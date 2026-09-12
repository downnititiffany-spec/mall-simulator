# 最终验收快照（**三个独立程序**形态）
#   :8090 模拟商城 mall-simulator（参考数据源）｜:8091 分析平台 platform-app（分析入口）｜:8092 合成数据生成器
#
# 与旧版的区别（旧版已废弃，勿回退）：旧版把商城 jar 当"单进程交付形态"启动，并把平台端点/生成器端点
# 都打向 8090 —— 那是已废弃的单进程架构，在本仓库当前实现下**必然失败**（商城没有那些路由）。
# 现在本脚本**不启动也不停止任何进程**：只对已在运行的三程序做验收（启动见 scripts/start-all.ps1）。
# 端点归属依据 = 各程序自身的 @RequestMapping，清单见 docs/acceptance/docs-sync-3programs-20260911/README.md。
#
# 用法:
#   pwsh -File scripts/final-accept.ps1
#   pwsh -File scripts/final-accept.ps1 -BusinessDate 2026-09-01   # 换流水线业务日
# 前置：三程序已启动（pwsh -File scripts/start-all.ps1）且**已有可采集的来源数据**——
#       生成步骤需先在生成器侧用 CLI 建计划版本（见下方 [4] 的 TODO(缺口)），本脚本不自造数据、不清理数据。
param(
  [string]$Mall     = 'http://127.0.0.1:8090',
  [string]$Platform = 'http://127.0.0.1:8091',
  [string]$Generator = 'http://127.0.0.1:8092',
  [string]$BusinessDate = '2026-09-05'   # 流水线业务日（与验收用小数据窗口一致）
)
$ErrorActionPreference = 'Stop'
$pass = 0; $fail = 0; $gap = 0
function Check($name, $cond) {
  if ($cond) { $script:pass++; Write-Host "  [PASS] $name" }
  else { $script:fail++; Write-Host "  [FAIL] $name" }
}
# 缺口 = 已知的"当前架构下该步骤无端点可用"。它不是"跑失败"，也不能记成 PASS（不伪造通过），
# 单独计数并在结尾显式列出，退出码单独一档（2），便于区分"环境坏了"与"脚本与架构不同步"。
function Gap($name) { $script:gap++; Write-Host "  [GAP ] $name" }

Write-Host '=== 最终验收快照（三程序独立形态）==='
Write-Host "[配置] 商城 $Mall ｜ 平台 $Platform ｜ 生成器 $Generator"

# ── 0. 三程序就绪（各打自己的探活端点） ────────────────────────────────
function Test-Endpoint($url) {
  try { $r = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing; return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) }
  catch { return $false }
}
$ready = @{ mall = $false; platform = $false; generator = $false }
foreach ($t in 1..20) {
  $ready.mall      = Test-Endpoint "$Mall/"
  $ready.platform  = Test-Endpoint "$Platform/api/v1/metrics/health"
  $ready.generator = Test-Endpoint "$Generator/api/v1/scenarios"
  if ($ready.mall -and $ready.platform -and $ready.generator) { break }
  Start-Sleep -Seconds 2
}
Check '1. 模拟商城独立就绪（:8090）' $ready.mall
Check '2. 分析平台独立就绪（:8091，/api/v1/metrics/health）' $ready.platform
Check '3. 合成数据生成器独立就绪（:8092，/api/v1/scenarios）' $ready.generator

# ── 1. 构建产物存在性（三份独立产物，只读检查，不启动） ─────────────────
$repo = Split-Path -Parent $PSScriptRoot
$jarMall = Get-ChildItem (Join-Path $repo 'mall-simulator\target\*.jar') -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
$jarPf = Get-ChildItem (Join-Path $repo 'analytics-server\platform-app\target\*.jar') -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
$jarGen = Get-ChildItem (Join-Path $repo 'synthetic-data-generator\target\*.jar') -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
Check '4. 三份独立构建产物齐备（商城/平台/生成器 jar）' ($null -ne $jarMall -and $null -ne $jarPf -and $null -ne $jarGen)
if ($jarMall) { Write-Host "      商城 jar : $($jarMall.Name) ($($jarMall.LastWriteTime))" }
if ($jarPf)   { Write-Host "      平台 jar : $($jarPf.Name) ($($jarPf.LastWriteTime))" }
if ($jarGen)  { Write-Host "      生成器 jar: $($jarGen.Name) ($($jarGen.LastWriteTime))" }

