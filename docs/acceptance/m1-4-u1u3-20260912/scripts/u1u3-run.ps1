# M1-4 U1/U3 真机正向取证：前态 -> 计划 -> 运行 -> 后态
# 用法：pwsh -File .verify/u1u3-run.ps1 -Phase pre|plan|run|post
param([string]$Phase = 'pre')

$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MC = @('-uroot', '-p123456', '-N', '--raw', '--default-character-set=utf8mb4')
$gen = 'http://127.0.0.1:8092'
$mall = 'http://127.0.0.1:8090'
$plat = 'http://127.0.0.1:8091'
$rawDir = Join-Path $root 'docs\acceptance\m1-4-u1u3-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("u1u3-$Phase-$stamp.txt")

$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Sql([string]$q) { & $my @MC -e $q 2>$null }

W ("=== M1-4 U1/U3 真机正向取证 · 阶段=" + $Phase + " · 起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + " ===")

if ($Phase -eq 'pre' -or $Phase -eq 'post') {
  W ''
  W '--- §A 三程序身份与健康 ---'
  foreach ($p in 8090, 8091, 8092) {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
      $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
      $cl = (Get-CimInstance Win32_Process -Filter ("ProcessId=" + $conn.OwningProcess)).CommandLine
      W ("  {0} pid={1} 启动={2} 命令行={3}" -f $p, $conn.OwningProcess, $proc.StartTime.ToString('yyyy-MM-dd HH:mm:ss'), $cl)
    } else { W ("  {0} 未监听" -f $p) }
  }
  foreach ($u in "$plat/api/v1/health", "$mall/api/v1/health", "$gen/api/v1/scenarios") {
    try { $r = Invoke-WebRequest -Uri $u -TimeoutSec 8 -UseBasicParsing; W ("  GET {0} -> {1} ({2} B)" -f $u, $r.StatusCode, $r.RawContentLength) }
    catch { W ("  GET {0} -> 异常 {1}" -f $u, $_.Exception.Message) }
  }

  W ''
  W '--- §B 商城目录（生成器看到的顺序：GET /api/v1/mall/products，位置从 1 起）---'
  $login = Invoke-RestMethod -Uri "$mall/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
  $token = $login.data.token
  W ("  登录 code={0} role={1} token长度={2}" -f $login.code, $login.data.user.role, $token.Length)
  $prods = Invoke-RestMethod -Uri "$mall/api/v1/mall/products" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
  $list = if ($prods.data.items) { $prods.data.items } elseif ($prods.data.list) { $prods.data.list } else { $prods.data }
  W ("  目录返回件数 = " + @($list).Count)
  $pos = 0
  $pool = 40
  foreach ($p in $list) {
    $pos++
    $inv = Sql ("SELECT CONCAT(IFNULL(available_qty,'NULL'),'/',IFNULL(reserved_qty,'NULL'),'/',IFNULL(version,'-')) FROM mall_simulator.inventory WHERE product_id=" + $p.id + ";")
    $pip = if (@($inv).Count -gt 0) { @($inv)[0] } else { '无库存行' }
    $tag = if ($pos -le $pool) { '池内' } else { '池外' }
    W ("  #{0,2} product_id={1,-22} status={2,-9} 库存(avail/reserved/ver)={3,-12} {4}" -f $pos, $p.id, $p.status, $pip, $tag)
  }

  W ''
  W '--- §C 商城库存汇总 + 业务计数（前/后态对照用）---'
  Sql 'SELECT CONCAT(''products='',COUNT(*),'' total_avail='',SUM(available_qty),'' sold_out='',SUM(available_qty=0),'' min='',MIN(available_qty)) FROM mall_simulator.inventory;' | ForEach-Object { W ('  ' + $_) }
  Sql "SELECT CONCAT(t,'=',n) FROM (SELECT 'mall_order' t, COUNT(*) n FROM mall_simulator.mall_order UNION ALL SELECT 'order_item',COUNT(*) FROM mall_simulator.order_item UNION ALL SELECT 'payment',COUNT(*) FROM mall_simulator.payment UNION ALL SELECT 'refund',COUNT(*) FROM mall_simulator.refund UNION ALL SELECT 'refund_COMPLETED',COUNT(*) FROM mall_simulator.refund WHERE status='COMPLETED' UNION ALL SELECT 'mall_user',COUNT(*) FROM mall_simulator.mall_user UNION ALL SELECT 'event_outbox',COUNT(*) FROM mall_simulator.event_outbox) x;" | ForEach-Object { W ('  ' + $_) }
  Sql "SELECT CONCAT('outbox/',event_type,'=',COUNT(*)) FROM mall_simulator.event_outbox GROUP BY event_type ORDER BY event_type;" | ForEach-Object { W ('  ' + $_) }

  W ''
  W '--- §D 平台侧计数 + 落地区（时点口径）---'
  Sql "SELECT CONCAT(t,'=',n) FROM (SELECT 'ingestion_batch' t,COUNT(*) n FROM analytics_meta.ingestion_batch UNION ALL SELECT 'pipeline_run',COUNT(*) FROM analytics_meta.pipeline_run UNION ALL SELECT 'metric_snapshot',COUNT(*) FROM analytics_meta.metric_snapshot UNION ALL SELECT 'file_checkpoint',COUNT(*) FROM analytics_meta.file_checkpoint) x;" | ForEach-Object { W ('  ' + $_) }
  $le = Get-ChildItem (Join-Path $root 'landing\events') -File
  W ("  landing/events 文件数={0} bytes={1} 最新={2}" -f $le.Count, ($le | Measure-Object -Property Length -Sum).Sum, ($le | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name)

  W ''
  W '--- §E 生成器侧（generator_meta + 产物目录）---'
  Sql "SELECT CONCAT(t,'=',n) FROM (SELECT 'generation_plan' t,COUNT(*) n FROM generator_meta.generation_plan UNION ALL SELECT 'generation_run',COUNT(*) FROM generator_meta.generation_run UNION ALL SELECT 'generation_artifact',COUNT(*) FROM generator_meta.generation_artifact UNION ALL SELECT 'generation_event_stat',COUNT(*) FROM generator_meta.generation_event_stat UNION ALL SELECT 'generator_target',COUNT(*) FROM generator_meta.generator_target) x;" | ForEach-Object { W ('  ' + $_) }
  Sql 'SELECT CONCAT(''max_run_id='',MAX(id)) FROM generator_meta.generation_run;' | ForEach-Object { W ('  ' + $_) }
  $go = Join-Path $root 'synthetic-data-generator\generator-output'
  if (Test-Path $go) {
    $dirs = Get-ChildItem $go -Directory | Sort-Object LastWriteTime -Descending
    W ("  generator-output 运行目录数={0} 最新 3 个：" -f $dirs.Count)
    $dirs | Select-Object -First 3 | ForEach-Object { W ("    " + $_.Name + "  " + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) }
  } else { W '  generator-output 不存在' }
}

if ($Phase -eq 'plan') {
  W ''
  W '--- §F 目标探针（POST /api/v1/targets/118/test，真实登录 8090）---'
  try {
    $t = Invoke-RestMethod -Uri "$gen/api/v1/targets/118/test" -Method Post -ContentType 'application/json' -Body '{}' -TimeoutSec 30
    W ('  ' + ($t | ConvertTo-Json -Depth 6 -Compress))
  } catch { W ('  探针异常：' + $_.Exception.Message) }
  W ''
  W '--- §G 目标 118 定义 ---'
  Sql 'SELECT CONCAT(id,''|'',name,''|'',adapter_type,''|'',base_url,''|'',credential_ref,''|'',status) FROM generator_meta.generator_target WHERE id=118;' | ForEach-Object { W ('  ' + $_) }
}

if ($Phase -eq 'run') {
  W ''
  W '--- §H 发起运行（POST /api/v1/generation-runs）---'
  $body = '{"plan_id":"m1-4-u1u3-20260912","version":1}'
  W ('  请求体 = ' + $body)
  $t0 = Get-Date
  try {
    $r = Invoke-RestMethod -Uri "$gen/api/v1/generation-runs" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 120
    W ('  响应 = ' + ($r | ConvertTo-Json -Depth 8 -Compress))
    $rid = $r.data.id
    if (-not $rid) { $rid = $r.data.runId }
    W ('  run_id = ' + $rid)
    for ($i = 1; $i -le 40; $i++) {
      Start-Sleep -Seconds 3
      $g = Invoke-RestMethod -Uri ("$gen/api/v1/generation-runs/" + $rid) -TimeoutSec 30
      $d = $g.data
      W ("  [{0}] status={1} success={2} failed={3} elapsed={4:N1}s" -f $i, $d.status, $d.successCount, $d.failedCount, ((Get-Date) - $t0).TotalSeconds)
      if ($d.status -in 'SUCCESS', 'FAILED', 'CANCELLED') {
        W ('  终态响应全文 = ' + ($g | ConvertTo-Json -Depth 8 -Compress))
        break
      }
    }
  } catch { W ('  运行异常：' + $_.Exception.Message) }
}

[System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("[写出] " + $outFile + "  (" + $L.Count + " 行)")
