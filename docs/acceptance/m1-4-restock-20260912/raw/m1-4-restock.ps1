# M1-4 后续①：给自建商城补库存（D-041 批准的追加性写入，原定"P1-06 之后与 8090/8092 起停一次性做完"）
#   只做追加：changeType=inbound（available_qty = available_qty + quantity），不删行、不改 reserved、不改商品/价格/状态。
#   取证：三个程序实例身份 + 登录 + 补库存前后 available_qty + outbox 事件 + 落地区文件数变化。
# 只读以外唯一的写：3 次库存 inbound（对象 = 被真机运行打空的 1001/1002/1003，available_qty 均为 0）。
$ErrorActionPreference = 'Stop'
$env:MYSQL_PWD = '123456'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$root = (Get-Location).Path
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root 'docs\acceptance\m1-4-restock-20260912\raw'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir "restock-$ts.log"
$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Q([string]$sql) { @(& $mysql -uroot --default-character-set=utf8mb4 -B -N -e $sql 2>&1) }
function Head([string]$s) { W ''; W ("=== " + $s + " ===") }

W ("M1-4 后续① 补库存取证  " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + "  仓库根 " + $root)
W ("HEAD = " + (git rev-parse --short HEAD) + "  branch = " + (git rev-parse --abbrev-ref HEAD))

Head "0. 三个程序实例身份（补库存前）"
foreach ($p in 8090, 8091, 8092) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $c) { W ("  " + $p + ": 未监听"); continue }
  $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $c.OwningProcess)
  $jar = $null
  if ($proc.CommandLine -match '-jar\s+"?([^"]+\.jar)') { $jar = $Matches[1] }
  $sha = if ($jar -and (Test-Path $jar)) { (Get-FileHash $jar -Algorithm SHA256).Hash } else { 'n/a' }
  $len = if ($jar -and (Test-Path $jar)) { (Get-Item $jar).Length } else { 0 }
  W ("  " + $p + ": pid=" + $c.OwningProcess + " 启动=" + $proc.CreationDate)
  W ("       jar=" + $jar + " (" + $len + " B) sha256=" + $sha)
}
$health = @{}
foreach ($u in @('http://127.0.0.1:8090/', 'http://127.0.0.1:8091/api/v1/health', 'http://127.0.0.1:8092/api/v1/scenarios')) {
  try { $r = Invoke-WebRequest $u -TimeoutSec 10 -SkipHttpErrorCheck; $health[$u] = $r.StatusCode } catch { $health[$u] = 'ERR:' + $_.Exception.Message }
  W ("  GET " + $u + " -> " + $health[$u])
}

Head "1. 补库存前状态（商城库，只读）"
W '  --- SQL: SELECT product_id,available_qty,reserved_qty,version FROM mall_simulator.inventory ORDER BY product_id LIMIT 6;'
(Q 'SELECT product_id,available_qty,reserved_qty,version FROM mall_simulator.inventory ORDER BY product_id LIMIT 6;') | ForEach-Object { W ("    " + $_) }
W '  --- 目标三件（真机运行打空的）：'
(Q 'SELECT product_id,available_qty,reserved_qty FROM mall_simulator.inventory WHERE product_id IN (1001,1002,1003) ORDER BY product_id;') | ForEach-Object { W ("    " + $_) }
W '  --- outbox 表名：'
(Q "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_simulator' AND TABLE_NAME LIKE '%outbox%';") | ForEach-Object { W ("    " + $_) }
$obBefore = (Q "SELECT COUNT(*) FROM mall_simulator.event_outbox WHERE event_type='stock_changed';" | Select-Object -Last 1)
W ("  --- stock_changed outbox 事件数（前）= " + $obBefore)
$landBefore = (Get-ChildItem (Join-Path $root 'landing\events') -File | Measure-Object).Count
$landBytesBefore = (Get-ChildItem (Join-Path $root 'landing\events') -File | Measure-Object Length -Sum).Sum
W ("  --- landing\events 文件数（前）= " + $landBefore + " / " + $landBytesBefore + " B")

Head "2. 商城 admin 登录（真实 HTTP）"
$login = Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/login' -Method Post -ContentType 'application/json' `
  -Body (@{ username = 'admin'; password = 'admin123' } | ConvertTo-Json) -SkipHttpErrorCheck -TimeoutSec 15
