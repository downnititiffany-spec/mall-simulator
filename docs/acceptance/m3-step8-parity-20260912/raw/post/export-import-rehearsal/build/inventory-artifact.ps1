# M3 rehearsal: export-artifact inventory (read-only).
# Reads metric-staging/ only; writes evidence into the report's raw/ dir.
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject\metric-staging'
$sid  = 'S20260901_47'
$out  = 'D:\Develop_code\GraduationProject\docs\acceptance\m3-step8-parity-20260912\raw\post\export-import-rehearsal\raw\60-artifact-inventory.txt'

"=== A. metric-staging snapshot dirs (root mtime = $(Get-Item $root | ForEach-Object {$_.LastWriteTime})) ===" | Set-Content $out -Encoding utf8
Get-ChildItem $root -Directory | Sort-Object Name | ForEach-Object {
  $f = Get-ChildItem $_.FullName -File | Where-Object { $_.Name -notlike '.*' }
  $h = Get-ChildItem $_.FullName -File -Force | Where-Object { $_.Name -like '.*' }
  $d = Get-ChildItem $_.FullName -Directory
  "{0}  visibleFiles={1} hiddenFiles(crc)={2} subdirs={3} lastWrite={4}" -f $_.Name, $f.Count, $h.Count, $d.Count, $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
} | Add-Content $out

"`n=== B. $sid contents: name / bytes / sha256 / first line (<=200 chars) ===" | Add-Content $out
Get-ChildItem (Join-Path $root $sid) -File | Where-Object { $_.Name -notlike '.*' } | Sort-Object Name | ForEach-Object {
  $first = (Get-Content $_.FullName -First 1)
  if ($first.Length -gt 200) { $first = $first.Substring(0,200) + '...[truncated]' }
  "{0}`n  bytes  = {1}`n  sha256 = {2}`n  first  = {3}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $first
} | Add-Content $out

"`n=== C. hidden sidecar files in $sid (Hadoop .crc) ===" | Add-Content $out
Get-ChildItem (Join-Path $root $sid) -File -Force | Where-Object { $_.Name -like '.*' } | Sort-Object Name |
  ForEach-Object { "{0}  {1} bytes" -f $_.Name, $_.Length } | Add-Content $out

"`n=== D. $sid/_export.json (verbatim) ===" | Add-Content $out
Get-Content (Join-Path (Join-Path $root $sid) '_export.json') -Raw | Add-Content $out
Write-Output "written: $out"
