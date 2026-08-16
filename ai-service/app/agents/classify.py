"""辅助分诊模块：classify(symptoms, evidence) → TriageResult。

关键接缝——纯函数，不依赖 LLM。高风险症状走优先规则，可完全离线测试。"""
from __future__ import annotations

from collections import Counter

from app.schemas import RankedEvidence, StructuredSymptoms, TriageFactor, TriageResult

# 高风险优先规则：命中关键词 → (科室, 风险, 就医时效, 规则名)
HIGH_RISK_RULES: tuple[tuple[str, str, str, str, str], ...] = (
    ("胸痛", "心血管内科", "高", "建议立即就医或呼叫急救", "胸痛→心血管内科高风险"),
    ("呼吸困难", "呼吸内科", "高", "建议立即就医", "呼吸困难→呼吸内科高风险"),
    ("气促", "呼吸内科", "高", "建议立即就医", "气促→呼吸内科高风险"),
    ("咯血", "呼吸内科", "高", "建议立即就医", "咯血→呼吸内科高风险"),
    ("便血", "消化内科", "高", "建议尽快就医", "便血→消化内科高风险"),
    ("晕厥", "心血管内科", "高", "建议立即就医", "晕厥→心血管内科高风险"),
    ("意识不清", "神经内科", "高", "建议立即就医或呼叫急救", "意识不清→神经内科高风险"),
    ("大出血", "急诊科", "高", "建议立即呼叫急救", "大出血→急诊科高风险"),
)

DEFAULT_URGENCY = "建议尽早于门诊就诊"
SUPPORTED_EVIDENCE_DEPARTMENTS = frozenset(
    {"呼吸内科", "消化内科", "心血管内科", "皮肤科"}
)


def _high_risk_match(symptoms: StructuredSymptoms) -> TriageResult | None:
    matched_signs = set(symptoms.red_flags)
    for keyword, dept, risk, urgency, rule in HIGH_RISK_RULES:
        if keyword in matched_signs:
            return TriageResult(
                department=dept,
                risk_level=risk,
                confidence=0.9,
                urgency=urgency,
                matched_rule=rule,
                support_score=1.0,
                factors=(TriageFactor(
                    kind="rule",
                    label=keyword,
                    reference=rule,
                    support=1.0,
                    detail="命中确定性安全筛查规则",
                ),),
                explanation=f"安全筛查命中“{keyword}”规则，因此进入高风险快速通道。",
            )
    return None


def _department_from_evidence(evidence: list[RankedEvidence]) -> tuple[str, float]:
    """按证据科室加权投票，返回 (科室, 归一化置信度)。"""
    if not evidence:
        return "全科/建议线下分诊台", 0.3
    weights: Counter[str] = Counter()
    for e in evidence:
        weights[e.department] += e.score
    dept, top = max(weights.items(), key=lambda kv: kv[1])
    total = sum(weights.values()) or 1.0
    return dept, round(min(0.85, 0.4 + 0.45 * (top / total)), 4)


def classify(symptoms: StructuredSymptoms, evidence: list[RankedEvidence]) -> TriageResult:
    high = _high_risk_match(symptoms)
    if high is not None:
        return high

    supported_evidence = [
        item for item in evidence if item.department in SUPPORTED_EVIDENCE_DEPARTMENTS
    ]
    dept, confidence = _department_from_evidence(supported_evidence)
    if not supported_evidence:
        return TriageResult(
            department=dept,
            risk_level="低",
            confidence=confidence,
            urgency=DEFAULT_URGENCY,
            matched_rule=None,
            support_score=0.0,
            abstained=True,
            explanation="当前检索未获得足够的科室证据，建议补充信息或线下分诊。",
        )

    weights: Counter[str] = Counter()
    for item in supported_evidence:
        weights[item.department] += item.score
    total = sum(weights.values()) or 1.0
    ranked_factors = tuple(
        TriageFactor(
            kind="evidence",
            label=item.title or item.source,
            reference=item.citation_id,
            support=round(max(0.0, min(1.0, item.score / total)), 4),
            detail=f"{item.department} · 检索支持分 {item.score:.2f}",
        )
        for item in sorted(supported_evidence, key=lambda value: value.score, reverse=True)[:3]
    )
    support_score = round(max(0.0, min(1.0, weights[dept] / total)), 4)
    return TriageResult(
        department=dept,
        risk_level="中",
        confidence=confidence,
        urgency=DEFAULT_URGENCY,
        matched_rule=None,
        support_score=support_score,
        factors=ranked_factors,
        abstained=False,
        explanation=f"检索证据主要支持{dept}，支持分为 {support_score:.0%}；该分数不是临床准确率。",
    )
