"""Bounded in-memory consultation context with a 30 minute TTL."""
from __future__ import annotations

import threading
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from app.shared_state import RedisSharedState, SharedStateUnavailable

_TTL = timedelta(minutes=30)
_SUMMARY_LIMIT = 1200
_FULL_HISTORY_TURNS = 5
_RECENT_TURNS = 2


@dataclass(frozen=True)
class SessionContext:
    history: list[str]
    turn_count: int
    history_mode: str
    summary: str


@dataclass(frozen=True)
class _Turn:
    user_text: str
    assistant_text: str


@dataclass
class _Session:
    turns: list[_Turn] = field(default_factory=list)
    summary: str = ""
    turn_count: int = 0
    last_active: datetime = field(default_factory=datetime.now)


def _turn_lines(turn: _Turn) -> list[str]:
    return [f"用户：{turn.user_text}", f"助手：{turn.assistant_text}"]


def _summary_line(turn: _Turn) -> str:
    user = " ".join(turn.user_text.split())[:80]
    assistant = " ".join(turn.assistant_text.split())[:100]
    return f"用户曾描述：{user}；系统曾回复：{assistant}。"


class SessionStore:
    def __init__(self, *, max_sessions: int = 1000, max_text_chars: int = 4000) -> None:
        if max_sessions < 1 or max_text_chars < 1:
            raise ValueError("session bounds must be positive")
        self._data: dict[str, _Session] = {}
        self._lock = threading.Lock()
        self._active_turns: set[str] = set()
        self._max_sessions = max_sessions
        self._max_text_chars = max_text_chars

    def get_context(self, session_id: str | None) -> SessionContext:
        if not session_id:
            return SessionContext([], 1, "full", "")
        with self._lock:
            session = self._active_session(session_id)
            if session is None:
                return SessionContext([], 1, "full", "")
            session.last_active = datetime.now()
            mode = "summary" if session.turn_count >= _FULL_HISTORY_TURNS else "full"
            history: list[str] = []
            if mode == "summary" and session.summary:
                history.append(f"对话摘要：{session.summary}")
            for turn in session.turns:
                history.extend(_turn_lines(turn))
            return SessionContext(
                history=history,
                turn_count=session.turn_count + 1,
                history_mode=mode,
                summary=session.summary,
            )

    def get_history(self, session_id: str | None) -> list[str]:
        """Compatibility helper used by existing callers and tests."""
        return list(self.get_context(session_id).history)

    def append(self, session_id: str, user_text: str, assistant_text: str) -> None:
        with self._lock:
            self._prune_expired_locked()
            if session_id not in self._data and len(self._data) >= self._max_sessions:
                oldest = min(self._data, key=lambda key: self._data[key].last_active)
                del self._data[oldest]
            session = self._data.setdefault(session_id, _Session())
            session.turns.append(_Turn(
                user_text[:self._max_text_chars],
                assistant_text[:self._max_text_chars],
            ))
            session.turn_count += 1
            if session.turn_count >= _FULL_HISTORY_TURNS:
                while len(session.turns) > _RECENT_TURNS:
                    summarized = _summary_line(session.turns.pop(0))
                    session.summary = "\n".join(
                        part for part in (session.summary, summarized) if part
                    )[-_SUMMARY_LIMIT:]
            session.last_active = datetime.now()

    def count(self) -> int:
        with self._lock:
            self._prune_expired_locked()
            return len(self._data)

    def evict_expired(self) -> int:
        with self._lock:
            return self._prune_expired_locked()

    def begin_turn(self, session_id: str) -> bool:
        with self._lock:
            if session_id in self._active_turns:
                return False
            self._active_turns.add(session_id)
            return True

    def end_turn(self, session_id: str) -> None:
        with self._lock:
            self._active_turns.discard(session_id)

    def _prune_expired_locked(self) -> int:
        cutoff = datetime.now() - _TTL
        expired = [key for key, value in self._data.items() if value.last_active < cutoff]
        for key in expired:
            del self._data[key]
            self._active_turns.discard(key)
        return len(expired)

    def _active_session(self, session_id: str) -> _Session | None:
        session = self._data.get(session_id)
        if session is None:
            return None
        if datetime.now() - session.last_active > _TTL:
            del self._data[session_id]
            return None
        return session


class RedisSessionCoordinator:
    """Distributed session-turn lease; clinical text stays in MySQL."""

    def __init__(self, state: RedisSharedState, *, default_lease_seconds: int = 240) -> None:
        if default_lease_seconds < 1:
            raise ValueError("session lease must be positive")
        self.state = state
        self.default_lease_seconds = default_lease_seconds
        self._tokens: dict[str, str] = {}
        self._lock = threading.Lock()

    async def begin_turn(self, session_id: str, *, lease_seconds: int | None = None) -> bool:
        if not session_id:
            return True
        token = await self.state.acquire_lease(
            "session-turn",
            session_id,
            max(1, lease_seconds or self.default_lease_seconds) * 1000,
        )
        if token is None:
            if self.state.required and self.state.client is None:
                raise SharedStateUnavailable("Redis session state is unavailable")
            return False
        with self._lock:
            self._tokens[session_id] = token
        return True

    async def end_turn(self, session_id: str) -> None:
        if not session_id:
            return
        with self._lock:
            token = self._tokens.pop(session_id, None)
        if token is not None:
            await self.state.release_lease("session-turn", session_id, token)


store = SessionStore()
