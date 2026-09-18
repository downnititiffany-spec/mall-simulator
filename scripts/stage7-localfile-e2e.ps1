param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [switch]$Confirm,
  [switch]$DryRun,
  [string]$ProducerEvidence = '',
  [int]$PipelineTimeoutSec = 600
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

function Fail([int]$Code, [string]$Message) {
  Write-Host ("[REFUSE exit={0}] {1}" -f $Code, $Message)
  exit $Code
}

function Save-Evidence([System.Collections.IDictionary]$Payload, [string]$AttemptFile, [string]$LatestFile) {
  $json = $Payload | ConvertTo-Json -Depth 14
  $json | Set-Content -LiteralPath $AttemptFile -Encoding utf8
  $json | Set-Content -LiteralPath $LatestFile -Encoding utf8
}

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') {
  Fail 5 "RunId 形状非法：$RunId"
}
if ($PipelineTimeoutSec -lt 60 -or $PipelineTimeoutSec -gt 1800) {
  Fail 5 "PipelineTimeoutSec 必须在 60..1800：$PipelineTimeoutSec"
}

$producerRoot = Join-Path $root "target\v25-it\$RunId\producer"
$httpRoot = Join-Path $root "target\v25-it\$RunId\http"
$e2eRoot = Join-Path $root "target\v25-it\$RunId\localfile-e2e"
$httpScript = Join-Path $PSScriptRoot 'stage7-http-isolated.ps1'
if ([string]::IsNullOrWhiteSpace($ProducerEvidence)) {
  $ProducerEvidence = Join-Path $producerRoot 'stage7-producer-result.json'
}

Write-Host '=== Stage 7 producer rolling → LocalFile → analytics ==='
Write-Host "RunId            : $RunId"
Write-Host "producer evidence : $ProducerEvidence"
Write-Host "analytics runner  : $httpScript"
Write-Host "pipeline timeout  : $PipelineTimeoutSec"

if ($DryRun) {
  Write-Host '[DRY-RUN] 不读 producer evidence、不读口令、不建目录、不启动 JVM、不发 HTTP、不连接数据库。'
  exit 0
}

if (-not $Confirm) {
  Fail 5 '真执行必须显式给出 -Confirm。'
}
if (-not (Test-Path -LiteralPath $ProducerEvidence)) {
  Fail 5 "找不到 producer evidence：$ProducerEvidence"
}
if (-not (Test-Path -LiteralPath $httpScript)) {
  Fail 5 "找不到 analytics runner：$httpScript"
}

$producer = Get-Content -Raw -LiteralPath $ProducerEvidence -Encoding utf8 | ConvertFrom-Json
if ([string]$producer.runId -ne $RunId) {
  Fail 5 "producer evidence runId 不匹配：expected=$RunId actual=$($producer.runId)"
}
if ([string]$producer.outcome -ne 'PASS') {
  Fail 5 "producer evidence 尚未 PASS：outcome=$($producer.outcome)"
}
if (-not $producer.rollingLog) {
  Fail 5 'producer evidence 缺 rollingLog。'
}

$sourceLineCount = [long]$producer.rollingLog.lineCount
$uniqueEventIdCount = [long]$producer.rollingLog.uniqueEventIdCount
$duplicateEventIdCount = [long]$producer.rollingLog.duplicateEventIdCount
if ($sourceLineCount -le 0) {
  Fail 5 "producer rolling lineCount 非法：$sourceLineCount"
}
if ($duplicateEventIdCount -ne 0 -or $uniqueEventIdCount -ne $sourceLineCount) {
  Fail 5 ("producer rolling 尚未满足 clean handoff：lines={0} unique={1} duplicateIds={2}" -f
    $sourceLineCount,$uniqueEventIdCount,$duplicateEventIdCount)
}

$rollingFiles = @($producer.rollingLog.files)
if ($rollingFiles.Count -ne 1) {
  Fail 5 "Batch T 首版只接受单一完成 rolling 文件；actual=$($rollingFiles.Count)"
}

