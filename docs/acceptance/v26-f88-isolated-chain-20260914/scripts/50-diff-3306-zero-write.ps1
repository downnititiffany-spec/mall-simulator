<#
V26-F88 | S6 3306 零写入核对（只读）
  步骤：
   1) 用**同一个** window 重跑 3306 只读指纹 → raw/50-fingerprint-3306-after.txt（同 SQL 同 sha256）；
   2) 与 raw/00-fingerprint-3306-before.txt 逐项 diff（键集合 + 逐键值），任何一项变化都必须报出；
   3) 追加只读旁证：本 run 前缀连接数=0、本 run 库/账号在 3306 上不存在、
      information_schema.TABLES 上 analytics_meta/analytics_metric 的 CREATE_TIME 未变（无 DDL）、
      performance_schema 汇总里本窗口内触及本工程库的写语句条数。
  * 硬约束：3306 仅 SELECT / SHOW；本脚本对 3306 不执行任何 DML/DDL。
#>
param(
  [string]$Before = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw\00-fingerprint-3306-before.txt'),
  [string]$AfterName = '50-fingerprint-3306-after.txt',
  [string]$OutName = '50-3306-zero-write-diff.txt',
  [string]$WinStart = '2026-09-14 16:20:00',
  [string]$WinEnd = '2026-09-14 23:59:59',
  [string]$RunId = 'v25f88_20260914_1624',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if (-not $env:HOST3306_PWD) { throw '未提供 3306 只读口令：请设置 HOST3306_PWD（不写入仓库）。' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$after = Join-Path $OutDir $AfterName
$token = $RunId -replace '-', '_'

$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }
function Sql6([string]$sql) {
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:HOST3306_PWD
  try { return (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root -N -B -e $sql 2>&1) }
  finally { $env:MYSQL_PWD = $old }
}
function Q6([string]$title, [string]$sql) {
  Emit "`n### $title"
  Emit "SQL: $sql"
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:HOST3306_PWD
  try { & $MysqlExe --host=127.0.0.1 --port=3306 --user=root -t -e $sql 2>&1 | ForEach-Object { Emit $_.ToString() } }
  finally { $env:MYSQL_PWD = $old }
}

Emit "V26-F88 S6 3306 零写入核对  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "同窗口 = [$WinStart , $WinEnd]   before=$Before"

# ── 1) 重跑 after 指纹（复用冻结脚本，只读）───────────────────────────
Emit "`n===== 1) 重跑 3306 只读指纹（phase=after，同 SQL）====="
& pwsh -NoProfile -File (Join-Path $PSScriptRoot '00-fingerprint-3306-readonly.ps1') `
  -Phase after -OutName $AfterName -WinStart $WinStart -WinEnd $WinEnd 2>&1 |
  ForEach-Object { Emit $_.ToString() }
$afterExit = $LASTEXITCODE
Emit "[after 指纹脚本 exit code] = $afterExit"
if ($afterExit -ne 0) { $lines | Set-Content $out -Encoding utf8; throw "after 指纹失败，exit=$afterExit" }

# ── 2) 解析 + 逐项 diff ──────────────────────────────────────────────
function Parse([string]$path, [string]$phase) {
  $map = [ordered]@{}
  $inRaw = $false; $exit = ''
  foreach ($l in (Get-Content $path)) {
    if ($l -like '### ---- raw output*') { $inRaw = $true; continue }
    if ($l -like '### exit code:*') { $exit = $l; continue }
    if ($l -like '###*' -or -not $inRaw) { continue }
    if ($l -match "`t") { $kv = $l -split "`t", 2; $map[$kv[0].Trim()] = $kv[1] } 
    elseif ($l.Trim()) { $map[$l.Trim()] = '' }
  }
  return [pscustomobject]@{ Phase = $phase; Map = $map; Exit = $exit }
}
$b = Parse $Before 'before'
$a = Parse $after  'after'

Emit "`n`n===== 2) 逐项 diff（before vs after）====="
Emit ("{0,-42} {1,-46} {2,-46} {3}" -f 'KEY', 'BEFORE', 'AFTER', 'VERDICT')

$onlyB = @($b.Map.Keys | Where-Object { -not $a.Map.Contains($_) })
$onlyA = @($a.Map.Keys | Where-Object { -not $b.Map.Contains($_) })
$changed = New-Object System.Collections.Generic.List[string]
foreach ($k in $b.Map.Keys) {
  if (-not $a.Map.Contains($k)) { continue }
  $bv = [string]$b.Map[$k]; $av = [string]$a.Map[$k]
  $same = ($bv -ceq $av)
  if (-not $same) { $changed.Add($k) }
  Emit ("{0,-42} {1,-46} {2,-46} {3}" -f $k, $bv, $av, $(if ($same) { 'IDENTICAL' } else { '*** CHANGED ***' }))
}
foreach ($k in $onlyB) { Emit ("{0,-42} {1,-46} {2,-46} {3}" -f $k, [string]$b.Map[$k], '<ABSENT>', '*** 键消失 ***') }
foreach ($k in $onlyA) { Emit ("{0,-42} {1,-46} {2,-46} {3}" -f $k, '<ABSENT>', [string]$a.Map[$k], '*** 新键 ***') }

Emit "`n[汇总] 键数 before=$($b.Map.Keys.Count)  after=$($a.Map.Keys.Count)  仅 before=$($onlyB.Count)  仅 after=$($onlyA.Count)  值变化=$($changed.Count)"
if ($changed.Count -gt 0) { Emit ("[变化键] " + ($changed -join ', ')) }

$zeroWrite = ($onlyB.Count -eq 0 -and $onlyA.Count -eq 0 -and $changed.Count -eq 0)
Emit "`n[结论] 3306 指纹 $($b.Map.Keys.Count) 项全等 ⇒ $(if ($zeroWrite) { '零写入（PASS）' } else { '存在差异，必须逐项裁决（NOT PASS）' })"

# ── 3) 追加只读旁证 ─────────────────────────────────────────────────
Emit "`n`n===== 3) 追加只读旁证（全部 SELECT / SHOW）====="
Q6 'P1 本 run 前缀的库/账号在 3306 上是否存在（必须全 0）' `
  "SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '%$token%') AS f88_schemas, (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '%v25%') AS any_v25_schemas, (SELECT COUNT(*) FROM mysql.user WHERE user LIKE '%$token%') AS f88_accounts, (SELECT COUNT(*) FROM mysql.user WHERE user LIKE '%v25%') AS any_v25_accounts;"
Q6 'P2 本 run 前缀的实时连接（必须 0）' `
  "SELECT COUNT(*) AS f88_conns FROM information_schema.PROCESSLIST WHERE DB LIKE '%$token%' OR USER LIKE '%$token%';"
Q6 'P3 DDL 旁证：data_quality_result / quality_rule_definition 的 CREATE_TIME 与列数' `
  "SELECT TABLE_SCHEMA,TABLE_NAME,CREATE_TIME,UPDATE_TIME,TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND TABLE_NAME IN ('data_quality_result','quality_rule_definition','runtime_profile','pipeline_run','metric_snapshot','metric_value') ORDER BY TABLE_SCHEMA,TABLE_NAME;"
Q6 'P4 旁证：analytics_meta.flyway_schema_history 全量（版本+installed_on+checksum，应与 before 完全一致）' `
  "SELECT installed_rank,version,description,checksum,installed_on,success FROM analytics_meta.flyway_schema_history ORDER BY installed_rank;"
Q6 'P5 旁证：本窗口内 3306 上触及本工程库的写语句（performance_schema 汇总，期望无行）' `
  "SELECT SCHEMA_NAME,LEFT(DIGEST_TEXT,90) AS digest,COUNT_STAR,FIRST_SEEN,LAST_SEEN FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME IN ('analytics_meta','analytics_metric') AND DIGEST_TEXT REGEXP '^(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|REPLACE)' AND LAST_SEEN >= '$WinStart' ORDER BY LAST_SEEN DESC LIMIT 40;"
Q6 'P6 旁证：3306 上 analytics_meta 各表行数（与 before 的 dqr_rows/meta_table_count 呼应）' `
  "SELECT COUNT(*) AS meta_table_count FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta';"

$lines | Set-Content $out -Encoding utf8
Write-Output "[50] 证据已写 $out"
if ($zeroWrite) { exit 0 } else { exit 5 }
