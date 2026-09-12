# e1a-compare.ps1 —— CT 批次集成期 E1-a「改动前 vs 改动后」对比门禁（父侧独立判据）
#
# 为什么需要本脚本：CT 泳道给出的 E1-a 是「platform-common,connection-ingestion」两模块集（41 + 156 = 197），
# 而 `docs/acceptance/ct-batch-20260912/PLAN.md` §3 的字面命令是
#   mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test '-DforkCount=0'
# 含 `-am` ⇒ 实际跑 **7 个反应堆模块、6 个有测试、合计 537 条**：
#   platform-common 41 / connection-ingestion 156 / warehouse-pipeline 111 / metric-analysis 38 /
#   ai-decision 91 / platform-app 100
# 且**主检出改动前**该字面命令已是 1 条既有失败
# （`IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`，环境面 `landing/manifests/40.json`
# 被回填，属 D-037 裁决 6 的前提校验，`landing/**` 为 gitignore 面）。
# ⇒ PLAN §3 字面判据「Tests run: N, Failures: 0, Errors: 0 ＋ BUILD SUCCESS」在改动前即不成立；
#   本脚本因此把判据改成**可实测且可复核**的七条（C1..C7），并显式声明：
#   ★ 本脚本不声称 E1-a 通过 PLAN 字面判据，只声称下列对比结论。
#
# 口径限制（已知，不得当作已覆盖）：失败用例名取自 surefire 汇总块 `[ERROR]   Class.method:line` 的**简单类名**；
# 若两个模块存在同名类同名方法，集合对比可能互相掩盖 ⇒ 用 C6（模块集合必须一致）旁证，但仍不区分同名类。
#
# 实现陷阱（本脚本已规避，勿改回）：**不要**对元素为 `[pscustomobject]` 的 `System.Collections.Generic.List[object]`
# 用数组子表达式 `@($list)` —— 在本机 pwsh 7.6.6 上抛 `System.ArgumentException: Argument types do not match`
# （异常类型看着像"函数参数绑定错"，实测与函数调用无关，单行 `@($c).Count` 即可复现）。
# ⇒ 本脚本用「只返回对象的嵌套函数 ＋ 父作用域 `+=` 收集」代替 List，并对 `List[string]` 用 `.ToArray()`。
#
# 用法：
#   pwsh -File e1a-compare.ps1 -BaselineLog <改动前日志> -AfterLog <改动后日志>
#   pwsh -File e1a-compare.ps1 -SelfTest -BaselineLog <改动前日志>   # 反向自测：喂人为做坏的日志，分析器必须判 FAIL
# 退出码：0 = PASS；1 = FAIL（对比不成立）；9 = 基础设施错误（文件缺失/解析不到汇总行）

