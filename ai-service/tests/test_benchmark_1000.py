from collections import Counter

from evaluation.benchmark_1000 import SEED, generate_cases


def test_benchmark_has_frozen_size_distribution_and_unique_ids():
    cases = generate_cases()

    assert len(cases) == 1000
    assert len({case.case_id for case in cases}) == 1000
    assert Counter(case.task for case in cases) == {
        "high_risk": 320,
        "evidence_triage": 320,
        "low_evidence": 200,
        "insufficient_followup": 160,
    }
    assert Counter(case.perturbation for case in cases) == {
        0: 200,
        1: 200,
        2: 200,
        3: 200,
        4: 200,
    }


def test_benchmark_generation_is_reproducible_and_seed_is_frozen():
    first = generate_cases(SEED)
    second = generate_cases(SEED)

    assert first == second
    assert SEED == 20260831
