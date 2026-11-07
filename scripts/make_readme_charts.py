#!/usr/bin/env python3
"""
Membuat gambar grafik untuk README dari results/aco_raw.csv dan results/woa_raw.csv.

Keluaran:
  docs/charts/makespan.png
  docs/charts/metrics-overview.png

Pemakaian (dari root repository):
  pip install pandas matplotlib
  python scripts/make_readme_charts.py
"""
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

RESULTS = Path("results")
OUT = Path("docs/charts")
OUT.mkdir(parents=True, exist_ok=True)

COLORS = {"ACO": "#1F77B4", "WOA": "#D62728"}
SIZES = list(range(100, 1001, 100))

raw = pd.concat([
    pd.read_csv(RESULTS / "aco_raw.csv").assign(Algorithm="ACO"),
    pd.read_csv(RESULTS / "woa_raw.csv").assign(Algorithm="WOA"),
])
grouped = raw.groupby(["Algorithm", "Tasks"])
mean = grouped.mean(numeric_only=True)
std = grouped["Makespan (s)"].std(ddof=1)

# 1) Makespan dengan simpangan baku
fig, ax = plt.subplots(figsize=(9, 5), dpi=150)
for algo in ("ACO", "WOA"):
    ax.errorbar(SIZES, mean.loc[algo]["Makespan (s)"].values, yerr=std.loc[algo].values,
                color=COLORS[algo], marker="o", ms=6, lw=2, capsize=4, label=algo)
ax.set_title("Makespan: ACO vs WOA (rata-rata 3 run, batang = simpangan baku)",
             fontsize=12, fontweight="bold")
ax.set_xlabel("Jumlah task (GoCJ)")
ax.set_ylabel("Makespan (s)")
ax.set_xticks(SIZES)
ax.grid(alpha=0.3)
ax.legend(frameon=False)
fig.tight_layout()
fig.savefig(OUT / "makespan.png")
plt.close(fig)

# 2) Empat metrik lain
metrics = [
    ("Degree of Imbalance", "Degree of Imbalance", ""),
    ("Average Waiting Time (s)", "Waktu tunggu rata-rata", "(s)"),
    ("Throughput (cloudlets/s)", "Throughput", "(cloudlet/s)"),
    ("Average VM Utilization (%)", "Utilisasi VM (berbasis PE)", "(%)"),
]
fig, axes = plt.subplots(2, 2, figsize=(12, 8), dpi=130)
for ax, (col, title, unit) in zip(axes.ravel(), metrics):
    for algo in ("ACO", "WOA"):
        ax.plot(SIZES, mean.loc[algo][col].values, color=COLORS[algo],
                marker="o", ms=5, lw=2, label=algo)
    ax.set_title(title, fontsize=11, fontweight="bold")
    ax.set_xlabel("Jumlah task (GoCJ)")
    ax.set_ylabel((title + " " + unit).strip())
    ax.set_xticks(SIZES)
    ax.tick_params(axis="x", labelsize=8)
    ax.grid(alpha=0.3)
    ax.legend(frameon=False)
fig.suptitle("Metrik lain: ACO vs WOA (rata-rata 3 run)", fontsize=13, fontweight="bold")
fig.tight_layout(rect=[0, 0, 1, 0.96])
fig.savefig(OUT / "metrics-overview.png")
plt.close(fig)

print(f"Tersimpan di {OUT}/")
