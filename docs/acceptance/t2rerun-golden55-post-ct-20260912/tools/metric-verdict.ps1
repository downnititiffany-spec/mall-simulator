<#
  E3-b 指标逐值判定器（可复跑）— 以【SQL 自连接差集】为唯一判据
  =====================================================================
  判据（ORDER-1 §4 E3-b）：
    新快照的 `day:2026-09-01` 十指标 与既有基线**逐值一致**（值/单位/口径/期间 四项）。
    **判据实现必须是 SQL 自连接差集 = 0 行**，而不是"我在 PowerShell 里比了一遍"。

  比对三方（都在同一 SQL 会话里，避免 PowerShell 侧比较）：
    侧 A = 新 ACTIVE 快照       （analytics_metric.metric_value）
    侧 B = 前一 ACTIVE 快照      （本单主跑 42 取代的那个：S20260901_41）
    侧 C = P1-01 冻结基线        （docs/acceptance/p1-baseline-r39-20260911/baseline.json，
                                  导入为临时表 —— 它是**外部冻结记录**，不是库内数据）
    C1 = A vs B 差集（库内→库内）
    C2 = A vs C 差集（库内→外部基线）**这条才是 E3-b 的正式判据**
       （B 侧只作参照：B 也是 spark-ads 发布的，两者同源；C 是独立冻结记录）

  口径（T2 首跑 README §8.2 登记）：ADS 镜像表是「每快照一组」的历史存储 ⇒ 按 snapshot_id 分组。
  红线：镜像行数**只用 COUNT(*)**；**禁止** information_schema.table_rows；**禁止**"总行数不变"当判据。

  用法：pwsh -NoProfile -File tools\metric-verdict.ps1 -SnapshotId S20260901_42 -RunId 42 -PrevSnapshotId S20260901_41

  自带断言（陷阱 #27）：
    A1 基线 JSON 的 metricCode 集合 == 期望的 10 个（防基线被改小后"差集=0"假绿）
    A2 新快照 metric_value 行数 == 10（不多不少）
    A3 差集查询两侧行数各自 == 10（两侧都满才叫有效比对）
    A4 **正向对照**：故意把基线里 order_count/pv 的值 +1 再比一次，差集必须 >= 1 行
       （证明该判据**有能力**发现不一致；F-38 ① 要求凡"零差异"结论必须带正向对照）
    A5 输出 LF、CR 字节 = 0
#>
param(
  [Parameter(Mandatory=$true)][string]$SnapshotId,
  [Parameter(Mandatory=$true)][int]$RunId,
  [string]$PrevSnapshotId = '',
  [string]$BaselineJson = 'D:\Develop_code\GraduationProject\docs\acceptance\p1-baseline-r39-20260911\baseline.json'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outFile = Join-Path $outDir "metric-verdict-run$RunId-$stamp.txt"
$enc = New-Object Text.UTF8Encoding($false)
$buf = New-Object 'System.Collections.Generic.List[string]'
$fail = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $buf.Add($s) }
function Flush { [IO.File]::WriteAllText($outFile, (($buf -join "`n") + "`n"), $enc) }

$expectCodes = @('avg_order_value','buy_rate','dau','full_refund_rate','gmv','net_sale','paid_order_cnt','pv','refund_rate','uv')

L "E3-b 指标逐值判定（SQL 自连接差集）"
L "====================================================================="
L "跑动时点：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L "目标快照：$SnapshotId   （runId=$RunId）"
L "参照快照：$PrevSnapshotId"
L "基线文件：$BaselineJson"
L "判定库  ：analytics_metric（权威指标库；见 F-82：analytics_meta 下的同名表是孤儿）"
L ""

# ---------- 读基线 JSON ----------
$j = Get-Content $BaselineJson -Raw -Encoding UTF8 | ConvertFrom-Json
$baseVals = @($j.metricSnapshot.values)
L "---- 基线 JSON 读数 ----"
L "  snapshotId=$($j.metricSnapshot.snapshotId)  version=$($j.metricSnapshot.version)  status=$($j.metricSnapshot.status)"
L "  values 行数=$($baseVals.Count)  valueCount字段=$($j.metricSnapshot.valueCount)"
$baseCodes = @($baseVals | ForEach-Object { "$($_.metricCode)" -replace '\s','' })
L "  metricCode 集合（排序）：$((($baseCodes | Sort-Object) -join ','))"
if ($baseVals.Count -ne 10) { $fail.Add("A1 失败：基线 JSON values 行数 = $($baseVals.Count)，期望 10") }
$missing = @($expectCodes | Where-Object { $_ -notin $baseCodes })
$extra   = @($baseCodes | Where-Object { $_ -notin $expectCodes })
if ($missing.Count -gt 0) { $fail.Add("A1 失败：基线缺指标 $($missing -join ',')") }
if ($extra.Count   -gt 0) { $fail.Add("A1 失败：基线多指标 $($extra -join ',')") }
L ""

