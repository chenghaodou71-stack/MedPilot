"""Immutable FAISS index bundles and atomic active-version switching."""
from __future__ import annotations

import json
import hashlib
import os
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import faiss

from app.rag.index import INDEX_DIR, INDEX_NAME, EmbedFn, build_index, load_index, save_index

_VERSION_RE = re.compile(r"^[A-Za-z0-9._-]+$")
_POINTER = "active.json"


def _validate_version(version: str) -> str:
    if not version or not _VERSION_RE.fullmatch(version):
        raise ValueError("version must contain only letters, digits, '.', '_' or '-'")
    return version


def _version_dir(index_dir: Path, version: str) -> Path:
    return index_dir / "versions" / _validate_version(version)


async def build_version(
    embed_fn: EmbedFn,
    corpus: tuple[dict[str, str], ...],
    index_dir: Path = INDEX_DIR,
    *,
    version: str | None = None,
) -> dict:
    version = _validate_version(version or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    final_dir = _version_dir(index_dir, version)
    if final_dir.exists():
        raise FileExistsError(f"index version '{version}' already exists")

    versions_dir = final_dir.parent
    versions_dir.mkdir(parents=True, exist_ok=True)
    staging = versions_dir / f".staging-{uuid4().hex}"
    try:
        index, chunks = await build_index(embed_fn, corpus)
        staging.mkdir()
        save_index(index, chunks, staging, INDEX_NAME)
        (staging / "documents.json").write_text(
            json.dumps(list(corpus), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        manifest = {
            "version": version,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "document_count": len(corpus),
            "chunk_count": len(chunks),
        }
        (staging / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )

        loaded_index, loaded_chunks = load_index(staging, INDEX_NAME)
        if loaded_index.ntotal != len(loaded_chunks) or len(loaded_chunks) != len(chunks):
            raise ValueError("new index bundle failed validation")
        os.replace(staging, final_dir)
        return manifest
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def list_versions(index_dir: Path = INDEX_DIR) -> list[dict]:
    versions_dir = index_dir / "versions"
    if not versions_dir.exists():
        return []
    manifests: list[dict] = []
    for path in versions_dir.iterdir():
        if not path.is_dir() or path.name.startswith(".staging-"):
            continue
        manifest_file = path / "manifest.json"
        try:
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        manifests.append(manifest)
    return sorted(manifests, key=lambda item: item.get("created_at", ""), reverse=True)


def diff_versions(
    version: str,
    against: str,
    index_dir: Path = INDEX_DIR,
) -> dict[str, object]:
    """Compare immutable document snapshots without exposing full medical text."""
    def load_documents_snapshot(value: str) -> dict[str, dict]:
        path = _version_dir(index_dir, value) / "documents.json"
        if not path.is_file():
            raise FileNotFoundError(f"index version '{value}' does not exist")
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(f"index version '{value}' has invalid documents") from exc
        if not isinstance(payload, list):
            raise ValueError(f"index version '{value}' has invalid documents")
        return {
            str(item["doc_id"]): item
            for item in payload
            if isinstance(item, dict) and item.get("doc_id")
        }

    def fingerprint(item: dict) -> str:
        canonical = json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    current_docs = load_documents_snapshot(version)
    previous_docs = load_documents_snapshot(against)
    current_ids = set(current_docs)
    previous_ids = set(previous_docs)
    changed = sorted(
        doc_id for doc_id in current_ids & previous_ids
        if fingerprint(current_docs[doc_id]) != fingerprint(previous_docs[doc_id])
    )
    return {
        "version": version,
        "against": against,
        "added": sorted(current_ids - previous_ids),
        "removed": sorted(previous_ids - current_ids),
        "changed": changed,
        "unchanged": len(current_ids & previous_ids) - len(changed),
    }


def current_version(index_dir: Path = INDEX_DIR) -> str | None:
    pointer = index_dir / _POINTER
    if not pointer.exists():
        return None
    try:
        payload = json.loads(pointer.read_text(encoding="utf-8"))
        if payload.get("state") == "empty":
            return None
        version = payload["version"]
    except (OSError, json.JSONDecodeError, KeyError, AttributeError, TypeError) as exc:
        raise ValueError("active index pointer is invalid") from exc
    return _validate_version(version)


def active_index_marker(index_dir: Path = INDEX_DIR) -> str:
    """Return a cheap marker that changes whenever the selected index changes."""
    pointer = index_dir / _POINTER
    if pointer.is_file():
        try:
            payload = json.loads(pointer.read_text(encoding="utf-8"))
            if payload.get("state") == "empty":
                return "empty"
            return f"version:{_validate_version(payload['version'])}"
        except (OSError, json.JSONDecodeError, KeyError, AttributeError, TypeError, ValueError):
            return f"invalid:{pointer.stat().st_mtime_ns}"

    index_file = index_dir / f"{INDEX_NAME}.faiss"
    meta_file = index_dir / f"{INDEX_NAME}.meta.json"
    if index_file.is_file() and meta_file.is_file():
        return (
            f"legacy:{index_file.stat().st_mtime_ns}:{index_file.stat().st_size}:"
            f"{meta_file.stat().st_mtime_ns}:{meta_file.stat().st_size}"
        )
    return "missing"


def get_index_health(index_dir: Path = INDEX_DIR) -> dict:
    marker = active_index_marker(index_dir)
    if marker == "missing":
        return {"ok": False, "status": "missing", "version": None}
    if marker == "empty":
        return {"ok": False, "status": "empty", "version": "empty"}
    try:
        index, chunks, version = load_active_index(index_dir)
    except (FileNotFoundError, OSError, ValueError, RuntimeError):
        return {"ok": False, "status": "corrupt", "version": None}
    if index is None or not chunks:
        return {"ok": False, "status": "empty", "version": version}
    if index.ntotal != len(chunks):
        return {"ok": False, "status": "corrupt", "version": version}
    return {"ok": True, "status": "ready", "version": version}


def clear_active_index(index_dir: Path = INDEX_DIR) -> None:
    """Atomically select an explicit empty state, overriding legacy files."""
    index_dir.mkdir(parents=True, exist_ok=True)
    temp_pointer = index_dir / f".{_POINTER}.{uuid4().hex}.tmp"
    temp_pointer.write_text(json.dumps({"state": "empty"}), encoding="utf-8")
    os.replace(temp_pointer, index_dir / _POINTER)

    from app.rag.retriever import clear_cache
    clear_cache()


def activate_version(version: str, index_dir: Path = INDEX_DIR) -> dict:
    version_dir = _version_dir(index_dir, version)
    manifest_file = version_dir / "manifest.json"
    if not manifest_file.is_file():
        raise FileNotFoundError(f"index version '{version}' does not exist")
    try:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"index version '{version}' has an invalid manifest") from exc
    if manifest.get("version") != version:
        raise ValueError("index manifest version does not match directory")
    index, chunks = load_index(version_dir, INDEX_NAME)
    if index.ntotal != len(chunks) or len(chunks) != manifest.get("chunk_count"):
        raise ValueError(f"index version '{version}' is incomplete or corrupt")

    index_dir.mkdir(parents=True, exist_ok=True)
    temp_pointer = index_dir / f".{_POINTER}.{uuid4().hex}.tmp"
    temp_pointer.write_text(json.dumps({"version": version}), encoding="utf-8")
    os.replace(temp_pointer, index_dir / _POINTER)

    from app.rag.retriever import clear_cache
    clear_cache()
    return manifest


def load_active_index(
    index_dir: Path = INDEX_DIR,
) -> tuple[faiss.Index | None, list, str]:
    pointer = index_dir / _POINTER
    if pointer.is_file():
        try:
            payload = json.loads(pointer.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError("active index pointer is invalid") from exc
        if isinstance(payload, dict) and payload.get("state") == "empty":
            return None, [], "empty"

    version = current_version(index_dir)
    if version is not None:
        index, chunks = load_index(_version_dir(index_dir, version), INDEX_NAME)
        return index, chunks, version

    legacy_index = index_dir / f"{INDEX_NAME}.faiss"
    legacy_meta = index_dir / f"{INDEX_NAME}.meta.json"
    if legacy_index.is_file() and legacy_meta.is_file():
        index, chunks = load_index(index_dir, INDEX_NAME)
        return index, chunks, "legacy"
    raise FileNotFoundError("no active or legacy index is available")
