"""SessionStore 契约测试。"""
import pytest

from app.session import SessionStore, _TTL
from datetime import datetime, timedelta


@pytest.fixture
def store():
    return SessionStore()


@pytest.mark.unit
def test_unknown_session_returns_empty(store):
    assert store.get_history("non-existent") == []


@pytest.mark.unit
def test_none_session_returns_empty(store):
    assert store.get_history(None) == []


@pytest.mark.unit
def test_single_turn_append(store):
    store.append("s1", "我头痛", "建议就诊神经内科")
    hist = store.get_history("s1")
    assert hist == ["用户：我头痛", "助手：建议就诊神经内科"]


@pytest.mark.unit
def test_multiple_turns_accumulate(store):
    store.append("s2", "我头痛", "能描述一下多久了？")
    store.append("s2", "两天了", "建议就诊神经内科")
    hist = store.get_history("s2")
    assert len(hist) == 4
    assert hist[0] == "用户：我头痛"
    assert hist[2] == "用户：两天了"


@pytest.mark.unit
def test_sixth_turn_uses_summary_and_only_two_recent_turns(store):
    first_full_text = "第一轮完整原文-" + "胸闷描述" * 30
    store.append("long", first_full_text, "第一轮回复" * 20)
    for turn in range(2, 6):
        store.append("long", f"第{turn}轮用户消息", f"第{turn}轮助手回复")

    context = store.get_context("long")
    prompt_history = "\n".join(context.history)

    assert context.turn_count == 6
    assert context.history_mode == "summary"
    assert first_full_text not in prompt_history
    assert "第4轮用户消息" in prompt_history
    assert "第5轮用户消息" in prompt_history
    assert "用户：第3轮用户消息" not in prompt_history
    assert len(context.summary) <= 1200


@pytest.mark.unit
def test_summary_storage_remains_bounded_after_many_turns(store):
    for turn in range(30):
        store.append("bounded", f"用户消息{turn}" * 20, f"助手回复{turn}" * 20)

    context = store.get_context("bounded")

    assert context.turn_count == 31
    assert context.history_mode == "summary"
    assert len(context.summary) <= 1200
    assert len(context.history) == 5  # one summary line plus two user/assistant pairs


@pytest.mark.unit
def test_history_is_copy(store):
    store.append("s3", "咳嗽", "建议就诊呼吸内科")
    hist = store.get_history("s3")
    hist.append("污染项")
    assert len(store.get_history("s3")) == 2


@pytest.mark.unit
def test_expired_session_returns_empty(store):
    store.append("s4", "咳嗽", "建议呼吸内科")
    # 手动推移 last_active 使其超时
    store._data["s4"].last_active = datetime.now() - _TTL - timedelta(seconds=1)
    assert store.get_history("s4") == []
    assert "s4" not in store._data


@pytest.mark.unit
def test_evict_expired_removes_old_sessions(store):
    store.append("a", "咳嗽", "建议呼吸内科")
    store.append("b", "胸痛", "建议心内科")
    store._data["a"].last_active = datetime.now() - _TTL - timedelta(seconds=1)
    count = store.evict_expired()
    assert count == 1
    assert "a" not in store._data
    assert "b" in store._data


@pytest.mark.unit
def test_count_prunes_expired_sessions_without_explicit_evict_call():
    bounded = SessionStore(max_sessions=2)
    bounded.append("expired", "text", "answer")
    bounded._data["expired"].last_active = datetime.now() - _TTL - timedelta(seconds=1)

    assert bounded.count() == 0
    assert "expired" not in bounded._data


@pytest.mark.unit
def test_session_store_evicts_oldest_session_at_capacity():
    bounded = SessionStore(max_sessions=2)
    bounded.append("oldest", "text", "answer")
    bounded.append("newer", "text", "answer")
    bounded._data["oldest"].last_active -= timedelta(seconds=1)

    bounded.append("newest", "text", "answer")

    assert set(bounded._data) == {"newer", "newest"}


@pytest.mark.unit
def test_same_session_turns_have_deterministic_exclusion():
    bounded = SessionStore()

    assert bounded.begin_turn("same-session") is True
    assert bounded.begin_turn("same-session") is False
    bounded.end_turn("same-session")
    assert bounded.begin_turn("same-session") is True


@pytest.mark.unit
def test_session_store_bounds_recent_text():
    bounded = SessionStore(max_text_chars=8)
    bounded.append("bounded", "0123456789", "abcdefghij")

    assert bounded.get_history("bounded") == ["用户：01234567", "助手：abcdefgh"]
