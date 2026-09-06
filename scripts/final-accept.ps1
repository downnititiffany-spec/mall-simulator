# 最终验收快照（单进程交付形态）：登录→生成→采集→流水线→指标→AI→决策→商城
param(
  [string]$Base = 'http://127.0.0.1:8090',
  [string]$DbPassword = $env:MALL_DB_PASSWORD
)
$ErrorActionPreference = 'Stop'
if (-not $DbPassword) { Write-Host '缺少数据库密码（-DbPassword 或环境变量 MALL_DB_PASSWORD）'; exit 1 }
$env:MALL_DB_PASSWORD = $DbPassword
$pass = 0; $fail = 0
function Check($name, $cond) {
  if ($cond) { $script:pass++; Write-Host "  [PASS] $name" }
  else { $script:fail++; Write-Host "  [FAIL] $name" }
}

Write-Host '=== 最终验收快照 ==='
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Duser.language=en'
$jar = Get-ChildItem 'D:\Develop_code\GraduationProject\mall-simulator\target\*.jar' | Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
# 1. 启动单进程 jar
# 前置：清理演示事件与采集断点（保证可重复运行的验收语义；断点续采本身由别处测试覆盖）
Get-ChildItem 'D:\Develop_code\GraduationProject\mall-simulator\landing\events' -Filter '*.jsonl' -ErrorAction SilentlyContinue | Remove-Item -Force
$env:MYSQL_PWD = if ($env:MALL_DB_PASSWORD) { $env:MALL_DB_PASSWORD } else { '' }
mysql -uroot -e "DELETE FROM mall_simulator.event_outbox; DELETE FROM mall_simulator.file_checkpoint; DELETE FROM mall_simulator.ingestion_batch; DELETE FROM mall_simulator.ingestion_batch_file; DELETE FROM mall_simulator.quarantine_record;" 2>$null
Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName) -WorkingDirectory (Split-Path $jar.FullName) -WindowStyle Hidden -RedirectStandardOutput "$env:TEMP\final-accept.log" -RedirectStandardError "$env:TEMP\final-accept.log.err"
$ready = $false
foreach ($t in 1..40) { Start-Sleep -Seconds 2; try { $r = Invoke-WebRequest -Uri "$Base/api/v1/metrics/health" -TimeoutSec 3 -UseBasicParsing; if ($r.StatusCode -eq 200) { $ready = $true; break } } catch {} }
Check '1. 单进程 jar 启动（:8090）' $ready

$h = @{ 'Content-Type' = 'application/json' }
# 2. 登录（三类角色抽查）
$admin = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
Check '2. admin 登录' ($admin.data.user.role -eq 'admin')
$h.Authorization = "Bearer $($admin.data.token)"

# 3. 一键演示数据（生成 2 小时窗口小数据 → 发布 → 采集）
$g = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/generator/runs" -Headers $h -Body '{"userCount":20,"productCount":0,"eventsPerSecond":1,"baseConversionRate":0.05,"startTime":"2026-09-05T10:00:00","endTime":"2026-09-05T12:00:00","randomSeed":909,"dirtyDataRate":0,"scenario":"normal"}'
Check '3. 生成小数据' ($g.data.totalEvents -gt 500)
$wait = (Get-Date).AddSeconds(180)
do { Start-Sleep -Seconds 5; $st = Invoke-RestMethod -Uri "$Base/api/v1/mall/outbox/status" -Headers $h } while ($st.data.pendingCount -gt 0 -and (Get-Date) -lt $wait)
Check '4. Outbox 发布排空' ($st.data.pendingCount -eq 0)
$ing = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/ingestion/runs" -Headers $h
Check '5. 采集完成' ($ing.data.recordCount -gt 0)

# 4. 流水线 + 指标
$p = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/pipeline-runs" -Headers $h -Body '{"runtimeProfileId":1,"pipelineCode":"DAILY_CORE","businessTime":"2026-09-05T00:00:00","sourceDataVersion":"final-accept-1"}'
Check '6. 流水线 7 阶段成功' ($p.data.status -eq 'SUCCESS' -and $p.data.stages.Count -eq 7)
$ov = Invoke-RestMethod -Uri "$Base/api/v1/metrics/overview" -Headers $h
Check '7. 指标发布（overview 非空）' ($ov.data.Count -gt 5)

# 5. AI 问答（降级模式）
$ai = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/ai/queries" -Headers $h -Body '{"question":"最新一期转化漏斗各阶段人数？","timeRange":"近30天"}'
Check '8. AI 问答执行' ($ai.data.query.status -eq 'EXECUTED' -or $ai.data.query.status -eq 'REPAIRED')

# 6. 决策列表 + 商城一单
$d = Invoke-RestMethod -Uri "$Base/api/v1/decisions?limit=5" -Headers $h
Check '9. 决策中心可查' ($null -ne $d.code)
$u = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/mall/users" -Headers $h -Body '{"ageGroup":"25-34","cityLevel":"1","memberLevel":"gold"}'
$o = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/mall/orders" -Headers $h -Body "{`"userId`":`"$($u.data.userId)`",`"items`":[{`"productId`":1001,`"quantity`":1}]}"
Invoke-RestMethod -Method Post -Uri "$Base/api/v1/mall/orders/$($o.data.orderId)/pay" -Headers $h -Body "{`"userId`":`"$($u.data.userId)`"}" | Out-Null
$orders = Invoke-RestMethod -Uri "$Base/api/v1/mall/orders?userId=$($u.data.userId)" -Headers $h
Check '10. 商城下单-支付闭环' ($orders.data[0].status -eq 'PAID')

# 7. 角色隔离复查
$op = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"operator","password":"operator123"}'
try { Invoke-RestMethod -Uri "$Base/api/v1/admin/users" -Headers @{ Authorization = "Bearer $($op.data.token)" } | Out-Null; Check '11. 角色隔离(403)' $false }
catch { Check '11. 角色隔离(operator→admin API 403)' ($_.Exception.Response.StatusCode.value__ -eq 403) }

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*mall-simulator*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host "=== 验收结果: PASS=$pass FAIL=$fail ==="
if ($fail -gt 0) { exit 1 }