[CmdletBinding()]
param(
    [string]$BaselineLog,
    [string]$AfterLog,
    [switch]$SelfTest,
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
$script:total = 0
$script:fail = 0
$script:lines = New-Object System.Collections.Generic.List[string]

function Assert([string]$what, $ok, [string]$detail) {
    $script:total++
    if (-not $ok) { $script:fail++ }
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    $script:lines.Add(('  [{0}] {1} :: {2}' -f $tag, $what, $detail))
    Write-Host ('  [{0}] {1} :: {2}' -f $tag, $what, $detail)
}
function Write-Log([string]$msg) {
    $script:lines.Add($msg)
    Write-Host $msg
}

# ---- 解析：只取「模块汇总行」（行尾锚定），并按 Building 行归属到模块 ------------
# surefire 单类行形如：「Tests run: 5, Failures: 0, ... -- in com.x.Y」
# 模块汇总行形如：「Tests run: 197, Failures: 0, Errors: 0, Skipped: 0」（行尾即结束）
# ⇒ 行尾锚定把两者区分开，避免把单类行也累加（否则总数会翻倍）。
function Parse-SurefireLog([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "日志不存在：$path" }
    $raw = [IO.File]::ReadAllLines($path)
    $modRe = '^\s*(?:\[INFO\]|\[ERROR\])\s+Tests run:\s+(\d+),\s+Failures:\s+(\d+),\s+Errors:\s+(\d+),\s+Skipped:\s+(\d+)\s*$'
    $failRe = '^\s*\[ERROR\]\s{2,}([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$]+)(?::(\d+))?\s*(.*)$'
    $tests = 0; $failures = 0; $errors = 0
    $build = ''
    $modTotals = [ordered]@{}
    $failNames = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $raw.Count; $i++) {
        $ln = $raw[$i]
        $m = [regex]::Match($ln, $modRe)
        if ($m.Success) {
            $owner = '(未找到 Building 行)'
            for ($j = $i; $j -ge 0; $j--) {
                if ($raw[$j] -match '^\[INFO\]\s+Building\s+(.+?)\s+\d') { $owner = $Matches[1]; break }
            }
            $tests += [int]$m.Groups[1].Value
            $failures += [int]$m.Groups[2].Value
            $errors += [int]$m.Groups[3].Value
            $modTotals[$owner] = [int]$m.Groups[1].Value
            continue
        }
        if ($ln -match 'BUILD SUCCESS|BUILD FAILURE') { $build = $ln.Trim() }
        $f = [regex]::Match($ln, $failRe)
        if ($f.Success) {
            $nm = $f.Groups[1].Value + '.' + $f.Groups[2].Value
            if (-not $failNames.Contains($nm)) { $failNames.Add($nm) }
        }
    }
    if ($modTotals.Count -eq 0) { throw "日志中找不到任何模块汇总行（行尾锚定）：$path" }
    return [pscustomobject]@{
        Path = $path; Modules = $modTotals; ModuleCount = $modTotals.Count
        Tests = $tests; Failures = $failures; Errors = $errors; Build = $build
        FailNames = $failNames.ToArray()
    }
}

# ---- 纯分析：返回检查项集合与裁决（不写全局计数器，便于反向自测） --------------
function New-Check([string]$name, $ok, [string]$detail) {
    return [pscustomobject]@{ Name = $name; Ok = [bool]$ok; Detail = $detail }
}
function Compare-Logs([string]$b, [string]$a) {
    $before = Parse-SurefireLog $b
    $after = Parse-SurefireLog $a
    $checks = @()
    $newFails = @($after.FailNames | Where-Object { $before.FailNames -notcontains $_ })
    $goneFails = @($before.FailNames | Where-Object { $after.FailNames -notcontains $_ })
    $beforeMods = [string[]]$before.Modules.Keys
    $afterMods = [string[]]$after.Modules.Keys
    $modDelta = @($afterMods | Where-Object { $beforeMods -notcontains $_ }) + @($beforeMods | Where-Object { $afterMods -notcontains $_ })
    $regressed = @()
    foreach ($k in $beforeMods) {
        if ($afterMods -contains $k -and $after.Modules[$k] -lt $before.Modules[$k]) {
            $regressed += ('{0} {1}→{2}' -f $k, $before.Modules[$k], $after.Modules[$k])
        }
    }
    $checks += New-Check 'C1 测试总数不得下降' ($after.Tests -ge $before.Tests) ('{0} → {1}（差 {2}）' -f $before.Tests, $after.Tests, ($after.Tests - $before.Tests))
    $checks += New-Check 'C2 不得新增失败用例' ($newFails.Count -eq 0) ('新增 = ' + $(if ($newFails.Count -eq 0) { '无' } else { ($newFails -join ', ') }))
    $checks += New-Check 'C3 判据地基：两侧都解析到模块汇总行' (($before.ModuleCount -gt 0) -and ($after.ModuleCount -gt 0)) ('前 {0} 条／后 {1} 条' -f $before.ModuleCount, $after.ModuleCount)
    $checks += New-Check 'C4 模块集合必须一致（防"少跑一个模块"式假绿）' ($modDelta.Count -eq 0) $(if ($modDelta.Count -eq 0) { ('{0} 个模块逐名相同' -f $before.ModuleCount) } else { '差异 = ' + ($modDelta -join ', ') })
    $checks += New-Check 'C5 逐模块测试数不得下降' ($regressed.Count -eq 0) $(if ($regressed.Count -eq 0) { '逐模块均未下降' } else { '下降 = ' + ($regressed -join '；') })
    $checks += New-Check 'C6 既有红逐条保留（消失≠修好，须解释）' ($goneFails.Count -eq 0) ('改动前红 {0} 条{1}' -f $before.FailNames.Count, $(if ($goneFails.Count -gt 0) { '，消失 ' + $goneFails.Count + ' 条：' + ($goneFails -join ', ') + '（可能是用例被删/改名/跳过 ⇒ 须解释）' } else { '，无消失' }))
    $checks += New-Check 'C7 BUILD 结果不得从 SUCCESS 变为 FAILURE' (-not (($before.Build -match 'SUCCESS') -and ($after.Build -match 'FAILURE'))) ('前 {0}／后 {1}' -f $before.Build, $after.Build)
    return [pscustomobject]@{
        Before = $before; After = $after; Checks = $checks
        Verdict = $(if (@($checks | Where-Object { -not $_.Ok }).Count -eq 0) { 'PASS' } else { 'FAIL' })
    }
}

