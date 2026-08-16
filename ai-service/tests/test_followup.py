"""followup 模块契约测试：充分性判断 + 追问生成。"""
import pytest

from app.agents.followup import build_followup, is_sufficient
from app.schemas import StructuredSymptoms


def _make(symptoms=(), duration=None, severity=None, red_flags=()):
    return StructuredSymptoms(
        symptoms=symptoms,
        duration=duration,
        severity=severity,
        red_flags=red_flags,
        history=(),
        raw_text="",
    )


# --- is_sufficient ---

@pytest.mark.unit
def test_sufficient_red_flag_single_symptom():
    s = _make(symptoms=("胸痛",), red_flags=("胸痛",))
    assert is_sufficient(s) is True


@pytest.mark.unit
def test_sufficient_two_symptoms():
    s = _make(symptoms=("咳嗽", "发烧"))
    assert is_sufficient(s) is True


@pytest.mark.unit
def test_sufficient_one_symptom_with_duration():
    s = _make(symptoms=("头痛",), duration="两天")
    assert is_sufficient(s) is True


@pytest.mark.unit
def test_sufficient_one_symptom_with_severity():
    s = _make(symptoms=("头痛",), severity="严重")
    assert is_sufficient(s) is True


@pytest.mark.unit
def test_insufficient_no_symptoms():
    assert is_sufficient(_make()) is False


@pytest.mark.unit
def test_insufficient_one_symptom_only():
    assert is_sufficient(_make(symptoms=("头痛",))) is False


# --- build_followup ---

@pytest.mark.unit
def test_followup_no_symptoms_asks_about_symptoms():
    fq = build_followup(_make())
    assert "symptoms" in fq.missing
    assert len(fq.question) > 0


@pytest.mark.unit
def test_followup_one_symptom_no_duration_asks_duration():
    fq = build_followup(_make(symptoms=("头痛",)))
    assert "duration" in fq.missing
    assert "头痛" in fq.question


@pytest.mark.unit
def test_followup_one_symptom_with_duration_asks_more():
    fq = build_followup(_make(symptoms=("头痛",), duration="两天"))
    assert "more_symptoms" in fq.missing
    assert "头痛" in fq.question


@pytest.mark.unit
def test_followup_is_frozen():
    fq = build_followup(_make())
    with pytest.raises(Exception):
        fq.question = "修改"
