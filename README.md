# Cloud Task Scheduling dengan ACO dan WOA (CloudSim 7.0.1)

Implementasi simulasi **Cloud Task Scheduling** menggunakan dua algoritma metaheuristik, **Ant Colony Optimization (ACO)** dan **Whale Optimization Algorithm (WOA)**, pada **CloudSim 7.0.1**.

Project ini dibuat untuk tugas mata kuliah **Strategi Optimasi Kerja Awan**.

## 1. Ringkasan Project

Eksperimen menggunakan workload **independent Bag of Tasks (BoT)** dari dataset **GoCJ** (file `GoCJ_Dataset_100.txt` sampai `GoCJ_Dataset_1000.txt`, satu file per ukuran). Untuk setiap ukuran workload (100, 200, ..., 1000 task), setiap algoritma dijalankan **3 kali**, sehingga total ada 2 algoritma x 10 ukuran x 3 run = **60 run**.

| Komponen | Konfigurasi |
| --- | --- |
| Simulator | CloudSim 7.0.1 |
| Java | OpenJDK 21 LTS |
| Build tool | Apache Maven 3.9.x |
| Datacenter | 1 |
| Host | 10 (5 Class A: 4 core, 2000 MIPS/core, 8 GB RAM; 5 Class B: 8 core, 3000 MIPS/core, 16 GB RAM) |
| VM | 20 VM heterogen (4 tipe x 5 VM) |
| VM Type 1 | 1 core, 1000 MIPS, 2 GB RAM |
| VM Type 2 | 2 core, 1500 MIPS, 2 GB RAM |
| VM Type 3 | 2 core, 2000 MIPS, 4 GB RAM |
| VM Type 4 | 4 core, 2500 MIPS, 4 GB RAM |
| Cloudlet | 100 sampai 1000 (kelipatan 100), dibaca dari file GoCJ per ukuran |
| Dataset | GoCJ_Dataset_100.txt sampai GoCJ_Dataset_1000.txt (satu file per ukuran; setiap file adalah sampel tersendiri) |
| Algoritma | ACO dan WOA |
| Run per ukuran | 3 (seed 1, 2, 3) |

## 2. Cara Kerja

### 2.1 Fitness (sama untuk ACO dan WOA)

Setiap solusi adalah pemetaan Cloudlet ke VM. Fitness dihitung dengan meniru antrean `CloudletSchedulerSpaceShared` (VM dengan p core menjalankan p Cloudlet paralel, antrean FIFO sesuai urutan id Cloudlet):

```
fitness = makespan + 0.001 x rata-rata waktu selesai Cloudlet
```

Makespan adalah tujuan utama. Suku kedua (bobot 0,1%) hanya pemecah seri: pada jumlah task kecil, satu Cloudlet terbesar (900.000 MI di VM tercepat 2500 MIPS = 360 s) sudah menentukan makespan sehingga banyak solusi bernilai sama. Estimasi fitness divalidasi terhadap hasil simulasi CloudSim: 55 dari 60 run selisihnya paling banyak 0,1 s, dan selisih terbesar 2,7 s (0,62%) akibat pembulatan waktu antar-event CloudSim pada antrean panjang. Makespan yang dilaporkan selalu hasil simulasi CloudSim, bukan estimasi.

### 2.2 ACO

Setiap semut membangun jadwal Cloudlet demi Cloudlet. Peluang memilih VM j untuk Cloudlet i:

```
p(i,j) ~ tau(i,j)^alpha x eta(i,j)^beta
eta(i,j) = 1 / (waktu selesai Cloudlet i jika ditaruh di VM j)
```

`eta` memperhitungkan kecepatan VM sekaligus antrean yang sudah terbentuk. Feromon diperbarui dengan penguapan, lalu deposit dari semut terbaik iterasi dan semut terbaik global (elitist), dengan batas bawah dan atas feromon.

| Parameter | Nilai |
| --- | --- |
| Semut | 30 |
| Iterasi | 100 |
| alpha / beta | 1 / 2 |
| rho (penguapan) | 0.3 |
| Q | 1.0 |
| tau awal / min / max | 1.0 / 0.01 / 2Q/rho |

### 2.3 WOA

Setiap paus adalah vektor posisi kontinu berdimensi N (satu elemen per Cloudlet) pada rentang [0, 20). Posisi didekode menjadi indeks VM dengan `floor()`. Memakai tiga perilaku WOA standar (Mirjalili dan Lewis, 2016): mengepung mangsa, mencari mangsa, dan gerakan spiral.

