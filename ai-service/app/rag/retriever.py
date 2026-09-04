"""RAG 检索模块：async retrieve(query, topK) → RankedEvidence[]。

#04 做实：bge-m3 向量化 query → 在持久化 FAISS 索引上做归一化内积检索（=余弦相似度）。
契约 RankedEvidence 与 #03 保持不变，仅由同步改为异步（需 embed query）。
embed_fn 可注入，测试用假向量 + 内存索引保证离线确定性。"""
from __future__ import annotations

import os
import threading
from collections.abc import Awaitable, Callable

import faiss
import numpy as np

from app.ollama_client import embed as ollama_embed
from app.rag.index import Chunk
from app.rag.index_versions import active_index_marker, load_active_index
from app.rag.entity_normalizer import expand_query_with_aliases
from app.schemas import RankedEvidence

EmbedFn = Callable[[str], Awaitable[list[float]]]

# 进程级缓存，避免每次检索重复读盘
_INDEX: faiss.Index | None = None
_CHUNKS: list[Chunk] | None = None
_INDEX_VERSION: str | None = None
_INDEX_MARKER: str | None = None
_STATS_LOCK = threading.Lock()
_RETRIEVAL_REQUESTS = 0
_RETRIEVAL_HITS = 0


def reset_retrieval_stats() -> None:
    global _RETRIEVAL_REQUESTS, _RETRIEVAL_HITS
    with _STATS_LOCK:
        _RETRIEVAL_REQUESTS = 0
        _RETRIEVAL_HITS = 0


def get_retrieval_stats() -> dict[str, int | float]:
    with _STATS_LOCK:
        requests = _RETRIEVAL_REQUESTS
        hits = _RETRIEVAL_HITS
    return {
        "retrieval_requests": requests,
        "retrieval_hits": hits,
        "hit_rate": round(hits / requests, 4) if requests else 0.0,
    }


def _record_request() -> None:
    global _RETRIEVAL_REQUESTS
    with _STATS_LOCK:
        _RETRIEVAL_REQUESTS += 1


def _record_hit() -> None:
    global _RETRIEVAL_HITS
    with _STATS_LOCK:
        _RETRIEVAL_HITS += 1


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
    vector_weight: float | None = None,
) -> list[RankedEvidence]:
    """向量检索。index/chunks 可注入（测试用），否则用持久化索引。"""
    if not query.strip():
        return []
    _record_request()
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

    if vector_weight is None:
        try:
            vector_weight = float(os.getenv("MEDPILOT_RAG_VECTOR_WEIGHT", "0.8"))
        except ValueError as exc:
            raise RuntimeError("MEDPILOT_RAG_VECTOR_WEIGHT must be a number") from exc
    if not 0.0 <= vector_weight <= 1.0:
        raise RuntimeError("MEDPILOT_RAG_VECTOR_WEIGHT must be between 0 and 1")
    lexical_weight = 1.0 - vector_weight

    retrieval_query = expand_query_with_aliases(query)
    vec = np.array([await embed_fn(retrieval_query)], dtype="float32")
    faiss.normalize_L2(vec)
    # Retrieve a wider dense candidate pool, then combine it with lexical
    # overlap. This lets an exact symptom phrase rescue a semantically weaker
    # embedding result without changing the public evidence contract.
    k = min(max(top_k * 4, top_k), len(chunks))
    scores, ids = index.search(vec, k)

    candidates: list[tuple[float, float, int]] = []
    for score, idx in zip(scores[0], ids[0]):
        if idx < 0:
            continue
        dense_score = float(score)
        lexical_score = _lexical_overlap(retrieval_query, chunks[idx].text)
        combined_score = vector_weight * dense_score + lexical_weight * lexical_score
        if combined_score < min_score:
            continue
        candidates.append((combined_score, dense_score, int(idx)))

    results: list[RankedEvidence] = []
    for combined_score, _dense_score, idx in sorted(
        candidates, key=lambda item: (-item[0], item[2])
    )[:top_k]:
        c = chunks[idx]
        results.append(RankedEvidence(
            citation_id=c.chunk_id,
            doc_id=c.doc_id,
            chunk_id=c.chunk_id,
            department=c.department,
            source=c.source,
            source_type=c.source_type,
            quote=c.text,
            score=round(float(combined_score), 4),
            index_version=index_version,
            institution=c.institution,
            title=c.title,
            url=c.url,
            published_date=c.published_date,
            version=c.version,
            license=c.license,
            review_status=c.review_status,
        ))
    if results:
        _record_hit()
    return results


def _lexical_overlap(query: str, text: str) -> float:
    """Character n-gram overlap in [0, 1], suitable for short Chinese text."""
    query_grams = _char_ngrams(query)
    text_grams = _char_ngrams(text)
    if not query_grams or not text_grams:
        return 0.0
    return len(query_grams & text_grams) / len(query_grams)


def _char_ngrams(value: str) -> set[str]:
    compact = "".join(value.casefold().split())
    if not compact:
        return set()
    if len(compact) == 1:
        return {compact}
    return {compact[index:index + 2] for index in range(len(compact) - 1)}
