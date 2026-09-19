# 按 pidfile 停止平台进程（G31-01 / 指导书 01.2 + 01.3 的可复用收口脚本）
#
# 解决什么（对照 BATCH-W 的 stage7w-session2-cleanup.ps1 的已识别缺口）：
#   * 旧脚本的身份核对只有**进程名**（cmd/java）——PID 被复用成一个同名无关进程时
#     会误杀。本脚本要求**命令行标记**证据：pidfile 之外，还须提供 identity JSON
#     （启动 driver 在启动瞬间写：pid/进程名/标记=runId）或 -IdentityMarker；
#     进程存在但命令行不含标记 ⇒ 拒绝（exit 5），**一个进程都不杀**。
#   * 只停「本次 attempt 自己拥有的进程树」（CIM ParentProcessId 闭包），
#     绝不做按进程名的组杀（指导书 5.1.6 / 01.2）。
#   * 资源账本：把 runId/PID/启动时间/端口/日志路径/清理结果写成 JSON（-LedgerOut），
#     供批次归档（01.2）。账本只记**凭据引用名**，绝不记口令值。
#   * 清理失败（端口未释放 / 日志中出现 :3306 / 停止失败）一律非 0 退出，
#     由调用方把该次 attempt 判为非 PASS——清理失败绝不能被报成批次 PASS（01.3）。
#
# 退出码：
#   0 = 已停止（或早已不在）且端口已释放、3306 清查无命中
#   1 = 用法错误（缺 pidfile / 缺身份证据）
#   5 = 身份核对未通过（疑似 PID 复用或证据不一致）⇒ 拒绝执行，未杀任何进程
#   7 = 已尝试清理但验证失败（端口仍监听 / 日志 3306 命中 / 进程未退）
#
# 用法（由启动 driver 写好 platform.pid + platform.identity.json 后，任意时机调用；
# 甚至在原 driver 已退出/被中断后独立调用——这就是 01.3 的"中断可独立清理"）：
#   pwsh -NoProfile -File scripts/stop-platform-by-pidfile.ps1 `
#        -PidFile  <session>\platform.pid `
#        -IdentityFile <session>\platform.identity.json `
#        -LogPath  <session>\platform.log -Port 8091 `
#        -LedgerOut <session>\ledger-stop.json
param(
  [Parameter(Mandatory = $true)][string]$PidFile,
  [string]$IdentityFile = '',
  # IdentityFile 不存在时的备用身份证据：命令行必须包含该标记（推荐 = runId）
  [string]$IdentityMarker = '',
  [string]$LogPath = '',
  [int]$Port = 8091,
  [string]$LedgerOut = '',
  # 停止后等待进程退出的秒数
  [int]$WaitSeconds = 5
)

$ErrorActionPreference = 'Stop'
$outcome = 'FAILED'
$owned = @()
$stopped = @()
$identityNote = ''
$rootPid = $null
$sweepHits = -1
$released = $false

function Write-Ledger {
  param([hashtable]$State)
  if (-not $LedgerOut) { return }
  $ledger = [ordered]@{
    timestamp    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
    pidFile      = $PidFile
    rootPid      = $State.rootPid
    identity     = [ordered]@{
      evidence = $(if ($IdentityFile) { $IdentityFile } else { '-IdentityMarker（命令行标记）' })
      note     = $State.identityNote
      verified = $State.identityVerified
    }
    ownedTree    = $State.owned
    stoppedPids  = $State.stopped
    port         = [ordered]@{ port = $Port; released = $State.released }
    sweep3306    = [ordered]@{ log = $LogPath; hits = $State.sweepHits }
    outcome      = $State.outcome
    # 凭据仅记引用名（键存在与否），绝不记值
    credentialRefs = @('V25_IT_*', 'IT_GUARD_*', 'V25IT_ADMIN_PWD', 'PLATFORM_*（口令仅存在于启动进程环境，不落盘）')
  }
  $dir = Split-Path -Parent $LedgerOut
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $ledger | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $LedgerOut -Encoding utf8
}

# ── 1. 读 pidfile ──────────────────────────────────────────────────────────
if (-not (Test-Path -LiteralPath $PidFile)) {
  Write-Host ("stop-platform-by-pidfile：用法错误，找不到 pidfile：{0}" -f $PidFile)
  Write-Ledger @{ rootPid = $null; identityNote = 'pidfile missing'; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'FAILED' }
  exit 1
}
$raw = (Get-Content -LiteralPath $PidFile -TotalCount 1 | Select-Object -First 1)
if (-not ($raw -match '^\s*(\d+)\s*$')) {
  Write-Host ("stop-platform-by-pidfile：pidfile 内容不是 PID：'{0}'" -f $raw)
  Write-Ledger @{ rootPid = $null; identityNote = 'pidfile malformed'; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'FAILED' }
  exit 1
}
$rootPid = [int]$Matches[1]

