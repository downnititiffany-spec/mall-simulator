<#
  CT 批次（CT-1/2/3 ＋ 一次性升版）主检出侧集成校验
  ------------------------------------------------------------------
  用途：把 CT 补丁集成进主检出后，由**父级**独立复算本批的全部关键主张——
        不采信泳道自述，全部量都从文件/JSON/git 现算。

  设计纪律（沿用本仓既有约定）：
    · 每条断言都打印「应然 vs 实际」，任何 FAIL 即 exit 1；
    · 零命中类断言必须带**正向对照**（防守卫空转）；
    · 末段做「断言条数自检」（陷阱 #27）；
    · 只读：不写任何被验对象；仅可选写一份日志到 .verify\。

  用法：
    # 集成前（预审）：补丁面断言应全 PASS，契约面断言应 FAIL（证明脚本非空转）
    pwsh -File docs/acceptance/ct-batch-20260912/tools/ct-integration-verify.ps1 -Phase PreApply

    # 集成后：全部 PASS
    pwsh -File docs/acceptance/ct-batch-20260912/tools/ct-integration-verify.ps1 -Phase PostApply `
         -BaselineTests 197 -AfterTests 197
#>
[CmdletBinding()]
param(
  [ValidateSet('PreApply', 'PostApply')][string]$Phase = 'PostApply',
  [string]$Repo = '',
  [string]$Patch = 'D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\ct-batch.patch',
  [int]$BaselineTests = 0,
  [int]$AfterTests = 0,
  [int]$LedgerBaselineRows = 113,
  [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

if (-not $Repo) { $Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path }
$Repo = (Resolve-Path $Repo).Path
Set-Location $Repo

# ── 日志落盘（陷阱：证据脚本"声明了 -LogPath 却不写文件"= 未取证）────────────────
# 用 Start-Transcript 零侵入捕获 Write-Host（Information 流）的全部输出；结尾做**日志自检**：
# 回读日志、数其中的 [PASS]/[FAIL] 条数，必须与脚本内计数一致，否则日志不可信。
$script:logFile = ''
if ($LogPath -ne '') {
  $script:logFile = $(if ([IO.Path]::IsPathRooted($LogPath)) { $LogPath } else { Join-Path $Repo $LogPath })
  $logDir = Split-Path -Parent $script:logFile
  if ($logDir -and -not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
  try { Start-Transcript -LiteralPath $script:logFile -Force | Out-Null }
  catch { $script:logFile = ''; Write-Host ('  !! 日志落盘失败（Start-Transcript）：' + $_.Exception.Message) }
}

$script:pass = 0; $script:fail = 0; $script:total = 0
function Assert([string]$name, [bool]$ok, [string]$expect, [string]$actual) {
  $script:total++
  if ($ok) { $script:pass++; Write-Host ("  [PASS] {0}" -f $name) }
  else { $script:fail++; Write-Host ("  [FAIL] {0}`n         应然: {1}`n         实际: {2}" -f $name, $expect, $actual) -ForegroundColor Red }
}
function Sha256Lf([string]$absPath) {
  $raw = [IO.File]::ReadAllBytes($absPath)
  $txt = [Text.Encoding]::UTF8.GetString($raw) -replace "`r`n", "`n"
  $sha = [Security.Cryptography.SHA256]::Create()
  ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($txt)) | ForEach-Object { $_.ToString('X2') }) -join ''
}

Write-Host ("=== CT 集成校验（阶段 {0}）===" -f $Phase)
Write-Host ("仓库 = {0}" -f $Repo)

# ── A. 补丁面（集成前即可判） ─────────────────────────────────────────
Write-Host "`n--- A. 补丁面 ---"
$patchOk = Test-Path -LiteralPath $Patch
Assert 'A1 补丁文件存在' $patchOk '存在' $(if ($patchOk) { '存在' } else { '缺失：' + $Patch })
if (-not $patchOk) { Write-Host '补丁缺失，终止'; exit 1 }

