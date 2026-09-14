# 一键演示脚本：生成(8092)→发布/采集(8090→8091)→流水线(8091)→证据输出(8091)
#
# 【三程序边界，勿合并】本系统由三个**独立程序**构成，各自独立进程/端口/库/构建产物：
#   模拟商城        mall-simulator            :8090  库 mall_simulator
#   分析平台        analytics-server/platform-app :8091  库 analytics_meta + analytics_metric（**分析入口**）
#   合成数据生成器  synthetic-data-generator  :8092  库 generator_meta
# 端点归属的唯一依据：各程序自己的 @RequestMapping（见 docs/acceptance/docs-sync-3programs-20260911/README.md）。
# 本脚本的每一步只打它真正所属程序的那个 base URL，绝不把三个程序的端点混指向同一端口。
#
# 用法:
#   pwsh -File scripts/run-demo.ps1                          # 三个进程必须已启动（scripts/start-all.ps1）
#   pwsh -File scripts/run-demo.ps1 -Clean                   # 先清空演示数据（**直连商城库/商城 landing，仅商城侧**）
#   pwsh -File scripts/run-demo.ps1 -Days 3 -Users 60        # 自定义规模（仅影响生成步骤计划版本，见 [1]）
param(
  [string]$Mall      = 'http://127.0.0.1:8090',  # 模拟商城（参考数据源）
  [string]$Platform  = 'http://127.0.0.1:8091',  # 分析平台（采集/流水线/指标，**分析入口**）
  [string]$Generator = 'http://127.0.0.1:8092',  # 合成数据生成器（只负责造数）
  [int]$Days = 1,          # 默认 1 天窗口 ≈ 3.2 万事件（~3 分钟）；演示建议 ≤2 天
  [int]$Users = 60,
  [int]$Eps = 2,
  [double]$Conv = 0.05,
  [string]$Scenario = 'normal',
  [long]$Seed = 20260906,
  [switch]$Clean,
  # ── V25-S03 R-6：清场目标与账号必须显式给出（不再隐式 root / 隐式库名）────────
  #   整改前清场命令写死 `& mysql -uroot -N -B -e "DELETE FROM mall_simulator.event_outbox; …"`：
  #   账号写死 root、库名写死 mall_simulator、且只用 $env:MALL_DB_PASSWORD 一个门槛。
  #   现在：账号 + 库名都成为参数；目标库与账号必须命中白名单并有**显式确认**
  #   （-ConfirmCleanTarget），否则拒绝执行清场（不清场 ≠ 演示失败，脚本继续）。
  [string]$MallDbName = 'mall_simulator',
  [string]$MallDbUser = 'mall_app',
  [string]$MysqlExe = 'mysql',
  [switch]$ConfirmCleanTarget
)
$ErrorActionPreference = 'Stop'

# ── 0. 三程序健康检查（各打自己的探活端点，均属各自程序） ─────────────
# 探活端点归属：商城 GET /（SpaFallbackController.java:16）｜平台 GET /api/v1/metrics/health
#（MetricController.java:59）｜生成器 GET /api/v1/scenarios（ScenarioController.java:26）
$missing = @()
try { $null = Invoke-RestMethod "$Mall/" -TimeoutSec 5 }
catch { $missing += "模拟商城 $Mall（请先 pwsh -File scripts/start-all.ps1）" }
try { $null = Invoke-RestMethod "$Platform/api/v1/metrics/health" -TimeoutSec 5 }
catch { $missing += "分析平台 $Platform（请先 pwsh -File scripts/start-all.ps1）" }
try { $null = Invoke-RestMethod "$Generator/api/v1/scenarios" -TimeoutSec 5 }
catch { $missing += "合成数据生成器 $Generator（请先 pwsh -File scripts/start-all.ps1）" }
if ($missing.Count -gt 0) {
  $missing | ForEach-Object { Write-Host "未就绪: $_" }
  exit 1
}
Write-Host "[0] 三程序就绪: 商城 $Mall ｜ 平台 $Platform ｜ 生成器 $Generator"

# ── 0.1 两个程序各自登录（**令牌不通用**：商城 token 只认商城会话，平台 token 只认平台会话） ──
# 登录端点两边同形：AuthController.java:31,43（商城）/ AuthController.java:31,43（平台）
$mallLogin = Invoke-RestMethod -Method Post "$Mall/api/v1/auth/login" -ContentType 'application/json' `
  -Body '{"username":"admin","password":"admin123"}'
$mallAuth = @{ Authorization = "Bearer $($mallLogin.data.token)"; 'Content-Type' = 'application/json' }

$pfLogin = Invoke-RestMethod -Method Post "$Platform/api/v1/auth/login" -ContentType 'application/json' `
  -Body '{"username":"admin","password":"admin123"}'
