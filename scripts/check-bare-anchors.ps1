# check-bare-anchors.ps1 —— contract-specs 裸锚点门禁（D-065 裁决 3 的交付物）
#
# 规则（D-065 裁决 2，冻结点：`docs/acceptance/ct-batch-20260912/RULINGS.md` §5bis）：
#   * 新增锚点必须带**文件名**（例：`docs/contracts/event-contract.md §2.5 L92-L100`）
#     或**在册版本前缀**（例：`指导书 V2.4 §4.1.1.3 L49`）；
#   * **裸锚点不得新增**。
# 判据（D-065 裁决 3）：裸锚点集合 ⊆ 台账集合；新增即门禁失败（响亮报错）；
#   台账**只减不增**；每次删减须在看板登记。守卫自带**正向对照**。
#
# 口径定义（本脚本即唯一实现，机器可判）：
#   * 锚点  = 正则 `§<节号> L<行号>`（节号可多级，如 §4.1.1.3；行号必须有）；
#   * 裸锚点 = 该 `§` **同一行**、**前 100 字符**内既无文件名
#             （`*.md|json|yaml|yml|java|scala|sql|txt|csv`）也无版本号（`V<数字>.<数字>`）。
#   * 说明：D-065 事实 1 的 **258** 是审计脚本 `audit-anchors.ps1` 的口径（只排除 `V2.1 `/`V2.3 ` 前缀、
#     只扫 5 个文件）测得的**出现次数**；本守卫按**规则**判定，实测 **201 处 / 113 行**，
#     差异 57 处全部已带版本前缀或文件名（即按规则本就合规）。两个数字都写在台账表头里，不得只引其一。
#
# 用法：
#   pwsh -File scripts/check-bare-anchors.ps1                  # 门禁（只读，PASS 退出码 0 / FAIL 1）
#   pwsh -File scripts/check-bare-anchors.ps1 -WriteLedger     # 首次建台账；已存在时**只许减**（不新增键、不升计数）
#   pwsh -File scripts/check-bare-anchors.ps1 -WriteLedger -Force   # 强行重建基线（响亮警告；须在看板登记）
#   pwsh -File scripts/check-bare-anchors.ps1 -ContractRoot <目录> -LedgerPath <台账>   # 负向对照用（不改仓库）
#
# 纪律：门禁只读仓库（除 `-WriteLedger` 外不写任何文件）；不删除任何文件。
[CmdletBinding()]
param(
  [string]$ContractRoot = 'contract-specs',
  [string]$LedgerPath   = 'scripts\contract-bare-anchors.allowlist.txt',
  [switch]$WriteLedger,
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$enc = [Text.UTF8Encoding]::new($false)

$rxAnchor = [regex]'§(?<sec>\d+(?:\.\d+)*)\s*L(?<line>\d+)'
$rxFile   = [regex]'[A-Za-z0-9_\-\./\\]+\.(?:md|json|yaml|yml|java|scala|sql|txt|csv)\b'
$rxVer    = [regex]'[Vv]\d+\.\d+'
$winLen   = 100
$sep      = [char]1   # 内部键分隔符，不会出现在文件名/锚点文本里

function Get-Anchors {
  param([string]$Path, [string]$Rel)
  $txt = [IO.File]::ReadAllText($Path, $enc)
  $out = New-Object System.Collections.Generic.List[object]
  foreach ($m in $rxAnchor.Matches($txt)) {
    $nl = $txt.LastIndexOf("`n", [Math]::Max(0, $m.Index - 1))
    $ls = if ($nl -lt 0) { 0 } else { $nl + 1 }
    $pref = $txt.Substring($ls, $m.Index - $ls)
    $win = if ($pref.Length -gt $winLen) { $pref.Substring($pref.Length - $winLen) } else { $pref }
    $hasFile = $rxFile.IsMatch($win)
    $hasVer  = $rxVer.IsMatch($win)
    $out.Add([pscustomobject]@{
      file = $Rel; anchor = $m.Value; bare = (-not $hasFile -and -not $hasVer)
      hasFile = $hasFile; hasVer = $hasVer
    })
  }
  return , $out
}

# 台账键一律用**规范前缀** `contract-specs\<相对路径>`，与扫描根落在哪里无关：
# 这样 `-ContractRoot <临时副本>` 的负向对照才会因"注入了新锚点"而失败，
# 而不是因为键前缀被换掉导致全量误报（那是假红，会掩盖真问题）。
$absRoot = if ([IO.Path]::IsPathRooted($ContractRoot)) { $ContractRoot } else { Join-Path $repo $ContractRoot }
$ledgerRootLabel = 'contract-specs'

# ---------- 正向对照（D-065 裁决 3 强制；跑真实代码路径，不另写一套判断） ----------
$probePath = Join-Path $env:TEMP ('anchor-probe-' + $PID + '.txt')
$probe = @(
  '裸锚点：见 §2.5 L92 的说明',
  '带文件名：见 docs/contracts/event-contract.md §2.5 L92-L100',
  '带在册版本前缀：见 指导书 V2.4 §4.1.1.3 L49',
  '带英文版本前缀：见 V2.1 §4.2 L134'
)
[IO.File]::WriteAllLines($probePath, $probe, $enc)
# 注意：Get-Anchors 返回的是**集合对象**，必须用 foreach 枚举；
# 早期版本写成 `@(Get-Anchors ...)`，集合被当成单个元素 ⇒ Count=1 ⇒ 本对照立即报假红
# （这条对照正是为此存在：它先于任何 PASS 拦住了写坏的守卫）。
$probeRows = New-Object System.Collections.Generic.List[object]
foreach ($r in (Get-Anchors -Path $probePath -Rel 'PROBE')) { $probeRows.Add($r) }
Remove-Item -LiteralPath $probePath -Force
$probeBare = New-Object System.Collections.Generic.List[object]
foreach ($r in $probeRows) { if ($r.bare) { $probeBare.Add($r) } }
Write-Host ('  [正向对照] 对照样本锚点=' + $probeRows.Count + ' 裸锚点=' + $probeBare.Count + ' → ' + (@($probeBare | ForEach-Object { $_.anchor }) -join ', '))
if ($probeRows.Count -ne 4) { throw ('正向对照失败：对照样本里应识别出 4 个锚点，实测 ' + $probeRows.Count + ' 个') }
if ($probeBare.Count -ne 1 -or $probeBare[0].anchor -ne '§2.5 L92') {
  throw ('正向对照失败：应只有第 1 行「§2.5 L92」计入裸锚点，实测 = [' + (@($probeBare | ForEach-Object { $_.anchor }) -join ', ') + ']')
}
Write-Host '  [正向对照] 4 个对照锚点 → 裸锚点恰 1 个（§2.5 L92）；带文件名/带版本前缀的 3 个均未计入  PASS'

# ---------- 当前实况 ----------
if (-not (Test-Path -LiteralPath $absRoot)) { throw ('扫描根不存在：' + $absRoot) }
$files = @(Get-ChildItem -LiteralPath $absRoot -Recurse -File |
    Where-Object { $_.FullName -notmatch '\\\.git\\|\\target\\|\\node_modules\\|\\\.verify\\' })
$all = New-Object System.Collections.Generic.List[object]
foreach ($f in $files) {
  $rel = $ledgerRootLabel + '\' + $f.FullName.Substring($absRoot.Length + 1)
  foreach ($r in (Get-Anchors -Path $f.FullName -Rel $rel)) { $all.Add($r) }
}
$bare = @($all | Where-Object { $_.bare })
$cur = @{}
foreach ($b in $bare) {
  $k = $b.file + $sep + $b.anchor
  if ($cur.ContainsKey($k)) { $cur[$k] = $cur[$k] + 1 } else { $cur[$k] = 1 }
}
$ledgerAbs = if ([IO.Path]::IsPathRooted($LedgerPath)) { $LedgerPath } else { Join-Path $repo $LedgerPath }

Write-Host ''
Write-Host ('  == 裸锚点实况（扫描根 ' + $ContractRoot + '，文件 ' + $files.Count + ' 个）==')
Write-Host ('  锚点总数（含合规）= ' + $all.Count + ' ；裸锚点出现次数 = ' + $bare.Count + ' ；去重后（文件|锚点）= ' + $cur.Count + ' 行')
$bareGroups = @($bare | Group-Object { $_.file } | Sort-Object Name)
# 列宽按实际最长文件名现算（原来硬编码 58：`generation-artifact-manifest.v1.schema.json` 长 59 ⇒ 名字与计数粘连成 "…json1 处"，读数会被误读）
$nameW = 58
foreach ($g in $bareGroups) { if ($g.Name.Length -gt $nameW) { $nameW = $g.Name.Length } }
foreach ($g in $bareGroups) { Write-Host ('    ' + $g.Name.PadRight($nameW + 2) + $g.Count + ' 处') }
Write-Host ('  非 markdown 载体占比：' + (@($bare | Group-Object { [IO.Path]::GetExtension($_.file) } | ForEach-Object { $_.Name + '=' + $_.Count }) -join '  '))

# ---------- 建/重建台账 ----------
if ($WriteLedger) {
  $prev = @{}
  if (Test-Path -LiteralPath $ledgerAbs) {
    foreach ($ln in [IO.File]::ReadAllLines($ledgerAbs, $enc)) {
      if ($ln.Trim() -eq '' -or $ln.StartsWith('#')) { continue }
      $p = $ln -split '\|'
      if ($p.Count -ne 3) { throw ('台账行格式非法（应为 文件|锚点|次数）：' + $ln) }
      $prev[$p[0].Trim() + $sep + $p[1].Trim()] = [int]$p[2].Trim()
    }
  }
  if ($prev.Count -gt 0 -and -not $Force) {
    $added = @($cur.Keys | Where-Object { -not $prev.ContainsKey($_) })
    $raised = @($cur.Keys | Where-Object { $prev.ContainsKey($_) -and $cur[$_] -gt $prev[$_] })
    if ($added.Count -gt 0 -or $raised.Count -gt 0) {
      Write-Host ''
      Write-Host '  [拒绝重建] 台账只减不增：本次基线会新增/升高以下条目 ——' -ForegroundColor Red
      $added  | ForEach-Object { Write-Host ('    新增 ' + ($_ -replace $sep, ' :: ')) }
      $raised | ForEach-Object { Write-Host ('    升高 ' + ($_ -replace $sep, ' :: ') + ' → ' + $cur[$_]) }
      Write-Host '  ⇒ 先按 D-065 规则给锚点补文件名/版本前缀（或经裁决后 -Force 重建并在看板登记）。'
      exit 1
    }
    Write-Host '  [重建] 只减不增检查通过（无新增键、无升高计数）。'
  } elseif ($prev.Count -gt 0 -and $Force) {
    Write-Host '  [警告] -Force 强行重建台账基线（会吞掉新增裸锚点）—— 必须已在看板登记。' -ForegroundColor Yellow
  }
  $rows = @($cur.Keys | Sort-Object | ForEach-Object {
    $kv = $_ -split $sep
    [pscustomobject]@{ file = $kv[0]; anchor = $kv[1]; n = $cur[$_] }
  })
  $head = @(
    '# contract-specs 裸锚点台账（D-065 裁决 3）—— 只减不增；每次删减/重建须在看板登记',
    ('# 重建时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '    重建命令：pwsh -File scripts/check-bare-anchors.ps1 -WriteLedger' + $(if ($Force) { ' -Force' } else { '' })),
    '# 口径：锚点=`§<节号> L<行号>`；裸锚点=同一行前 100 字符内既无文件名(*.md|json|yaml|yml|java|scala|sql|txt|csv)也无版本号(V<x>.<y>)',
    ('# 本次实测：裸锚点出现次数 = ' + $bare.Count + ' ，去重 (文件|锚点文本) = ' + $rows.Count + ' 行'),
    '# 与 D-065 事实 1 的 258 的关系：258 = 审计脚本 audit-anchors.ps1 口径（只排除 V2.1/V2.3 前缀、只扫 5 个文件）的出现次数；',
    '#   本台账按"规则口径"登记，两者的差全部是**已带版本前缀或文件名**的锚点（按规则本就合规，不计为负债）。两个数字都必须引用。',
    ('# 行数基线（只减不增）= ' + $rows.Count),
    '# 字段：文件|锚点文本|出现次数'
  )
  $body = @($rows | ForEach-Object { $_.file + '|' + $_.anchor + '|' + $_.n })
  [IO.File]::WriteAllLines($ledgerAbs, ($head + $body), $enc)
  Write-Host ''
  Write-Host ('  已写出台账：' + $ledgerAbs + '（' + ($head.Count + $body.Count) + ' 行，其中数据行 ' + $rows.Count + '，出现次数合计 ' + $bare.Count + '）')
  exit 0
}

# ---------- 门禁 ----------
if (-not (Test-Path -LiteralPath $ledgerAbs)) {
  Write-Host ('  [门禁失败] 台账不存在：' + $ledgerAbs + ' ⇒ 先跑 -WriteLedger 建基线。') -ForegroundColor Red
  exit 1
}
$entries = @{}
$baseRows = -1
$ledgerLines = [IO.File]::ReadAllLines($ledgerAbs, $enc)
foreach ($ln in $ledgerLines) {
  if ($ln.Trim() -eq '') { continue }
  if ($ln.StartsWith('#')) {
    $m = [regex]::Match($ln, '^#\s*行数基线（只减不增）\s*=\s*(\d+)')
    if ($m.Success) { $baseRows = [int]$m.Groups[1].Value }
    continue
  }
  $p = $ln -split '\|'
  if ($p.Count -ne 3) { throw ('台账行格式非法（应为 文件|锚点|次数）：' + $ln) }
  $entries[$p[0].Trim() + $sep + $p[1].Trim()] = [int]$p[2].Trim()
}
if ($baseRows -lt 0) { throw '台账表头缺少「# 行数基线（只减不增）= N」' }

$new   = @($cur.Keys | Where-Object { -not $entries.ContainsKey($_) } | Sort-Object)
$grown = @($cur.Keys | Where-Object { $entries.ContainsKey($_) -and $cur[$_] -gt $entries[$_] } | Sort-Object)
$stale = @($entries.Keys | Where-Object { -not $cur.ContainsKey($_) } | Sort-Object)
$shrunk = @($cur.Keys | Where-Object { $entries.ContainsKey($_) -and $cur[$_] -lt $entries[$_] } | Sort-Object)

Write-Host ''
Write-Host ('  == 门禁（台账 ' + $entries.Count + ' 行 / 基线 ' + $baseRows + ' 行）==')
$fail = $false
if ($entries.Count -gt $baseRows) {
  $fail = $true
  Write-Host ('  [门禁失败] 台账行数 ' + $entries.Count + ' > 基线 ' + $baseRows + ' ⇒ 台账只减不增，被改大了。') -ForegroundColor Red
}
if ($new.Count -gt 0) {
  $fail = $true
  Write-Host ('  [门禁失败] 新增裸锚点 ' + $new.Count + ' 处（D-065：裸锚点不得新增）——') -ForegroundColor Red
  $new | ForEach-Object { Write-Host ('    ' + ($_ -replace $sep, '  ') ) }
  Write-Host '    ⇒ 修法：给该锚点补**文件名**或**在册版本前缀**（例：`docs/contracts/event-contract.md §2.5 L92-L100`）。'
}
if ($grown.Count -gt 0) {
  $fail = $true
  Write-Host ('  [门禁失败] 台账内锚点出现次数升高 ' + $grown.Count + ' 处——') -ForegroundColor Red
  $grown | ForEach-Object { Write-Host ('    ' + ($_ -replace $sep, ' :: ') + '  台账=' + $entries[$_] + ' 实测=' + $cur[$_]) }
}
if ($stale.Count -gt 0) {
  Write-Host ('  [可删减 ' + $stale.Count + ' 行] 台账内已不再是裸锚点的条目（删减须在看板登记后跑 -WriteLedger）：') -ForegroundColor Yellow
  $stale | ForEach-Object { Write-Host ('    ' + ($_ -replace $sep, ' :: ')) }
}
if ($shrunk.Count -gt 0) {
  Write-Host ('  [已减少 ' + $shrunk.Count + ' 处] 出现次数低于台账（下次 -WriteLedger 收敛）：') -ForegroundColor Yellow
  $shrunk | ForEach-Object { Write-Host ('    ' + ($_ -replace $sep, ' :: ') + '  台账=' + $entries[$_] + ' 实测=' + $cur[$_]) }
}

Write-Host ''
if ($fail) {
  Write-Host '  结果 = FAIL（裸锚点门禁未通过；见上列失败项）' -ForegroundColor Red
  exit 1
}
Write-Host ('  结果 = PASS（裸锚点 ' + $bare.Count + ' 处 / ' + $cur.Count + ' 行，全部在台账内；台账 ' + $entries.Count + ' 行 ≤ 基线 ' + $baseRows + '）')
exit 0
