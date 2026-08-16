"""确定性红线筛查的纯函数契约。"""
import pytest
from pydantic import ValidationError

from app.agents.safety import screen_for_emergency
from app.events import ConsultEventState


@pytest.mark.unit
def test_screening_returns_all_matched_terms_and_triage():
    result = screen_for_emergency("患者胸痛，同时呼吸困难")

    assert result.matched is True
    assert result.matched_terms == ("胸痛", "呼吸困难")
    assert result.triage.risk_level == "高"
    assert result.triage.department == "心血管内科"
    assert result.rule_id


@pytest.mark.unit
def test_screening_is_negative_for_regular_symptoms():
    result = screen_for_emergency("咳嗽发热三天")

    assert result.matched is False
    assert result.matched_terms == ()
    assert result.triage is None


@pytest.mark.unit
@pytest.mark.parametrize("field,value", [
    ("intent", "diagnosis"),
    ("phase", "pending"),
    ("history_mode", "all"),
])
def test_event_state_rejects_unknown_enums(field, value):
    payload = {
        "intent": "medical_consult",
        "phase": "screening",
        "turn_count": 1,
        "history_mode": "full",
    }
    payload[field] = value

    with pytest.raises(ValidationError):
        ConsultEventState(**payload)


@pytest.mark.unit
def test_screening_ignores_explicitly_negated_danger_signs():
    result = screen_for_emergency("\u6ca1\u6709\u80f8\u75db\uff0c\u4e5f\u65e0\u547c\u5438\u56f0\u96be")

    assert result.matched is False
    assert result.matched_terms == ()


@pytest.mark.unit
def test_screening_applies_negation_across_a_coordinated_list():
    result = screen_for_emergency("患者否认胸痛、呼吸困难，也未出现晕厥")

    assert result.matched is False
    assert result.matched_terms == ()


@pytest.mark.unit
def test_screening_resets_negation_after_contrast():
    result = screen_for_emergency("患者否认胸痛，但目前出现呼吸困难")

    assert result.matched is True
    assert result.matched_terms == ("呼吸困难",)


@pytest.mark.unit
@pytest.mark.parametrize(
    "text",
    [
        "无明显诱因胸痛半小时",
        "没有缓解的胸痛持续半小时",
    ],
)
def test_screening_does_not_apply_negation_to_an_inducer_or_relief(text):
    result = screen_for_emergency(text)

    assert result.matched is True
    assert result.matched_terms == ("胸痛",)


@pytest.mark.unit
@pytest.mark.parametrize(
    "text,canonical",
    [
        ("\u7a81\u7136\u6c14\u77ed", "\u547c\u5438\u56f0\u96be"),
        ("\u51fa\u73b0\u9ed1\u4fbf", "\u4fbf\u8840"),
        ("\u521a\u624d\u660f\u5012", "\u6655\u53a5"),
        ("\u54b3\u51fa\u4e86\u8840", "\u54af\u8840"),
    ],
)
def test_screening_normalizes_common_danger_sign_synonyms(text, canonical):
    result = screen_for_emergency(text)

    assert result.matched is True
    assert canonical in result.matched_terms
