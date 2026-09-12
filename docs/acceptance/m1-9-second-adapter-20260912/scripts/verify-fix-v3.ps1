# ============================================================================
# M1-9 ② 修复轮验收复核器 **v3（在 v2 之上的追加修订版；本版只含 (修-6)）**（判据**先冻结后交付**）
# 冻结时刻：2026-09-12 12:2x 出 v1；本 v2 追加于 2026-09-12 12:3x，**仍在修复泳道交付之前**。
# 复核对象：**工作区未提交**的修复（复核器 v2 复核提交 25b0fe7 的交付面；本器复核修复面）。
# 与 v1 的关系：v1 = verify-fix.ps1（191 行，sha256 EF64B860E4FF784E4DD2322B37AC102613DE9E229F7491D30B80E026DAE82354，
#            已入库）。**v1 原样留档、一字不改**；本 v2 只在 v1 上做四处**声明式修订**（逐条留痕，不作静默放宽）：
#   (修-1) 变更面允许清单 $allow 增补 3 个**同族**文件：
#          adapter/ExternalOrder.java、adapter/ExternalRefund.java、adapter/ExternalUser.java。
#          理由：v1 的清单是按**复核报告点名的 14 个文件**枚举的（S1–S7 逐点），而 S1 是**一类**缺陷
#          （"造一个看起来像商城词的值"）：人工读码发现同一类残留在订单/退款 DTO 的 `"UNKNOWN"` 归一化上，
#          总控同轮裁决"在同一泳道收口"⇒ 修复面合法扩大。若不放行，W1 会以"清单外文件"误判。
#          ⇒ 这同时是 v1 的**判据缺口**（按缺陷点而非缺陷类写清单），在验收补记里明记。
#   (修-2) 追加判据 **W15**：S1 同类面（DTO 造值）是否收口 + 运维话术是否点名 format 键
#          （机械下限 + 人工读证，不把下限当充分证明）。
#   (修-3) W1b 的自我豁免正则由 `verify-fix\.ps1` 放宽为 `verify-fix(-v2)?\.ps1`（本器自身也未入库）。
#   (修-4) **破坏性缺陷修复**（2026-09-12 12:33 事故，实测）：v1/v2 初稿的读文件 helper 名为 `Rd`，
#          而本机 PowerShell 7.6.6 的内置别名 `rd` = `Remove-Item`，**别名优先级高于函数**
#          （PS 解析顺序：别名 > 函数 > cmdlet）⇒ 旧脚本里每一条 `Rd <路径>` 实际执行的是
#          **删除该文件**并返回空串。后果有两层，都是致命的：
#            (a) 取证对象当场被销毁（v2 初稿一次运行删掉 33 个文件：adapter/SecondMallHttpAdapter.java
#                + engine/** 11 个 + test/** 21 个，正是后续所有判据要读的对象）；
#            (b) 所有"零命中期望"判据在**被删空的文件**上**静默通过**（如 W8 输出 PASS 而对象已不存在）
#                ⇒ 这不是"跑出红灯"，而是**假绿灯**，比漏测更危险。
#          本器修法（三件一起做，缺一不可）：① 读文件 helper 改名 `Read-SrcText`，并加**启动自检**
#          （任何 helper 若被解析成 Alias 就 throw，不再靠人记住别名表）；② helper 内"存在 + 非空"双断言
#          （文件没了 → 立刻 throw，不再返回空串让判据静默通过）；③ W8 增加**正向对照**，禁止空对象上的空洞 PASS。
#          v1 **不追改**（历史留档已入库）：v1 与 v2 初稿均标注为**不可运行**，只在档案里作为判据文本引用。
#   (修-5) W3 计数口径修正（2026-09-12 12:4x，**仍在修复泳道交付之前**，实测触发）：W3 的机械下限原写
#          "`\"format\"` 字面量 ≤1（单点定义）"，按**全文件**字符串出现次数计；实测=2 而判 FAIL——第二处
#          是修复后 javadoc 里**解释单点定义纪律时引用的** `{@code "format"}`（注释引用，不是第二处定义）。
#          修订：只在**非注释行**中计数（跳过 `*`/`//`/`/*` 开头的行），并把"总次数 / 代码行次数"一并打印，
#          由人工读证确认第二处确实在注释里。理由：判据意图是"单点定义"，不是"全文只许出现一次字符串"；
#          旧口径会把"越解释越红"变成一条惩罚写注释的规则。此修订**不放松实质要求**：代码行仍必须 ≤1。
#   (修-6) W12 **解析缺陷修复**（2026-09-12 12:4x；**首次对真实 Maven 日志运行后实测触发**）：
#          W12 原判据按 `^Tests run: \d+, Failures` 逐行匹配"合计行"，而 **Maven surefire 的合计行带
#          `[INFO] ` 前缀**（实测原文：`[INFO] Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`，
#          该行行尾为 CRLF，末字符码=13）⇒ 原正则**永远匹配不到任何真实日志**，W12 在真日志上一律判 FAIL。
#          这不是"跑出红灯"，而是**判据写错了被测对象的形状**造成的**假红灯**——与 (修-4) 的假绿灯互为镜像
#          （假绿灯＝在空对象上通过；假红灯＝在真对象上不通过）。
#          实测证据（同一份原始日志 `e2-final-20260912-123946.out.txt`，三种候选正则的命中数）：
#            `Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$` → 原文 0 / 去 CR 后 0
#            `\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)`   → 原文 20 / 去 CR 后 20
#            `(?m)^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$` → 原文 0 / 去 CR 后 1（CRLF）
#          修订三件：① 允许可选 `[INFO] ` 前缀；② 用"**不含** `-- in ` 后缀"把**合计行**与逐类行显式区分
#          （原判据"取最后一条含该模式的行"，形状一变就可能取到逐类行）；③ 增**正向对照**：逐类行计数 ≥15，
#          证明读到的确实是一份真实 surefire 日志、而不是残档或缺行文件。
#          **判据阈值一字未改**：≥111 且 Failures/Errors/Skipped 全 0 且含 `BUILD SUCCESS`。
#          v2 原样留档、**不追改**（同 (修-1)…(修-5) 纪律）；v2 对同一份日志的读数（17 PASS / W12 FAIL）
#          在验收补记里与 v3 读数一并报告，不掩盖。
# 口径：每条零命中期望都配**正向对照**（证明读的是对的文件、正则有检出能力）；
#      含"机械下限 + 人工读证"的条目在证据串里明说。
# 用法：pwsh -NoProfile -File verify-fix-v3.ps1 [-LogPath <E2日志>] [-BaselineCommit ceeddec]
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
# 【修-4】读文件 helper：名字不得与内置别名同名；存在 + 非空，缺一即 throw（绝不返回空串）。
function Read-SrcText([string]$p) {
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) {
        throw ('取证对象不存在（不是"空文件"，是文件没了）：' + $p)
    }
    $t = [IO.File]::ReadAllText($p, $enc)
    if ($t.Length -eq 0) { throw ('取证对象为空文件：' + $p) }
    return $t
}
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
# 【修-4】启动自检：helper 若被别名/外部命令遮蔽（PS 解析顺序 别名 > 函数 > cmdlet），立即拒绝运行。
# 位置纪律：必须放在**全部 helper 定义之后**——放前面会把"尚未定义"误报成"被遮蔽"（12:3x 实测踩过）。
foreach ($hn in @('Read-SrcText', 'Cnt', 'Check', 'SrcFingerprint', 'FingerDiff')) {
    $hc = Get-Command $hn -ErrorAction SilentlyContinue
    if ($null -eq $hc -or $hc.CommandType -ne 'Function') {
        $ct = '未定义'
        if ($null -ne $hc) { $ct = [string]$hc.CommandType }
        throw ('helper 被遮蔽，拒绝运行（PS 解析顺序：别名 > 函数 > cmdlet）：' + $hn + ' → ' + $ct)
    }
}

