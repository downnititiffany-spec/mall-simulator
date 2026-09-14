<#
V25-E3 | 三步真链驱动（隔离实例版）——每一步都留 HTTP 状态码 + 原始响应体（已脱敏 token）。

  步骤 1  POST /api/v1/ingestion/runs        （采集：landing/events → accepted + manifests）
  步骤 2  POST /api/v1/pipeline-runs         （编排：Spark 真实提交，轮询到终态）
  步骤 3  GET  /api/v1/metrics/overview      （指标读取链路）

与 scripts/smoke-pipeline.ps1 的区别：本脚本**不碰 3306 的库名/口令**，不做任何 SQL 写；
所有落盘在 docs/acceptance/v25-e3-isolated-chain-20260914/raw/http/ 下。

用法：pwsh -File <this>
#>
param(
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$Username = 'admin',
  [string]$Password = 'admin123',
  [string]$RunId = 'v25it-20260914-1358-l4e3',
  [string]$BusinessTime = '2026-09-01T00:00:00',
  [string]$PipelineCode = 'ODS_TO_ADS',
  [int]$RuntimeProfileId = 1,
  [string]$SourceDataVersion = 'v25it-20260914-1358-l4e3',
  [int]$TimeoutSec = 900,
  [int]$PollSec = 5,
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$httpDir = Join-Path $EvidenceRoot 'raw\http'
New-Item -ItemType Directory -Force -Path $httpDir | Out-Null
$summary = [ordered]@{}
function Redact([string]$s) { if ($null -eq $s) { return '' } return ($s -replace '"token"\s*:\s*"[^"]*"', '"token":"<REDACTED>"') }

function Save([string]$name, $resp) {
  $path = Join-Path $httpDir "$name.json"
  $body = Redact ([string]$resp.Content)
  $rec = [ordered]@{ step = $name; status = [int]$resp.StatusCode; url = $resp.RequestMessage.RequestUri.AbsoluteUri; body = $body }
  ($rec | ConvertTo-Json -Depth 8) | Set-Content -Path $path -Encoding utf8
  Write-Host ("      {0,-28} HTTP {1}  ({2} bytes)" -f $name, [int]$resp.StatusCode, $body.Length)
  return $body
}
function Call([string]$method, [string]$uri, $headers, $body) {
  $p = @{ Method = $method; Uri = $uri; SkipHttpErrorCheck = $true; TimeoutSec = 180 }
  if ($headers) { $p.Headers = $headers }
  if ($body) { $p.ContentType = 'application/json'; $p.Body = $body }
  return Invoke-WebRequest @p
}

Write-Host "[E3-1/3] 登录取 token  $BaseUrl"
$login = Call 'POST' "$BaseUrl/api/v1/auth/login" $null (@{ username = $Username; password = $Password } | ConvertTo-Json)
$null = Save '00-login' $login
$loginJson = $login.Content | ConvertFrom-Json
$token = $loginJson.data.token
if (-not $token) { Write-Host '登录失败：无 token'; exit 2 }
$headers = @{ Authorization = "Bearer $token" }
$summary['loginHttp'] = [int]$login.StatusCode

# ── 步骤 1：采集 ─────────────────────────────────────────────────────
Write-Host '[E3-步1] POST /api/v1/ingestion/runs（landing/events → accepted + manifest）'
$ing = Call 'POST' "$BaseUrl/api/v1/ingestion/runs" $headers $null
$null = Save '01-ingestion-run' $ing
$summary['ingestionHttp'] = [int]$ing.StatusCode
$ingJson = $ing.Content | ConvertFrom-Json
$summary['ingestionData'] = $ingJson.data
$st = Call 'GET' "$BaseUrl/api/v1/ingestion/status" $headers $null
$null = Save '01b-ingestion-status' $st
$bt = Call 'GET' "$BaseUrl/api/v1/ingestion/batches?limit=10" $headers $null
$null = Save '01c-ingestion-batches' $bt

# ── 步骤 2：编排（真实 Spark 提交）────────────────────────────────────
Write-Host "[E3-步2] POST /api/v1/pipeline-runs businessTime=$BusinessTime profile=$RuntimeProfileId pipeline=$PipelineCode"
$body = @{ runtimeProfileId = $RuntimeProfileId; pipelineCode = $PipelineCode; businessTime = $BusinessTime; sourceDataVersion = $SourceDataVersion } | ConvertTo-Json
$created = Call 'POST' "$BaseUrl/api/v1/pipeline-runs" $headers $body
$null = Save '02-pipeline-run-created' $created
$summary['pipelineCreateHttp'] = [int]$created.StatusCode
$runId = ($created.Content | ConvertFrom-Json).data.runId
if (-not $runId) { Write-Host '未能取得 runId'; exit 3 }
Write-Host "      runId=$runId"
$summary['runId'] = $runId

$terminal = @('SUCCESS','FAILED','RUN_INTERRUPTED','CANCELLED','DEGRADED')
$t0 = Get-Date; $deadline = $t0.AddSeconds($TimeoutSec); $run = $null
$trace = @()
while ($true) {
  Start-Sleep -Seconds $PollSec
  $run = Call 'GET' "$BaseUrl/api/v1/pipeline-runs/$runId" $headers $null
  $rj = $run.Content | ConvertFrom-Json
  $el = [int]((Get-Date) - $t0).TotalSeconds
  $line = "[{0,4}s] status={1} stage={2} error={3}" -f $el, $rj.data.status, $rj.data.currentStage, $rj.data.errorCode
  Write-Host "      $line"
  $trace += $line
  if ($terminal -contains $rj.data.status) { break }
  if ((Get-Date) -gt $deadline) { Write-Host '      超时（未达终态，如实记录）'; break }
}
$null = Save '02b-pipeline-run-final' $run
$rjFinal = $run.Content | ConvertFrom-Json
$summary['runStatus'] = $rjFinal.data.status
$summary['runStage'] = $rjFinal.data.currentStage
$summary['runErrorCode'] = $rjFinal.data.errorCode
$summary['runSnapshot'] = $rjFinal.data.targetSnapshotId
$summary['elapsedSec'] = [int]((Get-Date) - $t0).TotalSeconds
$trace | Set-Content (Join-Path $httpDir '02c-pipeline-run-poll-trace.txt') -Encoding utf8

# 阶段/作业/质量规则原文：用**只读 SQL**（下一步 21-dump-meta-evidence.ps1）取，
# 因为 PipelineController 没有 /stages 子资源（已核对 line 45/55/61/68）。

# ── 步骤 3：指标读取 ─────────────────────────────────────────────────
Write-Host '[E3-步3] GET /api/v1/metrics/overview'
$ov = Call 'GET' "$BaseUrl/api/v1/metrics/overview" $headers $null
$null = Save '03-metrics-overview' $ov
$summary['overviewHttp'] = [int]$ov.StatusCode
$q = Call 'GET' "$BaseUrl/api/v1/metrics/quality?limit=20" $headers $null
$null = Save '03b-metrics-quality' $q
$summary['qualityHttp'] = [int]$q.StatusCode

$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceRoot 'raw\20-chain-summary.json') -Encoding utf8
Write-Host ''
Write-Host ("[E3 汇总] 步1 HTTP {0} / 步2 HTTP {1} runId={2} 终态={3} / 步3 HTTP {4}" -f $summary['ingestionHttp'], $summary['pipelineCreateHttp'], $summary['runId'], $summary['runStatus'], $summary['overviewHttp'])
if ($summary['runStatus'] -ne 'SUCCESS') { exit 4 }
exit 0
