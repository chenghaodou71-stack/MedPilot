"""Canonical symptom entity normalization tests."""
import pytest

from app.rag.entity_normalizer import (
    expand_query_with_aliases,
    normalize_symptom,
    normalize_symptoms,
)


@pytest.mark.unit
@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("发烧", "发热"),
        ("  心慌  ", "心悸"),
        ("胸口疼", "胸痛"),
        ("气短", "呼吸困难"),
        ("不舒服", "不舒服"),
    ],
)
def test_normalize_symptom_maps_common_aliases_without_inventing_entities(raw, expected):
    assert normalize_symptom(raw) == expected


@pytest.mark.unit
def test_normalize_symptoms_deduplicates_canonical_values_and_preserves_order():
    assert normalize_symptoms(("发烧", "发热", "心慌", "心悸", "  ")) == (
        "发热",
        "心悸",
    )


@pytest.mark.unit
def test_expand_query_adds_canonical_aliases_for_lexical_retrieval():
    expanded = expand_query_with_aliases("夜间气短，最近发烧")

    assert "夜间气短，最近发烧" in expanded
    assert "呼吸困难" in expanded
    assert "发热" in expanded


@pytest.mark.unit
def test_expand_query_does_not_promote_explicitly_negated_aliases():
    expanded = expand_query_with_aliases("没有胸痛，但最近发烧")

    assert "发热" in expanded
    assert expanded.count("胸痛") == 1
