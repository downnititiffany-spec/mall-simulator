# ============================================================================
# M1-9 ② 修复轮验收复核器（判据**先冻结后交付**）
# 冻结时刻：2026-09-12 12:2x（在修复泳道交付之前写入；修复泳道只收到"修哪几条"的裁决，
#           未见过本脚本的判据表达式）。
# 复核对象：**工作区未提交**的修复（复核器 v2 复核的是提交 25b0fe7 的交付面；本器复核修复面）。
# 与 v2 的关系：v2 的 V1–V14 仍适用（交付面不变），本器只追加"修复是否落地"的机械判据。
# 口径：每条零命中期望都配**正向对照**（证明读的是对的文件、正则有检出能力）。
#      含"机械下限 + 人工读证"的条目在证据串里明说，不把下限当充分证明。
# 用法：pwsh -NoProfile -File verify-fix.ps1 [-LogPath <E2日志>] [-BaselineCommit ceeddec]
# ============================================================================
param(
    [string]$LogPath = '',
    [string]$BaselineCommit = 'ceeddec'
)
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$enc = [Text.UTF8Encoding]::new($false)
$srcRoot = Join-Path $root 'synthetic-data-generator\src'
if (-not (Test-Path -LiteralPath $srcRoot)) { throw ('未在仓库根运行：' + $srcRoot + ' 不存在') }
$mainJava = Join-Path $srcRoot 'main\java\com\graduation\generator'
$testJava = Join-Path $srcRoot 'test\java\com\graduation\generator'
$adapter = Join-Path $mainJava 'adapter\SecondMallHttpAdapter.java'
$engineDir = Join-Path $mainJava 'engine'
$testA = Join-Path $testJava 'adapter\SecondMallAdapterOperationsTest.java'
$testB = Join-Path $testJava 'engine\SecondMallDualTargetTest.java'

$script:pass = 0; $script:fail = 0
function Check([string]$id, [string]$title, [bool]$ok, [string]$ev) {
    if ($ok) { $script:pass++; $tag = 'PASS' } else { $script:fail++; $tag = 'FAIL' }
    Write-Host ('[' + $tag + '] ' + $id + '  ' + $title)
    Write-Host ('        证据: ' + $ev)
}
function Rd([string]$p) { return [IO.File]::ReadAllText($p, $enc) }
function Cnt([string]$t, [string]$pat) { return ([regex]::Matches($t, $pat)).Count }
function SrcFingerprint {
    $h = @{}
    Get-ChildItem -LiteralPath $srcRoot -Recurse -File | ForEach-Object {
        $h[$_.FullName.Substring($root.Length + 1)] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    }
    return $h
}
function FingerDiff([hashtable]$a, [hashtable]$b) {
    $d = @()
    foreach ($k in $a.Keys) { if (-not $b.ContainsKey($k) -or $b[$k] -ne $a[$k]) { $d += ('改:' + $k) } }
    foreach ($k in $b.Keys) { if (-not $a.ContainsKey($k)) { $d += ('增:' + $k) } }
    return $d
}

Write-Host '=== M1-9 ② 修复轮验收复核（只读；判据先冻结）==='
Write-Host ('  基线提交=' + $BaselineCommit + '  工作区=' + $root)
$before = SrcFingerprint
Write-Host ('  取证起点：src 下 ' + $before.Count + ' 个文件的 sha256 已记录')
Write-Host ''

# ── W0 并发写者守卫：复核期间 src 不得变动（否则本次结论无效）──────────────
# （W0 的判定在脚本末尾用 after 指纹给出，先登记起点。）

# ── W1 变更面：修复只落在允许清单内，且未触碰 docs/** 与 contract-specs/** ──
$allow = @(
    'adapter/SecondMallHttpAdapter.java', 'adapter/MallStatusVocabulary.java', 'adapter/ExternalProduct.java',
    'adapter/ProductPage.java', 'adapter/MallTargetAdapter.java', 'adapter/TargetRoute.java',
    'engine/MallApiGenerationEngine.java', 'engine/MallApiDispatchSink.java', 'engine/MallDispatchPlan.java',
    'engine/OperationJournalEntry.java', 'config/GeneratorBeans.java',
    'adapter/SecondMallAdapterOperationsTest.java', 'engine/SecondMallDualTargetTest.java',
    'boundary/GeneratorBoundarySourcePolicyTest.java', 'fixture/SecondMallFakeServer.java'
)
$stat = @(& git status --porcelain -- synthetic-data-generator/src)
$outside = @()
foreach ($ln in $stat) {
    $q = $ln.Substring(3).Trim().Trim('"')
    $hit = $false
    foreach ($a in $allow) { if ($q.EndsWith($a)) { $hit = $true } }
    if (-not $hit) { $outside += $q }
}
Check 'W1' '修复变更面 ⊆ 允许清单' ($outside.Count -eq 0) `
    ('工作区变更文件数=' + $stat.Count + '；清单外=' + (($outside | ForEach-Object { $_ }) -join ';' ) + '；清单=' + $allow.Count + ' 条')
$docStat = @(& git status --porcelain -- docs contract-specs)
$docBad = @($docStat | Where-Object { $_ -notmatch '8091-stdout\.log' -and $_ -notmatch 'verify-fix\.ps1' })
Check 'W1b' '修复未触碰 docs/** 与 contract-specs/**（豁免：8091 运行期日志＝F-26 既有残留；本复核器自身 verify-fix.ps1 尚未入库）' ($docBad.Count -eq 0) `
    ('docs/contract-specs 变更行=' + $docStat.Count + '；除豁免外=' + $docBad.Count + $(if ($docBad.Count -gt 0) { '；' + ($docBad -join ';') } else { '' }))

