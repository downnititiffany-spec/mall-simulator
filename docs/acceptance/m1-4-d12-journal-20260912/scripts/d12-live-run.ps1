# M1-4 D12 真机正向复测：流水 real_http / local_accounting 两列 + U11 真实请求量收口
# 前置：新 fat jar 已打出（见 raw/e1-d12-package-r2-*.log），8092 处于停止状态，8090/8091 在跑。
# 说明：本文件是**修正版**。10:46:42 实际执行的那一版有两处脚本自身缺陷（不改写，留档见 raw/d12-live-*.log 与 README §8）：
#   ① 事件循环用了 `$l`，而 PowerShell 变量名大小写不敏感 ⇒ 与日志列表 `$L` 同名，把日志列表覆盖成字符串，证据文件只剩 1 行；
#   ② 终态断言取了 `successCount`，而 RunView 字段是 `success_count` ⇒ 唯一那条"失败断言"是脚本误判，不是系统缺陷。
#   ⇒ 真机运行本身成功（SUCCESS 120/0）；判据复算改由只读脚本 d12-verify.ps1 完成并留档。
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$gen = 'http://127.0.0.1:8092'
$mall = 'http://127.0.0.1:8090'
$planId = 'm1-4-d12-journal-v11'
$eventCount = 120
$seed = 20260912
$jar = Join-Path $root 'synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar'

$rawDir = Join-Path $root 'docs\acceptance\m1-4-d12-journal-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("d12-live-$stamp.log")

