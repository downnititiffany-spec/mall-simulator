# V25-E3 补充零写入证据：宿主 3306 「取证窗口内无任何新行」存在性检查（只读）
#
# 为什么需要它：fingerprint-3306.sql 只对 analytics_metric 的两张表做全表内容指纹，
# 而 analytics_meta 侧的 pipeline_run / spark_job_run / ingestion_batch 等才是应用真正会写的表。
# 该文件在取证窗口**开始前**没有留 baseline，无法做 before/after 比对，
# 因此改用**不需要 baseline 的判据**：这些表的每一列时间戳都不得落在取证窗口内，
# 且不得出现本 run 的标识（v25it-* / S20260901_2 / runId 2 等）。
# 全部语句均为 SELECT；脚本对任何非 SELECT 语句直接 throw（防呆，不依赖自觉）。
param(
  [string]$WindowStart = '2026-09-14 13:50:00',
  [string]$WindowEnd   = '2026-09-14 14:20:00',
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$raw = Join-Path $EvidenceRoot 'raw'
$out = Join-Path $raw '35-3306-window-absence.txt'
$sqlFile = Join-Path $raw '35-3306-window-absence.sql'

$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
function My([string]$sql) {
  if ($sql.TrimStart() -notmatch '^(?i)SET\b|^(?i)SELECT\b') { throw "拒绝执行非只读语句: $sql" }
  return (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names -e $sql 2>&1)
}
# source 是 mysql 客户端指令而非 SQL，单独走这条：调用前已对文件内每条语句逐条做只读校验
function MyFile([string]$file) {
  return (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names -e "source $file" 2>&1)
}
function AssertPortIs3306() {
  $r = My "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@hostname);"
  if ("$r".Trim() -notmatch '^3306\|') { throw "端口断言失败，拒绝继续：$r" }
  return "$r".Trim()
}

$L = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $L.Add($s); Write-Host $s }

