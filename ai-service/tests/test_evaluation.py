import json

import pytest

from evaluation.evaluator import (
    CaseOutcome,
    EvaluationCase,
    compute_classification_metrics,
    compute_retrieval_metrics,
    main,
)


def test_safety_recall_prioritizes_missed_red_flags():
    cases = [
        EvaluationCase(
            case_id="RF01",
            text="胸痛",
            gold_red_flags=("胸痛",),
            gold_department="心血管内科",
            gold_risk="高",
        ),
        EvaluationCase(
            case_id="RF02",
            text="呼吸困难",
            gold_red_flags=("呼吸困难",),
            gold_department="呼吸内科",
            gold_risk="高",
        ),
    ]
    outcomes = [
        CaseOutcome("RF01", ("胸痛",), "心血管内科", "高", (), 8),
        CaseOutcome("RF02", (), "全科/建议线下分诊台", "低", (), 9),
    ]

    metrics = compute_classification_metrics(cases, outcomes)

    assert metrics["safety_recall"] == 0.5
    assert metrics["red_flag_false_negatives"] == 1
    assert 0.0 <= metrics["department_macro_f1"] <= 1.0


def test_retrieval_metrics_report_recall_at_k_and_mrr():
    cases = [
        EvaluationCase(
            case_id="R1",
            text="咳嗽",
            gold_evidence_ids=("resp#0",),
        ),
        EvaluationCase(
            case_id="R2",
            text="皮疹",
            gold_evidence_ids=("skin#0",),
        ),
    ]
    outcomes = [
        CaseOutcome("R1", (), "呼吸内科", "中", ("other#0", "resp#0"), 12),
        CaseOutcome("R2", (), "皮肤科", "中", ("skin#0",), 14),
    ]

    metrics = compute_retrieval_metrics(cases, outcomes, k=2)

    assert metrics["recall_at_k"] == 1.0
    assert metrics["mrr"] == 0.75
    assert metrics["citation_traceability"] == 1.0


def test_empty_gold_set_is_excluded_from_retrieval_denominator():
    cases = [EvaluationCase(case_id="N1", text="乏力")]
    outcomes = [CaseOutcome("N1", (), "全科/建议线下分诊台", "低", (), 4)]

    metrics = compute_retrieval_metrics(cases, outcomes, k=3)

    assert metrics["evaluated_cases"] == 0
    assert metrics["recall_at_k"] == 0.0


def test_retrieval_metrics_reject_non_positive_k():
    with pytest.raises(ValueError, match="k must be at least 1"):
        compute_retrieval_metrics([], [], k=0)


def test_cli_reports_retrieval_metrics_in_json_and_human_summary(tmp_path, capsys):
    cases_path = tmp_path / "cases.json"
    outcomes_path = tmp_path / "outcomes.json"
    output_dir = tmp_path / "results"
    cases_path.write_text(
        json.dumps([
            {
                "case_id": "R1",
                "text": "咳嗽",
                "gold_evidence_ids": ["resp#0"],
            },
            {
                "case_id": "R2",
                "text": "皮疹",
                "gold_evidence_ids": ["skin#0"],
            },
        ], ensure_ascii=False),
        encoding="utf-8",
    )
    outcomes_path.write_text(
        json.dumps([
            {"case_id": "R1", "evidence_ids": ["other#0", "resp#0"]},
            {"case_id": "R2", "evidence_ids": ["skin#0"]},
        ], ensure_ascii=False),
        encoding="utf-8",
    )

    main([
        "--cases", str(cases_path),
        "--outcomes", str(outcomes_path),
        "--output-dir", str(output_dir),
        "--retrieval-k", "2",
        "--format", "text",
    ])

    stdout = capsys.readouterr().out
    metrics = json.loads((output_dir / "metrics.json").read_text(encoding="utf-8"))
    summary = (output_dir / "summary.txt").read_text(encoding="utf-8")
    assert metrics["recall_at_k"] == 1.0
    assert metrics["mrr"] == 0.75
    assert metrics["citation_traceability"] == 1.0
    assert metrics["retrieval_k"] == 2
    assert metrics["retrieval_status"] == "evaluated"
    assert "Recall@2：100.00%" in stdout
    assert "MRR：0.7500" in summary
    assert "引用可追溯率：100.00%" in summary


def test_cli_default_json_output_remains_runnable(tmp_path, capsys):
    cases_path = tmp_path / "cases.json"
    output_dir = tmp_path / "results"
    cases_path.write_text(
        json.dumps([{"case_id": "N1", "text": "最近有点乏力"}], ensure_ascii=False),
        encoding="utf-8",
    )

    main(["--cases", str(cases_path), "--output-dir", str(output_dir)])

    payload = json.loads(capsys.readouterr().out)
    assert payload["mode"] == "deterministic-safety-baseline"
    assert payload["recall_at_k"] == 0.0
    assert payload["mrr"] == 0.0
    assert payload["citation_traceability"] == 0.0
    assert payload["retrieval_status"] == "not-evaluated"
