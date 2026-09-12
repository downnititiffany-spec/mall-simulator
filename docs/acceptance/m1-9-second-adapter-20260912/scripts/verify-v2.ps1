# M1-9 ② 总控独立复核器 v2（只读；不修改任何文件）
#
# 用法：pwsh -File docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-v2.ps1 [-LaneCommit 25b0fe7] [-LogPath <全模块测试日志>]
#
# 为什么有 v2（v1 原样运行的记录见 .verify/m1-9-verify/verify-v1-asrun.txt：PASS=8 FAIL=5）：
#   v1 的三条判据被实测证伪为**判据自身缺陷**，不是交付缺陷；v2 修正判据，判据口径（要求什么）不变、只改「怎么测」：
#    * V1  v1 用 `git status --porcelain` 取变更面 ⇒ 受 **stat-dirty 幻影**影响：把内容与索引逐字节相同、
#          仅 mtime 变过的 `contract-specs/README.md` 计成"白名单外改动"（实测 `git diff --numstat` 为空、
#          sha256 = 07D2F02E… 与登记值相符）。v2 改为按**泳道提交区间**取内容级变更面（`git diff --name-only`）。
#    * V11 v1 要求 `GeneratorBeans` numstat = **+1/-0**。双适配器注册必须把 `List.of(...)` 的收尾行改一个逗号，
#          **+3/-1 才是最小编辑**（实测：1 条 import、1 条既有行改逗号、1 条新元素；delete 0 条逻辑）。
#          v2 断言"只有一条既有行被改写、且它与新增行同核（逐字比对）+ 无逻辑删除"。
#    * V12b v1 的正则取日志里**第一条** `Tests run:`（某个测试类的行，实测 = 10）⇒ 恒 <100 而恒 FAIL。
#          v2 取**无 `-- in` 后缀的合计行**（实测 = 111），并要求 `BUILD SUCCESS`。
#    * V10 v1 的字面正则在夹具的 Javadoc **对照表**上误报 3 处（`:29`/`:34`/`:35`，全是注释行）。
#          v2 只统计**非注释行**命中，并同时打印注释行命中数作为证据。
#    * V12a 拆两条：V12a1 = T1–T7 标识齐全；V12a2 = T8 的**实质**（泳道把 T8 落成 boundary 测试的第 5 个方法，
#    * V12a2 修正（同日）：T8 的权威文本是 docs/acceptance/m1-9-contract-first-20260912/README.md:149 —
#          「T8｜变更面守卫：新增一家商城只动"1 个新适配器文件 ＋ 登记处 1 行"｜硬约束 7」。
#          v2 首版把它误按「engine 无商城字面量」断言（那是硬约束 7 的另一半，不是 T8），因而误判 PASS；
#          已按权威文本改为断言"变更面守卫已落地为测试"，实测 0 命中 ⇒ FAIL。裁决见 README §5.4：本轮
#          「首家的写死尚未拆除」，而拆写死（引擎/SPI 去商城化）正是本轮主体内容 ⇒ T8 对本轮**结构性
#          不可满足**，其实质是"下一家商城"的回归守卫，转 P5-03 落地；本轮不声称 T8 通过。
#          未使用 `T8` 标识，故 v1 的"标识齐全"判 FAIL —— 这是**追溯标识缺失**，不是守卫缺失）。
#    * 新增 V0（工作区与提交内容一致才有资格被评审）、V13（无新增 @Disabled/assumeTrue）、V14（无凭据字面量）。
#
# 设计口径（与 v1 相同）：
#  * 只读——只做文件读取、正则匹配、git 查询；不写文件、不改工作区。
#  * 每条判据独立输出 PASS/FAIL 并打印判据原文与命中计数；文件缺失一律 FAIL（没有"跳过即通过"）。
param(
    [string]$LaneCommit = '25b0fe7',
    [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$gen = Join-Path $root 'synthetic-data-generator\src'
$main = Join-Path $gen 'main\java\com\graduation\generator'
$test = Join-Path $gen 'test\java\com\graduation\generator'
$enc = [Text.UTF8Encoding]::new($false)

$script:pass = 0
$script:fail = 0

function Check {
    param([string]$Id, [string]$Title, [bool]$Ok, [string]$Evidence)
    if ($Ok) { $script:pass++; $tag = 'PASS' } else { $script:fail++; $tag = 'FAIL' }
    Write-Host ("[{0}] {1}  {2}`n        证据: {3}" -f $tag, $Id, $Title, $Evidence)
}
function Read-IfExists {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) { return [IO.File]::ReadAllText($Path, $enc) }
    return $null
}
function Count-Matches {
    param([string]$Text, [string]$Pattern)
    if ($null -eq $Text) { return -1 }
    return ([regex]::Matches($Text, $Pattern)).Count
}
function Count-MatchesCode {
    # 只统计**非注释行**的命中（行首 trim 后以 * // /* 开头视为注释行）
    param([string]$Text, [string]$Pattern)
    if ($null -eq $Text) { return -1 }
    $n = 0
    foreach ($l in ($Text -split "`n")) {
        $t = $l.Trim()
        if ($t.StartsWith('*') -or $t.StartsWith('//') -or $t.StartsWith('/*')) { continue }
        $n += ([regex]::Matches($l, $Pattern)).Count
    }
    return $n
}

Write-Host "=== M1-9 ② 独立复核 v2（只读，判据已修正）  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
Write-Host "repo = $root"
Write-Host "审查对象 = 提交 $LaneCommit（内容级；不看 git status 的 stat 状态）"
Write-Host ''

# ── 变更面（内容级，供 V0/V1/V11/V13/V14 共用）────────────────────────────
$porcelain = & git -C $root diff --name-only "$LaneCommit^..$LaneCommit"
$changedAll = @($porcelain | Where-Object { $_ -ne '' })
$allowed = @(
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/TargetRoute.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/MallStatusVocabulary.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ExternalProduct.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/MallTargetAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ReferenceMallHttpAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallDispatchPlan.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiDispatchSink.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/OperationJournalEntry.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/config/GeneratorBeans.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/boundary/GeneratorBoundarySourcePolicyTest.java'
)

# ── V0 前置：工作区这 15 个文件必须与提交内容逐字节一致（并发写者守卫）──────
$treeDrift = @(& git -C $root diff --name-only $LaneCommit -- @allowed)
$treeDrift = @($treeDrift | Where-Object { $_ -ne '' })
Check 'V0' "工作区内容与提交 $LaneCommit 一致（否则本次评审结论无效）" ($treeDrift.Count -eq 0) `
    ("工作区漂移文件数=$($treeDrift.Count)（应 0）" + $(if ($treeDrift.Count) { '：' + ($treeDrift -join '、') } else { '' }))

# ── V1 变更面（按提交区间，内容级）────────────────────────────────────────
$docsChanged = @($changedAll | Where-Object { ($_ -replace '\\', '/') -like 'docs/*' })
$codeChanged = @($changedAll | Where-Object { ($_ -replace '\\', '/') -notlike 'docs/*' })
$outside = @($codeChanged | Where-Object { ($_ -replace '\\', '/') -notin $allowed })
Check 'V1' '泳道提交的代码侧变更面 = 白名单 15 个文件，且不含 docs/**' `
    ($outside.Count -eq 0 -and $docsChanged.Count -eq 0 -and $codeChanged.Count -eq 15) `
    ("提交区间 $LaneCommit^..$LaneCommit 共 $($changedAll.Count) 个文件（代码侧 $($codeChanged.Count)，应 15；docs 侧 $($docsChanged.Count)，应 0）；" + `
        "白名单外=" + $(if ($outside.Count) { ($outside -join '、') } else { '无' }))

