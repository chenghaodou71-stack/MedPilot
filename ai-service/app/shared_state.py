"""Shared runtime coordination backed by Redis.

Redis contains only short-lived coordination values. Session identifiers are
HMAC-derived before they become keys, and no patient text or clinical result
is written here. MySQL remains the source of truth for clinical data.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import time
from typing import Any


class SharedStateUnavailable(RuntimeError):
    """The configured shared-state backend cannot be used safely."""


_RELEASE_SCRIPT = """
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
end
return 0
"""

_ACQUIRE_CAPACITY_SCRIPT = """
local now = tonumber(ARGV[1])
local expires = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('zremrangebyscore', KEYS[1], '-inf', now)
if redis.call('zcard', KEYS[1]) >= limit then
  return 0
end
redis.call('zadd', KEYS[1], expires, ARGV[4])
redis.call('pexpire', KEYS[1], math.max(1000, expires - now + 1000))
return 1
"""

_RELEASE_CAPACITY_SCRIPT = """
return redis.call('zrem', KEYS[1], ARGV[1])
"""


class RedisSharedState:
    def __init__(
        self,
        *,
        required: bool,
        client: Any | None,
        key_prefix: str = "medpilot",
        key_hmac_secret: str = "",
        configuration_error: str | None = None,
    ) -> None:
        self.required = bool(required)
        self.client = client
        self.key_prefix = _safe_prefix(key_prefix)
        self._secret = key_hmac_secret.encode("utf-8")
        self.configuration_error = configuration_error

    @classmethod
    def from_env(cls) -> "RedisSharedState":
        required = _truthy(os.getenv("MEDPILOT_REDIS_REQUIRED", "false"))
        url = os.getenv("MEDPILOT_REDIS_URL", "").strip()
        prefix = os.getenv("MEDPILOT_REDIS_KEY_PREFIX", "medpilot").strip()
        secret = os.getenv("MEDPILOT_REDIS_KEY_HMAC_SECRET", "").strip()
        if not secret:
            secret = os.getenv("MEDPILOT_AI_SERVICE_TOKEN", "").strip()
        if not url:
            return cls(
                required=required,
                client=None,
                key_prefix=prefix,
                key_hmac_secret=secret,
                configuration_error="MEDPILOT_REDIS_URL is required",
            )
        if len(secret.encode("utf-8")) < 16:
            return cls(
                required=required,
                client=None,
                key_prefix=prefix,
                key_hmac_secret=secret,
                configuration_error=(
                    "MEDPILOT_REDIS_KEY_HMAC_SECRET must contain at least 16 bytes"
                ),
            )
        try:
            import redis.asyncio as redis

            client = redis.Redis.from_url(
                url,
                decode_responses=True,
                health_check_interval=30,
                socket_connect_timeout=2,
                socket_timeout=2,
            )
        except Exception as exc:  # pragma: no cover - depends on deployment image
            return cls(
                required=required,
                client=None,
                key_prefix=prefix,
                key_hmac_secret=secret,
                configuration_error=f"Redis client unavailable: {exc.__class__.__name__}",
            )
        return cls(
            required=required,
            client=client,
            key_prefix=prefix,
            key_hmac_secret=secret,
        )

    def key(self, namespace: str, value: str) -> str:
        if not self._secret:
            # A missing secret is never allowed to turn a user-controlled value
            # into a Redis key. Development memory mode does not call this.
            raise SharedStateUnavailable("Redis key HMAC secret is not configured")
        digest = hmac.new(
            self._secret,
            f"{namespace}\x00{value}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return f"{self.key_prefix}:{namespace}:{digest}"

    async def require_available(self) -> bool:
        if self.client is None:
            if self.required:
                raise SharedStateUnavailable(self.configuration_error or "Redis is unavailable")
            return False
        try:
            await self.client.ping()
        except Exception as exc:
            if self.required:
                raise SharedStateUnavailable("Redis is unavailable") from exc
            return False
        return True

    async def acquire_lease(self, namespace: str, value: str, lease_ms: int) -> str | None:
        if not await self.require_available():
            return None
        token = secrets.token_urlsafe(24)
        try:
            acquired = await self.client.set(
                self.key(namespace, value), token, nx=True, px=max(1000, int(lease_ms))
            )
        except Exception as exc:
            if self.required:
                raise SharedStateUnavailable("Redis lease operation failed") from exc
            return None
        return token if acquired else None

    async def release_lease(self, namespace: str, value: str, token: str) -> None:
        if self.client is None:
            return
        try:
            await self.client.eval(
                _RELEASE_SCRIPT,
                1,
                self.key(namespace, value),
                token,
            )
        except Exception as exc:
            if self.required:
                raise SharedStateUnavailable("Redis lease release failed") from exc

    async def acquire_capacity(
        self, namespace: str, token_value: str, limit: int, lease_ms: int
    ) -> str | None:
        if not await self.require_available():
            return None
        token = secrets.token_urlsafe(24)
        now = int(time.time() * 1000)
        expires = now + max(1000, int(lease_ms))
        try:
            result = await self.client.eval(
                _ACQUIRE_CAPACITY_SCRIPT,
                1,
                self.key(namespace, token_value),
                now,
                expires,
                max(1, int(limit)),
                token,
            )
        except Exception as exc:
            if self.required:
                raise SharedStateUnavailable("Redis capacity operation failed") from exc
            return None
        return token if int(result or 0) == 1 else None

    async def release_capacity(self, namespace: str, token_value: str, token: str) -> None:
        if self.client is None:
            return
        try:
            await self.client.eval(
                _RELEASE_CAPACITY_SCRIPT,
                1,
                self.key(namespace, token_value),
                token,
            )
        except Exception as exc:
            if self.required:
                raise SharedStateUnavailable("Redis capacity release failed") from exc

    async def close(self) -> None:
        if self.client is None:
            return
        close = getattr(self.client, "aclose", None)
        if close is not None:
            await close()


def _safe_prefix(value: str) -> str:
    normalized = value.strip() if value else "medpilot"
    if not normalized or len(normalized) > 64 or any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        for char in normalized
    ):
        raise RuntimeError("MEDPILOT_REDIS_KEY_PREFIX contains invalid characters")
    return normalized


def _truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}
