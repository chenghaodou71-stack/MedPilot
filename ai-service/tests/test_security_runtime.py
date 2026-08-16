from pathlib import Path
from unittest.mock import AsyncMock, patch

import asyncio
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.knowledge_routes import IngestRequest
from app.api.monitor_routes import TraceRequest
from app.api.routes import ConsultRequest
from app.config import parse_cors_origins, require_service_token, validate_index_dir
from app.runtime import CapacityExceeded, InferenceGate
from main import app


AUTH = {"X-MedPilot-Service-Token": "test-service-token"}


def test_all_sensitive_route_groups_require_internal_token():
    client = TestClient(app)

    async def one_event(*_args, **_kwargs):
        yield {"type": "done", "status": "completed"}

    with (
        patch("app.api.routes.ollama_health", new_callable=AsyncMock, return_value={}),
        patch("app.api.routes.run_consult_stream", side_effect=one_event),
        patch("app.api.knowledge_routes.load_documents", return_value=[]),
        patch("app.api.monitor_routes.ollama_health", new_callable=AsyncMock, return_value={}),
        patch("app.api.monitor_routes.load_documents", return_value=[]),
    ):
        assert client.get("/health").status_code == 401
        assert client.post("/consult", json={"text": "test"}).status_code == 401
        assert client.get("/knowledge/docs").status_code == 401
        assert client.get("/monitor/health").status_code == 401


def test_valid_internal_token_is_accepted():
    client = TestClient(app, headers=AUTH)
    with patch("app.api.knowledge_routes.load_documents", return_value=[]):
        assert client.get("/knowledge/docs").status_code == 200


def test_health_failure_is_sanitized_and_uses_service_unavailable_status():
    client = TestClient(app, headers=AUTH)
    with (
        patch(
            "app.api.routes.ollama_health",
            new_callable=AsyncMock,
            side_effect=RuntimeError("private host and credentials"),
        ),
        patch(
            "app.api.routes.get_index_health",
            return_value={"ok": True, "status": "ready", "version": "v1"},
        ),
    ):
        response = client.get("/health")

    assert response.status_code == 503
    assert "private host and credentials" not in response.text
    assert response.json()["status"] == "degraded"


def test_readiness_requires_both_configured_models():
    client = TestClient(app, headers=AUTH)
    with (
        patch(
            "app.api.routes.ollama_health",
            new_callable=AsyncMock,
            return_value={"ollama": "online", "models": ["qwen2.5:7b"]},
        ),
        patch(
            "app.api.routes.get_index_health",
            return_value={"ok": True, "status": "ready", "version": "v1"},
        ),
    ):
        response = client.get("/health")

    assert response.status_code == 503
    assert response.json()["components"]["embedding_model"]["status"] == "missing"


def test_readiness_requires_a_loadable_index():
    client = TestClient(app, headers=AUTH)
    with (
        patch(
            "app.api.routes.ollama_health",
            new_callable=AsyncMock,
            return_value={
                "ollama": "online",
                "models": ["qwen2.5:7b", "bge-m3:latest"],
            },
        ),
        patch(
            "app.api.routes.get_index_health",
            return_value={"ok": False, "status": "missing", "version": None},
        ),
    ):
        response = client.get("/health")

    assert response.status_code == 503
    assert response.json()["components"]["knowledge_index"]["status"] == "missing"


def test_readiness_is_ok_when_ollama_models_and_index_are_ready():
    client = TestClient(app, headers=AUTH)
    with (
        patch(
            "app.api.routes.ollama_health",
            new_callable=AsyncMock,
            return_value={
                "ollama": "online",
                "models": ["qwen2.5:7b", "bge-m3:latest"],
            },
        ),
        patch(
            "app.api.routes.get_index_health",
            return_value={"ok": True, "status": "ready", "version": "v1"},
        ),
    ):
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert all(
        component["ok"] for component in response.json()["components"].values()
    )


def test_service_token_is_required_at_configuration_time():
    with pytest.raises(RuntimeError, match="MEDPILOT_AI_SERVICE_TOKEN"):
        require_service_token({})


def test_cors_is_disabled_by_default_and_rejects_wildcard():
    assert parse_cors_origins(None) == ()
    assert parse_cors_origins("http://localhost:5173, https://medpilot.example") == (
        "http://localhost:5173",
        "https://medpilot.example",
    )
    with pytest.raises(RuntimeError, match="wildcard"):
        parse_cors_origins("*")


def test_windows_index_directory_accepts_unicode_with_byte_serialization():
    assert validate_index_dir(
        Path("D:/\u6bd5\u8bbe\u5236\u4f5c/index"), os_name="nt"
    ) == Path("D:/\u6bd5\u8bbe\u5236\u4f5c/index")
    assert validate_index_dir(Path("C:/medpilot_index"), os_name="nt") == Path(
        "C:/medpilot_index"
    )


def test_api_models_enforce_blank_and_size_limits():
    with pytest.raises(ValidationError):
        TraceRequest(text="   ")
    with pytest.raises(ValidationError):
        ConsultRequest(text="x" * 4001)
    with pytest.raises(ValidationError):
        IngestRequest(doc_id="d", department="test", source="source", text="x" * 200001)


def test_request_content_length_guard_returns_413():
    client = TestClient(
        app,
        headers={**AUTH, "Content-Length": str(2 * 1024 * 1024)},
    )
    response = client.get("/knowledge/docs")
    assert response.status_code == 413
    assert response.json() == {"detail": "request body too large"}


def test_request_body_limit_uses_actual_bytes_not_only_content_length():
    client = TestClient(app)
    response = client.post(
        "/consult",
        content=b"x" * (1024 * 1024 + 1),
        headers={
            **AUTH,
            "Content-Type": "application/json",
            "Content-Length": "1",
        },
    )

    assert response.status_code == 413
    assert response.json() == {"detail": "request body too large"}


@pytest.mark.unit
async def test_inference_gate_rejects_excess_concurrency():
    gate = InferenceGate(max_concurrency=1, queue_timeout=0.01, request_timeout=1)
    async with gate.slot():
        with pytest.raises(CapacityExceeded):
            async with gate.slot():
                pass


@pytest.mark.unit
async def test_inference_gate_enforces_total_deadline():
    gate = InferenceGate(max_concurrency=1, queue_timeout=0.1, request_timeout=0.01)
    with pytest.raises(TimeoutError):
        async with gate.slot():
            await asyncio.sleep(0.03)
