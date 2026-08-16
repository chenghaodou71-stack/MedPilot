import json
import asyncio
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator

from app.agents.graph import run_consult_stream
from app.events import terminal_error_event
from app.ollama_client import (
    CHAT_MODEL,
    EMBED_MODEL,
    health as ollama_health,
    model_is_available,
)
from app.rag.index_versions import get_index_health
from app.runtime import CapacityExceeded, InferenceGate, inference_gate
from app.session import store

router = APIRouter()


class HealthContext(BaseModel):
    """Patient-provided background fields; unknown or non-string PHI is rejected."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    allergies: StrictStr | None = Field(default=None, max_length=4000)
    conditions: StrictStr | None = Field(default=None, max_length=4000)
    medications: StrictStr | None = Field(default=None, max_length=4000)
    notes: StrictStr | None = Field(default=None, max_length=4000)

    @field_validator("*", mode="before")
    @classmethod
    def normalize_value(cls, value: object) -> object:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("health context values must be strings")
        normalized = value.strip()
        return normalized or None


class ConsultRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    session_id: str | None = Field(default=None, max_length=128)
    history: list[str] = Field(default_factory=list, max_length=20)
    health_context: HealthContext | None = None

    @field_validator("health_context", mode="after")
    @classmethod
    def omit_empty_health_context(cls, value: HealthContext | None) -> HealthContext | None:
        if value is None or not value.model_dump(exclude_none=True):
            return None
        return value

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("text must not be blank")
        return value


@router.get("/health")
async def health():
    index_health = get_index_health()
    try:
        info = await ollama_health()
        installed_models = info.get("models", [])
        ollama_ok = True
    except Exception:
        installed_models = []
        ollama_ok = False

    chat_ok = ollama_ok and model_is_available(CHAT_MODEL, installed_models)
    embed_ok = ollama_ok and model_is_available(EMBED_MODEL, installed_models)
    components = {
        "ollama": {
            "ok": ollama_ok,
            "status": "online" if ollama_ok else "unreachable",
        },
        "chat_model": {
            "ok": chat_ok,
            "status": "available" if chat_ok else "missing",
            "model": CHAT_MODEL,
        },
        "embedding_model": {
            "ok": embed_ok,
            "status": "available" if embed_ok else "missing",
            "model": EMBED_MODEL,
        },
        "knowledge_index": index_health,
    }
    ready = all(component.get("ok", False) for component in components.values())
    payload = {
        "status": "ok" if ready else "degraded",
        "components": components,
        "models": installed_models,
    }
    if not ready:
        return JSONResponse(status_code=503, content=payload)
    return payload
async def _ndjson_stream(
    req: ConsultRequest,
    *,
    request_timeout: float | None = None,
) -> AsyncIterator[bytes]:
    context = store.get_context(req.session_id)
    captured_response: str | None = None
    saw_done = False
    saw_error = False
    last_event: dict | None = None
    health_context = (
        req.health_context.model_dump(exclude_none=True)
        if req.health_context is not None
        else None
    )
    try:
        async with AsyncExitStack() as stack:
            if request_timeout is not None:
                await stack.enter_async_context(asyncio.timeout(request_timeout))
            async for event in run_consult_stream(
                req.text,
                history=context.history,
                session_id=req.session_id,
                turn_count=context.turn_count,
                history_mode=context.history_mode,
                health_context=health_context,
            ):
                last_event = event
                event_type = event.get("type")
                if event_type == "error" or event.get("status") == "error":
                    saw_error = True
                elif event_type == "done" and event.get("status") == "completed":
                    saw_done = True
                elif event_type == "node" and event.get("status") == "completed":
                    data = event.get("data") or {}
                    if event.get("node") == "compose" and data.get("answer"):
                        captured_response = data["answer"].get("text")
                    elif event.get("node") == "ask_followup" and data.get("followup"):
                        captured_response = data["followup"].get("question")
                yield (json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8")
    except asyncio.CancelledError:
        raise
    except TimeoutError:
        saw_error = True
        event = terminal_error_event(
            last_event,
            session_id=req.session_id,
            code="inference_timeout",
            detail="consultation timed out",
        )
        yield (json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8")
    except Exception:
        saw_error = True
        raise
    else:
        if req.session_id and saw_done and not saw_error and captured_response:
            store.append(req.session_id, req.text, captured_response)


@router.post("/consult")
async def consult(req: ConsultRequest):
    """LangGraph 串起四智能体，逐节点流式返回 NDJSON 事件。"""
    turn_started = False
    if req.session_id:
        turn_started = store.begin_turn(req.session_id)
        if not turn_started:
            raise HTTPException(status_code=409, detail="session turn already in progress")

    gate = inference_gate
    try:
        await gate.acquire()
    except CapacityExceeded as exc:
        if turn_started and req.session_id:
            store.end_turn(req.session_id)
        raise HTTPException(status_code=429, detail="inference capacity exceeded") from exc

    return StreamingResponse(
        _leased_stream(req, gate, turn_started),
        media_type="application/x-ndjson",
    )


async def _leased_stream(
    req: ConsultRequest,
    gate: InferenceGate,
    turn_started: bool,
) -> AsyncIterator[bytes]:
    try:
        async for chunk in _ndjson_stream(
            req, request_timeout=gate.request_timeout
        ):
            yield chunk
    finally:
        gate.release()
        if turn_started and req.session_id:
            store.end_turn(req.session_id)
