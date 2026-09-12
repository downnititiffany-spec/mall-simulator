# M1-9 ② 总控独立复核器（只读；不修改任何文件）
#
# 用法：pwsh -File docs/acceptance/m1-9-second-adapter-20260912/scripts/verify.ps1 [-LogPath <全模块测试日志>]
#
# 设计口径：
#  * 本脚本**只读**——只做文件读取、正则匹配与 git 查询，不写文件、不改工作区。
#  * 每条判据独立输出 PASS/FAIL，并打印**判据原文**（命中了什么、命中几次），不打印结论式空话。
#  * 任一条 FAIL ⇒ 退出码 1。**没有"跳过即通过"**：文件缺失一律计 FAIL。
#  * 阴性对照：本脚本在泳道交付前跑，应当出现大量 FAIL（证明它真的会失败，不是恒真）。

param(
    [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$gen = Join-Path $root 'synthetic-data-generator\src'
$main = Join-Path $gen 'main\java\com\graduation\generator'
$test = Join-Path $gen 'test\java\com\graduation\generator'

$script:pass = 0
$script:fail = 0
$script:results = New-Object System.Collections.Generic.List[string]

function Check {
    param([string]$Id, [string]$Title, [bool]$Ok, [string]$Evidence)
    if ($Ok) { $script:pass++; $tag = 'PASS' } else { $script:fail++; $tag = 'FAIL' }
    $line = "[{0}] {1}  {2}`n        证据: {3}" -f $tag, $Id, $Title, $Evidence
    $script:results.Add($line)
    Write-Host $line
}

function Read-IfExists {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) { return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false)) }
    return $null
}

function Count-Matches {
    param([string]$Text, [string]$Pattern)
    if ($null -eq $Text) { return -1 }
    return ([regex]::Matches($Text, $Pattern)).Count
}

Write-Host "=== M1-9 ② 独立复核（只读）  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
Write-Host "repo = $root"
Write-Host ''

# ── V1 变更面：改动文件必须落在泳道白名单内 ────────────────────────────────
$allowed = @(
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/TargetRoute.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/MallTargetAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ReferenceMallHttpAdapter.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallDispatchPlan.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiDispatchSink.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java',
    'synthetic-data-generator/src/main/java/com/graduation/generator/config/GeneratorBeans.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java',
    'synthetic-data-generator/src/test/java/com/graduation/generator/boundary/GeneratorBoundarySourcePolicyTest.java'
)
$porcelain = & git -C $root status --porcelain
$changed = @()
foreach ($row in $porcelain) {
    if ($row.Length -lt 4) { continue }
    $path = $row.Substring(3).Trim().Trim('"')
    if ($path -like '* -> *') { $path = ($path -split ' -> ')[-1].Trim('"') }
    $changed += $path
}
# 只审视**代码侧**变更：docs/** 是总控自己的领地（泳道被禁止改），单独列出、不计入泳道变更面。
$codeChanged = @($changed | Where-Object { ($_ -replace '\\', '/') -notlike 'docs/*' })
$docsChanged = @($changed | Where-Object { ($_ -replace '\\', '/') -like 'docs/*' })
$outside = @($codeChanged | Where-Object {
        $p = $_ -replace '\\', '/'
        ($p -notin $allowed) -and ($allowed | Where-Object { $_ -like "*$p" }).Count -eq 0
    })
Check 'V1' '代码侧变更面只在白名单内（新增适配器/夹具/用例 + 既有 7 个文件）' ($outside.Count -eq 0) `
    ("代码侧改动 $($codeChanged.Count) 个：`n        " + (($codeChanged | ForEach-Object { '  - ' + $_ }) -join "`n        ") + `
     $(if ($outside.Count -gt 0) { "`n        白名单外：`n        " + (($outside | ForEach-Object { '  ! ' + $_ }) -join "`n        ") } else { "`n        白名单外：无" }) + `
     "`n        docs 侧改动 $($docsChanged.Count) 个（不计入泳道面，供人工确认是否为总控自己的文件）：" + $(if ($docsChanged.Count) { "`n        " + (($docsChanged | ForEach-Object { '  · ' + $_ }) -join "`n        ") } else { ' 无' }))

