"""Create the ablation Visual Result Map for the MedPilot thesis."""
from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.lines import Line2D
from matplotlib.ticker import PercentFormatter


ROOT = Path(r"D:\毕设制作")
OUTPUT_DIR = ROOT / "outputs" / "thesis-figures" / "results"
STEM = "28_ablation_visual_result_map"


DATA = [
    {
        "label": "危险信号召回率",
        "short_label": "召回率",
        "complete": 0.8000,
        "ablation": 0.7937,
    },
    {
        "label": "非高风险特异度",
        "short_label": "特异度",
        "complete": 1.0000,
        "ablation": 0.8941,
    },
]

COLORS = {
    "complete": "#0072B2",  # Okabe-Ito blue
    "ablation": "#D55E00",  # Okabe-Ito vermilion
    "delta": "#009E73",  # Okabe-Ito green
    "line": "#7A858E",
    "grid": "#D8DEE4",
    "text": "#1F2933",
    "muted": "#59636E",
}


def configure_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Microsoft YaHei",
            "font.sans-serif": ["Microsoft YaHei", "SimHei", "Noto Sans SC", "DejaVu Sans"],
            "axes.unicode_minus": False,
            "svg.fonttype": "none",
            "font.size": 9,
            "axes.labelsize": 9,
            "xtick.labelsize": 8,
            "ytick.labelsize": 8.5,
            "legend.fontsize": 8,
            "axes.linewidth": 0.8,
        }
    )


def draw_map() -> plt.Figure:
    values = np.array([[row["ablation"], row["complete"]] for row in DATA], dtype=float)
    deltas = (values[:, 1] - values[:, 0]) * 100
    y = np.arange(len(DATA))[::-1]

    fig = plt.figure(figsize=(7.2, 3.95), facecolor="white")
    grid = fig.add_gridspec(1, 2, width_ratios=(1.55, 1.0), wspace=0.34)
    ax = fig.add_subplot(grid[0, 0])
    ax_delta = fig.add_subplot(grid[0, 1])

    # Left: paired points show the absolute value of each metric.
    ax.set_title("指标绝对值", loc="left", fontsize=10, fontweight="bold", color=COLORS["text"], pad=10)
    for row_index, row_y in enumerate(y):
        ablation, complete = values[row_index]
        ax.plot(
            [ablation, complete],
            [row_y, row_y],
            color=COLORS["line"],
            linewidth=2.0,
            solid_capstyle="round",
            zorder=1,
        )
        ax.scatter(
            ablation,
            row_y,
            s=62,
            marker="s",
            color=COLORS["ablation"],
            edgecolor="white",
            linewidth=0.8,
            zorder=3,
        )
        ax.scatter(
            complete,
            row_y,
            s=70,
            marker="o",
            color=COLORS["complete"],
            edgecolor="white",
            linewidth=0.8,
            zorder=3,
        )
        # Put the two labels on opposite sides of the connector to keep
        # the close recall values legible.
        ax.annotate(
            f"{ablation:.2%}",
            (ablation, row_y),
            xytext=(0, -17),
            textcoords="offset points",
            ha="center",
            va="top",
            fontsize=8,
            color=COLORS["ablation"],
        )
        ax.annotate(
            f"{complete:.2%}",
            (complete, row_y),
            xytext=(0, 14),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=8,
            color=COLORS["complete"],
            fontweight="bold",
        )

    ax.set_xlim(0.75, 1.025)
    ax.set_ylim(-0.55, 1.55)
    ax.set_yticks(y, [row["label"] for row in DATA])
    ax.xaxis.set_major_formatter(PercentFormatter(1.0, decimals=0))
    ax.set_xticks(np.arange(0.75, 1.001, 0.05))
    ax.set_xlabel("指标值")
    ax.grid(axis="x", color=COLORS["grid"], linewidth=0.7)
    ax.set_axisbelow(True)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.tick_params(axis="y", length=0, pad=7)
    ax.tick_params(axis="x", colors=COLORS["muted"])

    legend_handles = [
        Line2D(
            [0], [0], marker="o", color="none", markerfacecolor=COLORS["complete"],
            markeredgecolor="white", markersize=7, label="完整方案",
        ),
        Line2D(
            [0], [0], marker="s", color="none", markerfacecolor=COLORS["ablation"],
            markeredgecolor="white", markersize=7, label="消融方案",
        ),
    ]
    ax.legend(handles=legend_handles, loc="lower left", frameon=False, ncol=2, handletextpad=0.35, columnspacing=1.0)

    # Right: the engineering gain is shown as a point-and-line delta map.
    ax_delta.set_title("完整方案相对提升", loc="left", fontsize=10, fontweight="bold", color=COLORS["text"], pad=10)
    for row_index, row_y in enumerate(y):
        delta = deltas[row_index]
        ax_delta.plot(
            [0, delta],
            [row_y, row_y],
            color=COLORS["delta"],
            linewidth=5.0,
            solid_capstyle="round",
            zorder=1,
        )
        ax_delta.scatter(
            delta,
            row_y,
            s=64,
            marker="D",
            color=COLORS["delta"],
            edgecolor="white",
            linewidth=0.8,
            zorder=3,
        )
        ax_delta.annotate(
            f"+{delta:.2f} 个百分点",
            (delta, row_y),
            xytext=(8, 0),
            textcoords="offset points",
            ha="left",
            va="center",
            fontsize=8.5,
            color=COLORS["delta"],
            fontweight="bold",
        )

    ax_delta.set_xlim(0, 12.5)
    ax_delta.set_ylim(-0.55, 1.55)
    ax_delta.set_yticks(y, [row["short_label"] for row in DATA])
    ax_delta.set_xticks(np.arange(0, 13, 2))
    ax_delta.set_xlabel("差值（百分点）")
    ax_delta.grid(axis="x", color=COLORS["grid"], linewidth=0.7)
    ax_delta.set_axisbelow(True)
    ax_delta.spines["top"].set_visible(False)
    ax_delta.spines["right"].set_visible(False)
    ax_delta.tick_params(axis="y", length=0, pad=7)
    ax_delta.tick_params(axis="x", colors=COLORS["muted"])

    fig.text(
        0.5,
        0.015,
        "固定种子生成的1000条工程测试集；图中结果不代表临床验证性能",
        ha="center",
        va="bottom",
        fontsize=8,
        color=COLORS["muted"],
    )
    fig.subplots_adjust(left=0.12, right=0.98, top=0.86, bottom=0.20)
    return fig


