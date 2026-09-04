"""Conservative, deterministic normalization for symptom entity aliases.

The LLM remains responsible for extracting candidate strings. This module only
maps a bounded set of well-known patient-facing aliases to canonical terms,
deduplicating them without inventing symptoms.
"""
from __future__ import annotations

import re
from collections.abc import Iterable


# Keep this list intentionally small and auditable. Aliases are phrases a
# patient commonly uses for the same symptom, not disease names or diagnoses.
CANONICAL_SYMPTOM_ALIASES: dict[str, tuple[str, ...]] = {
    "发热": ("发热", "发烧", "高烧", "低烧", "体温升高"),
    "胸痛": ("胸痛", "胸口疼", "胸口痛", "胸部疼痛"),
    "呼吸困难": ("呼吸困难", "气短", "喘不上气", "呼吸不畅", "呼吸费力"),
    "心悸": ("心悸", "心慌", "心跳快", "心跳加速"),
    "咳嗽": ("咳嗽", "干咳"),
    "咳痰": ("咳痰", "痰多", "咳痰液"),
    "腹痛": ("腹痛", "肚子疼", "肚疼", "腹部疼痛"),
    "腹泻": ("腹泻", "拉肚子", "稀便"),
    "恶心": ("恶心", "想吐"),
    "呕吐": ("呕吐", "吐"),
    "头晕": ("头晕", "头昏", "眩晕"),
    "皮疹": ("皮疹", "起疹", "疹子"),
    "瘙痒": ("瘙痒", "皮肤痒"),
    "红斑": ("红斑", "皮肤发红"),
}

_TRIM_RE = re.compile(r"^[\s\u3000,，。；;、:：!?！？]+|[\s\u3000,，。；;、:：!?！？]+$")
_CLAUSE_BOUNDARY = re.compile(r"[。！？；，,\n]|但是|但|不过|然而")
_NEGATION_PREFIX = re.compile(
    r"(?:没有|无|未见|未出现|否认|不伴|不觉得)"
    r"\s*(?:明显|任何|相关|上述|这种|有|存在|出现)?\s*$"
)
_ALIAS_TO_CANONICAL = {
    alias.casefold(): canonical
    for canonical, aliases in CANONICAL_SYMPTOM_ALIASES.items()
    for alias in aliases
}
_ALIASES_BY_LENGTH = tuple(sorted(_ALIAS_TO_CANONICAL, key=len, reverse=True))


def _clean(value: str) -> str:
    return _TRIM_RE.sub("", " ".join(value.split()))


def normalize_symptom(value: str) -> str:
    """Return a canonical alias or the cleaned original candidate."""
    if not isinstance(value, str):
        return ""
    cleaned = _clean(value)
    if not cleaned:
        return ""
    folded = cleaned.casefold()
    direct = _ALIAS_TO_CANONICAL.get(folded)
    if direct:
        return direct
    # LLM output occasionally contains a short explanatory prefix. Only map
    # when a bounded alias is present; otherwise preserve the candidate.
    for alias in _ALIASES_BY_LENGTH:
        if alias in folded and len(alias) >= 2:
            return _ALIAS_TO_CANONICAL[alias]
    return cleaned


def normalize_symptoms(values: Iterable[str]) -> tuple[str, ...]:
    """Normalize, drop empty values and deduplicate while preserving order."""
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        canonical = normalize_symptom(value)
        if canonical and canonical not in seen:
            seen.add(canonical)
            result.append(canonical)
    return tuple(result)


def expand_query_with_aliases(query: str) -> str:
    """Append canonical terms found through aliases for lexical retrieval."""
    if not isinstance(query, str):
        return ""
    canonical_terms = normalize_symptoms(
        canonical
        for alias, canonical in _ALIAS_TO_CANONICAL.items()
        if _contains_asserted_alias(query, alias)
    )
    if not canonical_terms:
        return query
    return f"{query} {' '.join(canonical_terms)}"


def _contains_asserted_alias(query: str, alias: str) -> bool:
    """Ignore aliases that only occur in an explicitly negated clause."""
    folded_query = query.casefold()
    for clause in _CLAUSE_BOUNDARY.split(folded_query):
        start = 0
        while True:
            index = clause.find(alias)
            if index < 0:
                break
            prefix = clause[:index]
            if not _NEGATION_PREFIX.search(prefix):
                return True
            start = index + len(alias)
            clause = clause[start:]
    return False
