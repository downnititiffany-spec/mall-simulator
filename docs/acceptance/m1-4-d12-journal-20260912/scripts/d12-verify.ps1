# M1-4 D12 复算脚本（只读：不发起运行、不改商城数据）
# 用途：对已完成的真机运行重算 §8/§9/§10 全部判据，产出可入库的证据文本。
# 修正 d12-live-run.ps1 的两处脚本自身缺陷：① $l 与 $L 同名（把日志列表覆盖成字符串）② successCount 应为 success_count
# 另修正本文件首版（10:47:26 执行版）的两处查询缺陷：③ generation_plan 无 rate 列（列名 rate_per_second）④ 平台侧副作用误查 mall_simulator 同名表，应查 analytics_meta / analytics_metric（补正证据 raw/d12-p10-analytics-*.log）
param(
  [string]$RunId = 'm1-4-d12-journal-v11-v1-20260912-104653-f1bc'
)
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$gen = 'http://127.0.0.1:8092'

$rawDir = Join-Path $root 'docs\acceptance\m1-4-d12-journal-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("d12-verify-$stamp.log")

$lines = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $lines.Add($s); Write-Host $s }
function Flush() { [System.IO.File]::WriteAllLines($outFile, $lines, (New-Object System.Text.UTF8Encoding($false))) }
function Sql([string]$q) { & $my @MC -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password on the command line' } }
function One([string]$q) { (@(Sql $q) | Select-Object -First 1) }
function Cnt($h, $k) { if ($h.ContainsKey($k)) { [int]$h[$k] } else { 0 } }
$fails = New-Object System.Collections.Generic.List[string]
function Check([bool]$cond, [string]$what, [string]$detail) {
  if ($cond) { W ('  [OK] ' + $what) } else { W ('  [FAIL] ' + $what + '  ' + $detail); $fails.Add($what + ' :: ' + $detail) }
}

W ("=== M1-4 D12 复算（只读）· run_id=" + $RunId + " · 日志起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
W ''
W '--- §0 运行行（库内事实，窗口起点取 started_at）---'
Sql ("SELECT CONCAT('  run_id=',run_id,' plan=',plan_id,' v=',plan_version,' target_version=',target_version,' status=',status,' success=',success_count,' failed=',failed_count,' started=',started_at,' finished=',finished_at,' checksum=',checksum) FROM generator_meta.generation_run WHERE run_id='" + $RunId + "';") | ForEach-Object { W $_ }
$startedAt = One ("SELECT started_at FROM generator_meta.generation_run WHERE run_id='" + $RunId + "';")
$w = ([datetime]$startedAt).ToString('yyyy-MM-dd HH:mm:ss')
W ('  对账窗口 = ' + $w + '（= generation_run.started_at，运行前瞬间）')
Sql ("SELECT CONCAT('  计划定义：mode=',mode,' target_id=',target_id,' scenario=',scenario,' seed=',seed,' event_count=',event_count,' rate_per_second=',rate_per_second,' dirty=',dirty_profile) FROM generator_meta.generation_plan WHERE plan_id=(SELECT plan_id FROM generator_meta.generation_run WHERE run_id='" + $RunId + "') ORDER BY version DESC LIMIT 1;") | ForEach-Object { W $_ }
Flush

W ''
W '--- §1 库内制品行（P7：journal 制品 schema_version）---'
Sql ("SELECT CONCAT('  ',kind,' records=',record_count,' bytes=',bytes,' schema_version=',schema_version,' uri=',uri) FROM generator_meta.generation_artifact WHERE run_id='" + $RunId + "' ORDER BY id;") | ForEach-Object { W $_ }
$jv = One ("SELECT schema_version FROM generator_meta.generation_artifact WHERE run_id='" + $RunId + "' AND kind='OPERATION_JOURNAL';")
$ev = One ("SELECT schema_version FROM generator_meta.generation_artifact WHERE run_id='" + $RunId + "' AND kind='EVENT_JSONL';")
Check ($jv -eq '1.1') 'P7 journal 制品 schema_version=1.1' ('实际=' + $jv)
Check ($ev -eq '1.0') 'P7 事件流制品 schema_version=1.0' ('实际=' + $ev)
W ''
W '  逐类事件统计（generation_event_stat）与运行报告口径：'
Sql ("SELECT CONCAT('  ',event_type,' = ',event_count) FROM generator_meta.generation_event_stat WHERE run_id='" + $RunId + "' ORDER BY event_count DESC, event_type;") | ForEach-Object { W $_ }
W ''
W '  运行报告全文（run-report.json）：'
$runDir0 = Join-Path $root ("synthetic-data-generator\generator-output\" + $RunId)
$rp = Join-Path $runDir0 'run-report.json'
if (Test-Path $rp) { W ((Get-Content $rp -Raw).Trim()) } else { W '  （缺 run-report.json）' }
Flush

W ''
W '--- §2 运行目录产物 ---'
if (Test-Path $runDir0) {
  Get-ChildItem $runDir0 -File | Sort-Object Name | ForEach-Object {
    W ("  {0}  {1} B  sha256={2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash)
  }
} else { W ('  目录不存在：' + $runDir0) }
Flush

W ''
W '--- §3 流水逐字段复算（D12 核心判据）---'
$journal = Join-Path $runDir0 'operation-journal.jsonl'
$events = Get-ChildItem $runDir0 -Filter 'events-*.jsonl' | Select-Object -First 1
$rows = @(Get-Content $journal)
$realHttp = 0; $localRows = 0; $skipped = 0; $nonOk = 0
$byOp = @{}; $byOpReal = @{}; $byOpLocal = @{}
$preflightReal = 0; $preflightLocal = 0
$alignReal = 0; $alignLocal = 0
$realBadShape = 0; $localBadShape = 0; $bothFlags = 0
$firstReal = $null; $firstLocal = $null; $refundSample = $null; $alignSample = $null
foreach ($line in $rows) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $r = $line | ConvertFrom-Json
  $op = $r.operation
  if (-not $byOp.ContainsKey($op)) { $byOp[$op] = 0; $byOpReal[$op] = 0; $byOpLocal[$op] = 0 }
  $byOp[$op]++
  $isReal = [bool]$r.real_http
  $isLocal = [bool]$r.local_accounting
  if ($r.status -eq 'SKIPPED') { $skipped++ } elseif ($r.status -ne 'OK') { $nonOk++ }
  if ($isReal -and $isLocal) { $bothFlags++ }
  if ($isReal) {
    $realHttp++; $byOpReal[$op]++
    if (-not $firstReal) { $firstReal = $r }
    if ([string]::IsNullOrEmpty($r.http_method) -or [string]::IsNullOrEmpty($r.route)) { $realBadShape++ }
  }
  if ($isLocal) {
    $localRows++; $byOpLocal[$op]++
    if (-not $firstLocal) { $firstLocal = $r }
    if (-not [string]::IsNullOrEmpty($r.http_method) -or -not [string]::IsNullOrEmpty($r.route)) { $localBadShape++ }
    if ($op -eq 'listProducts' -and -not $alignSample) { $alignSample = $r }
    if ($op -eq 'refund' -and -not $refundSample) { $refundSample = $r }
  }
  if ($op -eq 'listProducts') {
    if ($isReal -and $isLocal) { } elseif ($isReal) { if ([string]::IsNullOrEmpty($r.canonical_id)) { $preflightReal++ } else { $alignReal++ } }
    elseif ($isLocal) { $alignLocal++ }
  }
}
W ('  流水行数=' + $rows.Count + '  real_http=' + $realHttp + '  local_accounting=' + $localRows + '  SKIPPED=' + $skipped + '  非OK状态=' + $nonOk)
W ('  逐操作总行数：' + (($byOp.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  逐操作 real_http：' + (($byOpReal.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  逐操作 local_accounting：' + (($byOpLocal.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  listProducts 分解：预检 real=' + $preflightReal + ' local=' + $preflightLocal + ' ；目录对齐 real=' + $alignReal + ' local=' + $alignLocal)
W ''
W '  样例行（原文，未改写）：'
W ('    [real 预检]      ' + ($firstReal | ConvertTo-Json -Compress))
W ('    [local 商品对齐] ' + ($alignSample | ConvertTo-Json -Compress))
W ('    [local 退款复用] ' + ($refundSample | ConvertTo-Json -Compress))
W ''
W '  流水首 3 行与末 2 行（原文）：'
$rows | Select-Object -First 3 | ForEach-Object { W ('    ' + $_) }
$rows | Select-Object -Last 2 | ForEach-Object { W ('    ' + $_) }
$evCounts = @{}
if ($events) {
  foreach ($evLine in (Get-Content $events.FullName)) {
    if ([string]::IsNullOrWhiteSpace($evLine)) { continue }
    $e = $evLine | ConvertFrom-Json
    if (-not $evCounts.ContainsKey($e.event_type)) { $evCounts[$e.event_type] = 0 }
    $evCounts[$e.event_type]++
  }
  W ''
  W ('  规范流事件数（' + $events.Name + '）：' + (($evCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
}
$totalEvents = ($evCounts.Values | Measure-Object -Sum).Sum
$pc = Cnt $evCounts 'product_created'; $rc = Cnt $evCounts 'refund_completed'
$ur = Cnt $evCounts 'user_registered'; $oc = Cnt $evCounts 'order_created'
W ''
Check ($totalEvents -eq 120) 'P2 规范流事件数=120' ('实际=' + $totalEvents)
Check ($ur -eq 10) 'P2 user_registered=10（usersFor(120)）' ('实际=' + $ur)
Check ($pc -eq 6) 'P2 product_created=6（productsFor(120)）' ('实际=' + $pc)
Check ($oc -ge 40 -and $oc -le 65) 'P3 order_created 落在预注册区间 40–65' ('实际=' + $oc)
Check ($rows.Count -eq 1 + $totalEvents) 'P4 流水行数 = 1（预检）+ 事件数' ('行数=' + $rows.Count + ' 事件=' + $totalEvents)
Check ($skipped -eq 0 -and $nonOk -eq 0) 'P4 无 SKIPPED、无非 OK 状态' ('skipped=' + $skipped + ' 非OK=' + $nonOk)
Check ($realHttp -eq 1 + $totalEvents - $pc - $rc) 'P5 real_http = 1+事件−商品对齐−退款完成复用' ('real=' + $realHttp + ' 期望=' + (1 + $totalEvents - $pc - $rc))
Check ($localRows -eq $pc + $rc) 'P5 local_accounting = 商品对齐+退款完成复用' ('local=' + $localRows + ' 期望=' + ($pc + $rc))
Check (($realHttp + $localRows + $skipped) -eq $rows.Count) '§3 三分类恰好覆盖每一行（无重复、无遗漏）' ('real+local+skipped=' + ($realHttp + $localRows + $skipped) + ' 行数=' + $rows.Count)
Check ($bothFlags -eq 0) '§3 没有任何一行同时 real_http 与 local_accounting' ('违规=' + $bothFlags)
Check ($realBadShape -eq 0) 'P6 真实调用行必带 http_method + route' ('违规=' + $realBadShape)
Check ($localBadShape -eq 0) 'P6 本地记账行必不带 http_method/route' ('违规=' + $localBadShape)
Check ($preflightReal -eq 1 -and $preflightLocal -eq 0) 'P6 预检行 counted as real_http（canonical_id 为空但真发过请求）' ('real=' + $preflightReal + ' local=' + $preflightLocal)
Check ($alignReal -eq 0 -and $alignLocal -eq $pc) 'P6 商品目录对齐全部是本地记账（D12 原始症状：过去被记成 listProducts 真读）' ('alignReal=' + $alignReal + ' alignLocal=' + $alignLocal + ' product_created=' + $pc)
Flush

W ''
W '--- §4 商城侧独立对照（库内增量 ↔ 流水逐操作真实行数）---'
$dUser = One ("SELECT COUNT(*) FROM mall_simulator.mall_user WHERE created_at >= '" + $w + "';")
$dOrder = One ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE created_at >= '" + $w + "';")
$dPaid = One ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE paid_at >= '" + $w + "';")
$dCancel = One ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE cancelled_at >= '" + $w + "';")
$dRefund = One ("SELECT COUNT(*) FROM mall_simulator.refund WHERE created_at >= '" + $w + "';")
$dPay = One ("SELECT COUNT(*) FROM mall_simulator.payment WHERE paid_at >= '" + $w + "';")
$dItem = One ("SELECT COUNT(*) FROM mall_simulator.order_item oi JOIN mall_simulator.mall_order o ON o.order_id=oi.order_id WHERE o.created_at >= '" + $w + "';")
$dOutbox = One ("SELECT COUNT(*) FROM mall_simulator.event_outbox WHERE created_at >= '" + $w + "';")
W ('  商城增量（窗口 ' + $w + ' 起）：mall_user=' + $dUser + ' mall_order=' + $dOrder + ' order_item=' + $dItem + ' payment=' + $dPay + ' paid_at=' + $dPaid + ' cancelled_at=' + $dCancel + ' refund=' + $dRefund + ' event_outbox=' + $dOutbox)
$rUser = Cnt $byOpReal 'createSyntheticUser'; $rOrder = Cnt $byOpReal 'createOrder'
$rPay = Cnt $byOpReal 'pay'; $rCancel = Cnt $byOpReal 'cancel'; $rRefund = Cnt $byOpReal 'refund'
Check ([int]$dUser -eq $rUser) 'P8 Δmall_user = createSyntheticUser 真实行数' ('Δ=' + $dUser + ' 流水=' + $rUser)
Check ([int]$dOrder -eq $rOrder) 'P8 Δmall_order = createOrder 真实行数' ('Δ=' + $dOrder + ' 流水=' + $rOrder)
Check ([int]$dPay -eq $rPay -and [int]$dPaid -eq $rPay) 'P8 Δpayment / Δpaid_at = pay 真实行数' ('Δpayment=' + $dPay + ' Δpaid=' + $dPaid + ' 流水=' + $rPay)
Check ([int]$dCancel -eq $rCancel) 'P8 Δcancelled_at = cancel 真实行数' ('Δ=' + $dCancel + ' 流水=' + $rCancel)
Check ([int]$dRefund -eq $rRefund) 'P8 Δrefund = refund 真实行数（每条含"申请+完成"两次 HTTP）' ('Δ=' + $dRefund + ' 流水=' + $rRefund)
W ''
W ('  U11 收口（D12 口径）：流水 real_http 行数 = ' + $realHttp + '；其中 refund 行 ' + $rRefund + ' 条每条含两步 HTTP，')
W ('  故商城侧实际收到 HTTP 请求数 = ' + $realHttp + ' + ' + $rRefund + ' = ' + ($realHttp + $rRefund) + '（不含被拒/重试；本次终态 SUCCESS、failed=0）')
W ''
W '  商城退款明细（窗口内）：'
Sql ("SELECT CONCAT('    refund_id=',refund_id,' order=',order_id,' status=',status,' created=',created_at,' completed=',IFNULL(completed_at,'-')) FROM mall_simulator.refund WHERE created_at >= '" + $w + "' ORDER BY refund_id;") | ForEach-Object { W $_ }
W '  订单状态分布（窗口内）：'
Sql ("SELECT CONCAT('    ',status,' = ',n) FROM (SELECT status, COUNT(*) n FROM mall_simulator.mall_order WHERE created_at >= '" + $w + "' GROUP BY status) x ORDER BY n DESC;") | ForEach-Object { W $_ }
W '  库存汇总：'
Sql "SELECT CONCAT('    products=',COUNT(*),' total_avail=',SUM(available_qty),' zero_avail=',SUM(available_qty=0)) FROM mall_simulator.inventory;" | ForEach-Object { W $_ }
Flush

W ''
W '--- §5 结束现场：三程序身份 + 平台侧副作用（P10）---'
foreach ($port in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($c) { W ("  {0} pid={1} 启动={2}" -f $port, $c.OwningProcess, (Get-Process -Id $c.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss')) }
  else { W ("  {0} 未监听" -f $port) }
}
W '  平台侧真实口径（analytics_meta / analytics_metric；mall_simulator 里也有同名表，口径不同，不能当平台副作用判据）：'
Sql "SELECT CONCAT('  ',t,'=',n) FROM (SELECT 'analytics_meta.ingestion_batch' t, COUNT(*) n FROM analytics_meta.ingestion_batch UNION ALL SELECT 'analytics_meta.pipeline_run',COUNT(*) FROM analytics_meta.pipeline_run UNION ALL SELECT 'analytics_meta.file_checkpoint',COUNT(*) FROM analytics_meta.file_checkpoint UNION ALL SELECT 'analytics_metric.metric_snapshot',COUNT(*) FROM analytics_metric.metric_snapshot) x;" | ForEach-Object { W $_ }
W '  U1/U3 轮后态为 ingestion_batch 40 / pipeline_run 40 / metric_snapshot 9 / file_checkpoint 105（本行由复算者对照，非脚本自动断言）'
W '  运行时点自证（均早于本轮 10:46:53）：'
Sql "SELECT CONCAT('  pipeline_run.max_started=',IFNULL(MAX(started_at),'-')) FROM analytics_meta.pipeline_run;" | ForEach-Object { W $_ }
Sql "SELECT CONCAT('  metric_snapshot.max_created=',IFNULL(MAX(created_at),'-')) FROM analytics_metric.metric_snapshot;" | ForEach-Object { W $_ }
W ''
W '  运行结束后无在飞运行：'
Sql "SELECT CONCAT('  non_terminal=',COUNT(*)) FROM generator_meta.generation_run WHERE status IN ('QUEUED','RUNNING');" | ForEach-Object { W $_ }
W ''
if ($fails.Count -gt 0) {
  W ('*** 复算断言失败 ' + $fails.Count + ' 条 ***')
  $fails | ForEach-Object { W ('  - ' + $_) }
} else {
  W '*** 复算全部断言通过 ***'
}
W ("=== 日志结束 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
Flush
Write-Host ('[写出] ' + $outFile + '  (' + $lines.Count + ' 行)  失败断言=' + $fails.Count)
