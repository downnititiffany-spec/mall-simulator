param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [switch]$Confirm,
  [switch]$DryRun,
  [long]$EventCount = 600,
  [long]$Seed = 20260401,
  [string]$Scenario = 'refund_rise',
  [int]$StockPerProduct = 1000,
  [int]$RunTimeoutSec = 300,
  [int]$PollSec = 2
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'isolation-naming.ps1')

function Fail([int]$Code, [string]$Message) {
  Write-Host ("[REFUSE exit={0}] {1}" -f $Code, $Message)
  exit $Code
}

function Read-CredrefPassword([string]$Path) {
  $line = Get-Content -LiteralPath $Path -Encoding utf8 | Where-Object { $_ -match '^password=' } | Select-Object -First 1
  if (-not $line) { throw "credref 缺 password 键：$Path" }
  return $line.Substring($line.IndexOf('=') + 1)
}

function Wait-Get([string]$Url, [int]$Tries = 50) {
  foreach ($i in 1..$Tries) {
    try {
      $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
      if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true }
    } catch {}
    Start-Sleep -Milliseconds 500
  }
  return $false
}

function LocalPath-FromFileUri([string]$UriText) {
  if ([string]::IsNullOrWhiteSpace($UriText)) { return $null }
  try {
    $u = [Uri]$UriText
    if ($u.IsFile) { return $u.LocalPath }
  } catch {}
  return $UriText
}

function Save-Evidence([System.Collections.IDictionary]$Payload, [string]$AttemptFile, [string]$LatestFile) {
  $json = $Payload | ConvertTo-Json -Depth 14
  $json | Set-Content -LiteralPath $AttemptFile -Encoding utf8
  $json | Set-Content -LiteralPath $LatestFile -Encoding utf8
}

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') { Fail 5 "RunId 形状非法：$RunId" }
if ($EventCount -lt 1 -or $EventCount -gt 5000) { Fail 5 "EventCount 必须在 1..5000：$EventCount" }
if ($StockPerProduct -lt 1 -or $StockPerProduct -gt 100000) { Fail 5 "StockPerProduct 必须在 1..100000：$StockPerProduct" }
if (-not $DryRun -and -not $Confirm) { Fail 5 '真执行必须显式给出 -Confirm。' }

$mallDb = "$($RunId)_mall"
$genDb = "$($RunId)_generator"
$mallUser = New-IsolationUserName -RunId $RunId -Role 'mallapp'
$genUser = New-IsolationUserName -RunId $RunId -Role 'genapp'
$jdbcSuffix = '?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$mallUrl = "jdbc:mysql://127.0.0.1:3307/$mallDb$jdbcSuffix"
$genUrl = "jdbc:mysql://127.0.0.1:3307/$genDb$jdbcSuffix"
if ($mallUrl -match ':3306/' -or $genUrl -match ':3306/') { Fail 5 '生产者隔离入口绝不允许目标 3306。' }

$producerRoot = Join-Path $root "target\v25-it\$RunId\producer"
$attemptId = 'attempt-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff')
$attemptRoot = Join-Path $producerRoot $attemptId
$mallLanding = Join-Path $attemptRoot 'mall-landing'
$generatorOutput = Join-Path $attemptRoot 'generator-output'
$logDir = Join-Path $attemptRoot 'logs'
$mallLog = Join-Path $logDir 'mall.log'
$generatorLog = Join-Path $logDir 'generator.log'
$planCliLog = Join-Path $logDir 'generator-plan-cli.log'
$evidencePath = Join-Path $attemptRoot 'stage7-producer-result.json'
$latestEvidencePath = Join-Path $producerRoot 'stage7-producer-result.json'
$mallCredref = Join-Path $root "mall-simulator\credref-$RunId-mall.properties"
$genCredref = Join-Path $root "synthetic-data-generator\credref-$RunId-generator.properties"
$mallJar = Join-Path $root 'mall-simulator\target\mall-simulator-0.1.0-SNAPSHOT.jar'
$genJar = Join-Path $root 'synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar'