# ── V2 路由契约类型 ────────────────────────────────────────────────────────
$targetRoute = Read-IfExists (Join-Path $main 'adapter\TargetRoute.java')
Check 'V2' 'adapter/TargetRoute.java 为 record(String method, String path) 且校验非空白' `
    ($null -ne $targetRoute -and $targetRoute -match 'record\s+TargetRoute\s*\(\s*String\s+method\s*,\s*String\s+path\s*\)' -and `
        $targetRoute -match 'isBlank' -and $targetRoute -match 'IllegalArgumentException') `
    ("文件存在={0}；record 签名命中={1}；isBlank 校验={2}" -f ($null -ne $targetRoute), `
        (Count-Matches $targetRoute 'record\s+TargetRoute\s*\(\s*String\s+method\s*,\s*String\s+path\s*\)'), (Count-Matches $targetRoute 'isBlank'))

# ── V3 接口新增 operationRoutes（默认空表，接口内不得有商城字面量） ────────
$spi = Read-IfExists (Join-Path $main 'adapter\MallTargetAdapter.java')
$spiRoute = Count-Matches $spi 'default\s+Map<String,\s*TargetRoute>\s+operationRoutes\s*\(\s*TargetConfig'
Check 'V3' 'MallTargetAdapter 新增 operationRoutes 默认方法（空表）且接口内无商城路由字面量' `
    ($spiRoute -eq 1 -and (Count-Matches $spi '"/api/|"/open/') -eq 0) `
    ("operationRoutes 默认方法命中=$spiRoute（应 1）；接口内商城路由字面量=$(Count-Matches $spi '"/api/|"/open/')（应 0）")