Write-Host '=== M1-9 ② 修复轮验收复核 v2（只读；判据先冻结）==='
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
    'adapter/ExternalOrder.java', 'adapter/ExternalRefund.java', 'adapter/ExternalUser.java',
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
Check 'W1' '修复变更面 ⊆ 允许清单（v2：含 3 个 DTO 同族文件）' ($outside.Count -eq 0) `
    ('工作区变更文件数=' + $stat.Count + '；清单外=' + (($outside | ForEach-Object { $_ }) -join ';' ) + '；清单=' + $allow.Count + ' 条')
$docStat = @(& git status --porcelain -- docs contract-specs)
$docBad = @($docStat | Where-Object { $_ -notmatch '8091-stdout\.log' -and $_ -notmatch 'verify-fix(-v2)?\.ps1' })
Check 'W1b' '修复未触碰 docs/** 与 contract-specs/**（豁免：8091 运行期日志＝F-26 既有残留；本复核器自身 verify-fix.ps1 / verify-fix-v2.ps1 尚未入库）' ($docBad.Count -eq 0) `
    ('docs/contract-specs 变更行=' + $docStat.Count + '；除豁免外=' + $docBad.Count + $(if ($docBad.Count -gt 0) { '；' + ($docBad -join ';') } else { '' }))