$sourceFile = [IO.Path]::GetFullPath([string]$rollingFiles[0])
$producerAttemptRoot = Join-Path $producerRoot ([string]$producer.attemptId)
$expectedEventsRoot = [IO.Path]::GetFullPath((Join-Path $producerAttemptRoot 'mall-landing\events'))
$expectedPrefix = $expectedEventsRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if (-not $sourceFile.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
  Fail 5 "rolling 文件越界：$sourceFile"
}
if (-not (Test-Path -LiteralPath $sourceFile)) {
  Fail 5 "rolling 文件不存在：$sourceFile"
}

$ids = [System.Collections.Generic.HashSet[string]]::new()
$duplicates = [System.Collections.Generic.HashSet[string]]::new()
$dates = [System.Collections.Generic.HashSet[string]]::new()
$sources = [System.Collections.Generic.HashSet[string]]::new()
$observedLines = 0L
foreach ($line in Get-Content -LiteralPath $sourceFile -Encoding utf8) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $node = $line | ConvertFrom-Json
  $eventId = [string]$node.event_id
  if ([string]::IsNullOrWhiteSpace($eventId)) {
    Fail 5 'rolling 文件存在空 event_id。'
  }
  if (-not $ids.Add($eventId)) {
    [void]$duplicates.Add($eventId)
  }
  $eventTime = [datetimeoffset]$node.event_time
  [void]$dates.Add($eventTime.ToString('yyyy-MM-dd'))
  [void]$sources.Add([string]$node.source_system)
  $observedLines++
}

if ($observedLines -ne $sourceLineCount) {
  Fail 5 "rolling evidence 与实文件行数不一致：evidence=$sourceLineCount file=$observedLines"
}
if ($duplicates.Count -ne 0 -or $ids.Count -ne $observedLines) {
  Fail 5 "rolling 实文件重复：lines=$observedLines unique=$($ids.Count) duplicateIds=$($duplicates.Count)"
}
if ($dates.Count -ne 1) {
  Fail 5 "Batch T 首版要求完成文件只含一个业务日；dates=$(@($dates) -join ',')"
}
if ($sources.Count -ne 1 -or -not $sources.Contains('mock-mall')) {
  Fail 5 "Batch T 首版只接受参考商城 canonical source_system=mock-mall；sources=$(@($sources) -join ',')"
}

$businessDate = @($dates)[0]
$businessTime = '{0}T00:00:00' -f $businessDate
$rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\','/')
$rootPrefix = $rootFull + [IO.Path]::DirectorySeparatorChar
if (-not $sourceFile.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
  Fail 5 "rolling 文件不在当前 worktree：$sourceFile"
}
$relativeInput = $sourceFile.Substring($rootPrefix.Length)

$metaPwd = [Environment]::GetEnvironmentVariable('V25_IT_META_PASSWORD','Process')
$metricPwd = [Environment]::GetEnvironmentVariable('V25_IT_METRIC_PUBLISH_PASSWORD','Process')
if ([string]::IsNullOrWhiteSpace($metaPwd) -or [string]::IsNullOrWhiteSpace($metricPwd)) {
  Fail 5 '缺少 analytics 进程环境口令 V25_IT_META_PASSWORD / V25_IT_METRIC_PUBLISH_PASSWORD。'
}

$attemptId = 'attempt-' + (Get-Date -Format 'yyyyMMdd_HHmmss_fff')
$attemptRoot = Join-Path $e2eRoot $attemptId
$evidencePath = Join-Path $attemptRoot 'stage7-localfile-e2e-result.json'
$latestEvidencePath = Join-Path $e2eRoot 'stage7-localfile-e2e-result.json'
New-Item -ItemType Directory -Force -Path $attemptRoot | Out-Null