# ── V4 计划层删除 method/route 与参考商城字面量 ────────────────────────────
$plan = Read-IfExists (Join-Path $main 'engine\MallDispatchPlan.java')
$planLiterals = Count-Matches $plan '"/api/|"/open/'
$planComponents = Count-Matches $plan 'String\s+method|String\s+route'
Check 'V4' 'MallDispatchPlan 不再持有 method/route 分量与商城路由字面量' `
    ($null -ne $plan -and $planLiterals -eq 0 -and $planComponents -eq 0) `
    ("file 存在={0}；商城路由字面量={1}（应 0）；method/route 分量={2}（应 0）" -f ($null -ne $plan), $planLiterals, $planComponents)

# ── V5 engine 全目录无商城路由字面量（含正向对照） ─────────────────────────
$engineFiles = @(Get-ChildItem -LiteralPath (Join-Path $main 'engine') -Filter *.java -Recurse -File)
$engineHits = @()
foreach ($f in $engineFiles) {
    $n = (Count-Matches ([IO.File]::ReadAllText($f.FullName, $enc)) '"/api/|"/open/')
    if ($n -gt 0) { $engineHits += "$($f.Name):$n" }
}
$refAdapter = Read-IfExists (Join-Path $main 'adapter\ReferenceMallHttpAdapter.java')
$refHits = Count-Matches $refAdapter '"/api/|"/open/'
Check 'V5' 'engine/** 无商城路由字面量；正向对照：参考适配器内必须命中（证明守卫有检出能力）' `
    ($engineHits.Count -eq 0 -and $refHits -gt 0) `
    ("engine 文件数=$($engineFiles.Count) 命中文件=$($engineHits.Count)（应 0）$(if ($engineHits.Count) { '：' + ($engineHits -join '、') })；参考适配器命中=$refHits（应 >0）")

# ── V6 未声明路由时写明确占位，不回落参考商城字面量 ───────────────────────
$sink = Read-IfExists (Join-Path $main 'engine\MallApiDispatchSink.java')
$engine = Read-IfExists (Join-Path $main 'engine\MallApiGenerationEngine.java')
$placeholder = (Count-Matches $sink '（适配器未声明路由）') + (Count-Matches $engine '（适配器未声明路由）')
Check 'V6' '路由取自适配器（sink/engine 出现 operationRoutes）且未声明时写占位串' `
    (((Count-Matches $sink 'operationRoutes') + (Count-Matches $engine 'operationRoutes')) -ge 1 -and $placeholder -ge 1) `
    ("operationRoutes 命中 sink=$((Count-Matches $sink 'operationRoutes')) engine=$((Count-Matches $engine 'operationRoutes'))；占位串命中=$placeholder")

# ── V7 第二家商城适配器：类型名/自有路由/能力缺口 ──────────────────────────
$second = Read-IfExists (Join-Path $main 'adapter\SecondMallHttpAdapter.java')
Check 'V7' 'SecondMallHttpAdapter：adapterType=SECOND_MALL_HTTP、自有 /open/v2 路由、声明 admin/refund 缺失' `
    ($null -ne $second -and (Count-Matches $second 'SECOND_MALL_HTTP') -ge 1 -and (Count-Matches $second '"/open/v2') -ge 1 -and `
        (Count-Matches $second 'MallCapability\.ADMIN') -ge 1 -and (Count-Matches $second 'MallCapability\.REFUND') -ge 1) `
    ("存在={0}；SECOND_MALL_HTTP={1}；/open/v2={2}；ADMIN 提及={3}；REFUND 提及={4}" -f ($null -ne $second), `
        (Count-Matches $second 'SECOND_MALL_HTTP'), (Count-Matches $second '"/open/v2'), (Count-Matches $second 'MallCapability\.ADMIN'), (Count-Matches $second 'MallCapability\.REFUND'))

# ── V8 金额换算：整数分 → 元，不经 double ─────────────────────────────────
Check 'V8' '金额换算有显式常量/精确换算且源码内不经 double' `
    ($null -ne $second -and (Count-Matches $second 'CENTS_PER_YUAN|_CENTS|movePointLeft') -ge 1 -and (Count-Matches $second 'doubleValue\(\)|\(double\)') -eq 0) `
    ("换算标识命中=$(Count-Matches $second 'CENTS_PER_YUAN|_CENTS|movePointLeft')（应 ≥1）；double 用法=$(Count-Matches $second 'doubleValue\(\)|\(double\)')（应 0）")

