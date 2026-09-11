# 真机证据：阶段证据超长截断缺陷（run 22 PUBLISH_METRIC「缺少 BUILD_ADS 真实作业证据」）
# 复现 → 修复 → 验证：对 run 22 执行 retry-from-stage BUILD_ADS（重跑真实 Spark 作业），断言
#   ① 存在阶段证据 >4000 字符（老 VARCHAR(4000) 上限），且**所有**阶段证据都是合法 JSON
#      （修复前：substring 截断停在 4000 字符 → 非法 JSON → 重试路径 stageEvidence() 静默退化空 Map
#        → PUBLISH_METRIC 报 RUN_PUBLISH_NO_ADS；实测 run 21 的 LOAD_ODS/BUILD_ADS/QUALITY_CHECK
#        证据长度恰好都是 4000）
#   ② PUBLISH_METRIC SUCCESS（修复前同一 run 在此阶段被拒）
#   ③ 指标快照真实切换为 ACTIVE（真实发布副作用，可核对）
#   ④ 导出目录残留不再是「非空目录」形态：`.jsonl` 必须是**单文件**、且无 `*.jsonl.parts` 残留
#      （真机事故：旧实现 text(target) 把目标路径写成目录，`fs.delete(target,false)` 非递归删除
#        抛 `Directory ... is not null` → mxp 一重试必挂；修复为递归清理 + 幂等覆盖）
# 用法：pwsh -NoProfile -File .verify/r8-evidence-truncation-proof.ps1 [-NoRetry]
#   -NoRetry：不触发重新跑链，只对**当前真实持久化状态**评估断言（用于重试已完成后的取证）
param([switch]$NoRetry)
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8091'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$rawDir = Join-Path $PSScriptRoot 'r8-raw'
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
function Sql([string]$q) { (& $mysql -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $q 2>$null) -join "`n" }

$token = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ username = 'admin'; password = 'admin123' } | ConvertTo-Json -Compress)).data.token
$H = @{ Authorization = "Bearer $token" }

Write-Host '=== 评估起点：run 22 阶段证据长度（CHAR_LENGTH） ==='
$before = Sql "SELECT CONCAT(stage_code,'=',status,'/ev=',CHAR_LENGTH(COALESCE(evidence,0)),'/json=',IF(JSON_VALID(evidence),'ok','BAD')) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 ORDER BY id;"
Write-Host $before

if (-not $NoRetry) {
    Write-Host "`n=== 触发 retry-from-stage BUILD_ADS（真实 Spark 作业重跑） ==="
    $reason = [uri]::EscapeDataString('R8 验收：验证阶段证据超长截断缺陷已修复')
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/pipeline-runs/22/retry-from-stage?stage=BUILD_ADS&operator=admin&reason=$reason" -Headers $H
    Write-Host ("runId={0} status={1} stage={2}" -f $r.data.runId, $r.data.status, $r.data.currentStage)
    $deadline = (Get-Date).AddMinutes(12)
    do {
        Start-Sleep -Seconds 10
        $cur = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/22" -Headers $H).data
        Write-Host ("  [{0:HH:mm:ss}] status={1} stage={2}" -f (Get-Date), $cur.status, $cur.currentStage)
    } while ($cur.status -in @('PENDING', 'RUNNING') -and (Get-Date) -lt $deadline)
} else {
    Write-Host "`n=== -NoRetry：不重跑，直接评估当前真实状态 ==="
    $cur = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/22" -Headers $H).data
    Write-Host ("runId=22 status={0} stage={1} attempt={2}" -f $cur.status, $cur.currentStage, $cur.attemptNo)
}

$cur | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $rawDir 'evidence-proof-run22.json') -Encoding UTF8
$after = Sql "SELECT CONCAT(stage_code,'=',status,'/ev=',CHAR_LENGTH(COALESCE(evidence,0)),'/json=',IF(JSON_VALID(evidence),'ok','BAD')) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 ORDER BY id;"
Write-Host "`n=== 评估终点：run 22 阶段（按落库顺序，最后一次尝试） ==="; Write-Host $after

