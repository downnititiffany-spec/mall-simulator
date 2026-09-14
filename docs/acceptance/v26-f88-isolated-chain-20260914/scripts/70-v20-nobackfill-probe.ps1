<#
V26-F88 | S5b V20「不回填」专用探测（**仅在 3307**，不改 E3 泳道证据库）

为什么需要它：
  S5-A6 要求「本次 run 之前的老行四列应全为 NULL」。但本泳道的隔离 meta 库是本次新建、
  Flyway 一次性 V1→V20 跑完，库里**根本不存在**「V20 之前产出的行」⇒ A6 在该库上无样本，
  只能如实记为 不适用，不能凭空造结论。

做法（真 DDL，而非「等价推理」）：
  1) 3307 上新建 probe 库；
  2) CREATE TABLE ... LIKE <E3历史库>.data_quality_result  —— 该表仍是 V20 之前的 14 列形态；
  3) 把 E3 历史库里的 **26 行真实历史行** INSERT ... SELECT 过来（这些行产出于 V20 之前）；
  4) 对 probe 库**逐字节执行仓库里的 V20 迁移文件本体**（记录 sha256），不做任何改写；
  5) 断言：行数仍 26、列数 14→18、新增 4 列在全部 26 行上全为 NULL、且无 DEFAULT。
  ⇒ 这才直接证明了「V20 不回填既有行」这条约束在真实 DDL 下成立。

