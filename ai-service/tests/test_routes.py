"""Consult streaming route lifecycle tests without Ollama."""
from __future__ import annotations

import json

import pytest

from app.api import routes
from app.api.routes import ConsultRequest, _ndjson_stream
from app.session import SessionStore


def completed_node(node: str, data: dict) -> dict:
    return {"type": "node", "node": node, "status": "completed", "data": data}


@pytest.mark.unit
async def test_route_passes_summary_context_to_sixth_turn(monkeypatch):
    session_store = SessionStore()
    first_full = "第一轮完整原文-" + "头痛描述" * 40
    session_store.append("s1", first_full, "第一轮回复")
    for turn in range(2, 6):
        session_store.append("s1", f"第{turn}轮", f"回复{turn}")
    captured = {}

    async def fake_stream(text, **kwargs):
        captured.update(kwargs)
        yield completed_node("ask_followup", {"followup": {"question": "请补充"}})
        yield {"type": "done", "status": "completed"}

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    chunks = [chunk async for chunk in _ndjson_stream(ConsultRequest(text="第六轮", session_id="s1"))]

    assert chunks
    assert captured["turn_count"] == 6
    assert captured["history_mode"] == "summary"
    assert first_full not in "\n".join(captured["history"])


@pytest.mark.unit
async def test_route_passes_health_context_separately_from_session_history(monkeypatch):
    captured = {}

    async def fake_stream(text, **kwargs):
        captured["text"] = text
        captured.update(kwargs)
        yield completed_node("ask_followup", {"followup": {"question": "请补充"}})
        yield {"type": "done", "status": "completed"}

    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    request = ConsultRequest(
        text="当前咳嗽",
        session_id="profile-context",
        health_context={"allergies": "青霉素", "conditions": "哮喘"},
    )
    chunks = [chunk async for chunk in _ndjson_stream(request)]

    assert chunks
    assert captured["text"] == "当前咳嗽"
    assert captured["health_context"] == {
        "allergies": "青霉素",
        "conditions": "哮喘",
    }
    assert captured["history"] == []


@pytest.mark.unit
def test_health_context_rejects_unknown_or_non_string_fields():
    with pytest.raises(ValueError):
        ConsultRequest.model_validate({
            "text": "咳嗽",
            "health_context": {"password": "should not pass"},
        })

    with pytest.raises(ValueError):
        ConsultRequest.model_validate({
            "text": "咳嗽",
            "health_context": {"conditions": 123},
        })


@pytest.mark.unit
async def test_route_writes_session_only_after_valid_done(monkeypatch):
    session_store = SessionStore()

    async def fake_stream(text, **kwargs):
        yield completed_node("compose", {"answer": {"text": "建议线下就医"}})
        yield {"type": "done", "status": "completed"}

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    payload = [json.loads(chunk) async for chunk in _ndjson_stream(
        ConsultRequest(text="咳嗽三天", session_id="success")
    )]

    assert payload[-1]["type"] == "done"
    assert session_store.get_history("success") == ["用户：咳嗽三天", "助手：建议线下就医"]


@pytest.mark.unit
async def test_route_does_not_write_session_after_error(monkeypatch):
    session_store = SessionStore()

    async def fake_stream(text, **kwargs):
        yield completed_node("compose", {"answer": {"text": "不应保存"}})
        yield {"type": "error", "status": "error", "detail": "model failed"}

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    _ = [chunk async for chunk in _ndjson_stream(
        ConsultRequest(text="咳嗽三天", session_id="failed")
    )]

    assert session_store.get_history("failed") == []


@pytest.mark.unit
async def test_route_does_not_write_session_when_client_cancels(monkeypatch):
    session_store = SessionStore()

    async def fake_stream(text, **kwargs):
        yield completed_node("compose", {"answer": {"text": "流尚未完成"}})
        yield {"type": "done", "status": "completed"}

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    stream = _ndjson_stream(ConsultRequest(text="咳嗽三天", session_id="cancelled"))
    await anext(stream)
    await stream.aclose()

    assert session_store.get_history("cancelled") == []