$pfAuth = @{ Authorization = "Bearer $($pfLogin.data.token)"; 'Content-Type' = 'application/json' }
Write-Host "[0.1] 已登录：商城 $($mallLogin.data.user.realName)（$($mallLogin.data.user.role)）｜ 平台 $($pfLogin.data.user.realName)（$($pfLogin.data.user.role)）"

# ── 0.5 清场（可选；**只清商城侧**，不碰平台库与数仓） ────────────────
if ($Clean) {
  # 清场只清**商城侧**。平台侧没有"清空演示数据/重置采集断点"的端点（见下方 TODO(缺口)），
  # 本脚本**不**替平台清库——尤其不许凭空删平台库/数仓数据。
  # TODO(缺口): 平台侧无 reset/cleanup 端点（platform-app 全部 @RequestMapping 见证据 README §2.2）。
  #   平台库的采集断点表在 analytics-server/platform-app/src/main/resources/db/meta/V1__platform_ingestion.sql
  #   （:8 ingestion_batch、:24 ingestion_batch_file、:37 file_checkpoint、:45 quarantine_record）——
  #   它们**属于平台**（权威库是 analytics_meta），故本脚本的商城库 SQL 里**不得**出现这四张表：
  #   旧版曾写 `DELETE FROM mall_simulator.file_checkpoint; …`。⚠️ 实测（2026-09-11，只读）：
  #   mall_simulator 库里**确实存在**这四张同名表且部分非空（file_checkpoint=3 行、ingestion_batch=1、
  #   pipeline_run=10，共 30 张表中的平台侧遗留表，证据 README §5.7），所以旧语句**会真的删掉数据**
  #   ——只是删的是**平台侧遗留行**，而不是它声称的"演示数据"。这正是必须移除的理由。
  #   若确需重置平台断点，请由人工作为**单独步骤**在平台库（analytics_meta）执行。
  if (-not $env:MALL_DB_PASSWORD) {
    Write-Host '[0.5] 跳过清场：未提供 $env:MALL_DB_PASSWORD（清场是直连商城库的步骤，仅商城侧）'
  } elseif (-not $ConfirmCleanTarget) {
    # ── V25-S03 R-6：目标不明确即拒绝 ────────────────────────────────────
    # 整改前的门槛只有"有没有口令"一个：给上口令就删。库名写死在语句里、
    # 账号写死 root，脚本自己并不校验"我要删的到底是哪个库的哪张表"。
    # 现在要求显式 -ConfirmCleanTarget，并把目标与影响面**先打印出来**。
    Write-Host "[0.5] 跳过清场：未提供 -ConfirmCleanTarget（目标不明确即拒绝）。"
    Write-Host "      若要清场，请显式确认：pwsh -File scripts/run-demo.ps1 -Clean -ConfirmCleanTarget"
    Write-Host "      当前目标：库=$MallDbName 表=event_outbox 账号=$MallDbUser"
  } else {
    # 直连**商城库**清 event_outbox —— 这是商城**自有表**：
    #   mall-simulator/src/main/resources/db/migration/V1__init_mall.sql:116
    # ⚠️ 实测（2026-09-11，只读探测）：root **不是免密**（`mysql -uroot` 报 ERROR 1045），
    #   且 mall_simulator 库里**同时存在平台侧遗留表**（file_checkpoint/ingestion_batch/… ，
    #   旧单进程架构留下的，见证据 README §5.7）——那些表**一律不在这里删**。
    #   init-three-dbs.sql 授权的 mall_app 账号对本库无权限（README §5.8），故清库必须用有权限的账号。
    # ⚠️ 不许再吞错误（本泳道自己认定的缺陷同型）：旧写法 `… 2>$null` 会把 ERROR 1045（口令错）
    #   等失败全部隐藏，脚本却报告"已清场"。这里显式读退出码 + 回显真实删除行数，失败就如实说。
    #
    # ── V25-S03 R-6：写前校验（目标白名单 + 影响面预览）─────────────────
    # • 库名只允许商城自有库；平台库（analytics_meta/analytics_metric）与生成器库
    #   （generator_meta）**一律拒绝**——本脚本没有清它们的所有权（三程序边界）。
    # • 账号：保留"可用任意有权限账号"的能力，但 root 必须显式写出并确认。
    $allowedDbs = @('mall_simulator')
    # 只有走完「白名单 + 账号 + 影响面预览」三道关，才把 $cleanTargetOk 置为 $true；
    # landing 清理据此决定跑不跑（同一道门禁，不是第二套判据）。
    $cleanTargetOk = $false
    if ($allowedDbs -notcontains $MallDbName) {
      Write-Host ("[0.5] ⚠️ 拒绝清场：目标库 '{0}' 不在商城自有库白名单 {1} 内。" -f $MallDbName, ($allowedDbs -join ', '))
      Write-Host '      平台库/生成器库不归本脚本清理（三程序边界）；如需重置请由人在对应程序内单独执行。'
    } elseif ($MallDbUser -eq 'root') {
      Write-Host '[0.5] ⚠️ 拒绝清场：账号为 root。请改用本库的受限账号（如 -MallDbUser mall_app）。'
      Write-Host '      改写为 root 需要显式理由；当前脚本不接受隐式 root（V25-S03 R-6）。'
    } else {
      # 影响面预览：先看要删多少行，再删。预览失败就拒绝（不盲删）。
      $env:MYSQL_PWD = $env:MALL_DB_PASSWORD
      $previewSql = "SELECT COUNT(*) FROM $MallDbName.event_outbox;"
      $previewOut = & $MysqlExe "-u$MallDbUser" -N -B -e $previewSql 2>&1
      if ($LASTEXITCODE -ne 0) {
        Write-Host ("[0.5] ⚠️ 清场预览失败（mysql 退出码 {0}）：{1}" -f $LASTEXITCODE, (($previewOut | Out-String).Trim()))
        Write-Host '      预览不可得即不删除（V25-S03 R-6：清理范围必须先可预览）。清场未生效，脚本继续。'
      } else {
        $previewRows = (($previewOut | Select-Object -Last 1) -replace '\s', '')
        Write-Host ("[0.5] 待清目标预览：{0}.event_outbox（账号 {1}）当前 {2} 行，将全部删除。" -f $MallDbName, $MallDbUser, $previewRows)
        $delOut = & $MysqlExe "-u$MallDbUser" -N -B -e "DELETE FROM $MallDbName.event_outbox; SELECT ROW_COUNT();" 2>&1
        if ($LASTEXITCODE -ne 0) {
          Write-Host ("[0.5] ⚠️ 清 event_outbox **失败**（mysql 退出码 {0}）：{1}" -f $LASTEXITCODE, (($delOut | Out-String).Trim()))
          Write-Host '      常见原因：MALL_DB_PASSWORD 与商城库账号不符。清场未生效，脚本继续（不清场不等于演示会失败）。'
        } else {
          Write-Host ("[0.5] 已清商城 event_outbox：删除 {0} 行（商城自有表，仅 -Clean -ConfirmCleanTarget 时执行）" -f (($delOut | Select-Object -Last 1) -replace '\s', ''))
          $cleanTargetOk = $true
        }
      }
    }
    if ($cleanTargetOk) {
    # 清商城自己的 landing 产物目录。
    # ⚠️ 实测（2026-09-11，只读）：**商城当前配置写的不是** mall-simulator\landing（那个目录是空的），
    #   而是**仓库根** landing\events —— 依据：mall-simulator application.yml:33 `mall.landing.path` 默认
    #   `./landing`，而 scripts/start-all.ps1:48 以 `-WorkingDirectory $root`（仓库根）启动，
    #   故相对路径解析到 <仓库根>\landing；实证是根目录 landing\events\2026091122.jsonl 里
    #   `"source_system":"mock-mall"`（商城产物）。
    # ⚠️ 但同一个目录里**混有平台真实采过的数据**（landing\events 共 55 个文件，2026090509.jsonl 等，
    #   12–17 MB/个，2026-09-06 写入）——所以这里**不能**无脑 `Remove-Item *.jsonl`，
    #   否则会把平台侧真实 landing 数据一起删掉（那正是本泳道被禁止的动作）。
    # 处置：只删"最近 2 小时内新产生"的文件（演示刚造的数据），并对更早的文件**明确报告并跳过**。
    #   注意 2 小时窗口是保守启发式：若演示中断超过 2 小时，本次产生的文件也会落入"跳过"那一类
    #   —— 宁可少删（脚本会打印跳过数量），不可多删。
    # V25-S03 R-6：landing 清理同样受目标校验约束——只有走完上面的
    # 「目标白名单 + 账号校验 + -ConfirmCleanTarget + 影响面预览」才允许走到这里。
    # 清理范围仍然只看"最近 2 小时内产生的 *.jsonl"，更早的一律报告并跳过。
      $landingDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'landing\events'
      if (Test-Path $landingDir) {
        $cut = (Get-Date).AddHours(-2)
        $fresh = @(Get-ChildItem $landingDir -Filter '*.jsonl' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $cut })
        $old   = @(Get-ChildItem $landingDir -Filter '*.jsonl' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt $cut })
        if ($fresh.Count -gt 0) {
          Write-Host ("[0.5] landing 清理预览：将删除 {0} 个最近 2 小时内产生的文件（{1}）" -f $fresh.Count, $landingDir)
        }
        $fresh | Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host ("[0.5] 已清本次演示新产生的 landing 文件 {0} 个（{1}）" -f $fresh.Count, $landingDir)
        if ($old.Count -gt 0) {
          Write-Host ("[0.5] ⚠️ 跳过 {0} 个更早的 landing 文件（疑似平台已采数据，删了可能破坏平台侧证据）" -f $old.Count)
          Write-Host '      如需彻底重置，请人工确认这些文件确实无用后再单独删除——脚本不替你做这个决定。'
        }
      } else {
        Write-Host ("[0.5] 未找到 landing 目录 {0}（商城可能尚未产出）" -f $landingDir)
      }
      Write-Host '[0.5] 商城侧演示数据已清空（event_outbox + landing 新文件）；平台库/数仓未清（无此端点）'
    }
  }
}

