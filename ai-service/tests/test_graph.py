"""LangGraph 编排契约测试。全程 LLM + embed 打桩，离线确定性。"""
from uuid import UUID

import numpy as np
import pytest

from app.agents.graph import run_consult_stream
from app.rag.index import build_index

_AXES = {
    "胸痛": [1.0, 0.0, 0.0],
    "心悸": [0.9, 0.0, 0.0],
    "呼吸困难": [0.0, 1.0, 0.0],
    "皮疹": [0.0, 0.0, 1.0],
}

_CORPUS = (
    {"doc_id": "c1", "department": "心血管内科", "source": "心血管", "text": "胸痛心悸。"},
    {"doc_id": "r1", "department": "呼吸内科", "source": "呼吸", "text": "呼吸困难。"},
    {"doc_id": "d1", "department": "皮肤科", "source": "皮肤", "text": "皮疹。"},
)


async def _fake_embed(text: str) -> list[float]:
    vec = np.zeros(3, dtype="float32")
    for kw, axis in _AXES.items():
        if kw in text:
            vec += np.array(axis, dtype="float32")
    if not vec.any():
        vec += 0.01
    return vec.tolist()


def _fake_chat(response: str):
    async def _chat(prompt, system=None):
        return response
    return _chat


def _completed_nodes(events):
    return [
        event["node"]
        for event in events
        if event["type"] == "node" and event["status"] == "completed"
    ]


def _completed_event(events, node):
    return next(
        event
        for event in events
        if event.get("node") == node and event.get("status") == "completed"
    )


@pytest.fixture
async def offline_index():
    return await build_index(_fake_embed, _CORPUS)


@pytest.mark.unit
async def test_stream_emits_screening_and_four_nodes_then_done(offline_index):
    index, chunks = offline_index
    fake = _fake_chat('{"symptoms":["咳嗽","发热"],"duration":"3天"}')
    events = [e async for e in run_consult_stream(
        "我咳嗽发热三天", chat_fn=fake, embed_fn=_fake_embed, index=index, chunks=chunks
    )]

    assert _completed_nodes(events) == [
        "safety_screen", "extract", "retrieve", "classify", "compose"
    ]
    assert events[-1]["type"] == "done"


@pytest.mark.unit
async def test_stream_high_risk_short_circuits_llm_and_embedding():
    calls = {"chat": 0, "embed": 0}

    async def forbidden_chat(*args, **kwargs):
        calls["chat"] += 1
        raise AssertionError("red-line path must not call chat_fn")

    async def forbidden_embed(*args, **kwargs):
        calls["embed"] += 1
        raise AssertionError("red-line path must not call embed_fn")

    events = [e async for e in run_consult_stream(
        "突发胸痛并呼吸困难", chat_fn=forbidden_chat, embed_fn=forbidden_embed
    )]

    assert calls == {"chat": 0, "embed": 0}
    assert _completed_nodes(events) == ["safety_screen", "classify", "compose"]

    triage = _completed_event(events, "classify")["data"]["triage"]
    assert triage["department"] == "心血管内科"
    assert triage["risk_level"] == "高"

    answer = _completed_event(events, "compose")["data"]["answer"]
    assert "立即就医" in answer["text"] or "呼叫急救" in answer["text"]
    assert "不替代" in answer["safety_boundary"]


@pytest.mark.unit
async def test_stream_sparse_symptoms_triggers_followup(offline_index):
    index, chunks = offline_index
    # 只有一个症状且无 duration/severity → 不充分 → ask_followup 路径
    fake = _fake_chat('{"symptoms":["头痛"]}')
    events = [e async for e in run_consult_stream(
        "头痛", chat_fn=fake, embed_fn=_fake_embed, index=index, chunks=chunks
    )]

    nodes = _completed_nodes(events)
    assert nodes == ["safety_screen", "extract", "ask_followup"]
    followup_event = _completed_event(events, "ask_followup")
    assert followup_event["data"]["followup"]["question"]
    assert events[-1]["type"] == "done"


@pytest.mark.unit
async def test_stream_no_symptoms_triggers_followup(offline_index):
    index, chunks = offline_index
    fake = _fake_chat('{}')
    events = [e async for e in run_consult_stream(
        "我不舒服", chat_fn=fake, embed_fn=_fake_embed, index=index, chunks=chunks
    )]

    nodes = _completed_nodes(events)
    assert "ask_followup" in nodes
    assert "retrieve" not in nodes


