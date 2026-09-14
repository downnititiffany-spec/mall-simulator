<#
V26-F88 | S4 真实链路驱动（隔离实例版）——每步留 HTTP 状态码 + 原始响应体（token 已脱敏）。
  步骤 1  POST /api/v1/ingestion/runs
  步骤 2  POST /api/v1/pipeline-runs  → 轮询到终态
  步骤 3  GET  /api/v1/metrics/overview
不做任何 SQL 写；所有落盘在 raw/http/ 下。链路失败（如 PIPELINE_QUALITY_FAILED）**不是脚本错误**，
照常落盘并以 exit 4 标记「已取证但非 SUCCESS」，供报告分级使用。
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$Username = 'admin',
  [string]$Password = 'admin123',
  [string]$BusinessTime = '2026-09-01T00:00:00',
  [string]$PipelineCode = 'ODS_TO_ADS',
  [int]$RuntimeProfileId = 1,
  [string]$SourceDataVersion,
  [int]$TimeoutSec = 900,
  [int]$PollSec = 5,
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v26-f88-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if (-not $SourceDataVersion) { $SourceDataVersion = $RunId }
if ($SourceDataVersion.Length -gt 64) { throw "sourceDataVersion 超过 64 字符: $SourceDataVersion" }

$httpDir = Join-Path $EvidenceRoot 'raw\http'
New-Item -ItemType Directory -Force -Path $httpDir | Out-Null
$summary = [ordered]@{ runId = $RunId; sourceDataVersion = $SourceDataVersion; startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') }
function Redact([string]$s) { if ($null -eq $s) { return '' } return ($s -replace '"token"\s*:\s*"[^"]*"', '"token":"<REDACTED>"') }
function Save([string]$name, $resp) {
  $path = Join-Path $httpDir "$name.json"
  $body = Redact ([string]$resp.Content)
  $rec = [ordered]@{ step = $name; status = [int]$resp.StatusCode; url = $resp.RequestMessage.RequestUri.AbsoluteUri; body = $body }
  ($rec | ConvertTo-Json -Depth 8) | Set-Content -Path $path -Encoding utf8
  Write-Host ("      {0,-30} HTTP {1}  ({2} bytes)" -f $name, [int]$resp.StatusCode, $body.Length)
  return $body
}
function Call([string]$method, [string]$uri, $headers, $body) {
  $p = @{ Method = $method; Uri = $uri; SkipHttpErrorCheck = $true; TimeoutSec = 240 }
  if ($headers) { $p.Headers = $headers }
  if ($body) { $p.ContentType = 'application/json'; $p.Body = $body }
  return Invoke-WebRequest @p
}

Write-Host "[F88-0/4] 健康检查 $BaseUrl/api/v1/health"
$hc = Call 'GET' "$BaseUrl/api/v1/health" $null $null
$null = Save '00-health' $hc
$summary['healthHttp'] = [int]$hc.StatusCode

Write-Host "[F88-1/4] 登录取 token"
$login = Call 'POST' "$BaseUrl/api/v1/auth/login" $null (@{ username = $Username; password = $Password } | ConvertTo-Json)
$null = Save '01-login' $login
$loginJson = $login.Content | ConvertFrom-Json
$token = $loginJson.data.token
if (-not $token) { Write-Host '登录失败：无 token'; exit 2 }
$headers = @{ Authorization = "Bearer $token" }
$summary['loginHttp'] = [int]$login.StatusCode

Write-Host '[F88-2/4] POST /api/v1/ingestion/runs'
$ing = Call 'POST' "$BaseUrl/api/v1/ingestion/runs" $headers $null
$null = Save '02-ingestion-run' $ing
$summary['ingestionHttp'] = [int]$ing.StatusCode
$ingJson = $ing.Content | ConvertFrom-Json
$summary['ingestionData'] = $ingJson.data
$st = Call 'GET' "$BaseUrl/api/v1/ingestion/status" $headers $null
$null = Save '02b-ingestion-status' $st
$bt = Call 'GET' "$BaseUrl/api/v1/ingestion/batches?limit=10" $headers $null
$null = Save '02c-ingestion-batches' $bt

Write-Host "[F88-3/4] POST /api/v1/pipeline-runs businessTime=$BusinessTime profile=$RuntimeProfileId pipeline=$PipelineCode"
$body = @{ runtimeProfileId = $RuntimeProfileId; pipelineCode = $PipelineCode; businessTime = $BusinessTime; sourceDataVersion = $SourceDataVersion } | ConvertTo-Json
$created = Call 'POST' "$BaseUrl/api/v1/pipeline-runs" $headers $body
$null = Save '03-pipeline-run-created' $created
$summary['pipelineCreateHttp'] = [int]$created.StatusCode
$runIdVal = ($created.Content | ConvertFrom-Json).data.runId
if (-not $runIdVal) { Write-Host '未能取得 runId'; exit 3 }
Write-Host "      pipelineRunId=$runIdVal"
$summary['pipelineRunId'] = $runIdVal

$terminal = @('SUCCESS', 'FAILED', 'RUN_INTERRUPTED', 'CANCELLED', 'DEGRADED')
$t0 = Get-Date; $deadline = $t0.AddSeconds($TimeoutSec); $run = $null; $trace = @()
while ($true) {
  Start-Sleep -Seconds $PollSec
  $run = Call 'GET' "$BaseUrl/api/v1/pipeline-runs/$runIdVal" $headers $null
  $rj = $run.Content | ConvertFrom-Json
  $el = [int]((Get-Date) - $t0).TotalSeconds
  $line = "[{0,4}s] status={1} stage={2} error={3}" -f $el, $rj.data.status, $rj.data.currentStage, $rj.data.errorCode
  Write-Host "      $line"
  $trace += $line
  if ($terminal -contains $rj.data.status) { break }
  if ((Get-Date) -gt $deadline) { Write-Host '      超时（未达终态，如实记录）'; break }
}
$null = Save '03b-pipeline-run-final' $run
$rjFinal = $run.Content | ConvertFrom-Json
$summary['runStatus'] = $rjFinal.data.status
$summary['runStage'] = $rjFinal.data.currentStage
$summary['runErrorCode'] = $rjFinal.data.errorCode
$summary['runSnapshot'] = $rjFinal.data.targetSnapshotId
$summary['elapsedSec'] = [int]((Get-Date) - $t0).TotalSeconds
$trace | Set-Content (Join-Path $httpDir '03c-pipeline-run-poll-trace.txt') -Encoding utf8

Write-Host '[F88-4/4] GET /api/v1/metrics/overview'
$ov = Call 'GET' "$BaseUrl/api/v1/metrics/overview" $headers $null
$null = Save '04-metrics-overview' $ov
$summary['overviewHttp'] = [int]$ov.StatusCode
$q = Call 'GET' "$BaseUrl/api/v1/metrics/quality?limit=50" $headers $null
$null = Save '04b-metrics-quality' $q
$summary['qualityHttp'] = [int]$q.StatusCode

$summary['finishedAt'] = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceRoot 'raw\30-chain-summary.json') -Encoding utf8
Write-Host ''
Write-Host ("[F88 汇总] 采集 HTTP {0} / 编排 HTTP {1} pipelineRunId={2} 终态={3} errorCode={4} / 指标 HTTP {5}" -f `
  $summary['ingestionHttp'], $summary['pipelineCreateHttp'], $summary['pipelineRunId'], $summary['runStatus'], $summary['runErrorCode'], $summary['overviewHttp'])
if ($summary['runStatus'] -ne 'SUCCESS') { exit 4 }
exit 0
