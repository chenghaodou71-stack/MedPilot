"""Knowledge draft management and immutable index-version APIs."""
from __future__ import annotations

import asyncio
import hashlib
import re
from datetime import date, datetime, timezone
from typing import Literal
from urllib.parse import urlparse

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, field_validator

from app.ollama_client import embed as ollama_embed
from app.rag.index import build_chunks
from app.rag.index_versions import (
    activate_version,
    build_version,
    clear_active_index,
    current_version,
    diff_versions,
    list_versions,
)
from app.rag.knowledge_store import load_documents, save_documents

router = APIRouter(prefix="/knowledge", tags=["knowledge"])
_mutation_lock = asyncio.Lock()
_UNSAFE_CONTENT = re.compile(
    r"\d+(?:\.\d+)?\s*(?:mg|g|ml|毫克|克|毫升|片|粒|袋|单位)|"
    r"处方|口服|含服|肌注|静滴|自行停药|加量|减量",
    re.IGNORECASE,
)

Department = Literal["呼吸内科", "消化内科", "心血管内科", "皮肤科"]


class IngestRequest(BaseModel):
    doc_id: str = Field(max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
    department: Department
    source: str = Field(max_length=1024)
    institution: str = Field(max_length=256)
    title: str = Field(max_length=512)
    url: str = Field(max_length=2048)
    published_date: str = Field(max_length=10)
    version: str = Field(max_length=256)
    license: str = Field(max_length=512)
    review_status: Literal["approved", "pending", "rejected"]
    text: str = Field(max_length=200_000)
    reviewer: str = Field(default="", max_length=128)
    reviewed_at: str = Field(default="", max_length=40)
    expires_at: str = Field(default="", max_length=10)
    change_reason: str = Field(default="", max_length=512)
    checksum: str = Field(default="", max_length=64)

    @field_validator(
        "doc_id",
        "source",
        "institution",
        "title",
        "url",
        "published_date",
        "version",
        "license",
        "text",
        "reviewer",
        "reviewed_at",
        "expires_at",
        "change_reason",
        "checksum",
    )
    @classmethod
    def non_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("field must not be empty")
        return value.strip()

    @field_validator("url")
    @classmethod
    def https_source_url(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme != "https" or not parsed.netloc:
            raise ValueError("source URL must use HTTPS")
        return value

    @field_validator("published_date")
    @classmethod
    def iso_published_date(cls, value: str) -> str:
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("published_date must be an ISO date") from exc
        return value

    @field_validator("expires_at")
    @classmethod
    def iso_expiry_date(cls, value: str) -> str:
        if not value:
            return value
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("expires_at must be an ISO date") from exc
        return value

    @field_validator("reviewed_at")
    @classmethod
    def iso_reviewed_at(cls, value: str) -> str:
        if not value:
            return value
        try:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("reviewed_at must be an ISO datetime") from exc
        return value

    @field_validator("text")
    @classmethod
    def non_prescriptive_text(cls, value: str) -> str:
        if _UNSAFE_CONTENT.search(value):
            raise ValueError("prescriptive or dosage content is not allowed")
        return value


def _as_corpus(docs: list[dict]) -> tuple[dict[str, str], ...]:
    return tuple(
        IngestRequest.model_validate(doc).model_dump(exclude_defaults=True)
        for doc in docs
    )


def _is_expired(doc: dict, *, today: date | None = None) -> bool:
    value = doc.get("expires_at") or ""
    if not value:
        return False
    return date.fromisoformat(value) < (today or date.today())


def _active_docs(docs: list[dict]) -> list[dict]:
    """Only reviewed, non-expired documents may enter a retrieval index."""
    return [
        doc for doc in docs
        if doc.get("review_status") == "approved" and not _is_expired(doc)
    ]


async def _rebuild_and_save(docs: list[dict]) -> dict:
    """Build and validate a draft version without changing the active pointer."""
    return await build_version(ollama_embed, _as_corpus(_active_docs(docs)))


@router.post("/ingest", status_code=201)
async def ingest(req: IngestRequest) -> dict:
    async with _mutation_lock:
        docs = load_documents()
        if any(doc["doc_id"] == req.doc_id for doc in docs):
            raise HTTPException(status_code=409, detail=f"doc_id '{req.doc_id}' already exists")
        new_doc = req.model_dump()
        new_doc["checksum"] = new_doc["checksum"] or hashlib.sha256(
            new_doc["text"].encode("utf-8")
        ).hexdigest()
        updated = [*docs, new_doc]
        manifest = (
            await _rebuild_and_save(updated)
            if new_doc["review_status"] == "approved" and not _is_expired(new_doc)
            else None
        )
        save_documents(updated)
    return {
        **new_doc,
        "chunks": manifest["chunk_count"] if manifest else 0,
        "version": manifest["version"] if manifest else None,
        "active": new_doc["review_status"] == "approved" and not _is_expired(new_doc),
    }


class ReviewRequest(BaseModel):
    action: Literal["approve", "reject"]
    reviewer: str = Field(min_length=1, max_length=128)
    change_reason: str = Field(default="", max_length=512)


@router.post("/docs/{doc_id}/review")
async def review_doc(doc_id: str, req: ReviewRequest) -> dict:
    """Move a document through the review gate before it can be indexed."""
    async with _mutation_lock:
        docs = load_documents()
        index = next((i for i, doc in enumerate(docs) if doc["doc_id"] == doc_id), None)
        if index is None:
            raise HTTPException(status_code=404, detail=f"doc_id '{doc_id}' not found")

        reviewed = dict(docs[index])
        reviewed["reviewer"] = req.reviewer.strip()
        reviewed["reviewed_at"] = datetime.now(timezone.utc).isoformat()
        reviewed["change_reason"] = req.change_reason.strip()
        reviewed["review_status"] = "approved" if req.action == "approve" else "rejected"
        updated = [*docs[:index], reviewed, *docs[index + 1:]]

        if req.action == "approve":
            manifest = await _rebuild_and_save(updated)
        else:
            manifest = None
        save_documents(updated)

    return {
        **reviewed,
        "active": reviewed["review_status"] == "approved" and not _is_expired(reviewed),
        "version": manifest["version"] if manifest else None,
    }


@router.delete("/{doc_id}", status_code=200)
async def delete_doc(doc_id: str) -> dict:
    async with _mutation_lock:
        docs = load_documents()
        remaining = [doc for doc in docs if doc["doc_id"] != doc_id]
        if len(remaining) == len(docs):
            raise HTTPException(status_code=404, detail=f"doc_id '{doc_id}' not found")

        active_remaining = _active_docs(remaining)
        manifest = await _rebuild_and_save(remaining) if active_remaining else None
        if not active_remaining:
            clear_active_index()
        save_documents(remaining)
    return {
        "deleted": doc_id,
        "remaining": len(remaining),
        "version": manifest["version"] if manifest else None,
    }


@router.get("/docs")
def list_docs() -> dict:
    docs = load_documents()
    result = []
    for doc in docs:
        chunks = build_chunks((_as_corpus([doc])[0],))
        result.append({
            "doc_id": doc["doc_id"],
            "department": doc["department"],
            "source": doc["source"],
            "institution": doc["institution"],
            "title": doc["title"],
            "url": doc["url"],
            "published_date": doc["published_date"],
            "version": doc["version"],
            "license": doc["license"],
            "review_status": doc["review_status"],
            "reviewer": doc.get("reviewer", ""),
            "reviewed_at": doc.get("reviewed_at", ""),
            "expires_at": doc.get("expires_at", ""),
            "change_reason": doc.get("change_reason", ""),
            "checksum": doc.get("checksum", ""),
            "expired": _is_expired(doc),
            "active": doc.get("review_status") == "approved" and not _is_expired(doc),
            "chunk_count": len(chunks),
            "text_preview": doc["text"][:80] + ("…" if len(doc["text"]) > 80 else ""),
        })
    return {"docs": result, "total": len(result)}


@router.get("/stats")
def stats() -> dict:
    docs = load_documents()
    active = _active_docs(docs)
    all_chunks = build_chunks(_as_corpus(active)) if active else []
    department_counts: dict[str, int] = {}
    for chunk in all_chunks:
        department_counts[chunk.department] = department_counts.get(chunk.department, 0) + 1
    return {
        "total_docs": len(docs),
        "active_docs": len(active),
        "pending_docs": sum(doc.get("review_status") == "pending" for doc in docs),
        "rejected_docs": sum(doc.get("review_status") == "rejected" for doc in docs),
        "expired_docs": sum(_is_expired(doc) for doc in docs),
        "total_chunks": len(all_chunks),
        "departments": department_counts,
        "active_version": current_version(),
    }


@router.get("/versions")
def versions() -> dict:
    active = current_version()
    manifests = [
        {**manifest, "active": manifest.get("version") == active}
        for manifest in list_versions()
    ]
    return {"current": active, "versions": manifests}


@router.post("/versions/build", status_code=201)
async def build_draft_version() -> dict:
    docs = load_documents()
    if not _active_docs(docs):
        raise HTTPException(status_code=409, detail="knowledge draft has no approved documents")
    return await _rebuild_and_save(docs)


@router.post("/versions/{version}/activate")
def activate(version: str) -> dict:
    try:
        manifest = activate_version(version)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {"active": version, "manifest": manifest}


@router.get("/versions/{version}/diff")
def version_diff(version: str, against: str) -> dict:
    try:
        return diff_versions(version, against)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