Write-Host '=== Stage 7 isolated producer chain ==='
Write-Host "RunId        : $RunId"
Write-Host "mall         : $mallDb / $mallUser @ 127.0.0.1:3307 / :8090"
Write-Host "generator    : $genDb / $genUser @ 127.0.0.1:3307 / :8092"
Write-Host "scenario     : $Scenario"
Write-Host "seed/events  : $Seed / $EventCount"
Write-Host "attempt root : $attemptRoot"
Write-Host 'credential   : gitignored credref（只读 password，不回显）'

if ($DryRun) {
  Write-Host '[DRY-RUN] 不读口令、不建目录、不启动 JVM、不发 HTTP、不连接数据库。'
  exit 0
}

foreach ($required in @($mallCredref,$genCredref,$mallJar,$genJar)) {
  if (-not (Test-Path -LiteralPath $required)) { Fail 5 "缺少运行前置：$required" }
}
foreach ($port in @(8090,8092)) {
  if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { Fail 5 "端口 $port 已被占用；拒绝复用未知实例。" }
}

$mallPwd = Read-CredrefPassword $mallCredref
$genPwd = Read-CredrefPassword $genCredref
if ([string]::IsNullOrWhiteSpace($mallPwd) -or [string]::IsNullOrWhiteSpace($genPwd)) { Fail 5 '隔离 credref 口令为空。' }
New-Item -ItemType Directory -Force -Path $mallLanding,$generatorOutput,$logDir | Out-Null

$mallProc = $null
$genProc = $null
$tokenRef = 'STAGE7_PRODUCER_MALL_TOKEN'
$mallToken = $null
$result = [ordered]@{
  runId=$RunId; attemptId=$attemptId; attemptRoot=$attemptRoot; testedAt=(Get-Date).ToString('s')
  port=3307; mallDb=$mallDb; generatorDb=$genDb; eventCount=$EventCount; seed=$Seed; scenario=$Scenario
  stockReset=$null; target=$null; targetProbe=$null; plan=$null; generationRun=$null; journal=$null; outbox=$null; rollingLog=$null
  outcome='STARTED'
}

