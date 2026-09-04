"""Redis shared-state safety contracts."""
from __future__ import annotations

import pytest

from app.session import RedisSessionCoordinator
from app.shared_state import RedisSharedState, SharedStateUnavailable


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    async def set(self, key, value, *, nx=False, px=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    async def eval(self, _script, _num_keys, key, token):
        if self.values.get(key) == token:
            del self.values[key]
            return 1
        return 0

    async def ping(self):
        return True


@pytest.mark.unit
async def test_redis_session_lock_uses_a_token_safe_release():
    redis = FakeRedis()
    state = RedisSharedState(required=True, client=redis, key_prefix="medpilot-test", key_hmac_secret="test-secret")
    coordinator = RedisSessionCoordinator(state, default_lease_seconds=60)

    assert await coordinator.begin_turn("session-a") is True
    key = state.key("session-turn", "session-a")
    held_token = redis.values[key]
    redis.values[key] = "different-owner-token"

    await coordinator.end_turn("session-a")

    assert redis.values[key] == "different-owner-token"
    assert held_token != redis.values[key]


@pytest.mark.unit
async def test_required_redis_without_a_client_fails_closed():
    state = RedisSharedState(
        required=True,
        client=None,
        key_prefix="medpilot-test",
        key_hmac_secret="test-secret",
        configuration_error="MEDPILOT_REDIS_URL is required",
    )

    with pytest.raises(SharedStateUnavailable):
        await state.require_available()
