"""Immutable index bundle and atomic active pointer tests."""
from __future__ import annotations

import json

import pytest

from app.rag.index_versions import (
    activate_version,
    build_version,
    clear_active_index,
    current_version,
    diff_versions,
    list_versions,
    load_active_index,
)

CORPUS = (
    {
        "doc_id": "doc-1",
        "department": "呼吸内科",
        "source": "测试指南",
        "text": "咳嗽伴发热应评估。",
    },
)


async def fake_embed(text: str) -> list[float]:
    return [1.0, float(len(text) % 3 + 1)]


@pytest.mark.unit
async def test_build_creates_immutable_bundle_without_switching(tmp_path):
    manifest = await build_version(fake_embed, CORPUS, tmp_path, version="v1")

    version_dir = tmp_path / "versions" / "v1"
    assert manifest["version"] == "v1"
    assert manifest["document_count"] == 1
    assert manifest["chunk_count"] == 1
    assert (version_dir / "medpilot.faiss").is_file()
    assert (version_dir / "medpilot.meta.json").is_file()
    assert json.loads((version_dir / "manifest.json").read_text(encoding="utf-8")) == manifest
    assert current_version(tmp_path) is None

    with pytest.raises(FileExistsError):
        await build_version(fake_embed, CORPUS, tmp_path, version="v1")


@pytest.mark.unit
async def test_activate_uses_atomic_pointer_and_loads_version(tmp_path):
    await build_version(fake_embed, CORPUS, tmp_path, version="v1")

    manifest = activate_version("v1", tmp_path)
    index, chunks, version = load_active_index(tmp_path)

    assert manifest["version"] == "v1"
    assert current_version(tmp_path) == "v1"
    assert version == "v1"
    assert index.ntotal == len(chunks) == 1
    assert [item["version"] for item in list_versions(tmp_path)] == ["v1"]


@pytest.mark.unit
async def test_failed_build_keeps_active_pointer(tmp_path):
    await build_version(fake_embed, CORPUS, tmp_path, version="stable")
    activate_version("stable", tmp_path)

    async def failing_embed(_text: str) -> list[float]:
        raise RuntimeError("embedding failed")

    with pytest.raises(RuntimeError, match="embedding failed"):
        await build_version(failing_embed, CORPUS, tmp_path, version="broken")

    assert current_version(tmp_path) == "stable"
    assert not (tmp_path / "versions" / "broken").exists()


@pytest.mark.unit
def test_legacy_files_are_loaded_as_legacy(tmp_path):
    from app.rag.index import build_index, save_index
    import asyncio

    index, chunks = asyncio.run(build_index(fake_embed, CORPUS))
    save_index(index, chunks, tmp_path)

    _, loaded_chunks, version = load_active_index(tmp_path)

    assert loaded_chunks
    assert version == "legacy"


@pytest.mark.unit
async def test_clear_active_index_creates_explicit_empty_state(tmp_path):
    await build_version(fake_embed, CORPUS, tmp_path, version="v1")
    activate_version("v1", tmp_path)

    clear_active_index(tmp_path)
    index, chunks, version = load_active_index(tmp_path)

    assert current_version(tmp_path) is None
    assert index is None
    assert chunks == []
    assert version == "empty"


@pytest.mark.unit
async def test_diff_versions_reports_added_removed_and_changed_documents(tmp_path):
    await build_version(fake_embed, CORPUS, tmp_path, version="v1")
    updated = (
        {**CORPUS[0], "text": "咳嗽持续加重应及时线下评估。"},
        {"doc_id": "doc-2", "department": "皮肤科", "source": "测试", "text": "皮疹资料。"},
    )
    await build_version(fake_embed, updated, tmp_path, version="v2")

    diff = diff_versions("v2", "v1", tmp_path)

    assert diff["added"] == ["doc-2"]
    assert diff["removed"] == []
    assert diff["changed"] == ["doc-1"]
