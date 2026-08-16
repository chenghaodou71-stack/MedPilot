"""Ollama 本地模型客户端封装。仅在系统边界处做输入校验。"""
import os
import httpx

OLLAMA_BASE = os.getenv("OLLAMA_BASE", "http://127.0.0.1:11434")
CHAT_MODEL = os.getenv("CHAT_MODEL", "qwen2.5:7b")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")


def model_is_available(configured: str, installed: list[str]) -> bool:
    if configured in installed:
        return True
    if ":" not in configured:
        return f"{configured}:latest" in installed
    return False


async def chat(prompt: str, system: str | None = None) -> str:
    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})
    async with httpx.AsyncClient(timeout=120, trust_env=False) as client:
        resp = await client.post(
            f"{OLLAMA_BASE}/api/chat",
            json={"model": CHAT_MODEL, "messages": messages, "stream": False},
        )
        resp.raise_for_status()
        return resp.json()["message"]["content"]


async def embed(text: str) -> list[float]:
    async with httpx.AsyncClient(timeout=60, trust_env=False) as client:
        resp = await client.post(
            f"{OLLAMA_BASE}/api/embeddings",
            json={"model": EMBED_MODEL, "prompt": text},
        )
        resp.raise_for_status()
        return resp.json()["embedding"]


async def health() -> dict:
    """返回 Ollama 服务状态与已加载模型列表。"""
    async with httpx.AsyncClient(timeout=10, trust_env=False) as client:
        resp = await client.get(f"{OLLAMA_BASE}/api/tags")
        resp.raise_for_status()
        models = [m["name"] for m in resp.json().get("models", [])]
        return {"ollama": "online", "models": models}
