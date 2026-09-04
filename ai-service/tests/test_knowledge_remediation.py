"""Knowledge governance, parsing state and retrieval-stat regression tests."""
from __future__ import annotations

import hashlib
from unittest.mock import AsyncMock, patch

import faiss
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.api import knowledge_routes
from app.api.knowledge_routes import IngestRequest
from app.rag.index import Chunk
from app.rag.retriever import get_retrieval_stats, reset_retrieval_stats, retrieve
from main import app


AUTH = {"X-MedPilot-Service-Token": "test-service-token"}
client = TestClient(app, headers=AUTH)

BASE_DOC = {
    "doc_id": "governed-doc",
    "department": "呼吸内科",
    "source": "测试来源",
    "source_type": "md",
    "institution": "MedPilot Test Institute",
    "title": "Reviewed respiratory guidance",
    "url": "https://example.org/respiratory",
    "published_date": "2026-01-15",
    "version": "2026-01-15",
    "license": "CC BY 4.0",
    "text": "  咳嗽需要记录持续时间。\r\n\r\n发热时应关注精神状态。  ",
}


@pytest.mark.unit
def test_source_type_accepts_backend_enum_case_and_normalizes_it():
    request = IngestRequest.model_validate({**BASE_DOC, "source_type": "PDF"})

    assert request.source_type == "pdf"


def _document_store(initial: list[dict] | None = None):
    stored = [dict(item) for item in (initial or [])]

    def load_documents():
        return [dict(item) for item in stored]

    def save_documents(docs):
        stored[:] = [dict(item) for item in docs]

    return stored, patch.object(knowledge_routes, "load_documents", load_documents), patch.object(
        knowledge_routes, "save_documents", save_documents
    )


@pytest.mark.unit
def test_ingest_forces_pending_and_recomputes_governance_fields():
    stored, load_patch, save_patch = _document_store()
    forged = {
        **BASE_DOC,
        "review_status": "approved",
        "reviewer": "forged-admin",
        "reviewed_at": "2026-01-01T00:00:00Z",
        "checksum": "0" * 64,
    }
    with load_patch, save_patch, patch.object(
        knowledge_routes, "_rebuild_and_save", new_callable=AsyncMock
    ) as rebuild:
        response = client.post("/knowledge/ingest", json=forged)

    assert response.status_code == 201
    body = response.json()
    normalized_text = "咳嗽需要记录持续时间。\n\n发热时应关注精神状态。"
    assert body["review_status"] == "pending"
    assert body["reviewer"] == ""
    assert body["reviewed_at"] == ""
    assert body["checksum"] == hashlib.sha256(normalized_text.encode("utf-8")).hexdigest()
    assert body["checksum"] != forged["checksum"]
    assert body["parsing_status"] == "completed"
    assert body["vector_status"] == "pending"
    assert body["failure_summary"] == ""
    assert body["active"] is False
    assert stored[0]["review_status"] == "pending"
    assert stored[0]["checksum"] == body["checksum"]
    rebuild.assert_not_awaited()


@pytest.mark.unit
def test_review_identity_is_required_in_trusted_header_and_rejected_in_body():
    pending = {
        **BASE_DOC,
        "review_status": "pending",
        "reviewer": "",
        "reviewed_at": "",
        "change_reason": "",
        "checksum": "a" * 64,
        "expires_at": "",
        "parsing_status": "completed",
        "vector_status": "pending",
        "failure_summary": "",
        "chunk_count": 0,
    }
    _, load_patch, save_patch = _document_store([pending])
    with load_patch, save_patch:
        missing_header = client.post(
            "/knowledge/docs/governed-doc/review",
            json={"action": "reject", "change_reason": "来源不足"},
        )
        forged_body = client.post(
            "/knowledge/docs/governed-doc/review",
            json={"action": "reject", "reviewer": "forged", "change_reason": "来源不足"},
            headers={**AUTH, "X-MedPilot-Reviewer": "real-reviewer"},
        )

    assert missing_header.status_code == 422
    assert forged_body.status_code == 422


@pytest.mark.unit
def test_review_uses_header_identity_and_records_vector_failure_without_leaking_error():
    pending = {
        **BASE_DOC,
        "review_status": "pending",
        "reviewer": "",
        "reviewed_at": "",
        "change_reason": "",
        "checksum": "a" * 64,
        "expires_at": "",
        "parsing_status": "completed",
        "vector_status": "pending",
        "failure_summary": "",
        "chunk_count": 0,
    }
    stored, load_patch, save_patch = _document_store([pending])
    with load_patch, save_patch, patch.object(
        knowledge_routes,
        "_rebuild_and_save",
        new_callable=AsyncMock,
        side_effect=RuntimeError("private embedding endpoint and token"),
    ):
        response = client.post(
            "/knowledge/docs/governed-doc/review",
            json={"action": "approve", "change_reason": "复核通过"},
            headers={**AUTH, "X-MedPilot-Reviewer": "reviewer-42"},
        )

    assert response.status_code == 503
    assert "private embedding endpoint" not in response.text
    assert stored[0]["reviewer"] == "reviewer-42"
    assert stored[0]["vector_status"] == "failed"
    assert stored[0]["failure_summary"] == "knowledge vectorization failed"


@pytest.mark.unit
async def test_retrieval_stats_count_nonblank_requests_and_hit_requests():
    reset_retrieval_stats()
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0]], dtype="float32"))
    chunks = [Chunk(
        chunk_id="resp#0",
        doc_id="resp",
        department="呼吸内科",
        source="test",
        text="咳嗽",
    )]

    async def hit_embed(_text: str) -> list[float]:
        return [1.0, 0.0]

    async def miss_embed(_text: str) -> list[float]:
        return [0.0, 1.0]

    assert await retrieve(
        "咳嗽", embed_fn=hit_embed, index=index, chunks=chunks, min_score=0.2
    )
    assert await retrieve(
        "无关", embed_fn=miss_embed, index=index, chunks=chunks, min_score=0.2
    ) == []
    assert await retrieve("   ", embed_fn=hit_embed, index=index, chunks=chunks) == []

    assert get_retrieval_stats() == {
        "retrieval_requests": 2,
        "retrieval_hits": 1,
        "hit_rate": 0.5,
    }


@pytest.mark.unit
def test_knowledge_stats_include_processing_states_and_retrieval_hit_rate():
    docs = [
        {**BASE_DOC, "doc_id": "ready", "review_status": "pending", "parsing_status": "completed", "vector_status": "pending"},
        {**BASE_DOC, "doc_id": "failed", "review_status": "rejected", "parsing_status": "failed", "vector_status": "failed", "failure_summary": "parse failed"},
    ]
    _, load_patch, save_patch = _document_store(docs)
    with load_patch, save_patch, patch.object(
        knowledge_routes,
        "get_retrieval_stats",
        return_value={"retrieval_requests": 4, "retrieval_hits": 3, "hit_rate": 0.75},
    ):
        response = client.get("/knowledge/stats")

    assert response.status_code == 200
    body = response.json()
    assert body["parsing_statuses"] == {"completed": 1, "failed": 1}
    assert body["vector_statuses"] == {"pending": 1, "failed": 1}
    assert body["retrieval_requests"] == 4
    assert body["retrieval_hits"] == 3
    assert body["hit_rate"] == 0.75
