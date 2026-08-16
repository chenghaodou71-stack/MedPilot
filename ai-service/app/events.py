"""NDJSON consult event protocol models and envelope builder."""
from __future__ import annotations

from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, Field

ConsultIntent = Literal["medical_consult", "emergency"]
ConsultPhase = Literal[
    "screening",
    "collecting",
    "summarizing",
    "retrieving",
    "triaging",
    "composing",
    "awaiting_followup",
    "completed",
    "escalated",
    "failed",
]
HistoryMode = Literal["full", "summary"]


class ConsultEventState(BaseModel):
    model_config = {"frozen": True}

    intent: ConsultIntent
    phase: ConsultPhase
    turn_count: int = Field(ge=1)
    history_mode: HistoryMode


class EventEmitter:
    """Builds validated event envelopes with one strictly increasing sequence."""

    def __init__(
        self,
        session_id: str | None,
        *,
        turn_count: int = 1,
        history_mode: HistoryMode = "full",
        intent: ConsultIntent = "medical_consult",
    ) -> None:
        self.trace_id = str(uuid4())
        self.session_id = session_id or str(uuid4())
        self.turn_count = turn_count
        self.history_mode = history_mode
        self.intent = intent
        self._sequence = 0

    def set_intent(self, intent: ConsultIntent) -> None:
        self.intent = intent

    def emit(
        self,
        event_type: str,
        *,
        phase: ConsultPhase,
        status: str,
        node: str | None = None,
        label: str | None = None,
        elapsed_ms: int = 0,
        data: dict | None = None,
    ) -> dict:
        self._sequence += 1
        event = {
            "protocol_version": "1.0",
            "trace_id": self.trace_id,
            "session_id": self.session_id,
            "sequence": self._sequence,
            "type": event_type,
            "status": status,
            "elapsed_ms": max(0, elapsed_ms),
            "state": ConsultEventState(
                intent=self.intent,
                phase=phase,
                turn_count=self.turn_count,
                history_mode=self.history_mode,
            ).model_dump(),
            "data": data or {},
        }
        if node is not None:
            event["node"] = node
        if label is not None:
            event["label"] = label
        return event


def terminal_error_event(
    previous: dict | None,
    *,
    session_id: str | None,
    code: str,
    detail: str,
) -> dict:
    """Continue an existing event envelope, or start one if no event was emitted."""
    if previous and all(
        key in previous
        for key in ("protocol_version", "trace_id", "session_id", "sequence", "state")
    ):
        state = dict(previous["state"])
        state["phase"] = "failed"
        return {
            "protocol_version": previous["protocol_version"],
            "trace_id": previous["trace_id"],
            "session_id": previous["session_id"],
            "sequence": int(previous["sequence"]) + 1,
            "type": "error",
            "status": "error",
            "elapsed_ms": 0,
            "state": state,
            "data": {"code": code, "detail": detail},
        }
    return EventEmitter(session_id).emit(
        "error",
        status="error",
        phase="failed",
        data={"code": code, "detail": detail},
    )
