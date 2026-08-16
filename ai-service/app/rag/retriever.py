"""RAG 检索模块：async retrieve(query, topK) → RankedEvidence[]。

#04 做实：bge-m3 向量化 query → 在持久化 FAISS 索引上做归一化内积检索（=余弦相似度）。
契约 RankedEvidence 与 #03 保持不变，仅由同步改为异步（需 embed query）。
embed_fn 可注入，测试用假向量 + 内存索引保证离线确定性。"""
from __future__ import annotations

import os
from collections.abc import Awaitable, Callable

import faiss
import numpy as np

from app.ollama_client import embed as ollama_embed
from app.rag.index import Chunk
from app.rag.index_versions import active_index_marker, load_active_index
from app.schemas import RankedEvidence

EmbedFn = Callable[[str], Awaitable[list[float]]]

# 进程级缓存，避免每次检索重复读盘
_INDEX: faiss.Index | None = None
_CHUNKS: list[Chunk] | None = None
_INDEX_VERSION: str | None = None
_INDEX_MARKER: str | None = None


def _get_index() -> tuple[faiss.Index | None, list[Chunk], str]:
    global _INDEX, _CHUNKS, _INDEX_VERSION, _INDEX_MARKER
    marker = active_index_marker()
    if _CHUNKS is None or _INDEX_VERSION is None or marker != _INDEX_MARKER:
        _INDEX, _CHUNKS, _INDEX_VERSION = load_active_index()
        _INDEX_MARKER = marker
    return _INDEX, _CHUNKS, _INDEX_VERSION


def clear_cache() -> None:
    global _INDEX, _CHUNKS, _INDEX_VERSION, _INDEX_MARKER
    _INDEX = None
    _CHUNKS = None
    _INDEX_VERSION = None
    _INDEX_MARKER = None


async def retrieve(
    query: str,
    top_k: int = 3,
    embed_fn: EmbedFn = ollama_embed,
    index: faiss.Index | None = None,
    chunks: list[Chunk] | None = None,
    index_version: str | None = None,
    min_score: float | None = None,
) -> list[RankedEvidence]:
    """向量检索。index/chunks 可注入（测试用），否则用持久化索引。"""
    if not query.strip():
        return []
    if index is None or chunks is None:
        index, chunks, index_version = _get_index()
    else:
        index_version = index_version or "in-memory"

    if index is None or not chunks:
        return []

    if min_score is None:
        try:
            min_score = float(os.getenv("MEDPILOT_RAG_MIN_SCORE", "0.35"))
        except ValueError as exc:
            raise RuntimeError("MEDPILOT_RAG_MIN_SCORE must be a number") from exc
        if not -1.0 <= min_score <= 1.0:
            raise RuntimeError("MEDPILOT_RAG_MIN_SCORE must be between -1 and 1")

    vec = np.array([await embed_fn(query)], dtype="float32")
    faiss.normalize_L2(vec)
    k = min(top_k, len(chunks))
    scores, ids = index.search(vec, k)

    results: list[RankedEvidence] = []
    for score, idx in zip(scores[0], ids[0]):
        if idx < 0 or float(score) < min_score:
            continue
        c = chunks[idx]
        results.append(RankedEvidence(
            citation_id=c.chunk_id,
            doc_id=c.doc_id,
            chunk_id=c.chunk_id,
            department=c.department,
            source=c.source,
            quote=c.text,
            score=round(float(score), 4),
            index_version=index_version,
            institution=c.institution,
            title=c.title,
            url=c.url,
            published_date=c.published_date,
            version=c.version,
            license=c.license,
            review_status=c.review_status,
        ))
    return results
