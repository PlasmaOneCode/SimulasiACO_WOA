#!/usr/bin/env python3
"""
Membangun results/ACO_WOA_Results.xlsx dari results/aco_raw.csv dan
results/woa_raw.csv.

Sheet:
  Raw Results      : seluruh run (nilai dari CSV)
  Average Results  : rata-rata per ukuran (rumus AVERAGEIFS) + perbandingan
  Charts           : grafik ACO vs WOA untuk setiap metrik

Pemakaian (dari root repository):
  pip install openpyxl
  python scripts/build_workbook.py [folder_results]
"""
import csv
import sys
from pathlib import Path

from openpyxl import Workbook
from openpyxl.chart import LineChart, Reference, Series
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

RESULTS = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
OUT = RESULTS / "ACO_WOA_Results.xlsx"
SOURCES = [("ACO", RESULTS / "aco_raw.csv"), ("WOA", RESULTS / "woa_raw.csv")]
SIZES = list(range(100, 1001, 100))

FONT = "Arial"
HEAD_FILL = PatternFill("solid", start_color="1F3864")
SUB_FILL = PatternFill("solid", start_color="D9E2F3")
THIN = Side(style="thin", color="BFBFBF")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)


def style_header(ws, row, first_col, last_col):
    for c in range(first_col, last_col + 1):
        cell = ws.cell(row=row, column=c)
        cell.font = Font(name=FONT, bold=True, color="FFFFFF", size=10)
        cell.fill = HEAD_FILL
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = BORDER


def read_rows():
    rows = []
    for algo, path in SOURCES:
        if not path.exists():
            sys.exit(f"File tidak ditemukan: {path}")
        with path.open(newline="", encoding="utf-8") as f:
            for rec in csv.DictReader(f):
                rows.append((algo, rec))
    rows.sort(key=lambda x: (x[0], int(x[1]["Tasks"]), int(x[1]["Run"])))
    return rows


rows = read_rows()
wb = Workbook()

# ---------------------------------------------------------------- Raw Results
raw = wb.active
raw.title = "Raw Results"
raw_cols = [
    ("Algorithm", None),
    ("Dataset", "Dataset"),
    ("Tasks", "Tasks"),
    ("Run", "Run"),
    ("Seed", "Seed"),
    ("Completed Cloudlets", "Completed Cloudlets"),
    ("Makespan (s)", "Makespan (s)"),
    ("Degree of Imbalance", "Degree of Imbalance"),
    ("Average Waiting Time (s)", "Average Waiting Time (s)"),
    ("Throughput (cloudlets/s)", "Throughput (cloudlets/s)"),
    ("Average VM Utilization (%)", "Average VM Utilization (%)"),
    ("Estimated Makespan (s)", "Estimated Makespan (s)"),
    ("Scheduling Time (ms)", "Scheduling Time (ms)"),
    ("Utilization MET Formula (%)", "Utilization MET Formula (%)"),
]
for c, (title, _) in enumerate(raw_cols, start=1):
    raw.cell(row=1, column=c, value=title)
style_header(raw, 1, 1, len(raw_cols))
raw.row_dimensions[1].height = 42

int_cols = {"Tasks", "Run", "Seed", "Completed Cloudlets"}
for r, (algo, rec) in enumerate(rows, start=2):
    for c, (title, key) in enumerate(raw_cols, start=1):
        if key is None:
            val = algo
        elif key == "Dataset":
            val = rec[key]
        elif key in int_cols:
            val = int(rec[key])
        else:
            val = float(rec[key])
        cell = raw.cell(row=r, column=c, value=val)
        cell.font = Font(name=FONT, size=10)
        cell.border = BORDER
        if key in ("Makespan (s)", "Average Waiting Time (s)", "Estimated Makespan (s)",
                   "Scheduling Time (ms)", "Average VM Utilization (%)",
                   "Utilization MET Formula (%)"):
            cell.number_format = "0.00"
        elif key in ("Degree of Imbalance", "Throughput (cloudlets/s)"):
            cell.number_format = "0.0000"

LAST = len(rows) + 1  # baris data terakhir
for c in range(1, len(raw_cols) + 1):
    raw.column_dimensions[get_column_letter(c)].width = 16
