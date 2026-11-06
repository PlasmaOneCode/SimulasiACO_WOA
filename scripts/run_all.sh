#!/usr/bin/env bash
# Menjalankan seluruh eksperimen: ukuran 100..1000 (kelipatan 100) x 3 run.
# Seed run ke-r adalah r, sehingga hasil dapat direproduksi.
#
# Pemakaian (dari root repository, setelah `mvn clean install`):
#   scripts/run_all.sh [aco|woa|all] [--force]
#
# --force  menghapus CSV dan log lama sebelum menjalankan ulang.
set -euo pipefail
cd "$(dirname "$0")/.."

ALGO="${1:-all}"
FORCE="${2:-}"
SIZES="100 200 300 400 500 600 700 800 900 1000"
RUNS="1 2 3"

run_algo() {
  local name="$1" class="$2"
  local csv="results/${name}_raw.csv"
  local logdir="results/logs/${name}"

  if [[ -s "$csv" ]]; then
    if [[ "$FORCE" == "--force" ]]; then
      rm -f "$csv"
      rm -rf "$logdir"
    else
      echo "ERROR: $csv sudah ada. Jalankan dengan --force untuk menimpa." >&2
      exit 1
    fi
  fi
  mkdir -p "$logdir"

  for n in $SIZES; do
    for r in $RUNS; do
      echo "[${name}] tasks=${n} run=${r} seed=${r}"
      mvn -q exec:java -pl modules/cloudsim-examples/ \
        -Dexec.mainClass="org.cloudbus.cloudsim.examples.${class}" \
        "-Dexec.args=${n} ${r} ${r} ${csv}" \
        > "${logdir}/${name}_${n}_run${r}.txt" 2>&1 \
        || { echo "GAGAL: ${name} tasks=${n} run=${r} (lihat ${logdir})" >&2; exit 1; }
    done
  done
  echo "[${name}] selesai -> ${csv}"
}

case "$ALGO" in
  aco) run_algo aco ACO_TaskScheduling ;;
  woa) run_algo woa WOA_TaskScheduling ;;
  all) run_algo aco ACO_TaskScheduling; run_algo woa WOA_TaskScheduling ;;
  *) echo "Pemakaian: $0 [aco|woa|all] [--force]" >&2; exit 1 ;;
esac