# ── 1. 生成（生成器 8092；**只负责造数**） ─────────────────────────────
# TODO(缺口): 原脚本调用 POST http://127.0.0.1:8090/api/v1/generator/runs —— 该端点已不存在。
#   • M1-7 已把生成器整体移出商城：mall-simulator 中 /api/v1/generator/** 已下线
#     （mall-simulator/src/main/java/com/graduation/mall/auth/AuthInterceptor.java:35-37 注释即在说明此事），
#     实测带 token 打 8090 的 /api/v1/generator/runs 与 /api/v1/generator/scenarios 均为 404。
#   • 生成器（8092）的真实现存端点是 POST /api/v1/generation-runs
#     （synthetic-data-generator/src/main/java/com/graduation/generator/web/GenerationRunController.java:25,35），
#     但它**只接受 {plan_id, version}**（GeneratorApiDtos.java:31-34，required=[plan_id,version]），
#     不接受 userCount/eventsPerSecond/baseConversionRate/startTime/... 这套旧参数体。
#   • **计划版本只能由生成器 CLI 创建**——冻结契约的七个端点里没有"创建计划"入口
#     （GeneratorCli.java:29-31 原话）。故本步骤无法只靠 HTTP 完成，必须在生成器侧先跑 CLI 两跳：
#       java -jar synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar \
#         --generator.cli=plan-append --plan-id=demo --scenario=normal --seed=<Seed> \
#         --start=<start>T09:00:00 --end=<end>T18:00:00 --event-count=<事件预算>
#       java -jar ... --generator.cli=run-start --plan-id=demo --version=<上一步输出的 version>
#     注意参数语义已变：旧脚本的 eventsPerSecond 对应 CLI 的 --rate（且**文件模式不生效**，
#     FileModeGenerationEngine.java:59）；旧脚本没有"总事件数"上限，新 CLI 是 --event-count（必填）。
#   本步骤保留为显式缺口：请按上面两跳造数后，把下面两行换成读取产物的只读校验，再继续 [2]。
# $g = Invoke-RestMethod -Method Post "$Generator/api/v1/generation-runs" -Headers $pfAuth `
#   -Body (@{ plan_id = 'demo'; version = 1 } | ConvertTo-Json) -ContentType 'application/json'
$end = (Get-Date).ToString('yyyy-MM-dd')
$start = (Get-Date).AddDays(-$Days + 1).ToString('yyyy-MM-dd')
Write-Host "[1] TODO(缺口): 生成步骤需在生成器侧用 CLI 完成（plan-append → run-start），参数窗口 $start → $end，"
Write-Host "    事件预算与 --rate 见上方说明；本脚本不伪造该调用。生成后产物写入生成器自有目录（synthetic=true）。"