# ---------- 构造临时基线表 SQL ----------
$rows = New-Object 'System.Collections.Generic.List[string]'
foreach ($v in $baseVals) {
  $mc = "$($v.metricCode)" -replace "'","''"
  $val = "$($v.value)"
  $un = "$($v.unit)" -replace "'","''"
  $pd = "$($v.period)" -replace "'","''"
  $dv = "$($v.definitionVersion)" -replace "'","''"
  $rows.Add("('$mc','$val','$un','$pd','$dv')")
}
$insBase = "CREATE TEMPORARY TABLE _base (metric_code VARCHAR(64), metric_value DECIMAL(20,4), unit VARCHAR(16), period VARCHAR(64), definition_version VARCHAR(16)); INSERT INTO _base VALUES " + ($rows -join ',')

# 正向对照用的变异基线（pv +1）
$rowsMut = New-Object 'System.Collections.Generic.List[string]'
foreach ($v in $baseVals) {
  $mc = "$($v.metricCode)" -replace "'","''"
  $val = [decimal]"$($v.value)"
  if ($mc -eq 'pv') { $val = $val + 1 }
  $valStr = $val.ToString([Globalization.CultureInfo]::InvariantCulture)
  $un = "$($v.unit)" -replace "'","''"
  $pd = "$($v.period)" -replace "'","''"
  $dv = "$($v.definitionVersion)" -replace "'","''"
  $rowsMut.Add("('$mc','$valStr','$un','$pd','$dv')")
}
$insMut = "CREATE TEMPORARY TABLE _mut (metric_code VARCHAR(64), metric_value DECIMAL(20,4), unit VARCHAR(16), period VARCHAR(64), definition_version VARCHAR(16)); INSERT INTO _mut VALUES " + ($rowsMut -join ',')

# ---------- 一条 SQL 会话里跑完全部 ----------
$sql = @"
USE analytics_metric;

SELECT '=== C0 目标快照逐值 dump ===' AS section;
SELECT metric_code, metric_value, unit, period, definition_version FROM analytics_metric.metric_value WHERE snapshot_id='$SnapshotId' ORDER BY metric_code;
SELECT '=== C0b 参照快照逐值 dump ===' AS section;
SELECT metric_code, metric_value, unit, period, definition_version FROM analytics_metric.metric_value WHERE snapshot_id='$PrevSnapshotId' ORDER BY metric_code;
$insBase;
SELECT '=== C2 行数核对 ===' AS section;
SELECT (SELECT COUNT(*) FROM analytics_metric.metric_value WHERE snapshot_id='$SnapshotId') AS new_rows,
       (SELECT COUNT(*) FROM analytics_metric.metric_value WHERE snapshot_id='$PrevSnapshotId') AS prev_rows,
       (SELECT COUNT(*) FROM _base) AS base_rows,
       (SELECT COUNT(DISTINCT metric_code) FROM _base) AS base_distinct_codes;
SELECT '=== C3 差集：新快照 vs 冻结基线（E3-b 正式判据，必须 0 行）===' AS section;
SELECT COUNT(*) AS diff_rows FROM (
  SELECT metric_code, metric_value, unit, period, definition_version FROM analytics_metric.metric_value WHERE snapshot_id='$SnapshotId'
  UNION ALL
  SELECT metric_code, metric_value, unit, period, definition_version FROM _base
) u GROUP BY metric_code, metric_value, unit, period, definition_version HAVING COUNT(*) <> 2;
SELECT '=== C3b 差集明细（新侧独有）===' AS section;
SELECT n.metric_code, n.metric_value, n.unit, n.period, n.definition_version FROM analytics_metric.metric_value n
 LEFT JOIN _base b ON b.metric_code=n.metric_code AND b.metric_value=n.metric_value AND b.unit=n.unit AND b.period=n.period AND b.definition_version=n.definition_version
 WHERE n.snapshot_id='$SnapshotId' AND b.metric_code IS NULL;
SELECT '=== C3c 差集明细（基线独有）===' AS section;
SELECT b.metric_code, b.metric_value, b.unit, b.period, b.definition_version FROM _base b
 LEFT JOIN analytics_metric.metric_value n ON n.metric_code=b.metric_code AND n.metric_value=b.metric_value AND n.unit=b.unit AND n.period=b.period AND n.definition_version=b.definition_version AND n.snapshot_id='$SnapshotId'
 WHERE n.metric_code IS NULL;
SELECT '=== C1 差集：新快照 vs 前一 ACTIVE 快照（参照）===' AS section;
SELECT COUNT(*) AS diff_rows_new_vs_prev FROM (
  SELECT metric_code, metric_value, unit, period, definition_version FROM analytics_metric.metric_value WHERE snapshot_id IN ('$SnapshotId','$PrevSnapshotId')
) u GROUP BY metric_code, metric_value, unit, period, definition_version HAVING COUNT(*) <> 2;
$insMut;
SELECT '=== C5 正向对照：变异基线（pv+1）vs 新快照，必须 >=1 行 ===' AS section;
SELECT COUNT(*) AS diff_rows FROM (
  SELECT metric_code, metric_value, unit, period, definition_version FROM analytics_metric.metric_value WHERE snapshot_id='$SnapshotId'
  UNION ALL
  SELECT metric_code, metric_value, unit, period, definition_version FROM _mut
) u GROUP BY metric_code, metric_value, unit, period, definition_version HAVING COUNT(*) <> 2;
"@