$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Flush() { [System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false))) }
# 绝不用 2>$null 吞 stderr：列名写错时 mysql 只报错不返回行，被吞掉就会得出"计数=0"的假结论（F-18）
function Sql([string]$q) { & $my @MC -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password on the command line' } }
$fails = New-Object System.Collections.Generic.List[string]
function Check([bool]$cond, [string]$what, [string]$detail) {
  if ($cond) { W ('  [OK] ' + $what) } else { W ('  [FAIL] ' + $what + '  ' + $detail); $fails.Add($what + ' :: ' + $detail) }
}

W ("=== M1-4 D12 真机正向复测 · 日志起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
W ''
W '--- §0 预注册预测（本段在发起运行之前写出；错了照原样留着）---'
W '  P1  终态 SUCCESS，success_count = 120，failed_count = 0'
W '  P2  逐类计数：user_registered = 10（usersFor(120)）、product_created = 6（productsFor(120)）'
W '  P3  order_created ≈ 51（120 × run147 实测比例 0.4275），区间 40–65'
W '  P4  流水行数 = 121 = 1（预检）+ 120（每条成功事件一行），SKIPPED = 0'
W '  P5  real_http 行数 = 1 + 120 − 6 − refund_completed；local_accounting 行数 = 6 + refund_completed'
W '  P6  预检行：real_http=true 且 canonical_id 为空；商品对齐行：real_http=false、local_accounting=true、method/route 为空'
W '  P7  库内 generation_artifact：journal 制品 schema_version = 1.1，events-0001.jsonl = 1.0'
W '  P8  商城侧增量与流水逐操作对齐：Δmall_user = createSyntheticUser 真实行数、Δmall_order = createOrder 真实行数、'
W '      Δ(refund) = refund 真实行数（每行含"申请+完成"两次 HTTP）、Δ(cancelled_at) = cancel 真实行数'
W '  P9  无 INSUFFICIENT_STOCK、无商城 5xx（池内前 6 件商品都有 inventory 行）'
W '  P10 平台侧零副作用：ingestion_batch / pipeline_run / metric_snapshot / file_checkpoint 行数不变'
Flush

W ''
W '--- §1 运行前身份与现场 ---'
foreach ($p in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($c) { W ("  {0} pid={1} 启动={2}" -f $p, $c.OwningProcess, (Get-Process -Id $c.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss')) }
  else { W ("  {0} 未监听" -f $p) }
}
$f = Get-Item $jar
W ('  待启动 jar=' + $f.Length + ' B mtime=' + $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + ' sha256=' + (Get-FileHash $jar -Algorithm SHA256).Hash)
Sql "SELECT CONCAT('  non_terminal_runs=',COUNT(*)) FROM generator_meta.generation_run WHERE status IN ('QUEUED','RUNNING');" | ForEach-Object { W $_ }
W '  商城现态：'
Sql "SELECT CONCAT('    ',t,'=',n) FROM (SELECT 'mall_order' t, COUNT(*) n FROM mall_simulator.mall_order UNION ALL SELECT 'order_item',COUNT(*) FROM mall_simulator.order_item UNION ALL SELECT 'payment',COUNT(*) FROM mall_simulator.payment UNION ALL SELECT 'refund',COUNT(*) FROM mall_simulator.refund UNION ALL SELECT 'mall_user',COUNT(*) FROM mall_simulator.mall_user UNION ALL SELECT 'event_outbox',COUNT(*) FROM mall_simulator.event_outbox) x;" | ForEach-Object { W $_ }
W '  平台现态：'
Sql "SELECT CONCAT('    ',t,'=',n) FROM (SELECT 'ingestion_batch' t, COUNT(*) n FROM mall_simulator.ingestion_batch UNION ALL SELECT 'pipeline_run',COUNT(*) FROM mall_simulator.pipeline_run UNION ALL SELECT 'metric_snapshot',COUNT(*) FROM mall_simulator.metric_snapshot UNION ALL SELECT 'file_checkpoint',COUNT(*) FROM mall_simulator.file_checkpoint) x;" | ForEach-Object { W $_ }
Flush

W ''
W '--- §2 追加计划版本（CLI plan-append；8092 未在跑，故也无需 web=none）---'
$args2 = @('-Dfile.encoding=UTF-8', '-jar', $jar, '--spring.main.web-application-type=none',
  '--generator.cli=plan-append', ('--plan-id=' + $planId), '--mode=MALL_API', '--target-id=118',
  '--scenario=normal', ('--seed=' + $seed), '--start=2026-09-12T10:00:00', '--end=2026-09-12T11:00:00',
  ('--event-count=' + $eventCount), '--rate=0', '--dirty-profile=none')
$planOut = & D:\Develop\JAVA17\bin\java.exe @args2 2>&1
$planCode = $LASTEXITCODE
$planOut | Where-Object { $_ -match 'plan_id=|ERROR|Exception' } | Select-Object -First 6 | ForEach-Object { W ('  ' + $_.Trim()) }
W ('  CLI exit=' + $planCode)
Sql ("SELECT CONCAT('  库内计划行：plan_id=',plan_id,' version=',version,' mode=',mode,' target_id=',IFNULL(target_id,'-'),' scenario=',scenario,' seed=',seed,' event_count=',event_count) FROM generator_meta.generation_plan WHERE plan_id='" + $planId + "' ORDER BY version DESC LIMIT 1;") | ForEach-Object { W $_ }
$version = (Sql ("SELECT MAX(version) FROM generator_meta.generation_plan WHERE plan_id='" + $planId + "';") | Select-Object -First 1)
W ('  将使用的 version=' + $version)
Check ([int]$version -ge 1 -and $planCode -eq 0) '计划版本已落库' ('exit=' + $planCode + ' version=' + $version)
Flush

W ''
W '--- §3 取 8090 新会话令牌并注入 8092（只进进程环境，不落盘、不打印值）---'
$login = Invoke-RestMethod -Uri "$mall/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
$tok = $login.data.token
W ('  code=' + $login.code + ' token长度=' + ($tok | Measure-Object -Character).Characters + '（值不落盘）')
$env:GENERATOR_TARGET_TOKEN = $tok
$wd = Join-Path $root 'synthetic-data-generator'
$p = Start-Process -FilePath 'D:\Develop\JAVA17\bin\java.exe' `
  -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $jar `
  -WorkingDirectory $wd -PassThru -WindowStyle Hidden
W ('  新 8092 pid=' + $p.Id + ' CWD=' + $wd)
$ready = $false
for ($i = 1; $i -le 45; $i++) {
  Start-Sleep -Seconds 2
  try { $s = Invoke-WebRequest -Uri "$gen/api/v1/scenarios" -UseBasicParsing -TimeoutSec 5; if ($s.StatusCode -eq 200) { $ready = $true; break } } catch { }
}
$c1 = Get-NetTCPConnection -State Listen -LocalPort 8092 -ErrorAction SilentlyContinue | Select-Object -First 1
W ('  就绪=' + $ready + ' 监听 pid=' + $c1.OwningProcess + ' 启动=' + (Get-Process -Id $c1.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
Check $ready '新 8092 已就绪（新 jar + 凭据在进程环境里）' ('ready=' + $ready)
Flush

W ''
W '--- §4 目标 118 探针（凭据可达性实测，不经运行）---'
try {
  $t = Invoke-WebRequest -Uri "$gen/api/v1/targets/118/test" -Method Post -ContentType 'application/json' -Body '{}' -UseBasicParsing -TimeoutSec 60
  W ('  http=' + $t.StatusCode)
  W ('  ' + $t.Content)
} catch { W ('  异常：' + $_.Exception.Message) }
Flush

W ''
W '--- §5 发起运行并轮询终态 ---'
$body = '{"plan_id":"' + $planId + '","version":' + $version + '}'
W ('  请求体 = ' + $body)
$postedAt = Get-Date
$postRaw = & curl.exe -s -w '|HTTP=%{http_code}' -X POST "$gen/api/v1/generation-runs" -H 'Content-Type: application/json' --data-binary $body
$postCode = [int]($postRaw -replace '^.*\|HTTP=', '')
$postBody = $postRaw -replace '\|HTTP=\d+$', ''
W ('  发起时刻=' + $postedAt.ToString('yyyy-MM-dd HH:mm:ss.fff') + ' http_code=' + $postCode)
W ('  响应体=' + $postBody)
if ($postCode -ne 200) {
  W '  *** 发起失败：硬断言中止（不轮询、不解释成运行结果）***'
  Flush; Write-Host ('[写出] ' + $outFile); exit 2
}
$runId = ($postBody | ConvertFrom-Json).runId
W ('  run_id=' + $runId)
$final = $null
for ($i = 1; $i -le 60; $i++) {
  Start-Sleep -Seconds 3
  try {
    $v = Invoke-RestMethod -Uri ("$gen/api/v1/generation-runs/" + $runId) -TimeoutSec 30
    W ("  [{0,2}] {1} status={2} success={3} failed={4}" -f $i, (Get-Date).ToString('HH:mm:ss'), $v.status, $v.successCount, $v.failedCount)
    if ($v.status -in 'SUCCESS', 'FAILED', 'CANCELLED') { $final = $v; break }
  } catch { W ("  [{0,2}] 查询异常：{1}" -f $i, $_.Exception.Message) }
}
W '  终态全文：'
W ($final | ConvertTo-Json -Depth 8)
Check ($final -and $final.status -eq 'SUCCESS') 'P1 终态 SUCCESS' ('status=' + $(if ($final) { $final.status } else { 'null' }))
# RunView 字段名是 success_count / failed_count（蛇形）；写成 successCount 会取到 $null 而误判失败（本轮踩到）
Check ($final -and $final.success_count -eq $eventCount -and $final.failed_count -eq 0) 'P1 success_count=120 / failed=0' ($final | ConvertTo-Json -Compress -Depth 4)
Flush

W ''
W '--- §6 库内制品行（含 schema_version：P7 的判据）---'
Sql ("SELECT CONCAT('  ',kind,' records=',record_count,' bytes=',bytes,' schema_version=',schema_version,' uri=',uri) FROM generator_meta.generation_artifact WHERE run_id='" + $runId + "' ORDER BY id;") | ForEach-Object { W $_ }
$jv = (Sql ("SELECT schema_version FROM generator_meta.generation_artifact WHERE run_id='" + $runId + "' AND kind='OPERATION_JOURNAL';") | Select-Object -First 1)
$ev = (Sql ("SELECT schema_version FROM generator_meta.generation_artifact WHERE run_id='" + $runId + "' AND kind='EVENT_JSONL';") | Select-Object -First 1)
Check ($jv -eq '1.1') 'P7 journal 制品 schema_version=1.1' ('实际=' + $jv)
Check ($ev -eq '1.0') 'P7 事件流制品 schema_version=1.0' ('实际=' + $ev)
W ''
W '--- §6b 逐类事件统计（generation_event_stat）与运行报告口径 ---'
Sql ("SELECT CONCAT('  ',event_type,' = ',event_count) FROM generator_meta.generation_event_stat WHERE run_id='" + $runId + "' ORDER BY event_count DESC;") | ForEach-Object { W $_ }
Flush

W ''
W '--- §7 运行目录产物 ---'
$runDir = Join-Path $root ("synthetic-data-generator\generator-output\" + $runId)
if (Test-Path $runDir) {
  Get-ChildItem $runDir -File | Sort-Object Name | ForEach-Object {
    W ("  {0}  {1} B  sha256={2}" -f $_.Name, $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash)
  }
} else { W ('  目录不存在：' + $runDir) }
Flush

W ''
W '--- §8 流水逐字段复算（D12 的核心判据）---'
$journal = Join-Path $runDir 'operation-journal.jsonl'
$events = Get-ChildItem $runDir -Filter 'events-*.jsonl' | Select-Object -First 1
$rows = @(Get-Content $journal)
$realHttp = 0; $localRows = 0; $skipped = 0; $badStatus = @()
$byOp = @{}; $byOpReal = @{}; $byOpLocal = @{}
$preflightReal = 0; $preflightLocal = 0
$alignReal = 0; $alignLocal = 0; $alignBadShape = 0; $realBadShape = 0; $localBadShape = 0
$firstReal = $null; $firstLocal = $null
foreach ($line in $rows) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $r = $line | ConvertFrom-Json
  $op = $r.operation
  if (-not $byOp.ContainsKey($op)) { $byOp[$op] = 0; $byOpReal[$op] = 0; $byOpLocal[$op] = 0 }
  $byOp[$op]++
  $isReal = [bool]$r.real_http
  $isLocal = [bool]$r.local_accounting
  if ($r.status -eq 'SKIPPED') { $skipped++ } elseif ($r.status -ne 'OK') { $badStatus += $line }
  if ($isReal -and $isLocal) { $fails.Add('同一行既 real_http 又 local_accounting：' + $line) }
  if ($isReal) {
    $realHttp++; $byOpReal[$op]++
    if (-not $firstReal) { $firstReal = $r }
    if ([string]::IsNullOrEmpty($r.http_method) -or [string]::IsNullOrEmpty($r.route)) { $realBadShape++ }
  }
  if ($isLocal) {
    $localRows++; $byOpLocal[$op]++
    if (-not $firstLocal) { $firstLocal = $r }
    if (-not [string]::IsNullOrEmpty($r.http_method) -or -not [string]::IsNullOrEmpty($r.route)) { $localBadShape++ }
  }
  if ($op -eq 'listProducts') {
    if ([string]::IsNullOrEmpty($r.canonical_id)) { if ($isReal) { $preflightReal++ } else { $preflightLocal++ } }
    elseif ($isLocal) { $alignLocal++ } elseif ($isReal) { $alignReal++ }
    if ($isReal -and -not $isLocal -and -not [string]::IsNullOrEmpty($r.canonical_id)) { $alignBadShape++ }
  }
}
W ('  流水行数=' + $rows.Count + '  real_http=' + $realHttp + '  local_accounting=' + $localRows + '  SKIPPED=' + $skipped)
W ('  逐操作：' + (($byOp.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  逐操作 real_http：' + (($byOpReal.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  逐操作 local_accounting：' + (($byOpLocal.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
W ('  预检行 real/local = ' + $preflightReal + '/' + $preflightLocal + '；商品对齐行 real/local = ' + $alignReal + '/' + $alignLocal)
W '  样例（第一条 real_http）：' + ($firstReal | ConvertTo-Json -Compress)
W '  样例（第一条 local_accounting）：' + ($firstLocal | ConvertTo-Json -Compress)
$evCounts = @{}
if ($events) {
  foreach ($evLine in (Get-Content $events.FullName)) {
    if ([string]::IsNullOrWhiteSpace($evLine)) { continue }
    $e = $evLine | ConvertFrom-Json
    if (-not $evCounts.ContainsKey($e.event_type)) { $evCounts[$e.event_type] = 0 }
    $evCounts[$e.event_type]++
  }
  W ('  规范流事件数=' + (($evCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value }) -join '  '))
}
$totalEvents = ($evCounts.Values | Measure-Object -Sum).Sum
$pc = if ($evCounts.ContainsKey('product_created')) { $evCounts['product_created'] } else { 0 }
$rc = if ($evCounts.ContainsKey('refund_completed')) { $evCounts['refund_completed'] } else { 0 }
$ur = if ($evCounts.ContainsKey('user_registered')) { $evCounts['user_registered'] } else { 0 }
$oc = if ($evCounts.ContainsKey('order_created')) { $evCounts['order_created'] } else { 0 }
Check ($totalEvents -eq $eventCount) 'P2 规范流事件数=120' ('实际=' + $totalEvents)
Check ($ur -eq 10) 'P2 user_registered=10' ('实际=' + $ur)
Check ($pc -eq 6) 'P2 product_created=6' ('实际=' + $pc)
Check ($oc -ge 40 -and $oc -le 65) 'P3 order_created 在 40–65' ('实际=' + $oc)
Check ($rows.Count -eq 1 + $totalEvents) 'P4 流水行数=1+事件数' ('行数=' + $rows.Count + ' 事件=' + $totalEvents)
Check ($skipped -eq 0 -and $badStatus.Count -eq 0) 'P4/§8 无 SKIPPED、无非 OK 状态' ('skipped=' + $skipped + ' 非OK=' + $badStatus.Count)
Check ($realHttp -eq 1 + $totalEvents - $pc - $rc) 'P5 real_http 行数 = 1+事件−商品对齐−退款完成复用' ('real=' + $realHttp + ' 期望=' + (1 + $totalEvents - $pc - $rc))
Check ($localRows -eq $pc + $rc) 'P5 local_accounting 行数 = 商品对齐+退款完成复用' ('local=' + $localRows + ' 期望=' + ($pc + $rc))
Check (($realHttp + $localRows + $skipped) -eq $rows.Count) '§8 三分类覆盖每一行' ('real+local+skipped=' + ($realHttp + $localRows + $skipped) + ' 行数=' + $rows.Count)
Check ($preflightReal -eq 1 -and $preflightLocal -eq 0) 'P6 预检行是真实调用且只有一条' ('real=' + $preflightReal + ' local=' + $preflightLocal)
Check ($alignReal -eq 0 -and $alignLocal -eq $pc) 'P6 商品对齐行全部是本地记账（D12 原始症状）' ('alignReal=' + $alignReal + ' alignLocal=' + $alignLocal + ' product_created=' + $pc)
Check ($localBadShape -eq 0) 'P6 本地记账行不带 method/route' ('违规行数=' + $localBadShape)
Check ($realBadShape -eq 0) 'P6 真实调用行必带 method/route' ('违规行数=' + $realBadShape)
Flush

W ''
W '--- §9 商城侧独立对照（库内增量 ↔ 流水逐操作真实行数）---'
$w = $postedAt.ToString('yyyy-MM-dd HH:mm:ss')
$dUser = @(Sql ("SELECT COUNT(*) FROM mall_simulator.mall_user WHERE created_at >= '" + $w + "';"))[0]
$dOrder = @(Sql ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE created_at >= '" + $w + "';"))[0]
$dPaid = @(Sql ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE paid_at >= '" + $w + "';"))[0]
$dCancel = @(Sql ("SELECT COUNT(*) FROM mall_simulator.mall_order WHERE cancelled_at >= '" + $w + "';"))[0]
$dRefund = @(Sql ("SELECT COUNT(*) FROM mall_simulator.refund WHERE created_at >= '" + $w + "';"))[0]
$dPay = @(Sql ("SELECT COUNT(*) FROM mall_simulator.payment WHERE paid_at >= '" + $w + "';"))[0]
$dItem = @(Sql ("SELECT COUNT(*) FROM mall_simulator.order_item oi JOIN mall_simulator.mall_order o ON o.order_id=oi.order_id WHERE o.created_at >= '" + $w + "';"))[0]
$dOutbox = @(Sql ("SELECT COUNT(*) FROM mall_simulator.event_outbox WHERE created_at >= '" + $w + "';"))[0]
W ('  商城增量（窗口 ' + $w + ' 起）：mall_user=' + $dUser + ' mall_order=' + $dOrder + ' order_item=' + $dItem + ' payment=' + $dPay + ' paid_at=' + $dPaid + ' cancelled_at=' + $dCancel + ' refund=' + $dRefund + ' event_outbox=' + $dOutbox)
Check ([int]$dUser -eq $byOpReal['createSyntheticUser']) 'P8 Δmall_user = createSyntheticUser 真实行数' ('Δ=' + $dUser + ' 流水=' + $byOpReal['createSyntheticUser'])
Check ([int]$dOrder -eq $byOpReal['createOrder']) 'P8 Δmall_order = createOrder 真实行数' ('Δ=' + $dOrder + ' 流水=' + $byOpReal['createOrder'])
Check ([int]$dPaid -eq $byOpReal['pay'] -and [int]$dPay -eq $byOpReal['pay']) 'P8 Δpaid_at/Δpayment = pay 真实行数' ('Δpaid=' + $dPaid + ' Δpayment=' + $dPay + ' 流水=' + $byOpReal['pay'])
Check ([int]$dCancel -eq $byOpReal['cancel']) 'P8 Δcancelled_at = cancel 真实行数' ('Δ=' + $dCancel + ' 流水=' + $byOpReal['cancel'])
Check ([int]$dRefund -eq $byOpReal['refund']) 'P8 Δrefund = refund 真实行数（每行含申请+完成两次 HTTP）' ('Δ=' + $dRefund + ' 流水=' + $byOpReal['refund'])
W ('  U11 收口：流水 real_http 行数=' + $realHttp + '；商城侧实际 HTTP 请求数 = real_http + refund 行数 = ' + $realHttp + ' + ' + $byOpReal['refund'] + ' = ' + ($realHttp + $byOpReal['refund']) + '（退款一行两步）')
Sql ("SELECT CONCAT('  商城退款明细：',refund_id,' order=',order_id,' status=',status,' at=',created_at) FROM mall_simulator.refund WHERE created_at >= '" + $w + "' ORDER BY refund_id;") | ForEach-Object { W $_ }
Sql "SELECT CONCAT('  库存：products=',COUNT(*),' total_avail=',SUM(available_qty),' sold_out=',SUM(available_qty=0)) FROM mall_simulator.inventory;" | ForEach-Object { W $_ }
Flush

W ''
W '--- §10 结束现场：三程序身份 + 平台侧零副作用（P10）---'
foreach ($p in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
  W ("  {0} pid={1} 启动={2}" -f $p, $c.OwningProcess, (Get-Process -Id $c.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
Sql "SELECT CONCAT('  ',t,'=',n) FROM (SELECT 'ingestion_batch' t, COUNT(*) n FROM mall_simulator.ingestion_batch UNION ALL SELECT 'pipeline_run',COUNT(*) FROM mall_simulator.pipeline_run UNION ALL SELECT 'metric_snapshot',COUNT(*) FROM mall_simulator.metric_snapshot UNION ALL SELECT 'file_checkpoint',COUNT(*) FROM mall_simulator.file_checkpoint) x;" | ForEach-Object { W $_ }
W ''
if ($fails.Count -gt 0) {
  W ('*** 断言失败 ' + $fails.Count + ' 条 ***')
  $fails | ForEach-Object { W ('  - ' + $_) }
} else {
  W '*** 全部断言通过 ***'
}
W ("=== 日志结束 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
Flush
Write-Host ('[写出] ' + $outFile + '  (' + $L.Count + ' 行)  run_id=' + $runId + '  失败断言=' + $fails.Count)
