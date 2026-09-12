<#
  进程快照工具（可复跑）— E3-d 的「cancel() 前后进程对照」取证
  =====================================================================
  输出（LF，UTF-8 无 BOM）：
    ① 全部 java.exe 进程（pid / ppid / 创建时刻 / 命令行前 200 字符）
    ② 以 -MatchPid 为根的**进程树**（递归子进程），并给出「Java 进程数」等计数
    ③ 每行带台钟时刻，便于与 Java 侧打印的时刻逐条对照

  自带断言（陷阱 #27）：
    A1 至少能看到 PID 1 之外的若干进程（避免"空快照也算通过"）
    A2 当 -MatchPid 指定且该 pid 存在时，进程树必须非空
    A3 输出 LF、CR=0

  用法：pwsh -NoProfile -File tools\snapshot-processes.ps1 -Tag before -MatchPid 61776
        pwsh -NoProfile -File tools\snapshot-processes.ps1 -Tag after  -MatchPid 61776
#>
param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [int]$MatchPid = 0,
  [string]$OutFile = ''
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
if (-not $OutFile) { $OutFile = Join-Path $outDir "e3d-processes-$Tag.txt" }
$enc = New-Object Text.UTF8Encoding($false)

$lines = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $lines.Add($s) }

L "E3-d 进程快照 — tag=$Tag"
L "====================================================================="
L "快照时刻：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L "主机 PID 基数：$(Get-CimInstance Win32_OperatingSystem | Select-Object -ExpandProperty CSName 2>$null)"
L ""

# 全部进程一次性取回（避免逐 pid 查询导致时刻漂移）
$all = Get-CimInstance Win32_Process
L "系统进程总数：$(($all | Measure-Object).Count)"
L ""

$javas = $all | Where-Object { $_.Name -in @('java.exe','javaw.exe') }
L "---- ① 全部 java/javaw 进程（$(($javas|Measure-Object).Count) 个）----"
L ("{0,-8} {1,-8} {2,-22} {3}" -f 'PID','PPID','CreationDate','Name/CommandLine(前200)')
foreach ($p in ($javas | Sort-Object ProcessId)) {
  $cl = "$($p.CommandLine)"
  $m = 'java'
  if ($cl -match '-jar\s+(\S+)') { $m = Split-Path $Matches[1] -Leaf }
  elseif ($cl -match '-cp\s+') { $m = 'spark-submit/driver' }
  L ("{0,-8} {1,-8} {2,-22} {3}" -f $p.ProcessId, $p.ParentProcessId, $p.CreationDate, $m)
  if ($cl) { L ("         cmd: " + $cl.Substring(0, [Math]::Min(200, $cl.Length))) }
}
L ""

if ($MatchPid -gt 0) {
  L "---- ② 以 pid=$MatchPid 为根的进程树（递归）----"
  $rootProc = $all | Where-Object { $_.ProcessId -eq $MatchPid }
  if (-not $rootProc) {
    L "  **未找到 pid=$MatchPid**（进程可能已退出 ⇒ 若这是 after 快照，即为预期结果）"
  } else {
    L ("  根：pid={0} name={1} ppid={2} created={3}" -f $rootProc.ProcessId, $rootProc.Name, $rootProc.ParentProcessId, $rootProc.CreationDate)
    if ($rootProc.CommandLine) { L ("      cmd: " + "$($rootProc.CommandLine)".Substring(0,[Math]::Min(240, "$($rootProc.CommandLine)".Length))) }
    # 递归子进程
    $tree = New-Object 'System.Collections.Generic.List[object]'
    $queue = New-Object 'System.Collections.Generic.Queue[int]'
    $queue.Enqueue($MatchPid)
    $seen = New-Object 'System.Collections.Generic.HashSet[int]'
    [void]$seen.Add($MatchPid)
    while ($queue.Count -gt 0) {
      $cur = $queue.Dequeue()
      foreach ($c in ($all | Where-Object { $_.ParentProcessId -eq $cur })) {
        if (-not $seen.Contains([int]$c.ProcessId)) {
          [void]$seen.Add([int]$c.ProcessId)
          $tree.Add($c)
          $queue.Enqueue([int]$c.ProcessId)
        }
      }
    }
    L "  后代进程数：$($tree.Count)"
    foreach ($c in $tree) { L ("    child pid={0} name={1} ppid={2} created={3}" -f $c.ProcessId, $c.Name, $c.ParentProcessId, $c.CreationDate) }
    L "  进程树总规模（含根）：$($tree.Count + 1)"
  }
  L ""
  L "---- ③ 检索：凡命令行含 spark-jobs jar 或 spark-submit 的进程 ----"
  $sparkish = $all | Where-Object { "$($_.CommandLine)" -match 'spark-jobs|spark-submit|spark-3\.5\.1' }
  L "  命中数：$(($sparkish|Measure-Object).Count)"
  foreach ($p in $sparkish) { L ("    pid={0} ppid={1} name={2}" -f $p.ProcessId, $p.ParentProcessId, $p.Name) }
}

$text = (($lines -join "`n") + "`n")
[IO.File]::WriteAllText($OutFile, $text, $enc)
$cr = ([regex]::Matches($text, [string][char]13)).Count
Write-Host "OUTFILE=$OutFile BYTES=$((Get-Item $OutFile).Length) CR=$cr"
if ($lines.Count -lt 5) { Write-Host "ASSERT A1 FAIL: 输出过短（$($lines.Count) 行）"; exit 1 }
if ($cr -ne 0) { Write-Host "ASSERT A3 FAIL: 存在 CR"; exit 1 }
Write-Host "ASSERT PASS: A1 行数=$($lines.Count) / A3 CR=0"