# ── V9 状态词映射（F-25） ─────────────────────────────────────────────────
Check 'V9' 'F-25：第二家自有状态词映射为规范词 on_sale（适配器内显式映射）' `
    ($null -ne $second -and (Count-Matches $second '"SALE"|SALE\b') -ge 1 -and (Count-Matches $second 'on_sale') -ge 1) `
    ("自有词 SALE 命中=$(Count-Matches $second '"SALE"|SALE\b')；规范词 on_sale 命中=$(Count-Matches $second 'on_sale')")

# ── V10 夹具独立（只算非注释行；注释行命中数作为证据打印）──────────────────
$fixture = Read-IfExists (Join-Path $test 'fixture\SecondMallFakeServer.java')
$fxCode = Count-MatchesCode $fixture '/api/v1/mall|FakeMallServer|FakeMall\b'
$fxAll = Count-Matches $fixture '/api/v1/mall|FakeMallServer|FakeMall\b'
Check 'V10' 'SecondMallFakeServer 独立：**非注释行**无参考商城路由/类名引用，用自己的 /open/v2 词表' `
    ($null -ne $fixture -and $fxCode -eq 0 -and (Count-Matches $fixture '/open/v2') -ge 1) `
    ("存在={0}；参考商城引用：非注释行=$fxCode（应 0）／含注释行合计=$fxAll（v1 判据在此误报，实测 3 处全在 Javadoc 对照说明 :29/:34/:35）；/open/v2={1}（应 ≥1）" -f ($null -ne $fixture), (Count-Matches $fixture '/open/v2'))

# ── V11 注册处最小编辑（+3/-1：1 import + 1 既有行改逗号 + 1 新元素）───────
$beansPath = 'synthetic-data-generator/src/main/java/com/graduation/generator/config/GeneratorBeans.java'
$numstat = @(& git -C $root diff --numstat "$LaneCommit^..$LaneCommit" -- $beansPath)
$adds = -1; $dels = -1; $raw = '（无 numstat）'
if ($numstat.Count -gt 0 -and $numstat[0] -match '^(\d+)\s+(\d+)\s') { $adds = [int]$Matches[1]; $dels = [int]$Matches[2]; $raw = $numstat[0] }
$diffLines = @(& git -C $root diff -U0 "$LaneCommit^..$LaneCommit" -- $beansPath | Where-Object { $_ -match '^[+-][^+-]' })
$delLines = @($diffLines | Where-Object { $_.StartsWith('-') })
$addLines = @($diffLines | Where-Object { $_.StartsWith('+') })
$core = { param($s) ($s -replace '^[+-]', '') -replace '[),;\s]+$', '' }
$delCore = if ($delLines.Count -eq 1) { & $core $delLines[0] } else { '' }
$addCore1 = if ($addLines.Count -ge 2) { & $core $addLines[1] } else { '' }
$okV11 = ($adds -eq 3 -and $dels -eq 1 -and $delLines.Count -eq 1 -and $addLines.Count -eq 3 -and `
        $addLines[0].Trim() -eq '+import com.graduation.generator.adapter.SecondMallHttpAdapter;' -and `
        $delCore -eq $addCore1 -and $delCore -match 'ReferenceMallHttpAdapter' -and `
        $addLines[2] -match 'new SecondMallHttpAdapter\(')
Check 'V11' 'GeneratorBeans 为**最小加法编辑**：1 条 import + 1 条既有注册行改逗号（同核）+ 1 条新元素，无逻辑删除' `
    $okV11 `
    ("numstat=+$adds/-$dels（v1 判据要求 +1/-0，实测双适配器注册必须 +3/-1）；" + `
        "被删核同=" + ($delCore -eq $addCore1) + "；删行=" + $delLines.Count + "；加行=" + $addLines.Count + "；新元素含SecondMallHttpAdapter=" + $addLines[2].Contains('SecondMallHttpAdapter'))

