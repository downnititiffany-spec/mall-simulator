<#
  R2-b 输入纯净度检查（可复跑）——判「落地区是否还有无断点的待处理文件」
  =====================================================================
  为什么必须有这一步：采集（POST /api/v1/ingestion/runs）是**整目录全量扫描**，
  不是"只采我放的那个文件"。landing\events 里任何**无 file_checkpoint** 的文件
  都会在同一批里被吃掉 ⇒ 输入不纯 ⇒ 同输入对照作废。

  判定：events 文件集合  ⊆  有断点的文件集合（否则差集非空 ⇒ 停止，不得开跑）

  路径归一（M1-12 已登记）：`file_checkpoint.file_path` 存的是**归一化**写法，
  实测形如 `D:\\Develop_code\\GraduationProject\\landing\\events\\<name>.jsonl`
  且可能带 `\.\` 段。本脚本两侧都归一到「小写 + 反斜杠折叠 + 去 \.\ + 取文件名」后比对，
  并**同时**输出严格模式（只比文件名 basename）的结果，两种口径都落盘。

  自带断言（陷阱 #27）：
    A1 events 目录文件数 > 0
    A2 checkpoint 行数 > 0
    A3 输出 LF、CR=0
    A4 **正向对照**：人为构造一个不存在于 checkpoint 的假文件名，验证差集算法**能**报出来
       （否则"差集为空"可能是算法坏了，而不是真的纯净 —— F-38 ①）

  用法：pwsh -NoProfile -File tools\check-input-purity.ps1
#>
param([string]$OutFile = '')
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
if (-not $OutFile) { $OutFile = Join-Path $outDir 'control-input-purity.txt' }
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$enc = New-Object Text.UTF8Encoding($false)
$buf = New-Object 'System.Collections.Generic.List[string]'
$fail = New-Object 'System.Collections.Generic.List[string]'
function L { param([string]$s) $buf.Add($s) }

$eventsDir = Join-Path $root 'landing\events'
$allCpFile = Join-Path $root 'landing\events'
$events = @(Get-ChildItem -File $eventsDir | Sort-Object Name)

L "R2-b 输入纯净度检查（landing\events 是否还有无断点文件）"
L "====================================================================="
L "检查时点：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')"
L "目录    ：$eventsDir"
L ""

# ---- 库内断点 ----
$q = "SELECT file_path, next_offset, source_id FROM analytics_meta.file_checkpoint ORDER BY id;"
$rows = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q 2>&1 | Where-Object { $_ -notmatch 'Warning.*password' }
$cp = @()
foreach ($r in ($rows | Select-Object -Skip 1)) {
  if ([string]::IsNullOrWhiteSpace($r)) { continue }
  $p = $r -split "`t"
  if ($p.Count -ge 2) { $cp += [pscustomobject]@{ path = $p[0]; nextOffset = $p[1]; sourceId = $p[2] } }
}
L "---- 库内 file_checkpoint ----"
L "  总行数：$($cp.Count)"
$cpNullSource = @($cp | Where-Object { "$($_.sourceId)" -eq 'NULL' -or "$($_.sourceId)" -eq '' }).Count
L "  source_id 为 NULL 的行数：$cpNullSource"
L ""

# ---- events 目录 ----
L "---- landing\events 目录 ----"
L "  文件数：$($events.Count)"
L "  合计字节：$((($events | Measure-Object -Property Length -Sum).Sum))"
L ""

# 归一函数：小写、反斜杠折叠、去 \.\ 、取 basename
function Norm([string]$p) {
  $x = $p.ToLowerInvariant() -replace '\\\\', '\' -replace '\\\.\\', '\' -replace '/', '\'
  $x = $x -replace '^\.\\', ''
  return $x
}
$cpByName = @{}
foreach ($c in $cp) { $cpByName[(Split-Path (Norm $c.path) -Leaf)] = $c }
$cpByFull = @{}
foreach ($c in $cp) { $cpByFull[(Norm $c.path)] = $c }

L "---- 逐文件核对（basename 口径）----"
$missing = New-Object 'System.Collections.Generic.List[string]'
foreach ($e in $events) {
  $hit = $cpByName.ContainsKey($e.Name.ToLowerInvariant())
  $cpTxt = if ($hit) { "next_offset=$($cpByName[$e.Name.ToLowerInvariant()].nextOffset) source_id=$($cpByName[$e.Name.ToLowerInvariant()].sourceId)" } else { "**无断点**" }
  L ("  {0,-46} {1,12} B  {2}" -f $e.Name, $e.Length, $cpTxt)
  if (-not $hit) { $missing.Add($e.Name) }
}
L ""
L "==== 三个读数（父侧要求的判据）===="
L "  ① landing\events 文件数            = $($events.Count)"
L "  ② 其中能在 file_checkpoint 找到的  = $($events.Count - $missing.Count)"
L "  ③ 差集名单（无断点 ⇒ 会被一起采集）= $(if ($missing.Count -eq 0) { '（空）' } else { $missing -join ', ' })"
L ""

# ---- 正向对照：假文件名必须被判缺失 ----
$probe = 'zzz-nonexistent-probe-file-20260912.jsonl'
$probeHit = $cpByName.ContainsKey($probe.ToLowerInvariant())
L "---- A4 正向对照 ----"
L "  构造假文件名：$probe"
L "  算法判定其有无断点：$(if ($probeHit) { '有（**异常**：算法把不存在的文件判成有断点）' } else { '无断点 ✓' })"
if ($probeHit) { $fail.Add("A4 失败：正向对照未命中所构造的假文件 ⇒ 差集算法不可信") }
L ""

if ($events.Count -le 0) { $fail.Add("A1 失败：events 目录文件数 = 0") }
if ($cp.Count -le 0) { $fail.Add("A2 失败：file_checkpoint 行数 = 0") }

L "---- 判定 ----"
if ($missing.Count -eq 0) {
  L "  **输入纯净**：landing\events 全部文件均有断点 ⇒ 采集只会吃到我新放的那一个文件。"
  L "  可以开跑 R2-b 对照。"
} else {
  L "  **输入不纯**：存在 $($missing.Count) 个无断点文件 ⇒ 采集会把它们一并吃掉，对照作废。"
  L "  ⇒ 按 predictions-control-run.txt §三：**停止并报告父侧**，不得径自开跑。"
  $fail.Add("纯净度失败：$($missing.Count) 个无断点文件：$($missing -join ', ')")
}

$text = (($buf -join "`n") + "`n")
[IO.File]::WriteAllText($OutFile, $text, $enc)
$cr = ([regex]::Matches($text, [string][char]13)).Count

Write-Host "OUTFILE=$OutFile BYTES=$((Get-Item $OutFile).Length) LINES=$($buf.Count) CR=$cr"
Write-Host "events_files=$($events.Count) with_checkpoint=$($events.Count - $missing.Count) missing=$($missing.Count)"
if ($cr -ne 0) { Write-Host "A3 FAIL: CR=$cr"; exit 1 }
if ($fail.Count -eq 0) { Write-Host "ASSERT PASS: all" } else {
  Write-Host "ASSERT FAIL ($($fail.Count)):"; $fail | ForEach-Object { Write-Host "  - $_" }; exit 1
}
