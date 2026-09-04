import json
import asyncio
import inspect
import os
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack
from typing import Annotated, Literal

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator

from app.agents.graph import run_consult_stream
from app.events import HistoryMode, terminal_error_event
from app.ollama_client import (
    CHAT_MODEL,
    EMBED_MODEL,
    health as ollama_health,
    model_is_available,
)
from app.rag.index_versions import get_index_health
from app.model_governance import model_governance_health
from app.runtime import (
    CapacityExceeded,
    InferenceGate,
    inference_gate,
    redis_inference_gate,
)
from app.session import RedisSessionCoordinator, store
from app.shared_state import RedisSharedState, SharedStateUnavailable

router = APIRouter()
session_coordinator: RedisSessionCoordinator | None = None
shared_state: RedisSharedState | None = None
persisted_history_required = os.getenv(
    "MEDPILOT_SESSION_HISTORY_SOURCE", "memory"
).strip().lower() in {"backend", "mysql", "persisted"}


def configure_shared_state(state: RedisSharedState) -> None:
    """Use Redis coordination when configured; memory remains test/dev only."""
    global inference_gate, session_coordinator, shared_state, persisted_history_required
    shared_state = state
    # A required shared runtime is the production signal: clinical history
    # must come from the Spring/MySQL system of record, never this process.
    persisted_history_required = persisted_history_required or state.required
    if state.client is not None or state.required:
        inference_gate = redis_inference_gate(state)
        session_coordinator = RedisSessionCoordinator(
            state,
            default_lease_seconds=max(1, int(inference_gate.request_timeout + 30)),
        )


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


class ConsultHistoryMessage(BaseModel):
    """One persisted message supplied by the Spring system of record."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    role: Literal["user", "assistant"]
    content: StrictStr = Field(min_length=1, max_length=4000)

    @field_validator("content")
    @classmethod
    def normalize_content(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("history content must not be blank")
        return normalized


LegacyHistoryLine = Annotated[StrictStr, Field(min_length=1, max_length=4000)]


class ConsultRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=4000)
    session_id: str | None = Field(default=None, max_length=128)
    history: list[ConsultHistoryMessage | LegacyHistoryLine] | None = Field(
        default=None,
        max_length=20,
    )
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

    @field_validator("history")
    @classmethod
    def normalize_history(
        cls,
        value: list[ConsultHistoryMessage | str] | None,
    ) -> list[ConsultHistoryMessage | str] | None:
        if value is None:
            return None
        normalized: list[ConsultHistoryMessage | str] = []
        for entry in value:
            if isinstance(entry, str):
                line = entry.strip()
                if not line:
                    raise ValueError("history lines must not be blank")
                normalized.append(line)
            else:
                normalized.append(entry)
        return normalized


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
    redis_component = {"ok": True, "status": "disabled"}
    if shared_state is not None and (shared_state.client is not None or shared_state.required):
        try:
            redis_ok = await shared_state.require_available()
        except SharedStateUnavailable:
            redis_ok = False
        redis_component = {
            "ok": redis_ok,
            "status": "online" if redis_ok else "unavailable",
            "required": shared_state.required,
        }
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
        "model_governance": model_governance_health(),
        "shared_state": redis_component,
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
    history, turn_count, history_mode = _resolve_history(req)
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
                history=history,
                session_id=req.session_id,
                turn_count=turn_count,
                history_mode=history_mode,
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
        if (
            req.session_id
            and saw_done
            and not saw_error
            and captured_response
            and not persisted_history_required
        ):
            store.append(req.session_id, req.text, captured_response)


def _resolve_history(req: ConsultRequest) -> tuple[list[str], int, HistoryMode]:
    """Prefer persisted request history; use process-local state only if omitted."""
    if req.history is None:
        if persisted_history_required and req.session_id:
            raise SharedStateUnavailable(
                "persisted consultation history is required in shared-state mode"
            )
        context = store.get_context(req.session_id)
        return context.history, context.turn_count, context.history_mode

    rendered: list[str] = []
    user_messages = 0
    for entry in req.history:
        if isinstance(entry, ConsultHistoryMessage):
            label = "用户" if entry.role == "user" else "助手"
            rendered.append(f"{label}：{entry.content}")
            if entry.role == "user":
                user_messages += 1
        else:
            rendered.append(entry)
            user_messages += entry.startswith(("用户：", "用户:"))
    if not user_messages and rendered:
        user_messages = (len(rendered) + 1) // 2
    history_mode = "summary" if any(
        line.startswith("对话摘要：") for line in rendered
    ) else "full"
    return rendered, user_messages + 1, history_mode


@router.post("/consult")
async def consult(req: ConsultRequest):
    """LangGraph 串起四智能体，逐节点流式返回 NDJSON 事件。"""
    if persisted_history_required and req.session_id and req.history is None:
        raise HTTPException(
            status_code=400,
            detail="history must be supplied by the backend system of record",
        )
    turn_started = False
    if req.session_id:
        try:
            turn_started = await _begin_turn(req.session_id)
        except SharedStateUnavailable as exc:
            raise HTTPException(status_code=503, detail="shared state unavailable") from exc
        if not turn_started:
            raise HTTPException(status_code=409, detail="session turn already in progress")

    gate = inference_gate
    try:
        await gate.acquire()
    except SharedStateUnavailable as exc:
        if turn_started and req.session_id:
            await _end_turn(req.session_id)
        raise HTTPException(status_code=503, detail="shared state unavailable") from exc
    except CapacityExceeded as exc:
        if turn_started and req.session_id:
            await _end_turn(req.session_id)
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
        await _maybe_await(gate.release())
        if turn_started and req.session_id:
            await _end_turn(req.session_id)


async def _begin_turn(session_id: str) -> bool:
    coordinator = session_coordinator if session_coordinator is not None else store
    return bool(await _maybe_await(coordinator.begin_turn(session_id)))


async def _end_turn(session_id: str) -> None:
    coordinator = session_coordinator if session_coordinator is not None else store
    await _maybe_await(coordinator.end_turn(session_id))


async def _maybe_await(value):
    if inspect.isawaitable(value):
        return await value
    return value
