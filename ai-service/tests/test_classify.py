"""辅助分诊模块契约测试。纯函数，重点覆盖高风险优先规则。"""
import pytest

from app.agents.classify import classify
from app.schemas import RankedEvidence, StructuredSymptoms, TriageResult


def _symptoms(raw: str, names=()):
    return StructuredSymptoms(symptoms=tuple(names), raw_text=raw)


def _evidence(dept: str, score: float, doc_id="d1"):
    return RankedEvidence(
        citation_id=f"{doc_id}#0",
        doc_id=doc_id,
        chunk_id=f"{doc_id}#0",
        quote="t",
        department=dept,
        source="s",
        score=score,
        index_version="test",
    )


@pytest.mark.unit
def test_chest_pain_maps_to_cardiology_high_risk():
    result = classify(StructuredSymptoms(raw_text="我胸痛", red_flags=("胸痛",)), [])
    assert result.department == "心血管内科"
    assert result.risk_level == "高"
    assert result.matched_rule is not None


@pytest.mark.unit
def test_dyspnea_maps_to_respiratory_high_risk():
    result = classify(
        StructuredSymptoms(raw_text="呼吸困难", red_flags=("呼吸困难",)), []
    )
    assert result.department == "呼吸内科"
    assert result.risk_level == "高"


@pytest.mark.unit
def test_evidence_voting_when_no_high_risk():
    ev = [_evidence("消化内科", 0.8, "a"), _evidence("消化内科", 0.5, "b"),
          _evidence("皮肤科", 0.2, "c")]
    result = classify(_symptoms("肚子不舒服"), ev)
    assert result.department == "消化内科"
    assert result.risk_level == "中"
    assert result.matched_rule is None


@pytest.mark.unit
def test_evidence_voting_ignores_departments_outside_supported_scope():
    result = classify(_symptoms("不舒服"), [_evidence("外科", 0.99)])

    assert result.department == "全科/建议线下分诊台"
    assert result.risk_level == "低"


@pytest.mark.unit
def test_no_evidence_low_risk_fallback():
    result = classify(_symptoms("有点累"), [])
    assert result.risk_level == "低"
    assert 0.0 <= result.confidence <= 1.0


@pytest.mark.unit
def test_triage_model_rejects_confidence_outside_unit_interval():
    with pytest.raises(Exception):
        TriageResult(
            department="test",
            risk_level="low",
            confidence=1.1,
            urgency="test",
        )


@pytest.mark.unit
def test_triage_model_rejects_unknown_enums():
    with pytest.raises(Exception):
        TriageResult(
            department="外科",
            risk_level="critical",
            confidence=0.5,
            urgency="立即服药",
        )


@pytest.mark.unit
def test_loss_of_consciousness_maps_to_neurology_high_risk():
    result = classify(
        StructuredSymptoms(raw_text="意识不清", red_flags=("意识不清",)), []
    )
    assert result.department == "神经内科"
    assert result.risk_level == "高"
    assert result.matched_rule is not None


@pytest.mark.unit
def test_major_bleeding_maps_to_er_high_risk():
    result = classify(StructuredSymptoms(raw_text="大出血", red_flags=("大出血",)), [])
    assert result.department == "急诊科"
    assert result.risk_level == "高"
    assert "急救" in result.urgency


@pytest.mark.unit
def test_all_red_flag_keywords_have_high_risk_rule():
    """RED_FLAG_KEYWORDS 与 HIGH_RISK_RULES 应一一对应。"""
    from app.agents.extract import RED_FLAG_KEYWORDS
    from app.agents.classify import HIGH_RISK_RULES
    rule_keywords = {r[0] for r in HIGH_RISK_RULES}
    for kw in RED_FLAG_KEYWORDS:
        assert kw in rule_keywords, f"RED_FLAG '{kw}' 缺少对应的 HIGH_RISK_RULE"


@pytest.mark.unit
def test_high_risk_confidence_is_fixed():
    result = classify(StructuredSymptoms(raw_text="胸痛", red_flags=("胸痛",)), [])
    assert result.confidence == 0.9


@pytest.mark.unit
def test_classifier_does_not_rescan_raw_text_or_model_symptom_names():
    symptoms = StructuredSymptoms(
        raw_text="没有胸痛",
        symptoms=("胸痛",),
        red_flags=(),
    )

    result = classify(symptoms, [])

    assert result.risk_level == "低"
    assert result.matched_rule is None


@pytest.mark.unit
def test_evidence_confidence_within_bounds():
    ev = [_evidence("皮肤科", 0.9)]
    result = classify(_symptoms("皮疹"), ev)
    assert 0.0 <= result.confidence <= 1.0


@pytest.mark.unit
def test_high_risk_result_exposes_deterministic_rule_factor():
    result = classify(StructuredSymptoms(raw_text="胸痛", red_flags=("胸痛",)), [])

    assert result.support_score == 1.0
    assert result.abstained is False
    assert result.factors
    assert result.factors[0].kind == "rule"
    assert result.factors[0].label == "胸痛"
    assert result.factors[0].support == 1.0
    assert "胸痛" in result.explanation


@pytest.mark.unit
def test_evidence_result_exposes_ranked_support_factors():
    result = classify(
        _symptoms("皮疹"),
        [_evidence("皮肤科", 0.9), _evidence("呼吸内科", 0.1, "r2")],
    )

    assert 0.0 < result.support_score <= 1.0
    assert result.abstained is False
    assert [factor.kind for factor in result.factors] == ["evidence", "evidence"]
    assert result.factors[0].reference == "d1#0"


@pytest.mark.unit
def test_empty_evidence_abstains_without_claiming_accuracy():
    result = classify(_symptoms("有点累"), [])

    assert result.abstained is True
    assert result.support_score == 0.0
    assert result.factors == ()
    assert "证据" in result.explanation