# ── W2 S1：订单状态只透出商城原词（别名后缀已删）────────────────────────────
$ad = Rd $adapter
$alias = Cnt $ad 'ORDER_STATE_ALIAS'
$aliasLit = Cnt $ad '"(CREATED|PAID|CANCELLED|NEW)"'
$pcRead = Cnt $ad 'readOrder'
Check 'W2' 'S1 已修：ORDER_STATE_ALIAS 与别名词字面量均为 0（正向对照 readOrder ≥1）' `
    (($alias -eq 0) -and ($aliasLit -eq 0) -and ($pcRead -ge 1)) `
    ('ORDER_STATE_ALIAS=' + $alias + '（应 0）；别名词字面量="(CREATED|PAID|CANCELLED|NEW)"=' + $aliasLit + '（应 0）；正向对照 readOrder=' + $pcRead + '（应 ≥1）')

# ── W3 S2：魔法串单点定义 + 报错点名 format（机械下限 + 人工读证）───────────
$fmtLit = Cnt $ad '"format"'
$openV2 = Cnt $ad 'open-v2'
$fmtLines = @($ad -split [char]10 | Where-Object { $_ -match 'format' } | Select-Object -First 4)
Check 'W3' 'S2 机械下限：`"format"` 字面量 ≤1（单点定义）；正向对照 open-v2 ≥1' `
    (($fmtLit -le 1) -and ($openV2 -ge 1)) `
    ('"format" 字面量=' + $fmtLit + '（应 ≤1）；正向对照 open-v2=' + $openV2 + '（应 ≥1）；含 format 的行（人工读证，判断报错是否点名该键）=' + (($fmtLines | ForEach-Object { $_.Trim() }) -join ' || '))

# ── W4 S3：能力缺口的声明标注"未探测/静态声明"──────────────────────────────
$notProbed = Cnt $ad '(未探测|静态声明|未做探测)'
$absent = Cnt $ad 'ABSENT'
Check 'W4' 'S3 已修：能力缺口声明明说"未探测/静态声明"（正向对照 ABSENT ≥1）' `
    (($notProbed -ge 1) -and ($absent -ge 1)) `
    ('未探测/静态声明 命中=' + $notProbed + '（应 ≥1）；正向对照 ABSENT=' + $absent + '（应 ≥1）')

# ── W5 S4：引擎对商城词表零知识 ───────────────────────────────────────────
$engText = ''
Get-ChildItem -LiteralPath $engineDir -Recurse -File -Filter *.java | ForEach-Object { $engText += (Rd $_.FullName) }
$engVocab = Cnt $engText 'MallStatusVocabulary'
$engInst = Cnt $engText 'instanceof\s+MallStatusVocabulary'
$engPc = Cnt $engText 'operationRoutes'
Check 'W5' 'S4 已修：engine/** 不含 MallStatusVocabulary 及其 instanceof（正向对照 operationRoutes ≥1）' `
    (($engVocab -eq 0) -and ($engInst -eq 0) -and ($engPc -ge 1)) `
    ('engine/** 内 MallStatusVocabulary=' + $engVocab + '（应 0）；其 instanceof=' + $engInst + '（应 0）；正向对照 operationRoutes=' + $engPc + '（应 ≥1）')

# ── W6 S5：跨运行/跨目标隔离的行为级证据 ──────────────────────────────────
$testAll = ''
Get-ChildItem -LiteralPath $testJava -Recurse -File -Filter *.java | ForEach-Object { $testAll += (Rd $_.FullName) }
$cross = Cnt $testAll '跨运行'
Check 'W6' 'S5 行为级证据：存在断言"跨运行"隔离的用例（DisplayName 含 跨运行）' ($cross -ge 1) `
    ('测试树内 跨运行 命中=' + $cross + '（应 ≥1）')

# ── W7 S6：价格缺失响亮失败的行为级证据 ───────────────────────────────────
$loud = Cnt $testAll '价格缺失'
Check 'W7' 'S6 行为级证据：存在断言"价格缺失"响亮失败的用例（DisplayName 含 价格缺失）' ($loud -ge 1) `
    ('测试树内 价格缺失 命中=' + $loud + '（应 ≥1）')

