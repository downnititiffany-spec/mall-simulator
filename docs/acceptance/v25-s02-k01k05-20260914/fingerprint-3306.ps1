# V25-S02 取证：宿主 3306 只读指纹（before / after 各跑一次，逐字节比对）
#
# 为什么必须新增而不是改现有脚本：E3 泳道的 scripts/fingerprint-3306.sql 只覆盖
# analytics_metric 两张表；S02 的判据还要覆盖 flyway_schema_history 计数、ACTIVE 指针、
# 以及「本轮 runId / v25it-% 在 3306 上是否为 0 行」。本脚本把 SQL 与执行/比对分开：
#   SQL 在 fingerprint-3306-readonly.sql（可被独立审阅），本脚本只负责执行与比对。
#
# 安全边界（硬约束）：
#   * 只允许 SET / SELECT 语句；出现任何其它语句直接 throw（防呆，不依赖自觉）。
#   * 端口必须实测为 3306，且 server_uuid 必须是宿主正式实例指纹，否则 throw。
#   * 全程只读；不执行任何 DDL/DML/清理。
param(
  [ValidateSet('before', 'after')][string]$Phase = 'before',
  [string]$OutDir = (Join-Path $PSScriptRoot 'raw')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$HostFingerprint = '85191145-1491-11f0-b4e2-60cf84d55629'   # 宿主正式实例（只读目标）
$SqlFile = Join-Path $PSScriptRoot 'fingerprint-3306-readonly.sql'
$OutFile = Join-Path $OutDir "s02-fingerprint-3306-$Phase.txt"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 防呆：SQL 文件里每条语句都必须是 SET / SELECT / 注释
$statements = (Get-Content $SqlFile -Encoding utf8) -join "`n" -split ';'
foreach ($s in $statements) {
  $t = ($s -split "`n" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('--') }) -join ' '
  if (-not $t.Trim()) { continue }
  if ($t.Trim() -notmatch '^(?i)SET\b|^(?i)SELECT\b') { throw "拒绝执行非只读语句：$t" }
}

$old = $env:MYSQL_PWD
# 总控复核补记 2026-09-14（以此为准）：原写法在未设 HOST3306_PWD 时回退明文 '123456'。
# 本仓库正在收口存量明文默认口令（V25-S05），取证工具不得**新增**明文默认值；
# 且与同泳道 K-04/K-05「无口令即拒绝」的姿态不一致 ⇒ 改为 fail-fast，不回退任何默认口令。
if (-not $env:HOST3306_PWD) {
  throw '未提供 3306 只读口令：请设置环境变量 HOST3306_PWD（不写入仓库）。本脚本不回退任何默认口令。'
}
$env:MYSQL_PWD = $env:HOST3306_PWD
try {
  # 1. 目标指纹断言：必须是宿主正式实例
  $fp = (& $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names `
        -e "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir,'|',@@hostname);" 2>&1) | Out-String
  $fp = $fp.Trim()
  if ($LASTEXITCODE -ne 0) { throw "3306 指纹查询失败（退出码 $LASTEXITCODE）：$fp" }
  if ($fp -notmatch "^3306\|$HostFingerprint\|") { throw "目标不是宿主正式实例，拒绝继续：$fp" }

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("### phase: $Phase")
  $lines.Add("### command: mysql --host=127.0.0.1 --port=3306 --user=root (MYSQL_PWD 注入) --batch --raw --skip-column-names -e 'source $SqlFile'")
  $lines.Add("### instance fingerprint (port|uuid|datadir|hostname): $fp")
  $lines.Add("### sql file sha256: $((Get-FileHash $SqlFile -Algorithm SHA256).Hash)")
  $lines.Add("### collected at: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))")
  $lines.Add('### ---- raw output (k<TAB>v) ----')
  $out = & $MysqlExe --host=127.0.0.1 --port=3306 --user=root --batch --raw --skip-column-names `
         -e "source $SqlFile" 2>&1
  $code = $LASTEXITCODE
  $out | ForEach-Object { $lines.Add($_.ToString()) }
  $lines.Add("### exit code: $code")
  $lines | Set-Content $OutFile -Encoding utf8
  $lines | ForEach-Object { Write-Host $_ }
  if ($code -ne 0) { throw "指纹采集失败，退出码 $code" }
} finally {
  $env:MYSQL_PWD = $old
}
