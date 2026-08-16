"""Small concurrency and deadline guard for local inference work."""
from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager


class CapacityExceeded(RuntimeError):
    pass


class InferenceGate:
    def __init__(
        self,
        *,
        max_concurrency: int,
        queue_timeout: float,
        request_timeout: float,
    ) -> None:
        if max_concurrency < 1 or queue_timeout <= 0 or request_timeout <= 0:
            raise ValueError("inference limits must be positive")
        self._semaphore = asyncio.Semaphore(max_concurrency)
        self.queue_timeout = queue_timeout
        self.request_timeout = request_timeout

    async def acquire(self) -> None:
        try:
            await asyncio.wait_for(
                self._semaphore.acquire(), timeout=self.queue_timeout
            )
        except TimeoutError as exc:
            raise CapacityExceeded("inference capacity exceeded") from exc

    def release(self) -> None:
        self._semaphore.release()

    @asynccontextmanager
    async def slot(self):
        await self.acquire()
        try:
            async with asyncio.timeout(self.request_timeout):
                yield
        finally:
            self.release()


def _positive_int(name: str, default: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    if value < 1:
        raise RuntimeError(f"{name} must be positive")
    return value


def _positive_float(name: str, default: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError as exc:
        raise RuntimeError(f"{name} must be a number") from exc
    if value <= 0:
        raise RuntimeError(f"{name} must be positive")
    return value


inference_gate = InferenceGate(
    max_concurrency=_positive_int("MEDPILOT_INFERENCE_MAX_CONCURRENCY", 2),
    queue_timeout=_positive_float("MEDPILOT_INFERENCE_QUEUE_TIMEOUT_SECONDS", 0.05),
    request_timeout=_positive_float("MEDPILOT_CONSULT_TIMEOUT_SECONDS", 180.0),
)
