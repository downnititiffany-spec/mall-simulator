# hash-key-shapes.ps1 -- P2-03 只读取证：对**真实**业务键取值算 SHA-256，观察字节/位分布与"同一真实实体两种 ID 形态"的哈希差
# 只读输入：landing/events/2026091211.jsonl（已在别处记录 sha256）
# 输出：raw/hash-key-vectors.tsv
# 注意：本脚本只做"取值 → 哈希"的观测，不实现任何代理键算法，不作为 P2-03 方案实现的证据。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = 'D:\Develop_code\GraduationProject'
$outDir = $PSScriptRoot
$f = Join-Path $root 'landing\events\2026091211.jsonl'

function Get-Sha256Hex([string]$text) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    return ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
  } finally { $sha.Dispose() }
}

# 固定样本：真实 landing 里出现过的同值不同形态
$samples = [ordered]@{
  'event_id(uuid,真实)'      = '89a6db2c-9ecb-4be0-a24b-9f697da00686'
  'user_id(雪花19,真实)'     = '2098607948334395394'
  'product_id(4位,目录)'     = '1001'
  'product_id(雪花19,真实)'  = '2096441909987262465'
  'order_id(雪花19,真实)'    = '2098607950855172098'
  'payment_id(雪花19,真实)'  = '2098607951186522113'
  'refund_id(雪花19,真实)'   = '2098607951572398081'
  '黄金集 payment_id(P-1001)' = 'P-1001'
  '黄金集 refund_id(R-1003-A)' = 'R-1003-A'
  '黄金集 event_id(golden-evt-001)' = 'golden-evt-001'
  '契约文档书写的 U000065'   = 'U000065'
  '契约文档书写的 P00030'    = 'P00030'
  '契约文档书写的 O00000001' = 'O00000001'
}
$rows = New-Object System.Collections.Generic.List[object]
foreach ($k in $samples.Keys) {
  $v = $samples[$k]
  $h = Get-Sha256Hex $v
  $first8hex = $h.Substring(0, 16)
  # 模拟"取前 8 字节、清符号位、0→1"这一设计草案口径，仅用于展示分布，不代表已实现
  $be = [System.Numerics.BigInteger]::Parse('0' + $h.Substring(0, 16), [System.Globalization.NumberStyles]::HexNumber)
  $be = $be -band [System.Numerics.BigInteger]::Parse('7FFFFFFFFFFFFFFF', [System.Globalization.NumberStyles]::HexNumber)
  $rows.Add([pscustomobject]@{
      label = $k; value = $v; utf8_len = [System.Text.Encoding]::UTF8.GetByteCount($v)
      sha256 = $h; head16hex = $first8hex; head8_prefix = $h.Substring(0, 8)
      be_cleared = $be.ToString()
    })
}
$rows | ForEach-Object { "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f $_.label, $_.value, $_.utf8_len, $_.sha256, $_.head16hex, $_.head8_prefix, $_.be_cleared } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'hash-key-vectors.tsv')

Write-Output '--- 样本哈希（前 16 hex 与高 64 位十进制）---'
$rows | Select-Object label, value, sha256 | Format-Table -AutoSize
Write-Output '--- 同一真值域内的哈希前缀是否碰撞（抽样自检，非全量）---'
$prefixes = @($rows | Group-Object head8_prefix | Where-Object { $_.Count -gt 1 })
"colliding_prefix_groups_in_sample=" + $prefixes.Count
Write-Output "sample_n=$($rows.Count)"