# ── 2. 身份证据核对（PID 复用拒绝的核心）───────────────────────────────────
$expectedName = $null
$expectedMarker = $IdentityMarker
if ($IdentityFile -and (Test-Path -LiteralPath $IdentityFile)) {
  try {
    $idj = Get-Content -LiteralPath $IdentityFile -Raw | ConvertFrom-Json
    if ($idj.pid -and [int]$idj.pid -ne $rootPid) {
      Write-Host ("stop-platform-by-pidfile：身份证据不一致：pidfile={0} 但 identity.json.pid={1} ⇒ 拒绝（未杀任何进程）。" -f $rootPid, [int]$idj.pid)
      Write-Ledger @{ rootPid = $rootPid; identityNote = 'pid mismatch between pidfile and identity.json'; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'REFUSED' }
      exit 5
    }
    $expectedName = $idj.name
    if ($idj.marker) { $expectedMarker = $idj.marker }
  } catch {
    Write-Host ("stop-platform-by-pidfile：identity.json 解析失败：{0} ⇒ 拒绝。" -f $_.Exception.Message)
    Write-Ledger @{ rootPid = $rootPid; identityNote = 'identity.json unparseable'; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'REFUSED' }
    exit 5
  }
}
if (-not $expectedMarker) {
  Write-Host 'stop-platform-by-pidfile：用法错误——没有 identity.json 也没有 -IdentityMarker，无法核对进程身份。'
  Write-Ledger @{ rootPid = $rootPid; identityNote = 'no identity evidence'; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'REFUSED' }
  exit 1
}

$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$rootPid" -Property ProcessId, Name, CommandLine, ParentProcessId, CreationDate
if (-not $proc) {
  # 早已不在：幂等成功路径（仍须完成端口与日志清查，给出可归档证据）
  Write-Host ("stop-platform-by-pidfile：PID {0} 已不存在（幂等：视为已清理）。" -f $rootPid)
  $identityNote = 'process already gone'
  $released = $true
  try {
    $c = New-Object Net.Sockets.TcpClient
    $c.Connect('127.0.0.1', $Port); $c.Close()
    $released = $false
  } catch { $released = $true }
  if ($LogPath -and (Test-Path -LiteralPath $LogPath)) {
    $hits = @(Select-String -LiteralPath $LogPath -Pattern ':3306' -SimpleMatch)
    $sweepHits = $hits.Count
  }
  Write-Ledger @{ rootPid = $rootPid; identityNote = $identityNote; identityVerified = $true; owned = @(); stopped = @(); released = $released; sweepHits = $sweepHits; outcome = $(if ($released -and $sweepHits -eq 0) { 'ALREADY_GONE' } else { 'FAILED' }) }
  if (-not $released) { Write-Host ("  但端口 {0} 仍被占用 ⇒ exit 7（请人工核对占用者）。" -f $Port); exit 7 }
  if ($sweepHits -gt 0) { Write-Host ("  日志清查发现 {0} 处 ':3306' ⇒ exit 7。" -f $sweepHits); exit 7 }
  exit 0
}

if ($expectedName -and $proc.Name -ne $expectedName) {
  Write-Host ("stop-platform-by-pidfile：身份拒绝：PID {0} 现为 '{1}'，启动时登记为 '{2}' ⇒ 疑似 PID 复用（未杀任何进程）。" -f $rootPid, $proc.Name, $expectedName)
  Write-Ledger @{ rootPid = $rootPid; identityNote = "name changed: now='$($proc.Name)' expected='$expectedName'"; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'REFUSED' }
  exit 5
}
$cmdline = [string]$proc.CommandLine
if (-not $cmdline.Contains($expectedMarker)) {
  Write-Host ("stop-platform-by-pidfile：身份拒绝：PID {0}（{1}）命令行不含标记 '{2}' ⇒ 疑似 PID 复用（未杀任何进程）。" -f $rootPid, $proc.Name, $expectedMarker)
  Write-Ledger @{ rootPid = $rootPid; identityNote = "command line lacks marker '$expectedMarker'"; identityVerified = $false; owned = @(); stopped = @(); released = $false; sweepHits = -1; outcome = 'REFUSED' }
  exit 5
}
Write-Host ("stop-platform-by-pidfile：身份核对通过：PID {0}（{1}，启动于 {2}），标记 '{3}' 命中。" -f $rootPid, $proc.Name, $proc.CreationDate, $expectedMarker)

