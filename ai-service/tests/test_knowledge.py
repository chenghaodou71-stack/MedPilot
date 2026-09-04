"""Integration tests for /knowledge endpoints (mocks Ollama + file I/O)."""
from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.api.knowledge_routes import _as_corpus
from main import app

client = TestClient(
    app,
    headers={"X-MedPilot-Service-Token": "test-service-token"},
)

SAMPLE_DOC = {
    "doc_id": "test-doc",
    "department": "心血管内科",
    "source": "测试来源",
    "institution": "MedPilot Test Institute",
    "title": "Reviewed cardiovascular triage guidance",
    "url": "https://example.org/medical/cardio-guidance",
    "published_date": "2026-01-15",
    "version": "reviewed-2026-01-15",
    "license": "CC BY 4.0",
    "review_status": "approved",
    "text": "这是一段测试医学文本。用于验证知识库录入流程是否正常工作。",
}


def _mock_rebuild(docs_file: Path):
    """Return a patch context that mocks _rebuild_and_save and file I/O."""
    return patch(
        "app.api.knowledge_routes._rebuild_and_save",
        new_callable=AsyncMock,
        return_value={
            "version": "test-v1",
            "document_count": 1,
            "chunk_count": 3,
            "created_at": "2026-07-30T00:00:00+00:00",
        },
    )


def _with_tmp_docs(tmp_path: Path, docs: list[dict]):
    """Patch load_documents / save_documents to use tmp_path."""
    docs_file = tmp_path / "documents.json"
    if docs:
        docs_file.write_text(json.dumps(docs, ensure_ascii=False), encoding="utf-8")

    def _load(f=None):
        p = f or docs_file
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else []

    def _save(d, f=None):
        p = f or docs_file
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")

    return (
        patch("app.api.knowledge_routes.load_documents", side_effect=_load),
        patch("app.api.knowledge_routes.save_documents", side_effect=_save),
    )


class TestIngest:
    def test_ingest_new_doc(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [])
        with load_p, save_p, _mock_rebuild(tmp_path):
            resp = client.post("/knowledge/ingest", json=SAMPLE_DOC)
        assert resp.status_code == 201
        body = resp.json()
        assert body["doc_id"] == "test-doc"
        assert body["review_status"] == "pending"
        assert body["chunks"] == 0
        assert body["version"] is None

    def test_ingest_duplicate_returns_409(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [SAMPLE_DOC])
        with load_p, save_p, _mock_rebuild(tmp_path):
            resp = client.post("/knowledge/ingest", json=SAMPLE_DOC)
        assert resp.status_code == 409

    def test_ingest_empty_field_returns_422(self):
        resp = client.post("/knowledge/ingest", json={**SAMPLE_DOC, "text": ""})
        assert resp.status_code == 422

    def test_ingest_missing_field_returns_422(self):
        resp = client.post("/knowledge/ingest", json={"doc_id": "x", "department": "y"})
        assert resp.status_code == 422

    @pytest.mark.parametrize(
        "updates",
        [
            {"department": "神经内科"},
            {"url": "http://example.org/insecure"},
            {"published_date": "2026/01/15"},
            {"text": "建议口服某药，每次 5mg。"},
        ],
    )
    def test_ingest_rejects_ungoverned_content(self, updates):
        resp = client.post("/knowledge/ingest", json={**SAMPLE_DOC, **updates})

        assert resp.status_code == 422

    def test_ingest_requires_all_traceability_metadata(self):
        for field in (
            "institution",
            "title",
            "url",
            "published_date",
            "version",
            "license",
        ):
            payload = dict(SAMPLE_DOC)
            payload.pop(field)

            assert client.post("/knowledge/ingest", json=payload).status_code == 422

    def test_corpus_conversion_preserves_governance_metadata(self):
        assert _as_corpus([SAMPLE_DOC]) == (SAMPLE_DOC,)

    def test_ingest_pending_doc_waits_for_review(self, tmp_path):
        pending = {**SAMPLE_DOC, "doc_id": "pending-doc", "review_status": "pending"}
        load_p, save_p = _with_tmp_docs(tmp_path, [])
        with load_p, save_p, _mock_rebuild(tmp_path):
            resp = client.post("/knowledge/ingest", json=pending)

        assert resp.status_code == 201
        assert resp.json()["review_status"] == "pending"

    def test_review_approve_updates_document_and_builds_version(self, tmp_path):
        pending = {**SAMPLE_DOC, "review_status": "pending"}
        load_p, save_p = _with_tmp_docs(tmp_path, [pending])
        with load_p, save_p, _mock_rebuild(tmp_path):
            resp = client.post(
                "/knowledge/docs/test-doc/review",
                json={"action": "approve", "change_reason": "复核通过"},
                headers={"X-MedPilot-Reviewer": "admin"},
            )

        assert resp.status_code == 200
        assert resp.json()["review_status"] == "approved"
        assert resp.json()["reviewer"] == "admin"

    def test_review_reject_updates_document_without_building_index(self, tmp_path):
        pending = {**SAMPLE_DOC, "review_status": "pending"}
        load_p, save_p = _with_tmp_docs(tmp_path, [pending])
        with load_p, save_p, patch(
            "app.api.knowledge_routes._rebuild_and_save", new_callable=AsyncMock
        ) as rebuild:
            resp = client.post(
                "/knowledge/docs/test-doc/review",
                json={"action": "reject", "change_reason": "来源不足"},
                headers={"X-MedPilot-Reviewer": "admin"},
            )

        assert resp.status_code == 200
        assert resp.json()["review_status"] == "rejected"
        rebuild.assert_not_called()


