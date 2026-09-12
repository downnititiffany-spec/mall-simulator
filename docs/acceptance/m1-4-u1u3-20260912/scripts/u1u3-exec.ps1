# M1-4 U1/U3 真机正向取证：预注册预测 -> 发起运行 -> 轮询终态 -> 后态快照
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$gen = 'http://127.0.0.1:8092'
$mall = 'http://127.0.0.1:8090'
$planId = 'm1-4-u1u3-live-20260912'
$runTag = 'u1u3'

$rawDir = Join-Path $root 'docs\acceptance\m1-4-u1u3-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("u1u3-exec-$stamp.log")

$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Flush() { [System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false))) }
# 注意：绝不用 2>$null 吞 stderr —— 列名写错时会静默返回零行（本泳道已两次踩到）。只滤掉 mysql 的口令告警。
function Sql([string]$q) { & $my @MC -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password on the command line' } }

W ("=== M1-4 U1/U3 真机正向运行 · 日志起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
W ''
W '--- §0 预注册预测（本段在发起运行之前写出；预测错也要照原样留着）---'
W '  P1 终态 = SUCCESS'
W '  P2 failed_count = 0，success_count = 640'
W '  P3 createOrder 尝试次数 ≈ 274（= 640 × 171/400，run 147 实测比例 0.4275），区间 200–340'
W '  P4 事件类型计数：user_registered = 53（usersFor(640)=floor(640/12)）、product_created = 32（productsFor(640)=32）、order_created ≈ 274'
W '  P5 至少 1 条 refund_completed 真实写入商城（U3 判据）；预期 2–4 条'
W '  P6 商城侧增量：mall_order ≈ +274、mall_user ≈ +53、refund(COMPLETED) ≥ +1'
W '  P7 无任何 INSUFFICIENT_STOCK（池内 32 件全部 available_qty=500/1000，单件最坏消耗按 run 147 分布外推 ≤ 130）'
W '  P8 落地区：商城 outbox 会新增当前小时文件（若商城定时发布器在跑）'
Flush

W ''
W '--- §1 发起运行（POST /api/v1/generation-runs，body 只含 plan_id + version）---'
$body = '{"plan_id":"' + $planId + '","version":1}'
W ("  请求体 = " + $body)
$postedAt = Get-Date
# 硬断言：curl 式取原始状态码与响应体 —— Invoke-RestMethod 在非 2xx 时可能只返回 $null 而不抛异常，
# 那会让后续轮询拿着空 runId 空转出一堆 404（本泳道第一次尝试的实测教训）。
$postRaw = & curl.exe -s -w '|HTTP=%{http_code}' -X POST "$gen/api/v1/generation-runs" -H 'Content-Type: application/json' --data-binary $body
$postCode = [int]($postRaw -replace '^.*\|HTTP=', '')
$postBody = $postRaw -replace '\|HTTP=\d+$', ''
W ("  发起时刻 = " + $postedAt.ToString('yyyy-MM-dd HH:mm:ss.fff'))
W ("  http_code = " + $postCode)
W ("  响应体 = " + $postBody)
if ($postCode -ne 200) {
  W '  *** 发起失败：按硬断言中止本轮（不轮询、不解释成运行结果）***'
  Flush
  W ''
  W ("=== 日志结束（发起失败）" + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
  Flush
  Write-Host ('[写出] ' + $outFile + '  (' + $L.Count + ' 行)')
  exit 2
}
$runId = ($postBody | ConvertFrom-Json).runId
if ([string]::IsNullOrWhiteSpace($runId)) { W '  *** 响应无 runId，视为发起失败 ***'; Flush; exit 2 }
W ("  run_id = " + $runId)
Flush

W ''
W '--- §2 轮询终态（GET /api/v1/generation-runs/{runId}）---'
$final = $null
for ($i = 1; $i -le 60; $i++) {
  Start-Sleep -Seconds 3
  try {
    $v = Invoke-RestMethod -Uri ("$gen/api/v1/generation-runs/" + $runId) -TimeoutSec 30
    W ("  [{0,2}] {1} status={2} success={3} failed={4}" -f $i, (Get-Date).ToString('HH:mm:ss'), $v.status, $v.successCount, $v.failedCount)
    if ($v.status -in 'SUCCESS', 'FAILED', 'CANCELLED') { $final = $v; break }
  } catch { W ("  [{0,2}] 查询异常：{1}" -f $i, $_.Exception.Message) }
  if ($null -eq $v -and $i -le 3) {
    $probe = & curl.exe -s -w '|HTTP=%{http_code}' "$gen/api/v1/generation-runs/$runId"
    W ("       原始探测：" + $probe)
  }
}
W ''
W '--- §3 终态全文 RunView ---'
W ($final | ConvertTo-Json -Depth 8)
Flush

W ''
W '--- §4 运行产物清单（GET /api/v1/generation-runs/{runId}/artifacts）---'
try {
  $arts = Invoke-RestMethod -Uri ("$gen/api/v1/generation-runs/" + $runId + "/artifacts") -TimeoutSec 30
  W ($arts | ConvertTo-Json -Depth 8)
} catch { W ('  查询异常：' + $_.Exception.Message) }
W ''
W '--- §5 库内运行行（generator_meta.generation_run）---'
Sql ("SELECT CONCAT('id=',id,' run_id=',run_id,' plan=',plan_id,' v',plan_version,' target=',IFNULL(target_id,'-'),' status=',status,' started=',started_at,' finished=',finished_at) FROM generator_meta.generation_run WHERE run_id='" + $runId + "';") | ForEach-Object { W ('  ' + $_) }
Sql ("SELECT CONCAT('success=',IFNULL(success_count,'-'),' failed=',IFNULL(failed_count,'-'),' error_code=',IFNULL(error_code,'-')) FROM generator_meta.generation_run WHERE run_id='" + $runId + "';") | ForEach-Object { W ('  ' + $_) }
Sql ("SELECT CONCAT('error_message=',IFNULL(error_message,'NULL')) FROM generator_meta.generation_run WHERE run_id='" + $runId + "';") | ForEach-Object { W ('  ' + $_) }
W ''
W '--- §6 库内逐类事件统计（generation_event_stat）---'
Sql ("SELECT CONCAT('  ',event_type,' = ',event_count) FROM generator_meta.generation_event_stat WHERE run_id='" + $runId + "' ORDER BY event_count DESC;") | ForEach-Object { W $_ }
W ''
W '--- §7 库内制品行（generation_artifact）---'
Sql ("SELECT CONCAT('  ',kind,' uri=',uri,' records=',record_count,' bytes=',bytes,' checksum=',LEFT(IFNULL(checksum,'-'),16)) FROM generator_meta.generation_artifact WHERE run_id='" + $runId + "';") | ForEach-Object { W $_ }
Flush

W ''
W '--- §8 运行目录产物（含 sha256）---'
$dir = Join-Path $root ("synthetic-data-generator\generator-output\" + $runId)
if (Test-Path $dir) {
  Get-ChildItem $dir -File | Sort-Object Name | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    W ("  {0}  {1} B  sha256={2}  mtime={3}" -f $_.Name, $_.Length, $h, $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss.fff'))
  }
} else { W ('  目录不存在：' + $dir) }
Flush

W ''
W '--- §9 后态：商城计数与库存 ---'
Sql "SELECT CONCAT(t,'=',n) FROM (SELECT 'mall_order' t, COUNT(*) n FROM mall_simulator.mall_order UNION ALL SELECT 'order_item',COUNT(*) FROM mall_simulator.order_item UNION ALL SELECT 'payment',COUNT(*) FROM mall_simulator.payment UNION ALL SELECT 'refund',COUNT(*) FROM mall_simulator.refund UNION ALL SELECT 'refund_COMPLETED',COUNT(*) FROM mall_simulator.refund WHERE status='COMPLETED' UNION ALL SELECT 'mall_user',COUNT(*) FROM mall_simulator.mall_user UNION ALL SELECT 'event_outbox',COUNT(*) FROM mall_simulator.event_outbox) x;" | ForEach-Object { W ('  ' + $_) }
Sql 'SELECT CONCAT(''products='',COUNT(*),'' total_avail='',SUM(available_qty),'' sold_out='',SUM(available_qty=0),'' min='',MIN(available_qty)) FROM mall_simulator.inventory;' | ForEach-Object { W ('  ' + $_) }
Sql "SELECT CONCAT('  outbox/',event_type,'=',COUNT(*)) FROM mall_simulator.event_outbox GROUP BY event_type ORDER BY event_type;" | ForEach-Object { W $_ }
W ''
W '--- §10 本轮运行造成的商城增量（按 run 时间窗）---'
Sql ("SELECT CONCAT('  订单=',COUNT(*)) FROM mall_simulator.mall_order WHERE created_at >= '" + $postedAt.ToString('yyyy-MM-dd HH:mm:ss') + "';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  退款COMPLETED=',COUNT(*)) FROM mall_simulator.refund WHERE created_at >= '" + $postedAt.ToString('yyyy-MM-dd HH:mm:ss') + "' AND status='COMPLETED';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  退款明细: ',refund_id,' order=',order_id,' status=',status,' amount=',amount,' at=',created_at) FROM mall_simulator.refund WHERE created_at >= '" + $postedAt.ToString('yyyy-MM-dd HH:mm:ss') + "' ORDER BY refund_id;") | ForEach-Object { W $_ }
W ''
W '--- §11 本轮下单分布（商品 × 单数 × 件数）---'
Sql ("SELECT CONCAT('  ',oi.product_id,' orders=',COUNT(*),' units=',SUM(oi.quantity)) FROM mall_simulator.mall_order o JOIN mall_simulator.order_item oi ON oi.order_id=o.order_id WHERE o.created_at >= '" + $postedAt.ToString('yyyy-MM-dd HH:mm:ss') + "' GROUP BY oi.product_id ORDER BY oi.product_id;") | ForEach-Object { W $_ }
W ''
W '--- §12 池内库存消耗（逐件，补库存后 -> 运行后）---'
$login = Invoke-RestMethod -Uri "$mall/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
$H = @{ Authorization = "Bearer " + $login.data.token }
$prods = Invoke-RestMethod -Uri "$mall/api/v1/mall/products" -Headers $H -TimeoutSec 20
$pos = 0
foreach ($p in @($prods.data)) {
  $pos++
  $row = Sql ("SELECT CONCAT(available_qty,'|',reserved_qty,'|',version) FROM mall_simulator.inventory WHERE product_id=" + $p.productId + ";")
  $v = if (@($row).Count -gt 0) { @($row)[0] } else { 'NO_ROW' }
  W ("  #{0,2} {1,-22} {2}" -f $pos, $p.productId, $v)
}
W ''
W '--- §13 落地区（时点口径）---'
$le = Get-ChildItem (Join-Path $root 'landing\events') -File
W ("  landing/events 文件数={0} bytes={1}" -f $le.Count, ($le | Measure-Object -Property Length -Sum).Sum)
$le | Sort-Object LastWriteTime -Descending | Select-Object -First 3 | ForEach-Object { W ("    " + $_.Name + "  " + $_.Length + " B  " + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) }
W ''
W '--- §14 结束时三程序身份（应完全未变）---'
foreach ($p in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
  W ("  {0} pid={1} 启动={2}" -f $p, $c.OwningProcess, (Get-Process -Id $c.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
W ("=== 日志结束 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
Flush
Write-Host ("[写出] " + $outFile + "  (" + $L.Count + " 行)")
