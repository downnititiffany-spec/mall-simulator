# B-13 forensics extractor — READ ONLY.
# Streams mysqlbinlog output (remote, read-only) and prints ONLY the DDL/DML events
# that touch a metric table, together with 30 lines of preceding context so the
# enclosing `use <db>` / BEGIN / thread_id / timestamp / GTID are visible.
param(
  [string]$Binlog = 'LAPTOP-8F8T3J1B-bin.000131',
  [string]$Start  = '2026-09-12 22:00:00',
  [string]$Stop   = '2026-09-12 23:20:00',
  [string]$OutFile = '',
  [int]$ContextLines = 30
)
$mb = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqlbinlog.exe'
$buf = New-Object System.Collections.Generic.List[string]
$console = New-Object System.Collections.Generic.List[string]
$pending = 0
$hitCount = 0

& $mb -R --host=127.0.0.1 --port=3306 --user=root --password=123456 `
      --base64-output=DECODE-ROWS -v --start-datetime=$Start --stop-datetime=$Stop $Binlog 2>&1 |
ForEach-Object {
  $line = [string]$_
  $buf.Add($line)
  if ($buf.Count -gt 200) { $buf.RemoveAt(0) }

  $isHit = $line -match '(?i)(CREATE|DROP|TRUNCATE|ALTER)\s+TABLE\s+`?[A-Za-z0-9_]*`?\.?`?(metric_snapshot|metric_value)`?' `
        -or $line -match '(?i)#{3}\s+(INSERT INTO|UPDATE|DELETE FROM)\s+`[A-Za-z0-9_]+`\.`(metric_snapshot|metric_value)`'
  $isDdl = $line -match '(?i)^\s*(CREATE|DROP|TRUNCATE|ALTER)\s+TABLE'

  if ($isHit -or $isDdl) {
    if ($pending -eq 0) {
      $console.Add('')
      $console.Add('=========================== CONTEXT ===========================')
      $startIdx = [Math]::Max(0, $buf.Count - 1 - $ContextLines)
      for ($i = $startIdx; $i -lt $buf.Count - 1; $i++) { $console.Add($buf[$i]) }
      $console.Add('------------------------- TRIGGER ----------------------------')
    }
    $console.Add($line)
    $pending = 6
    $hitCount++
  }
  elseif ($pending -gt 0) {
    $console.Add($line)
    $pending--
    if ($pending -eq 0) { $console.Add('======================= END CONTEXT ===========================') }
  }
}
$console.Add("### extractor done: binlog=$Binlog window=$Start..$Stop metric_hits=$hitCount total_lines=$($buf.Count)")
if ($OutFile -ne '') { $console | Set-Content -Path $OutFile -Encoding UTF8 }
$console
