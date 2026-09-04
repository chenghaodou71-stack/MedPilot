"""RAG 检索模块契约测试。基于固定小型内存索引 + 假向量，离线确定性。

假 embed 把每段文本映射到一个由关键词决定的确定性向量，
使得语义相近的 query 与 chunk 在向量空间中接近，验证命中与排序。"""
import faiss
import numpy as np
import pytest

from app.rag.index import Chunk, build_index
from app.rag.retriever import retrieve

# 三维语义轴：[心血管, 呼吸, 皮肤]
_AXES = {
    "胸痛": [1.0, 0.0, 0.0],
    "心悸": [0.9, 0.0, 0.0],
    "呼吸困难": [0.0, 1.0, 0.0],
    "咳嗽": [0.0, 0.9, 0.0],
    "皮疹": [0.0, 0.0, 1.0],
    "红斑": [0.0, 0.0, 0.9],
}


def _fake_vec(text: str) -> list[float]:
    vec = np.zeros(3, dtype="float32")
    for kw, axis in _AXES.items():
        if kw in text:
            vec += np.array(axis, dtype="float32")
    if not vec.any():
        vec += 0.01  # 避免零向量
    return vec.tolist()


async def _fake_embed(text: str) -> list[float]:
    return _fake_vec(text)


TEST_CORPUS = (
    {"doc_id": "c1", "department": "心血管内科", "source": "心血管", "text": "胸痛心悸。"},
    {"doc_id": "r1", "department": "呼吸内科", "source": "呼吸", "text": "呼吸困难咳嗽。"},
    {"doc_id": "d1", "department": "皮肤科", "source": "皮肤", "text": "皮疹红斑。"},
)


async def _fixture_index() -> tuple[faiss.Index, list[Chunk]]:
    return await build_index(_fake_embed, TEST_CORPUS)


@pytest.mark.unit
async def test_retrieve_hits_expected_department():
    index, chunks = await _fixture_index()
    results = await retrieve("我胸痛", top_k=3, embed_fn=_fake_embed,
                             index=index, chunks=chunks)
    assert results[0].department == "心血管内科"


@pytest.mark.unit
async def test_retrieve_ranked_by_score_desc():
    index, chunks = await _fixture_index()
    results = await retrieve("呼吸困难", top_k=3, embed_fn=_fake_embed,
                             index=index, chunks=chunks)
    scores = [e.score for e in results]
    assert scores == sorted(scores, reverse=True)
    assert results[0].department == "呼吸内科"


@pytest.mark.unit
async def test_retrieve_empty_query_returns_nothing():
    index, chunks = await _fixture_index()
    assert await retrieve("", top_k=3, embed_fn=_fake_embed,
                          index=index, chunks=chunks) == []


@pytest.mark.unit
async def test_retrieve_respects_top_k():
    index, chunks = await _fixture_index()
    results = await retrieve("皮疹", top_k=2, embed_fn=_fake_embed,
                             index=index, chunks=chunks)
    assert len(results) <= 2


@pytest.mark.unit
async def test_returned_evidence_carries_source_and_score():
    index, chunks = await _fixture_index()
    results = await retrieve("红斑", top_k=1, embed_fn=_fake_embed,
                             index=index, chunks=chunks)
    ev = results[0]
    assert ev.source == "皮肤" and ev.doc_id == "d1"
    assert ev.source_type == "text"
    assert isinstance(ev.score, float)
    assert ev.chunk_id == "d1#0"
    assert ev.citation_id == ev.chunk_id
    assert ev.quote == "皮疹红斑。"
    assert ev.index_version == "in-memory"


@pytest.mark.unit
async def test_injected_index_version_is_preserved():
    index, chunks = await _fixture_index()
    results = await retrieve(
        "胸痛",
        top_k=1,
        embed_fn=_fake_embed,
        index=index,
        chunks=chunks,
        index_version="snapshot-42",
    )

    assert results[0].index_version == "snapshot-42"


