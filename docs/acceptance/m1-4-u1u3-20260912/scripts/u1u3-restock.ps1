# M1-4 U1/U3：把商品池（目录前 32 件 = productsFor(640)）可用库存补到 500
# 依据：D-041「批准向自建商城补库存（追加性写入，非破坏性），使大规模真机 SUCCESS 可取证」
# 同一接口 POST /api/v1/admin/products/{id}/stock，同一 changeType=inbound，仅追加可用量，不动 reserved_qty
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$mall = 'http://127.0.0.1:8090'
$TARGET = 500
$POOL = 32

$rawDir = Join-Path $root 'docs\acceptance\m1-4-u1u3-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("u1u3-restock-$stamp.txt")

$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Sql([string]$q) { & $my @MC -e $q 2>$null }

W ("=== M1-4 U1/U3 前置：池内补库存 · 起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + " ===")
W ("授权依据：D-041（追加性写入，非破坏性）；接口 POST {0}/api/v1/admin/products/{{id}}/stock changeType=inbound；目标 available_qty={1}；池内定义=目录前 {2} 件（productsFor(640)=640/20）" -f $mall, $TARGET, $POOL)

$login = Invoke-RestMethod -Uri "$mall/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
$token = $login.data.token
$H = @{ Authorization = "Bearer $token"; 'Content-Type' = 'application/json' }
W ("登录 code={0} role={1} token长度={2}（值不落盘）" -f $login.code, $login.data.user.role, $token.Length)

# 无 token 对照（证明该接口确实受鉴权保护，不是谁都能改库存）
$ctrl = 'n/a'
try { Invoke-RestMethod -Uri "$mall/api/v1/admin/products/1004/stock" -Method Post -ContentType 'application/json' -Body '{"quantity":10,"changeType":"inbound"}' -TimeoutSec 10 | Out-Null; $ctrl = '意外成功' }
catch { $ctrl = 'HTTP ' + [int]$_.Exception.Response.StatusCode.value__ }
W ("无 token 对照（POST /admin/products/1004/stock）-> {0}" -f $ctrl)

$prods = Invoke-RestMethod -Uri "$mall/api/v1/mall/products" -Headers $H -TimeoutSec 20
$list = @($prods.data)
W ("目录件数={0}（生成器视角顺序）" -f $list.Count)

W ''
W '--- 补库存前：池内 32 件 ---'
$pos = 0
$before = @{}
foreach ($p in $list) {
  $pos++
  if ($pos -gt $POOL) { continue }
  $row = Sql ("SELECT CONCAT(available_qty,'|',reserved_qty,'|',version) FROM mall_simulator.inventory WHERE product_id=" + $p.productId + ";")
  $v = if (@($row).Count -gt 0) { @($row)[0] } else { 'NO_ROW' }
  $before[$p.productId] = $v
  W ("  #{0,2} {1,-22} {2}" -f $pos, $p.productId, $v)
}

W ''
W '--- 执行补库存（仅 available_qty < 目标者）---'
$pos = 0
$n = 0
foreach ($p in $list) {
  $pos++
  if ($pos -gt $POOL) { continue }
  $b = $before[$p.productId]
  if ($b -eq 'NO_ROW') { W ("  #{0,2} {1} 无库存行 —— 跳过（本接口语义是累加，不负责建行；登记为发现）" -f $pos, $p.productId); continue }
  $avail = [int]($b.Split('|')[0])
  if ($avail -ge $TARGET) { W ("  #{0,2} {1} available={2} >= {3} —— 不动" -f $pos, $p.productId, $avail, $TARGET); continue }
  $qty = $TARGET - $avail
  $body = '{"quantity":' + $qty + ',"changeType":"inbound"}'
  try {
    $r = Invoke-RestMethod -Uri ("$mall/api/v1/admin/products/" + $p.productId + "/stock") -Method Post -Headers $H -Body $body -TimeoutSec 15
    $n++
    $after = Sql ("SELECT CONCAT(available_qty,'|',reserved_qty,'|',version) FROM mall_simulator.inventory WHERE product_id=" + $p.productId + ";")
    W ("  #{0,2} {1,-22} inbound +{2,-4} HTTP200 code={3}  {4} -> {5}" -f $pos, $p.productId, $qty, $r.code, $b, @($after)[0])
  } catch {
    W ("  #{0,2} {1,-22} inbound +{2,-4} 失败：{3}" -f $pos, $p.productId, $qty, $_.Exception.Message)
  }
}
W ("本次实际调用成功次数 = {0}" -f $n)

W ''
W '--- 补库存后：池内 32 件 + 全库汇总 ---'
$pos = 0
foreach ($p in $list) {
  $pos++
  if ($pos -gt $POOL) { continue }
  $row = Sql ("SELECT CONCAT(available_qty,'|',reserved_qty,'|',version) FROM mall_simulator.inventory WHERE product_id=" + $p.productId + ";")
  W ("  #{0,2} {1,-22} {2}  (补前 {3})" -f $pos, $p.productId, @($row)[0], $before[$p.productId])
}
Sql 'SELECT CONCAT(''products='',COUNT(*),'' total_avail='',SUM(available_qty),'' sold_out='',SUM(available_qty=0),'' min='',MIN(available_qty)) FROM mall_simulator.inventory;' | ForEach-Object { W ('  ' + $_) }
Sql "SELECT CONCAT('outbox/stock_changed=',COUNT(*)) FROM mall_simulator.event_outbox WHERE event_type='stock_changed';" | ForEach-Object { W ('  ' + $_) }

[System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("[写出] " + $outFile + "  (" + $L.Count + " 行)")
