import asyncio

import pytest

from app.api import knowledge_routes
from app.api.knowledge_routes import IngestRequest


def governed_request(doc_id: str, text: str) -> IngestRequest:
    return IngestRequest(
        doc_id=doc_id,
        department="心血管内科",
        source="Test Institute | Reviewed triage guidance",
        institution="Test Institute",
        title="Reviewed triage guidance",
        url=f"https://example.org/medical/{doc_id}",
        published_date="2026-01-15",
        version="reviewed-2026-01-15",
        license="CC BY 4.0",
        review_status="approved",
        text=text,
    )


@pytest.mark.unit
async def test_concurrent_ingest_does_not_lose_an_update(monkeypatch):
    stored: list[dict] = []

    def load_documents():
        return [dict(doc) for doc in stored]

    def save_documents(docs):
        stored[:] = [dict(doc) for doc in docs]

    async def rebuild(docs):
        await asyncio.sleep(0.02)
        return {
            "version": f"v-{len(docs)}",
            "document_count": len(docs),
            "chunk_count": len(docs),
        }

    monkeypatch.setattr(knowledge_routes, "load_documents", load_documents)
    monkeypatch.setattr(knowledge_routes, "save_documents", save_documents)
    monkeypatch.setattr(knowledge_routes, "_rebuild_and_save", rebuild)

    await asyncio.gather(
        knowledge_routes.ingest(
            governed_request("one", "first reviewed guidance")
        ),
        knowledge_routes.ingest(
            governed_request("two", "second reviewed guidance")
        ),
    )

    assert {doc["doc_id"] for doc in stored} == {"one", "two"}
