"""FAISS 索引构建与持久化。

流程：语料按句切分为 chunk → bge-m3 向量化 → 归一化写入 IndexFlatIP（内积=余弦相似度）。
embed_fn 可注入，测试用假向量保证离线确定性；生产用 ollama_client.embed。
索引持久化为 <name>.faiss + <name>.meta.json 两个文件。"""
from __future__ import annotations

import json
import os
import re
from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass
from pathlib import Path

import faiss
import numpy as np

from app.config import validate_index_dir
from app.rag.corpus import CORPUS

EmbedFn = Callable[[str], Awaitable[list[float]]]

_DEFAULT_INDEX_DIR = Path(__file__).resolve().parent / "index_store"
INDEX_DIR = validate_index_dir(
    Path(os.getenv("MEDPILOT_INDEX_DIR", str(_DEFAULT_INDEX_DIR)))
)
INDEX_NAME = "medpilot"


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    doc_id: str
    department: str
    source: str
    text: str
    institution: str = ""
    title: str = ""
    url: str = ""
    published_date: str = ""
    version: str = ""
    license: str = ""
    review_status: str = ""


def split_document(doc: dict[str, str]) -> list[Chunk]:
    """按中文句号/分号等切分为句子级 chunk，过短的合并进上一句。"""
    sentences = [s for s in re.split(r"(?<=[。；！？])", doc["text"]) if s.strip()]
    merged: list[str] = []
    for s in sentences:
        s = s.strip()
        if merged and len(merged[-1]) < 20:
            merged[-1] += s
        else:
            merged.append(s)
    return [
        Chunk(
            chunk_id=f"{doc['doc_id']}#{i}",
            doc_id=doc["doc_id"],
            department=doc["department"],
            source=doc["source"],
            text=text,
            institution=doc.get("institution", ""),
            title=doc.get("title", ""),
            url=doc.get("url", ""),
            published_date=doc.get("published_date", ""),
            version=doc.get("version", ""),
            license=doc.get("license", ""),
            review_status=doc.get("review_status", ""),
        )
        for i, text in enumerate(merged)
    ]


def build_chunks(corpus: tuple[dict[str, str], ...] = CORPUS) -> list[Chunk]:
    chunks: list[Chunk] = []
    for doc in corpus:
        chunks.extend(split_document(doc))
    return chunks


def _normalize(matrix: np.ndarray) -> np.ndarray:
    faiss.normalize_L2(matrix)
    return matrix


async def build_index(
    embed_fn: EmbedFn,
    corpus: tuple[dict[str, str], ...] = CORPUS,
) -> tuple[faiss.Index, list[Chunk]]:
    """向量化所有 chunk 并构建归一化内积索引。返回 (index, chunks)。"""
    chunks = build_chunks(corpus)
    vectors = [await embed_fn(c.text) for c in chunks]
    matrix = np.array(vectors, dtype="float32")
    _normalize(matrix)
    index = faiss.IndexFlatIP(matrix.shape[1])
    index.add(matrix)
    return index, chunks


def save_index(index: faiss.Index, chunks: list[Chunk],
               index_dir: Path = INDEX_DIR, name: str = INDEX_NAME) -> None:
    index_dir.mkdir(parents=True, exist_ok=True)
    serialized = faiss.serialize_index(index)
    (index_dir / f"{name}.faiss").write_bytes(serialized.tobytes())
    meta = [asdict(chunk) for chunk in chunks]
    (index_dir / f"{name}.meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def load_index(index_dir: Path = INDEX_DIR,
               name: str = INDEX_NAME) -> tuple[faiss.Index, list[Chunk]]:
    serialized = np.frombuffer(
        (index_dir / f"{name}.faiss").read_bytes(), dtype="uint8"
    )
    index = faiss.deserialize_index(serialized)
    raw = json.loads((index_dir / f"{name}.meta.json").read_text(encoding="utf-8"))
    chunks = [Chunk(**item) for item in raw]
    return index, chunks