try {
  $fp = AssertPortIs3306
  A "V25-E3 宿主 3306「窗口内无新行」存在性检查（只读）  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
  A "实例指纹 @@port|@@server_uuid|@@hostname = $fp"
  A "取证窗口 = [$WindowStart , $WindowEnd]   （应用进程 8091 的运行区间 13:59:37–14:15:09 内含于其中）"
  A "判据：下表列出的每一张「应用会写的表」中，任何时间戳列都不得落在窗口内；且不得出现本 run 标识。"
  A ""

  # ── 1) 发现两库中所有带时间戳列的表 ─────────────────────────────
  $q = "SELECT CONCAT(TABLE_SCHEMA,'.',TABLE_NAME,'|',COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND DATA_TYPE IN ('datetime','timestamp') ORDER BY TABLE_SCHEMA,TABLE_NAME,ORDINAL_POSITION;"
  $cols = @(My $q | Where-Object { $_ -match '\|' })
  $tables = @{}
  foreach ($c in $cols) {
    $p = "$c".Trim().Split('|')
    if ($p.Count -ne 2) { continue }
    $t = $p[0]; $col = $p[1]
    if (-not $tables.ContainsKey($t)) { $tables[$t] = New-Object System.Collections.Generic.List[string] }
    $tables[$t].Add($col)
  }
  A ("### 1) 发现带时间戳列的表 {0} 张，生成判据语句" -f $tables.Count)

  # ── 2) 动态生成一条只读脚本 ─────────────────────────────────────
  $sql = New-Object System.Collections.Generic.List[string]
  $sql.Add("SELECT 'zz_instance' AS k, '$fp' AS v;")
  $win = "BETWEEN '$WindowStart' AND '$WindowEnd'"
  foreach ($t in ($tables.Keys | Sort-Object)) {
    $sql.Add("SELECT '$t.rows_total' AS k, COUNT(*) AS v FROM $t;")
    $hasId = @(My "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$($t.Split('.')[0])' AND TABLE_NAME='$($t.Split('.')[1])' AND COLUMN_NAME='id';")
    if ([int]"$($hasId[0])".Trim() -eq 1) { $sql.Add("SELECT '$t.max_id' AS k, MAX(id) AS v FROM $t;") }
    foreach ($col in $tables[$t]) {
      $sql.Add("SELECT '$t.max_$col' AS k, MAX($col) AS v FROM $t;")
      $sql.Add("SELECT '$t.inwin_$col' AS k, COUNT(*) AS v FROM $t WHERE $col $win;")
    }
  }
  # 本 run 标识不得出现在 3306
  $sql.Add("SELECT 'run2_source_data_version_rows_3306' AS k, COUNT(*) AS v FROM analytics_meta.pipeline_run WHERE source_data_version LIKE 'v25it-%';")
  $sql.Add("SELECT 'run2_target_snapshot_rows_3306' AS k, COUNT(*) AS v FROM analytics_meta.pipeline_run WHERE target_snapshot_id = 'S20260901_2';")
  $sql.Add("SELECT 'batch_ings_20260914_rows_3306' AS k, COUNT(*) AS v FROM analytics_meta.ingestion_batch WHERE batch_no LIKE 'ing-20260914%';")
  $sql.Add("SELECT 'v25it_users_3306' AS k, COUNT(*) AS v FROM mysql.user WHERE user LIKE 'v25it%';")
  $sql.Add("SELECT 'metric_snapshot_s2_rows_3306' AS k, COUNT(*) AS v FROM analytics_metric.metric_snapshot WHERE snapshot_id = 'S20260901_2';")
  $sql.Add("SELECT 'meta_flyway_count_3306' AS k, COUNT(*) AS v FROM analytics_meta.flyway_schema_history;")
  $sql.Add("SELECT 'meta_flyway_max_installed_on_3306' AS k, MAX(installed_on) AS v FROM analytics_meta.flyway_schema_history;")
  foreach ($s in $sql) {
    if ($s.TrimStart() -notmatch '^(?i)SELECT\b') { throw "生成的脚本含非只读语句，拒绝执行: $s" }
  }
  $sql -join "`r`n" | Set-Content -Path $sqlFile -Encoding UTF8
  A ("  生成的只读脚本: {0}（{1} 条语句，已逐条校验全部为 SELECT）" -f (Split-Path $sqlFile -Leaf), $sql.Count)

  # ── 3) 执行并解析 ───────────────────────────────────────────────
  $rawLines = @(MyFile $sqlFile)
  $map = [ordered]@{}
  foreach ($line in $rawLines) {
    if ("$line" -match '^([A-Za-z0-9_.]+)\t(.*)$') { $map[$Matches[1]] = $Matches[2].Trim() }
  }

  A ""
  A "### 2) 逐表判据（k = 表.指标）"
  A ("{0,-56} {1}" -f 'KEY', 'VALUE')
  $viol = @()
  foreach ($k in $map.Keys) {
    $v = [string]$map[$k]
    $flag = ''
    if ($k -match '^(.+)\.inwin_(.+)$') {
      if ($v -ne '0') { $flag = '  <== **窗口内有新行**'; $viol += "$k=$v" }
    }
    if ($k -match '^(.+)\.max_(.+)$' -and $k -match 'max_(created_at|updated_at|start_time|end_time|installed_on|published_at|data_updated_at|login_time|created|ts)$') {
      if ($v -and $v -ne 'NULL' -and ([datetime]$v) -ge [datetime]$WindowStart) { $flag = '  <== **最大值落在窗口内**'; $viol += "$k=$v" }
    }
    if ($k -in @('run2_source_data_version_rows_3306', 'run2_target_snapshot_rows_3306',
                 'batch_ings_20260914_rows_3306', 'v25it_users_3306',
                 'metric_snapshot_s2_rows_3306', 'metric_snapshot_rows', 'metric_value_rows')) {
      if ($v -ne '0' -and $k -ne 'metric_snapshot_rows' -and $k -ne 'metric_value_rows') { $flag = '  <== **本 run 标识出现在 3306**'; $viol += "$k=$v" }
    }
    A ("{0,-56} {1}{2}" -f $k, $v, $flag)
  }

  A ""
  A "### 3) 结论"
  if ($viol.Count -eq 0) {
    A "  PASS：analytics_meta / analytics_metric 两库中全部带时间戳列的表，其在窗口 [$WindowStart,$WindowEnd] 内的行数均为 0；"
    A "        且 3306 上不存在本 run 的任何标识（source_data_version/v25it 前缀 0 行、target_snapshot_id=S20260901_2 0 行、"
    A "        2026-09-14 的 ingestion batch 0 行、v25it 账号 0 个）。"
    A "        ⇒ 与 fingerprint-3306.sql 的 before/after 逐字节一致结论互为独立佐证：本轮对宿主 3306 零写入。"
  } else {
    A "  **FAIL**：发现 $($viol.Count) 项判据被打破，必须逐条排查："
    $viol | ForEach-Object { A "    - $_" }
  }
  A ""
  A "### 4) 局限（不夸大）"
  A "  · 本检查证明的是「窗口内没有新行 / 没有本 run 标识」；它**不能**证明窗口外的历史情况，"
  A "    也不覆盖 3306 上与本项目无关的其它库。"
  A "  · analytics_meta 侧没有「窗口前」的全表内容指纹 baseline（只有本文件的行数/最大时间戳），"
  A "    因此若攻击者以「改旧行且保持计数不变」的方式写入，本检查无法发现 —— 但本轮不存在此类写入方"
  A "    （应用是唯一写入者，且其链路日志显示它只连 3307，见 raw/30-app-connections-during-window.txt）。"
} finally {
  $env:MYSQL_PWD = $old
  $L -join "`r`n" | Set-Content -Path $out -Encoding UTF8
  Write-Host "`n证据已写 $out"
}