# ── 2. 等待 Outbox 发布 → 采集（发布在商城 8090，采集在平台 8091） ──────
# GET  http://127.0.0.1:8090/api/v1/mall/outbox/status  ← OutboxController.java:21,27（商城侧）
# POST http://127.0.0.1:8090/api/v1/mall/outbox/publish ← OutboxController.java:21,40（商城侧）
$wait = (Get-Date).AddMinutes(5)
do {
  Start-Sleep -Seconds 5
  $st = Invoke-RestMethod "$Mall/api/v1/mall/outbox/status" -Headers $mallAuth
  if ($st.data.pendingCount -gt 0) {
    # 商城 Outbox 本就有定时发布（mall-simulator application.yml:34-36 poll-seconds: 3 / batch-size: 5000），
    # 这里显式推一次只是不等定时轮、立即排空；不是"必须手动发布"。
    $null = Invoke-RestMethod -Method Post "$Mall/api/v1/mall/outbox/publish" -Headers $mallAuth
  }
} while ($st.data.pendingCount -gt 0 -and (Get-Date) -lt $wait)
if ($st.data.pendingCount -gt 0) { Write-Host '[2] 警告：outbox 发布超时，仍有积压'; exit 2 }

# POST http://127.0.0.1:8091/api/v1/ingestion/runs ← IngestionController.java:25,37（平台侧）
# 返回体 RunResult 字段（connection-ingestion/.../IngestionService.java:67-70）：
#   batchId/batchNo/status/recordCount/quarantineCount/errorCount/fileCount/…/noNewData
$ing = Invoke-RestMethod -Method Post "$Platform/api/v1/ingestion/runs" -Headers $pfAuth
Write-Host ("[2] 采集完成（平台）：批次 {0}（{1}）{2} 行，隔离 {3} 行" -f $ing.data.batchNo, $ing.data.status, $ing.data.recordCount, $ing.data.quarantineCount)
if ($ing.data.noNewData) {
  # noNewData=true ⇒ 本轮没读到任何新字节（IngestionService.java:63-65 明示"数据源未产出"），
  # 不是"采集成功但 0 行"。若这里为 true，后面的流水线会 WAIT_LANDING/空跑，演示会失真，故直接停。
  Write-Host '[2] 停止：noNewData=true（本次无新数据，商城未产出 landing）——请先完成步骤 [1]'
  exit 4
}

