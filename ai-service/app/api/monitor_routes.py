"""智能体监控端点：系统健康状态 + 执行链路实时追踪（带计时）。"""
from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator

from app.agents.graph import run_consult_stream
from app.events import terminal_error_event
from app.ollama_client import (
    CHAT_MODEL,
    EMBED_MODEL,
    health as ollama_health,
    model_is_available,
)
from app.rag import retriever
from app.rag.knowledge_store import load_documents
from app.rag.index_versions import get_index_health
from app.runtime import CapacityExceeded, InferenceGate, inference_gate
from app.session import store

router = APIRouter(prefix="/monitor", tags=["monitor"])


@router.get("/health")
async def health() -> dict:
    try:
        ollama_info = await ollama_health()
        ollama_ok = True
    except Exception:
        ollama_info = {"status": "unreachable", "models": []}
        ollama_ok = False

    installed_models = ollama_info.get("models", [])
    chat_ok = ollama_ok and model_is_available(CHAT_MODEL, installed_models)
    embed_ok = ollama_ok and model_is_available(EMBED_MODEL, installed_models)
    docs = load_documents()
    index_health = get_index_health()
    payload = {
        "ollama": {"ok": ollama_ok, **ollama_info},
        "models": {
            "chat": {
                "ok": chat_ok,
                "status": "available" if chat_ok else "missing",
                "model": CHAT_MODEL,
            },
            "embedding": {
                "ok": embed_ok,
                "status": "available" if embed_ok else "missing",
                "model": EMBED_MODEL,
            },
        },
        "sessions": {"active": store.count()},
        "knowledge": {
            "docs": len(docs),
            "index_loaded": retriever._INDEX is not None,
            **index_health,
        },
    }
    if not ollama_ok or not chat_ok or not embed_ok or not index_health["ok"]:
        return JSONResponse(status_code=503, content=payload)
    return payload


class TraceRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("text must not be blank")
        return value


async def _trace_stream(
    text: str,
    *,
    request_timeout: float | None = None,
) -> AsyncIterator[bytes]:
    last_event: dict | None = None
    try:
        async with AsyncExitStack() as stack:
            if request_timeout is not None:
                await stack.enter_async_context(asyncio.timeout(request_timeout))
            async for event in run_consult_stream(text):
                last_event = event
                event.setdefault("elapsed_ms", 0)
                yield (json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8")
    except asyncio.CancelledError:
        raise
    except TimeoutError:
        err = terminal_error_event(
            last_event,
            session_id=None,
            code="inference_timeout",
            detail="consultation timed out",
        )
        yield (json.dumps(err, ensure_ascii=False) + "\n").encode("utf-8")
    except Exception:
        err = terminal_error_event(
            last_event,
            session_id=None,
            code="trace_failed",
            detail="trace failed",
        )
        yield (json.dumps(err, ensure_ascii=False) + "\n").encode("utf-8")


@router.post("/trace")
async def trace(req: TraceRequest) -> StreamingResponse:
    gate = inference_gate
    try:
        await gate.acquire()
    except CapacityExceeded as exc:
        raise HTTPException(status_code=429, detail="inference capacity exceeded") from exc
    return StreamingResponse(
        _leased_trace_stream(req.text, gate),
        media_type="application/x-ndjson",
    )


async def _leased_trace_stream(
    text: str,
    gate: InferenceGate,
) -> AsyncIterator[bytes]:
    try:
        async for chunk in _trace_stream(
            text, request_timeout=gate.request_timeout
        ):
            yield chunk
    finally:
        gate.release()
