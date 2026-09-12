# scan-surrogate-key.ps1  -- P2-03 只读取证：代理键相关实现普查
# 只读：不写任何源码/契约/看板；输出写入本脚本所在的 raw/ 目录。
# 用法: pwsh -File .\scan-surrogate-key.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = 'D:\Develop_code\GraduationProject'
$outDir = $PSScriptRoot
$mods = @('analytics-server', 'spark-jobs', 'synthetic-data-generator', 'mall-simulator')
# 排除构建产物 / 依赖 / 元数据目录（*.java *.scala 只在这些目录下才会出现）
$exclude = '\\(target|node_modules|\.git|metastore_db|\.idea|generator-output|logs)\\' 

# 模式名 -> 正则（大小写敏感）。每个模式独立计数，独立记录命中。
$patterns = [ordered]@{
  'surrogate'       = 'surrogate'
  'surrogate_cap'   = 'Surrogate'
  'proxy_key_cn'    = '代理键'
  'sk_lower'        = 'sk_'
  'sk_upper'        = 'SK_'
  'underscore_sk'   = '\b\S*_sk\b'
  'row_key'         = 'row_key'
  'rowkey'          = 'rowkey'
  'rowkey_cap'      = 'RowKey'
  'snowflake'       = 'snowflake'
  'snowflake_cap'   = 'Snowflake'
  'uuid_upper'      = 'UUID'
  'uuid_lower'      = 'uuid'
  'uuid_mixed'      = 'Uuid'
  'digestutils'     = 'DigestUtils'
  'messagedigest'   = 'MessageDigest'
  'md5'             = 'md5'
  'md5_upper'       = 'MD5'
  'sha1'            = 'sha1'
  'sha1_upper'      = 'SHA1'
  'sha1_hyphen'     = 'SHA-1'
  'sha256'          = 'sha256'
  'sha256_upper'    = 'SHA256'
  'sha256_hyphen'   = 'SHA-256'
  'hash_lower'      = 'hash'
  'hash_cap'        = 'Hash'
  'hash_upper'      = 'HASH'
}

$all = New-Object System.Collections.Generic.List[object]
$encStats = New-Object System.Collections.Generic.List[object]
$fileCount = 0

foreach ($m in $mods) {
  $files = Get-ChildItem -Recurse -File -Path (Join-Path $root $m) -Include *.java, *.scala -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $exclude }
  foreach ($f in $files) {
    $fileCount++
    $rel = $f.FullName.Substring($root.Length + 1)
    $side = if ($rel -match '\\src\\test\\') { 'test' } else { 'main' }
    $mod = ($rel -split '\\')[0]
    # 严格 UTF-8 解码；失败则退化为系统默认编码（记录该退化，保证可复跑/可追溯）
    $enc = 'utf-8'
    try {
      $strict = New-Object System.Text.UTF8Encoding($false, $true)
      $lines = [System.IO.File]::ReadAllLines($f.FullName, $strict)
    } catch {
      $enc = 'default-fallback'
      $lines = [System.IO.File]::ReadAllLines($f.FullName)
    }
    if ($enc -ne 'utf-8') { $encStats.Add([pscustomobject]@{ path = $rel; encoding = $enc }) }
    for ($i = 0; $i -lt $lines.Count; $i++) {
      $line = $lines[$i]
      foreach ($p in $patterns.Keys) {
        if ($line -match $patterns[$p]) {
          $byteOff = [System.Text.Encoding]::UTF8.GetByteCount($line.Substring(0, $line.IndexOf($matches[0])))
          $all.Add([pscustomobject]@{
              side    = $side
              module  = $mod
              path    = $rel
              line    = $i + 1
              byteoff = $byteOff
              pattern = $p
              text    = $line.Trim()
            })
        }
      }
    }
  }
}

$hitLines = New-Object System.Collections.Generic.List[string]
foreach ($h in ($all | Sort-Object side, module, path, line, pattern)) {
  $hitLines.Add(("{0}`t{1}`t{2}:{3}:{4}`t{5}`t{6}" -f $h.side, $h.module, $h.path, $h.line, $h.byteoff, $h.pattern, $h.text))
}
$hitLines | Set-Content -Encoding utf8 (Join-Path $outDir 'grep-surrogate-key-hits.tsv')

$summaryRows = New-Object System.Collections.Generic.List[object]
foreach ($g in ($all | Group-Object side, module, pattern)) {
  $p = $g.Group[0]
  $summaryRows.Add([pscustomobject]@{
      side = $p.side; module = $p.module; pattern = $p.pattern
      hits = $g.Count; files = @($g.Group.path | Sort-Object -Unique).Count
    })
}
$summaryRows = $summaryRows | Sort-Object side, module, pattern
$summaryLines = New-Object System.Collections.Generic.List[string]
foreach ($r in $summaryRows) {
  $summaryLines.Add(("{0}`t{1}`t{2}`t{3}`t{4}" -f $r.side, $r.module, $r.pattern, $r.hits, $r.files))
}
$summaryLines | Set-Content -Encoding utf8 (Join-Path $outDir 'grep-surrogate-key-summary.tsv')

$perFileRows = New-Object System.Collections.Generic.List[object]
foreach ($g in ($all | Group-Object side, module, path)) {
  $p = $g.Group[0]
  $perFileRows.Add([pscustomobject]@{
      side = $p.side; module = $p.module; path = $p.path
      hits = $g.Count
      patterns = (($g.Group.pattern | Sort-Object -Unique) -join ',')
    })
}
$perFileRows = $perFileRows | Sort-Object side, module, hits
$perFileLines = New-Object System.Collections.Generic.List[string]
foreach ($r in $perFileRows) {
  $perFileLines.Add(("{0}`t{1}`t{2}`t{3}`t{4}" -f $r.side, $r.module, $r.hits, $r.patterns, $r.path))
}
$perFileLines | Set-Content -Encoding utf8 (Join-Path $outDir 'grep-surrogate-key-per-file.tsv')

Write-Output "files_scanned=$fileCount"
Write-Output "total_hits=$($all.Count)"
Write-Output "non_utf8_files=$($encStats.Count)"
$encStats | Format-Table -AutoSize
Write-Output '--- per side/module totals ---'
$all | Group-Object side, module | Select-Object Name, Count | Format-Table -AutoSize