def main() -> None:
    configure_style()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    fig = draw_map()
    fig.savefig(OUTPUT_DIR / f"{STEM}.png", dpi=300, bbox_inches="tight", facecolor="white")
    fig.savefig(OUTPUT_DIR / f"{STEM}.svg", bbox_inches="tight", facecolor="white")
    fig.savefig(OUTPUT_DIR / f"{STEM}.pdf", bbox_inches="tight", facecolor="white")
    plt.close(fig)

    deltas = [(row["complete"] - row["ablation"]) * 100 for row in DATA]
    audit = {
        "figure_type": "experimental-results",
        "paradigm": "paired-dot comparison with a delta map",
        "source": "表5-4：基线与消融结果",
        "sample_count": 1000,
        "data_boundary": "固定种子生成的人工构造工程测试集，不是临床病例或临床验证数据",
        "metrics": [
            {
                "metric": row["label"],
                "complete": row["complete"],
                "ablation": row["ablation"],
                "delta_percentage_points": round(delta, 2),
            }
            for row, delta in zip(DATA, deltas)
        ],
        "checks": {
            "vector_svg_and_pdf_exported": True,
            "word_compatible_300dpi_png_exported": True,
            "color_blind_safe_and_dual_encoded": True,
            "axis_units_present": True,
            "honest_axis_ranges": True,
            "no_3d_or_chartjunk": True,
            "no_new_or_fabricated_data": True,
        },
    }
    (OUTPUT_DIR / f"{STEM}.audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"wrote {STEM}.png/.svg/.pdf and audit to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