# ── V2 路由契约类型 ────────────────────────────────────────────────────────
$targetRoute = Read-IfExists (Join-Path $main 'adapter\TargetRoute.java')
Check 'V2' 'adapter/TargetRoute.java 为 record(String method, String path) 且校验非空白' `
    ($null -ne $targetRoute -and $targetRoute -match 'record\s+TargetRoute\s*\(\s*String\s+method\s*,\s*String\s+path\s*\)' -and `
        $targetRoute -match 'isBlank' -and $targetRoute -match 'IllegalArgumentException') `
    ("文件存在={0}；record 签名命中={1}；isBlank 校验={2}" -f ($null -ne $targetRoute), `
        (Count-Matches $targetRoute 'record\s+TargetRoute\s*\(\s*String\s+method\s*,\s*String\s+path\s*\)'), `
        (Count-Matches $targetRoute 'isBlank'))

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
    $t = [IO.File]::ReadAllText($f.FullName, [Text.UTF8Encoding]::new($false))
    $n = ([regex]::Matches($t, '"/api/|"/open/')).Count
    if ($n -gt 0) { $engineHits += "$($f.Name):$n" }
}
$refAdapter = Read-IfExists (Join-Path $main 'adapter\ReferenceMallHttpAdapter.java')
$refHits = Count-Matches $refAdapter '"/api/|"/open/'
Check 'V5' 'engine/** 无商城路由字面量；正向对照：参考适配器内必须命中（证明守卫有检出能力）' `
    ($engineHits.Count -eq 0 -and $refHits -gt 0) `
    ("engine 命中文件=$($engineHits.Count)（应 0）$(if ($engineHits.Count) { '：' + ($engineHits -join '、') })；参考适配器命中=$refHits（应 >0）")

# ── V6 未声明路由时写明确占位，不回落参考商城字面量 ───────────────────────
$sink = Read-IfExists (Join-Path $main 'engine\MallApiDispatchSink.java')
$engine = Read-IfExists (Join-Path $main 'engine\MallApiGenerationEngine.java')
$placeholder = (Count-Matches $sink '（适配器未声明路由）') + (Count-Matches $engine '（适配器未声明路由）')
$routeFromAdapter = (Count-Matches $sink 'operationRoutes') + (Count-Matches $engine 'operationRoutes')
Check 'V6' '路由取自适配器（sink/engine 出现 operationRoutes）且未声明时写占位串' `
    ($routeFromAdapter -ge 1 -and $placeholder -ge 1) `
    ("operationRoutes 命中 sink=$((Count-Matches $sink 'operationRoutes')) engine=$((Count-Matches $engine 'operationRoutes'))；占位串命中=$placeholder")

# ── V7 第二家商城适配器：类型名/自有路由/能力缺口 ──────────────────────────
$second = Read-IfExists (Join-Path $main 'adapter\SecondMallHttpAdapter.java')
$secondType = Count-Matches $second 'SECOND_MALL_HTTP'
$secondRoute = Count-Matches $second '"/open/v2'
$secondAdmin = Count-Matches $second 'MallCapability\.ADMIN'
$secondRefund = Count-Matches $second 'MallCapability\.REFUND'
Check 'V7' 'SecondMallHttpAdapter：adapterType=SECOND_MALL_HTTP、自有 /open/v2 路由、声明 admin/refund 缺失' `
    ($null -ne $second -and $secondType -ge 1 -and $secondRoute -ge 1 -and $secondAdmin -ge 1 -and $secondRefund -ge 1) `
    ("存在={0}；SECOND_MALL_HTTP={1}；/open/v2={2}；ADMIN 提及={3}；REFUND 提及={4}" -f ($null -ne $second), $secondType, $secondRoute, $secondAdmin, $secondRefund)

# ── V8 金额换算：整数分 → 元，不经 double ─────────────────────────────────
$centsConst = (Count-Matches $second 'CENTS_PER_YUAN|_CENTS|movePointLeft') 
$doubleUse = (Count-Matches $second 'doubleValue\(\)|\(double\)')
Check 'V8' '金额换算有显式常量/精确换算且源码内不经 double' `
    ($null -ne $second -and $centsConst -ge 1 -and $doubleUse -eq 0) `
    ("换算标识命中=$centsConst（应 ≥1）；double 用法=$doubleUse（应 0）")

# ── V9 状态词映射（F-25） ─────────────────────────────────────────────────
$stateMap = Count-Matches $second '"SALE"|SALE\b'
$canonical = Count-Matches $second 'on_sale'
Check 'V9' 'F-25：第二家自有状态词映射为规范词 on_sale（适配器内显式映射）' `
    ($null -ne $second -and $stateMap -ge 1 -and $canonical -ge 1) `
    ("自有词 SALE 命中=$stateMap；规范词 on_sale 命中=$canonical")

# ── V10 夹具独立：不得复刻参考商城词表/类 ────────────────────────────────
$fixture = Read-IfExists (Join-Path $test 'fixture\SecondMallFakeServer.java')
$fixtureRef = Count-Matches $fixture '/api/v1/mall|FakeMallServer|FakeMall\b'
$fixtureOpen = Count-Matches $fixture '/open/v2'
Check 'V10' 'SecondMallFakeServer 独立（无参考商城路由/类名引用，用自己的 /open/v2 词表）' `
    ($null -ne $fixture -and $fixtureRef -eq 0 -and $fixtureOpen -ge 1) `
    ("存在={0}；参考商城引用={1}（应 0）；/open/v2={2}（应 ≥1）" -f ($null -ne $fixture), $fixtureRef, $fixtureOpen)

# ── V11 登记处只加一行 ───────────────────────────────────────────────────
$numstat = & git -C $root diff --numstat -- 'synthetic-data-generator/src/main/java/com/graduation/generator/config/GeneratorBeans.java'
$beans = Read-IfExists (Join-Path $main 'config\GeneratorBeans.java')
$beansNew = Count-Matches $beans 'SecondMallHttpAdapter'
$adds = -1; $dels = -1
if ($numstat) { $parts = ($numstat -split "`t"); $adds = [int]$parts[0]; $dels = [int]$parts[1] }
Check 'V11' 'GeneratorBeans 登记处只新增（+1 行注册，0 删除）' `
    ($adds -eq 1 -and $dels -eq 0 -and $beansNew -ge 1) `
    ("numstat=+$adds/-$dels（应 +1/-0）；SecondMallHttpAdapter 提及=$beansNew")

