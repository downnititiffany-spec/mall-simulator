# audit-anchors.ps1 —— contract-specs 裸锚点审计（可复跑，纯只读）
# 目的：把 R-M1-5-1「258 处裸锚点未复核」变成可判定的证据：
#   步骤 1 全仓 markdown 编号标题普查（正面控制：任何"标签唯一"的主张必须对全体文档成立）
#   步骤 2 逐个裸锚点做目标文档判定（标签全仓唯一 / token 内容命中 / 无法判定）
# 纪律：本脚本不修改任何被审计文件；只写 raw/ 与 *.tsv 证据。
# 用法：在仓库根目录执行  pwsh -File docs\acceptance\m1-5-anchor-audit-20260912\scripts\audit-anchors.ps1
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$enc = [Text.UTF8Encoding]::new($false)
$outDir = Join-Path $root 'docs\acceptance\m1-5-anchor-audit-20260912\raw'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$log = New-Object System.Collections.Generic.List[string]
function Say([string]$s) { $log.Add($s); Write-Host $s }

$targets = @(
  'contract-specs\openapi\generator-api.v1.yaml',
  'contract-specs\schemas\generation-artifact-manifest.v1.schema.json',
  'contract-specs\schemas\canonical-event.v1.schema.json',
  'contract-specs\schemas\ingestion-manifest.v1.schema.json',
  'contract-specs\README.md'
)
$rx = [regex]'(?<!V2\.3 )(?<!V2\.1 )§(?<sec>\d+(?:\.\d+)*)\s*L(?<line>\d+)'