| Parameter | Nilai |
| --- | --- |
| Paus | 30 |
| Iterasi | 100 |
| b (bentuk spiral) | 1 |

Iterasi pertama mengevaluasi populasi awal, sehingga **kedua algoritma memakai 3000 evaluasi fitness** (perbandingan yang adil).

### 2.4 Metrik

| Metrik | Definisi |
| --- | --- |
| Makespan | Waktu selesai terakhir hasil simulasi CloudSim |
| Degree of Imbalance | (beban maks - beban min) / beban rata-rata antar VM |
| Average Waiting Time | Rata-rata waktu mulai eksekusi Cloudlet |
| Throughput | Jumlah Cloudlet / makespan |
| Average VM Utilization | Total waktu CPU Cloudlet / (makespan x 45 total PE seluruh VM), maksimum 100% |

**Catatan utilisasi:** rumus utilisasi pada implementasi MET lama membagi dengan (makespan x jumlah VM). Pada jadwal yang seimbang, rumus itu bisa melebihi 100% karena VM multi-core menjalankan beberapa Cloudlet paralel. Karena itu project ini memakai total PE sebagai pembagi. Nilai dengan rumus lama tetap disimpan di kolom `Utilization MET Formula (%)`.

## 3. Struktur Repository

```
.
├── README.md
├── dataset/
│   └── GoCJ_Dataset_100.txt ... GoCJ_Dataset_1000.txt   (10 file)
├── modules/
│   ├── cloudsim/
│   └── cloudsim-examples/
│       └── src/main/java/org/cloudbus/cloudsim/examples/
│           ├── ACO_TaskScheduling.java
│           └── WOA_TaskScheduling.java
├── scripts/
│   ├── run_all.sh            # runner 60 run (Linux/macOS/Git Bash)
│   ├── run_all.ps1           # runner 60 run (Windows PowerShell)
│   └── build_workbook.py     # membangun file Excel dari CSV
├── results/
│   ├── aco_raw.csv
│   ├── woa_raw.csv
│   ├── ACO_WOA_Results.xlsx
│   └── logs/                 # output konsol setiap run
├── pom.xml
└── ...
```

Repository ini adalah **salinan CloudSim 7.0.1** yang ditambahi file di atas, sama seperti repo tugas sebelumnya, sehingga langsung dapat dibangun dengan Maven.

## 4. Persyaratan

- JDK 21 (`java -version`)
- Apache Maven 3.9.x (`mvn -version`), dijalankan dengan JDK 21
- Python 3 dengan `openpyxl` (hanya untuk membangun ulang file Excel)

## 5. Build

Dari **root repository** (folder yang berisi `pom.xml`):

```
mvn clean install
```

Program membaca dataset lewat path relatif `dataset/GoCJ_Dataset_<jumlahTask>.txt` (misalnya 200 task membaca `GoCJ_Dataset_200.txt`), sehingga selalu dijalankan dari root repository.

## 6. Menjalankan Satu Simulasi

Argumen: `jumlahTask run seed fileCsv`. Tanpa argumen program memakai 1000 task, run 1, seed 1, dan menulis ke `results/aco_manual.csv` atau `results/woa_manual.csv` (file ini diabaikan Git, aman untuk demo).

```
mvn exec:java -pl modules/cloudsim-examples/ -Dexec.mainClass=org.cloudbus.cloudsim.examples.ACO_TaskScheduling

mvn exec:java -pl modules/cloudsim-examples/ -Dexec.mainClass=org.cloudbus.cloudsim.examples.WOA_TaskScheduling
```

Contoh dengan argumen (200 task, run 2, seed 2):

```
mvn exec:java -pl modules/cloudsim-examples/ -Dexec.mainClass=org.cloudbus.cloudsim.examples.ACO_TaskScheduling "-Dexec.args=200 2 2 results/contoh.csv"
```

## 7. Menjalankan Seluruh Eksperimen (60 run)

Windows PowerShell:

```
powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1
```

Linux/macOS/Git Bash:

```
scripts/run_all.sh
```

Opsi: pilih satu algoritma (`-Algo aco` atau `-Algo woa` di PowerShell, `aco` atau `woa` di Bash) dan `-Force`/`--force` untuk menimpa hasil lama. Tanpa opsi force, skrip berhenti bila CSV sudah ada agar hasil tidak tertimpa tanpa sengaja.

Hasil: `results/aco_raw.csv`, `results/woa_raw.csv`, dan log tiap run di `results/logs/`.

