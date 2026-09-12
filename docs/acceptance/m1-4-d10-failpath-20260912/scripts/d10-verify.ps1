# M1-4 D10 失败路径只读取证：库内逐类账 ↔ 运行报告 ↔ 规范流(独立重算) ↔ 流水四方一致
#
# 用法:
#   pwsh -File docs/acceptance/m1-4-d10-failpath-20260912/scripts/d10-verify.ps1 `
#        -RunId m1-4-d10-failpath-v1-v1-20260912-110139-8803
#
# 判据（任一不过 ⇒ 退出码 1）：
#   ① 运行行是 FAILED（D10 的取证前提：失败运行，不是成功运行）
#   ② generation_event_stat 非空（修复前为空 —— 缺陷本体）
#   ③ 逐类条数与金额：库内 = 报告 = 规范流独立重算；Σ 条数 = success_count = 规范流行数
#   ④ 流水算术：每个操作的 OK 条数 = 对应事件类型的入流条数；FAILED 条数 = 报告里的被拒次数；
#      real_http + local_accounting + SKIPPED = 全部流水行
#   ⑤ 正控：order_created 条数 > 0 且金额 > 0（否则"三方一致"可能只是三方都空）
#   ⑥ 报告 notes 写明失败路径的统计口径（部分真相）
#
# 只读：不写任何库、不发任何商城请求。金额一律按"分"用整数比较，避免浮点尾差。
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$DbUser = 'root',
  [string]$DbPassword = '123456',
  [string]$OutputRoot = 'generator-output'
)
$ErrorActionPreference = 'Stop'
# $PSScriptRoot = <repo>\docs\acceptance\<本目录>\scripts ⇒ 上溯 4 级才是仓库根
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$runDir = Join-Path $root (Join-Path $OutputRoot $RunId)
$script:fail = 0

function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) }
  else { $script:fail++; Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) }
}
function Info([string]$name, [string]$detail) { Write-Host ("  [INFO] {0}  {1}" -f $name, $detail) }
function Sql([string]$q) {
  # 注意：-N --raw 才是不带表头/不转义的机器可读输出（F-18）；绝不吞 stderr
  & $MysqlExe "-u$DbUser" "-p$DbPassword" -N --raw --default-character-set=utf8mb4 -e $q
}
function ToCents($value) {
  if ($null -eq $value -or "$value" -eq '') { return [long]0 }
  return [long][math]::Round([decimal]"$value" * 100)
}
function CompareMaps([string]$label, $a, $b, [string]$sideA, [string]$sideB) {
  $keys = @(@($a.Keys) + @($b.Keys) | Sort-Object -Unique)
  $diff = New-Object System.Collections.ArrayList
  foreach ($k in $keys) {
    $va = if ($a.ContainsKey($k)) { $a[$k] } else { 0 }
    $vb = if ($b.ContainsKey($k)) { $b[$k] } else { 0 }
    if ($va -ne $vb) { [void]$diff.Add("$k($sideA)=$va $sideB=$vb") }
  }
  $detail = if ($diff.Count -eq 0) { "类型数=$($keys.Count)" } else { $diff -join '; ' }
  Check $label ($diff.Count -eq 0) $detail
}

Write-Host "== M1-4 D10 失败路径只读取证 =="
Write-Host "  runId=$RunId"
Write-Host "  运行目录=$runDir"
if (-not (Test-Path $runDir)) { Write-Host "  [FAIL] 运行目录不存在"; exit 1 }

# ---------- ① 运行行 ----------
$row = Sql "SELECT status, success_count, failed_count, IFNULL(error_code,'') FROM generator_meta.generation_run WHERE run_id='$RunId'"
if (-not $row) { Write-Host "  [FAIL] 库内没有这条运行"; exit 1 }
$f = @($row)[0] -split "`t"
$status = $f[0]; $successCount = [long]$f[1]; $failedCount = [long]$f[2]; $errCode = $f[3]
Check '① 运行终态是 FAILED' ($status -eq 'FAILED') "status=$status error_code=$errCode success_count=$successCount failed_count=$failedCount"
Check '①-2 failed_count=0（失败来自商城拒绝，不是写入失败）' ($failedCount -eq 0) "failed_count=$failedCount"

