# 一键演示脚本（阶段10）：生成→发布→采集→流水线→证据输出
# 用法:
#   pwsh -File scripts/run-demo.ps1                          # 后端必须已启动(8090)
#   pwsh -File scripts/run-demo.ps1 -Clean                   # 先清空演示数据
#   pwsh -File scripts/run-demo.ps1 -Days 3 -Users 60        # 自定义规模(3天×60用户)
param(
  [string]$Base = 'http://127.0.0.1:8090',
  [int]$Days = 1,          # 默认 1 天窗口 ≈ 3.2 万事件（~3 分钟）；演示建议 ≤2 天
  [int]$Users = 60,
  [int]$Eps = 2,
  [double]$Conv = 0.05,
  [string]$Scenario = 'normal',
  [long]$Seed = 20260906,
  [switch]$Clean
)
$ErrorActionPreference = 'Stop'

# ── 0. 健康检查 ───────────────────────────────────────────────
try { $null = Invoke-RestMethod "$Base/api/v1/metrics/health" -TimeoutSec 5 }
catch { Write-Host "后端未就绪: $Base（请先在 mall-simulator 目录 mvn spring-boot:run）"; exit 1 }
Write-Host "[0] 后端就绪: $Base"

# ── 0.5 清场（可选） ──────────────────────────────────────────
if ($Clean) {
  $env:MYSQL_PWD = if ($env:MYSQL_PWD) { $env:MYSQL_PWD } else { $env:MALL_DB_PASSWORD }
  mysql -uroot -e "DELETE FROM mall_simulator.event_outbox;" 2>$null
  Get-ChildItem 'mall-simulator\landing\events' -Filter '*.jsonl' -ErrorAction SilentlyContinue | Remove-Item -Force
  Write-Host '[0.5] 演示数据已清空'
}

# ── 1. 生成（多日窗口） ────────────────────────────────────────
$end = (Get-Date).ToString('yyyy-MM-dd')
$start = (Get-Date).AddDays(-$Days + 1).ToString('yyyy-MM-dd')
$body = @{ userCount = $Users; productCount = 0; eventsPerSecond = $Eps; baseConversionRate = $Conv;
           startTime = "$($start)T09:00:00"; endTime = "$($end)T18:00:00"; randomSeed = $Seed;
           dirtyDataRate = 0; scenario = $Scenario } | ConvertTo-Json
$t0 = Get-Date
$g = Invoke-RestMethod -Method Post "$Base/api/v1/generator/runs" -Body $body -ContentType 'application/json'
Write-Host ("[1] 生成 {0} 天 × {1} 事件（{2}s）：支付 {3} 单，GMV {4}" -f $Days, $g.data.totalEvents,
  [Math]::Round(((Get-Date)-$t0).TotalSeconds,1), $g.data.ordersPaid, $g.data.gmv)

# ── 2. 等待 Outbox 发布 → 采集 ────────────────────────────────
$wait = (Get-Date).AddMinutes(5)
do { Start-Sleep -Seconds 5; $st = Invoke-RestMethod "$Base/api/v1/mall/outbox/status" } while ($st.data.pendingCount -gt 0 -and (Get-Date) -lt $wait)
if ($st.data.pendingCount -gt 0) { Write-Host '[2] 警告：outbox 发布超时，仍有积压'; exit 2 }
$ing = Invoke-RestMethod -Method Post "$Base/api/v1/ingestion/runs"
Write-Host ("[2] 采集完成：{0} 行（{1}，隔离 {2}）" -f $ing.data.recordCount, $ing.data.status, $ing.data.quarantineCount)

# ── 3. 流水线（最后一天） ──────────────────────────────────────
$p = Invoke-RestMethod -Method Post "$Base/api/v1/pipeline-runs" -Body (@{ runtimeProfileId=1; pipelineCode='DAILY_CORE';
  businessTime = "$($end)T00:00:00"; sourceDataVersion = "demo-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" } | ConvertTo-Json) -ContentType 'application/json'
Write-Host ("[3] 流水线：{0}（阶段 {1}/7 成功）{2}" -f $p.data.status,
  ($p.data.stages | Where-Object { $_.status -eq 'SUCCESS' }).Count,
  $(if ($p.data.errorCode) { "错误码=$($p.data.errorCode)" } else { '' }))
if ($p.data.status -ne 'SUCCESS') { exit 3 }

# ── 4. 证据输出 ────────────────────────────────────────────────
$ov = Invoke-RestMethod "$Base/api/v1/metrics/overview"
$snap = ($ov.data | Select-Object -First 1)
Write-Host "[4] 证据："
Write-Host "    快照: $($snap.snapshotId)"
$ov.data | ForEach-Object { "    $($_.metricCode) = $($_.value) $($_.unit)" } | Select-Object -First 8
Write-Host "[OK] 演示数据就绪：前端 http://127.0.0.1:5173/overview 可直接查看"