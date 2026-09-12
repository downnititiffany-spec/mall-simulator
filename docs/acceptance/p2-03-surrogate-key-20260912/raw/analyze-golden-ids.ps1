# analyze-golden-ids.ps1 -- P2-03 只读取证：黄金集 55 行业务键真实取值形态统计
# 只读输入：tests/golden-dataset/events/golden-20260901.jsonl
# 输出：本脚本所在 raw/ 目录下的 golden-id-shapes.tsv / golden-id-samples.tsv / golden-line-classes.tsv
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = 'D:\Develop_code\GraduationProject'
$outDir = $PSScriptRoot
$golden = Join-Path $root 'tests\golden-dataset\events\golden-20260901.jsonl'

$lines = [System.IO.File]::ReadAllLines($golden, (New-Object System.Text.UTF8Encoding($false, $true)))
Write-Output "golden_file=$golden"
Write-Output "golden_lines=$($lines.Count)"
Write-Output "golden_sha256=$((Get-FileHash $golden -Algorithm SHA256).Hash.ToLower())"

# 候选业务键字段：信封 event_id + payload 内所有以 _id 结尾的键
$fieldNames = New-Object System.Collections.Generic.List[string]
$fieldNames.Add('event_id')

$parsed = New-Object System.Collections.Generic.List[object]
$badLines = New-Object System.Collections.Generic.List[object]
$lineClasses = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $lines.Count; $i++) {
  $raw = $lines[$i]
  $cls = ''
  $obj = $null
  if ([string]::IsNullOrWhiteSpace($raw)) { $cls = 'BLANK' }
  else {
    try { $obj = $raw | ConvertFrom-Json -ErrorAction Stop; $cls = 'JSON' }
    catch { $cls = 'NOT_JSON' }
  }
  $lineClasses.Add([pscustomobject]@{ line = $i + 1; cls = $cls; bytes_utf8 = [System.Text.Encoding]::UTF8.GetByteCount($raw) })
  if ($cls -ne 'JSON') { $badLines.Add([pscustomobject]@{ line = $i + 1; cls = $cls; raw = $raw }); continue }
  $hasId = $null -ne $obj.PSObject.Properties['event_id']
  $hasPayload = $null -ne $obj.PSObject.Properties['payload']
  $payloadKeys = @()
  if ($hasPayload) { $payloadKeys = @($obj.payload.PSObject.Properties.Name) }
  $parsed.Add([pscustomobject]@{
      line = $i + 1; obj = $obj; hasEventId = $hasId; hasPayload = $hasPayload; payloadKeys = $payloadKeys
    })
  if ($hasPayload) {
    foreach ($k in $payloadKeys) { if ($k -like '*_id') { $fieldNames.Add($k) } }
  }
}
$fieldNames = @($fieldNames | Sort-Object -Unique)

# 逐字段逐个观测值
$obs = New-Object System.Collections.Generic.List[object]
foreach ($p in $parsed) {
  foreach ($f in $fieldNames) {
    $v = $null; $present = $false; $src = ''
    if ($f -eq 'event_id') {
      if ($p.hasEventId) { $present = $true; $v = [string]$p.obj.event_id; $src = 'envelope' }
    }
    elseif ($p.hasPayload -and ($p.payloadKeys -contains $f)) {
      $present = $true; $v = [string]$p.obj.payload.$f; $src = 'payload'
    }
    if ($present) {
      $obs.Add([pscustomobject]@{ field = $f; line = $p.line; src = $src; value = $v })
    }
  }
}

