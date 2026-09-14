<#
V26-F88 | S3 事后断言 + 应用连接落点取证（铁律 4 的「启动后立刻用 SQL 确认落在 3307」）
  1) 隔离 meta 库 flyway_schema_history 出现 19/20；
  2) data_quality_result 的 4 个 V20 列已存在（含 ORDINAL_POSITION）；
  3) quality_rule_definition（V19 定义表）存在及行数/版本；
  4) 3307 上本 run 账号的实际连接（应用确实落在 3307）；
  5) **3306 上本 run 前缀连接数 = 0**（只读 SELECT，仅为该断言）；
  6) 8091 健康检查。
  * 3307 读写仅 SELECT；3306 仅 SELECT。
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutName = '25-app-window-verification.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if (-not $env:HOST3306_PWD) { throw '未提供 3306 只读口令：请设置 HOST3306_PWD（不写入仓库）。' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$token = $RunId -replace '-', '_'
$db = "analytics_meta_$token"
$mdb = "analytics_metric_$token"

$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }
function Q([string]$port, [string]$pwd, [string]$title, [string]$sql) {
  Emit "`n### $title"
  Emit "SQL(port=$port): $sql"
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = $pwd
  try { & $MysqlExe --host=127.0.0.1 "--port=$port" --user=root -t -e $sql 2>&1 | ForEach-Object { Emit $_.ToString() } }
  finally { $env:MYSQL_PWD = $old }
}

Emit "V26-F88 S3 事后断言 + 连接落点取证  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "runId=$RunId  metaDb=$db  metricDb=$mdb"

$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
try {
  $i7 = (& $MysqlExe --host=127.0.0.1 --port=3307 --user=root -N -B -e "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir);" 2>&1) | Out-String
  Emit "`n### 隔离实例指纹（写目标）: $($i7.Trim())"
} finally { $env:MYSQL_PWD = $old }
$old = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:HOST3306_PWD
try {
  $i6 = (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root -N -B -e "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir);" 2>&1) | Out-String
  Emit "### 宿主实例指纹（只读目标）: $($i6.Trim())"
} finally { $env:MYSQL_PWD = $old }

Q 3307 '123456' 'A1 隔离库 flyway 版本清单（末 5 条）' "SELECT installed_rank,version,description,success,installed_on FROM $db.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
Q 3307 '123456' 'A2 断言：19/20 是否出现（必须 = 2）' "SELECT COUNT(*) AS has_19_20 FROM $db.flyway_schema_history WHERE version IN (19,20) AND success=1;"
Q 3307 '123456' 'A3 断言：当前 schema 版本（必须 = 20）' "SELECT MAX(CAST(version AS UNSIGNED)) AS max_version, COUNT(*) AS total_migrations FROM $db.flyway_schema_history WHERE success=1;"
Q 3307 '123456' 'B1 data_quality_result 列清单（V20 四列必须存在）' "SELECT ORDINAL_POSITION,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='data_quality_result' ORDER BY ORDINAL_POSITION;"
Q 3307 '123456' 'B2 断言：V20 四列存在数（必须 = 4）' "SELECT COUNT(*) AS v20_cols FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='data_quality_result' AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint');"
Q 3307 '123456' 'C1 V19 定义表存在性 + 行数（必须 = 1 张表）' "SELECT COUNT(*) AS qrd_table FROM information_schema.TABLES WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='quality_rule_definition';"
Q 3307 '123456' 'C2 quality_rule_definition 内容概览' "SELECT COUNT(*) AS defs, COUNT(DISTINCT rule_code) AS codes, MIN(version) AS min_v, MAX(version) AS max_v FROM $db.quality_rule_definition;"
Q 3307 '123456' 'C3 quality_rule_definition 是否含 fingerprint/checksum 列' "SELECT ORDINAL_POSITION,COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='quality_rule_definition' ORDER BY ORDINAL_POSITION;"
Q 3307 '123456' 'D1 3307 上本 run 账号的连接（应用落点，必须 > 0）' "SELECT USER, COUNT(*) AS conns, GROUP_CONCAT(DISTINCT DB) AS dbs FROM information_schema.PROCESSLIST WHERE USER LIKE '$token%' GROUP BY USER;"
Q 3307 '123456' 'D2 3307 上本 run 账号连接明细（前 8 条）' "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE USER LIKE '$token%' LIMIT 8;"
Q 3306 $env:HOST3306_PWD 'E1 断言：3306 上本 run 前缀连接数（必须 = 0）' "SELECT COUNT(*) AS f88_conns_on_3306 FROM information_schema.PROCESSLIST WHERE DB LIKE '%$token%' OR USER LIKE '$token%';"
Q 3306 $env:HOST3306_PWD 'E2 3306 上全部非系统连接（旁证：没有任何本 run 的库/账号）' "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE DB LIKE '%v25%' OR USER LIKE '%v25%';"

Emit "`n### F1 8091 健康检查"
try {
  $r = Invoke-WebRequest -Uri "$BaseUrl/api/v1/health" -SkipHttpErrorCheck -TimeoutSec 20
  Emit "GET $BaseUrl/api/v1/health -> HTTP $([int]$r.StatusCode)  body=$($r.Content)"
} catch { Emit "health 调用异常: $_" }

Emit "`n### F2 8091 监听进程"
Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue |
  ForEach-Object { Emit ("  {0}:{1} OwningProcess={2}" -f $_.LocalAddress, $_.LocalPort, $_.OwningProcess) }

$lines | Set-Content $out -Encoding utf8
Write-Output "[25] 证据已写 $out"
exit 0
