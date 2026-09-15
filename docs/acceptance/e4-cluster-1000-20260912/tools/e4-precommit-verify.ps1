# e4-precommit-verify.ps1 —— E4 §10 提交前自检（陷阱 #27 的正向应用：读数脚本必须先做「应然值」对照）
# 用法: pwsh -NoProfile -File .verify\e4-precommit-verify.ps1
# 只读脚本。把文档里的每个数字压回 raw/ 与 raw-fix/ 的原始捕获逐条核对；任何一项 FAIL 都不得提交。
# v2（2026-09-12）：修正 v1 的四处**断言**错误（D 的字段名、E 的文件集、J 的残留口径、K 的路径引用），
#                  并把「run-2 只重测了哪些」写成断言，防止文档再次把 run-1 读数写成本轮实测。

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$e4    = Join-Path $root 'docs\acceptance\e4-cluster-1000-20260912'
$board = Join-Path $root 'docs\status-history\项目实施进度与任务看板 V2.2.md'

$results = New-Object System.Collections.ArrayList
function Add-Result([string]$name, [bool]$ok, [string]$detail) {
  [void]$results.Add([pscustomobject]@{ Name = $name; OK = $ok; Detail = $detail })
}
function Read-Text([string]$p) {
  if (-not (Test-Path -LiteralPath $p)) { throw "文件不存在: $p" }
  return [IO.File]::ReadAllText((Resolve-Path -LiteralPath $p).Path)
}
function Count-Regex([string]$text, [string]$pattern) { return ([regex]::Matches($text, $pattern)).Count }
function File-Sha([string]$p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash }

# ── A. 上集群的 jar 指纹（与 §10.2 一致）────────────────────────────────
$jar = Join-Path $root 'spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar'
if (Test-Path -LiteralPath $jar) {
  $len = (Get-Item -LiteralPath $jar).Length
  $sha = File-Sha $jar
  Add-Result 'A1 jar 字节数 = 285256' ($len -eq 285256) "实际 $len"
  Add-Result 'A2 jar sha256 = 4F297355…AA3A' ($sha -eq '4F29735547D6DDEFDE4BF25C8E3157BF316D8F9EE6DBA10436E611A02421AA3A') "实际 $sha"
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::OpenRead($jar)
  try {
    $cls = @($zip.Entries | Where-Object { $_.FullName -like '*.class' })
    Add-Result 'A3 class 数 = 105' ($cls.Count -eq 105) "实际 $($cls.Count)"
    $hitIsBlank = 0; $hitTrim = 0
    foreach ($e in $cls) {
      $ms = New-Object IO.MemoryStream
      $e.Open().CopyTo($ms)
      $s = [Text.Encoding]::Latin1.GetString($ms.ToArray())
      if ($s.Contains('isBlank')) { $hitIsBlank++ }
      if ($s.Contains('trim'))    { $hitTrim++ }
      $ms.Dispose()
    }
    Add-Result 'A4 jar 内 `isBlank` 命中 = 0' ($hitIsBlank -eq 0) "实际 $hitIsBlank"
    Add-Result 'A5 jar 内 `trim` 正对照 > 0'  ($hitTrim -gt 0)    "实际 $hitTrim"
  } finally { $zip.Dispose() }
} else { Add-Result 'A. jar 存在' $false "缺失 $jar" }

# ── B. 修复补丁指纹 ──────────────────────────────────────────────────────
$patch = Join-Path $root '.verify\f80-jdk8fix.patch'
if (Test-Path -LiteralPath $patch) {
  $pl = (Get-Item -LiteralPath $patch).Length; $ps = File-Sha $patch
  Add-Result 'B1 patch 字节数 = 5131' ($pl -eq 5131) "实际 $pl"
  Add-Result 'B2 patch sha256 = F77B9557…8497' ($ps -eq 'F77B9557A7A8374D2827F69BE2778C9B0D348310FEC4C714528DACD7D8EF8497') "实际 $ps"
  $tc = Join-Path $e4 'tools\f80-jdk8fix.patch'
  Add-Result 'B3 tools/ 内补丁副本同指纹' ((File-Sha $tc) -eq $ps) "tools 副本 $((File-Sha $tc).Substring(0,16))…"
} else { Add-Result 'B. patch 存在' $false "缺失 $patch" }