raw.column_dimensions["B"].width = 14
raw.freeze_panes = "A2"
raw.auto_filter.ref = f"A1:{get_column_letter(len(raw_cols))}{LAST}"

# ------------------------------------------------------------ Average Results
avg = wb.create_sheet("Average Results")
avg["A1"] = "Average Results: ACO vs WOA (GoCJ, rata-rata 3 run per ukuran)"
avg["A1"].font = Font(name=FONT, bold=True, size=13)

headers = ["Algorithm", "Tasks", "Runs", "Makespan (s)", "Makespan Std Dev (s)",
           "Degree of Imbalance", "Average Waiting Time (s)",
           "Throughput (cloudlets/s)", "Average VM Utilization (%)",
           "Scheduling Time (ms)"]
# kolom Raw Results untuk tiap metrik rata-rata
metric_src = {4: "G", 6: "H", 7: "I", 8: "J", 9: "K", 10: "M"}
metric_fmt = {4: "0.00", 5: "0.00", 6: "0.0000", 7: "0.00", 8: "0.0000", 9: "0.00", 10: "0.00"}

A_RNG = f"'Raw Results'!$A$2:$A${LAST}"
C_RNG = f"'Raw Results'!$C$2:$C${LAST}"
G_RNG = f"'Raw Results'!$G$2:$G${LAST}"

blocks = {}  # algo -> baris pertama data
start = 4
for algo in ("ACO", "WOA"):
    avg.cell(row=start - 1, column=1, value=f"{algo} (Ant Colony Optimization)" if algo == "ACO"
             else f"{algo} (Whale Optimization Algorithm)").font = Font(name=FONT, bold=True, size=11)
    for c, h in enumerate(headers, start=1):
        avg.cell(row=start, column=c, value=h)
    style_header(avg, start, 1, len(headers))
    avg.row_dimensions[start].height = 42
    first = start + 1
    blocks[algo] = first
    for i, n in enumerate(SIZES):
        r = first + i
        avg.cell(row=r, column=1, value=algo)
        avg.cell(row=r, column=2, value=n)
        avg.cell(row=r, column=3,
                 value=f"=COUNTIFS({A_RNG},$A{r},{C_RNG},$B{r})")
        for col, src in metric_src.items():
            rng = f"'Raw Results'!${src}$2:${src}${LAST}"
            avg.cell(row=r, column=col,
                     value=f"=AVERAGEIFS({rng},{A_RNG},$A{r},{C_RNG},$B{r})")
        avg.cell(row=r, column=5,
                 value=(f"=IF(C{r}>1,SQRT(SUMPRODUCT(({A_RNG}=$A{r})*({C_RNG}=$B{r})"
                        f"*({G_RNG}-D{r})^2)/(C{r}-1)),0)"))
        for c in range(1, len(headers) + 1):
            cell = avg.cell(row=r, column=c)
            cell.font = Font(name=FONT, size=10)
            cell.border = BORDER
            if c in metric_fmt:
                cell.number_format = metric_fmt[c]
    start = first + len(SIZES) + 3  # jarak antar blok

# Perbandingan makespan
cmp_title = start - 1
avg.cell(row=cmp_title, column=1, value="Perbandingan Makespan ACO vs WOA").font = Font(
    name=FONT, bold=True, size=11)
cmp_heads = ["Tasks", "ACO Makespan (s)", "WOA Makespan (s)",
             "Selisih WOA - ACO (s)", "ACO lebih cepat (%)"]
for c, h in enumerate(cmp_heads, start=1):
    avg.cell(row=start, column=c, value=h)
style_header(avg, start, 1, len(cmp_heads))
avg.row_dimensions[start].height = 42
cmp_first = start + 1
for i, n in enumerate(SIZES):
    r = cmp_first + i
    ra, rw = blocks["ACO"] + i, blocks["WOA"] + i
    avg.cell(row=r, column=1, value=f"=B{ra}")
    avg.cell(row=r, column=2, value=f"=D{ra}")
    avg.cell(row=r, column=3, value=f"=D{rw}")
    avg.cell(row=r, column=4, value=f"=C{r}-B{r}")
    avg.cell(row=r, column=5, value=f"=IF(C{r}=0,0,(C{r}-B{r})/C{r})")
    for c in range(1, 6):
        cell = avg.cell(row=r, column=c)
        cell.font = Font(name=FONT, size=10)
        cell.border = BORDER
    for c in (2, 3, 4):
        avg.cell(row=r, column=c).number_format = "0.00"
    avg.cell(row=r, column=5).number_format = "0.0%"

