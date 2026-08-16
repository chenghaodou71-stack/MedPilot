import json

from app.rag.knowledge_store import load_documents, save_documents


def test_document_store_uses_atomic_replace(tmp_path):
    docs_file = tmp_path / "documents.json"
    docs = [{"doc_id": "one", "department": "test", "source": "test", "text": "text"}]

    save_documents(docs, docs_file)

    assert load_documents(docs_file) == docs
    assert json.loads(docs_file.read_text(encoding="utf-8")) == docs
    assert list(tmp_path.glob("*.tmp")) == []