function Show-ModuleTable($r) {
    Write-Log ''
    Write-Log '  逐模块测试数（前 → 后）：'
    foreach ($k in $r.Before.Modules.Keys) {
        $bv = $r.Before.Modules[$k]
        $av = if ($r.After.Modules.Contains($k)) { $r.After.Modules[$k] } else { '(无)' }
        Write-Log ('    {0,-24} {1,5} → {2,5}' -f $k, $bv, $av)
    }
    Write-Log ('    合计 {0,22} → {1,5}' -f ($r.Before.Tests), ($r.After.Tests))
    Write-Log ''
    Write-Log ('  失败集合 前：{0}' -f $(if ($r.Before.FailNames.Count -eq 0) { '（空）' } else { ($r.Before.FailNames -join ' , ') }))
    Write-Log ('  失败集合 后：{0}' -f $(if ($r.After.FailNames.Count -eq 0) { '（空）' } else { ($r.After.FailNames -join ' , ') }))
    Write-Log ('  BUILD 前：{0}' -f $r.Before.Build)
    Write-Log ('  BUILD 后：{0}' -f $r.After.Build)
}

# ---- 反向自测：门禁必须先能判红（喂人为做坏的日志） ----------------------------
function Invoke-SelfTest([string]$src) {
    Write-Log ''
    Write-Log '  ==== 反向自测：把"改动后"日志人为做坏，分析器必须判 FAIL ===='
    $tmp = Join-Path $env:TEMP ('e1a-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    $good = Join-Path $tmp 'after-good.log'
    [IO.File]::WriteAllLines($good, [IO.File]::ReadAllLines($src))
    # 做坏：少跑一个模块（platform-common 41 条整块删掉）＋ 新增一条失败 ⇒ 必须同时被 C1/C2/C4/C5 抓到
    $bad = Join-Path $tmp 'after-bad.log'
    $bl = New-Object System.Collections.Generic.List[string]
    $skip = $false
    foreach ($l in [IO.File]::ReadAllLines($src)) {
        if ($l -match '^\[INFO\]\s+Building\s+platform-common\s') { $skip = $true }
        elseif ($l -match '^\[INFO\]\s+Building\s+' -and $skip) { $skip = $false }
        if (-not $skip) { $bl.Add($l) }
    }
    $bl.Add('[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0')
    $bl.Add('[ERROR]   com.graduation.analytics.SyntheticNewFailureTest.mustBeCaught:42 人为做坏')
    [IO.File]::WriteAllLines($bad, $bl)

    $rGood = Compare-Logs $src $good
    $rBad = Compare-Logs $src $bad
    Write-Log ('  自测①同源对比：检查项 {0} 条，FAIL {1} 条（应然 0）⇒ {2}' -f $rGood.Checks.Count, @($rGood.Checks | Where-Object { -not $_.Ok }).Count, $rGood.Verdict)
    Write-Log ('  自测②做坏日志：检查项 {0} 条，FAIL {1} 条（应然 >=1）⇒ {2}' -f $rBad.Checks.Count, @($rBad.Checks | Where-Object { -not $_.Ok }).Count, $rBad.Verdict)
    @($rBad.Checks | Where-Object { -not $_.Ok }) | ForEach-Object { Write-Log ('      ↳ 抓到：{0} :: {1}' -f $_.Name, $_.Detail) }
    Assert 'S1 分析器对「同源日志」判绿' ($rGood.Verdict -eq 'PASS') ('裁决 = ' + $rGood.Verdict)
    Assert 'S2 分析器对「做坏日志」判红（防假绿灯）' ($rBad.Verdict -eq 'FAIL') ('裁决 = ' + $rBad.Verdict)
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# ---- 主流程 -------------------------------------------------------------------
Write-Log '==== CT 集成期 E1-a 对比门禁（父侧独立判据；不声称通过 PLAN 字面判据）===='
Write-Log ('  时间 = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$compareCount = 7   # Compare-Logs 的 C1..C7 条数（单一来源，供 D1 自检用）

if ($SelfTest) {
    if (-not $BaselineLog) { $BaselineLog = 'D:\Develop_code\GraduationProject\.verify\ct-e1a-baseline-main.log' }
    try { Invoke-SelfTest $BaselineLog } catch {
        Write-Log ('  [基础设施错误] ' + $_.Exception.Message)
        Write-Log ('    位置 = ' + $_.InvocationInfo.PositionMessage.Trim())
        Write-Log ('    栈 = ' + ($_.ScriptStackTrace -replace "`r?`n", ' | '))
        exit 9
    }
    $expected = 3            # S1 + S2 + D1
} else {
    if (-not $BaselineLog -or -not $AfterLog) {
        Write-Log '  用法：-BaselineLog <log> -AfterLog <log>；或 -SelfTest [-BaselineLog <log>]'
        exit 9
    }
    try {
        $r = Compare-Logs $BaselineLog $AfterLog
    } catch {
        Write-Log ('  [基础设施错误] ' + $_.Exception.Message)
        Write-Log ('    位置 = ' + $_.InvocationInfo.PositionMessage.Trim())
        Write-Log ('    栈 = ' + ($_.ScriptStackTrace -replace "`r?`n", ' | '))
        exit 9
    }
    Show-ModuleTable $r
    Write-Log ''
    foreach ($c in $r.Checks) { Assert $c.Name $c.Ok $c.Detail }
    $expected = $compareCount + 1   # C1..C7 + D1
}

# ---- D 段：自检（应然断言数 vs 实际；本断言的比较在自增之前求值 ⇒ +1） --------
Write-Log ''
$okCount = (($script:total + 1) -eq $expected)
Assert 'D1 断言数与应然值一致（本脚本自检）' $okCount ('应然 = {0}，实际 = {1}' -f $expected, ($script:total + 1))

Write-Log ''
Write-Log ('  ==== 汇总：断言 {0} 条，PASS {1}，FAIL {2} ====' -f $script:total, ($script:total - $script:fail), $script:fail)
$verdict = if ($script:fail -eq 0) { 'PASS' } else { 'FAIL' }
Write-Log ('  结果 = ' + $verdict)
if ($LogPath) { [IO.File]::WriteAllLines($LogPath, $script:lines) }
if ($script:fail -eq 0) { exit 0 } else { exit 1 }