# ── 3. 只停本次拥有的进程树（CIM 父子闭包；绝不按名字组杀）─────────────────
function Get-OwnedTree {
  param([int]$RootPid)
  $all = Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, Name, CreationDate
  $byParent = @{}
  foreach ($p in $all) {
    $pp = [int]$p.ParentProcessId
    if (-not $byParent.ContainsKey($pp)) { $byParent[$pp] = [System.Collections.Generic.List[object]]::new() }
    $byParent[$pp].Add($p)
  }
  $tree = [System.Collections.Generic.List[object]]::new()
  $seen = @{}
  $queue = [System.Collections.Generic.Queue[int]]::new()
  $queue.Enqueue($RootPid)
  while ($queue.Count -gt 0) {
    $cur = $queue.Dequeue()
    if ($seen.ContainsKey($cur)) { continue }
    $seen[$cur] = $true
    foreach ($p in $all) {
      if ([int]$p.ProcessId -eq $cur) { $tree.Add($p); break }
    }
    if ($byParent.ContainsKey($cur)) { foreach ($c in $byParent[$cur]) { $queue.Enqueue([int]$c.ProcessId) } }
  }
  return $tree
}

$owned = @(Get-OwnedTree -RootPid $rootPid | ForEach-Object {
  [ordered]@{ pid = [int]$_.ProcessId; name = $_.Name; startedAt = $_.CreationDate }
})
Write-Host ("stop-platform-by-pidfile：本次拥有的进程树 {0} 个节点：{1}" -f $owned.Count, (($owned | ForEach-Object { "{0}({1})" -f $_.pid, $_.name }) -join ' '))

# 子先父后（reverse = 叶子优先），逐 PID 停止
foreach ($node in ($owned | Sort-Object { $_.pid } -Descending)) {
  try {
    Stop-Process -Id $node.pid -Force -ErrorAction Stop
    $stopped += $node.pid
  } catch {
    Write-Host ("  PID {0} 停止失败（可能已退出）：{1}" -f $node.pid, $_.Exception.Message)
  }
}
Start-Sleep -Seconds 2
$left = @(Get-CimInstance Win32_Process -Filter ("ProcessId=" + (($owned | ForEach-Object { $_.pid }) -join ' OR ProcessId=')))
if ($left.Count -gt 0) {
  Write-Host ("stop-platform-by-pidfile：{0} 个自有进程未退出 ⇒ exit 7。" -f $left.Count)
  Write-Ledger @{ rootPid = $rootPid; identityNote = 'identity verified'; identityVerified = $true; owned = $owned; stopped = $stopped; released = $false; sweepHits = -1; outcome = 'FAILED' }
  exit 7
}

# ── 4. 端口释放核对 ────────────────────────────────────────────────────────
Start-Sleep -Seconds 1
try {
  $c = New-Object Net.Sockets.TcpClient
  $c.Connect('127.0.0.1', $Port); $c.Close()
  $released = $false
  Write-Host ("stop-platform-by-pidfile：端口 {0} 仍可连接 ⇒ exit 7（清理未达效）。" -f $Port)
  $sweepHits = -1
  if ($LogPath -and (Test-Path -LiteralPath $LogPath)) {
    $sweepHits = @(Select-String -LiteralPath $LogPath -Pattern ':3306' -SimpleMatch).Count
  }
  Write-Ledger @{ rootPid = $rootPid; identityNote = 'identity verified'; identityVerified = $true; owned = $owned; stopped = $stopped; released = $false; sweepHits = $sweepHits; outcome = 'FAILED' }
  exit 7
} catch { $released = $true }
Write-Host ("stop-platform-by-pidfile：端口 {0} 已释放。" -f $Port)

# ── 5. 全量日志 :3306 清查（5.1.5：单独零命中不充分，但命中即清理失败）──────
if ($LogPath -and (Test-Path -LiteralPath $LogPath)) {
  $hits = @(Select-String -LiteralPath $LogPath -Pattern ':3306' -SimpleMatch)
  $sweepHits = $hits.Count
  if ($sweepHits -gt 0) {
    Write-Host ("stop-platform-by-pidfile：日志中出现 {0} 处 ':3306' ⇒ exit 7（平台曾指向宿主正式实例）。" -f $sweepHits)
    foreach ($h in ($hits | Select-Object -First 10)) {
      $snippet = $h.Line
      if ($snippet.Length -gt 120) { $snippet = $snippet.Substring(0, 120) }
      Write-Host ("  行 {0}: {1}" -f $h.LineNumber, $snippet)
    }
    Write-Ledger @{ rootPid = $rootPid; identityNote = 'identity verified'; identityVerified = $true; owned = $owned; stopped = $stopped; released = $true; sweepHits = $sweepHits; outcome = 'FAILED' }
    exit 7
  }
  Write-Host ("stop-platform-by-pidfile：日志 ':3306' 清查零命中（{0} 行已扫）。" -f (Get-Content -LiteralPath $LogPath | Measure-Object -Line).Lines)
} else {
  $sweepHits = -1
}

$outcome = 'STOPPED'
Write-Ledger @{ rootPid = $rootPid; identityNote = 'identity verified'; identityVerified = $true; owned = $owned; stopped = $stopped; released = $true; sweepHits = $sweepHits; outcome = $outcome }
Write-Host 'stop-platform-by-pidfile：OK（进程树已停、端口已释放、3306 清查通过；账本已写）。'
exit 0
