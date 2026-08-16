"""Persistent document store for dynamic knowledge management.

Documents are stored as JSON alongside the FAISS index files.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from uuid import uuid4

DOCS_FILE = Path(__file__).resolve().parent / "index_store" / "documents.json"


def load_documents(docs_file: Path = DOCS_FILE) -> list[dict]:
    if not docs_file.exists():
        return []
    return json.loads(docs_file.read_text(encoding="utf-8"))


def save_documents(docs: list[dict], docs_file: Path = DOCS_FILE) -> None:
    docs_file.parent.mkdir(parents=True, exist_ok=True)
    temp_file = docs_file.with_name(f".{docs_file.name}.{uuid4().hex}.tmp")
    temp_file.write_text(
        json.dumps(docs, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    os.replace(temp_file, docs_file)
