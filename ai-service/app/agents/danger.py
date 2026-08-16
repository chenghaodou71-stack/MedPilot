"""Single negation-aware matcher for deterministic danger signs."""
from __future__ import annotations

import re


DANGER_SIGN_TERMS: tuple[str, ...] = (
    "胸痛",
    "呼吸困难",
    "气促",
    "咯血",
    "便血",
    "意识不清",
    "晕厥",
    "大出血",
)

_ALIASES: dict[str, tuple[str, ...]] = {
    "呼吸困难": ("气短",),
    "便血": ("黑便",),
    "晕厥": ("昏倒",),
    "咯血": ("咳血", "咳出血", "咳出了血"),
}
_CLAUSE_BOUNDARY = re.compile(r"[。！？；，,\n]|但是|但|不过|然而")
_TARGETED_NEGATION = re.compile(
    r"(?:没有|并无|未见|未出现|否认|不伴|无)"
    r"(?:\s*(?:明显|任何|相关|上述|这种|有|存在|出现))*\s*$"
)
_COORDINATING_TEXT = re.compile(r"^\s*(?:(?:、|/)|和|及|以及|或|也|并)*\s*$")


def _aliases() -> tuple[tuple[str, str], ...]:
    return tuple(
        (alias, canonical)
        for canonical in DANGER_SIGN_TERMS
        for alias in (canonical, *_ALIASES.get(canonical, ()))
    )


def _clause_matches(clause: str) -> list[tuple[int, int, str]]:
    matches: list[tuple[int, int, str]] = []
    for alias, canonical in _aliases():
        start = 0
        while True:
            index = clause.find(alias, start)
            if index < 0:
                break
            matches.append((index, index + len(alias), canonical))
            start = index + len(alias)

    selected: list[tuple[int, int, str]] = []
    for match in sorted(matches, key=lambda item: (item[0], -(item[1] - item[0]))):
        if selected and match[0] < selected[-1][1]:
            continue
        selected.append(match)
    return selected


def match_danger_signs(text: str) -> tuple[str, ...]:
    """Return canonical danger signs that are asserted, not explicitly negated."""
    asserted: set[str] = set()
    for clause in _CLAUSE_BOUNDARY.split(text):
        previous_end = 0
        previous_negated = False
        for start, end, canonical in _clause_matches(clause):
            prefix = clause[:start]
            connector = clause[previous_end:start]
            directly_negated = _TARGETED_NEGATION.search(prefix) is not None
            inherited_negation = (
                previous_negated
                and _COORDINATING_TEXT.fullmatch(connector) is not None
            )
            negated = directly_negated or inherited_negation
            if not negated:
                asserted.add(canonical)
            previous_end = end
            previous_negated = negated

    return tuple(term for term in DANGER_SIGN_TERMS if term in asserted)