@pytest.mark.unit
async def test_retrieve_discards_results_below_similarity_threshold():
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0]], dtype="float32"))
    chunks = [
        Chunk(
            chunk_id="doc#0",
            doc_id="doc",
            department="test",
            source="test",
            text="unrelated",
        )
    ]

    async def orthogonal_embed(_text: str) -> list[float]:
        return [0.0, 1.0]

    results = await retrieve(
        "query",
        embed_fn=orthogonal_embed,
        index=index,
        chunks=chunks,
        min_score=0.2,
    )

    assert results == []


@pytest.mark.unit
async def test_retrieve_uses_configured_similarity_threshold_by_default(monkeypatch):
    monkeypatch.setenv("MEDPILOT_RAG_MIN_SCORE", "0.2")
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0]], dtype="float32"))
    chunks = [
        Chunk(
            chunk_id="doc#0",
            doc_id="doc",
            department="心血管内科",
            source="test",
            text="unrelated",
        )
    ]

    async def orthogonal_embed(_text: str) -> list[float]:
        return [0.0, 1.0]

    assert await retrieve(
        "query", embed_fn=orthogonal_embed, index=index, chunks=chunks
    ) == []


@pytest.mark.unit
async def test_hybrid_retrieval_promotes_lexical_match_when_dense_scores_tie():
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0], [1.0, 0.0]], dtype="float32"))
    chunks = [
        Chunk(
            chunk_id="generic#0",
            doc_id="generic",
            department="呼吸内科",
            source="test",
            text="需要观察一般不适。",
        ),
        Chunk(
            chunk_id="match#0",
            doc_id="match",
            department="呼吸内科",
            source="test",
            text="气短时需要记录活动后呼吸困难。",
        ),
    ]

    async def tied_embed(_text: str) -> list[float]:
        return [1.0, 0.0]

    results = await retrieve(
        "气短",
        top_k=1,
        embed_fn=tied_embed,
        index=index,
        chunks=chunks,
        min_score=0.2,
    )

    assert [item.citation_id for item in results] == ["match#0"]


@pytest.mark.unit
async def test_vector_weight_zero_disables_lexical_reranking():
    index = faiss.IndexFlatIP(2)
    index.add(np.array([[1.0, 0.0], [1.0, 0.0]], dtype="float32"))
    chunks = [
        Chunk(
            chunk_id="generic#0",
            doc_id="generic",
            department="呼吸内科",
            source="test",
            text="需要观察一般不适。",
        ),
        Chunk(
            chunk_id="match#0",
            doc_id="match",
            department="呼吸内科",
            source="test",
            text="气短时需要记录活动后呼吸困难。",
        ),
    ]

    async def tied_embed(_text: str) -> list[float]:
        return [1.0, 0.0]

    results = await retrieve(
        "气短",
        top_k=1,
        embed_fn=tied_embed,
        index=index,
        chunks=chunks,
        min_score=0.2,
        vector_weight=1.0,
    )

    assert [item.citation_id for item in results] == ["generic#0"]


@pytest.mark.unit
async def test_retrieve_empty_active_index_skips_embedding(monkeypatch):
    from app.rag import retriever

    called = False

    async def should_not_embed(_text: str) -> list[float]:
        nonlocal called
        called = True
        return [1.0]

    monkeypatch.setattr(retriever, "_get_index", lambda: (None, [], "empty"))

    assert await retriever.retrieve("query", embed_fn=should_not_embed) == []
    assert called is False


@pytest.mark.unit
def test_cached_index_reloads_when_active_pointer_changes(monkeypatch):
    from app.rag import retriever

    marker = {"value": "version:v1"}
    loads = []

    def fake_load():
        version = marker["value"].split(":", 1)[1]
        loads.append(version)
        return object(), [], version

    monkeypatch.setattr(retriever, "active_index_marker", lambda: marker["value"])
    monkeypatch.setattr(retriever, "load_active_index", fake_load)
    retriever.clear_cache()

    assert retriever._get_index()[2] == "v1"
    marker["value"] = "version:v2"
    assert retriever._get_index()[2] == "v2"
    assert loads == ["v1", "v2"]
