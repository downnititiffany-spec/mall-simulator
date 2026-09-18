<#
Stage 7 HTTP 真实链隔离入口（3307 only）。

目标：
  1) 只启动 analytics platform（8091），数据面严格指向 RunId 的 analytics_meta / analytics_metric；
  2) landing / Spark warehouse / metastore / metric staging 全部落到 target/v25-it/<RunId>/http；
  3) 通过真实 HTTP API 完成 runtime profile 更新、test/activate、ingestion、pipeline；
  4) 任一步失败都留下脱敏证据并退出，不回退 3306、不碰正式库。

凭据只从当前进程环境读取：
  V25_IT_META_PASSWORD
  V25_IT_METRIC_PUBLISH_PASSWORD

退出码：
  0 = HTTP preflight + ingestion + pipeline 全部通过
  2 = HTTP/业务链未达成功态
  5 = 执行前安全门禁拒绝
  7 = runtime profile test/activate 或 pipeline 运行时失败
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [switch]$Confirm,
  [switch]$DryRun,
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$BusinessTime = '2026-09-01T00:00:00',
  [string]$GoldenDataset = 'tests/golden-dataset/events/golden-20260901-positive.jsonl',
  [string]$SparkSubmitPath = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd',
  [int]$PipelineTimeoutSec = 600,
  [int]$PollSec = 3
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'isolation-naming.ps1')

function Fail([int]$Code, [string]$Message) {
  Write-Host ("[REFUSE exit={0}] {1}" -f $Code, $Message)
  exit $Code
}

function Mask-State([string]$Name) {
  $v = [Environment]::GetEnvironmentVariable($Name, 'Process')
  if ([string]::IsNullOrWhiteSpace($v)) { return 'MISSING' }
  return 'SET'
}

function Wait-Http([string]$Url, [int]$Tries = 40) {
  foreach ($i in 1..$Tries) {
    try {
      $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
      if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true }
    } catch {}
    Start-Sleep -Milliseconds 750
  }
  return $false
}

function Stop-OwnedProcessTree([int]$RootPid) {
  # 只按当前 platform PID 的父子关系收集后代，避免 Get-Process java 之类的扫杀。
  # 先拍快照再停进程：即使停掉父进程后子进程被系统重新挂父，也仍能按已捕获 PID 精确清理。
  $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
  $owned = [System.Collections.Generic.HashSet[int]]::new()
  [void]$owned.Add($RootPid)
  do {
    $changed = $false
    foreach ($p in $all) {
      # PowerShell 变量名大小写不敏感；$PID 是只读自动变量，不能使用 $pid 作为局部变量。
      $processId = [int]$p.ProcessId
      $parentProcessId = [int]$p.ParentProcessId
      if ($owned.Contains($parentProcessId) -and -not $owned.Contains($processId)) {
        [void]$owned.Add($processId)
        $changed = $true
      }
    }
  } while ($changed)

  $children = @($owned | Where-Object { $_ -ne $RootPid })
  foreach ($processId in $children) {
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
  }
  Stop-Process -Id $RootPid -Force -ErrorAction SilentlyContinue
  return $children
}

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') {
  Fail 5 "RunId 形状非法：$RunId"
}
if (-not $DryRun -and -not $Confirm) {
  Fail 5 '真执行必须显式给出 -Confirm。'
}

$metaPwd = [Environment]::GetEnvironmentVariable('V25_IT_META_PASSWORD', 'Process')
$metricPwd = [Environment]::GetEnvironmentVariable('V25_IT_METRIC_PUBLISH_PASSWORD', 'Process')
if (-not $DryRun -and ([string]::IsNullOrWhiteSpace($metaPwd) -or [string]::IsNullOrWhiteSpace($metricPwd))) {
  Fail 5 '缺 V25_IT_META_PASSWORD / V25_IT_METRIC_PUBLISH_PASSWORD；拒绝启动 HTTP 真链。'
}

$metaDb = "$($RunId)_analytics_meta"
$metricDb = "$($RunId)_analytics_metric"
$metaUser = New-IsolationUserName -RunId $RunId -Role 'metaapp'
$metricUser = New-IsolationUserName -RunId $RunId -Role 'metricapp'
$jdbcSuffix = '?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$metaUrl = "jdbc:mysql://127.0.0.1:3307/$metaDb$jdbcSuffix"
$metricUrl = "jdbc:mysql://127.0.0.1:3307/$metricDb$jdbcSuffix"