# ── V12 用例与全模块日志 ─────────────────────────────────────────────────
$opsTest = Read-IfExists (Join-Path $test 'adapter\SecondMallAdapterOperationsTest.java')
$dualTest = Read-IfExists (Join-Path $test 'engine\SecondMallDualTargetTest.java')
$guardTest = Read-IfExists (Join-Path $test 'boundary\GeneratorBoundarySourcePolicyTest.java')
$allTestText = "$opsTest`n$dualTest`n$guardTest"
$tHits = @{}
foreach ($k in @('T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8')) { $tHits[$k] = Count-Matches $allTestText $k }
$testsNamed = (@('T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8') | Where-Object { $tHits[$_] -lt 1 })
$logText = if ($LogPath -ne '' -and (Test-Path -LiteralPath $LogPath)) { [IO.File]::ReadAllText($LogPath, [Text.UTF8Encoding]::new($false)) } else { $null }
$runLine = $null; $runCount = -1
if ($null -ne $logText) {
    $m = [regex]::Match($logText, 'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)')
    if ($m.Success) {
        $runLine = $m.Value; $runCount = [int]$m.Groups[1].Value
        $clean = ([int]$m.Groups[2].Value -eq 0 -and [int]$m.Groups[3].Value -eq 0 -and [int]$m.Groups[4].Value -eq 0)
    } else { $clean = $false }
} else { $clean = $false }
Check 'V12a' 'T1–T8 断言标识齐全（测试源码内可检索）' ($testsNamed.Count -eq 0) `
    ("缺失标识=" + $(if ($testsNamed.Count -eq 0) { '无' } else { $testsNamed -join '、' }))
Check 'V12b' '全模块 E2 日志：Tests run ≥100 且 0 失败/0 错误/0 跳过' `
    ($null -ne $logText -and $clean -and $runCount -ge 100) `
    ("日志=$LogPath 存在={0}；原文行={1}" -f ($null -ne $logText), $(if ($runLine) { $runLine } else { '（未匹配到 Tests run 行）' }))

Write-Host ''
Write-Host "=== 汇总：PASS=$script:pass FAIL=$script:fail（本脚本只读；FAIL 一律计不通过） ==="
if ($script:fail -gt 0) { exit 1 }
exit 0