# ── 2. 商城侧数据边界（**本脚本只读，不清理任何数据**） ─────────────────
# 旧版在这里直连商城库做 DELETE（清 event_outbox / file_checkpoint / …）。现已**移出验收脚本**，原因有二：
#  ① 语义矛盾：验收脚本先清空来源数据、再断言"采集 recordCount>0"，自己把前提删了；
#     清场是**演示准备**动作，只应出现在 run-demo.ps1 -Clean。
#  ② 归属错误：旧 SQL 删的 file_checkpoint/ingestion_batch/ingestion_batch_file/quarantine_record
#     **不属于商城库**——它们由平台元库脚本创建
#     （analytics-server/platform-app/src/main/resources/db/meta/V1__platform_ingestion.sql:8,24,37,45），
#     而 mall-simulator/src/main/resources/db/migration/*.sql 里 grep 这四张表 **0 命中**。
#     （确属商城的只有 event_outbox：V1__init_mall.sql:116。）
# 平台侧的采集断点若确需重置，请由人工作为**单独步骤**在平台库执行——本脚本不碰任何库。
Write-Host '  [INFO] 本脚本不清理任何数据（清场只在 run-demo.ps1 -Clean，且仅商城侧）'

# ── 3. 登录（两个程序各取各的令牌；令牌不通用） ─────────────────────────
$h = @{ 'Content-Type' = 'application/json' }   # 平台
$hm = @{ 'Content-Type' = 'application/json' }  # 商城
$admin = Invoke-RestMethod -Method Post -Uri "$Platform/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
Check '5. 平台 admin 登录' ($admin.data.user.role -eq 'admin')
$h.Authorization = "Bearer $($admin.data.token)"
$mAdmin = Invoke-RestMethod -Method Post -Uri "$Mall/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
Check '6. 商城 admin 登录（独立会话）' ($mAdmin.data.user.role -eq 'admin')
$hm.Authorization = "Bearer $($mAdmin.data.token)"

# ── 4. 造数（生成器 8092） ─────────────────────────────────────────────
# TODO(缺口): 生成器现存端点是 POST /api/v1/generation-runs，且**只接受 {plan_id, version}**；
#   计划版本只能由生成器 CLI 创建（无 HTTP 入口）。旧脚本的 /api/v1/generator/runs + 旧参数体已不存在。
#   因此这一步不可能只用本脚本走通，必须在跑本脚本**之前**先执行（生成器侧 CLI 两跳）：
#     java -jar synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar \
#       --generator.cli=plan-append --plan-id=final-accept --scenario=normal --seed=909 \
#       --start=${BusinessDate}T10:00:00 --end=${BusinessDate}T12:00:00 --event-count=1000
#     java -jar ... --generator.cli=run-start --plan-id=final-accept --version=<上一步输出>
#   校验改为**只读**：确认生成器里已有可用运行（GET /api/v1/generation-runs/{id}）。
Gap '7. 造数步骤：生成器无 HTTP 入口创建计划版本，需先用 CLI 两跳预置（详见上方 TODO(缺口)），本脚本不伪造该调用'

# ── 5. Outbox 发布 + 采集（发布在商城，采集在平台） ────────────────────
$wait = (Get-Date).AddSeconds(180)
do {
  Start-Sleep -Seconds 5
  $st = Invoke-RestMethod -Uri "$Mall/api/v1/mall/outbox/status" -Headers $hm
  if ($st.data.pendingCount -gt 0) { $null = Invoke-RestMethod -Method Post -Uri "$Mall/api/v1/mall/outbox/publish" -Headers $hm }
} while ($st.data.pendingCount -gt 0 -and (Get-Date) -lt $wait)
Check '8. 商城 Outbox 发布排空（:8090）' ($st.data.pendingCount -eq 0)
$ing = Invoke-RestMethod -Method Post -Uri "$Platform/api/v1/ingestion/runs" -Headers $h
# noNewData 是独立信号：IngestionService.java:63-65 明示"本次没有读到任何新字节（数据源未产出）"，
# 不能只看 recordCount>0 就判"采集链路正常"。
Check '9. 平台采集完成（:8091，recordCount>0 且 noNewData=false）' ($ing.data.recordCount -gt 0 -and -not $ing.data.noNewData)

# ── 6. 流水线 + 指标（平台） ───────────────────────────────────────────
$body = @{ runtimeProfileId = 1; pipelineCode = 'DAILY_CORE'
           businessTime = "${BusinessDate}T00:00:00"; sourceDataVersion = 'final-accept-1' } | ConvertTo-Json
