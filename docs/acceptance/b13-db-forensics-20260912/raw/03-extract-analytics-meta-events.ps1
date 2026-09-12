# B-13 forensics extractor v2 — READ ONLY.
# Only reports events that touch an EXACT schema.table pair, e.g. analytics_meta.metric_snapshot.
# Avoids the false positives from clone schemas (analytics_meta_p103, analytics_verify_m3_parity, ...).
param(
  [string]$Binlog = 'LAPTOP-8F8T3J1B-bin.000131',
  [string]$Start  = '2026-09-12 00:00:00',
  [string]$Stop   = '2026-09-12 23:30:00',
  [string]$OutFile = '',
  [int]$ContextLines = 34,
  [string[]]$Exact = @('analytics_meta.metric_snapshot','analytics_meta.metric_value')
)
$mb = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqlbinlog.exe'
$buf = New-Object System.Collections.Generic.List[string]
$console = New-Object System.Collections.Generic.List[string]
$pending = 0
$hitCount = 0
$alt = ($Exact | ForEach-Object { [regex]::Escape($_) }) -join '|'

& $mb -R --host=127.0.0.1 --port=3306 --user=root --password=123456 `
      --base64-output=DECODE-ROWS -v --start-datetime=$Start --stop-datetime=$Stop $Binlog 2>&1 |
ForEach-Object {
  $line = [string]$_
  $buf.Add($line)
  if ($buf.Count -gt 300) { $buf.RemoveAt(0) }

  $isHit = $line -match "(?i)``(?:$alt)``"
  if ($isHit) {
    if ($pending -eq 0) {
      $console.Add('')
      $console.Add('=========================== CONTEXT ===========================')
      $startIdx = [Math]::Max(0, $buf.Count - 1 - $ContextLines)
      for ($i = $startIdx; $i -lt $buf.Count - 1; $i++) { $console.Add($buf[$i]) }
      $console.Add('------------------------- TRIGGER ----------------------------')
    }
    $console.Add($line)
    $pending = 8
    $hitCount++
  }
  elseif ($pending -gt 0) {
    $console.Add($line)
    $pending--
    if ($pending -eq 0) { $console.Add('======================= END CONTEXT ===========================') }
  }
}
$console.Add("### extractor v2 done: binlog=$Binlog window=$Start..$Stop exact=$($Exact -join ',') hits=$hitCount")
if ($OutFile -ne '') { $console | Set-Content -Path $OutFile -Encoding UTF8 }
$console | Select-String -Pattern '### extractor v2 done' | ForEach-Object { $_.Line }
