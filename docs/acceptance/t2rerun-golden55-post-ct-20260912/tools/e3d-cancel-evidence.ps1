<#
  E3-d 受控 cancel 取证编排器（可复跑）— M1-11 E3 观测
  =====================================================================
  要证的三件事：
    O1 `status()` 迁移序列：SUBMITTED → RUNNING →（终态）
    O2 调用 `cancel()` 后，**真实 Spark driver 进程**确实消失（不只是 status() 说 CANCELLED）
    O3 `cancel()` **前后**的进程快照对照：before 有该 pid，after 没有

  编排时序（关键，勿改）：
    T1 抓 before 基线快照（此时链上无 Spark）
    T2 后台启动 harness（mode=cancel）→ 它 submit 真实 spark-submit，探针静默 90s
    T3 轮询等 `e3d-driver-pid.txt` 出现 ⇒ driver 已在跑
    T4 抓 **before-cancel** 快照（**driver 存活中** —— 这是 O3 的"before"）
    T5 创建 go 文件 ⇒ harness 立刻 `cancel()`
    T6 等 harness 进程退出
    T7 抓 **after-cancel** 快照（**driver 应已消失** —— 这是 O3 的"after"）
    T8 断言：before-cancel 快照里出现该 driver pid，after-cancel 快照里**不出现**

  为什么要外部编排（如实登记）：
    平台的 HTTP 面**没有**取消端点；真实链 `SparkStageExecutor.executeJob` **从不调用** `cancel()`。
    ⇒ 受控 cancel 只能直接驱动**产品类** `LocalProcessSparkSubmitter`（与真实链同一个类），
      提交的是**真实 spark-submit.cmd + 真实 spark-jobs jar**。
      故 status()/cancel() 在真实 Spark 进程上的行为是**真实测量**，
      但它**不是**平台 HTTP 链的一部分 ⇒ README §5 必须写明该界限。
    又：cancel 会留半成品数仓 ⇒ 本取证放在**最后**做（主跑 42 / 对照跑 43 都已完成）。

  自带断言（陷阱 #27）：
    A1 before-cancel 快照里确实出现 driver pid（否则"after 里没有"毫无意义 —— 假绿）
    A2 after-cancel 快照里**不**出现 driver pid
    A3 harness 输出里有 RUNNING
    A4 harness 输出里 status() 终态 == CANCELLED
    A5 harness 输出里有"alive=false"或句柄消失
    A6 输出 LF、CR=0

  用法：pwsh -NoProfile -File tools\e3d-cancel-evidence.ps1
#>
param(
  [string]$WorkDir = 'D:\Develop_code\GraduationProject\.verify\e3d',
  [int]$DriverWaitSec = 150,
  [int]$HarnessWaitSec = 240
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$rawDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
$toolsDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\tools'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java  = 'D:\Develop\JAVA17\bin\java.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outFile = Join-Path $rawDir "e3d-cancel-evidence-$stamp.txt"
$enc = New-Object Text.UTF8Encoding($false)
$buf = New-Object 'System.Collections.Generic.List[string]'
$fail = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $buf.Add($s) }
function Save { [IO.File]::WriteAllText($outFile, (($buf -join "`n") + "`n"), $enc) }

$logDir = Join-Path $WorkDir 'logs'
$goFile = Join-Path $WorkDir 'go.txt'
$pidFile = Join-Path $logDir 'e3d-driver-pid.txt'
$cpFile = Join-Path $root '.verify\r2-harness-classpath.txt'
$classes = Join-Path $WorkDir 'classes'

L "E3-d 受控 cancel 取证（M1-11 E3 观测）"
L "====================================================================="
L "跑动时点：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L ""
L "【前置声明 · 必须与 README §5 一致】"
L "  · 平台 HTTP 面**无**取消端点（PipelineController/PipelineAdminController 无 cancel 映射）"
L "  · 真实链 SparkStageExecutor.executeJob(L173-193) **只轮询** status()/logs()，**从不调用** cancel()"
L "  ⇒ 本取证直接驱动**产品类** LocalProcessSparkSubmitter，提交**真实** spark-submit.cmd"
L "     + **真实** spark-jobs-0.1.0-SNAPSHOT.jar ⇒ 对真实 Spark 进程的行为是真实测量，"
L "     但**不是**平台 HTTP 端到端链的一部分。"
L ""

# ---------- 前置检查 ----------
foreach ($p in @($java, $cpFile, (Join-Path $classes 'E3DCancelHarness.class'))) {
  if (-not (Test-Path $p)) { $fail.Add("前置缺失：$p"); L "  [缺失] $p" } else { L "  [就绪] $p" }
}
if ($fail.Count -gt 0) { L "前置不满足 ⇒ 中止"; Save; Write-Host "PREREQ FAIL"; exit 1 }

