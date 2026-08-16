"""Deterministic emergency screening that runs before any model call."""
from __future__ import annotations

from pydantic import BaseModel

from app.agents.classify import classify
from app.agents.danger import match_danger_signs
from app.schemas import StructuredSymptoms, TriageResult


class SafetyScreenResult(BaseModel):
    model_config = {"frozen": True}

    matched: bool
    matched_terms: tuple[str, ...] = ()
    triage: TriageResult | None = None
    rule_id: str | None = None


def screen_for_emergency(text: str) -> SafetyScreenResult:
    matched_terms = match_danger_signs(text)
    if not matched_terms:
        return SafetyScreenResult(matched=False)

    symptoms = StructuredSymptoms(
        symptoms=matched_terms,
        red_flags=matched_terms,
        raw_text=text,
    )
    triage = classify(symptoms, [])
    return SafetyScreenResult(
        matched=True,
        matched_terms=matched_terms,
        triage=triage,
        rule_id=triage.matched_rule,
    )
