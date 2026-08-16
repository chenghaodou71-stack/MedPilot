"""LangGraph 编排：采集 → 检索 → 分诊 → 编排 四节点有向图。

按节点顺序流式产出各智能体执行状态与结果，供 /consult 流式返回与监控使用。
LLM 调用通过 chat_fn 注入，测试可打桩。"""
from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import suppress
from time import perf_counter
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.agents.classify import classify
from app.agents.compose import compose
from app.agents.extract import extract
from app.agents.followup import build_followup, is_sufficient
from app.agents.safety import SafetyScreenResult, screen_for_emergency
from app.events import EventEmitter, HistoryMode
from app.ollama_client import chat as ollama_chat
from app.rag.retriever import retrieve
from app.schemas import ComposedAnswer, FollowUpQuestion, RankedEvidence, StructuredSymptoms, TriageResult
from app.agents.compose import SAFETY_BOUNDARY

ChatFn = Callable[..., Awaitable[str]]


class ConsultState(TypedDict, total=False):
    text: str
    history: list[str]
    health_context: dict[str, str]
    chat_fn: ChatFn
    top_k: int
    embed_fn: Any
    index: Any
    chunks: Any
    symptoms: StructuredSymptoms
    followup: FollowUpQuestion
    evidence: list[RankedEvidence]
    triage: TriageResult
    answer: ComposedAnswer
    event_sink: Any


_NODE_PHASES = {
    "extract": "collecting",
    "ask_followup": "awaiting_followup",
    "retrieve": "retrieving",
    "classify": "triaging",
    "compose": "composing",
}


async def _run_node(
    state: ConsultState,
    node: str,
    operation: Callable[[], Awaitable[dict[str, Any]]],
) -> dict[str, Any]:
    sink = state.get("event_sink")
    if sink is not None:
        await sink(node, "started", _NODE_PHASES[node], {}, 0)
    started = perf_counter()
    try:
        update = await operation()
    except Exception as exc:
        if sink is not None:
            elapsed = round((perf_counter() - started) * 1000)
            await sink(node, "error", "failed", {"detail": "node execution failed"}, elapsed)
        raise
    if sink is not None:
        elapsed = round((perf_counter() - started) * 1000)
        await sink(
            node,
            "completed",
            _NODE_PHASES[node],
            _serialize_update(node, update),
            elapsed,
        )
    return update


async def _extract_node(state: ConsultState) -> dict[str, Any]:
    async def operation() -> dict[str, Any]:
        symptoms = await extract(
            state["text"],
            state.get("history"),
            state["chat_fn"],
            health_context=state.get("health_context"),
        )
        return {"symptoms": symptoms}

    return await _run_node(state, "extract", operation)


async def _retrieve_node(state: ConsultState) -> dict[str, Any]:
    async def operation() -> dict[str, Any]:
        query = state["text"] + " ".join(state["symptoms"].symptoms)
        kwargs: dict[str, Any] = {}
        if state.get("embed_fn") is not None:
            kwargs["embed_fn"] = state["embed_fn"]
        if state.get("index") is not None:
            kwargs["index"] = state["index"]
            kwargs["chunks"] = state["chunks"]
        evidence = await retrieve(query, state.get("top_k", 3), **kwargs)
        return {"evidence": evidence}

    return await _run_node(state, "retrieve", operation)


async def _followup_node(state: ConsultState) -> dict[str, Any]:
    async def operation() -> dict[str, Any]:
        symptoms = state.get("symptoms") or StructuredSymptoms()
        return {"followup": build_followup(symptoms)}

    return await _run_node(state, "ask_followup", operation)


def _route_after_extract(state: ConsultState) -> str:
    symptoms = state.get("symptoms")
    if symptoms and is_sufficient(symptoms):
        return "retrieve"
    return "ask_followup"


async def _classify_node(state: ConsultState) -> dict[str, Any]:
    async def operation() -> dict[str, Any]:
        return {"triage": classify(state["symptoms"], state["evidence"])}

    return await _run_node(state, "classify", operation)


async def _compose_node(state: ConsultState) -> dict[str, Any]:
    async def operation() -> dict[str, Any]:
        answer = await compose(
            state["triage"], state["evidence"], state["symptoms"], state["chat_fn"]
        )
        return {"answer": answer}

    return await _run_node(state, "compose", operation)


def build_graph():
    graph = StateGraph(ConsultState)
    graph.add_node("extract", _extract_node)
    graph.add_node("ask_followup", _followup_node)
    graph.add_node("retrieve", _retrieve_node)
    graph.add_node("classify", _classify_node)
    graph.add_node("compose", _compose_node)
    graph.add_edge(START, "extract")
    graph.add_conditional_edges(
        "extract",
        _route_after_extract,
        {"retrieve": "retrieve", "ask_followup": "ask_followup"},
    )
    graph.add_edge("ask_followup", END)
    graph.add_edge("retrieve", "classify")
    graph.add_edge("classify", "compose")
    graph.add_edge("compose", END)
    return graph.compile()


