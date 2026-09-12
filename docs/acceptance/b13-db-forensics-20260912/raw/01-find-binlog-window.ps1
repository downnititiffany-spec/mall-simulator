# B-13 forensics helper — READ ONLY.
# Scans each binlog file for the FIRST and LAST event timestamp, to find which
# files overlap the 2026-09-12 22:00:17 .. 23:10:00 incident window.
# mysqlbinlog only READS the file; no MySQL write statement is issued.
$ErrorActionPreference = 'Continue'
$mb = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqlbinlog.exe'
$data = 'C:\ProgramData\MySQL\MySQL Server 8.0\Data'

$files = Get-ChildItem (Join-Path $data 'LAPTOP-8F8T3J1B-bin.*') | Where-Object { $_.Name -match '^LAPTOP-8F8T3J1B-bin\.\d{6}$' } | Sort-Object Name
foreach ($f in $files) {
  $first = $null; $last = $null; $n = 0
  & $mb $f.FullName 2>&1 | ForEach-Object {
    $line = [string]$_
    if ($line -match '^#(\d{6})\s+(\d{1,2}:\d{2}:\d{2})') {
      $ts = $Matches[1] + ' ' + $Matches[2]
      if ($null -eq $first) { $first = $ts }
      $last = $ts
    }
    $n++
  }
  '{0}  size={1,12}  lines={2,9}  first={3}  last={4}' -f $f.Name, $f.Length, $n, $first, $last
}
