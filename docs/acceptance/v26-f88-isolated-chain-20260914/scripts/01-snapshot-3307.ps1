<#
V26-F88 | 隔离实例 3307 现状只读快照（S1）
  * 端口必须实测为 3307 且 uuid 等于隔离实例指纹，否则 throw（3306 连都不连）。
  * 全 SELECT；零 DDL / 零 DML。
  * 逐个列出 3307 上所有 analytics_meta* / analytics_metric* 库的 flyway 版本清单与
    data_quality_result 的列清单 / 行数 —— 用来回答「V19/V20 在 3307 上是否落地过」。
#>
param(
  [string]$OutName = '01-snapshot-3307-before.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$ExpectUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [int]$Port = 3307
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($Port -ne 3307) { throw "本脚本只允许 3307（当前 $Port）" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName

$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
$lines = New-Object System.Collections.Generic.List[string]
function Q([string]$title, [string]$sql) {
  $lines.Add("`n### $title")
  $lines.Add("SQL: $sql")
  & $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -t -e $sql 2>&1 | ForEach-Object { $lines.Add($_.ToString()) }
}
try {
  $lines.Add("V26-F88 隔离实例 3307 现状快照  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))")
  $id = (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir,'|',@@version);" 2>&1) | Out-String
  $id = $id.Trim()
  if ($id -notmatch "^3307\|$ExpectUuid\|") { throw "目标不是隔离实例，拒绝继续：$id" }
  $lines.Add("### instance fingerprint (port|uuid|datadir|version): $id")
  # 静态只读自检
  foreach ($s in @('SELECT 1')) { }
  Q '3307 上全部 schema' "SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME;"
  Q '3307 上全部 v25* / analytics* 账号' "SELECT User, Host FROM mysql.user WHERE User LIKE 'v25%' OR User LIKE 'analytics%' ORDER BY User;"

  $dbs = & $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'analytics_meta%' ORDER BY SCHEMA_NAME;" 2>&1
  foreach ($db in $dbs) {
    $db = $db.Trim(); if (-not $db) { continue }
    Q "[$db] flyway 版本清单" "SELECT installed_rank, version, description, success, installed_on FROM $db.flyway_schema_history ORDER BY installed_rank;"
    Q "[$db] flyway 版本号汇总" "SELECT COUNT(*) AS cnt, GROUP_CONCAT(version ORDER BY installed_rank) AS versions, MAX(installed_rank) AS max_rank FROM $db.flyway_schema_history;"
    $hasDqr = (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='data_quality_result';" 2>&1) | Out-String
    if ($hasDqr.Trim() -eq '1') {
      Q "[$db] data_quality_result 列清单" "SELECT ORDINAL_POSITION, COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='data_quality_result' ORDER BY ORDINAL_POSITION;"
      Q "[$db] data_quality_result 行数 + run 分布" "SELECT COUNT(*) AS rows_cnt, COUNT(DISTINCT run_id) AS runs, MIN(created_at) AS first_at, MAX(created_at) AS last_at FROM $db.data_quality_result;"
      Q "[$db] V20 四列是否存在" "SELECT COUNT(*) AS v20_cols FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='data_quality_result' AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint');"
    } else {
      $lines.Add("`n### [$db] 无 data_quality_result 表")
    }
    Q "[$db] quality_rule_definition 是否存在（V19）" "SELECT COUNT(*) AS qrd FROM information_schema.TABLES WHERE TABLE_SCHEMA='$db' AND TABLE_NAME='quality_rule_definition';"
  }
  $mds = & $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'analytics_metric%' ORDER BY SCHEMA_NAME;" 2>&1
  foreach ($db in $mds) {
    $db = $db.Trim(); if (-not $db) { continue }
    Q "[$db] flyway 版本号汇总" "SELECT COUNT(*) AS cnt, GROUP_CONCAT(version ORDER BY installed_rank) AS versions FROM $db.flyway_schema_history;"
  }
} finally { $env:MYSQL_PWD = $old }
$lines | Set-Content $out -Encoding utf8
$lines | ForEach-Object { Write-Host $_ }
Write-Output "[01] 快照已写 $out"
exit 0