$p = Invoke-RestMethod -Method Post -Uri "$Platform/api/v1/pipeline-runs" -Headers $h -Body $body -ContentType 'application/json'
$runId = $p.data.runId
# POST 立即返回 PENDING（PipelineService.java:165-167「立即返回 PENDING taskId，计算链异步执行」），必须轮询。
$wait = (Get-Date).AddMinutes(30)
do {
  Start-Sleep -Seconds 5
  $p = Invoke-RestMethod -Uri "$Platform/api/v1/pipeline-runs/$runId" -Headers $h
} while ($p.data.status -in @('PENDING','RUNNING') -and (Get-Date) -lt $wait)
# 八阶段权威顺序：PipelineService.java:205-207（WAIT_LANDING/INIT_SCHEMA/LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS/QUALITY_CHECK/PUBLISH_METRIC）
$stageCount = @($p.data.stages).Count
$statuses = @($p.data.stages | ForEach-Object { "$($_.stageCode)=$($_.status)" })
Check '10. 流水线 8 阶段全部 SUCCESS' ($p.data.status -eq 'SUCCESS' -and $stageCount -eq 8)
Write-Host "      runId=$runId status=$($p.data.status) stages=$stageCount"
Write-Host "      $($statuses -join ' | ')"
$ov = Invoke-RestMethod -Uri "$Platform/api/v1/metrics/overview" -Headers $h
Check '11. 指标发布（平台 overview 非空）' ($ov.data.Count -gt 5)

# ── 7. AI 问答（平台，降级模式） ───────────────────────────────────────
try {
  $ai = Invoke-RestMethod -Method Post -Uri "$Platform/api/v1/ai/queries" -Headers $h -Body '{"question":"最新一期转化漏斗各阶段人数？","timeRange":"近30天"}'
  Check '12. AI 问答执行（EXECUTED/REPAIRED/REJECTED 均为真实状态）' ($ai.data.query.status -in @('EXECUTED','REPAIRED','REJECTED'))
} catch { Check '12. AI 问答执行（EXECUTED/REPAIRED/REJECTED 均为真实状态）' $false }

# ── 8. 决策列表 + 商城一单（决策在平台，下单在商城） ────────────────────
# 商城请求体依据 MallDtos.java：CreateUserReq(:19-23) ageGroup/cityLevel/memberLevel；
# OrderCreateReq(:31-34) userId+items；OrderItemReq(:36-39) productId+quantity；OrderPayReq(:41-43) userId。
# ⚠️ userId 在这两个 DTO 里是 **Long**，而 /users 返回的 userId 是**字符串**（MallController.java:47
# 原话"ID 超 JS 安全整数，统一字符串返回"）⇒ 必须 [long] 显式转换，否则模型绑定可能失败。
$d = Invoke-RestMethod -Uri "$Platform/api/v1/decisions?limit=5" -Headers $h
Check '13. 平台决策中心可查（:8091）' ($null -ne $d.code)
$u = Invoke-RestMethod -Method Post -Uri "$Mall/api/v1/mall/users" -Headers $hm -Body '{"ageGroup":"25-34","cityLevel":"1","memberLevel":"gold"}'
$uid = [long]$u.data.userId
$prod = Invoke-RestMethod -Uri "$Mall/api/v1/mall/products" -Headers $hm
$pid = [long]$prod.data[0].id     # 用真实存在的商品 id，不硬编码（硬编码 1001 依赖种子数据）
$o = Invoke-RestMethod -Method Post -Uri "$Mall/api/v1/mall/orders" -Headers $hm -Body (@{ userId = $uid; items = @(@{ productId = $pid; quantity = 1 }) } | ConvertTo-Json -Depth 5)
Invoke-RestMethod -Method Post -Uri "$Mall/api/v1/mall/orders/$($o.data.orderId)/pay" -Headers $hm -Body (@{ userId = $uid } | ConvertTo-Json) | Out-Null
$orders = Invoke-RestMethod -Uri "$Mall/api/v1/mall/orders?userId=$uid" -Headers $hm
Check '14. 商城下单-支付闭环（:8090）' ($orders.data[0].status -eq 'PAID')

# ── 9. 角色隔离复查（平台） ────────────────────────────────────────────
$op = Invoke-RestMethod -Method Post -Uri "$Platform/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"operator","password":"operator123"}'
try { Invoke-RestMethod -Uri "$Platform/api/v1/admin/users" -Headers @{ Authorization = "Bearer $($op.data.token)" } | Out-Null; Check '15. 角色隔离(operator→admin API 403)' $false }
catch { Check '15. 角色隔离(operator→admin API 403)' ($_.Exception.Response.StatusCode.value__ -eq 403) }

# 旧版在此处 `Stop-Process` 掉商城 java 进程。三程序形态下**不停止任何进程**：
# 停谁、何时停由运维按 §3.4-1 独立启停决定，验收脚本无权替人关服务。
Write-Host "=== 验收结果: PASS=$pass FAIL=$fail GAP=$gap ==="
if ($gap -gt 0) { Write-Host "注意：有 $gap 项缺口（脚本步骤与当前架构不同步，未伪造通过）；详见 docs/acceptance/docs-sync-3programs-20260911/README.md 缺口清单" }
if ($fail -gt 0) { exit 1 }
if ($gap -gt 0) { exit 2 }
