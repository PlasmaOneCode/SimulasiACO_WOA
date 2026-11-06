<#
Menjalankan seluruh eksperimen: ukuran 100..1000 (kelipatan 100) x 3 run.
Seed run ke-r adalah r, sehingga hasil dapat direproduksi.

Pemakaian (dari root repository, setelah `mvn clean install`):
  powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1 [-Algo aco|woa|all] [-Force]

-Force  menghapus CSV dan log lama sebelum menjalankan ulang.
#>
param(
    [ValidateSet('aco', 'woa', 'all')][string]$Algo = 'all',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$sizes = 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000
$runs  = 1, 2, 3

function Invoke-Algo([string]$name, [string]$class) {
    $csv    = "results/${name}_raw.csv"
    $logdir = "results/logs/$name"

    if ((Test-Path $csv) -and (Get-Item $csv).Length -gt 0) {
        if ($Force) {
            Remove-Item $csv -Force
            if (Test-Path $logdir) { Remove-Item $logdir -Recurse -Force }
        } else {
            throw "$csv sudah ada. Jalankan dengan -Force untuk menimpa."
        }
    }
    New-Item -ItemType Directory -Force -Path $logdir | Out-Null

    foreach ($n in $sizes) {
        foreach ($r in $runs) {
            Write-Host "[$name] tasks=$n run=$r seed=$r"
            $log = "$logdir/${name}_${n}_run${r}.txt"
            & mvn -q exec:java -pl modules/cloudsim-examples/ `
                "-Dexec.mainClass=org.cloudbus.cloudsim.examples.$class" `
                "-Dexec.args=$n $r $r $csv" 2>&1 |
                Out-File -FilePath $log -Encoding utf8
            if ($LASTEXITCODE -ne 0) {
                throw "GAGAL: $name tasks=$n run=$r (lihat $log)"
            }
        }
    }
    Write-Host "[$name] selesai -> $csv"
}

if ($Algo -in 'aco', 'all') { Invoke-Algo 'aco' 'ACO_TaskScheduling' }
if ($Algo -in 'woa', 'all') { Invoke-Algo 'woa' 'WOA_TaskScheduling' }