# —— 断言取数（全部来自真实库/真实磁盘，不做估算） ——
$maxEv = [int](Sql "SELECT COALESCE(MAX(CHAR_LENGTH(evidence)),0) FROM analytics_meta.pipeline_stage_run WHERE run_id=22;")
# 本次重试实际重跑的三个阶段（retry-from-stage BUILD_ADS）——缺陷断言只针对它们
$retriedStages = @('BUILD_ADS', 'QUALITY_CHECK', 'PUBLISH_METRIC')
$retriedBad = [int](Sql "SELECT COUNT(*) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND stage_code IN ('BUILD_ADS','QUALITY_CHECK','PUBLISH_METRIC') AND evidence IS NOT NULL AND JSON_VALID(evidence)=0;")
# 修复后突破 4000 的合法 JSON 证据（修复前：所有长证据恰好停在 4000 且非法）
$over4kValid = [int](Sql "SELECT COUNT(*) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND CHAR_LENGTH(evidence)>4000 AND JSON_VALID(evidence)=1;")
$over4kLens = Sql "SELECT CONCAT(stage_code,'/',CHAR_LENGTH(evidence)) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND CHAR_LENGTH(evidence)>4000 AND JSON_VALID(evidence)=1;"
# 仍停在老上限 4000 的行：修复后不应再产生（唯一允许的是修复前历史行 LOAD_ODS）
$atBoundary = @(Sql "SELECT CONCAT(stage_code,'/',IF(JSON_VALID(evidence),'ok','BAD'),'/',LEFT(started_at,19)) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND CHAR_LENGTH(evidence)=4000;" -split "`n" | Where-Object { $_.Trim() })
$atBoundaryUnexpected = @($atBoundary | Where-Object { $_ -notmatch '^LOAD_ODS/' })
# 非 JSON 证据的白名单：①WAIT_LANDING = JSON + " | " 审计追加的混合文本（口径已登记，该阶段只被正则读取）
# ②修复前历史行（长度恰停 4000 老上限，属 run 22 早期尝试的失败现场，本次重试未执行该阶段）
$nonJsonRows = @(Sql "SELECT CONCAT(stage_code,'/',CHAR_LENGTH(evidence),'/',LEFT(started_at,19)) FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND evidence IS NOT NULL AND JSON_VALID(evidence)=0;" -split "`n" | Where-Object { $_.Trim() })
$nonJsonUnexpected = @($nonJsonRows | Where-Object { $_ -notmatch '^WAIT_LANDING/' -and $_ -notmatch '/4000/' })
$pubEv = Sql "SELECT evidence FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND stage_code='PUBLISH_METRIC' ORDER BY id DESC LIMIT 1;"
$pubLen = $pubEv.Length
$pubJsonOk = $false
try { $null = $pubEv | ConvertFrom-Json; $pubJsonOk = $true } catch { $pubJsonOk = $false }
$pubStatus = (Sql "SELECT status FROM analytics_meta.pipeline_stage_run WHERE run_id=22 AND stage_code='PUBLISH_METRIC' ORDER BY id DESC LIMIT 1;").Trim()
$mxpStatus = (Sql "SELECT status FROM analytics_meta.spark_job_run WHERE pipeline_run_id=22 AND job_code='mxp' ORDER BY id DESC LIMIT 1;").Trim()
$active = (Sql "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE status='ACTIVE' ORDER BY id DESC LIMIT 1;").Trim()
$snapRows = Sql "SELECT CONCAT(snapshot_id,'/',status) FROM analytics_metric.metric_snapshot ORDER BY id DESC LIMIT 3;"

$stageDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'metric-staging\S20260901_22'
$jsonlFiles = @(Get-ChildItem $stageDir -Filter '*.jsonl' -Force -ErrorAction SilentlyContinue | Where-Object { -not $_.PSIsContainer })
$dirLeftovers = @(Get-ChildItem $stageDir -Force -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })
$partsLeftovers = @($dirLeftovers | Where-Object { $_.Name -like '*.parts' })

