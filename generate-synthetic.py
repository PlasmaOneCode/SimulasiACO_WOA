"""Pembangkit dataset synthetic untuk eksperimen MET (seed 42).

Proporsi: Small 30%, Medium 40%, Large 20%, Very Large 10% (sisa pembulatan).
Setiap file memakai random.Random(42) sendiri, sehingga file dapat dibuat ulang satu per satu.
Pakai: python generate-synthetic.py   (menulis dataset/synthetic/Synthetic_N.txt)
"""
import os, random

SEED = 42
CATEGORIES = [("small", 0.30, 15000, 50000),
              ("medium", 0.40, 50001, 150000),
              ("large", 0.20, 150001, 500000),
              ("vlarge", 0.10, 500001, 900000)]

def generate(n):
    # Menghitung jumlah data per kategori; kategori terakhir menyerap pembulatan.
    rng = random.Random(SEED)
    counts = [round(n * p) for _, p, _, _ in CATEGORIES[:3]]
    counts.append(n - sum(counts))  # Very Large menyerap sisa pembulatan
    values = []
    for (_, _, lo, hi), c in zip(CATEGORIES, counts):
        values += [rng.randint(lo, hi) for _ in range(c)]
    # Mengacak urutan agar kategori tersebar dalam dataset.
    rng.shuffle(values)
    return values, counts

if __name__ == "__main__":
    # Membuat dataset berukuran 1.000 sampai 10.000 tugas.
    out = os.path.join("dataset", "synthetic")
    os.makedirs(out, exist_ok=True)
    for n in range(1000, 10001, 1000):
        vals, counts = generate(n)
        # Menulis satu nilai panjang tugas pada setiap baris.
        with open(os.path.join(out, f"Synthetic_{n}.txt"), "w", newline="\n") as f:
            f.write("\n".join(str(v) for v in vals))
        print(f"Synthetic_{n}: n={len(vals)} {counts} min={min(vals)} max={max(vals)} mean={round(sum(vals)/len(vals))}")