# ── V12a1 T1–T7 标识齐全 ──────────────────────────────────────────────────
$opsTest = Read-IfExists (Join-Path $test 'adapter\SecondMallAdapterOperationsTest.java')
$dualTest = Read-IfExists (Join-Path $test 'engine\SecondMallDualTargetTest.java')
$guardTest = Read-IfExists (Join-Path $test 'boundary\GeneratorBoundarySourcePolicyTest.java')
$allTestText = "$opsTest`n$dualTest`n$guardTest"
$missing = @(@('T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7') | Where-Object { (Count-Matches $allTestText $_) -lt 1 })
Check 'V12a1' 'T1–T7 断言标识齐全（泳道新增两测试类内可检索）' ($missing.Count -eq 0) `
    ("缺失标识=" + $(if ($missing.Count -eq 0) { '无' } else { $missing -join '、' }) + "；三文件 T1–T7 命中数=" + ((@('T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7') | ForEach-Object { (Count-Matches $allTestText $_) }) -join '/'))

# ── V12a2 T8 变更面守卫（权威文本：M1-9 ① README:149）────────────────────
$t8Guard = @(Get-ChildItem -LiteralPath $test -Recurse -File -Filter *.java | Where-Object {
        $c = [IO.File]::ReadAllText($_.FullName, $enc)
        return ($c -match 'void\s+\w*[Cc]hangeSurface\w*\s*\(' -or $c -match '@DisplayName\("[^"\r\n]*(变更面|T8)')
    })
Check 'V12a2' 'T8 变更面守卫（新增一家商城只动 1 个新适配器文件 + 登记处 1 行）已落地为测试' ($t8Guard.Count -ge 1) `
    ('测试树内含变更面/T8 守卫方法或 DisplayName 的文件数=' + $t8Guard.Count + '（应 ≥1）；' + `
        '权威文本=docs/acceptance/m1-9-contract-first-20260912/README.md:149；' + `
        '裁决=结构性转序至 P5-03（本轮不声称 T8 通过）')
# ── V12b 全模块 E2 日志（取合计行，非首条类行）────────────────────────────
$logText = if ($LogPath -ne '' -and (Test-Path -LiteralPath $LogPath)) { [IO.File]::ReadAllText($LogPath, [Text.UTF8Encoding]::new($false)) } else { $null }
$runLine = $null; $runCount = -1; $clean = $false
if ($null -ne $logText) {
    $lines = $logText -split "`r?`n"
    $sum = @($lines | Where-Object { $_ -match '^\[INFO\] Tests run:\s*\d+,\s*Failures:\s*\d+,\s*Errors:\s*\d+,\s*Skipped:\s*\d+\s*$' })
    if ($sum.Count -gt 0) {
        $last = $sum[-1]
        $m = [regex]::Match($last, 'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)')
        $runLine = $m.Value; $runCount = [int]$m.Groups[1].Value
        $clean = ([int]$m.Groups[2].Value -eq 0 -and [int]$m.Groups[3].Value -eq 0 -and [int]$m.Groups[4].Value -eq 0)
    }
    $buildOk = @($lines | Where-Object { $_ -match '^\[INFO\] BUILD SUCCESS' }).Count -eq 1
} else { $buildOk = $false }
Check 'V12b' '全模块 E2 日志的**合计行**：Tests run ≥100 且 0 失败/0 错误/0 跳过，且 BUILD SUCCESS' `
    ($null -ne $logText -and $clean -and $runCount -ge 100 -and $buildOk) `
    ("日志=$LogPath 存在={0}；合计行原文={1}；BUILD SUCCESS={2}" -f ($null -ne $logText), $(if ($runLine) { $runLine } else { '（未匹配到合计行）' }), $buildOk)

# ── V13 无削弱测试（新增 @Disabled/@Ignore/assumeTrue）─────────────────────
$weak = @(& git -C $root diff -U0 "$LaneCommit^..$LaneCommit" -- 'synthetic-data-generator/src/test' |
    Where-Object { $_ -match '^\+' -and $_ -match '@Disabled|@Ignore|assumeTrue|assumeFalse' })
Check 'V13' '泳道提交内未新增 @Disabled/@Ignore/assumeTrue（未削弱测试）' ($weak.Count -eq 0) `
    ("新增削弱语句=$($weak.Count)（应 0）" + $(if ($weak.Count) { '：' + ($weak -join ' / ') } else { '' }))

# ── V14 无凭据字面量（32 位 hex token 之类）────────────────────────────────
$cred = @(& git -C $root diff -U0 "$LaneCommit^..$LaneCommit" -- 'synthetic-data-generator/src' |
    Where-Object { $_ -match '^\+' -and $_ -match '\b[0-9a-fA-F]{32}\b' -and $_ -notmatch 'payload_hash|sha256|SHA-256' })
Check 'V14' '泳道提交内无 32 位十六进制凭据字面量（允许 test-token/ut-token-*/SECOND_MALL_TOKEN 引用名）' ($cred.Count -eq 0) `
    ("疑似凭据新增行=$($cred.Count)（应 0）" + $(if ($cred.Count) { '：' + ($cred -join ' / ') } else { '' }))

Write-Host ''
Write-Host "=== 汇总：PASS=$script:pass FAIL=$script:fail（只读；FAIL 一律计不通过） ==="
if ($script:fail -gt 0) { exit 1 }
exit 0
