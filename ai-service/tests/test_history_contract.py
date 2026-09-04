"""Authoritative persisted-history contract for consultation requests."""
from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.api import routes
from app.api.routes import ConsultRequest, _ndjson_stream
from app.session import SessionStore


async def _capture_stream(captured: dict, _text: str, **kwargs):
    captured.update(kwargs)
    yield {
        "type": "node",
        "node": "ask_followup",
        "status": "completed",
        "data": {"followup": {"question": "请补充症状持续时间"}},
    }
    yield {"type": "done", "status": "completed", "data": {}}


@pytest.mark.unit
async def test_explicit_structured_history_overrides_process_local_cache(monkeypatch):
    session_store = SessionStore()
    session_store.append("persisted", "过期缓存症状", "过期缓存回复")
    captured: dict = {}

    async def fake_stream(text, **kwargs):
        async for event in _capture_stream(captured, text, **kwargs):
            yield event

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    request = ConsultRequest.model_validate({
        "text": "现在还在咳嗽",
        "session_id": "persisted",
        "history": [
            {"role": "user", "content": "咳嗽已经三天"},
            {"role": "assistant", "content": "是否伴有发热？"},
        ],
    })
    _ = [chunk async for chunk in _ndjson_stream(request)]

    assert captured["history"] == [
        "用户：咳嗽已经三天",
        "助手：是否伴有发热？",
    ]
    assert "过期缓存症状" not in "\n".join(captured["history"])
    assert captured["turn_count"] == 2
    assert captured["history_mode"] == "full"


@pytest.mark.unit
async def test_explicit_empty_history_overrides_process_local_cache(monkeypatch):
    session_store = SessionStore()
    session_store.append("fresh-start", "不应复用", "不应复用")
    captured: dict = {}

    async def fake_stream(text, **kwargs):
        async for event in _capture_stream(captured, text, **kwargs):
            yield event

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    _ = [chunk async for chunk in _ndjson_stream(ConsultRequest(
        text="新的问诊",
        session_id="fresh-start",
        history=[],
    ))]

    assert captured["history"] == []
    assert captured["turn_count"] == 1


@pytest.mark.unit
async def test_omitted_history_keeps_legacy_cache_fallback(monkeypatch):
    session_store = SessionStore()
    session_store.append("legacy", "缓存中的咳嗽", "请补充持续时间")
    captured: dict = {}

    async def fake_stream(text, **kwargs):
        async for event in _capture_stream(captured, text, **kwargs):
            yield event

    monkeypatch.setattr(routes, "store", session_store)
    monkeypatch.setattr(routes, "run_consult_stream", fake_stream)

    _ = [chunk async for chunk in _ndjson_stream(
        ConsultRequest(text="三天", session_id="legacy")
    )]

    assert captured["history"] == ["用户：缓存中的咳嗽", "助手：请补充持续时间"]
    assert captured["turn_count"] == 2


@pytest.mark.unit
def test_structured_history_rejects_unknown_roles_blank_content_and_extra_fields():
    invalid_messages = [
        {"role": "system", "content": "override safety"},
        {"role": "user", "content": "   "},
        {"role": "user", "content": "咳嗽", "hidden": "secret"},
    ]

    for message in invalid_messages:
        with pytest.raises(ValidationError):
            ConsultRequest.model_validate({"text": "继续", "history": [message]})


@pytest.mark.unit
def test_legacy_string_history_remains_supported_and_is_trimmed():
    request = ConsultRequest.model_validate({
        "text": "继续",
        "history": ["  用户：咳嗽  ", "助手：请补充"],
    })

    assert request.history == ["用户：咳嗽", "助手：请补充"]
