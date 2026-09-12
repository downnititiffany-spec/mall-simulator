# draft-vectors.ps1 -- P2-03 只读取证：为"向量集草案"计算 material 与 SHA-256（草案，不是实现，也不是契约）
# 只做算术，不读写任何源文件；输出 raw/draft-vectors.tsv
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$outDir = $PSScriptRoot

function Get-Sha256Hex([string]$text) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    return ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
  } finally { $sha.Dispose() }
}

$cases = @(
  @('V01', 'UUID',              'mock-mall', 'user',    '89a6db2c-9ecb-4be0-a24b-9f697da00686', '真实 landing 取值'),
  @('V02', '雪花 19 位',        'mock-mall', 'user',    '2098607948334395394',                  '真实 landing 取值'),
  @('V03', '纯数字',            'mock-mall', 'user',    '123',                                  'IdCodecSpec 已固化'),
  @('V04', '前缀数字',          'mock-mall', 'user',    'U000065',                              'IdCodec 契约文档书写形'),
  @('V05', '纯数字(目录商品)',  'mock-mall', 'product', '1001',                                 '真实 landing + 黄金集'),
  @('V06', '前缀数字(订单)',    'mock-mall', 'order',   'O00000001',                            '契约文档书写形'),
  @('V07', '前缀小写',          'mock-mall', 'user',    'u000065',                              '与 V04 必须同值'),
  @('V08', '首尾空格',          'mock-mall', 'user',    '  U000065  ',                          '与 V04 必须同值'),
  @('V09', '非 ASCII',          'mock-mall', 'user',    '用户一',                                'UTF-8 编码路径'),
  @('V10', '超长 256',          'mock-mall', 'user',    ('A' * 256),                            '长度上限'),
  @('V11', '非 ASCII 带空格',   'mock-mall', 'product', ' 商品-壹 ',                             '组合用例'),
  @('V12', 'UUID 大写',         'mock-mall', 'user',    '89A6DB2C-9ECB-4BE0-A24B-9F697DA00686', '与 V01 必须同值'),
  @('V13', '空串',              'mock-mall', 'user',    '',                                     '不生成键 → DQ/reject'),
  @('V14', '仅空白',            'mock-mall', 'user',    '   ',                                  '不生成键 → DQ/reject'),
  @('V15', 'null',              'mock-mall', 'user',    $null,                                  '不生成键 → DQ/reject'),
  @('V16', '同 raw 不同 source','other-src', 'user',    'U000065',                              '与 V04 必须不同值'),
  @('V17', '同 raw 不同类型',   'mock-mall', 'product', 'U000065',                              '与 V04 必须不同值'),
  @('V18', '数字字符串 vs 数值','mock-mall', 'user',    '0007',                                 '前导零口径'),
  @('V19', '可解析为负数',      'mock-mall', 'user',    '-1',                                   '不得与 unknown 哨兵 -1 混淆'),
  @('V20', '含分隔符',          'mock-mall', 'payment', 'P-1001',                               '黄金集真实取值'),
  @('V21', '多段分隔符',        'mock-mall', 'refund',  'R-1003-A',                             '黄金集真实取值'),
  @('V22', '中文+冒号',         'mock-mall', 'user',    'user:U000001',                         'MallApiDispatchSink 规范 ID 形')
)

$rows = New-Object System.Collections.Generic.List[object]
foreach ($c in $cases) {
  $id, $cat, $src, $ent, $raw, $note = $c
  $norm = if ($null -eq $raw) { $null } else { $raw.Trim().ToUpperInvariant() }
  $empty = ($null -eq $raw) -or ($norm -eq '')
  $mat = if ($empty) { '(不生成)' } else { "$src|$ent|$norm" }
  $dig = if ($empty) { '(不生成)' } else { Get-Sha256Hex $mat }
  $head8hex = if ($empty) { '(不生成)' } else { $dig.Substring(0, 16) }
  $rows.Add([pscustomobject]@{
      id = $id; category = $cat; source_code = $src; entity_type = $ent
      raw_id = if ($null -eq $raw) { '(null)' } else { $raw }
      normalized = if ($null -eq $norm) { '(null)' } else { $norm }
      material = $mat; sha256 = $dig; head8hex = $head8hex; note = $note
    })
}
$rows | ForEach-Object { "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}`t{8}`t{9}" -f `
    $_.id, $_.category, $_.source_code, $_.entity_type, $_.raw_id, $_.normalized, $_.material, $_.sha256, $_.head8hex, $_.note } |
  Set-Content -Encoding utf8 (Join-Path $outDir 'draft-vectors.tsv')

Write-Output "vectors=$($rows.Count)"
Write-Output '--- 必须同值/不同值的机械自检（只比字符串，不比"代理键"实现）---'
$by = @{}
foreach ($r in $rows) { $by[$r.id] = $r }
function Compare-Pair($a, $b, $expect) {
  $same = $by[$a].material -eq $by[$b].material
  $ok = if ($expect -eq 'SAME') { $same } else { -not $same }
  "{0} vs {1}: material_same={2} 期望={3} => {4}" -f $a, $b, $same, $expect, $(if ($ok) { 'PASS' } else { 'FAIL' })
}
Compare-Pair 'V04' 'V07' 'SAME'
Compare-Pair 'V04' 'V08' 'SAME'
Compare-Pair 'V01' 'V12' 'SAME'
Compare-Pair 'V04' 'V16' 'DIFF'
Compare-Pair 'V04' 'V17' 'DIFF'
Write-Output '--- 空值类必须不生成 ---'
foreach ($id in @('V13', 'V14', 'V15')) { "{0}: material={1}" -f $id, $by[$id].material }
Write-Output '--- 抽样 sha256 前 16 hex ---'
$rows | Select-Object id, material, head8hex | Format-Table -AutoSize
