# P2-02 只读取数：真实语料中 event_time 的形态分布（2026-09-12）
# 只读：只 Get-ChildItem / StreamReader.ReadToEnd / [regex]::Matches。不写任何被扫目录。
# 作者：P2-02 规格/施工单起草泳道。用法：pwsh -File scan-event-time-shapes.ps1
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'

# 契约冻结正则（逐字取自 contract-specs/schemas/canonical-event.v1.schema.json $defs.iso8601_time.pattern）
$contractPattern = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-]\d{2}:\d{2}|Z)$'
$rxExtract = [regex]'"event_time"\s*:\s*"([^"]*)"'
$rxContract = [regex]$contractPattern
$rxNoOffset = [regex]'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?$'
$rxSpaceSep = [regex]'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}'
$rxDateOnly = [regex]'^\d{4}-\d{2}-\d{2}$'
$rxEpoch = [regex]'^\d{10}$|^\d{13}$'
$rxNoColonOffset = [regex]'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?[+-]\d{4}$'
$rxSlash = [regex]'/'

function Classify([string]$v) {
  if ($null -eq $v) { return 'MISSING_KEY' }
  if ($v -eq '') { return 'EMPTY_STRING' }
  if ($rxContract.IsMatch($v)) {
    if ($v.EndsWith('Z')) { return 'CONTRACT_OK_Z' }
    return 'CONTRACT_OK_OFFSET'
  }
  if ($rxNoOffset.IsMatch($v)) { return 'NO_OFFSET' }
  if ($rxNoColonOffset.IsMatch($v)) { return 'OFFSET_NO_COLON' }
  if ($rxSpaceSep.IsMatch($v)) { return 'SPACE_SEP' }
  if ($rxDateOnly.IsMatch($v)) { return 'DATE_ONLY' }
  if ($rxEpoch.IsMatch($v)) { return 'EPOCH_DIGITS' }
  if ($rxSlash.IsMatch($v)) { return 'SLASH_SEP' }
  return 'OTHER'
}

$rows = New-Object System.Collections.Generic.List[object]
$samples = @{}
$fileGroup = New-Object System.Collections.Generic.List[object]
$grand = @{}

# 扫描面：landing/** 与 generator-output/** 与 tests/golden-dataset/** 下的 .jsonl
$scanRoots = @("$root\landing", "$root\generator-output", "$root\tests\golden-dataset")
$files = foreach ($r in $scanRoots) {
  if (Test-Path -LiteralPath $r) {
    Get-ChildItem -LiteralPath $r -Recurse -File -Filter *.jsonl
  }
}
$files = $files | Sort-Object FullName
"SCAN_ROOT_COUNT`t$($scanRoots.Count)"
"SCAN_FILE_COUNT`t$($files.Count)"
"SCAN_TOTAL_BYTES`t$(($files | Measure-Object -Property Length -Sum).Sum)"
"CONTRACT_PATTERN`t$contractPattern"
""

foreach ($f in $files) {
  $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
  $lineCount = ($text.Split("`n")).Count
  $ms = $rxExtract.Matches($text)
  $local = @{}
  $localTotal = 0
  foreach ($m in $ms) {
    $cls = Classify $m.Groups[1].Value
    $localTotal++
    if (-not $local.ContainsKey($cls)) { $local[$cls] = 0 }
    $local[$cls] = $local[$cls] + 1
    if (-not $grand.ContainsKey($cls)) { $grand[$cls] = 0 }
    $grand[$cls] = $grand[$cls] + 1
    if (-not $samples.ContainsKey($cls)) { $samples[$cls] = New-Object System.Collections.Generic.List[string] }
    if ($samples[$cls].Count -lt 4 -and -not $samples[$cls].Contains($m.Groups[1].Value)) {
      $samples[$cls].Add($m.Groups[1].Value)
    }
    if ($rows.Count -lt 40 -or $cls -ne 'CONTRACT_OK_OFFSET') {
      if ($rows.Count -lt 4000) {
        $rows.Add([pscustomobject]@{ file = $f.Name; shape = $cls; value = $m.Groups[1].Value })
      }
    }
  }
  $rel = $f.FullName.Substring($root.Length + 1)
  $shapeStr = (($local.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '; ')
  $fileGroup.Add([pscustomobject]@{ file = $rel; bytes = $f.Length; lines = $lineCount; extracted = $localTotal; shapes = $shapeStr })
}

"=== PER-FILE ==="
"file`tbytes`tlines`textracted`tshapes"
foreach ($r in $fileGroup) { "$($r.file)`t$($r.bytes)`t$($r.lines)`t$($r.extracted)`t$($r.shapes)" }
""
"=== GRAND TOTAL BY SHAPE ==="
"shape`tcount"
foreach ($k in ($grand.Keys | Sort-Object)) { "$k`t$($grand[$k])" }
""
"=== DISTINCT SAMPLES PER SHAPE (max 4) ==="
foreach ($k in ($samples.Keys | Sort-Object)) {
  "SHAPE $k -> " + (($samples[$k]) -join ' | ')
}
""
"=== POSITIVE CONTROL ==="
$ctlFile = "$root\tests\golden-dataset\events\golden-20260901.jsonl"
$ctlText = [System.IO.File]::ReadAllText($ctlFile, [System.Text.Encoding]::UTF8)
$ctlMs = $rxExtract.Matches($ctlText)
"CONTROL_FILE`t$ctlFile"
"CONTROL_EXTRACT_COUNT`t$($ctlMs.Count)"
"CONTROL_FIRST_VALUE`t$($ctlMs[0].Groups[1].Value)"
"CONTROL_FIRST_CLASS`t$(Classify $ctlMs[0].Groups[1].Value)"
$ctlAbsent = $rxExtract.Matches('{"event_time_ZZZ":"nope"}').Count
"NEGATIVE_CONTROL_ABSENT_KEY_COUNT`t$ctlAbsent"
"CONTROL_METHOD`tSelect-String 对照（不使用 git grep / pathspec）"
$ss = Select-String -LiteralPath $ctlFile -SimpleMatch -Pattern '"event_time"' | Measure-Object
"CONTROL_SELECTSTRING_HITS`t$($ss.Count)"