Say ('== 步骤 1：全仓 markdown 编号标题普查 ==')
Say ('时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Say ('仓库根：' + $root)
# 采集锚点（先跑一遍，得到需要普查的标签集合）
$anchors = @()
foreach ($t in $targets) {
  $txt = [IO.File]::ReadAllText((Join-Path $root $t), $enc)
  foreach ($m in $rx.Matches($txt)) {
    $anchors += [pscustomobject]@{ file = $t; sec = $m.Groups['sec'].Value; line = [int]$m.Groups['line'].Value; index = $m.Index; len = $m.Length; text = $txt }
  }
}
Say ('裸锚点总数 = ' + $anchors.Count)
$want = @($anchors | ForEach-Object { $_.sec } | Sort-Object -Unique)
Say ('涉及标签 = ' + ($want -join ' '))

# 全仓普查：排除 .git / node_modules / target / .verify
# 并排除 references\（第三方样例项目，非本项目权威链；首轮未排除时它曾误命中 1 处 token，
# 属反面证据：token 打分必须限定在权威文档集合内，否则会把第三方 README 当成目标文档）
$mds = Get-ChildItem -Recurse -File -Filter *.md -Path $root |
  Where-Object { $_.FullName -notmatch '\\\.git\\|\\node_modules\\|\\target\\|\\\.verify\\|\\references\\' }
Say ('参与普查的 md 文件数 = ' + $mds.Count + '（已排除 references\\ 下的第三方样例项目）')
$docLabels = @{}
foreach ($md in $mds) {
  $rel = $md.FullName.Substring($root.Length + 1)
  $ls = [IO.File]::ReadAllText($md.FullName, $enc) -split "`n"
  $set = @()
  for ($i = 0; $i -lt $ls.Count; $i++) {
    $h = [regex]::Match($ls[$i], '^(#{1,4})\s+§?(\d+(?:\.\d+)*)\.?\s')
    if ($h.Success) { $set += $h.Groups[2].Value }
  }
  if ($set.Count -gt 0) { $docLabels[$rel] = @($set | Sort-Object -Unique) }
}
Say ''
Say ('== 标签 → 全仓文档数（只列锚点用到的标签） ==')
$labelOwner = @{}
foreach ($lab in $want) {
  $owners = @($docLabels.Keys | Where-Object { $docLabels[$_] -contains $lab })
  $labelOwner[$lab] = $owners
  $tail = ''
  if ($owners.Count -eq 1) { $tail = '  ← 全仓唯一：' + $owners[0] }
  elseif ($owners.Count -le 3) { $tail = '  ← ' + ($owners -join ' ; ') }
  Say ('  §' + $lab.PadRight(9) + ' 出现于 ' + ([string]$owners.Count).PadRight(4) + ' 个文档' + $tail)
}

Say ''
Say '== 步骤 2：逐个锚点判定目标文档 =='
# 只在"含该标签"的文档里做判定；先做 L / L+1 的内容打分（token 来自锚点上下文）
$docCache = @{}
function GetDoc([string]$rel) {
  if (-not $docCache.ContainsKey($rel)) {
    $ls = [IO.File]::ReadAllText((Join-Path $root $rel), $enc) -split "`n"
    $docCache[$rel] = $ls
  }
  return , $docCache[$rel]
}
$rows = @()
foreach ($a in $anchors) {
  $owners = $labelOwner[$a.sec]
  $cls = 'R-C'; $win = ''; $ev = ''
  if ($owners.Count -eq 0) { $ev = '该标签在全仓 markdown 里不存在' }
  elseif ($owners.Count -eq 1) { $cls = 'R-A'; $win = $owners[0]; $ev = '标签全仓唯一' }
  else {
    $after = $a.text.Substring($a.index + $a.len, [Math]::Min(90, $a.text.Length - ($a.index + $a.len)))
    $before = $a.text.Substring([Math]::Max(0, $a.index - 60), [Math]::Min(60, $a.index))
    $toks = @()
    foreach ($w in @($before, $after)) {
      foreach ($x in [regex]::Matches($w, '`([^`]{2,40})`')) { $toks += $x.Groups[1].Value }
      foreach ($x in [regex]::Matches($w, '[「『]([^」』]{2,30})[」』]')) { $toks += $x.Groups[1].Value }
    }
    $toks = @($toks | Sort-Object -Unique)
    if ($toks.Count -eq 0) { $ev = ('无 token 可核对；候选文档 ' + $owners.Count + ' 个') }
    else {
      $sc = @{}
      foreach ($o in $owners) {
        $ls = GetDoc $o
        $best = 0
        foreach ($off in 0, 1) {
          $ln = $a.line + $off
          if ($ln -lt 1 -or $ln -gt $ls.Count) { continue }
          $line = $ls[$ln-1].Trim()
          if ($line.Length -eq 0) { continue }
          $s = 0
          foreach ($tk in $toks) { if ($line.Contains($tk)) { $s++ } }
          if ($s -gt $best) { $best = $s }
        }
        if ($best -gt 0) { $sc[$o] = $best }
      }
      if ($sc.Count -eq 1) { $cls = 'R-B'; $win = @($sc.Keys)[0]; $ev = ('token 命中 ' + $sc[$win] + ' 个（候选文档 ' + $owners.Count + ' 个；tokens=' + ($toks -join ',') + '）') }
      elseif ($sc.Count -gt 1) { $ev = ('多文档同分：' + (($sc.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ' ')) }
      else { $ev = ('token 在任何候选文档的 L/L+1 都未命中（tokens=' + ($toks -join ',') + '）') }
    }
  }
  $rows += [pscustomobject]@{ file = ($a.file -replace 'contract-specs\\',''); sec = $a.sec; line = $a.line; cls = $cls; win = $win; ev = $ev }
}
foreach ($c in 'R-A','R-B','R-C') {
  Say ('  ' + $c + ' = ' + ($rows | Where-Object { $_.cls -eq $c }).Count + ' 处')
}
Say ''
Say '  -- R-A / R-B 命中文档分布 --'
$rows | Where-Object { $_.cls -in @('R-A','R-B') } | Group-Object { $_.cls + ' -> ' + $_.win } | Sort-Object Name | ForEach-Object { Say ('    ' + $_.Name.PadRight(72) + $_.Count + ' 处') }
Say ''
Say '  -- R-C 原因分布 --'
$rows | Where-Object { $_.cls -eq 'R-C' } | Group-Object { if ($_.ev -like '多文档同分*') { '多文档同分' } elseif ($_.ev -like '无 token*') { '无 token 可核对' } elseif ($_.ev -like 'token 在任何*') { 'token 未命中' } else { '标签全仓不存在' } } | Sort-Object Name | ForEach-Object { Say ('    ' + $_.Name.PadRight(18) + $_.Count + ' 处') }
Say ''
Say '  -- 结论 --'
Say '    机械可判定的锚点（R-A + R-B）与需人工逐条判定的锚点（R-C）已分开；'
Say '    注意：R-A 的"标签全仓唯一"这一判据本身仅对 §2.8/§2.9/§2.10/§4.1.1.3 成立，其余标签在多个文档中都存在，'
Say '    因此 258 处裸锚点不能靠统一规则（如 F-29 的 +1 或 +12/+145）批量改写。'

[IO.File]::WriteAllLines((Join-Path $outDir 'anchor-census-20260912.txt'), $log, $enc)
$tsv = @("file`tsection`tline`tclass`tresolved_doc`tevidence")
foreach ($r in $rows) { $tsv += ($r.file + "`t" + $r.sec + "`t" + $r.line + "`t" + $r.cls + "`t" + $r.win + "`t" + $r.ev) }
[IO.File]::WriteAllLines((Join-Path $outDir 'anchor-classify-20260912.tsv'), $tsv, $enc)
Write-Host ''
Write-Host ('已写出：' + (Join-Path $outDir 'anchor-census-20260912.txt'))
Write-Host ('已写出：' + (Join-Path $outDir 'anchor-classify-20260912.tsv'))
