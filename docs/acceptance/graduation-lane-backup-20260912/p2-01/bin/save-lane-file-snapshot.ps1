# =====================================================================
# P2-01 泳道：改动前文件备份（ORDER-1 §3.4「每改一个文件前先复制一份」）
# 硬约束遵守：
#   - 不含任何删除类命令（无 Remove-Item / rd / del / ri / robocopy /MIR）
#   - 函数名不与 PowerShell 内置别名同名
#   - 复制前断言「源存在且非空」，复制后断言「副本字节数与源相等」
# 可重复执行（幂等：同文件重复备份只追加 manifest 行，不覆盖既有副本）
# =====================================================================
param(
  [Parameter(Mandatory = $true)]
  [string]$PathListFile,
  [string]$Label = 'base'
)

$ErrorActionPreference = 'Stop'

$repoRoot = 'D:\Develop_code\GraduationProject'
$backupRoot = 'D:\Develop_code\graduation-lane-backup\p2-01\files'
$manifestPath = 'D:\Develop_code\graduation-lane-backup\p2-01\files-manifest.tsv'

if (-not (Test-Path -LiteralPath $PathListFile)) { throw "清单文件不存在：$PathListFile" }
if (-not (Test-Path -LiteralPath $repoRoot))    { throw "仓库不存在：$repoRoot" }
if (-not (Test-Path -LiteralPath $backupRoot))  { New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null }

$rows = Get-Content -LiteralPath $PathListFile | Where-Object { $_.Trim() -ne '' -and -not $_.TrimStart().StartsWith('#') }

$report = New-Object System.Collections.Generic.List[string]
$report.Add("label`trelpath`tsrc_bytes`tsrc_sha256`tcopy_bytes`tcopy_sha256`tcopy_path")

foreach ($rel in $rows) {
  $rel = $rel.Trim().Replace('\', '/')
  $src = Join-Path $repoRoot ($rel.Replace('/', '\'))

  if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw "源文件不存在（拒绝继续）：$src" }
  $srcItem = Get-Item -LiteralPath $src
  if ($srcItem.Length -le 0) { throw "源文件为空（拒绝继续）：$src" }
  $srcHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash

  # 扁平化目标名：层级用 __ 连接，避免建子目录树
  $flat = ($rel -replace '[/\\]', '__')
  $dst = Join-Path $backupRoot ("$Label" + '~' + $flat)
  Copy-Item -LiteralPath $src -Destination $dst -Force

  if (-not (Test-Path -LiteralPath $dst -PathType Leaf)) { throw "备份未生成：$dst" }
  $dstItem = Get-Item -LiteralPath $dst
  $dstHash = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
  if ($dstItem.Length -ne $srcItem.Length) { throw "备份字节数不等：$src ($($srcItem.Length)) vs $dst ($($dstItem.Length))" }
  if ($dstHash -ne $srcHash) { throw "备份 sha256 不等：$src" }

  $report.Add("$Label`t$rel`t$($srcItem.Length)`t$srcHash`t$($dstItem.Length)`t$dstHash`t$dst")
  Write-Output "[backup] OK $rel ($($srcItem.Length) B, $srcHash)"
}

Add-Content -LiteralPath $manifestPath -Value $report -Encoding UTF8
Write-Output "[backup] manifest += $($report.Count - 1) 行 → $manifestPath"
