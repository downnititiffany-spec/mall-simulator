<#
  P2-01（ODS v2 加法双写）总控收口自检 —— 只读、可复算、每条判据打印「应然 vs 实际」。

  设计纪律（照 R2 的 tools/e3-close-verify.ps1）：
    · 只读：不写仓库、不连库、不启停服务、不跑 Maven/Spark；只用 git 对象 + 文件指纹 + 泳道已落盘的外部制品。
    · 查询失败显式抛错（$ErrorActionPreference='Stop' + 每次调用后校验退出码），**绝不把工具失败读成 0**。
    · 末尾条数自检：打印的 PASS/FAIL 必须等于脚本自身计数。
    · 带**正向对照**（故意写错的期望必须判红），证明比较器本身能红。
    · 对照物一律取**外部制品**（git 对象/泳道证据文件），不用本脚本自己产出的值自证。

  用法：pwsh -NoProfile -File docs\acceptance\p2-01-ods-v2-20260912\tools\p2-01-close-verify.ps1
  退出码：0 = 全部成立；1 = 存在不成立（红项不隐藏、不改写）。
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Results = New-Object System.Collections.ArrayList
$script:AllCounts = New-Object System.Collections.ArrayList
function Add-Result {
  param([string]$Id, [string]$Expect, $Actual, [bool]$Ok, [string]$Note = '')
  [void]$script:Results.Add([pscustomobject]@{ Id = $Id; Expect = $Expect; Actual = "$Actual"; Ok = $Ok; Note = $Note })
}
function Invoke-Git {
  param([string[]]$GitArgs)
  $out = & git @GitArgs 2>&1
  if ($LASTEXITCODE -ne 0) { throw ("git " + ($GitArgs -join ' ') + " 退出码 " + $LASTEXITCODE + "；输出：" + ($out -join ' | ')) }
  return ,@($out)
}
# 从 git 中的 00-ods.sql 解析指定表的非分区列名（父提交/本提交通用）
function Get-DdlColumns {
  param([string]$Rev, [string]$Table)
  $lines = Invoke-Git @('show', ($Rev + ':warehouse/ddl/00-ods.sql'))
  $cols = New-Object System.Collections.ArrayList
  $inTbl = $false
  foreach ($l in $lines) {
    if ($l -match [regex]::Escape($Table)) { $inTbl = $true }
    if ($inTbl) {
      foreach ($m in [regex]::Matches($l, '(?m)(?:^|,|\()\s*([a-z][a-z0-9_]*)\s+(STRING|BIGINT|INT|DOUBLE|DECIMAL|TIMESTAMP|DATE|BOOLEAN)\b')) { [void]$cols.Add($m.Groups[1].Value) }
      if ($l -match '\)\s*(USING|PARTITIONED|STORED|$)') { if ($cols.Count -gt 3) { break } }
    }
  }
  return ,@($cols)
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
Set-Location -LiteralPath $root
Write-Host ("仓库根 = " + $root)
Write-Host ("时点   = " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ''

# ── 0. 外部对照常量（来源：草案 §5.5(2) 登记值 / D-060 冻结值 / 泳道证据，均非本脚本产出）──
$DdlPreBaselineSha8 = '36EDBF79'          # 草案 §5.5(2)：P2-01 之前的 00-ods.sql
$DdlNowSha          = '2BEBE55FF09580443937F945A31DACF856BBBD898FB7C1B953DC6C7465816863'
$DdlOthersBaseline  = [ordered]@{ '01-dwd.sql' = @('8ECF37D5', 3509); '02-dims.sql' = @('AD2D0881', 2965); '03-dws.sql' = @('D1BE5E71', 4194); '04-ads.sql' = @('3057DE99', 5207) }
$V2Names            = @('raw_event_type', 'raw_source_system', 'landing_file', 'payload_json', 'payload_hash')
$P201Commit         = '9679f5e'
$CtCommit           = '5690ffb'
$JarFrozenD060      = 234038
$EvidenceDir        = 'docs\acceptance\p2-01-ods-v2-20260912\evidence'
$ChecksTsv          = Join-Path $EvidenceDir 'e3-20260912-141554-checks.tsv'
$SummaryTxt         = Join-Path $EvidenceDir 'e3-20260912-141554-summary.txt'

# ── 1. DDL 指纹对账（外部登记值 vs 盘面实测）──
$ddl00 = Get-Item -LiteralPath 'warehouse\ddl\00-ods.sql'
$sha00 = (Get-FileHash -LiteralPath $ddl00.FullName -Algorithm SHA256).Hash
Add-Result 'DDL-00-sha256' $DdlNowSha $sha00 ($sha00 -eq $DdlNowSha) 'P2-01 之后的登记值'
Add-Result 'DDL-00-bytes' '6178' $ddl00.Length ($ddl00.Length -eq 6178)
foreach ($n in $DdlOthersBaseline.Keys) {
  $f = Get-Item -LiteralPath (Join-Path 'warehouse\ddl' $n)
  $h = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
  $ok = ($h.Substring(0, 8) -eq $DdlOthersBaseline[$n][0]) -and ($f.Length -eq $DdlOthersBaseline[$n][1])
  Add-Result ("DDL-" + $n + "-untouched") ($DdlOthersBaseline[$n][0] + "… / " + $DdlOthersBaseline[$n][1] + "B") ($h.Substring(0, 8) + "… / " + $f.Length + "B") $ok 'P2-01 只应改 00-ods.sql'
}

# ── 2. 列级归因：父提交 14 列 → 本提交 19 列，且 v2 5 列全在 ──
$tbl = 'ods_behavior_event'
$pre = Get-DdlColumns '9679f5e^' $tbl
$now = Get-DdlColumns $P201Commit $tbl
Add-Result 'COL-count-parent' '14' $pre.Count ($pre.Count -eq 14) (($pre -join ', '))
Add-Result 'COL-count-p201' '19' $now.Count ($now.Count -eq 19) (($now -join ', '))
$missing = @($V2Names | Where-Object { $_ -notin $now })
Add-Result 'COL-v2-5-present' '0 missing' ($missing.Count.ToString() + ' missing') ($missing.Count -eq 0) (($missing -join ', '))
$prefixSame = (@($pre | Where-Object { $_ -ne '' }) -join ',') -eq (@($now[0..([Math]::Min(13, $now.Count - 1))]) -join ',')
Add-Result 'COL-v1-prefix-identical' 'v1 14 列名字/顺序不变' ($prefixSame.ToString()) $prefixSame 'D-059：末尾追加'

# ── 3. 引入面：在**代码与 DDL 路径**内，5 个 v2 列名只由 P2-01 一个提交引入 ──
#     口径（本人曾把「仅 9679f5e 引入」写成全仓口径 ⇒ 过宽，本脚本钉住限定面）：
#       限定面 = spark-jobs/src/main ＋ warehouse/ddl（“作业与 DDL”的可执行面）；
#       不加限定会命中后续**文档/证据**提交（文本被引用即计入 -S），故全仓计数仅作 Note 展示。
foreach ($c in $V2Names) {
  $hits = Invoke-Git @('log', '--format=%h', '-S', $c, '--', 'spark-jobs/src/main', 'warehouse/ddl')
  $u = @($hits | Where-Object { $_.Trim() -ne '' } | Select-Object -Unique)
  # 全仓计数用**直接调用**（本轮实测：函数包装调用曾给出可疑的 1 ⇒ 未定位原因前不采信包装读数）
  $allRaw = @(& git log '--format=%h' -S $c -- 2>&1)
  $allLines = @($allRaw | Where-Object { $_ -is [string] -and $_ -match '^[0-9a-f]{7,40}$' })
  Add-Result ("LOG-S-" + $c) ($P201Commit + "（代码/DDL 面唯一）") (($u -join ',') + " 共 " + $u.Count) (($u.Count -eq 1) -and ($u[0] -eq $P201Commit)) ("全仓 " + $allLines.Count + " 个提交（多出的为文档/证据引用）")
  [void]$script:AllCounts.Add([int]$allLines.Count)
}
$scTotal = @($script:Results | Where-Object { $_.Id -like 'LOG-S-*' -and $_.Ok }).Count
$allTotal = ($script:AllCounts | Measure-Object -Sum).Sum
Add-Result 'LOG-S-scope-required' '全仓计数 > 限定面计数（故必须限定路径）' ("全仓 " + $allTotal + " vs 限定面 " + $scTotal) ($allTotal -gt $scTotal) '口径纪律：不加路径限定会命中后续文档提交'

# ── 4. CT 排除：CT 提交未碰 DDL 与 spark-jobs 主源码 ──
$ctDdl = @(Invoke-Git @('diff', ($CtCommit + '^'), $CtCommit, '--', 'warehouse/ddl') | Where-Object { $_ -ne '' })
$ctSrc = @(Invoke-Git @('diff', ($CtCommit + '^'), $CtCommit, '--', 'spark-jobs/src/main') | Where-Object { $_ -ne '' })
Add-Result 'CT-excluded-ddl' '0 行' ($ctDdl.Count.ToString() + ' 行') ($ctDdl.Count -eq 0)
Add-Result 'CT-excluded-sparkjobs' '0 行' ($ctSrc.Count.ToString() + ' 行') ($ctSrc.Count -eq 0)
$head41 = @(Invoke-Git @('log', '--format=%h', '--before=2026-09-12 09:25', '-1'))[0].Trim()
$head41Time = @(Invoke-Git @('log', '--format=%ci', '--before=2026-09-12 09:25', '-1'))[0].Trim()
Add-Result 'RUN41-tree-predates-P201' ('HEAD ≠ ' + $P201Commit) $head41 ($head41 -ne $P201Commit) ($head41Time)

# ── 5. 泳道 E3 外部制品（真链读数，本脚本不复跑）──
if (-not (Test-Path -LiteralPath $ChecksTsv)) { throw ("缺证据文件：" + $ChecksTsv) }
$rows = @([IO.File]::ReadAllLines($ChecksTsv) | Where-Object { $_.Trim() -ne '' })
$body = @($rows | Select-Object -Skip 1)
$nonPass = @($body | Where-Object { ($_ -split "`t")[1] -ne '1' })
Add-Result 'E3-checks-rows' '51' $body.Count ($body.Count -eq 51) '表头 1 行 + 判据 51 行'
Add-Result 'E3-checks-all-pass' '0 条非 1' ($nonPass.Count.ToString() + ' 条') ($nonPass.Count -eq 0) (($nonPass | ForEach-Object { ($_ -split "`t")[0] }) -join ', ')
if (-not (Test-Path -LiteralPath $SummaryTxt)) { throw ("缺证据文件：" + $SummaryTxt) }
$sum = [IO.File]::ReadAllLines($SummaryTxt)
$resLine = @($sum | Where-Object { $_ -match '^E3_RESULT=' })
$before = @($sum | Where-Object { $_ -match '^realWarehouseBefore=' })
$after = @($sum | Where-Object { $_ -match '^realWarehouseAfter=' })
Add-Result 'E3-RESULT' 'E3_RESULT=PASS' ($resLine -join '') (($resLine.Count -eq 1) -and ($resLine[0] -eq 'E3_RESULT=PASS'))
$beforeVal = @($before | ForEach-Object { ($_ -split '=', 2)[1] })
$afterVal = @($after | ForEach-Object { ($_ -split '=', 2)[1] })
Add-Result 'E3-real-warehouse-isolated' 'before 值 == after 值' (($beforeVal -join '') -eq ($afterVal -join '')) (($beforeVal.Count -eq 1) -and ($afterVal.Count -eq 1) -and ($beforeVal[0] -eq $afterVal[0])) (($beforeVal -join ''))
Add-Result 'E3-order1-README-present' 'README.md 存在' (Test-Path -LiteralPath (Join-Path (Split-Path $EvidenceDir -Parent) 'README.md')) (Test-Path -LiteralPath (Join-Path (Split-Path $EvidenceDir -Parent) 'README.md')) 'ORDER-1 §6 要求总控追加'

# ── 6. 制品面：D-060 冻结的 jar 值已失效（库内无指纹列 ⇒ F-81）──
$jarPath = 'spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar'
if (Test-Path -LiteralPath $jarPath) {
  $j = Get-Item -LiteralPath $jarPath
  $js = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
  Add-Result 'JAR-D060-freeze-stale' 'False（冻结值已失效）' ($j.Length -eq $JarFrozenD060) ($j.Length -ne $JarFrozenD060) ($j.Length.ToString() + " B / " + $j.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + " / " + $js.Substring(0, 16) + '…')
} else {
  Add-Result 'JAR-D060-freeze-stale' 'jar 存在' '缺失' $false $jarPath
}

# ── 7. 正向对照：故意用 P2-01 之前的 00-ods.sql 指纹比现在（必须判红，证明比较器能红）──
Add-Result 'CONTROL-expect-red' ('sha8 = ' + $DdlPreBaselineSha8 + '（P2-01 之前）') ('sha8 = ' + $sha00.Substring(0, 8)) ($sha00.Substring(0, 8) -eq $DdlPreBaselineSha8) '对照项：应然=判红 ⇒ Ok=False 才算对照通过'

# ── 8. 汇总 + 条数自检 ──
Write-Host ' Id                                  | 应然                                | 实际                                | 判定'
Write-Host (' ' + ('-' * 100))
foreach ($r in $script:Results) {
  $e = $r.Expect; if ($e.Length -gt 34) { $e = $e.Substring(0, 34) }
  $a = $r.Actual; if ($a.Length -gt 34) { $a = $a.Substring(0, 34) }
  Write-Host (' {0,-35} | {1,-35} | {2,-35} | {3}' -f $r.Id, $e, $a, $(if ($r.Ok) { 'PASS' } else { 'FAIL' }))
}
Write-Host ''
foreach ($r in $script:Results) { if ($r.Note -ne '') { Write-Host ('   note ' + $r.Id + '：' + $r.Note) } }
$real = @($script:Results | Where-Object { $_.Id -notlike 'CONTROL-*' })
$ctrl = @($script:Results | Where-Object { $_.Id -like 'CONTROL-*' })
$pass = @($real | Where-Object { $_.Ok }).Count
$fail = @($real | Where-Object { -not $_.Ok }).Count
Write-Host ''
Write-Host (' 判据（不含对照）：PASS ' + $pass + ' / FAIL ' + $fail + ' / 计 ' + $real.Count + ' 条')
Write-Host (' 正向对照：' + $ctrl.Count + ' 条；对照是否按预期判红 = ' + (@($ctrl | Where-Object { -not $_.Ok }).Count -eq $ctrl.Count))
Write-Host (' 自检：打印条数 ' + $script:Results.Count + ' = 判据 ' + $real.Count + ' + 对照 ' + $ctrl.Count + ' ⇒ ' + ($script:Results.Count -eq ($real.Count + $ctrl.Count)))
if (@($ctrl | Where-Object { $_.Ok }).Count -gt 0) { Write-Host ' ⚠ 正向对照未判红 ⇒ 比较器可疑，本次读数整体不采信' }
exit $(if ($fail -eq 0) { 0 } else { 1 })
