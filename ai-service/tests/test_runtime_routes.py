"""Request-path concurrency and deadline regression tests."""
from __future__ import annotations

import asyncio
import json

import pytest
from fastapi import HTTPException

from app.api import routes
from app.api.routes import ConsultRequest, _ndjson_stream
from app.api import monitor_routes
from app.api.monitor_routes import TraceRequest, _trace_stream
from app.runtime import InferenceGate
from app.session import SessionStore


async def _completed_stream(*_args, **_kwargs):
    yield {"type": "done", "status": "completed"}


@pytest.mark.unit
async def test_consult_rejects_a_concurrent_turn_for_the_same_session(monkeypatch):
    session_store = SessionStore()
    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", _completed_stream)
    monkeypatch.setattr(
        routes,
        "inference_gate",
        InferenceGate(max_concurrency=2, queue_timeout=0.01, request_timeout=1),
        raising=False,
    )

    first = await routes.consult(ConsultRequest(text="第一轮", session_id="same"))
    try:
        with pytest.raises(HTTPException) as exc_info:
            await routes.consult(ConsultRequest(text="重复轮次", session_id="same"))
        assert exc_info.value.status_code == 409
    finally:
        _ = [chunk async for chunk in first.body_iterator]


@pytest.mark.unit
async def test_consult_returns_429_when_inference_capacity_is_full(monkeypatch):
    monkeypatch.setattr(routes, "store", SessionStore())
    monkeypatch.setattr(routes, "run_consult_stream", _completed_stream)
    monkeypatch.setattr(
        routes,
        "inference_gate",
        InferenceGate(max_concurrency=1, queue_timeout=0.01, request_timeout=1),
        raising=False,
    )

    first = await routes.consult(ConsultRequest(text="第一条"))
    try:
        with pytest.raises(HTTPException) as exc_info:
            await routes.consult(ConsultRequest(text="第二条"))
        assert exc_info.value.status_code == 429
    finally:
        _ = [chunk async for chunk in first.body_iterator]


@pytest.mark.unit
async def test_consult_timeout_emits_a_protocol_error_event(monkeypatch):
    async def hanging_stream(*_args, **_kwargs):
        yield {
            "protocol_version": "1.0",
            "trace_id": "trace-1",
            "session_id": "deadline",
            "sequence": 1,
            "type": "node",
            "status": "started",
            "elapsed_ms": 0,
            "state": {
                "intent": "medical_consult",
                "phase": "collecting",
                "turn_count": 1,
                "history_mode": "full",
            },
            "data": {},
        }
        await asyncio.sleep(1)

    monkeypatch.setattr(routes, "run_consult_stream", hanging_stream)
    events = [
        json.loads(chunk)
        async for chunk in _ndjson_stream(
            ConsultRequest(text="咳嗽", session_id="deadline"),
            request_timeout=0.01,
        )
    ]

    assert events[-1]["type"] == "error"
    assert events[-1]["status"] == "error"
    assert events[-1]["state"]["phase"] == "failed"
    assert events[-1]["data"] == {
        "code": "inference_timeout",
        "detail": "consultation timed out",
    }
    assert events[-1]["sequence"] == 2


@pytest.mark.unit
async def test_monitor_trace_shares_the_inference_capacity_gate(monkeypatch):
    monkeypatch.setattr(monitor_routes, "run_consult_stream", _completed_stream)
    monkeypatch.setattr(
        monitor_routes,
        "inference_gate",
        InferenceGate(max_concurrency=1, queue_timeout=0.01, request_timeout=1),
        raising=False,
    )

    first = await monitor_routes.trace(TraceRequest(text="第一条"))
    try:
        with pytest.raises(HTTPException) as exc_info:
            await monitor_routes.trace(TraceRequest(text="第二条"))
        assert exc_info.value.status_code == 429
    finally:
        _ = [chunk async for chunk in first.body_iterator]


@pytest.mark.unit
async def test_monitor_trace_timeout_emits_a_protocol_error_event(monkeypatch):
    async def hanging_stream(*_args, **_kwargs):
        yield {
            "protocol_version": "1.0",
            "trace_id": "trace-monitor",
            "session_id": "monitor-session",
            "sequence": 1,
            "type": "node",
            "status": "started",
            "elapsed_ms": 0,
            "state": {
                "intent": "medical_consult",
                "phase": "collecting",
                "turn_count": 1,
                "history_mode": "full",
            },
            "data": {},
        }
        await asyncio.sleep(1)

    monkeypatch.setattr(monitor_routes, "run_consult_stream", hanging_stream)
    events = [
        json.loads(chunk)
        async for chunk in _trace_stream("咳嗽", request_timeout=0.01)
    ]

    assert events[-1]["type"] == "error"
    assert events[-1]["state"]["phase"] == "failed"
    assert events[-1]["data"]["code"] == "inference_timeout"