# ---------- ② 库内逐类账本 ----------
$dbCounts = @{}; $dbCents = @{}
foreach ($line in @(Sql "SELECT event_type, event_count, amount FROM generator_meta.generation_event_stat WHERE run_id='$RunId' ORDER BY event_type")) {
  if (-not $line) { continue }
  $p = $line -split "`t"
  $dbCounts[$p[0]] = [long]$p[1]
  $dbCents[$p[0]] = ToCents $p[2]
}
$dbTotal = ($dbCounts.Values | Measure-Object -Sum).Sum
if ($null -eq $dbTotal) { $dbTotal = 0 }
Check '② 库内逐类账本非空（D10 修复前此处为 0 行）' ($dbCounts.Count -gt 0) "类型数=$($dbCounts.Count) 合计条数=$dbTotal"
Check '②-2 库内合计条数 = success_count' ($dbTotal -eq $successCount) "库内=$dbTotal success_count=$successCount"
Info '②-3 库内逐类账' (($dbCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')

# ---------- ③ 规范流独立重算（金额口径来自 payload 字段，不读引擎的账本） ----------
$streamCounts = @{}; $streamCents = @{}
$streamLines = 0
foreach ($line in [System.IO.File]::ReadLines((Join-Path $runDir 'events-0001.jsonl'))) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $e = $line | ConvertFrom-Json
  $t = $e.event_type
  $streamLines++
  $streamCounts[$t] = 1 + [long]($streamCounts[$t])
  $cents = 0
  if ($t -eq 'order_created') { $cents = ToCents $e.payload.total_amount }
  elseif ($t -in @('order_paid', 'refund_created', 'refund_completed')) { $cents = ToCents $e.payload.amount }
  $streamCents[$t] = $cents + [long]($streamCents[$t])
}
Check '③ 规范流行数 = success_count' ($streamLines -eq $successCount) "行数=$streamLines success_count=$successCount"
Check '⑤ 正控 order_created 有量' (([long]$streamCounts['order_created'] -gt 0) -and ([long]$streamCents['order_created'] -gt 0)) `
  "条数=$($streamCounts['order_created']) 金额分=$($streamCents['order_created'])"
CompareMaps '③-2 逐类条数：库内 vs 规范流' $dbCounts $streamCounts '库内' '流'
CompareMaps '③-3 逐类金额：库内 vs 规范流' $dbCents $streamCents '库内' '流'

# ---------- ④ 运行报告 ----------
$report = Get-Content (Join-Path $runDir 'run-report.json') -Raw | ConvertFrom-Json
$repCounts = @{}; $repCents = @{}
foreach ($s in $report.event_stats) { $repCounts[$s.event_type] = [long]$s.count; $repCents[$s.event_type] = ToCents $s.amount }
CompareMaps '④ 逐类条数：报告 vs 规范流' $repCounts $streamCounts '报告' '流'
CompareMaps '④-2 逐类金额：报告 vs 规范流' $repCents $streamCents '报告' '流'
Check '④-3 报告 status=FAILED 且条数合计=success_count' `
  (($report.status -eq 'FAILED') -and ((($repCounts.Values | Measure-Object -Sum).Sum) -eq $successCount)) `
  "status=$($report.status) 合计=$(($repCounts.Values | Measure-Object -Sum).Sum)"
$notes = ($report.notes -join ' | ')
Check '⑥ 报告写明失败路径统计口径' ($notes -match '部分真相') $notes

# ---------- 流水算术 ----------
$jrows = New-Object System.Collections.ArrayList
foreach ($line in [System.IO.File]::ReadLines((Join-Path $runDir 'operation-journal.jsonl'))) {
  if ($line.Trim()) { [void]$jrows.Add(($line | ConvertFrom-Json)) }
}
$realHttp = @($jrows | Where-Object { $_.real_http }).Count
$localAcc = @($jrows | Where-Object { $_.local_accounting }).Count
$skippedRows = @($jrows | Where-Object { -not $_.real_http -and -not $_.local_accounting }).Count
Check '④-4 流水 request 归属三分（real_http + local_accounting + skipped = 全部）' `
  (($realHttp + $localAcc + $skippedRows) -eq $jrows.Count) `
  "总行数=$($jrows.Count) real_http=$realHttp local_accounting=$localAcc skipped=$skippedRows"

$opToTypes = @{
  createSyntheticUser = @('user_registered'); createOrder = @('order_created')
  pay = @('order_paid'); cancel = @('order_cancelled')
  refund = @('refund_created', 'refund_completed')
  listProducts = @('product_created')   # +1 = 预检那次真实调用，见下面的修正
}
foreach ($op in ($opToTypes.Keys | Sort-Object)) {
  $okRows = @($jrows | Where-Object { $_.operation -eq $op -and $_.status -eq 'OK' }).Count
  $expect = 0
  foreach ($t in $opToTypes[$op]) { $expect += [long]($streamCounts[$t]) }
  if ($op -eq 'listProducts') { $expect += 1 }
  Check "④-5 流水 OK 条数 = 入流条数（$op）" ($okRows -eq $expect) "OK=$okRows 入流=$expect"
}
$journalFailed = @($jrows | Where-Object { $_.status -eq 'FAILED' }).Count
$reportedRejects = 0
if ($report.error_message -match '运行中有 (\d+) 次商城操作被真实拒绝') { $reportedRejects = [long]$Matches[1] }
Check '④-6 流水 FAILED 条数 = 报告被拒次数' ($journalFailed -eq $reportedRejects -and $reportedRejects -gt 0) `
  "流水 FAILED=$journalFailed 报告=$reportedRejects"
Info '④-7 流水 status 直方图' (($jrows | Group-Object status | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ' ')

# ---------- 已知残留（如实打印，不算缺陷修复的一部分） ----------
Info '残留A expected_quarantine_counts（失败路径）' ($report.expected_quarantine_counts | ConvertTo-Json -Compress)
Info '残留B dirty_sample_types（失败路径）' (($report.dirty_sample_types) -join ',')

Write-Host ''
if ($script:fail -eq 0) { Write-Host '== 全部判据通过 =='; exit 0 }
Write-Host "== 有 $script:fail 条判据未通过 =="; exit 1