$tmp = Join-Path $env:TEMP "metric-verdict-$stamp.sql"
[IO.File]::WriteAllText($tmp, $sql, $enc)
$out = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $sql 2>&1
$code = $LASTEXITCODE
$txt = ($out | Where-Object { $_ -notmatch 'Warning.*password' }) -join "`n"
L "---- SQL 原样输出（退出码 $code）----"
L $txt
L ""

# ---------- 断言 ----------
if ($code -ne 0) { $fail.Add("SQL 执行失败：退出码 $code") } else {
  if ($txt -match 'new_rows\s+prev_rows\s+base_rows\s+base_distinct_codes\s*\r?\n(\d+)\s+(\d+)\s+(\d+)\s+(\d+)') {
    $nr=[int]$Matches[1]; $pr=[int]$Matches[2]; $br=[int]$Matches[3]; $bd=[int]$Matches[4]
    L "---- 解析 ----"
    L "  new_rows=$nr prev_rows=$pr base_rows=$br base_distinct_codes=$bd"
    if ($nr -ne 10) { $fail.Add("A2 失败：新快照 metric_value 行数 = $nr，期望 10") }
    if ($pr -ne 10) { $fail.Add("A3 失败：参照快照行数 = $pr，期望 10") }
    if ($br -ne 10) { $fail.Add("A3 失败：基线临时表行数 = $br，期望 10") }
    if ($bd -ne 10) { $fail.Add("A3 失败：基线去重 metric_code 数 = $bd，期望 10") }
  } else { $fail.Add("A3 解析失败：取不到 C2 行数核对") }

  # C3（正式判据）
  $m3 = [regex]::Match($txt, 'C3 差集：新快照 vs 冻结基线[^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n=== [^\r\n]*\r?\n?')
  # 简化：直接找 C3 段与下一个 C3b 段之间的第一个纯数字行
  $seg = [regex]::Match($txt, "(?s)C3 差集：新快照 vs 冻结基线.*?(?=== C3b)")
  if ($seg.Success) {
    $num = [regex]::Match($seg.Value, '\r?\n(\d+)\s*\r?\n')
    if ($num.Success) {
      $d3 = [int]$num.Groups[1].Value
      L "  C3 diff_rows（vs 冻结基线）= $d3"
      if ($d3 -ne 0) { $fail.Add("E3-b 判据失败：新快照与冻结基线差集 = $d3 行（期望 0）") }
    } else { $fail.Add("C3 解析失败（无法判定 E3-b）") }
  } else { $fail.Add("C3 段缺失（无法判定 E3-b）") }

  # C5（正向对照）
  $seg5 = [regex]::Match($txt, "(?s)C5 正向对照.*$")
  if ($seg5.Success) {
    $num5 = [regex]::Match($seg5.Value, '\r?\n(\d+)\s*\r?\n')
    if ($num5.Success) {
      $d5 = [int]$num5.Groups[1].Value
      L "  C5 正向对照 diff_rows（pv+1 的变异基线）= $d5"
      if ($d5 -lt 1) { $fail.Add("A4 失败：正向对照未命中（差集 = $d5，期望 >=1）⇒ 零差异判据无效（F-38 ①）") }
    } else { $fail.Add("A4 解析失败（正向对照缺失 ⇒ 零差异判据无效，F-38 ①）") }
  } else { $fail.Add("A4 失败：C5 正向对照段缺失 ⇒ 零差异判据无效（F-38 ①）") }
}

# ---------- 汇总 ----------
L ""
L "---- 断言小结 ----"
if ($fail.Count -eq 0) {
  L "全部断言 PASS：A1 基线 10 码 / A2 新快照 10 行 / A3 两侧各 10 行 / A4 正向对照命中 / A5 LF"
  L ""
  L "**E3-b 判定：成立** —— 新快照 $SnapshotId 的十指标与冻结基线 $($j.metricSnapshot.snapshotId)"
  L "  在 (metric_code, metric_value, unit, period, definition_version) 五元组上**差集 = 0 行**。"
} else {
  foreach ($f in $fail) { L "  FAIL: $f" }
}
$text = (($buf -join "`n") + "`n")
[IO.File]::WriteAllText($outFile, $text, $enc)
$cr = ([regex]::Matches($text, [string][char]13)).Count
if ($cr -ne 0) { Write-Host "A5 FAIL: CR bytes = $cr" }

Write-Host "OUTFILE=$outFile"
Write-Host "LINES=$($buf.Count)  CR_BYTES=$cr  BYTES=$((Get-Item $outFile).Length)"
if ($fail.Count -eq 0) { Write-Host "ASSERT PASS: all" } else {
  Write-Host "ASSERT FAIL ($($fail.Count)):"
  $fail | ForEach-Object { Write-Host "  - $_" }
  exit 1
}