## 8. Membangun File Excel

```
pip install openpyxl
python scripts/build_workbook.py
```

Menghasilkan `results/ACO_WOA_Results.xlsx` dengan sheet **Raw Results** (semua run), **Average Results** (rata-rata, simpangan baku makespan, dan perbandingan ACO vs WOA, semuanya rumus), dan **Charts** (6 grafik ACO vs WOA).

## 9. Hasil (rata-rata 3 run)

| Tasks | ACO Makespan (s) | WOA Makespan (s) | ACO Util (%) | WOA Util (%) | ACO Wait (s) | WOA Wait (s) |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 360.08 | 360.01 | 37.98 | 39.96 | 23.82 | 35.24 |
| 200 | 437.58 | 643.82 | 68.01 | 46.95 | 89.98 | 148.97 |
| 300 | 552.16 | 713.79 | 83.46 | 63.63 | 160.47 | 190.64 |
| 400 | 668.67 | 955.51 | 85.87 | 59.34 | 219.37 | 256.36 |
| 500 | 812.68 | 1189.01 | 88.58 | 61.00 | 296.09 | 354.68 |
| 600 | 1027.17 | 1585.07 | 88.92 | 56.60 | 373.52 | 460.20 |
| 700 | 1075.43 | 1629.64 | 90.65 | 61.55 | 439.65 | 561.38 |
| 800 | 1208.26 | 1939.56 | 91.28 | 58.90 | 505.71 | 645.25 |
| 900 | 1474.86 | 2303.74 | 90.74 | 60.62 | 568.89 | 757.52 |
| 1000 | 1584.50 | 2224.56 | 90.76 | 63.93 | 632.81 | 771.53 |

Ringkasan temuan:

- ACO menghasilkan makespan lebih kecil daripada WOA pada hampir seluruh ukuran workload dengan anggaran evaluasi yang sama. Pada 100 task keduanya sekitar 360 s karena makespan sudah mencapai batas bawah teoretis (job terbesar 900.000 MI di VM 2500 MIPS).
- WOA lebih bervariasi antar run (simpangan baku makespan hingga 182 s pada 700 task, vs maksimum 27 s pada ACO). WOA standar dengan pengodean posisi kontinu cenderung mengalami konvergensi dini pada masalah diskret berdimensi tinggi (N dimensi). Menaikkan iterasi WOA 10x pada uji pendahuluan tidak memperbaiki hasil secara berarti.
- WOA lebih cepat secara komputasi (waktu penjadwalan rata-rata sekitar 37 sampai 170 ms vs 132 sampai 474 ms pada ACO), karena ACO membayar biaya konstruksi solusi per semut. Angka ini bergantung pada komputer.

## 10. Reproducibility

Dengan seed yang sama, makespan, degree of imbalance, waiting time, throughput, dan utilisasi bersifat **deterministik**. Hanya kolom `Scheduling Time (ms)` yang bergantung pada kecepatan komputer. Jangan mengubah dataset, jumlah host/VM, parameter algoritma, atau seed tanpa mencatatnya sebagai eksperimen baru.

## 11. Troubleshooting

- `Dataset file not found`: jalankan dari root repository dan pastikan `dataset/GoCJ_Dataset_<jumlahTask>.txt` ada (10 file, 100 sampai 1000).
- `mvn` tidak dikenali: periksa instalasi Maven dan `PATH`, lalu jalankan `mvn -version`.
- Main class tidak ditemukan: pastikan file berada di `modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/`, lalu jalankan ulang `mvn clean install`.
- Angka desimal dengan koma di CSV: tidak terjadi, program memakai `Locale.ROOT` sehingga selalu titik desimal.

## 12. File yang Perlu Di-commit

Tambahkan ke salinan CloudSim 7.0.1:

```
README.md
.gitignore
dataset/ (10 file GoCJ_Dataset_*.txt)
modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/ACO_TaskScheduling.java
modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/WOA_TaskScheduling.java
scripts/
results/
```

## 13. Referensi

- CloudSim. Cloudslab. <https://github.com/Cloudslab/cloudsim>
- GoCJ: Google Cloud Jobs Dataset. Mendeley Data. <https://data.mendeley.com/datasets/b7bp6xhrcd/1>
- Dorigo, M. dan Stutzle, T. (2004). *Ant Colony Optimization*. MIT Press.
- Mirjalili, S. dan Lewis, A. (2016). The Whale Optimization Algorithm. *Advances in Engineering Software*, 95, 51-67.