# ── W2 S1：订单状态只透出商城原词（别名后缀已删）────────────────────────────
$ad = Read-SrcText $adapter
$alias = Cnt $ad 'ORDER_STATE_ALIAS'
$aliasLit = Cnt $ad '"(CREATED|PAID|CANCELLED|NEW)"'
$pcRead = Cnt $ad 'readOrder'
Check 'W2' 'S1 已修：ORDER_STATE_ALIAS 与别名词字面量均为 0（正向对照 readOrder ≥1）' `
    (($alias -eq 0) -and ($aliasLit -eq 0) -and ($pcRead -ge 1)) `
    ('ORDER_STATE_ALIAS=' + $alias + '（应 0）；别名词字面量="(CREATED|PAID|CANCELLED|NEW)"=' + $aliasLit + '（应 0）；正向对照 readOrder=' + $pcRead + '（应 ≥1）')

# ── W3 S2：魔法串单点定义 + 报错点名 format（机械下限 + 人工读证）───────────
$fmtAll = Cnt $ad '"format"'
$fmtCode = 0
foreach ($l in ($ad -split [char]10)) {
    $tl = $l.Trim()
    if ($tl.StartsWith('*') -or $tl.StartsWith('//') -or $tl.StartsWith('/*')) { continue }
    $fmtCode += (Cnt $l '"format"')
}
$openV2 = Cnt $ad 'open-v2'
$fmtLines = @($ad -split [char]10 | Where-Object { $_ -match 'format' } | Select-Object -First 4)
Check 'W3' 'S2 机械下限：**代码行**中 `"format"` 字面量 ≤1（单点定义；注释引用不计，见修-5）；正向对照 open-v2 ≥1' `
    (($fmtCode -le 1) -and ($openV2 -ge 1)) `
    ('"format" 代码行字面量=' + $fmtCode + '（应 ≤1）；全文出现=' + $fmtAll + '（含注释引用，仅记录）；正向对照 open-v2=' + $openV2 + '（应 ≥1）；含 format 的行（人工读证，判断报错是否点名该键）=' + (($fmtLines | ForEach-Object { $_.Trim() }) -join ' || '))

# ── W4 S3：能力缺口的声明标注"未探测/静态声明"──────────────────────────────
$notProbed = Cnt $ad '(未探测|静态声明|未做探测)'
$absent = Cnt $ad 'ABSENT'
Check 'W4' 'S3 已修：能力缺口声明明说"未探测/静态声明"（正向对照 ABSENT ≥1）' `
    (($notProbed -ge 1) -and ($absent -ge 1)) `
    ('未探测/静态声明 命中=' + $notProbed + '（应 ≥1）；正向对照 ABSENT=' + $absent + '（应 ≥1）')

# ── W5 S4：引擎对商城词表零知识 ───────────────────────────────────────────
$engText = ''
Get-ChildItem -LiteralPath $engineDir -Recurse -File -Filter *.java | ForEach-Object { $engText += (Read-SrcText $_.FullName) }
$engVocab = Cnt $engText 'MallStatusVocabulary'
$engInst = Cnt $engText 'instanceof\s+MallStatusVocabulary'
$engPc = Cnt $engText 'operationRoutes'
Check 'W5' 'S4 已修：engine/** 不含 MallStatusVocabulary 及其 instanceof（正向对照 operationRoutes ≥1）' `
    (($engVocab -eq 0) -and ($engInst -eq 0) -and ($engPc -ge 1)) `
    ('engine/** 内 MallStatusVocabulary=' + $engVocab + '（应 0）；其 instanceof=' + $engInst + '（应 0）；正向对照 operationRoutes=' + $engPc + '（应 ≥1）')

