<#
V26-F88 | 宿主正式实例 3306 只读指纹（before / after 各跑一次，逐项 diff）

安全边界（硬约束，逐条实现，不依赖自觉）：
  * 端口必须实测为 3306，且 @@server_uuid 必须等于宿主正式实例指纹，否则 throw。
  * SQL 文件逐语句静态检查：只允许 SET / SELECT / 注释；出现其它语句立即 throw（连都不连）。
  * 只读口令从环境变量 HOST3306_PWD 取；**不设任何明文默认值**，未提供即 fail-fast
    （沿用 V25-S02 总控复核口径：取证工具不得新增明文默认口令）。
  * 全程只读：零 DDL / 零 DML / 零清理。
  * 输出只落本泳道 raw/ 目录（仓库内）。

用法：
  $env:HOST3306_PWD='...'; pwsh -NoProfile -File <this> -Phase before|after -WinStart '...' -WinEnd '...'
#>
param(
  [Parameter(Mandatory = $true)][ValidateSet('before', 'after')][string]$Phase,
  [string]$OutName,
  [string]$WinStart,
  [string]$WinEnd,
  [string]$SqlFile = (Join-Path $PSScriptRoot 'fingerprint-3306.sql'),
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$ExpectUuid = '85191145-1491-11f0-b4e2-60cf84d55629',
  [int]$Port = 3306
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ($Port -ne 3306) { throw "本脚本只允许只读指纹目标 3306（当前 $Port）" }
if (-not $env:HOST3306_PWD) {
  throw '未提供 3306 只读口令：请设置环境变量 HOST3306_PWD（不写入仓库）。本脚本不回退任何默认口令。'
}
if (-not $OutName) { $OutName = "00-fingerprint-3306-$Phase.txt" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutFile = Join-Path $OutDir $OutName

# ── 门禁：SQL 文本里每条语句必须是 SET / SELECT ────────────────────────
$raw = Get-Content $SqlFile -Encoding utf8 -Raw
$effective = $raw
if ($WinStart) { $effective = $effective.Replace('__WIN_START__', $WinStart) }
if ($WinEnd)   { $effective = $effective.Replace('__WIN_END__',   $WinEnd) }
if ($effective -match '__WIN_(START|END)__') { throw '窗口占位符未被替换：请传 -WinStart/-WinEnd' }
$stmts = $effective -split ';'
foreach ($s in $stmts) {
  $t = ($s -split "`n" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('--') }) -join ' '
  if (-not $t.Trim()) { continue }
  if ($t.Trim() -notmatch '^(?i)SET\b|^(?i)SELECT\b') { throw "拒绝执行非只读语句：$t" }
}

# 落盘用的是替换后的 SQL（窗口字面量已固化），保证读数可复现
$sqlTmp = Join-Path $env:TEMP ("f88-fingerprint-3306-$Phase.sql")
Set-Content -Path $sqlTmp -Value $effective -Encoding utf8

$old = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:HOST3306_PWD
try {
  $fp = (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names `
        -e "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir,'|',@@hostname);" 2>&1) | Out-String
  $fp = $fp.Trim()
  if ($LASTEXITCODE -ne 0) { throw "3306 指纹查询失败（退出码 $LASTEXITCODE）：$fp" }
  if ($fp -notmatch "^3306\|$ExpectUuid\|") { throw "目标不是宿主正式实例，拒绝继续：$fp" }

  $out = & $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names `
         -e "source $($sqlTmp -replace '\\','/')" 2>&1
  $code = $LASTEXITCODE

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("### phase: $Phase")
  $lines.Add("### collected at: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))")
  $lines.Add("### command: mysql --host=127.0.0.1 --port=3306 --user=root (HOST3306_PWD 注入) --batch --raw --skip-column-names -e 'source <sql>'")
  $lines.Add("### instance fingerprint (port|uuid|datadir|hostname): $fp")
  $lines.Add("### sql sha256: $((Get-FileHash $SqlFile -Algorithm SHA256).Hash)")
  $lines.Add("### window: [$WinStart , $WinEnd]")
  $lines.Add('### ---- raw output (k<TAB>v) ----')
  $out | ForEach-Object { $lines.Add($_.ToString()) }
  $lines.Add("### exit code: $code")
  $lines | Set-Content $OutFile -Encoding utf8
  $lines | ForEach-Object { Write-Host $_ }
  if ($code -ne 0) { throw "指纹采集失败，退出码 $code" }
} finally {
  $env:MYSQL_PWD = $old
}
Write-Output "[00] 指纹已写 $OutFile"
exit 0