@pytest.mark.unit
async def test_stream_history_passed_to_extract(offline_index):
    """history 应传给 extract；打桩 chat_fn 记录收到的 prompt。"""
    index, chunks = offline_index
    received: list[str] = []

    async def _recording_chat(prompt, system=None):
        received.append(prompt)
        return '{"symptoms":["头痛","发烧"]}'

    events = [e async for e in run_consult_stream(
        "还有发烧",
        history=["用户：我头痛", "助手：能描述多久了？"],
        chat_fn=_recording_chat,
        embed_fn=_fake_embed,
        index=index,
        chunks=chunks,
    )]
    assert received, "extract 应调用 chat_fn"
    assert "用户：我头痛" in received[0]


@pytest.mark.unit
async def test_health_context_is_separate_background_and_never_rewrites_raw_text(offline_index):
    index, chunks = offline_index
    received: list[str] = []

    async def _recording_chat(prompt, system=None):
        received.append(prompt)
        return '{"symptoms":["咳嗽","发热"],"duration":"3天"}'

    text = "当前咳嗽发热"
    events = [e async for e in run_consult_stream(
        text,
        health_context={
            "allergies": "青霉素",
            "conditions": "哮喘",
            "notes": "意识不清（既往记录，不是本轮症状）",
        },
        chat_fn=_recording_chat,
        embed_fn=_fake_embed,
        index=index,
        chunks=chunks,
    )]

    assert received
    assert "青霉素" in received[0]
    assert "哮喘" in received[0]
    assert received[0].count("青霉素") == 1
    assert _completed_nodes(events) == [
        "safety_screen", "extract", "retrieve", "classify", "compose"
    ]
    extracted = _completed_event(events, "extract")["data"]["symptoms"]
    assert extracted["raw_text"] == text
    assert "青霉素" not in extracted["raw_text"]
    assert "意识不清" not in extracted["raw_text"]


@pytest.mark.unit
async def test_events_have_stable_envelope_and_strict_sequence(offline_index):
    index, chunks = offline_index
    session_id = "1779673a-c983-47e4-9715-f2d9548f469a"
    fake = _fake_chat('{"symptoms":["咳嗽","发热"],"duration":"3天"}')

    events = [e async for e in run_consult_stream(
        "咳嗽发热三天",
        session_id=session_id,
        chat_fn=fake,
        embed_fn=_fake_embed,
        index=index,
        chunks=chunks,
    )]

    trace_ids = {event["trace_id"] for event in events}
    assert len(trace_ids) == 1
    UUID(trace_ids.pop())
    assert [event["sequence"] for event in events] == list(range(1, len(events) + 1))
    assert all(event["protocol_version"] == "1.0" for event in events)
    assert all(event["session_id"] == session_id for event in events)
    assert all(event["state"]["intent"] == "medical_consult" for event in events)
    assert all(event["state"]["history_mode"] == "full" for event in events)

    node_events = [event for event in events if event["type"] == "node"]
    assert all(event["status"] in {"started", "completed"} for event in node_events)
    for node in _completed_nodes(events):
        statuses = [event["status"] for event in node_events if event["node"] == node]
        assert statuses == ["started", "completed"]
        assert _completed_event(events, node)["elapsed_ms"] >= 0

    assert events[-1]["type"] == "done"
    assert events[-1]["state"]["phase"] == "completed"


@pytest.mark.unit
async def test_emergency_event_state_is_escalated():
    events = [e async for e in run_consult_stream(
        "意识不清", chat_fn=None, embed_fn=None
    )]

    assert events[0]["node"] == "safety_screen"
    assert events[0]["status"] == "started"
    assert events[0]["state"]["intent"] == "medical_consult"
    assert all(event["state"]["intent"] == "emergency" for event in events[1:])
    assert events[-1]["type"] == "done"
    assert events[-1]["state"]["phase"] == "escalated"


@pytest.mark.unit
async def test_stream_failure_ends_with_a_valid_sanitized_error_event():
    async def failing_chat(*args, **kwargs):
        raise RuntimeError("private provider detail")

    events = [event async for event in run_consult_stream(
        "咳嗽三天并伴有发热",
        session_id="1779673a-c983-47e4-9715-f2d9548f469a",
        chat_fn=failing_chat,
    )]

    assert events[-1]["type"] == "error"
    assert events[-1]["status"] == "error"
    assert events[-1]["state"]["phase"] == "failed"
    assert "private provider detail" not in events[-1]["data"]["detail"]
    assert [event["sequence"] for event in events] == list(range(1, len(events) + 1))
    assert all(event["protocol_version"] == "1.0" for event in events)