# ── W6 S5：跨运行/跨目标隔离的行为级证据 ──────────────────────────────────
$testAll = ''
Get-ChildItem -LiteralPath $testJava -Recurse -File -Filter *.java | ForEach-Object { $testAll += (Read-SrcText $_.FullName) }
$cross = Cnt $testAll '跨运行'
Check 'W6' 'S5 行为级证据：存在断言"跨运行"隔离的用例（DisplayName 含 跨运行）' ($cross -ge 1) `
    ('测试树内 跨运行 命中=' + $cross + '（应 ≥1）')

# ── W7 S6：价格缺失响亮失败的行为级证据 ───────────────────────────────────
$loud = Cnt $testAll '价格缺失'
Check 'W7' 'S6 行为级证据：存在断言"价格缺失"响亮失败的用例（DisplayName 含 价格缺失）' ($loud -ge 1) `
    ('测试树内 价格缺失 命中=' + $loud + '（应 ≥1）')

# ── W8 S7：缺字段不再混入"未映射状态词"（修-4：加正向对照，禁空洞 PASS）────
$msw = Cnt $ad 'MISSING_STATE_WORD'
$cnSentence = Cnt $ad '商城未给 state 字段'
$sfm = Cnt $ad 'stateFieldMissing'
Check 'W8' 'S7 已修：缺字段标记独立（MISSING_STATE_WORD=0 或 中文说明=0，二者至少其一归零；正向对照 stateFieldMissing ≥1）' `
    ((($msw -eq 0) -or ($cnSentence -eq 0)) -and ($sfm -ge 1)) `
    ('MISSING_STATE_WORD=' + $msw + '；"(商城未给 state 字段)"=' + $cnSentence + '（至少其一应 0）；正向对照 stateFieldMissing=' + $sfm + '（应 ≥1，证明读到的确实是修复后的文件而不是空对象）')

