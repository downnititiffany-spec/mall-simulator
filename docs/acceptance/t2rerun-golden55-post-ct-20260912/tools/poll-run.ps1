<#
  真实链轮询器（可复跑）— E3-a 阶段时刻 + E3-d M1-11 的 status() 迁移序列
  =====================================================================
  用途：轮询 GET /api/v1/pipeline-runs/{id} 至终态，逐次落盘「观测时刻 + 原始响应」，
        并在每次响应里抽取 stages[] 的逐阶段 status，形成**状态迁移序列**（供 E3-d 的
        「至少 RUNNING 与 SUCCESS 两态」判据使用）。

  判据（ORDER-1 §4）：
    E3-a：8/8 阶段每阶段 SUCCESS；落 runId / ingestion_batch id / 快照 ID / 起止时刻
    E3-d：status() 迁移序列至少含 RUNNING 与 SUCCESS 两态

  自带断言（陷阱 #27）：
    A1 轮询次数 > 0 且至少观测到 2 个不同的 run.status（否则"迁移序列"不成立）
    A2 终态必须是 SUCCESS / FAILED / CANCELLED 之一（不能无限跑）
    A3 阶段数必须为 8（权威顺序 8 个，不是 7 个）—— 终态后校验
    A4 输出 LF、CR=0

  用法：pwsh -NoProfile -File tools\poll-run.ps1 -RunId 42 -TimeoutSec 1800
#>
param(
  [Parameter(Mandatory=$true)][int]$RunId,
  [int]$TimeoutSec = 1800,
  [int]$IntervalSec = 3
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logPath = Join-Path $outDir "e3a-poll-run$RunId-$stamp.txt"
$enc = New-Object Text.UTF8Encoding($false)

$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8091/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$hdr = @{ Authorization = "Bearer $($login.data.token)" }

$lines = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $lines.Add($s); [IO.File]::WriteAllText($logPath, (($lines -join "`n") + "`n"), $enc) }

L "E3-a/E3-d 真实链轮询原始证据 — runId=$RunId"
L "====================================================================="
L "轮询开始：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L "端点    ：GET http://127.0.0.1:8091/api/v1/pipeline-runs/$RunId"
L "间隔    ：${IntervalSec}s   上限：${TimeoutSec}s"
L ""

$seen = New-Object 'System.Collections.Generic.List[string]'
$stageSeen = @{}          # stageCode -> List of statuses
$stageOrder = New-Object 'System.Collections.Generic.List[string]'
$stageTimes = @{}         # stageCode -> first/last seen
$final = $null
$t0 = Get-Date
$n = 0

while ($true) {
  $n++
  $now = Get-Date
  if (($now - $t0).TotalSeconds -gt $TimeoutSec) { L "!! 超时 ${TimeoutSec}s，未达终态"; break }
  try {
    $j = Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/v1/pipeline-runs/$RunId" -Headers $hdr -TimeoutSec 30
  } catch {
    L "[$n] $($now.ToString('HH:mm:ss.fff')) 请求失败：$($_.Exception.Message)"
    Start-Sleep -Seconds $IntervalSec
    continue
  }
  $d = $j.data
  $st = $d.status
  $cs = $d.currentStage
  if (-not $seen.Contains($st)) { $seen.Add($st) }

  $stTxt = New-Object 'System.Collections.Generic.List[string]'
  foreach ($s in $d.stages) {
    $code = $s.stageCode
    if (-not $stageSeen.ContainsKey($code)) {
      $stageSeen[$code] = New-Object 'System.Collections.Generic.List[string]'
      $stageOrder.Add($code)
      $stageTimes[$code] = @{ first = $now; last = $now; firstStatus = $s.status }
    }
    $lst = $stageSeen[$code]
    if ($lst.Count -eq 0 -or $lst[$lst.Count-1] -ne $s.status) { $lst.Add($s.status) }
    $stageTimes[$code].last = $now
    $stTxt.Add("$code=$($s.status)")
  }
  L ("[{0,3}] {1}  run.status={2,-12} currentStage={3,-16} snapshot={4}" -f $n, $now.ToString('HH:mm:ss.fff'), $st, $cs, $d.targetSnapshotId)
  if ($stTxt.Count -gt 0) { L ("       stages: " + ($stTxt -join ' ')) }

  if ($st -in @('SUCCESS','FAILED','CANCELLED')) { $final = $d; break }
  Start-Sleep -Seconds $IntervalSec
}

