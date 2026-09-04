"""Ollama chat transport must consume the real streaming protocol fail-closed."""
from __future__ import annotations

import json

import pytest

from app import ollama_client


class _FakeStreamResponse:
    def __init__(self, lines: list[str], *, status_error: Exception | None = None) -> None:
        self._lines = lines
        self._status_error = status_error

    def raise_for_status(self) -> None:
        if self._status_error:
            raise self._status_error

    async def aiter_lines(self):
        for line in self._lines:
            yield line


class _StreamContext:
    def __init__(self, response: _FakeStreamResponse) -> None:
        self.response = response

    async def __aenter__(self):
        return self.response

    async def __aexit__(self, *_args):
        return False


class _FakeClient:
    def __init__(self, response: _FakeStreamResponse, captured: dict, **_kwargs) -> None:
        self.response = response
        self.captured = captured

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    def stream(self, method: str, url: str, *, json: dict):
        self.captured.update({"method": method, "url": url, "json": json})
        return _StreamContext(self.response)


def _line(content: str, *, done: bool = False) -> str:
    return json.dumps({"message": {"content": content}, "done": done})


@pytest.mark.unit
async def test_chat_uses_streaming_api_and_assembles_only_a_completed_response(monkeypatch):
    captured: dict = {}
    response = _FakeStreamResponse([
        _line('{"symptoms":['),
        _line('"咳嗽","发热"]}'),
        _line("", done=True),
    ])
    monkeypatch.setattr(
        ollama_client.httpx,
        "AsyncClient",
        lambda **kwargs: _FakeClient(response, captured, **kwargs),
    )

    result = await ollama_client.chat("extract")

    assert result == '{"symptoms":["咳嗽","发热"]}'
    assert captured["method"] == "POST"
    assert captured["json"]["stream"] is True


@pytest.mark.unit
@pytest.mark.parametrize(
    "lines",
    [
        [_line("partial")],
        ["not-json", _line("", done=True)],
        [json.dumps({"message": {"content": 123}, "done": True})],
        [json.dumps({"error": "private model detail", "done": True})],
    ],
)
async def test_chat_rejects_incomplete_or_malformed_stream_without_returning_partial_text(
    monkeypatch,
    lines,
):
    captured: dict = {}
    response = _FakeStreamResponse(lines)
    monkeypatch.setattr(
        ollama_client.httpx,
        "AsyncClient",
        lambda **kwargs: _FakeClient(response, captured, **kwargs),
    )

    with pytest.raises(RuntimeError, match="Ollama chat stream failed") as exc_info:
        await ollama_client.chat("extract")

    assert "private model detail" not in str(exc_info.value)
