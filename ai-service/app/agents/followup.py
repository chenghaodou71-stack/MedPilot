"""症状充分性判断与追问问题生成。纯规则，无 LLM 调用，离线可测。"""
from __future__ import annotations

from app.schemas import FollowUpQuestion, StructuredSymptoms


def is_sufficient(s: StructuredSymptoms) -> bool:
    """判断采集到的症状信息是否足以进行分诊。"""
    if s.red_flags:
        return True
    n = len(s.symptoms)
    if n >= 2:
        return True
    if n == 1 and (s.duration or s.severity):
        return True
    return False


def build_followup(s: StructuredSymptoms) -> FollowUpQuestion:
    """根据缺失信息生成最有价值的一个追问问题。"""
    if not s.symptoms:
        return FollowUpQuestion(
            question="能描述一下您主要的不舒服是什么吗？",
            missing=("symptoms",),
        )

    missing: list[str] = []
    if len(s.symptoms) < 2:
        missing.append("more_symptoms")
    if not s.duration:
        missing.append("duration")
    if not s.severity:
        missing.append("severity")

    if "duration" in missing:
        question = f"您感到{s.symptoms[0]}大概有多长时间了？"
    elif "more_symptoms" in missing:
        question = f"除了{s.symptoms[0]}，还有其他不舒服的地方吗？"
    else:
        question = f"您的{s.symptoms[0]}程度如何？是轻微、中等还是严重？"

    return FollowUpQuestion(question=question, missing=tuple(missing))