$cp = ([IO.File]::ReadAllText($cpFile)).Trim() + ';' + $classes
if (Test-Path $goFile) { Remove-Item $goFile -Force }
if (Test-Path $pidFile) { Remove-Item $pidFile -Force }
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
L "  classpath 条数 = $(($cp -split ';').Count)"
L ""

# ---------- T1 before 基线 ----------
L "---- T1 before 基线快照（链上无 Spark 时的基线）----"
$b1 = Join-Path $rawDir "e3d-procs-baseline-$stamp.txt"
& pwsh -NoProfile -File (Join-Path $toolsDir 'snapshot-processes.ps1') -Tag "baseline-$stamp" -OutFile $b1 2>&1 | Select-Object -Last 4
L "  已写 $b1"
L ""

# ---------- T2 后台启动 harness ----------
L "---- T2 后台启动 harness（mode=cancel）----"
$hOut = Join-Path $logDir "e3d-harness-stdout-$stamp.txt"
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:PATH = "D:\Develop\JAVA17\bin;$env:PATH"
$harness = Start-Process -FilePath $java `
  -ArgumentList @('-cp', $cp, 'E3DCancelHarness', $goFile, $logDir, 'cancel') `
  -RedirectStandardOutput $hOut -RedirectStandardError (Join-Path $logDir "e3d-harness-stderr-$stamp.txt") `
  -PassThru -NoNewWindow
L "  harness pid = $($harness.Id)  启动 $(Get-Date -Format 'HH:mm:ss.fff')"
L ""

# ---------- T3 等 driver pid 文件 ----------
L "---- T3 等真实 Spark driver 起来（轮询 e3d-driver-pid.txt）----"
$dl = (Get-Date).AddSeconds($DriverWaitSec)
$driverPid = -1
while ((Get-Date) -lt $dl) {
  if (Test-Path $pidFile) {
    $v = ([IO.File]::ReadAllText($pidFile)).Trim()
    if ($v -match '^\d+$') { $driverPid = [int]$v; break }
  }
  if ($harness.HasExited) { L "  harness 提前退出，退出码 $($harness.ExitCode)"; break }
  Start-Sleep -Milliseconds 500
}
L "  driver pid = $driverPid   发现于 $(Get-Date -Format 'HH:mm:ss.fff')"
if ($driverPid -le 0) { $fail.Add("A1 失败：未取到 driver pid（harness 未产出 e3d-driver-pid.txt）") }
if ($driverPid -gt 0) {
  $dh = Get-Process -Id $driverPid -ErrorAction SilentlyContinue
  L "  Get-Process -Id $driverPid ：$(if ($dh) { "存活 cmd=$($dh.ProcessName) 启动=$($dh.StartTime.ToString('HH:mm:ss.fff'))" } else { '**不存在**' })"
  if (-not $dh) { $fail.Add("A1 失败：driver pid $driverPid 在 cancel 前就查不到") }
}
L ""

# ---------- T4 before-cancel 快照（driver 存活中）----------
L "---- T4 before-cancel 快照（**driver 存活中**，这是 O3 的 before）----"
$b2 = Join-Path $rawDir "e3d-procs-before-cancel-$stamp.txt"
& pwsh -NoProfile -File (Join-Path $toolsDir 'snapshot-processes.ps1') -Tag "before-cancel-$stamp" -MatchPid $driverPid -OutFile $b2 2>&1 | Select-Object -Last 4
L "  已写 $b2"
$b2txt = [IO.File]::ReadAllText($b2)
$b2Has = $b2txt -match "(?m)\b$driverPid\b"
L "  **A1 检查**：before-cancel 快照里出现 pid $driverPid ？ = $b2Has"
if (-not $b2Has) { $fail.Add("A1 失败：before-cancel 快照里没有 pid $driverPid ⇒ 'after 里没有'不能证明任何事（假绿风险）") }
L ""

# ---------- T5 触发 cancel ----------
L "---- T5 创建 go 文件触发 cancel() ----"
[IO.File]::WriteAllText($goFile, "go $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')", $enc)
L "  go 文件已建 $(Get-Date -Format 'HH:mm:ss.fff')：$goFile"
L ""

# ---------- T6 等 harness 退出 ----------
L "---- T6 等 harness 结束 ----"
$hd = (Get-Date).AddSeconds($HarnessWaitSec)
while (-not $harness.HasExited -and (Get-Date) -lt $hd) { Start-Sleep -Milliseconds 500 }
if ($harness.HasExited) { L "  harness 已退出，退出码 $($harness.ExitCode) 于 $(Get-Date -Format 'HH:mm:ss.fff')" }
else { L "  harness 超时未退出（${HarnessWaitSec}s）"; $fail.Add("harness 超时未退出") }
L ""

# ---------- T7 after-cancel 快照 ----------
L "---- T7 after-cancel 快照（**driver 应已消失**，这是 O3 的 after）----"
Start-Sleep -Seconds 2
$a1 = Join-Path $rawDir "e3d-procs-after-cancel-$stamp.txt"
& pwsh -NoProfile -File (Join-Path $toolsDir 'snapshot-processes.ps1') -Tag "after-cancel-$stamp" -MatchPid $driverPid -OutFile $a1 2>&1 | Select-Object -Last 4
L "  已写 $a1"
$a1txt = [IO.File]::ReadAllText($a1)
$a1Has = $a1txt -match "(?m)\b$driverPid\b"
L "  **A2 检查**：after-cancel 快照里出现 pid $driverPid ？ = $a1Has （期望 False）"
if ($a1Has) { $fail.Add("A2 失败：after-cancel 快照里仍出现 pid $driverPid ⇒ cancel 未真正释放进程") }
L "  Get-Process -Id $driverPid ：$(if (Get-Process -Id $driverPid -ErrorAction SilentlyContinue) { '**仍存活**' } else { '已消失 ✓' })"
L ""

# ---------- T8 读 harness 输出 ----------
$hFile = Join-Path $logDir 'e3d-harness-cancel.out.txt'
L "---- T8 harness 自述输出（$hFile）----"
if (Test-Path $hFile) {
  $h = [IO.File]::ReadAllText($hFile)
  L $h
  if ($h -notmatch 'status\(.*\) -> RUNNING') { $fail.Add("A3 失败：harness 输出里未观测到 RUNNING") }
  if ($h -notmatch 'status\(' -or $h -notmatch 'CANCELLED') { $fail.Add("A4 失败：harness 输出里 status() 终态不是 CANCELLED") }
  if ($h -notmatch 'alive=false' -and $h -notmatch '句柄已不存在') { $fail.Add("A5 失败：harness 未报告 driver 已消失") }
} else { L "  **文件不存在**"; $fail.Add("A5 失败：harness 输出文件不存在 $hFile") }
L ""

# ---------- 汇总 ----------
L "==== 断言小结 ===="
if ($fail.Count -eq 0) {
  L "A1 before-cancel 里 pid 存在 ✓ / A2 after-cancel 里 pid 不存在 ✓"
  L "A3 RUNNING 可观测 ✓ / A4 终态 CANCELLED ✓ / A5 driver 已消失 ✓ / A6 LF ✓"
  L ""
  L "**E3-d 判定：成立**"
  L "  O1 status() 迁移：SUBMITTED -> RUNNING -> CANCELLED（见 T8 输出）"
  L "  O2 cancel() 后真实 Spark driver pid $driverPid 消失（T7 快照 + ProcessHandle 复查）"
  L "  O3 前后快照对照：$([IO.Path]::GetFileName($b2)) 有该 pid；$([IO.Path]::GetFileName($a1)) 无该 pid"
  L ""
  L "  界限（不得含糊）：本取证驱动的是**产品类** LocalProcessSparkSubmitter，"
  L "  非平台 HTTP 链；真实链 SparkStageExecutor **不调用** cancel()。"
} else {
  foreach ($f in $fail) { L "  FAIL: $f" }
  L ""
  L "**E3-d 判定：不成立（见上）** —— 属真实发现，照实登记，不得改写为通过。"
}
L ""
L "证据文件："
L "  baseline   : $b1"
L "  before-canc: $b2"
L "  after-canc : $a1"
L "  harness    : $hFile"
Save
$cr = ([regex]::Matches((($buf -join "`n") + "`n"), [string][char]13)).Count
Write-Host "OUTFILE=$outFile BYTES=$((Get-Item $outFile).Length) LINES=$($buf.Count) CR=$cr"
Write-Host "driverPid=$driverPid"
if ($cr -ne 0) { Write-Host "A6 FAIL CR=$cr"; exit 1 }
if ($fail.Count -eq 0) { Write-Host "ASSERT PASS: all" } else {
  Write-Host "ASSERT FAIL ($($fail.Count)):"; $fail | ForEach-Object { Write-Host "  - $_" }; exit 1
}