不影响主链路结论：probe 库与本次 run 的 analytics_meta_v25f88_20260914_1624 完全隔离。
#>
param(
  [string]$ProbeDb = '',
  [string]$LegacyDb = 'analytics_meta_v25it_20260914_1358_l4e3',
  [string]$V20File = 'D:\Develop_code\GraduationProject\analytics-server\platform-app\src\main\resources\db\meta\V20__data_quality_result_rule_version.sql',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutName = '70-v20-nobackfill-probe.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$ExpectUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [int]$Port = 3307
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($Port -ne 3307) { throw "本脚本只允许 3307（当前 $Port）" }
if (-not $ProbeDb) { $ProbeDb = 'analytics_meta_f88v20probe_' + (Get-Date).ToString('yyyyMMdd_HHmm') }
if ($ProbeDb -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') { throw "probe 库名不合法: $ProbeDb" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$v20Sha = (Get-FileHash $V20File -Algorithm SHA256).Hash

$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }
function Sql([string]$sql) {
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
  try { return (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e $sql 2>&1) }
  finally { $env:MYSQL_PWD = $old }
}
function SqlT([string]$sql) {
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
  try { return (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -t -e $sql 2>&1) }
  finally { $env:MYSQL_PWD = $old }
}
function Q([string]$title, [string]$sql) {
  Emit "`n### $title"; Emit "SQL: $sql"
  SqlT $sql | ForEach-Object { Emit $_.ToString() }
}

$id = (Sql "SELECT CONCAT(@@port,'|',@@server_uuid);") | Out-String
$id = $id.Trim()
if ($id -notmatch "^3307\|$ExpectUuid$") { throw "目标不是隔离实例，拒绝：$id" }

Emit "V26-F88 S5b V20 不回填专用探测  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "目标实例(仅 3307) = $id"
Emit "probe 库          = $ProbeDb"
Emit "历史行来源库       = $LegacyDb"
Emit "V20 迁移文件       = $V20File"
Emit "V20 sha256        = $v20Sha"

$legacyRows = [int]((Sql "SELECT COUNT(*) FROM $LegacyDb.data_quality_result;") | Out-String).Trim()
Emit "历史行数（预期 26）= $legacyRows"

# ── 1) 建 probe 库 + 复制 V20 之前的表结构与历史行 ────────────────────
Emit "`n===== 1) 建 probe 库并复制历史行（写 3307）====="
Sql "DROP DATABASE IF EXISTS $ProbeDb;" | Out-Null
Sql "CREATE DATABASE $ProbeDb DEFAULT CHARACTER SET utf8mb4;" | Out-Null
Sql "CREATE TABLE $ProbeDb.data_quality_result LIKE $LegacyDb.data_quality_result;" | Out-Null
Sql "INSERT INTO $ProbeDb.data_quality_result SELECT * FROM $LegacyDb.data_quality_result;" | Out-Null
Q 'P1 probe 库列数（V20 之前，预期 14）' `
  "SELECT COUNT(*) AS cols_before_v20 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result';"
Q 'P2 probe 库行数（预期 = 历史行数）' `
  "SELECT COUNT(*) AS rows_in_probe FROM $ProbeDb.data_quality_result;"
$colsBefore = [int]((Sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result';") | Out-String).Trim()
$rowsInProbe = [int]((Sql "SELECT COUNT(*) FROM $ProbeDb.data_quality_result;") | Out-String).Trim()
Q 'P3 历史行样本（含 severity 为 NULL 的行，证明是真实历史数据）' `
  "SELECT COUNT(*) AS total, IFNULL(SUM(severity IS NULL),0) AS severity_null, MIN(created_at) AS first_at, MAX(created_at) AS last_at FROM $ProbeDb.data_quality_result;"

# ── 2) 逐字节执行 V20 本体 ───────────────────────────────────────────
Emit "`n===== 2) 对 probe 库执行 V20 迁移文件本体（sha256 同上，未做任何改写）====="
$v20fwd = ($V20File -replace '\\', '/')
Emit "命令: mysql --host=127.0.0.1 --port=3307 --user=root --database=$ProbeDb -e `"source $v20fwd`""
$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
try {
  $apply = (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root "--database=$ProbeDb" -e "source $v20fwd" 2>&1) | Out-String
} finally { $env:MYSQL_PWD = $old }
Emit "[V20 执行输出] exit=$LASTEXITCODE`n$apply"

# ── 3) 断言 ─────────────────────────────────────────────────────────
Emit "`n===== 3) 断言 ====="
Q 'P4 probe 库列数（V20 之后，预期 18）+ 四列明细' `
  "SELECT COUNT(*) AS cols_after_v20 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result';"
Q 'P5 新增四列的定义（IS_NULLABLE 必须 YES，COLUMN_DEFAULT 必须 NULL）' `
  "SELECT ORDINAL_POSITION,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result' AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint') ORDER BY ORDINAL_POSITION;"
$colsAfter = [int]((Sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result';") | Out-String).Trim()
$rowsAfter = [int]((Sql "SELECT COUNT(*) FROM $ProbeDb.data_quality_result;") | Out-String).Trim()
$nn = [int]((Sql "SELECT IFNULL(SUM(rule_version IS NOT NULL OR effective_severity IS NOT NULL OR compat_policy_version IS NOT NULL OR rule_fingerprint IS NOT NULL),0) FROM $ProbeDb.data_quality_result;") | Out-String).Trim()
$defs = [int]((Sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$ProbeDb' AND TABLE_NAME='data_quality_result' AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint') AND COLUMN_DEFAULT IS NOT NULL;") | Out-String).Trim()
$nnRows = [int]((Sql "SELECT COUNT(*) FROM $ProbeDb.data_quality_result WHERE rule_version IS NOT NULL OR effective_severity IS NOT NULL OR compat_policy_version IS NOT NULL OR rule_fingerprint IS NOT NULL;") | Out-String).Trim()
Q 'P6 四列非空行数（必须 0）与总行数' `
  "SELECT COUNT(*) AS total_rows, IFNULL(SUM(rule_version IS NOT NULL),0) AS rv_nn, IFNULL(SUM(effective_severity IS NOT NULL),0) AS eff_nn, IFNULL(SUM(compat_policy_version IS NOT NULL),0) AS cp_nn, IFNULL(SUM(rule_fingerprint IS NOT NULL),0) AS fp_nn FROM $ProbeDb.data_quality_result;"
Q 'P7 全量 26 行四列原值（应为整列 NULL，逐行可核）' `
  "SELECT id,run_id,rule_code,severity,rule_version,effective_severity,compat_policy_version,rule_fingerprint FROM $ProbeDb.data_quality_result ORDER BY id;"

Emit ""
Emit ("[断言] P4 列数 14 -> 18 : actual={0} expected=18 => {1}" -f $colsAfter, $(if ($colsAfter -eq 18) { 'PASS' } else { 'FAIL' }))
Emit ("[断言] P6 行数不变        : actual={0} expected={1} => {2}" -f $rowsAfter, $legacyRows, $(if ($rowsAfter -eq $legacyRows) { 'PASS' } else { 'FAIL' }))
Emit ("[断言] P6b 四列非空行数=0 : actual={0} (行级 {1}) expected=0 => {2}" -f $nn, $nnRows, $(if ($nn -eq 0 -and $nnRows -eq 0) { 'PASS' } else { 'FAIL' }))
Emit ("[断言] P5 四列无 DEFAULT   : actual={0} expected=0 => {1}" -f $defs, $(if ($defs -eq 0) { 'PASS' } else { 'FAIL' }))
Emit ""
Emit "[遗留物] probe 库 $ProbeDb 保留作为可复核证据（3307 隔离实例内）；人工清理命令："
Emit "  mysql -h127.0.0.1 -P3307 -uroot -e 'DROP DATABASE IF EXISTS $ProbeDb;'"

$lines | Set-Content $out -Encoding utf8
Write-Output "[70] 证据已写 $out"
if ($colsAfter -eq 18 -and $rowsAfter -eq $legacyRows -and $nn -eq 0 -and $defs -eq 0) { exit 0 } else { exit 6 }