$shape = {
  param($s)
  if ($null -eq $s) { return 'NULL' }
  if ($s -eq '') { return 'EMPTY' }
  if ($s -match '^[0-9]+$') { return 'NUMERIC' }
  if ($s -match '^[A-Za-z]+[0-9]+$') { return 'PREFIXED_NUMERIC' }
  if ($s -match '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') { return 'UUID_CANONICAL' }
  if ($s -match '^[0-9a-fA-F]{32}$') { return 'UUID_HEX32' }
  if ($s -match '^[0-9]{15,19}$') { return 'SNOWFLAKE_LIKE' }
  if ($s -match '^[A-Za-z0-9]+$') { return 'ALNUM_NOPREFIX_DIGIT_TAIL_MIX' }
  return 'OTHER'
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($g in ($obs | Group-Object field)) {
  $vals = @($g.Group.value)
  $distinct = @($vals | Sort-Object -Unique)
  $lens = @($vals | ForEach-Object { $_.Length })
  $charset = @($vals | ForEach-Object { ($_.ToCharArray() | Sort-Object -Unique) -join '' } | Sort-Object -Unique)
  $shapes = @($vals | ForEach-Object { & $shape $_ } | Sort-Object -Unique)
  $rows.Add([pscustomobject]@{
      field          = $g.Name
      occurrences    = $g.Count
      distinct       = $distinct.Count
      min_len        = ($lens | Measure-Object -Minimum).Minimum
      max_len        = ($lens | Measure-Object -Maximum).Maximum
      shapes         = ($shapes -join ',')
      has_uuid       = if ($shapes -contains 'UUID_CANONICAL') { 'YES' } else { 'NO' }
      has_pure_digit = if ($shapes -contains 'NUMERIC') { 'YES' } else { 'NO' }
      has_prefix     = if ($shapes -contains 'PREFIXED_NUMERIC') { 'YES' } else { 'NO' }
      charset_union  = ($charset -join ' ')
      whitespace     = if (@($vals | Where-Object { $_ -ne $_.Trim() }).Count -gt 0) { 'YES' } else { 'NO' }
      has_upper      = if (@($vals | Where-Object { $_ -cmatch '[A-Z]' }).Count -gt 0) { 'YES' } else { 'NO' }
      has_lower      = if (@($vals | Where-Object { $_ -cmatch '[a-z]' }).Count -gt 0) { 'YES' } else { 'NO' }
      has_nonascii   = if (@($vals | Where-Object { $_ -match '[^\x00-\x7F]' }).Count -gt 0) { 'YES' } else { 'NO' }
      sample_values  = (($distinct | Select-Object -First 8) -join ' ; ')
    })
}
$rows = $rows | Sort-Object field
$rows | ForEach-Object {
  "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}`t{8}`t{9}`t{10}`t{11}`t{12}`t{13}`t{14}" -f `
    $_.field, $_.occurrences, $_.distinct, $_.min_len, $_.max_len, $_.shapes, $_.has_uuid, `
    $_.has_pure_digit, $_.has_prefix, $_.has_upper, $_.has_lower, $_.has_nonascii, $_.whitespace, $_.charset_union, $_.sample_values
} | Set-Content -Encoding utf8 (Join-Path $outDir 'golden-id-shapes.tsv')

# 全量逐观测值样本（可回溯）
$obs | Sort-Object field, line | ForEach-Object { "{0}`t{1}`t{2}`t{3}" -f $_.field, $_.line, $_.src, $_.value } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'golden-id-samples.tsv')

$lineClasses | ForEach-Object { "{0}`t{1}`t{2}" -f $_.line, $_.cls, $_.bytes_utf8 } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'golden-line-classes.tsv')

Write-Output '--- 行分类计数 ---'
$lineClasses | Group-Object cls | Select-Object Name, Count | Format-Table -AutoSize
Write-Output '--- 字段形态 ---'
$rows | Format-Table -AutoSize
Write-Output '--- 非 JSON 行 ---'
$badLines | Format-Table -AutoSize
Write-Output "--- payload 键名并集 ---"
@($parsed | ForEach-Object { $_.payloadKeys } | Sort-Object -Unique) -join ', '
$sv = @($parsed | Where-Object { -not $_.hasEventId })
Write-Output "缺 event_id 的行数 = $($sv.Count)；行号 = $(($sv.line) -join ',')"
$scc = $parsed | Group-Object { [string]$_.obj.schema_version } | Select-Object Name, Count
Write-Output '--- schema_version 分布 ---'
$scc | Format-Table -AutoSize
$se = $parsed | Group-Object { [string]$_.obj.event_type } | Select-Object Name, Count | Sort-Object Name
Write-Output '--- event_type 分布 ---'
$se | Format-Table -AutoSize