# A0：补丁**自身行尾**与**可落地性**（只读）。实测教训（2026-09-12）：泳道用 PowerShell 重定向导出 `git diff`，
# 补丁文件被写成 CRLF 行尾（260 CRLF / 0 纯 LF），而本检出是 LF ⇒ `git apply --check` **8/8 文件全部 does not apply**，
# 且 git 把 CR 渲染成行尾 `?`、指向 hunk 上下文 ⇒ 极易误诊为"内容对不上"。内容其实完全正确：仅规范化行尾后即 8/8 通过。
# 故本条必须在 A 段强制：不合格的补丁形态不得进入集成。
$pTxt = [IO.File]::ReadAllText($Patch)
$crlfN = ([regex]::Matches($pTxt, "`r`n")).Count
$lfN = ([regex]::Matches($pTxt, "(?<!`r)`n")).Count
Assert 'A0a 补丁文件自身行尾 = 纯 LF（CRLF 行数必须为 0）' ($crlfN -eq 0) 'CRLF 0 行 / 纯 LF > 0 行' ('CRLF {0} 行 / 纯 LF {1} 行' -f $crlfN, $lfN)
$applyArgs = @('-C', $Repo, 'apply', '--check')
$applyExpect = 'exit 0 / error 0 条'
if ($Phase -eq 'PostApply') {
  # 落地后必须反过来查：正向 --check 必然失败（补丁已在树上）。反向 --check -R 通过 = 工作区确实就是补丁后的字节，
  # 这比"正向检查通过"更强：它证明已落地的改动与本补丁逐 hunk 一致（既没漏落也没多落）。
  $applyArgs += '-R'; $applyExpect = 'exit 0（反向）/ error 0 条'
}
$applyOut = & git @applyArgs $Patch 2>&1
$applyCode = $LASTEXITCODE
$applyErr = @($applyOut | Where-Object { $_ -match '^error:' }).Count
Assert ('A0b 补丁与本检出{' + $(if ($Phase -eq 'PreApply') { '可落地：正向 git apply --check' } else { '已落地一致：反向 git apply --check -R' }) + '，只读}') ($applyCode -eq 0) $applyExpect ('exit {0} / error {1} 条' -f $applyCode, $applyErr)

$plines = [IO.File]::ReadAllLines($Patch)
$files = @($plines | Where-Object { $_ -match '^diff --git ' } | ForEach-Object { ($_ -replace '^diff --git a/', '') -replace ' b/.*$', '' })
$expectFiles = @(
  'analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationScriptTest.java',
  'analytics-server/platform-common/src/main/java/com/graduation/analytics/contracts/EventContract.java',
  'analytics-server/platform-common/src/test/java/com/graduation/analytics/contracts/CanonicalEventSchemaParityTest.java',
  'contract-specs/README.md',
  'contract-specs/VERSION',
  'contract-specs/schemas/canonical-event.v1.schema.json',
  'docs/contracts/event-contract.md',
  'scripts/check-bare-anchors.ps1'
)
$diffFiles = @(Compare-Object ($files | Sort-Object) ($expectFiles | Sort-Object))
Assert 'A2 补丁文件集合 = 施工单 §2 的 8 个受控文件' ($diffFiles.Count -eq 0) '集合相同' (($files | Sort-Object) -join ' | ')

$redline = @('spark-jobs/', 'mall-simulator/', 'tests/golden-dataset/', 'docs/acceptance/', 'warehouse-namespace.v1.json')
$hit = @($files | Where-Object { $f = $_; @($redline | Where-Object { $f -like ('*' + $_ + '*') }).Count -gt 0 })
Assert 'A3 补丁不改红线面（spark-jobs/mall-simulator/黄金数据集/证据目录/冻结契约本体）' ($hit.Count -eq 0) '0 个' ($hit -join ' | ')

