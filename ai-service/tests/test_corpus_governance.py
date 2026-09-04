"""Governance checks for the repository-delivered medical corpus and index."""
from __future__ import annotations

import json
import re
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

import faiss
import numpy as np

from app.rag.corpus import CORPUS
from app.rag.index import INDEX_DIR, load_index, save_index


ALLOWED_DEPARTMENTS = {"呼吸内科", "消化内科", "心血管内科", "皮肤科"}
OFFICIAL_SOURCE_HOSTS = {
    "www.who.int",
    "www.nhlbi.nih.gov",
    "www.niddk.nih.gov",
    "www.niams.nih.gov",
    "medlineplus.gov",
    "www.nhs.uk",
}
OFFICIAL_INSTITUTION_HOSTS = {
    "World Health Organization": {"www.who.int"},
    "National Heart, Lung, and Blood Institute": {"www.nhlbi.nih.gov"},
    "National Institute of Diabetes and Digestive and Kidney Diseases": {
        "www.niddk.nih.gov",
    },
    "National Institute of Arthritis and Musculoskeletal and Skin Diseases": {
        "www.niams.nih.gov",
    },
    "U.S. National Library of Medicine": {"medlineplus.gov"},
    "National Health Service": {"www.nhs.uk"},
}
REQUIRED_METADATA = {
    "institution",
    "title",
    "url",
    "published_date",
    "version",
    "license",
    "review_status",
}
UNSAFE_CONTENT = re.compile(
    r"\d+(?:\.\d+)?\s*(?:mg|g|ml|毫克|克|毫升|片|粒|袋|单位)|"
    r"处方|口服|含服|肌注|静滴|自行停药|加量|减量",
    re.IGNORECASE,
)
INDEX_STORE = Path(__file__).resolve().parents[1] / "app" / "rag" / "index_store"


def test_builtin_corpus_is_scoped_traceable_and_non_prescriptive():
    assert CORPUS
    for document in CORPUS:
        assert document["department"] in ALLOWED_DEPARTMENTS
        assert REQUIRED_METADATA <= document.keys()
        assert document["institution"].strip()
        assert document["title"].strip()
        assert document["url"].startswith("https://")
        date.fromisoformat(document["published_date"])
        assert document["version"].strip()
        assert document["license"].strip()
        assert document["review_status"] == "approved"
        assert not UNSAFE_CONTENT.search(document["text"])


def test_builtin_corpus_has_at_least_one_hundred_unique_official_documents():
    assert len(CORPUS) >= 100
    assert len({document["doc_id"] for document in CORPUS}) == len(CORPUS)
    assert {
        urlparse(document["url"]).netloc
        for document in CORPUS
    } <= OFFICIAL_SOURCE_HOSTS
    for document in CORPUS:
        assert urlparse(document["url"]).netloc in OFFICIAL_INSTITUTION_HOSTS[
            document["institution"]
        ]


def test_delivered_documents_and_index_match_the_governed_corpus():
    documents = json.loads(
        (INDEX_STORE / "documents.json").read_text(encoding="utf-8")
    )
    metadata = json.loads(
        (INDEX_STORE / "medpilot.meta.json").read_text(encoding="utf-8")
    )
    index = faiss.deserialize_index(
        np.frombuffer((INDEX_STORE / "medpilot.faiss").read_bytes(), dtype="uint8")
    )

    assert documents == list(CORPUS)
    assert index.ntotal == len(metadata) > 0
    assert {chunk["doc_id"] for chunk in metadata} <= {
        document["doc_id"] for document in CORPUS
    }
    assert {chunk["department"] for chunk in metadata} <= ALLOWED_DEPARTMENTS
    assert all(not UNSAFE_CONTENT.search(chunk["text"]) for chunk in metadata)


def test_default_index_is_repository_local_and_unicode_paths_round_trip(tmp_path):
    assert INDEX_DIR == INDEX_STORE
    unicode_dir = tmp_path / "中文索引"
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0]], dtype="float32"))
    from app.rag.index import Chunk

    chunks = [
        Chunk(
            chunk_id="doc#0",
            doc_id="doc",
            department="呼吸内科",
            source="source",
            text="咳嗽需要评估。",
        )
    ]

    save_index(index, chunks, unicode_dir)
    loaded_index, loaded_chunks = load_index(unicode_dir)

    assert loaded_index.ntotal == 1
    assert loaded_chunks == chunks
