# analyze-landing-ids.ps1 -- P2-03 只读取证：真实落地区 JSONL 的业务键取值形态统计（对照黄金集 55 行）
# 只读输入：landing/events/*.jsonl（流式逐行，不整文件载入）
# 输出：本脚本所在 raw/ 目录下的 landing-id-shapes.tsv
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = 'D:\Develop_code\GraduationProject'
$outDir = $PSScriptRoot
$inputs = @(
  (Join-Path $root 'landing\events\2026091211.jsonl'),
  (Join-Path $root 'landing\events\2026091210.jsonl')
)
$fields = @('event_id', 'user_id', 'product_id', 'order_id', 'payment_id', 'refund_id', 'category_id', 'brand_id', 'session_id')

function Get-Shape([string]$s) {
  if ($null -eq $s) { return 'NULL' }
  if ($s -eq '') { return 'EMPTY' }
  if ($s -match '^[0-9]+$') { if ($s.Length -ge 15) { return 'NUMERIC_SNOWFLAKE_LEN' } else { return 'NUMERIC' } }
  if ($s -match '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') { return 'UUID_CANONICAL' }
  if ($s -match '^[A-Za-z]+[0-9]+$') { return 'PREFIXED_NUMERIC' }
  if ($s -match '^[0-9a-fA-F]{32}$') { return 'UUID_HEX32' }
  if ($s -match '^[A-Za-z0-9]+$') { return 'ALNUM_MIX' }
  return 'OTHER'
}

$stat = @{}
foreach ($f in $fields) { $stat[$f] = @{ n = 0; vals = (New-Object System.Collections.Generic.HashSet[string]); lens = (New-Object System.Collections.Generic.HashSet[int]); shapes = @{}; nonascii = 0; ws = 0 } }

$files = @()
foreach ($p in $inputs) {
  if (-not (Test-Path $p)) { continue }
  $files += [pscustomobject]@{ path = $p.Replace($root, ''); sha256 = (Get-FileHash $p -Algorithm SHA256).Hash.ToLower(); lines = 0; bytes = (Get-Item $p).Length; json = 0; notjson = 0 }
  $idx = $files.Count - 1
  $reader = [System.IO.StreamReader]::new($p, (New-Object System.Text.UTF8Encoding($false, $true)))
  try {
    while ($null -ne ($line = $reader.ReadLine())) {
      $files[$idx].lines++
      $obj = $null
      try { $obj = $line | ConvertFrom-Json -ErrorAction Stop; $files[$idx].json++ }
      catch { $files[$idx].notjson++; continue }
      $vals = @{}
      if ($null -ne $obj.PSObject.Properties['event_id']) { $vals['event_id'] = [string]$obj.event_id }
      if ($null -ne $obj.PSObject.Properties['payload']) {
        foreach ($k in @($obj.payload.PSObject.Properties.Name)) {
          if ($fields -contains $k) { $vals[$k] = [string]$obj.payload.$k }
        }
      }
      foreach ($k in $vals.Keys) {
        $v = $vals[$k]
        $s = $stat[$k]
        $s.n++
        [void]$s.vals.Add($v)
        [void]$s.lens.Add($v.Length)
        $sh = Get-Shape $v
        if (-not $s.shapes.ContainsKey($sh)) { $s.shapes[$sh] = 0 }
        $s.shapes[$sh]++
        if ($v -match '[^\x00-\x7F]') { $s.nonascii++ }
        if ($v -ne $v.Trim()) { $s.ws++ }
      }
    }
  } finally { $reader.Dispose() }
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($f in $fields) {
  $s = $stat[$f]
  $shapeStr = (($s.shapes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')
  $sample = (($s.vals | Select-Object -First 3) -join ' ; ')
  $rows.Add([pscustomobject]@{
      field = $f; occurrences = $s.n; distinct = $s.vals.Count
      min_len = if ($s.lens.Count -gt 0) { ($s.lens | Measure-Object -Minimum).Minimum } else { 0 }
      max_len = if ($s.lens.Count -gt 0) { ($s.lens | Measure-Object -Maximum).Maximum } else { 0 }
      shapes = $shapeStr; whitespace = $s.ws; nonascii = $s.nonascii; sample = $sample
    })
}
$rows | ForEach-Object { "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}`t{8}" -f $_.field, $_.occurrences, $_.distinct, $_.min_len, $_.max_len, $_.shapes, $_.whitespace, $_.nonascii, $_.sample } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'landing-id-shapes.tsv')

Write-Output '--- 输入文件指纹 ---'
$files | ForEach-Object { "{0}`tsha256={1}`tlines={2}`tjson={3}`tnotjson={4}`tbytes={5}" -f $_.path, $_.sha256, $_.lines, $_.json, $_.notjson, $_.bytes }
Write-Output '--- 字段形态 ---'
$rows | Format-Table -AutoSize
