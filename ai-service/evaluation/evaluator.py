"""Deterministic evaluation helpers for safety, triage and retrieval.

The metrics are engineering regression signals, not clinical validation.  The
CLI deliberately keeps model-backed runs separate from deterministic runs so
that a paper can report the execution mode next to each result.
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

from app.agents.safety import screen_for_emergency


@dataclass(frozen=True)
class EvaluationCase:
    case_id: str
    text: str
    gold_red_flags: tuple[str, ...] = ()
    gold_department: str | None = None
    gold_risk: str | None = None
    gold_evidence_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class CaseOutcome:
    case_id: str
    red_flags: tuple[str, ...]
    department: str
    risk: str
    evidence_ids: tuple[str, ...] = ()
    latency_ms: float = 0.0
    error: str | None = None


def load_cases(path: Path) -> list[EvaluationCase]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError("evaluation cases must be a JSON array")
    cases: list[EvaluationCase] = []
    for item in payload:
        if not isinstance(item, dict) or not item.get("case_id") or not item.get("text"):
            raise ValueError("each evaluation case needs case_id and text")
        cases.append(EvaluationCase(
            case_id=str(item["case_id"]),
            text=str(item["text"]),
            gold_red_flags=tuple(str(value) for value in item.get("gold_red_flags", ())),
            gold_department=item.get("gold_department"),
            gold_risk=item.get("gold_risk"),
            gold_evidence_ids=tuple(str(value) for value in item.get("gold_evidence_ids", ())),
        ))
    return cases


def run_deterministic(cases: Iterable[EvaluationCase]) -> list[CaseOutcome]:
    """Run only the deterministic safety screen for reproducible baselines."""
    outcomes: list[CaseOutcome] = []
    for case in cases:
        result = screen_for_emergency(case.text)
        triage = result.triage
        if triage is None:
            department = "全科/建议线下分诊台"
            risk = "低"
        else:
            department = triage.department
            risk = triage.risk_level
        outcomes.append(CaseOutcome(
            case_id=case.case_id,
            red_flags=tuple(result.matched_terms),
            department=department,
            risk=risk,
            latency_ms=0.0,
        ))
    return outcomes


def load_outcome_overrides(
    path: Path,
    baseline_outcomes: Iterable[CaseOutcome],
) -> list[CaseOutcome]:
    """Overlay recorded model/retrieval results onto deterministic outcomes.

    The override file may contain only ``case_id`` and ``evidence_ids`` for a
    retrieval-only run. Classification fields that are omitted retain the
    deterministic safety-baseline value.
    """
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError("evaluation outcomes must be a JSON array")

    outcomes = list(baseline_outcomes)
    positions = {outcome.case_id: index for index, outcome in enumerate(outcomes)}
    seen: set[str] = set()
    for item in payload:
        if not isinstance(item, dict) or not str(item.get("case_id", "")).strip():
            raise ValueError("each evaluation outcome needs case_id")
        case_id = str(item["case_id"])
        if case_id in seen:
            raise ValueError(f"duplicate evaluation outcome case_id: {case_id}")
        if case_id not in positions:
            raise ValueError(f"evaluation outcome references unknown case_id: {case_id}")
        seen.add(case_id)

        baseline = outcomes[positions[case_id]]
        red_flags = _string_tuple(item, "red_flags", baseline.red_flags)
        evidence_ids = _string_tuple(item, "evidence_ids", baseline.evidence_ids)
        latency_ms = float(item.get("latency_ms", baseline.latency_ms))
        if latency_ms < 0:
            raise ValueError(f"latency_ms cannot be negative for case_id: {case_id}")
        error = item.get("error", baseline.error)
        outcomes[positions[case_id]] = CaseOutcome(
            case_id=case_id,
            red_flags=red_flags,
            department=str(item.get("department", baseline.department)),
            risk=str(item.get("risk", baseline.risk)),
            evidence_ids=evidence_ids,
            latency_ms=latency_ms,
            error=None if error is None else str(error),
        )
    return outcomes


def _string_tuple(
    item: dict,
    field_name: str,
    default: tuple[str, ...],
) -> tuple[str, ...]:
    value = item.get(field_name, default)
    if not isinstance(value, (list, tuple)):
        raise ValueError(f"{field_name} must be an array")
    return tuple(str(entry) for entry in value)


def _f1_for_label(gold: list[str], actual: list[str], label: str) -> float:
    tp = sum(g == label and a == label for g, a in zip(gold, actual))
    fp = sum(g != label and a == label for g, a in zip(gold, actual))
    fn = sum(g == label and a != label for g, a in zip(gold, actual))
    if tp == 0 and (fp or fn):
        return 0.0
    if tp == 0:
        return 1.0
    precision = tp / (tp + fp)
    recall = tp / (tp + fn)
    return 2 * precision * recall / (precision + recall)


def compute_classification_metrics(
    cases: Iterable[EvaluationCase],
    outcomes: Iterable[CaseOutcome],
) -> dict[str, float | int]:
    case_by_id = {case.case_id: case for case in cases}
    matched = [outcome for outcome in outcomes if outcome.case_id in case_by_id]
    red_flag_cases = [case for case in matched if case_by_id[case.case_id].gold_red_flags]
    red_flag_hits = sum(
        set(case_by_id[outcome.case_id].gold_red_flags).issubset(set(outcome.red_flags))
        for outcome in red_flag_cases
    )
    false_negatives = sum(
        bool(set(case_by_id[outcome.case_id].gold_red_flags) - set(outcome.red_flags))
        for outcome in red_flag_cases
    )
    department_pairs = [
        (case_by_id[outcome.case_id].gold_department, outcome.department)
        for outcome in matched
        if case_by_id[outcome.case_id].gold_department
    ]
    departments = sorted({gold for gold, _ in department_pairs})
    macro_f1 = (
        sum(_f1_for_label([gold for gold, _ in department_pairs], [actual for _, actual in department_pairs], label)
            for label in departments) / len(departments)
        if departments else 0.0
    )
    risk_pairs = [
        (case_by_id[outcome.case_id].gold_risk, outcome.risk)
        for outcome in matched
        if case_by_id[outcome.case_id].gold_risk
    ]
    risks = sorted({gold for gold, _ in risk_pairs})
    risk_macro_f1 = (
        sum(_f1_for_label([gold for gold, _ in risk_pairs], [actual for _, actual in risk_pairs], label)
            for label in risks) / len(risks)
        if risks else 0.0
    )
    latencies = [outcome.latency_ms for outcome in matched if outcome.latency_ms > 0]
    return {
        "evaluated_cases": len(matched),
        "safety_cases": len(red_flag_cases),
        "safety_recall": round(red_flag_hits / len(red_flag_cases), 4) if red_flag_cases else 0.0,
        "red_flag_false_negatives": false_negatives,
        "department_macro_f1": round(macro_f1, 4),
        "risk_macro_f1": round(risk_macro_f1, 4),
        "error_rate": round(sum(bool(outcome.error) for outcome in matched) / len(matched), 4)
        if matched else 0.0,
        "p50_latency_ms": round(statistics.median(latencies), 2) if latencies else 0.0,
        "p95_latency_ms": round(_percentile(latencies, 0.95), 2) if latencies else 0.0,
    }


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def compute_retrieval_metrics(
    cases: Iterable[EvaluationCase],
    outcomes: Iterable[CaseOutcome],
    *,
    k: int = 3,
) -> dict[str, float | int]:
    if k < 1:
        raise ValueError("k must be at least 1")
    case_by_id = {case.case_id: case for case in cases}
    evaluated = [
        outcome for outcome in outcomes
        if outcome.case_id in case_by_id and case_by_id[outcome.case_id].gold_evidence_ids
    ]
    if not evaluated:
        return {
            "evaluated_cases": 0,
            "recall_at_k": 0.0,
            "mrr": 0.0,
            "citation_traceability": 0.0,
        }
    hits = 0
    reciprocal_ranks: list[float] = []
    for outcome in evaluated:
        gold = set(case_by_id[outcome.case_id].gold_evidence_ids)
        top_k = outcome.evidence_ids[:max(1, k)]
        rank = next((index + 1 for index, item in enumerate(top_k) if item in gold), None)
        if rank:
            hits += 1
            reciprocal_ranks.append(1 / rank)
        else:
            reciprocal_ranks.append(0.0)
    traceable = sum(bool(outcome.evidence_ids) for outcome in evaluated)
    return {
        "evaluated_cases": len(evaluated),
        "recall_at_k": round(hits / len(evaluated), 4),
        "mrr": round(sum(reciprocal_ranks) / len(reciprocal_ranks), 4),
        "citation_traceability": round(traceable / len(evaluated), 4),
    }


def _render_summary(metrics: dict[str, float | int | str]) -> str:
    return "\n".join([
        "MedPilot 离线评估摘要",
        f"运行模式：{metrics['mode']}",
        f"病例总数：{metrics['case_count']}",
        "",
        "安全与分诊",
        f"安全召回率：{float(metrics['safety_recall']):.2%}",
        f"危险信号漏检数：{metrics['red_flag_false_negatives']}",
        f"科室 Macro-F1：{float(metrics['department_macro_f1']):.4f}",
        f"风险 Macro-F1：{float(metrics['risk_macro_f1']):.4f}",
        "",
        f"检索与引用（K={metrics['retrieval_k']}）",
        f"检索评估病例：{metrics['retrieval_evaluated_cases']}",
        f"Recall@{metrics['retrieval_k']}：{float(metrics['recall_at_k']):.2%}",
        f"MRR：{float(metrics['mrr']):.4f}",
        f"引用可追溯率：{float(metrics['citation_traceability']):.2%}",
        "",
        "说明：这些指标用于工程回归，不等同于临床准确率。",
    ]) + "\n"


def _write_outputs(
    cases: list[EvaluationCase],
    outcomes: list[CaseOutcome],
    output_dir: Path,
    *,
    retrieval_k: int = 3,
    mode: str = "deterministic-safety-baseline",
) -> dict[str, float | int | str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics = compute_classification_metrics(cases, outcomes)
    retrieval_metrics = compute_retrieval_metrics(cases, outcomes, k=retrieval_k)
    metrics.update({
        "retrieval_k": retrieval_k,
        "retrieval_evaluated_cases": retrieval_metrics["evaluated_cases"],
        "retrieval_status": (
            "evaluated" if retrieval_metrics["evaluated_cases"] else "not-evaluated"
        ),
        "recall_at_k": retrieval_metrics["recall_at_k"],
        "mrr": retrieval_metrics["mrr"],
        "citation_traceability": retrieval_metrics["citation_traceability"],
    })
    metrics["mode"] = mode
    metrics["case_count"] = len(cases)
    (output_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (output_dir / "summary.txt").write_text(
        _render_summary(metrics), encoding="utf-8"
    )
    with (output_dir / "case-results.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "case_id", "red_flags", "department", "risk", "evidence_ids", "latency_ms", "error",
        ])
        writer.writeheader()
        for outcome in outcomes:
            writer.writerow({
                "case_id": outcome.case_id,
                "red_flags": "、".join(outcome.red_flags),
                "department": outcome.department,
                "risk": outcome.risk,
                "evidence_ids": "、".join(outcome.evidence_ids),
                "latency_ms": outcome.latency_ms,
                "error": outcome.error or "",
            })
    return metrics


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Run MedPilot offline safety and retrieval evaluation"
    )
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    parser.add_argument(
        "--outcomes",
        "--outcome-file",
        "--retrieval-outcomes",
        dest="outcomes",
        type=Path,
        help="JSON array of recorded outcome overrides, including evidence_ids",
    )
    parser.add_argument("--output-dir", type=Path, default=Path("evaluation-results"))
    parser.add_argument("--retrieval-k", "--k", dest="retrieval_k", type=int, default=3)
    parser.add_argument("--format", choices=("json", "text", "human"), default="json")
    args = parser.parse_args(argv)
    if args.retrieval_k < 1:
        parser.error("--retrieval-k must be at least 1")

    cases = load_cases(args.cases)
    outcomes = run_deterministic(cases)
    mode = "deterministic-safety-baseline"
    if args.outcomes is not None:
        outcomes = load_outcome_overrides(args.outcomes, outcomes)
        mode = "deterministic-safety-with-recorded-outcomes"
    metrics = _write_outputs(
        cases,
        outcomes,
        args.output_dir,
        retrieval_k=args.retrieval_k,
        mode=mode,
    )
    if args.format in {"text", "human"}:
        print(_render_summary(metrics), end="")
    else:
        print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