$result = [ordered]@{
  runId=$RunId
  attemptId=$attemptId
  testedAt=(Get-Date).ToString('s')
  producerEvidence=[IO.Path]::GetFullPath($ProducerEvidence)
  producerAttemptId=[string]$producer.attemptId
  producerSourceFile=$sourceFile
  producerSourceSha256=(Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
  sourceLineCount=$sourceLineCount
  uniqueEventIdCount=$uniqueEventIdCount
  duplicateEventIdCount=$duplicateEventIdCount
  sourceSystems=@($sources)
  businessDate=$businessDate
  businessTime=$businessTime
  analytics=$null
  outcome='STARTED'
}

try {
  Write-Host ("[1/2] 交给 analytics LocalFile/HTTP pipeline：lines={0} businessDate={1}" -f $sourceLineCount,$businessDate)
  $httpArgs = @(
    '-NoProfile','-File',$httpScript,
    '-RunId',$RunId,
    '-GoldenDataset',$relativeInput,
    '-BusinessTime',$businessTime,
    '-PipelineTimeoutSec',[string]$PipelineTimeoutSec,
    '-Confirm'
  )
  & pwsh @httpArgs
  $httpExit = $LASTEXITCODE

  $httpLatestEvidence = Join-Path $httpRoot 'stage7-http-result.json'
  if (-not (Test-Path -LiteralPath $httpLatestEvidence)) {
    throw "analytics runner 未生成 latest evidence：$httpLatestEvidence"
  }
  $http = Get-Content -Raw -LiteralPath $httpLatestEvidence -Encoding utf8 | ConvertFrom-Json
  $result.analytics = @{
    exitCode=$httpExit
    attemptId=$http.attemptId
    attemptRoot=$http.attemptRoot
    evidence=(Join-Path ([string]$http.attemptRoot) 'stage7-http-result.json')
    outcome=$http.outcome
    ingestion=$http.ingestion
    pipeline=$http.pipeline
  }

  if ($httpExit -ne 0 -or [string]$http.outcome -ne 'PASS') {
    $result.outcome='ANALYTICS_CHAIN_FAILED'
    Save-Evidence $result $evidencePath $latestEvidencePath
    Write-Host ("[FAIL exit=7] analytics chain exit={0} outcome={1}；证据 {2}" -f $httpExit,$http.outcome,$evidencePath)
    exit 7
  }

  if ([bool]$http.ingestion.noNewData -or
      [long]$http.ingestion.recordCount -ne $sourceLineCount -or
      [long]$http.ingestion.quarantineCount -ne 0) {
    $result.outcome='INGESTION_HANDOFF_MISMATCH'
    Save-Evidence $result $evidencePath $latestEvidencePath
    Write-Host ("[FAIL exit=7] rolling→ingestion 对账失败：source={0} accepted={1} quarantine={2} noNewData={3}；证据 {4}" -f
      $sourceLineCount,$http.ingestion.recordCount,$http.ingestion.quarantineCount,$http.ingestion.noNewData,$evidencePath)
    exit 7
  }

  if ([string]$http.pipeline.status -ne 'SUCCESS') {
    $result.outcome='PIPELINE_NOT_SUCCESS'
    Save-Evidence $result $evidencePath $latestEvidencePath
    Write-Host "[FAIL exit=7] pipeline 非 SUCCESS；证据 $evidencePath"
    exit 7
  }

  Write-Host '[2/2] 收口三程序 LocalFile 证据 ...'
  $result.outcome='PASS'
  Save-Evidence $result $evidencePath $latestEvidencePath
  Write-Host "[PASS exit=0] producer rolling → LocalFile ingestion → Spark pipeline 通过；证据 $evidencePath"
  exit 0
}
catch {
  $result.outcome='EXCEPTION'
  $result.exception=$_.Exception.Message
  try { Save-Evidence $result $evidencePath $latestEvidencePath } catch {}
  Write-Host ("[FAIL exit=7] {0}" -f $_.Exception.Message)
  exit 7
}