# ── W9 W1/W2：严格断言取代截断式断言 ──────────────────────────────────────
$ta = Read-SrcText $testA
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
    $nowTxt = Read-SrcText (Join-Path $root ($rel -replace '/', '\'))
    $oldTxt = (@(& git show ($BaselineCommit + ':' + $rel)) -join [char]10)
    $nNow = Cnt $nowTxt 'assert[A-Za-z]*\('
    $nOld = Cnt $oldTxt 'assert[A-Za-z]*\('
    $weakCnt += (($rel -split '/')[-1] + ':' + $nOld + '→' + $nNow)
    if ($nNow -lt $nOld) { $weakCnt += 'FAIL' }
}
Check 'W14' '既有测试断言数不减少（防"改口径变相削弱"）' ($weakCnt -notcontains 'FAIL') `
    ('断言调用数（基线→工作区）：' + ($weakCnt -join '；'))

# ── W15（v2 新增）S1 同类面：DTO 不再自造状态值 + 运维话术点名 format 键 ────
$dtoDir = Join-Path $mainJava 'adapter'
$dtoAll = @(Get-ChildItem -LiteralPath $dtoDir -File -Filter *.java)
$assignUnknown = 0; $mentionUnknown = 0; $records = 0; $mentionLines = @()
foreach ($f in $dtoAll) {
    $t = Read-SrcText $f.FullName
    $assignUnknown += (Cnt $t '(\?|=)\s*"UNKNOWN"')
    $mentionUnknown += (Cnt $t '"UNKNOWN"')
    $records += (Cnt $t 'record ')
    if ($t -match '"UNKNOWN"') {
        $ln = 0
        foreach ($l in ($t -split [char]10)) { $ln++; if ($l -match '"UNKNOWN"') { $mentionLines += ($f.Name + ':' + $ln + ' ' + $l.Trim()) } }
    }
}
Check 'W15a' 'S1 同类面已收口：adapter/** 内无 `= "UNKNOWN"` / `? "UNKNOWN"` 造值（正向对照：文件数 ≥5 且含 record 的文件 ≥5）' `
    (($assignUnknown -eq 0) -and ($dtoAll.Count -ge 5) -and ($records -ge 5)) `
    ('造值赋值命中=' + $assignUnknown + '（应 0）；"UNKNOWN" 提及=' + $mentionUnknown + '（仅记录：注释里的禁令不算缺陷，由人工读证判定）' + $(if ($mentionLines.Count -gt 0) { '；提及处=' + ($mentionLines -join ' || ') } else { '' }) + '；正向对照 adapter/** .java 文件数=' + $dtoAll.Count + '（应 ≥5）、含 record 的文件累计出现=' + $records + '（应 ≥5）')
$newSem = Cnt $testAll '(未给状态|缺状态)'
Check 'W15b' 'S1 同类面行为级证据：存在"商城未给/缺状态字段"时不造词的用例（DisplayName 含 未给状态/缺状态）' ($newSem -ge 1) `
    ('测试树内 未给状态|缺状态 命中=' + $newSem + '（应 ≥1）')
$keyUse = Cnt $ad 'CONFIG_FORMAT_KEY'
$valUse = Cnt $ad 'CONFIG_FORMAT_VALUE'
Check 'W15c' 'S2 后半：运维话术点名格式声明（机械下限：键/值常量各被引用 ≥2；是否真出现在话术文本里＝人工读证）' `
    (($keyUse -ge 2) -and ($valUse -ge 2)) `
    ('CONFIG_FORMAT_KEY 引用=' + $keyUse + '（应 ≥2）；CONFIG_FORMAT_VALUE 引用=' + $valUse + '（应 ≥2）')

# ── W12 E2 日志合计行（【修-6】：允许 `[INFO] ` 前缀；必须取到"合计行"而非逐类行）──────
if ($LogPath -ne '' -and (Test-Path -LiteralPath $LogPath)) {
    $lg = Read-SrcText $LogPath
    $logLines = @($lg -split [char]10)
    $classLines = @($logLines | Where-Object { $_ -match 'Tests run: \d+, Failures' -and $_ -match '-- in ' })
    $sum = @($logLines | Where-Object { $_ -match 'Tests run: \d+, Failures' -and $_ -notmatch '-- in ' } | Select-Object -Last 1)
    $okSum = ($sum.Count -eq 1 -and $sum[0] -match 'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0')
    $n = 0; if ($okSum) { $n = [int]([regex]::Match($sum[0], 'Tests run: (\d+)').Groups[1].Value) }
    Check 'W12' 'E2 合计行：≥111 且 0 失败/0 错误/0 跳过，且 BUILD SUCCESS（【修-6】允许 [INFO] 前缀；正向对照：逐类行 ≥15）' `
        ($okSum -and $n -ge 111 -and $lg -match 'BUILD SUCCESS' -and $classLines.Count -ge 15) `
        ('日志=' + $LogPath + '；合计行=[' + ($sum -join '') + ']；合计用例数=' + $n + '（应 ≥111）；逐类行=' + $classLines.Count + '（正向对照，应 ≥15）；BUILD SUCCESS=' + ($lg -match 'BUILD SUCCESS'))
}
else {
    Check 'W12' 'E2 合计行' $false ('未提供 -LogPath 或日志不存在：' + $LogPath + '（按未取证计，不通过）')
}

# ── W0 结论：复核期间 src 未被并发改动（含"文件被删"这一最坏情形）──────────
$after = SrcFingerprint
$drift = @(FingerDiff $before $after)
Check 'W0' '复核期间 src 指纹未变（否则本次修复轮结论无效）' ($drift.Count -eq 0) `
    ('起点文件数=' + $before.Count + ' → 终点文件数=' + $after.Count + '；漂移=' + $drift.Count + '（应 0）' + $(if ($drift.Count -gt 0) { '；' + ($drift -join ';') } else { '' }))

Write-Host ''
Write-Host ('=== 汇总：PASS=' + $script:pass + ' FAIL=' + $script:fail + '（只读；FAIL 一律计不通过）===')
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