# ── 3. 流水线（平台 8091；**异步**：POST 立即返回 PENDING，必须轮询） ────
# POST http://127.0.0.1:8091/api/v1/pipeline-runs      ← PipelineController.java:31,45（平台侧）
# GET  http://127.0.0.1:8091/api/v1/pipeline-runs/{id} ← PipelineController.java:31,55（平台侧）
$p = Invoke-RestMethod -Method Post "$Platform/api/v1/pipeline-runs" -Headers $pfAuth -Body (@{ runtimeProfileId=1; pipelineCode='DAILY_CORE';
  businessTime = "$($end)T00:00:00"; sourceDataVersion = "demo-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" } | ConvertTo-Json) -ContentType 'application/json'
$runId = $p.data.runId
# 八阶段权威顺序（PipelineService.java:205-207 STAGE_ORDER）：WAIT_LANDING→INIT_SCHEMA→LOAD_ODS→BUILD_DWD
#   →BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC —— 共 **8** 个，不是 7 个。
$wait = (Get-Date).AddMinutes(30)
do {
  Start-Sleep -Seconds 5
  $p = Invoke-RestMethod "$Platform/api/v1/pipeline-runs/$runId" -Headers $pfAuth
} while ($p.data.status -in @('PENDING','RUNNING') -and (Get-Date) -lt $wait)
$okStages = @($p.data.stages | Where-Object { $_.status -eq 'SUCCESS' }).Count
Write-Host ("[3] 流水线 runId={0}：{1}（阶段 {2}/8 成功）{3}" -f $runId, $p.data.status, $okStages,
  $(if ($p.data.errorCode) { "错误码=$($p.data.errorCode)" } else { '' }))
if ($p.data.status -ne 'SUCCESS') { exit 3 }

# ── 4. 证据输出（平台 8091） ───────────────────────────────────────────
# GET http://127.0.0.1:8091/api/v1/metrics/overview ← MetricController.java:28,36（平台侧）
$ov = Invoke-RestMethod "$Platform/api/v1/metrics/overview" -Headers $pfAuth
$snap = ($ov.data | Select-Object -First 1)
Write-Host "[4] 证据："
Write-Host "    快照: $($snap.snapshotId)"
$ov.data | ForEach-Object { "    $($_.metricCode) = $($_.value) $($_.unit)" } | Select-Object -First 8
Write-Host "[OK] 演示数据就绪：分析平台看板 http://127.0.0.1:8091/ 可直接查看（商城前端 8090 只演示经营行为，不展示分析看板）"