class TestDelete:
    def test_delete_existing_doc(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [SAMPLE_DOC])
        index_dir = tmp_path / "runtime-index"
        index_dir.mkdir()
        (index_dir / "medpilot.faiss").write_bytes(b"test-index")
        (index_dir / "medpilot.meta.json").write_text("[]", encoding="utf-8")
        with load_p, save_p, _mock_rebuild(tmp_path), patch(
            "app.api.knowledge_routes.clear_active_index"
        ) as clear_active:
            resp = client.delete("/knowledge/test-doc")
        assert resp.status_code == 200
        assert resp.json()["deleted"] == "test-doc"
        assert resp.json()["remaining"] == 0
        clear_active.assert_called_once_with()
        assert (index_dir / "medpilot.faiss").read_bytes() == b"test-index"
        assert (index_dir / "medpilot.meta.json").is_file()

    def test_failed_rebuild_does_not_change_documents(self, tmp_path):
        other_doc = {**SAMPLE_DOC, "doc_id": "other-doc"}
        load_p, save_p = _with_tmp_docs(tmp_path, [SAMPLE_DOC, other_doc])
        with load_p, save_p as save_mock, patch(
            "app.api.knowledge_routes._rebuild_and_save",
            new_callable=AsyncMock,
            side_effect=RuntimeError("embedding failed"),
        ), pytest.raises(RuntimeError, match="embedding failed"):
            client.delete("/knowledge/test-doc")
        save_mock.assert_not_called()

    def test_delete_nonexistent_returns_404(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [])
        with load_p, save_p:
            resp = client.delete("/knowledge/nonexistent")
        assert resp.status_code == 404


class TestListAndStats:
    def test_list_docs_empty(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [])
        with load_p, save_p:
            resp = client.get("/knowledge/docs")
        assert resp.status_code == 200
        assert resp.json()["total"] == 0

    def test_list_docs_returns_preview(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [SAMPLE_DOC])
        with load_p, save_p:
            resp = client.get("/knowledge/docs")
        assert resp.status_code == 200
        docs = resp.json()["docs"]
        assert len(docs) == 1
        assert docs[0]["doc_id"] == "test-doc"
        assert "text_preview" in docs[0]
        assert docs[0]["chunk_count"] >= 1

    def test_stats_empty(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [])
        with load_p, save_p:
            resp = client.get("/knowledge/stats")
        assert resp.status_code == 200
        body = resp.json()
        assert body["total_docs"] == 0
        assert body["total_chunks"] == 0

    def test_stats_counts_chunks(self, tmp_path):
        load_p, save_p = _with_tmp_docs(tmp_path, [SAMPLE_DOC])
        with load_p, save_p:
            resp = client.get("/knowledge/stats")
        assert resp.status_code == 200
        body = resp.json()
        assert body["total_docs"] == 1
        assert body["total_chunks"] >= 1
        assert "心血管内科" in body["departments"]
