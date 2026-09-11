# R9 可靠性实验（§30 可靠性清单 + §23.3 必测故障，全部真机）
# 用法: pwsh -File .verify\r9-reliability.ps1 -EvidenceDir <docs\acceptance\r9-...> [-WithRestart] [-SkipQuality]
# 实验（每个都落真实证据）：
#   C1 同键重复运行 → 幂等返回同一 run
#   C2 同键并发双击 6 次 → 只产生 1 个 run（内存锁 + DB 唯一键）
#   C3 失败重试只从失败阶段开始（用 run 22 的真实 retry-from-stage 时间线证明）
#   C4 质量失败不污染：篡改金额夹具 + 隔离业务日期(2026-09-03) → 发布被阻断、ACTIVE 与 dt=20260901 正式分区不变
#   C5（-WithRestart）RUNNING 中重启平台进程 → 恢复后可查询并 resume 续跑
param(
  [Parameter(Mandatory = $true)][string]$EvidenceDir,
  [switch]$WithRestart,
  [switch]$SkipQuality
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$base = 'http://127.0.0.1:8091'
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$tag = 'r9-rel'
$log = "$root\.verify\$tag.log"
$out = Join-Path $root $EvidenceDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$rows = New-Object System.Collections.Generic.List[string]
function Say($m) { Write-Host $m; Add-Content $log $m }
function Ck($id, $expect, $actual, $ok, $note = '') {
  $rows.Add(("{0}`t{1}`t{2}`t{3}`t{4}" -f $id, $expect, $actual, $(if ($ok) { 'PASS' } else { 'FAIL' }), $note))
  Write-Host ("[{0}] {1} expect={2} actual={3} {4}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $id, $expect, $actual, $note)
}
function Q([string]$sql) { & $MYSQL -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1 | Where-Object { $_ -notmatch 'Using a password' } }
function One([string]$sql) { (Q $sql | Select-Object -First 1) }
function Login([string]$u, [string]$p) { (Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body (@{ username = $u; password = $p } | ConvertTo-Json)).data.token }

$admin = Login 'admin' 'admin123'
$hAdmin = @{ Authorization = "Bearer $admin" }
$beforeActive = (One "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;").Trim()
$beforeSnapCount = [int](One "SELECT COUNT(*) FROM analytics_metric.metric_snapshot;")
Say "START active=$beforeActive snapshots=$beforeSnapCount"
$rows.Add("# R9 可靠性实验（$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')，commit $((git -C $root rev-parse --short HEAD).Trim())）")
$rows.Add("# 基线：ACTIVE=$beforeActive 快照总数=$beforeSnapCount")
$rows.Add("check`t期望`t实测`t结果`t备注")

# ---------- C1 幂等：同键两次 ----------
$ver = "r9-idem-$(Get-Date -Format 'HHmmss')"
$body = @{ runtimeProfileId = 1; pipelineCode = 'ODS_TO_ADS'; businessTime = '2026-09-01T00:00:00'; sourceDataVersion = 'r9-idem-probe' } | ConvertTo-Json
$r1 = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/pipeline-runs" -Headers $hAdmin -ContentType 'application/json' -Body $body).data
$r2 = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/pipeline-runs" -Headers $hAdmin -ContentType 'application/json' -Body $body).data
Ck 'C1 同键重复 POST 幂等' '两次返回同一 runId' "run1=$($r1.runId) run2=$($r2.runId)" ($r1.runId -eq $r2.runId) "幂等键=runtimeProfileId+pipelineCode+businessTime+sourceDataVersion"

# ---------- C2 并发双击（同键 6 并发） ----------
$jobs = 1..6 | ForEach-Object {
  Start-Job -ScriptBlock {
    param($b, $t)
    try { (Invoke-RestMethod -Method Post -Uri "$using:base/api/v1/pipeline-runs" -Headers @{ Authorization = "Bearer $t" } -ContentType 'application/json' -Body $b).data.runId } catch { "ERR:$($_.Exception.Message)" }
  } -ArgumentList $body, $admin
}
$ids = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job -Force
$uniq = ($ids | Sort-Object -Unique).Count
$dbCount = [int](One "SELECT COUNT(*) FROM analytics_meta.pipeline_run WHERE idempotency_key=(SELECT idempotency_key FROM analytics_meta.pipeline_run WHERE id=$($r1.runId));")
Ck 'C2 同键并发 6 次只建 1 个 run' '唯一 runId 数=1 且 DB 行数=1' ("uniqueIds=$uniq dbRows=$dbCount ids=" + ($ids -join ',')) (($uniq -eq 1) -and ($dbCount -eq 1)) '内存锁 + uk_idempotency 兜底（§13.4）'

# ---------- C3 失败重试只从失败阶段开始（run 22 真实时间线） ----------
$t = Q "SELECT stage_code, status, COALESCE(LEFT(started_at,19),'-') FROM analytics_meta.pipeline_stage_run WHERE run_id=22 ORDER BY id;"
$stages = @{}
foreach ($line in $t) { $p = $line -split "`t"; $stages[$p[0]] = @{ status = $p[1]; started = $p[2] } }
$early = @('WAIT_LANDING', 'INIT_SCHEMA', 'LOAD_ODS', 'BUILD_DWD', 'BUILD_DWS')
$late = @('BUILD_ADS', 'QUALITY_CHECK', 'PUBLISH_METRIC')
$minEarly = ($early | ForEach-Object { [datetime]$stages[$_].started } | Measure-Object -Minimum).Minimum
$minLate = ($late | ForEach-Object { [datetime]$stages[$_].started } | Measure-Object -Minimum).Minimum
$gapMin = [math]::Round(($minLate - $minEarly).TotalMinutes, 1)
Ck 'C3 retry-from-stage 只重跑失败阶段及之后' '前 5 阶段时间戳早于后 3 阶段(>30 分钟)' "minEarly=$($minEarly.ToString('MM-dd HH:mm:ss')) minLate=$($minLate.ToString('MM-dd HH:mm:ss')) gap=${gapMin}min" ($gapMin -gt 30) 'run 22 attempt1 失败于 BUILD_ADS，attempt4 从 BUILD_ADS 续跑'

# ---------- C4 质量失败不污染正式 Hive / MySQL ACTIVE（隔离业务日期 2026-09-03） ----------
if (-not $SkipQuality) {
  $golden = Get-Content "$root\tests\golden-dataset\events\golden-20260901.jsonl" -Raw
  # ① 业务日期整体挪到 2026-09-03（不触碰黄金 dt=20260901 分区）② paid_amount 篡改为 0.01（AMOUNT_RECONCILE 必失败）
  $bad = $golden -replace '2026-09-0[12]T', '2026-09-03T' -replace '"paid_amount":"[0-9.]+"', '"paid_amount":"0.01"'
  $stamp = Get-Date -Format 'HHmmss'
  $fixture = "$root\landing\events\r9-qualityfail-$stamp.jsonl"
  [System.IO.File]::WriteAllText($fixture, $bad, (New-Object System.Text.UTF8Encoding($false)))
  Say "C4 篡改夹具=$fixture lines=$((Get-Content $fixture | Measure-Object -Line).Lines)"
  Invoke-RestMethod -Method Post -Uri "$base/api/v1/ingestion/runs" -Headers $hAdmin | Out-Null
  $qbody = @{ runtimeProfileId = 1; pipelineCode = 'ODS_TO_ADS'; businessTime = '2026-09-03T00:00:00'; sourceDataVersion = "r9-qualityfail-$stamp" } | ConvertTo-Json
  $qr = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/pipeline-runs" -Headers $hAdmin -ContentType 'application/json' -Body $qbody).data
  Say "C4 run=$($qr.runId) status=$($qr.status) snapshot=$($qr.targetSnapshotId)"
  $deadline = (Get-Date).AddMinutes(20); $qf = $null
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    $qf = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/$($qr.runId)" -Headers $hAdmin).data
    Say ("C4 POLL status={0} stage={1}" -f $qf.status, $qf.currentStage)
    if ($qf.status -in @('SUCCESS', 'FAILED', 'PARTIAL')) { break }
  }
  $qf | ConvertTo-Json -Depth 12 | Set-Content "$out\22-qualityfail-run$($qr.runId).json" -Encoding UTF8
  $qcStage = $qf.stages | Where-Object { $_.stageCode -eq 'QUALITY_CHECK' }
  $pubStage = $qf.stages | Where-Object { $_.stageCode -eq 'PUBLISH_METRIC' }
  $afterActive = (One "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;").Trim()
  $afterSnapCount = [int](One "SELECT COUNT(*) FROM analytics_metric.metric_snapshot;")
  $goldenRow = (Q "SELECT CONCAT(pv,'/',order_count,'/',sale_amount,'/',refund_rate) FROM analytics_metric.ads_operation_overview_m WHERE snapshot_id='$beforeActive';") -join ''
  $qEv = ($qcStage.evidence | Out-String)
  Ck 'C4 质量门阻断发布（run 终态）' 'FAILED 且 errorCode 指向质量/发布' "status=$($qf.status) error=$($qf.errorCode) qualityStatus=$($qcStage.status) publishStatus=$($pubStage.status)" ($qf.status -eq 'FAILED') '篡改 paid_amount=0.01 → AMOUNT_RECONCILE 必失败'
  Ck 'C4b 未产生新 ACTIVE 快照' "ACTIVE 仍为 $beforeActive 且快照总数不变($beforeSnapCount)" "active=$afterActive snapshots=$afterSnapCount" (($afterActive -eq $beforeActive) -and ($afterSnapCount -eq $beforeSnapCount)) '发布指针未动'
  Ck 'C4c 黄金快照指标库未被污染' 'pv/order/sale/refund_rate 与阻断前一致' "row=$goldenRow" ($goldenRow -match '^7/5/2042.00/0.6000$') '黄金 ACTIVE 行未被改写'
  Ck 'C4d 质量证据给出失败规则' 'evidence 含未通过规则' ($qEv.Trim() -replace '\s+', ' ').Substring(0, [Math]::Min(180, $qEv.Trim().Length)) ($qEv -match 'AMOUNT_RECONCILE|passed.:0|corePassed.:false')
}

# ---------- C5 RUNNING 中重启平台进程 → 可查询 + resume 续跑 ----------
if ($WithRestart) {
  $platformPid = (One "SELECT 1;" | Out-Null); $platformPid = $null
  $platformPid = (Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
  $rbody = @{ runtimeProfileId = 1; pipelineCode = 'ODS_TO_ADS'; businessTime = '2026-09-04T00:00:00'; sourceDataVersion = "r9-restart-$(Get-Date -Format 'HHmmss')" } | ConvertTo-Json
  $rr = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/pipeline-runs" -Headers $hAdmin -ContentType 'application/json' -Body $rbody).data
  Say "C5 run=$($rr.runId) 启动后等待进入 RUNNING"
  $deadline = (Get-Date).AddMinutes(6); $mid = $null
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $mid = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/$($rr.runId)" -Headers $hAdmin).data
    if ($mid.status -eq 'RUNNING') { break }
  }
  Ck 'C5 运行中重启前状态可查询' 'RUNNING' "status=$($mid.status) stage=$($mid.currentStage)" ($mid.status -eq 'RUNNING')
  Say "C5 杀掉平台进程 PID=$platformPid（真实进程重启实验）"
  Stop-Process -Id $platformPid -Force
  Start-Sleep -Seconds 5
  $down = -not (Test-NetConnection -ComputerName 127.0.0.1 -Port 8091 -InformationLevel Quiet -WarningAction SilentlyContinue)
  Ck 'C5b 平台进程已停止' '端口 8091 不可用' "listening=$(-not $down)" $down
  # 重启（与既有启动方式一致：java -jar platform-app）
  $javaExe = 'D:\Develop\JAVA17\bin\java.exe'
  Start-Process -FilePath $javaExe -ArgumentList '-jar', "$root\analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar", '--server.port=8091' `
    -WorkingDirectory $root -RedirectStandardOutput "$root\.verify\r9-restart-platform.log" -RedirectStandardError "$root\.verify\r9-restart-platform.err" -WindowStyle Hidden
  $up = $false; $deadline = (Get-Date).AddMinutes(3)
  while ((Get-Date) -lt $deadline) { Start-Sleep -Seconds 5; if (Test-NetConnection -ComputerName 127.0.0.1 -Port 8091 -InformationLevel Quiet -WarningAction SilentlyContinue) { $up = $true; break } }
  Ck 'C5c 平台重启后可服务' '8091 重新可用' "up=$up" $up
  $admin2 = Login 'admin' 'admin123'
  $h2 = @{ Authorization = "Bearer $admin2" }
  $after = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/$($rr.runId)" -Headers $h2).data
  Ck 'C5d 重启后仍可查询该运行（不清零）' '返回同一 runId 及阶段状态' "runId=$($after.runId) status=$($after.status) stage=$($after.currentStage)" ($after.runId -eq $rr.runId)
  $rurl = "$base/api/v1/admin/pipeline-runs/$($rr.runId)/resume?operator=admin&reason=" + [uri]::EscapeDataString('R9 重启恢复实验')
  $raw = Invoke-WebRequest -Method Post -Uri $rurl -Headers $h2 -SkipHttpErrorCheck
  $resume = $raw.Content | ConvertFrom-Json
  Ck 'C5e 重启后可 resume 续跑' 'HTTP 200 且 code=OK' "HTTP=$($raw.StatusCode) code=$($resume.code) status=$($resume.data.status)" ($raw.StatusCode -eq 200 -and $resume.code -eq 'OK') 'operator/reason 为 @RequestParam，必须放 query'
}

$rows | Set-Content "$out\21-reliability-experiments.tsv" -Encoding UTF8
$fail = ($rows | Where-Object { $_ -match "`tFAIL`t" }).Count
Write-Host "`n可靠性实验：PASS=$(($rows | Where-Object { $_ -match "`tPASS`t" }).Count) FAIL=$fail → $out\21-reliability-experiments.tsv"
if ($fail -gt 0) { exit 1 }


