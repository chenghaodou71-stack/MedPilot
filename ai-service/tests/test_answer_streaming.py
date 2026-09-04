"""Validated answer-delta protocol tests."""
from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.agents.graph import run_consult_stream
from app.events import EventEmitter


@pytest.mark.unit
async def test_validated_emergency_answer_streams_deltas_then_matching_done_answer():
    events = [event async for event in run_consult_stream("突发胸痛并呼吸困难")]
    compose_index = next(
        index for index, event in enumerate(events)
        if event.get("node") == "compose" and event.get("status") == "completed"
    )
    delta_indexes = [
        index for index, event in enumerate(events) if event["type"] == "answer_delta"
    ]
    deltas = [events[index]["data"]["delta"] for index in delta_indexes]
    compose_answer = events[compose_index]["data"]["answer"]
    done = events[-1]

    assert len(deltas) >= 2
    assert delta_indexes[0] > compose_index
    assert delta_indexes[-1] < len(events) - 1
    assert all(delta and set(events[index]["data"]) == {"delta"} for index, delta in zip(delta_indexes, deltas))
    assert all(events[index]["status"] == "streaming" for index in delta_indexes)
    assert all(events[index]["state"]["phase"] == "composing" for index in delta_indexes)
    assert "".join(deltas) == compose_answer["text"]
    assert done["type"] == "done"
    assert done["data"]["answer"] == compose_answer
    assert done["data"]["answer"]["citations"] == []


@pytest.mark.unit
async def test_high_risk_path_exposes_rule_basis_without_fabricated_rag_citations():
    events = [event async for event in run_consult_stream("突然意识不清")]
    safety = next(
        event for event in events
        if event.get("node") == "safety_screen" and event.get("status") == "completed"
    )["data"]["safety"]
    triage = next(
        event for event in events
        if event.get("node") == "classify" and event.get("status") == "completed"
    )["data"]["triage"]
    answer = events[-1]["data"]["answer"]

    assert safety["rule_id"]
    assert triage["factors"] == [{
        "kind": "rule",
        "label": "意识不清",
        "reference": safety["rule_id"],
        "support": 1.0,
        "detail": "命中确定性安全筛查规则",
    }]
    assert safety["rule_id"] in answer["text"]
    assert answer["citations"] == []


@pytest.mark.unit
def test_answer_delta_payload_fails_closed_on_empty_or_extra_fields():
    emitter = EventEmitter("session")

    for invalid_delta in ("", "   "):
        with pytest.raises(ValidationError):
            emitter.emit(
                "answer_delta",
                status="streaming",
                phase="composing",
                data={"delta": invalid_delta},
            )
    with pytest.raises(ValidationError):
        emitter.emit(
            "answer_delta",
            status="streaming",
            phase="composing",
            data={"delta": "安全文本", "answer": "伪造全文"},
        )
    with pytest.raises(ValueError, match="unsupported event type"):
        emitter.emit("token", status="streaming", phase="composing", data={})