if ($metaUrl -match ':3306/' -or $metricUrl -match ':3306/') {
  Fail 5 '隔离 HTTP 入口绝不允许目标 3306。'
}

$httpRoot = Join-Path $root "target\v25-it\$RunId\http"
$attemptId = 'attempt-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff')
$attemptRoot = Join-Path $httpRoot $attemptId
$landingRoot = Join-Path $attemptRoot 'landing'
$eventsDir = Join-Path $landingRoot 'events'
$warehouseDir = Join-Path $attemptRoot 'spark-warehouse'
$metastoreDir = Join-Path $attemptRoot 'derby-metastore'
$metricStaging = Join-Path $attemptRoot 'metric-staging'
$logDir = Join-Path $attemptRoot 'logs'
$platformLog = Join-Path $logDir 'platform.log'
$evidencePath = Join-Path $attemptRoot 'stage7-http-result.json'
$latestEvidencePath = Join-Path $httpRoot 'stage7-http-result.json'
$goldenPath = Join-Path $root $GoldenDataset

Write-Host '=== Stage 7 isolated HTTP preflight ==='
Write-Host "RunId       : $RunId"
Write-Host "meta         : $metaDb / $metaUser @ 127.0.0.1:3307"
Write-Host "metric       : $metricDb / $metricUser @ 127.0.0.1:3307"
Write-Host "meta password: $(Mask-State 'V25_IT_META_PASSWORD')"
Write-Host "metric pwd   : $(Mask-State 'V25_IT_METRIC_PUBLISH_PASSWORD')"
Write-Host "http root    : $httpRoot"
Write-Host "attempt root : $attemptRoot"

if ($DryRun) {
  Write-Host '[DRY-RUN] 不建目录、不启动 Java、不发 HTTP、不连接数据库。'
  exit 0
}

if (-not (Test-Path -LiteralPath $goldenPath)) {
  Fail 5 "找不到 golden dataset：$goldenPath"
}
if (-not (Test-Path -LiteralPath $SparkSubmitPath)) {
  Fail 5 "找不到 spark-submit：$SparkSubmitPath"
}

$portBusy = Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue
if ($portBusy) {
  Fail 5 '8091 已有监听进程；为避免误打其它平台实例，本脚本拒绝复用现有 8091。'
}

$jar = Get-ChildItem (Join-Path $root 'analytics-server\platform-app\target\*.jar') -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*original*' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $jar) {
  Fail 5 '未找到 platform-app jar；请先构建当前 worktree。'
}

# Derby JDBC 使用 create=true；数据库目录自身必须不存在，由 Derby 首次启动创建。
# 因此这里只创建 attempt 根及其它普通目录，绝不能预创建 $metastoreDir。
New-Item -ItemType Directory -Force -Path $eventsDir,$warehouseDir,$metricStaging,$logDir | Out-Null
$inputFile = Join-Path $eventsDir ("stage7-$RunId-$attemptId-golden.jsonl")
Copy-Item -LiteralPath $goldenPath -Destination $inputFile -Force

$env:PLATFORM_META_URL = $metaUrl
$env:PLATFORM_META_USER = $metaUser
$env:PLATFORM_META_PASSWORD = $metaPwd
$env:PLATFORM_METRIC_PUBLISH_URL = $metricUrl
$env:PLATFORM_METRIC_PUBLISH_USER = $metricUser
$env:PLATFORM_METRIC_PUBLISH_PASSWORD = $metricPwd
$env:PLATFORM_METRIC_READ_URL = $metricUrl
$env:PLATFORM_METRIC_READ_USER = $metricUser
$env:PLATFORM_METRIC_READ_PASSWORD = $metricPwd
$env:PLATFORM_LANDING_LOCAL_ROOT = $landingRoot
$env:PLATFORM_SOURCE_PROFILE_ROOT = $root
$env:PLATFORM_SPARK_WAREHOUSE_DIR = $warehouseDir
$env:PLATFORM_SPARK_METASTORE_DIR = $metastoreDir

