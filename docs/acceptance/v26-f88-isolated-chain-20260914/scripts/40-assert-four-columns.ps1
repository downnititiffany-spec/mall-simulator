<#
V26-F88 | S5 核心产出：data_quality_result 四列「真的落库」六条断言（3307 只读 SELECT）

断言（编号与任务书 S5 一一对应）：
  A1 本次 run 的 data_quality_result 行数 + 逐行明细
     (rule_code, severity, effective_severity, rule_version, compat_policy_version, LEFT(rule_fingerprint,8))
  A2 SUM(rule_fingerprint = '<期望常量>') = 行数；不等则逐行列出异常行
  A3 SUM(compat_policy_version = 'compat-v1') = 行数；不等则逐行列出异常行
  A4 SUM(rule_version IS NOT NULL) = 行数；
     未登记行判据（总控裁决①）：severity IS NULL AND rule_version IS NULL —— 按此逐条列出
  A5 SUM(severity <> effective_severity)：>0 逐行展示原值；=0 则明写「本次链路未产生两列不同的行」
  A6 本次 run 之前的老行四列应全为 NULL（不回填约束）
     + 旁证：本实例其它库（历史 runId 库）同表连这 4 个列都不存在（比 NULL 更强）

硬约束：仅 3307；仅 SELECT；端口+uuid 前置核对。
用法：pwsh -NoProfile -File <this> -RunId <id> -PipelineRunId <n> -ExpectedFingerprint <hex>
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [Parameter(Mandatory = $true)][long]$PipelineRunId,
  [string]$ExpectedFingerprint = '6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6',
  [string]$ExpectedCompatPolicy = 'compat-v1',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutName = '40-four-column-assertions.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$ExpectUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [int]$Port = 3307
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($Port -ne 3307) { throw "本脚本只允许 3307（当前 $Port）" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$token = $RunId -replace '-', '_'
$db = "analytics_meta_$token"

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
function Q([string]$title, [string]$sql, [string]$note = '') {
  Emit "`n### $title"
  if ($note) { Emit "# $note" }
  Emit "SQL: $sql"
  SqlT $sql | ForEach-Object { Emit $_.ToString() }
}
function Assert([string]$name, [string]$actual, [string]$expected, [string]$verdict) {
  Emit ("`n[断言] {0}: actual={1} expected={2} => {3}" -f $name, $actual, $expected, $verdict)
}

# ── 前置：实例指纹核对（只写目标）────────────────────────────────────
$id = (Sql "SELECT CONCAT(@@port,'|',@@server_uuid);") | Out-String
$id = $id.Trim()
if ($id -notmatch "^3307\|$ExpectUuid$") { throw "目标不是隔离实例，拒绝：$id" }

Emit "V26-F88 S5 四列落库六条断言  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "runId=$RunId  metaDb=$db  目标 pipeline_run.id=$PipelineRunId  实例=$id"
Emit "期望常量 rule_fingerprint  = $ExpectedFingerprint"
Emit "期望常量 compat_policy_version = $ExpectedCompatPolicy"

# ── 参照：目标 pipeline_run 行 ───────────────────────────────────────
Q 'S5-0 目标 pipeline_run 行（核对口径：run_id 指向它）' `
  "SELECT id,pipeline_code,status,current_stage,error_code,source_data_version,started_at,finished_at FROM $db.pipeline_run WHERE id=$PipelineRunId;"

# ── A1 ───────────────────────────────────────────────────────────────
Q 'S5-A1 本次 run 的 data_quality_result 逐行明细' `
  "SELECT id,rule_code,layer,severity,effective_severity,rule_version,compat_policy_version,LEFT(rule_fingerprint,8) AS fp8,LENGTH(rule_fingerprint) AS fp_len,passed FROM $db.data_quality_result WHERE run_id=$PipelineRunId ORDER BY id;"
$rowCount = [int]((Sql "SELECT COUNT(*) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
Emit "`n[读数] 本次 run 行数 row_count = $rowCount"

# ── A2 ───────────────────────────────────────────────────────────────
$fpMatch = [int]((Sql "SELECT IFNULL(SUM(rule_fingerprint = '$ExpectedFingerprint'),0) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
$fpNull  = [int]((Sql "SELECT IFNULL(SUM(rule_fingerprint IS NULL),0) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
$fpDistinct = ((Sql "SELECT IFNULL(GROUP_CONCAT(DISTINCT rule_fingerprint),'') FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
Q 'S5-A2 SUM(rule_fingerprint = 期望常量)' `
  "SELECT IFNULL(SUM(rule_fingerprint = '$ExpectedFingerprint'),0) AS fp_match_rows, IFNULL(SUM(rule_fingerprint IS NULL),0) AS fp_null_rows, COUNT(*) AS total_rows FROM $db.data_quality_result WHERE run_id=$PipelineRunId;"
Emit "[读数] 库内 distinct rule_fingerprint = $fpDistinct"
if ($fpMatch -ne $rowCount) {
  Emit "`n### S5-A2 异常行（rule_fingerprint 不等于期望常量）"
  SqlT "SELECT id,rule_code,rule_fingerprint FROM $db.data_quality_result WHERE run_id=$PipelineRunId AND (rule_fingerprint IS NULL OR rule_fingerprint <> '$ExpectedFingerprint') ORDER BY id;" | ForEach-Object { Emit $_.ToString() }
}
Assert 'A2 fingerprint 命中行数 = 行数' "$fpMatch (NULL=$fpNull)" "$rowCount" $(if ($fpMatch -eq $rowCount) { 'PASS' } else { 'FAIL' })
Assert 'A2b 库内指纹 = 任务书期望常量' $fpDistinct $ExpectedFingerprint $(if ($fpDistinct -eq $ExpectedFingerprint) { 'PASS' } else { 'FAIL' })

# ── A3 ───────────────────────────────────────────────────────────────
$cpMatch = [int]((Sql "SELECT IFNULL(SUM(compat_policy_version = '$ExpectedCompatPolicy'),0) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
$cpDistinct = ((Sql "SELECT IFNULL(GROUP_CONCAT(DISTINCT compat_policy_version),'') FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
Q 'S5-A3 SUM(compat_policy_version = 期望常量)' `
  "SELECT IFNULL(SUM(compat_policy_version = '$ExpectedCompatPolicy'),0) AS cp_match_rows, IFNULL(SUM(compat_policy_version IS NULL),0) AS cp_null_rows, COUNT(*) AS total_rows FROM $db.data_quality_result WHERE run_id=$PipelineRunId;"
Emit "[读数] 库内 distinct compat_policy_version = $cpDistinct"
if ($cpMatch -ne $rowCount) {
  Emit "`n### S5-A3 异常行"
  SqlT "SELECT id,rule_code,compat_policy_version FROM $db.data_quality_result WHERE run_id=$PipelineRunId AND (compat_policy_version IS NULL OR compat_policy_version <> '$ExpectedCompatPolicy') ORDER BY id;" | ForEach-Object { Emit $_.ToString() }
}
Assert 'A3 compat_policy_version 命中行数 = 行数' $cpMatch $rowCount $(if ($cpMatch -eq $rowCount) { 'PASS' } else { 'FAIL' })

# ── A4 ───────────────────────────────────────────────────────────────
$rvNotNull = [int]((Sql "SELECT IFNULL(SUM(rule_version IS NOT NULL),0) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
Q 'S5-A4 SUM(rule_version IS NOT NULL)' `
  "SELECT IFNULL(SUM(rule_version IS NOT NULL),0) AS rv_notnull_rows, IFNULL(SUM(rule_version IS NULL),0) AS rv_null_rows, COUNT(*) AS total_rows FROM $db.data_quality_result WHERE run_id=$PipelineRunId;"
Q 'S5-A4b 未登记行判据（总控裁决①）：severity IS NULL AND rule_version IS NULL' `
  "SELECT id,rule_code,severity,effective_severity,rule_version,compat_policy_version,LEFT(rule_fingerprint,8) AS fp8 FROM $db.data_quality_result WHERE run_id=$PipelineRunId AND severity IS NULL AND rule_version IS NULL ORDER BY id;"
$unreg = [int]((Sql "SELECT COUNT(*) FROM $db.data_quality_result WHERE run_id=$PipelineRunId AND severity IS NULL AND rule_version IS NULL;") | Out-String).Trim()
Emit "[读数] 未登记行数 = $unreg"
Q 'S5-A4c 全部 rule_version 分布' `
  "SELECT rule_version, COUNT(*) AS rows_cnt, GROUP_CONCAT(DISTINCT rule_code ORDER BY rule_code) AS codes FROM $db.data_quality_result WHERE run_id=$PipelineRunId GROUP BY rule_version ORDER BY rule_version;"
Assert 'A4 rule_version 非空行数 = 行数' "$rvNotNull (NULL=$($rowCount-$rvNotNull))" $rowCount $(if ($rvNotNull -eq $rowCount) { 'PASS' } else { "FAIL（未登记行 $unreg 条，按判据应为 severity IS NULL AND rule_version IS NULL）" })

# ── A5 ───────────────────────────────────────────────────────────────
$diff = [int]((Sql "SELECT IFNULL(SUM(severity <> effective_severity),0) FROM $db.data_quality_result WHERE run_id=$PipelineRunId;") | Out-String).Trim()
Q 'S5-A5 SUM(severity <> effective_severity)' `
  "SELECT COUNT(*) AS total_rows, IFNULL(SUM(severity <> effective_severity),0) AS diff_rows, IFNULL(SUM(severity IS NULL),0) AS sev_null_rows, IFNULL(SUM(effective_severity IS NULL),0) AS eff_null_rows FROM $db.data_quality_result WHERE run_id=$PipelineRunId;"
Q 'S5-A5b 声明档位 × 生效档位 交叉分布' `
  "SELECT severity AS declared, effective_severity AS effective, passed, COUNT(*) AS rows_cnt FROM $db.data_quality_result WHERE run_id=$PipelineRunId GROUP BY severity,effective_severity,passed ORDER BY declared,effective;"
if ($diff -gt 0) {
  Q 'S5-A5c 两列不同的行（「声明 WARN 但生效阻断」的真实落库证据，抄原值）' `
    "SELECT id,rule_code,severity AS declared_severity,effective_severity AS effective_severity,rule_version,compat_policy_version,LEFT(rule_fingerprint,8) AS fp8,passed,check_count,error_count,error_rate,threshold,LEFT(detail,220) AS detail_head FROM $db.data_quality_result WHERE run_id=$PipelineRunId AND severity <> effective_severity ORDER BY id;"
  Emit "[读数] severity <> effective_severity 行数 = $diff（>0：已逐行抄原值，见上）"
} else {
  Emit "[读数] severity <> effective_severity 行数 = 0 ⇒ **本次链路未产生两列不同的行**（如实记录，不编造）。"
}
Assert 'A5 两列不同行数' $diff '(报告值；0 与 >0 均合法，但必须与逐行原文一致)' 'INFO'

# ── A6 ───────────────────────────────────────────────────────────────
Q 'S5-A6 本次 run 之前的老行（run_id <> 目标）四列是否全 NULL' `
  "SELECT COUNT(*) AS other_run_rows, IFNULL(SUM(rule_version IS NOT NULL),0) AS rv_notnull, IFNULL(SUM(effective_severity IS NOT NULL),0) AS eff_notnull, IFNULL(SUM(compat_policy_version IS NOT NULL),0) AS cp_notnull, IFNULL(SUM(rule_fingerprint IS NOT NULL),0) AS fp_notnull FROM $db.data_quality_result WHERE run_id <> $PipelineRunId;"
Q 'S5-A6b 本表全部行的 run_id 分布（看是否存在本次之外的行）' `
  "SELECT run_id, COUNT(*) AS rows_cnt, MIN(created_at) AS first_at, MAX(created_at) AS last_at FROM $db.data_quality_result GROUP BY run_id ORDER BY run_id;"
$otherRows = [int]((Sql "SELECT COUNT(*) FROM $db.data_quality_result WHERE run_id <> $PipelineRunId;") | Out-String).Trim()
if ($otherRows -eq 0) {
  Emit "[读数] 本隔离库 data_quality_result 内**没有**本次 run 之外的行（库为本次 Flyway V1→V20 一次性新建，无版本化之前的历史行）⇒ A6 在本实例上『无样本可验』，如实记为 不适用/未取证，见 S5b 专用 probe。"
} else {
  $bad = [int]((Sql "SELECT COUNT(*) FROM $db.data_quality_result WHERE run_id <> $PipelineRunId AND (rule_version IS NOT NULL OR effective_severity IS NOT NULL OR compat_policy_version IS NOT NULL OR rule_fingerprint IS NOT NULL);") | Out-String).Trim()
  Assert 'A6 老行四列全 NULL' "$otherRows 行老行 / 非空四列的行数=$bad" '0' $(if ($bad -eq 0) { 'PASS' } else { 'FAIL' })
}
Q 'S5-A6c 旁证：V20 四列在 analytics_meta / E3 历史库 上各存在几列（预期 0 / 0 ⇒ 连列都没有，比 NULL 更强）' `
  "SELECT SUM(TABLE_SCHEMA='analytics_meta') AS v20_cols_on_analytics_meta, SUM(TABLE_SCHEMA='analytics_meta_v25it_20260914_1358_l4e3') AS v20_cols_on_e3_db, COUNT(*) AS total_matched FROM information_schema.COLUMNS WHERE TABLE_NAME='data_quality_result' AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint');"
Q 'S5-A6d 旁证：本实例历史 run 库 data_quality_result 行数' `
  "SELECT COUNT(*) AS e3_rows FROM analytics_meta_v25it_20260914_1358_l4e3.data_quality_result;"

$lines | Set-Content $out -Encoding utf8
Write-Output "[40] 证据已写 $out"
exit 0