$results = @()
function Say([string]$n, [bool]$ok, [string]$d) {
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host "[$tag] $n $d"
    $script:results += [pscustomobject]@{ check = $n; result = $tag; detail = $d }
}
Say 'P1 run 22 终态 SUCCESS（修复前同一 run 在 PUBLISH_METRIC 被拒）' ($cur.status -eq 'SUCCESS') "status=$($cur.status) err=$($cur.errorMessage)"
Say 'P2 存在阶段证据 >4000 字符（老 VARCHAR(4000) 上限已被突破）' ($maxEv -gt 4000) "maxStageEvidenceChars=$maxEv publishEvidenceChars=$pubLen"
Say 'P2b 本次重试重跑的 BUILD_ADS/QUALITY_CHECK/PUBLISH_METRIC 证据全部合法 JSON' ($retriedBad -eq 0) "invalidRows=$retriedBad"
Say 'P2b2 修复后多个阶段证据突破 4000 字符且合法 JSON（修复前所有长证据恰好停在 4000）' ($over4kValid -ge 2) "rows=$over4kValid lens=$over4kLens"
Say 'P2b3 「恰好 4000 字符」的行只剩修复前历史行（白名单 LOAD_ODS）' ($atBoundaryUnexpected.Count -eq 0) "atBoundary=[$($atBoundary -join ' ; ')] unexpected=[$($atBoundaryUnexpected -join ' ; ')]"
Say 'P2c 非 JSON 证据仅限白名单（WAIT_LANDING 审计追加文本 / 修复前 4000 截断历史行）' ($nonJsonUnexpected.Count -eq 0) "nonJsonRows=[$($nonJsonRows -join ' ; ')] unexpected=[$($nonJsonUnexpected -join ' ; ')]"
Say 'P3 PUBLISH_METRIC 证据是合法 JSON 且含逐作业明细' ($pubJsonOk -and $pubEv -match 'outputPartitions' -and $pubEv -match 'jobCode') "jsonOk=$pubJsonOk len=$pubLen"
Say 'P4 PUBLISH_METRIC 阶段 SUCCESS' ($pubStatus -eq 'SUCCESS') "publishStatus=$pubStatus"
Say 'P4b mxp 导出作业 SUCCESS（修复前因残留非空目录失败）' ($mxpStatus -eq 'SUCCESS') "mxpStatus=$mxpStatus"
Say 'P5 快照已真实切换 ACTIVE' ($active -eq 'S20260901_22') "active=$active snapshots=$snapRows"
Say 'P6 导出目录 8 张表均为单文件 .jsonl（非目录形态）' ($jsonlFiles.Count -eq 8) "jsonlFiles=$($jsonlFiles.Count) names=$($jsonlFiles.Name -join ',')"
Say 'P6b 导出目录无 *.jsonl.parts / 非空目录残留' ($partsLeftovers.Count -eq 0 -and $dirLeftovers.Count -eq 0) "partsLeftovers=$($partsLeftovers.Count) dirLeftovers=$($dirLeftovers.Count)"

$summary = [pscustomobject]@{
    ranAt = (Get-Date).ToString('s'); runId = 22; mode = if ($NoRetry) { 'no-retry (评估真实重试后的持久化状态)' } else { 'retry-from-stage BUILD_ADS' }
    maxStageEvidenceChars = $maxEv; publishStageEvidenceChars = $pubLen
    retriedStages = $retriedStages; retriedInvalidJsonRows = $retriedBad; over4000ValidRows = $over4kValid; over4000Lens = $over4kLens; atBoundaryRows = $atBoundary
    nonJsonEvidenceRows = $nonJsonRows; nonJsonUnexpected = $nonJsonUnexpected; activeSnapshot = $active
    stageTableBefore = $before -split "`n"; stageTableAfter = $after -split "`n"
    pass = ($results | Where-Object { $_.result -eq 'PASS' }).Count
    fail = ($results | Where-Object { $_.result -eq 'FAIL' }).Count
    checks = $results
}
$summary | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $PSScriptRoot 'r8-evidence-truncation-proof.json') -Encoding UTF8
Write-Host "`nPASS=$($summary.pass) FAIL=$($summary.fail) → .verify/r8-evidence-truncation-proof.json"
if ($summary.fail -gt 0) { exit 1 }


