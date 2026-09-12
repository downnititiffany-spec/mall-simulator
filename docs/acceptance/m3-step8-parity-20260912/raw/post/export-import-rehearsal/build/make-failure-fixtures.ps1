# M3 rehearsal: build SYNTHETIC failure fixtures from the local run-47 export artifact.
# IMPORTANT: the original artifact under metric-staging/ is opened read-only here; all
# mutations land in this report's build directory. Every mutation is printed for evidence.
$ErrorActionPreference = 'Stop'
$src = 'D:\Develop_code\GraduationProject\metric-staging\S20260901_47'
$b   = 'D:\Develop_code\GraduationProject\docs\acceptance\m3-step8-parity-20260912\raw\post\export-import-rehearsal\build'
$files = @('_export.json','ads_operation_overview_m.jsonl','ads_sale_trend_m.jsonl','ads_behavior_funnel_m.jsonl',
           'ads_active_trend_m.jsonl','ads_hot_product_m.jsonl','ads_product_conversion_m.jsonl',
           'ads_user_profile_m.jsonl','ads_data_quality_m.jsonl')

function New-Fixture([string]$name, [string]$snapshotId, [scriptblock]$mutate) {
  $dir = Join-Path $b $name
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  foreach ($f in $files) { Copy-Item (Join-Path $src $f) (Join-Path $dir $f) }
  $m = Get-Content (Join-Path $dir '_export.json') -Raw
  $m = $m.Replace('D:/Develop_code/GraduationProject/metric-staging/S20260901_47/', ($dir -replace '\\','/') + '/')
  $m = $m.Replace('S20260901_47', $snapshotId)
  Set-Content -Path (Join-Path $dir '_export.json') -Value $m -Encoding utf8NoBOM -NoNewline
  & $mutate $dir
  Write-Output "--- fixture: $dir (snapshotId=$snapshotId) ---"
  Get-ChildItem $dir -File | ForEach-Object {
    "{0,-34} {1,6} bytes  sha256={2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash
  }
  Write-Output "manifest head: " + ((Get-Content (Join-Path $dir '_export.json') -Raw) -split "`n" | Select-Object -First 3) -join ' | '
}

# ── Fixture B: row-count mismatch — drop ONE line from ads_hot_product_m.jsonl (manifest still says rowCount=4) ──
New-Fixture 'fx-b-missing-row' 'S20260901_47RH' {
  param($dir)
  $p = Join-Path $dir 'ads_hot_product_m.jsonl'
  $lines = Get-Content $p
  Write-Output "fx-b: ads_hot_product_m.jsonl lines BEFORE = $($lines.Count)"
  Set-Content -Path $p -Value ($lines[0..($lines.Count-2)]) -Encoding utf8NoBOM
  Write-Output "fx-b: ads_hot_product_m.jsonl lines AFTER  = $((Get-Content $p).Count)"
}

# ── Fixture C: illegal column — inject a non-whitelist column into the 7th table's first line ──
New-Fixture 'fx-c-unknown-column' 'S20260901_47RC' {
  param($dir)
  $p = Join-Path $dir 'ads_user_profile_m.jsonl'
  $lines = Get-Content $p
  Write-Output "fx-c: ads_user_profile_m.jsonl line1 BEFORE = $($lines[0])"
  $lines[0] = $lines[0].Replace('"user_id":3', '"user_id":3,"hacked_col":1')
  Set-Content -Path $p -Value $lines -Encoding utf8NoBOM
  Write-Output "fx-c: ads_user_profile_m.jsonl line1 AFTER  = $((Get-Content $p)[0])"
}

Write-Output "=== NON-EXISTENT export dir used by failure A ==="
$noSuch = Join-Path $b 'no-such-export-dir'
if (Test-Path $noSuch) { Remove-Item $noSuch -Recurse -Force }
Write-Output "path=$noSuch exists=$(Test-Path $noSuch)"