L ""
L "---- 汇总 ----"
L "轮询次数：$n ；耗时：$([math]::Round(((Get-Date)-$t0).TotalSeconds,1)) s"
L "run.status 观测到的**迁移序列**（去重、按首次出现顺序）：$($seen -join ' → ')"
L ""
L "阶段首次出现顺序（共 $($stageOrder.Count) 个）：$($stageOrder -join ' → ')"
L ""
L "逐阶段状态迁移（stageCode: 观测到的状态序列 | 首次观测 | 末次观测）："
foreach ($c in $stageOrder) {
  $seq = ($stageSeen[$c] -join '→')
  L ("  {0,-18} {1,-22} first={2} last={3}" -f $c, $seq, $stageTimes[$c].first.ToString('HH:mm:ss.fff'), $stageTimes[$c].last.ToString('HH:mm:ss.fff'))
}
L ""
if ($final) {
  L "终态：status=$($final.status) currentStage=$($final.currentStage)"
  L "      targetSnapshotId=$($final.targetSnapshotId)  attemptNo=$($final.attemptNo)"
  L "      errorCode=$($final.errorCode)"
  L "      errorMessage=$($final.errorMessage)"
  L ""
  L "终态时 stages[] 逐阶段原始记录（code|status|startedAt|finishedAt|message 截断）："
  foreach ($s in $final.stages) {
    $msg = "$($s.message)"
    if ($msg.Length -gt 160) { $msg = $msg.Substring(0,160) + '…' }
    L ("  {0,-18} {1,-10} started={2} finished={3}" -f $s.stageCode, $s.status, $s.startedAt, $s.finishedAt)
    if ($msg -and $msg -ne '') { L ("        message: $msg") }
  }
  L ""
  L "终态响应体（原样 JSON）："
  L ($final | ConvertTo-Json -Depth 8 -Compress)
} else {
  L "**未达终态**（超时或中断）"
}

# 断言
$fails = New-Object 'System.Collections.Generic.List[string]'
if ($n -le 0) { $fails.Add("A1 失败：轮询次数=0") }
if ($seen.Count -lt 2) { $fails.Add("A1 失败：run.status 只观测到 $($seen.Count) 个状态（迁移序列不成立）") }
if (-not $final) { $fails.Add("A2 失败：未达终态") }
elseif ($final.status -notin @('SUCCESS','FAILED','CANCELLED')) { $fails.Add("A2 失败：终态非法 $($final.status)") }
if ($final -and $final.stages.Count -ne 8) { $fails.Add("A3 失败：阶段数 = $($final.stages.Count)，期望 8") }

L "---- 断言小结 ----"
if ($fails.Count -eq 0) { L "全部断言 PASS（A1 迁移序列>=2 态 / A2 终态合法 / A3 阶段数=8）" }
else { foreach ($f in $fails) { L "  FAIL: $f" } }
L "输出行数：$($lines.Count)"
[IO.File]::WriteAllText($logPath, (($lines -join "`n") + "`n"), $enc)

$w = [IO.File]::ReadAllText($logPath)
$cr = ([regex]::Matches($w, [string][char]13)).Count
Write-Host "OUTFILE=$logPath"
Write-Host "LINES=$(([regex]::Matches($w, [string][char]10)).Count)  CR_BYTES=$cr  BYTES=$((Get-Item $logPath).Length)"
Write-Host "STATUS_SEQUENCE=$($seen -join ' -> ')"
Write-Host "STAGE_ORDER=$($stageOrder -join ' -> ')"
Write-Host "STAGE_COUNT=$($stageOrder.Count)"
if ($final) { Write-Host "FINAL=$($final.status) SNAPSHOT=$($final.targetSnapshotId)" } else { Write-Host "FINAL=<none>" }
if ($cr -ne 0) { Write-Host "EOL FAIL: CR present"; exit 1 }
if ($fails.Count -gt 0) { Write-Host "ASSERT FAIL ($($fails.Count))"; $fails | ForEach-Object { Write-Host "  - $_" }; exit 1 }
Write-Host "ASSERT PASS: all"
