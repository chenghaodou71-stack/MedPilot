"""Build the repository-delivered FAISS index from the reviewed corpus."""
import asyncio
import json
from pathlib import Path

from app.ollama_client import embed
from app.rag.corpus import CORPUS
from app.rag.index import build_index, save_index


async def main():
    print(f"开始构建索引，语料共 {len(CORPUS)} 条文档...")
    index, chunks = await build_index(embed, CORPUS)
    print(f"索引构建完成，共 {len(chunks)} 个 chunk。")

    target_dir = Path(__file__).parent / "app" / "rag" / "index_store"
    save_index(index, chunks, index_dir=target_dir)
    (target_dir / "documents.json").write_text(
        json.dumps(list(CORPUS), ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"索引和语料快照已保存到 {target_dir}")


if __name__ == "__main__":
    asyncio.run(main())