# ── C. 四处源码修复就位（新写法在、旧 API 清零）──────────────────────────
$srcChecks = @(
  @{ F = 'spark-jobs\pom.xml';                                                              New = '<release>8</release>';               Old = '<release>17</release>' },
  @{ F = 'spark-jobs\src\main\scala\com\graduation\analytics\job\MetricExportJob.scala';     New = 'trim.isEmpty';                       Old = 'isBlank' },
  @{ F = 'spark-jobs\src\test\scala\com\graduation\analytics\P2TestSupport.scala';           New = 'ManagementFactory.getRuntimeMXBean'; Old = 'ProcessHandle.current().pid()' },
  @{ F = 'spark-jobs\src\test\scala\com\graduation\analytics\WarehouseNamespaceSpec.scala';  New = 'Files.readAllBytes';                 Old = 'Files.readString' }
)
foreach ($c in $srcChecks) {
  $t = Read-Text (Join-Path $root $c.F); $leaf = Split-Path $c.F -Leaf
  Add-Result "C 新写法在 [$leaf]"   ($t.Contains($c.New))       "找 `"$($c.New)`""
  Add-Result "C 旧写法清零 [$leaf]" (-not $t.Contains($c.Old))  "旧写法命中 $(Count-Regex $t ([regex]::Escape($c.Old)))"
}

# ── D. 集群侧独立证据：yarn application -status 10/10（字段名以捕获原文为准）──
$app = Read-Text (Join-Path $e4 'raw-fix\e4-appstatus-fix.txt')
Add-Result 'D1 appstatus State=FINISHED ×10'   ((Count-Regex $app 'State=FINISHED') -eq 10)  "实际 $(Count-Regex $app 'State=FINISHED')"
Add-Result 'D2 appstatus Final=SUCCEEDED ×10'  ((Count-Regex $app 'Final=SUCCEEDED') -eq 10) "实际 $(Count-Regex $app 'Final=SUCCEEDED')"
Add-Result 'D3 appstatus 10 行 appId'          ((Count-Regex $app 'application_\d+_\d+') -eq 10) "实际 $(Count-Regex $app 'application_\d+_\d+')"

# ── E. 仓库侧独立回读：**只**断言 run-2 真正重测过的项（边界见 §10.3）────
$rbInv  = Read-Text (Join-Path $e4 'raw-fix\e4-readback-fix.txt')     # 表清单（inventory）
$rb2    = Read-Text (Join-Path $e4 'raw-fix\e4-readback2-fix.txt')    # run-2 计数
$mxpFix = Read-Text (Join-Path $e4 'raw-fix\e4-mxp-readback-out.txt')
$bid    = Read-Text (Join-Path $e4 'raw-fix\e4-batchid-check-fix.txt')
$readme = Read-Text (Join-Path $e4 'README.md')
$eSigs = @(
  @{ N = 'DWD_order_detail 134';       P = 'DWD_order_detail\s+134' },
  @{ N = 'DWD_user_behavior 373';      P = 'DWD_user_behavior\s+373' },
  @{ N = 'DWD_reject 0';               P = 'DWD_reject\s+0' },
  @{ N = 'DWD_TOTAL 507';              P = 'DWD_TOTAL\s+507' },
  @{ N = 'DWS_trade_day 1';            P = 'DWS_trade_day\s+1' },
  @{ N = 'DWS_user_behavior 83';       P = 'DWS_user_behavior\s+83' },
  @{ N = 'DWS_user_trade_period 37';   P = 'DWS_user_trade_period\s+37' }
)
foreach ($s in $eSigs) { Add-Result "E run-2 重测项 [$($s.N)]" ((Count-Regex $rb2 $s.P) -eq 1) "命中 $(Count-Regex $rb2 $s.P)" }
Add-Result 'E run-2 ADS 独立合计 138'        ((Count-Regex $mxpFix 'FORMAL_ADS_TOTAL\s+138') -eq 1) "命中 $(Count-Regex $mxpFix 'FORMAL_ADS_TOTAL\s+138')"
Add-Result 'E run-2 ODS 覆盖性 373/[0]/mock-mall' (($bid -match '373') -and ($bid -match '\[0\]') -and ($bid -match 'mock-mall')) '三项俱在'
Add-Result 'E 表清单含 __staging 配对'        ((Count-Regex $rbInv '__staging') -ge 8) "命中 $(Count-Regex $rbInv '__staging')"
Add-Result 'E 文档已划清「未重测」边界'        ((Count-Regex $readme 'run-2 \*\*没有重测\*\*|run-2 没有重测|未重测') -ge 1) "命中 $(Count-Regex $readme '未重测')"

# ── F. 导出侧三方对账 ───────────────────────────────────────────────────
$mxpBad = Read-Text (Join-Path $e4 'raw-fix\e4-mxp-readback-out-FLAWED-capture.txt')
Add-Result 'F1 修版捕获含 138（≥2 处）'      ((Count-Regex $mxpFix '138') -ge 2) "138 出现 $(Count-Regex $mxpFix '138') 次"
Add-Result 'F2 修版捕获含 8 张表名'          ((Count-Regex $mxpFix 'ads_operation_overview|ads_sale_trend|ads_behavior_funnel|ads_active_trend|ads_hot_product|ads_product_conversion|ads_user_profile|ads_data_quality') -ge 8) "命中 $(Count-Regex $mxpFix 'ads_[a-z_]+')"
Add-Result 'F3 有缺陷首版已保留（含 156）'    ($mxpBad.Contains('156')) "含 156 = $($mxpBad.Contains('156'))"
Add-Result 'F4 两版内容不同（未覆盖留痕）'    ($mxpFix -ne $mxpBad) '两文件字节不同'

# ── G. 分区指针逐条实测 8/8 ─────────────────────────────────────────────
$loc = Read-Text (Join-Path $e4 'raw-fix\e4-ads-location-check.txt')
$nStaging = Count-Regex $loc '__staging/snapshot_id=S20260901E4'
Add-Result 'G1 LOCATION 指向 __staging/S20260901E4 ×8' ($nStaging -eq 8) "实际 $nStaging"

# ── H. 10 作业提交退出码（chain-summary）────────────────────────────────
$chain = Read-Text (Join-Path $e4 'raw-fix\chain-summary.jsonl')
$lines = @($chain -split "`n" | Where-Object { $_.Trim() -ne '' })
Add-Result 'H1 chain-summary 行数 = 10' ($lines.Count -eq 10) "实际 $($lines.Count)"
Add-Result 'H2 chain-summary exit:0 = 10' ((Count-Regex $chain '"exit"\s*:\s*0') -eq 10) "实际 $(Count-Regex $chain '"exit"\s*:\s*0')"

# ── I. raw/f80-fix/MANIFEST.md 指纹清单自洽（掩码后必须仍成立）────────────
$man    = Read-Text (Join-Path $e4 'raw\f80-fix\MANIFEST.md')
$manDir = Join-Path $e4 'raw\f80-fix'
$manRows = [regex]::Matches($man, '\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|\s*`([0-9A-Fa-f]{64})`\s*\|')
$manBad = @()
foreach ($m in $manRows) {
  $f = Join-Path $manDir $m.Groups[1].Value
  if (-not (Test-Path -LiteralPath $f)) { $manBad += "$($m.Groups[1].Value):缺失"; continue }
  $al = (Get-Item -LiteralPath $f).Length
  if ($al -ne [int]$m.Groups[2].Value) { $manBad += "$($m.Groups[1].Value):字节 $al≠$($m.Groups[2].Value)"; continue }
  if ((File-Sha $f) -ne $m.Groups[3].Value.ToUpper()) { $manBad += "$($m.Groups[1].Value):sha 不符" }
}
Add-Result 'I1 MANIFEST 覆盖 12 个文件' ($manRows.Count -eq 12) "实际 $($manRows.Count)"
Add-Result 'I2 MANIFEST 全部条目指纹自洽' ($manBad.Count -eq 0) $(if ($manBad.Count -eq 0) { '12/12 一致' } else { $manBad -join '; ' })

# ── J. 脱敏复检：目录内 0；看板仅剩「已公开历史」那一处（allowlist）──────
$strict = '192\.168\.18\.10[0-9]'
$dirFiles = @(Get-ChildItem -LiteralPath $e4 -Recurse -File | ForEach-Object { $_.FullName })
$leftDir = 0; $place = 0; $derby = 0
foreach ($f in $dirFiles) {
  $t = [IO.File]::ReadAllText($f)
  $leftDir += Count-Regex $t $strict
  $place   += Count-Regex $t '192\.168\.18\.10x'
  $derby   += Count-Regex $t '(?<![\d.])10\.14\.2\.0'
}
$bt = Read-Text $board
$leftBoard = Count-Regex $bt $strict
$inPublishedLine = Count-Regex $bt '(?m)^\|\s*(\|\s*)?2026-09-12 15:46.*192\.168\.18\.10[0-9]'
Add-Result 'J1 证据目录内真地址残留 = 0' ($leftDir -eq 0) "实际 $leftDir"
Add-Result 'J2 占位符仍在 > 0'          ($place -gt 0)    "实际 $place"
Add-Result 'J3 Derby 误报备案可解释'     ($derby -gt 0)    "10.14.2.0 命中 $derby（版本号，非地址）"
Add-Result 'J4 看板真地址仅剩 1 处且在 15:46 已公开行' (($leftBoard -eq 1) -and ($inPublishedLine -eq 1)) "看板共 $leftBoard 处；落在 15:46 行 $inPublishedLine 处"

# ── K. 工作树只含预期改动 + 无崩溃残留 ─────────────────────────────────
$porcelain = & git -C $root -c core.quotepath=false status --porcelain
$stray = @($porcelain | Where-Object { $_ -match 'hs_err_pid|replay_pid|\.hprof' })
Add-Result 'K1 无 JVM 崩溃残留文件' ($stray.Count -eq 0) $(if ($stray.Count) { $stray -join '; ' } else { '无' })
$expected = @('docs/status-history/项目实施进度与任务看板 V2.2.md','spark-jobs/pom.xml',
  'spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala',
  'spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala',
  'docs/acceptance/e4-cluster-1000-20260912/')
$unexpected = @()
foreach ($l in $porcelain) {
  $p = $l.Substring(3).Trim('"')
  $hit = $false
  foreach ($e in $expected) { if ($p -eq $e -or $p -like "$e*") { $hit = $true } }
  if (-not $hit) { $unexpected += $p }
}
Add-Result 'K2 工作树无预期外改动' ($unexpected.Count -eq 0) $(if ($unexpected.Count) { $unexpected -join '; ' } else { "仅 $(@($porcelain).Count) 项预期改动" })

# ── L. 文档与工具落点 ───────────────────────────────────────────────────
$tools = @(Get-ChildItem -LiteralPath (Join-Path $e4 'tools') -File | ForEach-Object { $_.Name })
Add-Result 'L1 README 含 §10 追加补记' ($readme.Contains('# 10. 附录')) '标题存在'
Add-Result 'L2 README §1–§9 原文锚点仍在' ($readme.Contains('# 9.') -and $readme.Contains('# 1.')) '章节锚点在'
Add-Result 'L3 看板含本轮新行（F-80 闭环）' ((Count-Regex $bt 'F-80 已在真集群闭环修复') -eq 1) "命中 $(Count-Regex $bt 'F-80 已在真集群闭环修复')"
Add-Result 'L4 看板本轮新行含自检留痕 ⑮' ((Count-Regex $bt 'F-80 已在真集群闭环修复.*自检门禁') -eq 1) "命中 $(Count-Regex $bt 'F-80 已在真集群闭环修复.*自检门禁')"
$numstat = & git -C $root diff --numstat -- 'docs/status-history/项目实施进度与任务看板 V2.2.md'
$parts = ($numstat -split "`t")
Add-Result 'L5 看板 diff = 净增 1 行 / 删除 0' (($parts[0] -eq '1') -and ($parts[1] -eq '0')) "实际 $($parts[0]) $($parts[1])"
Add-Result 'L6 MASKING.md 已落' (Test-Path -LiteralPath (Join-Path $e4 'MASKING.md')) 'MASKING.md'
$needTools = @('e4-run-chain-fix.ps1','e4-verify-fix.ps1','e4-mxp-readback.ps1','e4-location-check.sql','jdk8-preflight-v2-utf8.ps1','f80-jdk8fix.patch','e4-precommit-verify.ps1')
$missTools = @($needTools | Where-Object { $tools -notcontains $_ })
Add-Result 'L7 tools/ 已归档 7 个工具' ($missTools.Count -eq 0) $(if ($missTools.Count) { '缺: ' + ($missTools -join ', ') } else { ($tools.Count.ToString()) + ' 个文件在册' })
# L8 是防漂移用的自指断言：看板 ⑮ 里自述的断言数必须等于脚本**本次实际**做出的断言数（含 L8 自身）
$claim = [regex]::Match($bt, '\*\*(\d+) 项断言\*\*')
$claimedN = if ($claim.Success) { [int]$claim.Groups[1].Value } else { -1 }
Add-Result 'L8 看板自述断言数 = 脚本实测数' ($claimedN -eq ($results.Count + 1)) "看板 $claimedN / 脚本将达 $($results.Count + 1)"

# ── 汇总 ────────────────────────────────────────────────────────────────
''
'================ E4 提交前自检结果（v2）================'
foreach ($r in $results) { '  [{0}] {1,-48} {2}' -f $(if ($r.OK) { 'PASS' } else { 'FAIL' }), $r.Name, $r.Detail }
$fail = @($results | Where-Object { -not $_.OK })
''
'  合计 {0} 项，PASS {1}，FAIL {2}' -f $results.Count, ($results.Count - $fail.Count), $fail.Count
if ($fail.Count -gt 0) { '  结论: 不得提交 —— 先修正 FAIL 项' ; exit 1 } else { '  结论: 全部 PASS，可以提交' ; exit 0 }
