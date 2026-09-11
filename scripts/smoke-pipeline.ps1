<#
本地真实链路小规模冒烟（平台 API 驱动，小规模：单业务日、单快照）

用途：每完成一处数仓/质量/发布改动，用**同一条真实链路**验证端到端结果，而不是只跑单测。
依赖：平台已启动（scripts\start-all.ps1 -PlatformOnly），MySQL 可连，spark-jobs jar 已按最新代码打包。

流程：
  [1/7] 登录取 token
  [2/7] 记录基线（pipeline_run 最大 id、metric_value 总行数）
  [3/7] POST /api/v1/pipeline-runs（Idempotency-Key 头）创建真实 run
  [4/7] 轮询 GET /api/v1/pipeline-runs/{id} 直到终态
  [5/7] 收集阶段/作业状态 + data_quality_result 逐条规则
  [6/7] 收集发布结果（发布库 metric_value 行数 + 快照注册状态 + /metrics/* 响应）
  [7/7] 落证据（JSON + Markdown）并按门禁判定退出码

库归属（application.yml 已冻结）：pipeline_run / pipeline_stage_run / spark_job_run /
data_quality_result 在 analytics_meta；metric_value / metric_snapshot（发布库=读库）在
analytics_metric（`-MetricDb` 可覆盖）。analytics_meta 下另有历史遗留的 metric_* 表，
**不得**用它判定发布结果（口径错误会让"未发布"看起来像已发布）。

退出码：0 = 门禁全过；2 = 链路未达成功态；3 = 存在 BLOCKING 质量失败；4 = 未发布任何指标值。

示例：
  pwsh scripts\smoke-pipeline.ps1 -BusinessTime '2026-09-01T00:00:00' -SourceDataVersion 'm1-4s3b-gen1000-def05fix'
#>
param(
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$Username = 'admin',
  [string]$Password = 'admin123',
  [long]$RuntimeProfileId = 1,
  [string]$PipelineCode = 'ODS_TO_ADS',
  [Parameter(Mandatory = $true)][string]$BusinessTime,
  [string]$SourceDataVersion = '',
  [string]$IdempotencyKey = '',
  [int]$TimeoutSec = 900,
  [int]$PollSec = 10,
  [string]$Name = 'smoke',
  [string]$OutDir = 'docs\acceptance',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$MysqlUser = 'root',
  [string]$MetricDb = 'analytics_metric',
  [string]$MysqlPassword = '123456'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Q([string]$sql) {
  $out = & $MysqlExe "-u$MysqlUser" "-p$MysqlPassword" -N -B --default-character-set=utf8mb4 -e $sql 2>$null
  return @($out | Where-Object { $_ -ne '' })
}
function Cells([string]$line) { return ($line -split "`t") }
# 单元格取值必须显式两步：`[long](Cells $row)[0]` 会被 PowerShell 解析成
# `([long](Cells $row))[0]`（先整体转数值再索引），实测把 `10<TAB>3963.2000` 读成 49 —— 静默错数。
function CellLong([string]$line, [int]$i) {
  $c = Cells $line
  if ($c.Count -le $i) { return 0 }
  $v = 0L; [void][long]::TryParse($c[$i], [ref]$v); return $v
}
function CellStr([string]$line, [int]$i) {
  $c = Cells $line
  if ($c.Count -le $i) { return '' }
  return $c[$i]
}

Write-Host "[1/7] 登录 $BaseUrl"
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' `
  -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
if (-not $login.data.token) { Write-Host '登录失败：无 token'; exit 2 }
$headers = @{ Authorization = "Bearer $($login.data.token)" }

Write-Host '[2/7] 基线'
$beforeMaxRun = [long](Q 'SELECT COALESCE(MAX(id),0) FROM analytics_meta.pipeline_run' | Select-Object -First 1)
$beforeMetricRows = [long](Q "SELECT COUNT(*) FROM $MetricDb.metric_value" | Select-Object -First 1)
Write-Host "      pipeline_run maxId=$beforeMaxRun, $MetricDb.metric_value rows=$beforeMetricRows"

Write-Host "[3/7] 创建 run：businessTime=$BusinessTime profile=$RuntimeProfileId"
$body = @{ runtimeProfileId = $RuntimeProfileId; pipelineCode = $PipelineCode; businessTime = $BusinessTime }
if ($SourceDataVersion) { $body.sourceDataVersion = $SourceDataVersion }
$hdr = @{} + $headers
if ($IdempotencyKey) { $hdr['Idempotency-Key'] = $IdempotencyKey }
$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/pipeline-runs" -Headers $hdr `
  -ContentType 'application/json' -Body ($body | ConvertTo-Json)
$runId = $created.data.runId
if (-not $runId) { $runId = [long](Q 'SELECT MAX(id) FROM analytics_meta.pipeline_run' | Select-Object -First 1) }
Write-Host "      runId=$runId snapshot(预期)=$($created.data.targetSnapshotId)"

Write-Host "[4/7] 轮询终态（最多 $TimeoutSec s）"
$deadline = (Get-Date).AddSeconds($TimeoutSec); $run = $null; $t0 = Get-Date
$terminal = @('SUCCESS', 'FAILED', 'RUN_INTERRUPTED', 'CANCELLED', 'DEGRADED')
while ($true) {
  Start-Sleep -Seconds $PollSec
  $run = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/pipeline-runs/$runId" -Headers $headers
  $st = $run.data.status
  $el = [int]((Get-Date) - $t0).TotalSeconds
  Write-Host ("      [{0,4}s] {1} stage={2}" -f $el, $st, $run.data.currentStage)
  if ($terminal -contains $st) { break }
  if ((Get-Date) -gt $deadline) { Write-Host '      超时，按非终态处理'; break }
}
$status = $run.data.status
$snapshot = $run.data.targetSnapshotId

Write-Host '[5/7] 阶段/作业/质量规则'
$stages = Q "SELECT stage_code,status,records,COALESCE(error_code,'') FROM analytics_meta.pipeline_stage_run WHERE run_id=$runId ORDER BY id"
$jobs = Q "SELECT job_code,status,input_records,output_records,rejected_records FROM analytics_meta.spark_job_run WHERE pipeline_run_id=$runId ORDER BY id"
$rules = Q "SELECT rule_code,severity,passed,check_count,error_count,COALESCE(detail,'') FROM analytics_meta.data_quality_result WHERE run_id=$runId ORDER BY id"
$blockingFail = @($rules | Where-Object { $c = Cells $_; $c[1] -eq 'BLOCKING' -and $c[2] -ne '1' })
$jobFail = @($jobs | Where-Object { (Cells $_)[1] -ne 'SUCCESS' })

Write-Host '[6/7] 发布结果'
# DEF-09 二次现场（run 39 实测）：**别用 `$x = if (...) { @(Q ...) }`**
# `if` 当表达式用时，块内输出会被重新摊平成流：单行结果 → 赋给变量后是**字符串**而不是数组，
# 于是 `$pubRaw[0]` 退化成"取首字符"（`10<TAB>285.6800` → `1`），行数报错、合计变空，门禁还被蒙过去。
# 正确写法：先在赋值处 `@(...)`，再做 if 分支。
$pubRaw = @()
if ($snapshot) { $pubRaw = @(Q "SELECT COUNT(*),COALESCE(SUM(metric_value),0) FROM $MetricDb.metric_value WHERE snapshot_id='$snapshot'") }
$pubRows = if ($pubRaw.Count -gt 0) { CellLong $pubRaw[0] 0 } else { 0 }
$pubSum = if ($pubRaw.Count -gt 0) { CellStr $pubRaw[0] 1 } else { '0' }
# 解析自检：SQL 选了两列，正确解析必然得到 ≥2 个单元格；只有 1 个就说明又踩了摊平成标量的坑。
if ($pubRaw.Count -gt 0 -and (Cells $pubRaw[0]).Count -lt 2) {
  Write-Host "门禁：发布行数解析异常（单元格数=$((Cells $pubRaw[0]).Count)，原文=[$($pubRaw[0])]）"; exit 5
}
# 快照注册状态：SUCCESS 的 run 应留下 ACTIVE 快照；失败 run 允许缺席（发布阶段未执行）
$snapReg = @()
if ($snapshot) { $snapReg = @(Q "SELECT snapshot_id,status,source,pipeline_run_id FROM $MetricDb.metric_snapshot WHERE snapshot_id='$snapshot'") }
$snapRegText = if ($snapReg.Count -gt 0) { $snapReg[0] } else { '(无注册行)' }
if ($snapReg.Count -gt 0 -and (Cells $snapReg[0]).Count -lt 4) {
  Write-Host "门禁：快照注册解析异常（单元格数=$((Cells $snapReg[0]).Count)，原文=[$($snapReg[0])]）"; exit 5
}
$afterMetricRows = [long](Q "SELECT COUNT(*) FROM $MetricDb.metric_value" | Select-Object -First 1)
$overview = $null; $quality = $null
try { $overview = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/metrics/overview" -Headers $headers } catch { Write-Host "      /metrics/overview 失败: $($_.Exception.Message)" }
try { $quality = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/metrics/quality?limit=20" -Headers $headers } catch { Write-Host "      /metrics/quality 失败: $($_.Exception.Message)" }

Write-Host '[7/7] 落证据'
$evidence = [ordered]@{
  name            = $Name
  timestamp       = (Get-Date).ToString('s')
  runId           = $runId
  status          = $status
  snapshot        = $snapshot
  businessTime    = $BusinessTime
  sourceDataVersion = $SourceDataVersion
  idempotencyKey  = $created.data.idempotencyKey
  errorCode       = $run.data.errorCode
  currentStage    = $run.data.currentStage
  baseline        = @{ metricValueRows = $beforeMetricRows; maxRunId = $beforeMaxRun }
  stages          = $stages
  jobs            = $jobs
  rules           = $rules
  published       = @{ metricDb = $MetricDb; snapshotRows = $pubRows; snapshotValueSum = $pubSum
                       metricValueRowsTotal = $afterMetricRows; snapshotRegistry = $snapRegText }
  metricOverview  = $overview.data
  metricQuality   = $quality.data
}
$jsonPath = Join-Path $OutDir "$Name-$stamp.json"
$evidence | ConvertTo-Json -Depth 12 | Set-Content $jsonPath -Encoding utf8

$md = @()
$md += "# 真实链路冒烟记录（$Name）"
$md += ''
$md += "- 时间：$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
$md += "- 平台：$BaseUrl（runtimeProfileId=$RuntimeProfileId，pipeline=$PipelineCode）"
$md += "- businessTime=$BusinessTime；sourceDataVersion=$SourceDataVersion"
$md += "- runId=**$runId**；快照=**$snapshot**；终态=**$status**；耗时 $([int]((Get-Date) - $t0).TotalSeconds) s"
$md += ''
$md += '## 阶段'
$md += ''
$md += '| stage | status | records | error |'
$md += '| --- | --- | --- | --- |'
foreach ($s in $stages) { $c = Cells $s; $md += "| $($c[0]) | $($c[1]) | $($c[2]) | $($c[3]) |" }
$md += ''
$md += '## 作业'
$md += ''
$md += '| job | status | in | out | rejected |'
$md += '| --- | --- | --- | --- | --- |'
foreach ($j in $jobs) { $c = Cells $j; $md += "| $($c[0]) | $($c[1]) | $($c[2]) | $($c[3]) | $($c[4]) |" }
$md += ''
$md += '## 质量规则（data_quality_result）'
$md += ''
$md += '| rule | severity | passed | check | error | detail |'
$md += '| --- | --- | --- | --- | --- | --- |'
foreach ($r in $rules) { $c = Cells $r; $md += "| $($c[0]) | $($c[1]) | $($c[2]) | $($c[3]) | $($c[4]) | $($c[5]) |" }
$md += ''
$md += '## 发布结果'
$md += ''
$md += "- 快照 $snapshot 的 $MetricDb.metric_value 行数：**$pubRows**（值合计 $pubSum）"
$md += "- $MetricDb.metric_value 总行数：$beforeMetricRows → $afterMetricRows"
$md += "- 快照注册（$MetricDb.metric_snapshot）：$snapRegText"
$md += ''
$mdPath = Join-Path $OutDir "$Name-$stamp.md"
$md | Set-Content $mdPath -Encoding utf8

Write-Host ''
Write-Host "run $runId 终态=$status 快照=$snapshot"
Write-Host "阶段 $(($stages | Where-Object { (Cells $_)[1] -ne 'SUCCESS' }).Count) 个非 SUCCESS / 共 $($stages.Count)"
Write-Host "作业 $(($jobs | Where-Object { (Cells $_)[1] -ne 'SUCCESS' }).Count) 个非 SUCCESS / 共 $($jobs.Count)"
Write-Host "BLOCKING 失败 $($blockingFail.Count) 条；发布行数 $pubRows"
Write-Host "证据：$mdPath / $jsonPath"

if ($status -ne 'SUCCESS') { Write-Host '门禁：链路未达 SUCCESS'; exit 2 }
if ($blockingFail.Count -gt 0) { Write-Host '门禁：存在 BLOCKING 质量失败'; exit 3 }
if ($pubRows -le 0) { Write-Host '门禁：未发布任何指标值'; exit 4 }
Write-Host '门禁：全过'
exit 0