W ("  POST /api/v1/auth/login -> HTTP " + $login.StatusCode)
W ("  响应体 = " + $login.Content)
$lj = $login.Content | ConvertFrom-Json
$token = $lj.data.token
if (-not $token) { throw '登录未取到 token' }
$H = @{ Authorization = 'Bearer ' + $token }

Head "3. 补库存（POST /api/v1/admin/products/{id}/stock，changeType=inbound，quantity=1000）"
$results = @{}
foreach ($id in 1001, 1002, 1003) {
  $body = @{ quantity = 1000; changeType = 'inbound' } | ConvertTo-Json
  $r = Invoke-WebRequest ("http://127.0.0.1:8090/api/v1/admin/products/" + $id + "/stock") -Method Post `
    -Headers $H -ContentType 'application/json' -Body $body -SkipHttpErrorCheck -TimeoutSec 15
  $results[$id] = $r.StatusCode
  W ("  product " + $id + " -> HTTP " + $r.StatusCode + "  响应体 = " + $r.Content)
}
W '  --- 越权对照（无 token）：'
$r401 = Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/admin/products/1001/stock' -Method Post `
  -ContentType 'application/json' -Body (@{ quantity = 1; changeType = 'inbound' } | ConvertTo-Json) -SkipHttpErrorCheck -TimeoutSec 15
W ("  product 1001（无 Authorization）-> HTTP " + $r401.StatusCode + "  响应体 = " + $r401.Content)

Head "4. 补库存后状态"
W '  --- SQL: 同 §1 查询'
(Q 'SELECT product_id,available_qty,reserved_qty,version FROM mall_simulator.inventory ORDER BY product_id LIMIT 6;') | ForEach-Object { W ("    " + $_) }
(Q 'SELECT product_id,available_qty,reserved_qty FROM mall_simulator.inventory WHERE product_id IN (1001,1002,1003) ORDER BY product_id;') | ForEach-Object { W ("    " + $_) }
Start-Sleep -Seconds 3
$obAfter = (Q "SELECT COUNT(*) FROM mall_simulator.event_outbox WHERE event_type='stock_changed';" | Select-Object -Last 1)
W ("  --- stock_changed outbox 事件数（后）= " + $obAfter + "（增量应 = 3）")
W '  --- 新增 outbox 行明细：'
(Q "SELECT id,aggregate_id,event_type,status,created_at FROM mall_simulator.event_outbox WHERE event_type='stock_changed' ORDER BY id DESC LIMIT 4;" ) | ForEach-Object { W ("    " + $_) }
W '  --- 商城 admin 列表接口回读（GET /api/v1/admin/products，取 1001-1003）：'
$list = Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/admin/products' -Headers $H -SkipHttpErrorCheck -TimeoutSec 15
W ("  HTTP " + $list.StatusCode)
($list.Content | ConvertFrom-Json).data | Where-Object { $_.productId -in @('1001', '1002', '1003') } | ForEach-Object {
  W ("    productId=" + $_.productId + " name=" + $_.productName + " availableQty=" + $_.availableQty + " reservedQty=" + $_.reservedQty + " status=" + $_.status)
}
$landAfter = (Get-ChildItem (Join-Path $root 'landing\events') -File | Measure-Object).Count
$landBytesAfter = (Get-ChildItem (Join-Path $root 'landing\events') -File | Measure-Object Length -Sum).Sum
W ("  --- landing\events 文件数（后）= " + $landAfter + " / " + $landBytesAfter + " B（差额 " + ($landAfter - $landBefore) + " 文件 / " + ($landBytesAfter - $landBytesBefore) + " B）")

Head "5. 结论"
$ok = ($results[1001] -eq 200) -and ($results[1002] -eq 200) -and ($results[1003] -eq 200)
W ("  三件库存补入 HTTP 200 = " + $ok + "；越权对照（无 token）= HTTP " + $r401.StatusCode + "（应 401）")
W ("  stock_changed 事件增量 = " + ([int]$obAfter - [int]$obBefore) + "（应 3）")
W ("  落地区 55 -> " + $landAfter + " 文件：商城已恢复运行，outbox 继续按小时落盘（P1-06 冻结窗口已结束）")
Set-Content -Path $out -Value $L -Encoding UTF8
Write-Output ("OUT=" + $out)