# ── W8 S7：缺字段不再混入"未映射状态词" ───────────────────────────────────
$msw = Cnt $ad 'MISSING_STATE_WORD'
$cnSentence = Cnt $ad '商城未给 state 字段'
Check 'W8' 'S7 已修：缺字段标记独立（MISSING_STATE_WORD=0 或 中文说明=0，二者至少其一归零）' `
    (($msw -eq 0) -or ($cnSentence -eq 0)) `
    ('MISSING_STATE_WORD=' + $msw + '；"(商城未给 state 字段)"=' + $cnSentence + '（至少其一应 0）')

# ── W9 W1/W2：严格断言取代截断式断言 ──────────────────────────────────────
$ta = Rd $testA
$sw = Cnt $ta 'startsWith\("NEW"\)'
$sp = Cnt $ta 'split\("\\\\\("\)'
$eqNew = Cnt $ta 'assertEquals\("NEW"'
$eqSet = Cnt $ta 'assertEquals\("SETTLED"'
$eqPc = Cnt $ta 'assertEquals'
Check 'W9' 'W1/W2 已修：无 startsWith("NEW")/split("(") 截断；有 assertEquals("NEW"/"SETTLED")' `
    (($sw -eq 0) -and ($sp -eq 0) -and (($eqNew + $eqSet) -ge 2) -and ($eqPc -ge 10)) `
    ('startsWith("NEW")=' + $sw + '；split("(")=' + $sp + '；assertEquals("NEW")=' + $eqNew + '；assertEquals("SETTLED")=' + $eqSet + '；正向对照 assertEquals 总数=' + $eqPc)

# ── W10/W11/W14：未削弱测试 / 无凭据 / 断言行数不减少 ─────────────────────
$diff = @(& git diff HEAD --unified=0 -- synthetic-data-generator/src)
$added = @($diff | Where-Object { $_ -match '^\+' -and $_ -notmatch '^\+\+\+' })
$weaken = @($added | Where-Object { $_ -match '@Disabled|@Ignore|assumeTrue|assumeFalse' })
Check 'W10' '未新增 @Disabled/@Ignore/assumeTrue/assumeFalse' ($weaken.Count -eq 0) `
    ('新增行=' + $added.Count + '；其中削弱语句=' + $weaken.Count + '（应 0）')
$cred = @($added | Where-Object { $_ -match '\b[0-9a-fA-F]{32}\b' })
Check 'W11' '未新增 32 位十六进制凭据字面量' ($cred.Count -eq 0) `
    ('新增行中疑似凭据=' + $cred.Count + '（应 0）')
$weakCnt = @()
foreach ($rel in @('synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java',
        'synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java',
        'synthetic-data-generator/src/test/java/com/graduation/generator/boundary/GeneratorBoundarySourcePolicyTest.java')) {
    $nowTxt = Rd (Join-Path $root ($rel -replace '/', '\'))
    $oldTxt = (@(& git show ($BaselineCommit + ':' + $rel)) -join [char]10)
    $nNow = Cnt $nowTxt 'assert[A-Za-z]*\('
    $nOld = Cnt $oldTxt 'assert[A-Za-z]*\('
    $weakCnt += (($rel -split '/')[-1] + ':' + $nOld + '→' + $nNow)
    if ($nNow -lt $nOld) { $weakCnt += 'FAIL' }
}
Check 'W14' '既有测试断言数不减少（防"改口径变相削弱"）' ($weakCnt -notcontains 'FAIL') `
    ('断言调用数（基线→工作区）：' + ($weakCnt -join '；'))

# ── W12 E2 日志合计行 ─────────────────────────────────────────────────────
if ($LogPath -ne '' -and (Test-Path -LiteralPath $LogPath)) {
    $lg = Rd $LogPath
    $sum = @($lg -split [char]10 | Where-Object { $_ -match '^Tests run: \d+, Failures' } | Select-Object -Last 1)
    $okSum = ($sum.Count -eq 1 -and $sum[0] -match 'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0')
    $n = 0; if ($okSum) { $n = [int]([regex]::Match($sum[0], 'Tests run: (\d+)').Groups[1].Value) }
    Check 'W12' 'E2 合计行：≥111 且 0 失败/0 错误/0 跳过，且 BUILD SUCCESS' `
        ($okSum -and $n -ge 111 -and $lg -match 'BUILD SUCCESS') `
        ('日志=' + $LogPath + '；合计行=' + ($sum -join '') + '；BUILD SUCCESS=' + ($lg -match 'BUILD SUCCESS'))
}
else {
    Check 'W12' 'E2 合计行' $false ('未提供 -LogPath 或日志不存在：' + $LogPath + '（按未取证计，不通过）')
}

# ── W0 结论：复核期间 src 未被并发改动 ────────────────────────────────────
$after = SrcFingerprint
$drift = @(FingerDiff $before $after)
Check 'W0' '复核期间 src 指纹未变（否则本次修复轮结论无效）' ($drift.Count -eq 0) `
    ('起点文件数=' + $before.Count + '；漂移=' + $drift.Count + '（应 0）' + $(if ($drift.Count -gt 0) { '；' + ($drift -join ';') } else { '' }))

Write-Host ''
Write-Host ('=== 汇总：PASS=' + $script:pass + ' FAIL=' + $script:fail + '（只读；FAIL 一律计不通过）===')
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