# A4：8 个受控文件在检出中必须**全程 LF**（CR 字节 0）。落地只接受 LF 补丁，若补丁的"新增行"带 CR，
# 落盘结果就会混入 CRLF（指纹/仓库 blob 口径随即被破坏）。本条与 A0a 一起构成行尾完整性闸。
$crFiles = @()
foreach ($f in $expectFiles) {
  $fp = Join-Path $Repo ($f -replace '/', '\')
  if (Test-Path -LiteralPath $fp) {
    $n = 0; foreach ($b in [IO.File]::ReadAllBytes($fp)) { if ($b -eq 13) { $n++ } }
    if ($n -gt 0) { $crFiles += ('{0}({1})' -f $f, $n) }
  } else { $crFiles += ($f + '(缺失)') }
}
Assert 'A4 8 个受控文件在检出中全程 LF（CR 字节 0）' ($crFiles.Count -eq 0) '0 个含 CR' ($crFiles -join ' | ')

# A5：改动集合必须**恰好**是这 8 个受控文件（落地前 = 受控面 0 改动；落地后 = 恰 8 个 M、0 删除）。
# 注意：本闸按设计**只在提交前**成立 —— 一旦把改动提交，工作区改动集合即为空，A5 会按设计判红。
$porcelain = @(git -C $Repo -c core.quotepath=false status --porcelain)
$mods = @($porcelain | Where-Object { $_ -match '^ ?M ' } | ForEach-Object { ($_ -replace '^ ?M\s+', '') -replace '"', '' })
$dels = @($porcelain | Where-Object { $_ -match '^ ?D ' })
if ($Phase -eq 'PreApply') {
  $touched = @($mods | Where-Object { $expectFiles -contains $_ })
  Assert 'A5 落地前：受控面在主检出中未被改动（基线干净）' (($touched.Count -eq 0) -and ($dels.Count -eq 0)) '受控面 0 改动 / 0 删除' ('受控面改动 {0} / 删除 {1}' -f $touched.Count, $dels.Count)
} else {
  $miss = @($expectFiles | Where-Object { $mods -notcontains $_ })
  $extra = @($mods | Where-Object { $expectFiles -notcontains $_ })
  Assert 'A5 落地后：改动集合恰为 8 个受控文件（无漏改/无多改/无删除）' (($miss.Count -eq 0) -and ($extra.Count -eq 0) -and ($dels.Count -eq 0)) '恰 8 个 M / 0 删除' ('漏改 {0} / 多改 {1} / 删除 {2}' -f $miss.Count, $extra.Count, $dels.Count)
}

# ── B. 契约面（集成后判定；PreApply 阶段应当 FAIL） ────────────────────
Write-Host "`n--- B. 契约面（现算）---"
$versionAbs = Join-Path $Repo 'contract-specs\VERSION'
$vTxt = $(if (Test-Path -LiteralPath $versionAbs) { [IO.File]::ReadAllText($versionAbs) } else { '' })
$vRaw = $(if (Test-Path -LiteralPath $versionAbs) { [IO.File]::ReadAllBytes($versionAbs) } else { @() })
$vLf = ($vTxt -replace "`r`n", "`n")
Assert 'B1a VERSION 内容 = "contract-specs 2.2.0"' ((($vTxt -replace "`r`n", "`n").TrimEnd()) -eq 'contract-specs 2.2.0') 'contract-specs 2.2.0' ($vTxt.Trim())
Assert 'B1b VERSION 的 LF 形态 = 21 B（§14 登记口径）' ($vLf.Length -eq 21) '21' ([string]$vLf.Length)
Assert 'B1c VERSION 的 LF sha256 = EB175583…（README §14 登记值）' ((Sha256Lf $versionAbs) -like 'EB175583*') 'EB175583…' (Sha256Lf $versionAbs)

$wnAbs = Join-Path $Repo 'contract-specs\specs\warehouse-namespace.v1.json'
Assert 'B2 冻结契约 warehouse-namespace.v1.json 指纹仍为 463D9DC3…（本批不得动）' ((Sha256Lf $wnAbs) -like '463D9DC3*') '463D9DC3…' (Sha256Lf $wnAbs)

$scAbs = Join-Path $Repo 'contract-specs\schemas\canonical-event.v1.schema.json'
$sc = [IO.File]::ReadAllText($scAbs) | ConvertFrom-Json
$ssNames = @($sc.properties.source_system.PSObject.Properties.Name)
Assert 'B3a source_system 节点不含 const（CT-1 / D-061）' (-not ($ssNames -contains 'const')) '无 const' ('含：' + ($ssNames -join ','))
Assert 'B3b 正向对照：schema_version 仍锁 const = 1.0（证明 B3a 非空转）' ($sc.properties.schema_version.const -eq '1.0') '1.0' ([string]$sc.properties.schema_version.const)
Assert 'B3c source_system 仍受形状约束（type=string ∧ minLength≥1）' ($sc.properties.source_system.type -eq 'string' -and [int]$sc.properties.source_system.minLength -ge 1) 'string 且 minLength≥1' ('type=' + $sc.properties.source_system.type + ' minLength=' + $sc.properties.source_system.minLength)

$itNode = $sc.'$defs'.order_created.properties.items
$br = @($itNode.oneOf | Where-Object { $_ })
$brTypes = @($br | ForEach-Object { $_.type })
Assert 'B4a order_created.items 有 oneOf 且恰 2 分支（CT-3 / D-063）' ($br.Count -eq 2) '2' ([string]$br.Count)
Assert 'B4b 两分支 type 分别为 array 与 string（加性接受，规范形态仍为数组）' (($brTypes -contains 'array') -and ($brTypes -contains 'string')) 'array + string' ($brTypes -join '/')
Assert 'B4c 数组分支保留原 items 子定义（原文不动）' (@($br[0].PSObject.Properties.Name) -contains 'items') '含 items' (@($br[0].PSObject.Properties.Name) -join ',')

$ecAbs = Join-Path $Repo 'analytics-server\platform-common\src\main\java\com\graduation\analytics\contracts\EventContract.java'
$ec = $(if (Test-Path -LiteralPath $ecAbs) { [IO.File]::ReadAllText($ecAbs) } else { '' })
$ssHits = ([regex]::Matches($ec, 'SOURCE_SYSTEM')).Count
$svHits = ([regex]::Matches($ec, 'SCHEMA_VERSION')).Count
Assert 'B5a 平台侧常量 SOURCE_SYSTEM 已退休（0 命中）' ($ssHits -eq 0) '0' ([string]$ssHits)
Assert 'B5b 正向对照：SCHEMA_VERSION 仍存在（≥1 命中，证明扫描非空转）' ($svHits -ge 1) '≥1' ([string]$svHits)

$rdAbs = Join-Path $Repo 'contract-specs\README.md'
$rd = [IO.File]::ReadAllText($rdAbs)
Assert 'B6a README §10 历史指纹行仍在（append-only，未删旧值）' ($rd -match '9784177F') '含 9784177F…' '缺失'
Assert 'B6b README §14 已登记新值 EB175583… 与 raw 5D349AFB…' (($rd -match 'EB175583') -and ($rd -match '5D349AFB')) '两者都在' '缺失'

Write-Host "`n--- B7 裸锚点守卫实跑（只读模式，退出码 0 = PASS）---"
$guard = Join-Path $Repo 'scripts\check-bare-anchors.ps1'
$guardOk = Test-Path -LiteralPath $guard
Assert 'B7a 守卫脚本存在' $guardOk '存在' $(if ($guardOk) { '存在' } else { '缺失' })
if ($guardOk) {
  $out = & pwsh -NoProfile -ExecutionPolicy Bypass -File $guard 2>&1
  $code = $LASTEXITCODE
  $passLine = @($out | Where-Object { $_ -match '结果 = PASS' })
  $ledgerRows = 0
  if ($passLine.Count -eq 1 -and $passLine[0] -match '台账 (\d+) 行 ≤ 基线 (\d+)') { $ledgerRows = [int]$Matches[1]; $ledgerBase = [int]$Matches[2] } else { $ledgerBase = 0 }
  Assert 'B7b 守卫退出码 = 0' ($code -eq 0) '0' ([string]$code)
  Assert 'B7c 守卫自报 结果 = PASS（恰 1 行）' ($passLine.Count -eq 1) '1 行' ([string]$passLine.Count)
  Assert 'B7d 台账行数 ≤ 基线（只减不增）' ($ledgerBase -gt 0 -and $ledgerRows -le $ledgerBase) ('≤ ' + $LedgerBaselineRows) ('台账=' + $ledgerRows + ' 基线=' + $ledgerBase)
  Write-Host ('         守卫自报：' + ($passLine -join ' '))
}

# ── C. E1-a 计数（由调用方传入；本脚本不跑 Maven，避免与其它构建并发） ──
if ($BaselineTests -gt 0 -or $AfterTests -gt 0) {
  Write-Host "`n--- C. E1-a 测试计数对比 ---"
  Assert 'C1 改动后测试数 ≥ 改动前基线' ($AfterTests -ge $BaselineTests) ('≥ ' + $BaselineTests) ([string]$AfterTests)
} else {
  Write-Host "`n--- C. E1-a 计数：未传入（-BaselineTests / -AfterTests），本段跳过 ---"
}

# ── D. 条数自检（陷阱 #27） ───────────────────────────────────────────
Write-Host "`n--- D. 条数自检 ---"
# 逐段点名（防漏跑/多跑）：A0a/b=2；A1-A3=3；A4=1；A5=1；B1a/b/c=3；B2=1；B3a/b/c=3；B4a/b/c=3；
# B5a/b=2；B6a/b=2；B7a/b/c/d=4；C1=1（仅当传入计数时）；D1=1（本条）
$cRan = ($BaselineTests -gt 0 -or $AfterTests -gt 0)
$expectedAssertions = 26 + $(if ($cRan) { 1 } else { 0 })
# 口径声明：末段 E1（日志自检）**不计入**本计数——它在 Stop-Transcript 之后判定，其输出不在日志内，
# 若计入会使"日志内条数 = 脚本计数"这条自检恒不成立。E1 的可信度由它自己打印的应然/实际两行证明。
# 注意 +1：本行断言在 Assert 内部自增**之前**求值，故比较量是「已跑条数 + 本条」
Assert 'D1 断言总条数 = 脚本声明值（防漏跑/多跑）' (($script:total + 1) -eq $expectedAssertions) ([string]$expectedAssertions) ([string]($script:total + 1))

Write-Host ("`n=== 汇总：断言 {0} 条（应然 {1}）；PASS {2} / FAIL {3} ===" -f $script:total, $expectedAssertions, $script:pass, $script:fail)
$exitCode = 0
if ($script:fail -gt 0) {
  Write-Host ('  ' + $(if ($Phase -eq 'PreApply') { 'PreApply 阶段出现 FAIL 属**预期**（契约尚未改）——仅 A 段须全 PASS。' } else { '存在 FAIL，集成不合格。' }))
  $exitCode = 1
} else {
  Write-Host '  全部通过。'
}

# ── E. 日志自检（防"日志截断/没落盘"被当成证据）────────────────────────
if ($script:logFile -ne '') {
  Stop-Transcript | Out-Null
  $logTxt = [IO.File]::ReadAllText($script:logFile)
  $pIn = ([regex]::Matches($logTxt, '\[PASS\]')).Count
  $fIn = ([regex]::Matches($logTxt, '\[FAIL\]')).Count
  $logOk = ($pIn -eq $script:pass) -and ($fIn -eq $script:fail)
  Write-Host ("`n--- E. 日志自检 ---")
  Write-Host ('  [{0}] E1 日志已落盘且条数与计数一致' -f $(if ($logOk) { 'PASS' } else { 'FAIL' }))
  Write-Host ('         应然: 日志内 PASS {0} / FAIL {1}' -f $script:pass, $script:fail)
  Write-Host ('         实际: 文件内 PASS {0} / FAIL {1}' -f $pIn, $fIn)
  Write-Host ('         日志 = {0}（{1} B / sha256 {2}）' -f $script:logFile, (Get-Item -LiteralPath $script:logFile).Length, (Get-FileHash -LiteralPath $script:logFile -Algorithm SHA256).Hash)
  if (-not $logOk) { $exitCode = 1 }
}
exit $exitCode
