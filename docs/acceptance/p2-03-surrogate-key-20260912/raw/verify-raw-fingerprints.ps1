# verify-raw-fingerprints.ps1 -- 只读自检：把 README §2.2/§2.3/§2.4 里逐字写下的
# (字节, 行, sha256) 三元组与磁盘现状比对。只需 -File，无参数。
# 判据：三条全等 => PASS；任一不等 => FAIL 并打印差异（不修改任何文件）。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $PSScriptRoot          # docs\acceptance\p2-03-surrogate-key-20260912
$raw = $PSScriptRoot

# README 中逐字登记的三元组（字节 / 行 / sha256）；key = raw 下的相对文件名
$expected = @(
  # --- §2.2 自写脚本 ---
  @{ f = 'scan-surrogate-key.ps1';                       b = 5222;  l = 128; h = '9370bd128016201f0eb33a1069d7163f8295c556a18d1cd1be36c564c8dcd299' },
  @{ f = 'analyze-golden-ids.ps1';                       b = 6577;  l = 131; h = '5a66c8dc2c24cbad7bd039e29168690acfdcd439542ebea50b2b974454c0a1b3' },
  @{ f = 'analyze-landing-ids.ps1';                      b = 4045;  l = 82;  h = '19502ffc1f620b6be0e704bce1e2cd63b38baf326539999db32b1b4519af1fd4' },
  @{ f = 'hash-key-shapes.ps1';                          b = 3079;  l = 58;  h = 'c2acbefd9b997124e351b0c5cc18f5ec70d9f5f3693575b14d1ae0c5be73368e' },
  @{ f = 'draft-vectors.ps1';                            b = 5335;  l = 76;  h = '9ae900b938c2441785424be4df6c2dcbb974c820cb96a9d730715489e6ffb6b2' },
  @{ f = 'fingerprint-cited-files.ps1';                  b = 6445;  l = 96;  h = '82d41b681bc37cc58cfcd046633852dc8ffba316b05c3124fad235c759a8b842' },
  # --- §2.3 脚本产出 ---
  @{ f = 'source-files-inventory.txt';                   b = 53873; l = 447; h = '72018b403f1164535437ad7d44dd3ba6a8a0046bb7a62e9ed1db9df421e7019a' },
  @{ f = 'grep-surrogate-key-hits.tsv';                  b = 340420;l = 1732;h = 'e199cb26493808a2c06e4b0044df50bdd4405b20227acbf49174463b15565586' },
  @{ f = 'grep-surrogate-key-summary.tsv';               b = 2548;  l = 64;  h = '51124d1a7b283a859ec84de11dd4142e6c2702d83f7c75d4b26fd788b49e6e26' },
  @{ f = 'grep-surrogate-key-per-file.tsv';              b = 20042; l = 124; h = 'e5657d709badbd565fc604ad620c21472dd14037b4753177eb2ce255ae51a9e2' },
  @{ f = 'scan-surrogate-key-console.txt';               b = 470;   l = 16;  h = '6f81608bc3822e3b1564b6e3662131dd1949077b556a3b7a8c0bef7f59d41daa' },
  @{ f = 'golden-id-shapes.tsv';                         b = 1331;  l = 9;   h = '5a173fb9ed1b79d4951b71b1f0696f649e3328bbfee69471eea6702e80b89662' },
  @{ f = 'golden-id-samples.tsv';                        b = 5317;  l = 188; h = 'af33e40d8eb5079f127ddd42cb34f5359891d6cfa2ecd64ac19b957a57fc2ed3' },
  @{ f = 'golden-line-classes.tsv';                      b = 709;   l = 55;  h = '646e838ece539241acea0684c3232533df3c38ad79993d84164d6d26a73d5181' },
  @{ f = 'analyze-golden-ids-console.txt';               b = 2448;  l = 57;  h = '0221fdcd69855f9793b38a8b4ad23a928b492bc9c80ff6675350ae60971cbc09' },
  @{ f = 'landing-id-shapes.tsv';                        b = 803;   l = 9;   h = '508f6d30b3ae0e690b807f325f40636a2254307e212113da32f8da39ff97d987' },
  @{ f = 'analyze-landing-ids-console.txt';              b = 1649;  l = 17;  h = 'e8be6ec3b094a10f72613b15cdfc95fcc8021225cae435aaa90f625f4848586b' },
  @{ f = 'hash-key-vectors.tsv';                         b = 2051;  l = 13;  h = 'f3f8eeaca69add0b149d9494e3728fc41ad10a399fee746c3d1f8b7ffa43a0a0' },
  @{ f = 'hash-key-shapes-console.txt';                  b = 2006;  l = 21;  h = 'ef1cb4d3e6715737cdaea0476e3e05dc29bac9d09fee188d740fcffeb6c5dfca' },
  @{ f = 'cited-file-fingerprints.tsv';                  b = 9909;  l = 56;  h = '0c044d422f7b9a73e48d746c1bcad4dde81e8b995df8359accfe1803df19e00b' },
  @{ f = 'cited-file-fingerprints-console.txt';          b = 744;   l = 11;  h = '1ecd5d70adc730e597d0a47e70db013b8996be0575d7cda59e4eba95e8ab7df6' },
  @{ f = 'draft-vectors.tsv';                            b = 4682;  l = 22;  h = '3aa11845cf70c8b5ef8cfd756a0ed1ee2763ac7ba7b83da19093d32c65928567' },
  @{ f = 'draft-vectors-console.txt';                    b = 3334;  l = 38;  h = '87b381d76efd93c622e40648e5e0edc7f353073ba713a90ab5f97e7ebe7a97b3' },
  # --- §2.4 窗口快照与指纹 ---
  @{ f = 'git-head-early.txt';                           b = 42;    l = 1;   h = '92640474375dfecdfb349bdaa7b99750573954715f1831600b2603f9f14ca685' },
  @{ f = 'git-head-late.txt';                            b = 42;    l = 1;   h = '92640474375dfecdfb349bdaa7b99750573954715f1831600b2603f9f14ca685' },
  @{ f = 'git-head-detail-early.txt';                    b = 257;   l = 3;   h = '1d8d092c712e145e2596ae531b756fe8bfdee6f7ba732240a8b538115bbab797' },
  @{ f = 'git-head-detail-late.txt';                     b = 257;   l = 3;   h = '1d8d092c712e145e2596ae531b756fe8bfdee6f7ba732240a8b538115bbab797' },
  @{ f = 'git-status-early.txt';                         b = 1320;  l = 20;  h = 'd9ace97fb562d23cbd9b14ab9377fbde9897dc6746888ef705d80b629ec758f1' },
  @{ f = 'git-status-late.txt';                          b = 1418;  l = 19;  h = 'a98e2dd293fa714a2fcac400e71977e5dfca9f88c2c5ead94cffcfb2a5e59b6e' },
  @{ f = 'window-timestamp-early.txt';                   b = 32;    l = 1;   h = '5fdb1a76ba011354652f38dc536b9506e95287a63f00145ce7e204c2351e6039' },
  @{ f = 'window-timestamp-late.txt';                    b = 54;    l = 1;   h = '5654abd301eeb118ee023125c8d4c08b549e68b5550561e6ab27ee4c9a58c945' },
  @{ f = 'git-status-postwindow.txt';                    b = 2115;  l = 29;  h = '61b87cc02031c6c5b6050c4adee4ff453ac4dc9e11e3eb74237ce54a0162d0ee' },
  @{ f = 'postwindow-timestamp.txt';                     b = 32;    l = 1;   h = 'f15cae79a8ea8f81957b48ed9d667777187371f323112723a59fc05c6a936f77' },
  @{ f = 'git-head-postwindow.txt';                      b = 42;    l = 1;   h = '7c3c44bf1e961a85fcee94bf7f5d6f63f5d9264c9a229d1e2af59e9b10136101' },
  @{ f = 'git-log-timeline.txt';                         b = 2257;  l = 21;  h = '3df4f1dda2c371230d632fd4a927ebafa070a2fd31b459c2912da9c0f83e74eb' }
)

$pass = 0; $fail = 0; $missing = 0
foreach ($e in $expected) {
  $p = Join-Path $raw $e.f
  if (-not (Test-Path $p)) { "MISSING`t$($e.f)"; $missing++; continue }
  $i = Get-Item $p
  $lines = (Get-Content $p | Measure-Object).Count
  $hash = (Get-FileHash -Algorithm SHA256 $p).Hash.ToLower()
  $okB = ($i.Length -eq $e.b); $okL = ($lines -eq $e.l); $okH = ($hash -eq $e.h)
  if ($okB -and $okL -and $okH) { $pass++ }
  else {
    $fail++
    "FAIL`t$($e.f)`t bytes:$($i.Length)/$($e.b) lines:$lines/$($e.l) sha256:$hash/$($e.h)"
  }
}
"registered=$($expected.Count) pass=$pass fail=$fail missing=$missing"
if ($fail -eq 0 -and $missing -eq 0) { "VERDICT=PASS（README §2.2/§2.3/§2.4 逐字登记的 $($expected.Count) 个制品三元组与磁盘现状全部一致）" }
else { 'VERDICT=FAIL（见上面的 FAIL/MISSING 行）' }