_COMPILED = build_graph()

_NODE_LABELS = {
    "extract": "症状采集",
    "ask_followup": "追问",
    "retrieve": "知识检索",
    "classify": "辅助分诊",
    "compose": "回答编排",
}


def _serialize_update(node: str, update: dict[str, Any]) -> dict[str, Any]:
    """把节点状态更新转成可 JSON 序列化的事件 payload。"""
    payload: dict[str, Any] = {}
    if "symptoms" in update:
        payload["symptoms"] = update["symptoms"].model_dump()
    if "followup" in update:
        payload["followup"] = update["followup"].model_dump()
    if "evidence" in update:
        payload["evidence"] = [e.model_dump() for e in update["evidence"]]
    if "triage" in update:
        payload["triage"] = update["triage"].model_dump()
    if "answer" in update:
        payload["answer"] = update["answer"].model_dump()
    return payload


async def run_consult_stream(
    text: str,
    history: list[str] | None = None,
    session_id: str | None = None,
    turn_count: int = 1,
    history_mode: HistoryMode = "full",
    chat_fn: ChatFn = ollama_chat,
    top_k: int = 3,
    embed_fn: Any = None,
    index: Any = None,
    chunks: Any = None,
    health_context: dict[str, str] | None = None,
) -> AsyncIterator[dict[str, Any]]:
    """Stream validated, timed node events for one consultation turn."""
    emitter = EventEmitter(
        session_id,
        turn_count=turn_count,
        history_mode=history_mode,
    )

    screening_started = perf_counter()
    yield emitter.emit(
        "node", node="safety_screen", label="安全筛查",
        status="started", phase="screening",
    )
    screening = screen_for_emergency(text)
    if screening.matched:
        emitter.set_intent("emergency")
    screening_data = _serialize_screening(screening)
    yield emitter.emit(
        "node", node="safety_screen", label="安全筛查",
        status="completed", phase="screening",
        elapsed_ms=round((perf_counter() - screening_started) * 1000),
        data={"safety": screening_data},
    )

    if screening.matched:
        triage = screening.triage
        assert triage is not None
        yield emitter.emit(
            "node", node="classify", label=_NODE_LABELS["classify"],
            status="started", phase="triaging",
        )
        yield emitter.emit(
            "node", node="classify", label=_NODE_LABELS["classify"],
            status="completed", phase="triaging",
            data={"triage": triage.model_dump()},
        )
        yield emitter.emit(
            "node", node="compose", label=_NODE_LABELS["compose"],
            status="started", phase="composing",
        )
        terms = "、".join(screening.matched_terms)
        answer = ComposedAnswer(
            text=(
                f"检测到危险信号：{terms}。{triage.urgency}，"
                "请勿自行驾车，尽快由他人陪同就医。"
            ),
            citations=(),
            safety_boundary=SAFETY_BOUNDARY,
        )
        yield emitter.emit(
            "node", node="compose", label=_NODE_LABELS["compose"],
            status="completed", phase="composing",
            data={"answer": answer.model_dump()},
        )
        yield emitter.emit("done", status="completed", phase="escalated")
        return

    queue: asyncio.Queue[dict | None] = asyncio.Queue()

    async def event_sink(node, status, phase, data, elapsed_ms):
        await queue.put(emitter.emit(
            "node",
            node=node,
            label=_NODE_LABELS.get(node, node),
            status=status,
            phase=phase,
            elapsed_ms=elapsed_ms,
            data=data,
        ))

    initial: ConsultState = {
        "text": text,
        "history": history or [],
        "health_context": dict(health_context or {}),
        "chat_fn": chat_fn,
        "top_k": top_k,
        "event_sink": event_sink,
    }
    if embed_fn is not None:
        initial["embed_fn"] = embed_fn
    if index is not None:
        initial["index"] = index
        initial["chunks"] = chunks
    graph_error: BaseException | None = None

    async def drive_graph() -> None:
        nonlocal graph_error
        try:
            await _COMPILED.ainvoke(initial)
        except BaseException as exc:
            graph_error = exc
        finally:
            await queue.put(None)

    task = asyncio.create_task(drive_graph())
    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            yield event
        if graph_error is not None:
            yield emitter.emit(
                "error",
                status="error",
                phase="failed",
                data={"detail": "consultation failed"},
            )
            return
        yield emitter.emit("done", status="completed", phase="completed")
    finally:
        if not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task


def _serialize_screening(result: SafetyScreenResult) -> dict[str, Any]:
    triage = result.triage
    return {
        "matched": result.matched,
        "matched_terms": list(result.matched_terms),
        "department": triage.department if triage else None,
        "risk_level": triage.risk_level if triage else None,
        "urgency": triage.urgency if triage else None,
        "rule_id": result.rule_id,
    }
