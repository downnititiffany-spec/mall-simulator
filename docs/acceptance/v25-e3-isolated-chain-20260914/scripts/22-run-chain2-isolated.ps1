<#
V25-E3 | 第二次真链（隔离实例）：用**无重复 event_id** 的夹具做一次干净链。

第一次（run 1，见 20-*.ps1 / raw/http/0*.json）在 QUALITY_CHECK 被**合法阻断**：
  EVENT_ID_UNIQUE 重复率=0.010989 > 0.0005（golden-r73-clean 夹具里 golden-evt-008 出现 2 次）
  ⇒ errorCode=PIPELINE_QUALITY_FAILED，发布未执行（这是规则的正确行为，不是环境缺陷）。

本次做法（不修改任何夹具原文、不修改任何配置）：
  · 采集是**断点续采**（file_checkpoint：runtime_profile + source_id + 文件绝对路径 → offset）。
    已消费到 EOF 的文件不会重采，因此把同一份**无重复**夹具 gen-s3b-1000-20260911.jsonl
    以**新文件名** r2 投递（同一内容、新路径 ⇒ 新断点 ⇒ 作为新交付文件被采集）。
  · 新批次 → 新 manifest（READY）→ 新 pipeline run（新 sourceDataVersion）走完整七阶段。
  · 本脚本只调 HTTP 接口，不执行任何 SQL 写；产物写 raw/http/4*.json 与 raw/22-chain2-summary.json，
    不覆盖第一次的证据。

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
  [string]$SourceDataVersion = 'v25it-20260914-1358-l4e3-r2',
  [int]$TimeoutSec = 900,
  [int]$PollSec = 5,
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scratch = "D:\Develop_code\GraduationProject\target\e3-run\$RunId"
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

# ── 步骤 0：投递新文件（新路径=新断点）────────────────────────────────
$eventsDir = Join-Path $scratch 'landing\events'
$src = Join-Path $eventsDir 'gen-s3b-1000-20260911.jsonl'
$dst = Join-Path $eventsDir 'gen-s3b-1000-20260911-r2.jsonl'
if (-not (Test-Path $src)) { Write-Host "缺少源夹具 $src"; exit 2 }
Copy-Item $src $dst -Force
$sha = (Get-FileHash $src -Algorithm SHA256).Hash
$sha2 = (Get-FileHash $dst -Algorithm SHA256).Hash
Write-Host "[E3-0/3] 投递新文件 $([IO.Path]::GetFileName($dst))  SHA256=$sha2（与源一致=$($sha -eq $sha2)）"
$summary['deliveredFile'] = $dst
$summary['deliveredFileSha256'] = $sha2
$summary['deliveredFileSameAsSource'] = ($sha -eq $sha2)

Write-Host "[E3-1/3] 登录取 token  $BaseUrl"
$login = Call 'POST' "$BaseUrl/api/v1/auth/login" $null (@{ username = $Username; password = $Password } | ConvertTo-Json)
$null = Save '40-login' $login
$token = ($login.Content | ConvertFrom-Json).data.token
if (-not $token) { Write-Host '登录失败：无 token'; exit 2 }
$headers = @{ Authorization = "Bearer $token" }
$summary['loginHttp'] = [int]$login.StatusCode

Write-Host '[E3-步1] POST /api/v1/ingestion/runs'
$ing = Call 'POST' "$BaseUrl/api/v1/ingestion/runs" $headers $null
$null = Save '41-ingestion-run' $ing
$summary['ingestionHttp'] = [int]$ing.StatusCode
$summary['ingestionData'] = ($ing.Content | ConvertFrom-Json).data
$st = Call 'GET' "$BaseUrl/api/v1/ingestion/status" $headers $null
$null = Save '41b-ingestion-status' $st
$bt = Call 'GET' "$BaseUrl/api/v1/ingestion/batches?limit=10" $headers $null
$null = Save '41c-ingestion-batches' $bt

Write-Host "[E3-步2] POST /api/v1/pipeline-runs businessTime=$BusinessTime profile=$RuntimeProfileId pipeline=$PipelineCode sourceDataVersion=$SourceDataVersion"
$body = @{ runtimeProfileId = $RuntimeProfileId; pipelineCode = $PipelineCode; businessTime = $BusinessTime; sourceDataVersion = $SourceDataVersion } | ConvertTo-Json
$created = Call 'POST' "$BaseUrl/api/v1/pipeline-runs" $headers $body
$null = Save '42-pipeline-run-created' $created
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
$null = Save '42b-pipeline-run-final' $run
$rjFinal = $run.Content | ConvertFrom-Json
$summary['runStatus'] = $rjFinal.data.status
$summary['runStage'] = $rjFinal.data.currentStage
$summary['runErrorCode'] = $rjFinal.data.errorCode
$summary['runSnapshot'] = $rjFinal.data.targetSnapshotId
$summary['elapsedSec'] = [int]((Get-Date) - $t0).TotalSeconds
$trace | Set-Content (Join-Path $httpDir '42c-pipeline-run-poll-trace.txt') -Encoding utf8

Write-Host '[E3-步3] GET /api/v1/metrics/overview'
$ov = Call 'GET' "$BaseUrl/api/v1/metrics/overview" $headers $null
$null = Save '43-metrics-overview' $ov
$summary['overviewHttp'] = [int]$ov.StatusCode
$summary['overviewBodyLength'] = ([string]$ov.Content).Length
$q = Call 'GET' "$BaseUrl/api/v1/metrics/quality?limit=20" $headers $null
$null = Save '43b-metrics-quality' $q
$summary['qualityHttp'] = [int]$q.StatusCode
$sum = Call 'GET' "$BaseUrl/api/v1/metrics/summary" $headers $null
$null = Save '43c-metrics-summary' $sum
$summary['summaryHttp'] = [int]$sum.StatusCode

$summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceRoot 'raw\22-chain2-summary.json') -Encoding utf8
Write-Host ''
Write-Host ("[E3 汇总2] 步1 HTTP {0} / 步2 HTTP {1} runId={2} 终态={3} / 步3 HTTP {4}" -f $summary['ingestionHttp'], $summary['pipelineCreateHttp'], $summary['runId'], $summary['runStatus'], $summary['overviewHttp'])
if ($summary['runStatus'] -ne 'SUCCESS') { exit 4 }
exit 0
