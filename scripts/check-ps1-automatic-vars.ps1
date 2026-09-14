# 静态检查：PowerShell 参数名与只读自动变量冲突（V25-S02/K-01 同型缺陷防复发）
#
# 为什么需要：`scripts/it-prepare-isolation.ps1` 原先把参数命名为 `$Host`，
# `$Host` 是 PowerShell **只读自动变量**，参数绑定阶段即抛「无法覆盖变量 Host」——
# `-File` 形式 exit 1（看得见），`-Command` 形式**静默 exit 0**（看不见），
# 后者正是"假绿"温床。改名 `$DbHost` 只修了这一个文件，本检查用来防止同型写法再混进来。
#
# 判据：只在 `param(...)` 块内、按 `,` 切片段、只看**片段首个 `$名字` token**。
#   因此 `$fail = $false` 这种赋值行不会被误判（首 token 是 $fail）；
#   `[Alias('Host','HostName')]` 里的字符串不算（别名不产生 `$Host` 变量，故合法保留）；
#   `[Parameter(Mandatory = $true)]` 里的 `$true` 不算（先剥掉行内所有 `[...]`）。
# 退出码：0 = 无冲突；1 = 存在冲突（逐条打印 file:line 与变量名）。
#
# 用法：
#   pwsh -NoProfile -File scripts/check-ps1-automatic-vars.ps1                     # 默认扫本仓库
#   pwsh -NoProfile -File scripts/check-ps1-automatic-vars.ps1 -Path <目录或文件>   # 供正对照/定向检查
param(
  [string[]]$Path = @(),
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

# PowerShell 只读自动变量中「真会被人当成参数名」的那些。不列 $null/$true/$false
# 这类（没人会把它们当参数名，列进来只会制造假阳性）。
$AutomaticVars = @(
  'Host', 'Args', 'Input', 'Error', 'Matches', 'PSItem', 'This', 'PSCmdlet',
  'PSCommandPath', 'PSScriptRoot', 'MyInvocation', 'ExecutionContext', 'StackTrace',
  'Home', 'Pid', 'Profile', 'ShellId', 'ConsoleFileName', 'OutputEncoding',
  'NestedPromptLevel', 'PSBoundParameters', 'PSDefaultParameterValues', 'PSStyle', 'ErrorView'
)

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Path -or $Path.Count -eq 0) { $Path = @($repoRoot) }

$files = New-Object System.Collections.Generic.List[string]
foreach ($p in $Path) {
  if (-not (Test-Path -LiteralPath $p)) { continue }
  $item = Get-Item -LiteralPath $p
  if ($item.PSIsContainer) {
    Get-ChildItem -LiteralPath $item.FullName -Recurse -File -Filter '*.ps1' |
      Where-Object { $_.FullName -notmatch '[\\/](\.git|target|node_modules|\.venv)[\\/]' } |
      ForEach-Object { $files.Add($_.FullName) }
  } else {
    $files.Add($item.FullName)
  }
}

$violations = New-Object System.Collections.Generic.List[object]
$scannedBlocks = 0
foreach ($f in $files) {
  $lines = @(Get-Content -LiteralPath $f -Encoding utf8)
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -notmatch '^\s*param\s*\(') { continue }
    $scannedBlocks++
    # 区域：param 行本身括号已配对 ⇒ 只扫这一行；否则扫到「单独一个 )」为止（上限 120 行兜底）。
    $end = $i
    $open = ([regex]::Matches($lines[$i], '\(')).Count
    $close = ([regex]::Matches($lines[$i], '\)')).Count
    if ($open -ne $close) {
      $end = [Math]::Min($i + 120, $lines.Count - 1)
      for ($j = $i + 1; $j -le $end; $j++) {
        if ($lines[$j].Trim() -eq ')') { $end = $j; break }
      }
    }
    for ($j = $i; $j -le $end; $j++) {
      $raw = $lines[$j]
      if ($raw.TrimStart().StartsWith('#')) { continue }
      $stripped = [regex]::Replace($raw, '\[[^\]]*\]', '')
      foreach ($frag in ($stripped -split ',')) {
        if ($frag -match '^\s*\$([A-Za-z_][A-Za-z0-9_]*)\s*(=|$|\))') {
          $name = $Matches[1]
          if ($AutomaticVars -contains $name) {
            $violations.Add([pscustomobject]@{
                file = $f.Replace($repoRoot + '\', '')
                line = $j + 1
                name = $name
                text = $raw.Trim()
              })
          }
        }
      }
    }
    $i = $end
  }
}

if ($violations.Count -eq 0) {
  if (-not $Quiet) {
    Write-Host ("[PASS] 参数名静态检查通过：{0} 个 *.ps1、{1} 个 param 块，无「参数名撞只读自动变量」。" -f $files.Count, $scannedBlocks)
  }
  exit 0
}

Write-Host ("[FAIL] 发现 {0} 处「参数名撞 PowerShell 只读自动变量」（参数绑定即失败；-Command 下可能静默 exit 0）：" -f $violations.Count)
foreach ($v in $violations) {
  Write-Host ("  {0}:{1}  `${2}    {3}" -f $v.file, $v.line, $v.name, $v.text)
}
Write-Host '  修法：参数改名（如 $Host → $DbHost）；兼容旧调用写法用 [Alias(...)]，别名本身合法、不必改。'
exit 1
