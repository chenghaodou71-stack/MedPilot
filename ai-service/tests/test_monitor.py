"""Tests for /monitor endpoints (mocks Ollama and graph execution)."""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from main import app

client = TestClient(
    app,
    headers={"X-MedPilot-Service-Token": "test-service-token"},
)


def _mock_ollama_ok():
    return patch(
        "app.api.monitor_routes.ollama_health",
        new_callable=AsyncMock,
        return_value={
            "ollama": "online",
            "models": ["qwen2.5:7b", "bge-m3:latest"],
        },
    )


def _mock_ollama_fail():
    return patch(
        "app.api.monitor_routes.ollama_health",
        new_callable=AsyncMock,
        side_effect=Exception("connection refused"),
    )


def _mock_docs(n: int):
    return patch(
        "app.api.monitor_routes.load_documents",
        return_value=[{"doc_id": f"doc-{i}"} for i in range(n)],
    )


def _mock_index_ok():
    return patch(
        "app.api.monitor_routes.get_index_health",
        return_value={"ok": True, "status": "ready", "version": "v1"},
    )


class TestHealth:
    def test_health_ollama_ok(self):
        with _mock_ollama_ok(), _mock_docs(3), _mock_index_ok():
            resp = client.get("/monitor/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["ollama"]["ok"] is True
        assert body["knowledge"]["docs"] == 3

    def test_health_ollama_fail(self):
        with _mock_ollama_fail(), _mock_docs(0), _mock_index_ok():
            resp = client.get("/monitor/health")
        assert resp.status_code == 503
        body = resp.json()
        assert body["ollama"]["ok"] is False
        assert "connection refused" not in resp.text

    def test_health_is_unready_when_a_configured_model_is_missing(self):
        with patch(
            "app.api.monitor_routes.ollama_health",
            new_callable=AsyncMock,
            return_value={"ollama": "online", "models": ["qwen2.5:7b"]},
        ), _mock_docs(0), _mock_index_ok():
            resp = client.get("/monitor/health")

        assert resp.status_code == 503
        assert resp.json()["models"]["embedding"]["status"] == "missing"

    def test_health_sessions_count(self):
        with _mock_ollama_ok(), _mock_docs(0), _mock_index_ok():
            from app.session import store
            with patch.object(store, "count", return_value=5):
                resp = client.get("/monitor/health")
        assert resp.json()["sessions"]["active"] == 5


MOCK_EVENTS = [
    {"type": "node", "node": "extract", "label": "症状采集",
     "data": {"symptoms": {"symptoms": ["头痛"], "red_flags": [], "duration": None, "severity": None}}},
    {"type": "node", "node": "retrieve", "label": "知识检索", "data": {"evidence": []}},
    {"type": "node", "node": "classify", "label": "辅助分诊",
     "data": {"triage": {"department": "神经内科", "risk_level": "中",
                         "confidence": 0.8, "urgency": "建议尽快就诊", "matched_rule": None}}},
    {"type": "node", "node": "compose", "label": "回答编排",
     "data": {"answer": {"text": "建议就诊神经内科", "citations": [], "safety_boundary": ""}}},
    {"type": "done"},
]


async def _fake_stream(text, **_):
    for evt in MOCK_EVENTS:
        yield dict(evt)


class TestTrace:
    def test_trace_preserves_graph_measured_elapsed_ms(self):
        async def measured_stream(text, **_):
            yield {"type": "node", "node": "extract", "elapsed_ms": 37, "data": {}}
            yield {"type": "done", "elapsed_ms": 0, "data": {}}

        with patch("app.api.monitor_routes.run_consult_stream", side_effect=measured_stream):
            resp = client.post("/monitor/trace", json={"text": "头痛"})

        import json
        events = [json.loads(ln) for ln in resp.text.strip().split("\n") if ln]
        assert events[0]["elapsed_ms"] == 37

    def test_trace_streams_nodes(self):
        with patch("app.api.monitor_routes.run_consult_stream", side_effect=_fake_stream):
            resp = client.post("/monitor/trace", json={"text": "头痛"})
        assert resp.status_code == 200
        lines = [ln for ln in resp.text.strip().split("\n") if ln]
        import json
        events = [json.loads(ln) for ln in lines]
        node_events = [e for e in events if e.get("type") == "node"]
        assert len(node_events) == 4
        assert node_events[0]["node"] == "extract"
        assert all("elapsed_ms" in e for e in node_events)

    def test_trace_elapsed_ms_present(self):
        with patch("app.api.monitor_routes.run_consult_stream", side_effect=_fake_stream):
            resp = client.post("/monitor/trace", json={"text": "胸痛"})
        import json
        events = [json.loads(ln) for ln in resp.text.strip().split("\n") if ln]
        for e in events:
            assert "elapsed_ms" in e

    def test_trace_empty_text_returns_422(self):
        resp = client.post("/monitor/trace", json={})
        assert resp.status_code == 422