$proc = $null
$result = [ordered]@{
  runId = $RunId
  attemptId = $attemptId
  attemptRoot = $attemptRoot
  testedAt = (Get-Date).ToString('s')
  metaDb = $metaDb
  metricDb = $metricDb
  port = 3307
  platformJar = $jar.FullName
  goldenDataset = $goldenPath
  landingInput = $inputFile
  runtimeProfileTest = $null
  ingestion = $null
  pipeline = $null
  platform = [ordered]@{
    pid = $null
    hasExited = $null
    exitCode = $null
    lastAliveAt = $null
    lastWorkingSetBytes = $null
    lastPrivateMemoryBytes = $null
    lastHandleCount = $null
    pollErrorCount = 0
    lastPollError = $null
  }
  outcome = 'STARTED'
}

function Save-Evidence {
  param([System.Collections.IDictionary]$Payload)
  $json = $Payload | ConvertTo-Json -Depth 12
  $json | Set-Content -LiteralPath $evidencePath -Encoding utf8
  # latest 指针便于控制端固定读取；attempt 内原始证据永久区分每次执行。
  $json | Set-Content -LiteralPath $latestEvidencePath -Encoding utf8
}

function Capture-PlatformState {
  if (-not $proc) { return }
  try {
    $proc.Refresh()
    $result.platform.pid = $proc.Id
    $result.platform.hasExited = $proc.HasExited
    if ($proc.HasExited) {
      $result.platform.exitCode = $proc.ExitCode
      return
    }
    $result.platform.lastAliveAt = (Get-Date).ToString('s')
    $result.platform.lastWorkingSetBytes = $proc.WorkingSet64
    $result.platform.lastPrivateMemoryBytes = $proc.PrivateMemorySize64
    $result.platform.lastHandleCount = $proc.HandleCount
  } catch {
    # 诊断采样本身绝不能改变验证结果。
  }
}

