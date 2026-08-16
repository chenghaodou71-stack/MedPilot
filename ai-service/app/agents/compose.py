"""Deterministic, fail-closed rendering for the final consultation answer."""
from __future__ import annotations

import re
from collections.abc import Awaitable, Callable
from datetime import date

from app.schemas import ComposedAnswer, RankedEvidence, StructuredSymptoms, TriageResult

ChatFn = Callable[..., Awaitable[str]]

SAFETY_BOUNDARY = (
    "本系统提供的是辅助分诊建议，不替代执业医生的诊断与治疗。"
    "如症状持续、加重或出现危险信号，请及时线下就医。"
)

_ALLOWED_TRIAGE_DEPARTMENTS = {
    "呼吸内科",
    "消化内科",
    "心血管内科",
    "皮肤科",
    "神经内科",
    "急诊科",
    "全科/建议线下分诊台",
}
_ALLOWED_EVIDENCE_DEPARTMENTS = {
    "呼吸内科",
    "消化内科",
    "心血管内科",
    "皮肤科",
}
_ALLOWED_RISK_LEVELS = {"低", "中", "高"}
_ALLOWED_URGENCIES = {
    "建议立即就医或呼叫急救",
    "建议立即就医",
    "建议尽快就医",
    "建议立即呼叫急救",
    "建议尽早于门诊就诊",
}

# Approved evidence is structurally gated first. These patterns are only a
# secondary cleanup layer for accidental prescription content in citations.
_RESTRICTED_EVIDENCE = re.compile(
    r"确诊(?:为)?|诊断(?:为|是)|患有|得了|就是|肯定是|明确是|"
    r"阿司匹林|布洛芬|对乙酰氨基酚|阿莫西林|头孢|硝酸甘油|奥美拉唑|"
    r"氯雷他定|西替利嗪|二甲双胍|胰岛素|阿托品|肾上腺素|华法林|"
    r"利伐沙班|泼尼松|地塞米松|"
    r"[0-9零〇一二两三四五六七八九十百半]+(?:\.[0-9]+)?\s*"
    r"(?:mg|g|ml|毫克|克|毫升|片|粒|袋|滴|揿|喷|支|单位|iu)|"
    r"口服|服用|服药|用药|含服|外用|涂抹|肌注|注射|静滴|"
    r"停药|换药|加量|减量|自行购药|开具处方|处方",
    re.IGNORECASE,
)


def _is_trusted_evidence(item: RankedEvidence) -> bool:
    if item.department not in _ALLOWED_EVIDENCE_DEPARTMENTS:
        return False
    if item.review_status != "approved" or not item.url.startswith("https://"):
        return False
    if not all(
        value.strip()
        for value in (
            item.institution,
            item.title,
            item.published_date,
            item.version,
            item.license,
        )
    ):
        return False
    try:
        date.fromisoformat(item.published_date)
    except ValueError:
        return False
    return _RESTRICTED_EVIDENCE.search(item.quote) is None


def _render_answer(triage: TriageResult) -> str:
    department = triage.department
    if department not in _ALLOWED_TRIAGE_DEPARTMENTS:
        department = "线下分诊台"
    elif department == "全科/建议线下分诊台":
        department = "全科或线下分诊台"

    risk_level = (
        triage.risk_level if triage.risk_level in _ALLOWED_RISK_LEVELS else "待评估"
    )
    urgency = (
        triage.urgency
        if triage.urgency in _ALLOWED_URGENCIES
        else "建议尽快线下就医"
    )
    return (
        f"辅助分诊建议：{department}。当前风险等级：{risk_level}。"
        f"就医时效：{urgency}。请由执业医生结合线下检查进一步评估。"
    )


async def compose(
    triage: TriageResult,
    evidence: list[RankedEvidence],
    symptoms: StructuredSymptoms,
    chat_fn: ChatFn | None = None,
) -> ComposedAnswer:
    """Render only validated triage fields; free-form model text is never used."""
    citations = tuple(
        {item.citation_id: item for item in evidence if _is_trusted_evidence(item)}.values()
    )
    return ComposedAnswer(
        text=_render_answer(triage),
        citations=citations,
        safety_boundary=SAFETY_BOUNDARY,
    )
