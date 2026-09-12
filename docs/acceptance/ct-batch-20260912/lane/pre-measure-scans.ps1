# pre-measure-scans.ps1 —— CT 批次 PLAN §4 开工首测（第 3/4/5 项）证据生成器
# 纪律（陷阱 #27）：先算"应然值"，再与实际读数对照；对照不符即写 SELFCHECK-FAIL。
# 只读仓库：不写任何仓内文件（输出全部落在 .verify\ct-batch\）。
[CmdletBinding()]
param([string]$Wt = 'D:\Develop_code\GraduationProject-wt\ct-batch',
      [string]$Out = 'D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$enc = [Text.UTF8Encoding]::new($false)

# ---------- 入库文件清单（git ls-files -z，NUL 分隔，避免非 ASCII 被转义） ----------
$raw = & git -C $Wt ls-files -z
$tracked = @($raw -split "`0" | Where-Object { $_ -ne '' })
$trackedAbs = @($tracked | ForEach-Object { Join-Path $Wt ($_ -replace '/', '\') } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })

$readme = [IO.File]::ReadAllText((Join-Path $Wt 'docs\acceptance\m1-5-anchor-audit-20260912\README.md'), $enc)
$expectedTotal = if ($readme -match '(\d+)\s*个入库文件') { [int]$Matches[1] } else { -1 }
$expectedScanned = if ($readme -match '扫描\s*(\d+)\s*个') { [int]$Matches[1] } else { -1 }

$o = New-Object System.Collections.Generic.List[string]
function W { param([string]$s) $script:o.Add($s) }

W "=== pre-measure-scans.ps1 @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
W "worktree    : $Wt"
W "git HEAD    : $(& git -C $Wt rev-parse HEAD)"
W "ls-files -z : 条目 $($tracked.Count) ；其中磁盘上存在的文件 $($trackedAbs.Count)"
W "对照（m1-5-anchor-audit README 自述）：入库文件 = $expectedTotal ；扫描 = $expectedScanned"
W ""

# ---------- 第 4 项：全仓"固定值：mock-mall" / "只按 event_id 去重" 扫描 ----------
# 口径：全量入库文件（不只 contract-specs 与 docs/contracts）+ 明确标注排除面。
$P1 = [regex]'固定值'
$P2 = [regex]'SOURCE_SYSTEM\s*=\s*"'
$P3 = [regex]'"const"\s*:\s*"mock-mall"'
$P4 = [regex]'(等于|==|必须是).{0,12}mock-mall|mock-mall.{0,12}(固定|常量|唯一)'
$D1 = [regex]'只按\s*`?event_id`?\s*去重|按\s*`?event_id`?\s*去重|全局唯一'
function Scan([string]$name, [regex]$rx, [bool]$skipAcceptance) {
  W "--- 扫描组 $name ：$($rx.ToString()) ---"
  W "    口径：全量入库文件；排除 docs/acceptance/** = $skipAcceptance ；排除 target/**、logs/**、.verify/**"
  $hits = 0; $files = 0
  foreach ($f in $trackedAbs) {
    $rel = $f.Substring($Wt.Length + 1)
    if ($rel -match '(^|\\)target\\') { continue }
    if ($rel -match '(^|\\)logs\\') { continue }
    if ($rel -match '(^|\\)\.verify\\') { continue }
    if ($skipAcceptance -and $rel -match '^docs\\acceptance\\') { continue }
    $t = [IO.File]::ReadAllText($f, $enc)
    $ms = $rx.Matches($t)
    if ($ms.Count -gt 0) {
      $files++
      $ln = 1; $pos = 0
      foreach ($m in $ms) {
        while ($pos -lt $m.Index) { if ($t[$pos] -eq "`n") { $ln++ }; $pos++ }
        $lineStart = $t.LastIndexOf("`n", [Math]::Max(0, $m.Index - 1)) + 1
        $lineEnd = $t.IndexOf("`n", $m.Index); if ($lineEnd -lt 0) { $lineEnd = $t.Length }
        $snippet = $t.Substring($lineStart, [Math]::Min(160, $lineEnd - $lineStart)).Trim()
        W ("    {0}:{1}  |  {2}" -f $rel, $ln, $snippet)
        $hits++
      }
    }
  }
  W "    ⇒ 命中 $hits 处 / $files 个文件"
  W ""
  return @{ hits = $hits; files = $files }
}
$r1 = Scan 'P1' $P1 $false
$r2 = Scan 'P2' $P2 $false
$r3 = Scan 'P3' $P3 $false
$r4 = Scan 'P4' $P4 $false
$rD = Scan 'D-dedup/global-unique' $D1 $false

# 正向对照（F-38 ①：零命中必须有正向对照证明扫描器非空转）
W "--- 正向对照 ---"
$posCtrl = @(
  @{ n = 'P1 应命中（docs/contracts/event-contract.md:16 含「固定值」）'; ok = ($r1.hits -ge 1) },
  @{ n = 'P2 应命中（analytics-server .../EventContract.java:17 常量定义）'; ok = ($r2.hits -ge 1) },
  @{ n = 'P3 应命中（canonical-event.v1.schema.json:39 const mock-mall）'; ok = ($r3.hits -ge 1) },
  @{ n = 'P4 应命中（contract-specs/README.md:71「取 const: "mock-mall"」）'; ok = ($r4.hits -ge 1) },
  @{ n = 'D 组应命中（event-contract.md 含「全局唯一」）'; ok = ($rD.hits -ge 1) }
)
foreach ($c in $posCtrl) { W ("    [{0}] {1}" -f $(if ($c.ok) { 'OK ' } else { 'FAIL' }), $c.n) }
W ""

# ---------- 第 5 项：EventContract.java SOURCE_SYSTEM 完整引用面 ----------
W "=== 第 5 项：SOURCE_SYSTEM 完整引用面（含 javadoc/注释/资源文件，不限于 .java）==="
$rxSym = [regex]'SOURCE_SYSTEM'
$sym = New-Object System.Collections.Generic.List[object]
foreach ($f in $trackedAbs) {
  $rel = $f.Substring($Wt.Length + 1)
  if ($rel -match '(^|\\)target\\') { continue }
  $t = [IO.File]::ReadAllText($f, $enc)
  foreach ($m in $rxSym.Matches($t)) {
    $ln = 1; for ($i = 0; $i -lt $m.Index; $i++) { if ($t[$i] -eq "`n") { $ln++ } }
    $ls = $t.LastIndexOf("`n", [Math]::Max(0, $m.Index - 1)) + 1
    $le = $t.IndexOf("`n", $m.Index); if ($le -lt 0) { $le = $t.Length }
    $sym.Add([pscustomobject]@{ rel = $rel; line = $ln; text = $t.Substring($ls, $le - $ls).Trim() })
  }
}
W "符号 SOURCE_SYSTEM 全仓（入库文件）命中：$($sym.Count) 处"
$sym | ForEach-Object { W ("    {0}:{1}  |  {2}" -f $_.rel, $_.line, $_.text) }
W ""
W "按文件归并："
$sym | Group-Object rel | Sort-Object Name | ForEach-Object { W ("    {0}  ×{1}" -f $_.Name, $_.Count) }
W ""
W "分类（人工判读口径，逐条给出理由）："
$cls = @(
  'A=平台侧常量定义/引用（本次 CT-1 变更面）',
  'B=mall-simulator 侧常量定义/引用（B-06 未决，禁改）',
  'C=生成器侧常量定义/引用（ContractFormat，B-06 未决，禁改）',
  'D=测试样本字符串（合法，禁改）',
  'E=注释/javadoc/文档叙述'
)
$cls | ForEach-Object { W "    $_" }
W ""
$o -join "`r`n" | Set-Content (Join-Path $Out 'pre-measure-scans.txt') -Encoding utf8
Write-Output "written: $Out\pre-measure-scans.txt"
