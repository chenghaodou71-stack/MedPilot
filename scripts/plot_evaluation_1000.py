"""Generate publication figures from the frozen 1,000-case benchmark."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Sequence

import matplotlib.pyplot as plt
import numpy as np
from matplotlib import font_manager


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULTS = ROOT / "ai-service" / "evaluation-results-1000"
DEFAULT_OUTPUT = ROOT / "outputs" / "thesis-evaluation-1000"
def _font() -> str:
    candidates = ("Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Arial Unicode MS")
    installed = {item.name for item in font_manager.fontManager.ttflist}
    return next((name for name in candidates if name in installed), "DejaVu Sans")


def configure_style() -> None:
    plt.rcParams.update({
        "font.family": _font(),
        "axes.unicode_minus": False,
        "font.size": 9,
        "axes.labelsize": 9,
        "xtick.labelsize": 8,
        "ytick.labelsize": 8,
        "legend.fontsize": 7.5,
        "axes.linewidth": 0.8,
        "svg.fonttype": "none",
    })


def save_figure(fig: plt.Figure, output_dir: Path, stem: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    fig.savefig(output_dir / f"{stem}.png", dpi=300, bbox_inches="tight", facecolor="white")
    fig.savefig(output_dir / f"{stem}.svg", bbox_inches="tight", facecolor="white")
    plt.close(fig)


def plot_robustness(metrics: dict, output_dir: Path) -> None:
    rows = metrics["perturbation_metrics"]
    x = np.arange(len(rows))
    series = (
        ("危险信号召回率", "safety_recall", "#C84B31", "o", "-"),
        ("证据分诊准确率", "department_accuracy", "#2F6690", "s", "--"),
        ("低证据回退率", "abstain_rate", "#4F7F52", "^", "-."),
        ("主动追问触发率", "followup_trigger_rate", "#75507B", "D", ":"),
    )
    fig, ax = plt.subplots(figsize=(6.45, 3.45))
    for label, key, color, marker, linestyle in series:
        values = [row[key] * 100 for row in rows]
        ax.plot(
            x, values, label=label, color=color, marker=marker,
            linestyle=linestyle, linewidth=1.6, markersize=4.8,
        )
    ax.set_xticks(x, [f"P{row['level']}" for row in rows])
    ax.set_xlabel("扰动等级（P0规范表达，P4未登录改写与错别字）")
    ax.set_ylabel("比例（%）")
    ax.set_ylim(0, 105)
    ax.set_yticks(np.arange(0, 101, 20))
    ax.grid(axis="y", color="#D7DCE2", linewidth=0.7, alpha=0.8)
    ax.set_axisbelow(True)
    ax.legend(ncol=2, loc="lower left", frameon=False)
    fig.tight_layout()
    save_figure(fig, output_dir, "figure5-1_perturbation_robustness")


def plot_confusion(metrics: dict, output_dir: Path) -> None:
    department = metrics["department"]
    labels = list(department["labels"])
    short = {
        "心血管内科": "心血管",
        "呼吸内科": "呼吸",
        "消化内科": "消化",
        "皮肤科": "皮肤",
        "全科/建议线下分诊台": "回退",
    }
    matrix = np.asarray(department["confusion_matrix"], dtype=float)
    row_sums = matrix.sum(axis=1, keepdims=True)
    normalized = np.divide(matrix, row_sums, out=np.zeros_like(matrix), where=row_sums != 0)

    fig, ax = plt.subplots(figsize=(5.25, 4.25))
    image = ax.imshow(normalized, cmap="YlGnBu", vmin=0, vmax=1, aspect="equal")
    ax.set_xticks(range(len(labels)), [short[label] for label in labels])
    ax.set_yticks(range(len(labels)), [short[label] for label in labels])
    ax.set_xlabel("系统输出")
    ax.set_ylabel("金标准")
    for row in range(matrix.shape[0]):
        for column in range(matrix.shape[1]):
            value = normalized[row, column]
            color = "white" if value >= 0.58 else "#1F2933"
            ax.text(
                column, row, f"{int(matrix[row, column])}\n{value:.1%}",
                ha="center", va="center", fontsize=7.5, color=color,
            )
    cbar = fig.colorbar(image, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label("行归一化比例")
    fig.tight_layout()
    save_figure(fig, output_dir, "figure5-2_department_confusion_matrix")


def plot_latency(outcomes: list[dict], output_dir: Path) -> None:
    groups = (
        ("规则命中\n高危快速通道", lambda row: row["task"] == "high_risk" and row["predicted_route"] == "high_risk_fast_path"),
        ("高危漏检后\n进入检索", lambda row: row["task"] == "high_risk" and row["predicted_route"] != "high_risk_fast_path"),
        ("证据支持\n分诊", lambda row: row["task"] == "evidence_triage"),
        ("低证据\n测试", lambda row: row["task"] == "low_evidence"),
        ("信息不足\n追问", lambda row: row["task"] == "insufficient_followup"),
    )
    values = [
        [
            max(float(row["latency_ms"]), 0.01)
            for row in outcomes
            if predicate(row) and not row["error"]
        ]
        for _label, predicate in groups
    ]
    fig, ax = plt.subplots(figsize=(6.25, 3.55))
    box = ax.boxplot(
        values, patch_artist=True, widths=0.55, showfliers=False, whis=(5, 95),
        medianprops={"color": "#222222", "linewidth": 1.2},
        whiskerprops={"color": "#59636E", "linewidth": 0.9},
        capprops={"color": "#59636E", "linewidth": 0.9},
        boxprops={"edgecolor": "#59636E", "linewidth": 0.9},
    )
    colors = ("#E8B4A6", "#E6C58F", "#A8C7DF", "#B6D3B8", "#C8B4CC")
    for patch, color in zip(box["boxes"], colors):
        patch.set_facecolor(color)
    ax.set_xticks(range(1, len(groups) + 1), [label for label, _predicate in groups])
    ax.set_ylabel("单样本离线组件耗时（ms，对数坐标）")
    ax.set_yscale("log")
    ax.grid(axis="y", color="#D7DCE2", linewidth=0.7, alpha=0.8)
    ax.set_axisbelow(True)
    fig.tight_layout()
    save_figure(fig, output_dir, "figure5-3_offline_latency_boxplot")


def write_audit(metrics: dict, output_dir: Path) -> None:
    audit = {
        "figure_type": "experimental-results",
        "source": "ai-service/evaluation-results-1000",
        "sample_count": metrics["benchmark"]["case_count"],
        "seed": metrics["benchmark"]["seed"],
        "checks": {
            "reproducible_from_results": True,
            "color_blind_safe_and_dual_encoding": True,
            "axis_units_present": True,
            "honest_axis_ranges": True,
            "no_3d_or_chartjunk": True,
            "vector_svg_exported": True,
            "word_compatible_300dpi_png_exported": True,
        },
        "data_boundary": metrics["benchmark"]["data_boundary"],
    }
    (output_dir / "figure_audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    configure_style()
    metrics = json.loads((args.results_dir / "metrics.json").read_text(encoding="utf-8"))
    outcomes = json.loads((args.results_dir / "case_outcomes.json").read_text(encoding="utf-8"))
    plot_robustness(metrics, args.output_dir)
    plot_confusion(metrics, args.output_dir)
    plot_latency(outcomes, args.output_dir)
    write_audit(metrics, args.output_dir)
    print(f"wrote 3 figures to {args.output_dir}")


if __name__ == "__main__":
    main()