# Catatan asumsi
note_row = cmp_first + len(SIZES) + 2
notes = [
    "Catatan dan asumsi:",
    "- Dataset: file GoCJ per ukuran, GoCJ_Dataset_N.txt (N = 100, 200, ..., 1000); setiap file adalah sampel tersendiri.",
    "- Setiap ukuran dijalankan 3 kali; seed run ke-r adalah r (1, 2, 3). Sel Tasks dan Algorithm adalah input tetap; kolom lain berupa rumus dari sheet Raw Results.",
    "- ACO: 30 semut x 100 iterasi, alpha=1, beta=2, rho=0.3, Q=1. WOA: 30 paus x 100 iterasi, b=1. Keduanya 3000 evaluasi fitness.",
    "- Fitness = makespan + 0,001 x rata-rata waktu selesai (tie-breaker); Makespan yang dilaporkan adalah hasil simulasi CloudSim.",
    "- Average VM Utilization = total waktu CPU / (makespan x 45 total PE VM), maksimum 100%. Rumus MET lama tersedia di kolom terakhir Raw Results dan dapat melebihi 100%.",
    "- Waktu penjadwalan (ms) bergantung pada komputer dan dapat berbeda antar mesin; metrik lain deterministik untuk seed yang sama.",
]
for i, text in enumerate(notes):
    cell = avg.cell(row=note_row + i, column=1, value=text)
    cell.font = Font(name=FONT, size=10, bold=(i == 0))

avg.column_dimensions["A"].width = 14
for c in range(2, 11):
    avg.column_dimensions[get_column_letter(c)].width = 17
avg.freeze_panes = "A4"

# --------------------------------------------------------------------- Charts
ch = wb.create_sheet("Charts")
ch["A1"] = "Grafik ACO vs WOA (sumbu X: jumlah task GoCJ)"
ch["A1"].font = Font(name=FONT, bold=True, size=13)

chart_defs = [
    (4, "Makespan", "Makespan (s)"),
    (6, "Degree of Imbalance", "Degree of Imbalance"),
    (7, "Average Waiting Time", "Waktu tunggu rata-rata (s)"),
    (8, "Throughput", "Throughput (cloudlets/s)"),
    (9, "Average VM Utilization", "Utilisasi VM (%)"),
    (10, "Scheduling Time", "Waktu penjadwalan (ms)"),
]
colors = {"ACO": "1F77B4", "WOA": "D62728"}
anchors = ["A3", "L3", "A22", "L22", "A41", "L41"]

for (col, title, ytitle), anchor in zip(chart_defs, anchors):
    chart = LineChart()
    chart.title = f"{title}: ACO vs WOA"
    chart.height = 8.5
    chart.width = 17
    chart.y_axis.title = ytitle
    chart.x_axis.title = "Jumlah task"
    chart.x_axis.delete = False
    chart.y_axis.delete = False
    chart.legend.position = "b"
    cats = Reference(avg, min_col=2, min_row=blocks["ACO"], max_row=blocks["ACO"] + len(SIZES) - 1)
    for algo in ("ACO", "WOA"):
        first = blocks[algo]
        data = Reference(avg, min_col=col, min_row=first, max_row=first + len(SIZES) - 1)
        s = Series(data, title=algo)
        s.graphicalProperties.line.solidFill = colors[algo]
        s.graphicalProperties.line.width = 22000
        s.marker.symbol = "circle"
        s.marker.size = 6
        s.marker.graphicalProperties.solidFill = colors[algo]
        s.marker.graphicalProperties.line.solidFill = colors[algo]
        s.smooth = False
        chart.series.append(s)
    chart.set_categories(cats)
    ch.add_chart(chart, anchor)

wb.save(OUT)
print(f"Tersimpan: {OUT}")
