# M1-4 U1/U3：后态补收（exec 脚本被 Select-Object -First 掐断，这里用 run 库内真实时间口径补全 §9-§15）
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$mall = 'http://127.0.0.1:8090'
$runId = 'm1-4-u1u3-live-20260912-v1-20260912-101242-c0ba'
$since = '2026-09-12 10:12:42'   # generation_run.started_at（库内真实值）
$rawDir = Join-Path $root 'docs\acceptance\m1-4-u1u3-20260912\raw'
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("u1u3-post-$stamp.log")
$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add([string]$s); Write-Host $s }
function Flush() { [System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false))) }
function Sql([string]$q) { & $my @MC -e $q 2>&1 | Where-Object { $_ -notmatch 'Using a password on the command line' } }

W ("=== M1-4 U1/U3 后态补收 · " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
W ("run_id  = " + $runId)
W ("增量口径 = created_at >= " + $since + "（generation_run.started_at）")
W '说明：同轮 exec 日志在 §8 之后被外层管道（Select-Object -First）掐断，本文件是同一轮的续录，不改动前一份。'
Flush

W ''
W '--- §9 后态：商城计数 ---'
Sql "SELECT CONCAT(t,'=',n) FROM (SELECT 'mall_order' t, COUNT(*) n FROM mall_simulator.mall_order UNION ALL SELECT 'order_item',COUNT(*) FROM mall_simulator.order_item UNION ALL SELECT 'payment',COUNT(*) FROM mall_simulator.payment UNION ALL SELECT 'refund',COUNT(*) FROM mall_simulator.refund UNION ALL SELECT 'refund_COMPLETED',COUNT(*) FROM mall_simulator.refund WHERE status='COMPLETED' UNION ALL SELECT 'mall_user',COUNT(*) FROM mall_simulator.mall_user UNION ALL SELECT 'event_outbox',COUNT(*) FROM mall_simulator.event_outbox UNION ALL SELECT 'cart_item',COUNT(*) FROM mall_simulator.cart_item) x;" | ForEach-Object { W ('  ' + $_) }
Sql 'SELECT CONCAT(''inventory_rows='',COUNT(*),'' total_avail='',SUM(available_qty),'' total_reserved='',SUM(reserved_qty),'' sold_out='',SUM(available_qty=0),'' min='',MIN(available_qty)) FROM mall_simulator.inventory;' | ForEach-Object { W ('  ' + $_) }
W '  outbox 逐类：'
Sql 'SELECT CONCAT(''    '',event_type,''='',COUNT(*)) FROM mall_simulator.event_outbox GROUP BY event_type ORDER BY event_type;' | ForEach-Object { W $_ }
Flush

W ''
W '--- §10 本轮增量（按 run 时间窗）---'
Sql ("SELECT CONCAT('  订单=',COUNT(*)) FROM mall_simulator.mall_order WHERE created_at >= '" + $since + "';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  订单行=',COUNT(*)) FROM mall_simulator.order_item oi JOIN mall_simulator.mall_order o ON o.order_id=oi.order_id WHERE o.created_at >= '" + $since + "';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  用户=',COUNT(*)) FROM mall_simulator.mall_user WHERE created_at >= '" + $since + "';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  支付=',COUNT(*)) FROM mall_simulator.payment WHERE created_at >= '" + $since + "';") | ForEach-Object { W $_ }
Sql ("SELECT CONCAT('  退款=',COUNT(*),' COMPLETED=',SUM(status='COMPLETED')) FROM mall_simulator.refund WHERE created_at >= '" + $since + "';") | ForEach-Object { W $_ }
W ''
W '  U3 逐条退款明细（本轮写入商城的真实退款）：'
Sql ("SELECT CONCAT('    refund_id=',refund_id,' order=',order_id,' status=',status,' amount=',amount,' reason=',IFNULL(reason,'-'),' created=',created_at) FROM mall_simulator.refund WHERE created_at >= '" + $since + "' ORDER BY refund_id;") | ForEach-Object { W $_ }
W ''
W '  本轮订单状态分布：'
Sql ("SELECT CONCAT('    ',status,'=',COUNT(*)) FROM mall_simulator.mall_order WHERE created_at >= '" + $since + "' GROUP BY status;") | ForEach-Object { W $_ }
W ''
W '  本轮下单分布（商品 × 单数 × 件数）：'
Sql ("SELECT CONCAT('    ',oi.product_id,' orders=',COUNT(*),' units=',SUM(oi.quantity)) FROM mall_simulator.mall_order o JOIN mall_simulator.order_item oi ON oi.order_id=o.order_id WHERE o.created_at >= '" + $since + "' GROUP BY oi.product_id ORDER BY oi.product_id;") | ForEach-Object { W $_ }
W '  本轮下单涉及商品数：'
Sql ("SELECT CONCAT('    不同商品数=',COUNT(DISTINCT oi.product_id)) FROM mall_simulator.mall_order o JOIN mall_simulator.order_item oi ON oi.order_id=o.order_id WHERE o.created_at >= '" + $since + "';") | ForEach-Object { W $_ }
Flush

W ''
W '--- §11 池内 32 件库存（运行后；与补库存日志的 500|*|* 对照）---'
$login = Invoke-RestMethod -Uri "$mall/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
$H = @{ Authorization = "Bearer " + $login.data.token }
$prods = Invoke-RestMethod -Uri "$mall/api/v1/mall/products" -Headers $H -TimeoutSec 30
$pos = 0
foreach ($p in @($prods.data)) {
  $pos++
  $row = Sql ("SELECT CONCAT(available_qty,'|',reserved_qty,'|',version) FROM mall_simulator.inventory WHERE product_id=" + $p.productId + ";")
  $v = if (@($row).Count -gt 0) { @($row)[0] } else { 'NO_ROW' }
  W ("  #{0,2} {1,-22} avail|reserved|ver = {2}" -f $pos, $p.productId, $v)
}
Flush

W ''
W '--- §12 操作日志逐类汇总（operation-journal.jsonl，真机调用的直接证据）---'
$jf = Join-Path $root ("synthetic-data-generator\generator-output\" + $runId + "\operation-journal.jsonl")
if (Test-Path $jf) {
  $lines = Get-Content $jf
  $rows = $lines | ForEach-Object { $_ | ConvertFrom-Json }
  W ('  总行数=' + $rows.Count)
  W ('  字段名=' + (($rows[0].PSObject.Properties | ForEach-Object { $_.Name }) -join ','))
  W '  按 操作 × 结果 计数：'
  $rows | Group-Object { ($_.operation, $_.result) -join ' | ' } | Sort-Object Name | ForEach-Object { W ('    {0} -> {1}' -f $_.Name, $_.Count) }
  W ''
  W '  U3 退款相关行（逐条原文）：'
  $rows | Where-Object { $_.operation -match 'efund' -or $_.event_type -match 'efund' } | ForEach-Object { W ('    ' + ($_ | ConvertTo-Json -Compress)) }
  W ''
  W '  头两行原文（看字段语义）：'
  $lines | Select-Object -First 2 | ForEach-Object { W ('    ' + $_) }
} else { W ('  未找到：' + $jf) }
Flush

W ''
W '--- §13 运行报告 run-report.json（原文）---'
$rf = Join-Path $root ("synthetic-data-generator\generator-output\" + $runId + "\run-report.json")
if (Test-Path $rf) { W (Get-Content $rf -Raw) } else { W '  未找到' }
Flush

W ''
W '--- §14 落地区时点口径 ---'
$le = Get-ChildItem (Join-Path $root 'landing\events') -File
W ("  landing/events 文件数={0} bytes={1}（时点 {2}）" -f $le.Count, ($le | Measure-Object -Property Length -Sum).Sum, (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
$le | Sort-Object LastWriteTime -Descending | Select-Object -First 4 | ForEach-Object { W ("    " + $_.Name + "  " + $_.Length + " B  " + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) }
W ''
W '--- §15 结束时三程序身份 ---'
foreach ($p in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
  W ("  {0} pid={1} 启动={2}" -f $p, $c.OwningProcess, (Get-Process -Id $c.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
Flush
W ''
W ("=== 结束 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
Flush
Write-Host ('[写出] ' + $outFile + '  (' + $L.Count + ' 行)')