try {
  Write-Host '[1/9] 启动 isolated mall :8090 ...'
  $env:SPRING_DATASOURCE_URL = $mallUrl
  $env:SPRING_DATASOURCE_USERNAME = $mallUser
  $env:SPRING_DATASOURCE_PASSWORD = $mallPwd
  $env:MALL_LANDING_PATH = $mallLanding
  $mallProc = Start-Process -FilePath 'java' -ArgumentList @('-Dfile.encoding=UTF-8','-jar',$mallJar) -WorkingDirectory $root -RedirectStandardOutput $mallLog -RedirectStandardError "$mallLog.err" -PassThru

  $login = $null
  $lastLoginError = $null
  foreach ($i in 1..120) {
    if ($mallProc.HasExited) {
      throw "mall 启动进程提前退出 exit=$($mallProc.ExitCode)；日志：$mallLog"
    }
    try {
      $loginBody = @{username='admin';password='admin123'} | ConvertTo-Json
      $login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8090/api/v1/auth/login' -ContentType 'application/json' -Body $loginBody -TimeoutSec 2
      if ($login.data.token) { break }
    } catch {
      $lastLoginError = $_.Exception.Message
    }
    Start-Sleep -Milliseconds 500
  }
  if (-not $login.data.token) {
    throw "mall 60s 内未完成 admin 登录；lastError=$lastLoginError；日志：$mallLog"
  }
  $mallToken = [string]$login.data.token
  $mallHeaders = @{Authorization="Bearer $mallToken"}

  Write-Host '[1a/9] 收敛历史 Outbox 并建立不可发布残留基线 ...'
  $baselineFailedIds = [System.Collections.Generic.HashSet[string]]::new()
  $previousPending = [long]::MaxValue
  for ($i=0; $i -lt 10; $i++) {
    $baselinePub = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/publish' -Headers $mallHeaders
    foreach ($failedId in @($baselinePub.data.failedEventIds)) {
      if (-not [string]::IsNullOrWhiteSpace([string]$failedId)) { [void]$baselineFailedIds.Add([string]$failedId) }
    }
    $baselineStatus = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/status' -Headers $mallHeaders
    $pending = [long]$baselineStatus.data.pendingCount
    if ($pending -eq 0 -or $pending -eq $previousPending) { break }
    $previousPending = $pending
    Start-Sleep -Milliseconds 300
  }
  $baselineStatus = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/status' -Headers $mallHeaders
  $baselineResidualPending = [long]$baselineStatus.data.pendingCount
  $result.outbox = @{
    baselineResidualPending=$baselineResidualPending
    baselineFailedEventIds=@($baselineFailedIds)
  }

  Write-Host '[1b/9] 通过 admin HTTP 恢复 run-scoped 商品库存 ...'
  $adminProducts = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/admin/products' -Headers $mallHeaders
  $stockAdjusted = 0
  foreach ($product in @($adminProducts.data)) {
    $stockBody = @{quantity=$StockPerProduct;changeType='adjust'} | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri ("http://127.0.0.1:8090/api/v1/admin/products/{0}/stock" -f $product.productId) -Headers $mallHeaders -ContentType 'application/json' -Body $stockBody | Out-Null
    $stockAdjusted++
  }
  if ($stockAdjusted -lt 1) { throw 'admin 商品目录为空，无法恢复隔离库存' }
  $result.stockReset = @{productCount=$stockAdjusted;availableQtyEach=$StockPerProduct;via='POST /api/v1/admin/products/{productId}/stock'}

  Write-Host '[2/9] 启动 isolated generator :8092 ...'
  $env:SPRING_DATASOURCE_URL = $genUrl
  $env:SPRING_DATASOURCE_USERNAME = $genUser
  $env:SPRING_DATASOURCE_PASSWORD = $genPwd
  $env:GENERATOR_OUTPUT_ROOT = $generatorOutput
  $env:GENERATOR_LOG_FILE = $generatorLog
  [Environment]::SetEnvironmentVariable($tokenRef,$mallToken,'Process')
  $genProc = Start-Process -FilePath 'java' -ArgumentList @('-Dfile.encoding=UTF-8','-jar',$genJar) -WorkingDirectory $root -RedirectStandardOutput "$generatorLog.stdout" -RedirectStandardError "$generatorLog.stderr" -PassThru
  if (-not (Wait-Get 'http://127.0.0.1:8092/api/v1/scenarios' 120)) {
    if ($genProc.HasExited) {
      throw "generator 启动进程提前退出 exit=$($genProc.ExitCode)；日志：$generatorLog"
    }
    throw "generator 60s 内未就绪；日志：$generatorLog"
  }

  Write-Host '[3/9] 创建并实测 REFERENCE_MALL_HTTP target ...'
  $targetBody = @{
    name="stage7-producer-$attemptId"; adapter_type='REFERENCE_MALL_HTTP'; base_url='http://127.0.0.1:8090'
    credential_ref=$tokenRef; config_json='{}'; status='ACTIVE'; test_environment=$true
    capabilities='product,user,order,refund'
  } | ConvertTo-Json
  $target = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8092/api/v1/targets' -ContentType 'application/json' -Body $targetBody
  if (-not $target.id) { throw 'generator target 创建后没有 id' }
  $result.target = @{id=$target.id;adapterType=$target.adapter_type;baseUrl=$target.base_url;credentialRef=$target.credential_ref;configVersion=$target.config_version}
  $probe = Invoke-RestMethod -Method Post -Uri ("http://127.0.0.1:8092/api/v1/targets/{0}/test" -f $target.id)
  $result.targetProbe = $probe
  foreach ($cap in @('product','user','order','refund')) {
    if ($probe.capabilities.$cap -ne 'SUPPORTED') { throw "target capability 未通过：$cap=$($probe.capabilities.$cap)" }
  }
  if (-not $probe.reachable) { throw "target probe reachable=false：$($probe.detail)" }

  Write-Host '[4/9] CLI 追加不可变 MALL_API plan ...'
  $planId = 's7p-' + (Get-Date -Format 'yyyyMMddHHmmssfff')
  $cliArgs = @(
    '-Dfile.encoding=UTF-8','-jar',$genJar,'--spring.main.web-application-type=none','--generator.cli=plan-append'
    "--plan-id=$planId",'--mode=MALL_API',"--target-id=$($target.id)","--scenario=$Scenario","--seed=$Seed"
    '--start=2026-09-01T00:00:00+08:00','--end=2026-09-02T00:00:00+08:00',"--event-count=$EventCount"
    '--rate=0','--dirty-profile=none'
  )
  $cliProc = Start-Process -FilePath 'java' -ArgumentList $cliArgs -WorkingDirectory $root -RedirectStandardOutput $planCliLog -RedirectStandardError "$planCliLog.err" -Wait -PassThru
  if ($cliProc.ExitCode -ne 0) { throw "plan-append exit=$($cliProc.ExitCode)；日志：$planCliLog" }
  $planLine = Get-Content -LiteralPath $planCliLog -Encoding utf8 | Where-Object { $_ -match '^plan_id=.* version=\d+ ' } | Select-Object -Last 1
  if (-not $planLine -or $planLine -notmatch 'version=(\d+)') { throw "plan-append 未输出版本号；日志：$planCliLog" }
  $planVersion = [int]$Matches[1]
  $result.plan = @{planId=$planId;version=$planVersion;mode='MALL_API';targetId=$target.id}

  Write-Host '[5/9] HTTP 启动 generator MALL_API run ...'
  $runBody = @{plan_id=$planId;version=$planVersion} | ConvertTo-Json
  $started = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8092/api/v1/generation-runs' -ContentType 'application/json' -Body $runBody
  if (-not $started.runId) { throw 'generation-runs 未返回 runId' }
  $deadline = (Get-Date).AddSeconds($RunTimeoutSec)
  $runView = $null
  do {
    Start-Sleep -Seconds $PollSec
    $runView = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8092/api/v1/generation-runs/{0}" -f $started.runId)
  } while ($runView.status -notin @('SUCCESS','FAILED','CANCELLED') -and (Get-Date) -lt $deadline)
  $result.generationRun = $runView
  if ($runView.status -notin @('SUCCESS','FAILED','CANCELLED')) {
    $result.outcome='GENERATOR_TIMEOUT'; Save-Evidence $result $evidencePath $latestEvidencePath
    Write-Host "[TIMEOUT exit=7] generator run 未到终态；证据 $evidencePath"; exit 7
  }
  if ($runView.status -ne 'SUCCESS' -or [long]$runView.failed_count -ne 0 -or [long]$runView.success_count -ne $EventCount) {
    $result.outcome='GENERATOR_RUN_FAILED'; Save-Evidence $result $evidencePath $latestEvidencePath
    Write-Host ("[FAIL exit=7] generator status={0} success={1} failed={2} error={3}；证据 {4}" -f $runView.status,$runView.success_count,$runView.failed_count,$runView.error,$evidencePath)
    exit 7
  }

  Write-Host '[6/9] 对账 generator operation journal ...'
  # Invoke-RestMethod 对顶层 JSON 数组会保留一个 Object[] 结果；若再直接 @() 包一层，
  # Where-Object 看到的是整个数组对象，uri 会成员枚举成多个值并在字符串化时粘在一起。
  # 显式过一次 pipeline，让每个 ArtifactView 成为独立元素。
  $artifactResponse = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8092/api/v1/generation-runs/{0}/artifacts" -f $started.runId)
  $artifacts = @($artifactResponse | ForEach-Object { $_ })
  $journalArtifact = $artifacts | Where-Object { $_.uri -like '*operation-journal.jsonl' } | Select-Object -First 1
  if (-not $journalArtifact) { throw 'MALL_API run 缺 operation-journal.jsonl 制品' }
  $journalPath = LocalPath-FromFileUri $journalArtifact.uri
  if (-not (Test-Path -LiteralPath $journalPath)) { throw "operation journal 文件不存在：$journalPath" }
  $journalRows = @(Get-Content -LiteralPath $journalPath -Encoding utf8 | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
  $realRows = @($journalRows | Where-Object { $_.real_http -eq $true -and $_.status -eq 'OK' })
  $journalCounts = [ordered]@{}
  foreach ($op in @('listProducts','createSyntheticUser','createOrder','pay','cancel','refund')) {
    $journalCounts[$op] = @($realRows | Where-Object { $_.operation -eq $op }).Count
  }
  foreach ($requiredOp in @('createOrder','pay','refund')) {
    if ([int]$journalCounts[$requiredOp] -lt 1) { throw "operation journal 未出现真实 $requiredOp HTTP：$($journalCounts | ConvertTo-Json -Compress)" }
  }
  if (@($journalRows | Where-Object { $_.status -eq 'FAILED' }).Count -gt 0) { throw 'operation journal 出现 FAILED 行，但 generation_run 却声称 SUCCESS' }
  $currentOrderIds = @($realRows | Where-Object { $_.operation -eq 'createOrder' } | ForEach-Object { [string]$_.external_id } | Sort-Object -Unique)
  $currentRefundIds = @($realRows | Where-Object { $_.operation -eq 'refund' } | ForEach-Object { [string]$_.external_id } | Sort-Object -Unique)
  $result.journal = @{
    artifactUri=$journalArtifact.uri;recordCount=$journalArtifact.record_count;realHttpOk=$realRows.Count
    realOperationCounts=$journalCounts;createdOrderIds=$currentOrderIds;refundIds=$currentRefundIds
  }

  Write-Host '[7/9] 商城 Outbox 显式发布到 run-scoped rolling log ...'
  $statusBefore = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/status' -Headers $mallHeaders
  $publishedTotal = 0
  $failedAfterIds = [System.Collections.Generic.HashSet[string]]::new()
  for ($i=0; $i -lt 10; $i++) {
    $pub = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/publish' -Headers $mallHeaders
    $publishedTotal += [int]$pub.data.publishedCount
    foreach ($failedId in @($pub.data.failedEventIds)) {
      if (-not [string]::IsNullOrWhiteSpace([string]$failedId)) { [void]$failedAfterIds.Add([string]$failedId) }
    }
    $statusNow = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/status' -Headers $mallHeaders
    if ([long]$statusNow.data.pendingCount -le $baselineResidualPending) { break }
    Start-Sleep -Milliseconds 300
  }
  $statusAfter = Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:8090/api/v1/mall/outbox/status' -Headers $mallHeaders
  $newFailedIds = @($failedAfterIds | Where-Object { -not $baselineFailedIds.Contains($_) })
  if ($newFailedIds.Count -gt 0) {
    throw "Outbox 本次新增失败事件：$($newFailedIds -join ',')"
  }
  if ([long]$statusAfter.data.pendingCount -gt $baselineResidualPending) {
    throw "Outbox 本次 run 留下新 pending：baseline=$baselineResidualPending after=$($statusAfter.data.pendingCount)"
  }
  $result.outbox = @{
    baselineResidualPending=$baselineResidualPending
    baselineFailedEventIds=@($baselineFailedIds)
    pendingBefore=$statusBefore.data.pendingCount
    explicitPublishedCount=$publishedTotal
    pendingAfter=$statusAfter.data.pendingCount
    failedEventIdsObserved=@($failedAfterIds)
    newFailedEventIds=$newFailedIds
    latestFile=$statusAfter.data.latestFile
  }

  Write-Host '[8/9] 核对商城 rolling JSONL 事件类型 ...'
  $eventFiles = @(Get-ChildItem -Path (Join-Path $mallLanding 'events') -Filter '*.jsonl' -File -ErrorAction SilentlyContinue)
  if ($eventFiles.Count -lt 1) { throw "商城 landing 没有 JSONL：$mallLanding" }
  $eventTypeCounts = [ordered]@{}
  $eventLines = 0
  $loggedOrderCreatedIds = [System.Collections.Generic.HashSet[string]]::new()
  $loggedOrderPaidIds = [System.Collections.Generic.HashSet[string]]::new()
  $loggedRefundCreatedIds = [System.Collections.Generic.HashSet[string]]::new()
  $loggedRefundCompletedIds = [System.Collections.Generic.HashSet[string]]::new()
  foreach ($file in $eventFiles) {
    foreach ($line in Get-Content -LiteralPath $file.FullName -Encoding utf8) {
      if ([string]::IsNullOrWhiteSpace($line)) { continue }
      $node = $line | ConvertFrom-Json
      $type = [string]$node.event_type
      if (-not $eventTypeCounts.Contains($type)) { $eventTypeCounts[$type] = 0 }
      $eventTypeCounts[$type] = [int]$eventTypeCounts[$type] + 1
      if ($type -eq 'order_created') { [void]$loggedOrderCreatedIds.Add([string]$node.payload.order_id) }
      if ($type -eq 'order_paid') { [void]$loggedOrderPaidIds.Add([string]$node.payload.order_id) }
      if ($type -eq 'refund_created') { [void]$loggedRefundCreatedIds.Add([string]$node.payload.refund_id) }
      if ($type -eq 'refund_completed') { [void]$loggedRefundCompletedIds.Add([string]$node.payload.refund_id) }
      $eventLines++
    }
  }
  foreach ($requiredType in @('order_created','order_paid','refund_created','refund_completed')) {
    if (-not $eventTypeCounts.Contains($requiredType) -or [int]$eventTypeCounts[$requiredType] -lt 1) { throw "rolling JSONL 缺 $requiredType：$($eventTypeCounts | ConvertTo-Json -Compress)" }
  }
  $currentPaidOrderIds = @($realRows | Where-Object { $_.operation -eq 'pay' } | ForEach-Object {
    $canonicalOrder = [string]$_.canonical_id
    $created = $realRows | Where-Object { $_.operation -eq 'createOrder' -and $_.canonical_id -eq $canonicalOrder } | Select-Object -First 1
    if ($created) { [string]$created.external_id }
  } | Where-Object { $_ } | Sort-Object -Unique)
  $missingCreatedOrders = @($currentOrderIds | Where-Object { -not $loggedOrderCreatedIds.Contains($_) })
  $missingPaidOrders = @($currentPaidOrderIds | Where-Object { -not $loggedOrderPaidIds.Contains($_) })
  $missingRefundCreated = @($currentRefundIds | Where-Object { -not $loggedRefundCreatedIds.Contains($_) })
  $missingRefundCompleted = @($currentRefundIds | Where-Object { -not $loggedRefundCompletedIds.Contains($_) })
  if ($missingCreatedOrders.Count -gt 0 -or $missingPaidOrders.Count -gt 0 -or
      $missingRefundCreated.Count -gt 0 -or $missingRefundCompleted.Count -gt 0) {
    throw ("rolling JSONL 与本次 operation journal 关联不完整：order_created missing={0}, order_paid missing={1}, refund_created missing={2}, refund_completed missing={3}" -f
      $missingCreatedOrders.Count,$missingPaidOrders.Count,$missingRefundCreated.Count,$missingRefundCompleted.Count)
  }
  $result.rollingLog = @{
    files=@($eventFiles | ForEach-Object {$_.FullName});lineCount=$eventLines;eventTypeCounts=$eventTypeCounts
    correlatedCurrentRun=@{
      createdOrders=$currentOrderIds.Count;paidOrders=$currentPaidOrderIds.Count;refunds=$currentRefundIds.Count
      missingCreatedOrders=$missingCreatedOrders.Count;missingPaidOrders=$missingPaidOrders.Count
      missingRefundCreated=$missingRefundCreated.Count;missingRefundCompleted=$missingRefundCompleted.Count
    }
  }

  Write-Host '[9/9] 收口 producer 证据 ...'
  $result.outcome='PASS'
  Save-Evidence $result $evidencePath $latestEvidencePath
  Write-Host "[PASS exit=0] generator :8092 → mall :8090 → order/pay/refund → Outbox/JSONL 通过；证据 $evidencePath"
  exit 0
}
catch {
  $result.outcome='EXCEPTION'
  $result.exception=$_.Exception.Message
  try {
    New-Item -ItemType Directory -Force -Path $attemptRoot | Out-Null
    Save-Evidence $result $evidencePath $latestEvidencePath
  } catch {}
  Write-Host ("[FAIL exit=7] {0}" -f $_.Exception.Message)
  exit 7
}
finally {
  [Environment]::SetEnvironmentVariable($tokenRef,$null,'Process')
  foreach ($owned in @($genProc,$mallProc)) {
    if ($owned -and -not $owned.HasExited) { Stop-Process -Id $owned.Id -Force -ErrorAction SilentlyContinue }
  }
  Remove-Item Env:\SPRING_DATASOURCE_URL,Env:\SPRING_DATASOURCE_USERNAME,Env:\SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
  Remove-Item Env:\MALL_LANDING_PATH,Env:\GENERATOR_OUTPUT_ROOT,Env:\GENERATOR_LOG_FILE -ErrorAction SilentlyContinue
}