try {
  Write-Host '[1/7] 启动 isolated analytics platform...'
  $jvmArgs = @('-Dfile.encoding=UTF-8', "-Dplatform.metric.publish.export-dir=$metricStaging", '-jar', $jar.FullName)
  $proc = Start-Process -FilePath 'java' -ArgumentList $jvmArgs -WorkingDirectory $root -RedirectStandardOutput $platformLog -RedirectStandardError "$platformLog.err" -PassThru
  Capture-PlatformState

  if (-not (Wait-Http "$BaseUrl/api/v1/metrics/health")) {
    $result.outcome = 'PLATFORM_NOT_READY'
    throw "平台未就绪；日志：$platformLog"
  }

  Write-Host '[2/7] 登录并读取 runtime profile...'
  $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{ username='admin'; password='admin123' } | ConvertTo-Json)
  if (-not $login.data.token) { throw '登录响应没有 token' }
  $headers = @{ Authorization = "Bearer $($login.data.token)" }

  $profileResp = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/runtime-profiles/1" -Headers $headers
  $profile = $profileResp.data
  if (-not $profile) { throw 'runtime profile 1 不存在' }
  if ($profile.status -eq 'ACTIVE') {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/runtime-profiles/1/disable" -Headers $headers | Out-Null
    $profile.status = 'DISABLED'
  }
  $profile.landingUri = ('file://' + ($landingRoot -replace '\\','/'))
  $profile.landingLayout = 'ROLLING_LOG'
  $profile.sparkSubmitPath = $SparkSubmitPath
  $profile.sparkJobJarUri = (Join-Path $root 'spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar')
  $profile.hiveDatabasePrefix = $null

  Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/v1/runtime-profiles/1" -Headers $headers -ContentType 'application/json' -Body ($profile | ConvertTo-Json -Depth 8) | Out-Null

  Write-Host '[3/7] runtime profile 真检查...'
  $profileTest = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/runtime-profiles/1/test" -Headers $headers
  $result.runtimeProfileTest = $profileTest.data
  if (-not $profileTest.data.allPassed) {
    $result.outcome = 'RUNTIME_PROFILE_TEST_FAILED'
    Save-Evidence $result
    Write-Host "[FAIL exit=7] runtime profile test 未全过；证据 $evidencePath"
    exit 7
  }

  Write-Host '[4/7] 激活 runtime profile...'
  Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/runtime-profiles/1/activate" -Headers $headers | Out-Null

  Write-Host '[5/7] HTTP ingestion...'
  $ingestion = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/ingestion/runs" -Headers $headers
  $result.ingestion = $ingestion.data
  if (-not $ingestion.data -or $ingestion.data.noNewData -or [long]$ingestion.data.recordCount -le 0) {
    $result.outcome = 'INGESTION_NO_DATA'
    Save-Evidence $result
    Write-Host "[FAIL exit=2] ingestion 未取得可消费数据；证据 $evidencePath"
    exit 2
  }

  Write-Host '[6/7] HTTP pipeline...'
  # 每个 verification attempt 必须创建独立 Pipeline run。
  # PipelineService 的正式契约是“同 Idempotency-Key 返回原 run，不重复执行”，所以这里若只按 RunId
  # 生成 key，会让 R1/R2 之类的重跑错误复用旧失败 run，根本没有验证本次 attempt 的 landing/Spark。
  $attemptVersion = "stage7-$RunId-$attemptId"
  $idem = $attemptVersion
  $pipelineBody = @{ runtimeProfileId=1; pipelineCode='ODS_TO_ADS'; businessTime=$BusinessTime; sourceDataVersion=$attemptVersion } | ConvertTo-Json
  $pipelineHeaders = @{} + $headers
  $pipelineHeaders['Idempotency-Key'] = $idem
  $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/pipeline-runs" -Headers $pipelineHeaders -ContentType 'application/json' -Body $pipelineBody
  $pipelineId = $created.data.runId
  if (-not $pipelineId) { throw 'pipeline create 未返回 runId' }
  $result.pipeline = $created.data

  $terminal = @('SUCCESS','FAILED','RUN_INTERRUPTED','CANCELLED','DEGRADED')
  $deadline = (Get-Date).AddSeconds($PipelineTimeoutSec)
  $last = $created
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSec
    Capture-PlatformState
    try {
      $last = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/pipeline-runs/$pipelineId" -Headers $headers
      $result.pipeline = $last.data
    } catch {
      $result.platform.pollErrorCount = [int]$result.platform.pollErrorCount + 1
      $result.platform.lastPollError = $_.Exception.Message
      Capture-PlatformState
      if ($proc -and $proc.HasExited) {
        $result.outcome = 'PLATFORM_EXITED_DURING_PIPELINE'
        Save-Evidence $result
        Write-Host ("[FAIL exit=7] platform 在 pipeline 运行中退出：pid={0} exitCode={1} stage={2}；证据 {3}" -f
          $proc.Id,$proc.ExitCode,$result.pipeline.currentStage,$evidencePath)
        exit 7
      }
      if ([int]$result.platform.pollErrorCount -ge 3) {
        throw
      }
      continue
    }
    if ($terminal -contains $last.data.status) { break }
  }
  $result.pipeline = $last.data
  if (-not ($terminal -contains $last.data.status)) {
    $result.outcome = 'PIPELINE_TIMEOUT'
    $result.pipelineTimeoutSec = $PipelineTimeoutSec
    Save-Evidence $result
    Write-Host ("[TIMEOUT exit=7] pipeline 在 {0}s 内未到终态：status={1} stage={2}；证据 {3}" -f $PipelineTimeoutSec, $last.data.status, $last.data.currentStage, $evidencePath)
    exit 7
  }
  if ($last.data.status -ne 'SUCCESS') {
    $result.outcome = 'PIPELINE_NOT_SUCCESS'
    Save-Evidence $result
    Write-Host ("[FAIL exit=7] pipeline 终态={0} stage={1} error={2}；证据 {3}" -f $last.data.status, $last.data.currentStage, $last.data.errorCode, $evidencePath)
    exit 7
  }

  Write-Host '[7/7] 收口证据...'
  $result.outcome = 'PASS'
  Save-Evidence $result
  Write-Host "[PASS exit=0] Stage 7 isolated HTTP ingestion → pipeline 通过；证据 $evidencePath"
  exit 0
}
catch {
  Capture-PlatformState
  $result.outcome = 'EXCEPTION'
  $result.exception = $_.Exception.Message
  try { Save-Evidence $result } catch {}
  Write-Host ("[FAIL exit=7] {0}" -f $_.Exception.Message)
  exit 7
}
finally {
  if ($proc -and -not $proc.HasExited) {
    $children = @(Stop-OwnedProcessTree $proc.Id)
    Write-Host ("已停止本脚本启动的 platform PID={0} 及其后代 PID=[{1}]" -f $proc.Id, ($children -join ','))
  }
}
