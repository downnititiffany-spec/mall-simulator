<#
V25-E3 | 在隔离实例（WSL MySQL 8.0.41 @ 127.0.0.1:3307）上创建本次 testRunId 命名的库与受限账号。

纪律：
  * 唯一写入目标 = 3307。脚本在**建立连接之前**先做端口白名单 + 实例指纹核对；
    指纹不符（uuid/port/datadir）立即拒绝，不连接。
  * 3306 一律拒绝（连都不连）。
  * 落盘全部在仓库内且被 gitignore：（scratch）= target/e3-run/<runId>/。
  * 口令随机生成，只写进 (scratch)/credref.properties；仓库内任何证据文件只出现 credref 引用。

用法：pwsh -File <this> [-RunId <id>]
#>
param(
  [string]$RunId = 'v25it-20260914-1358-l4e3',
  [string]$DbHost = '127.0.0.1',
  [int]$DbPort = 3307,
  [int[]]$AllowedPorts = @(3307),
  [string]$ExpectUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [string]$ExpectDatadir = '/data/mysql-isolated/data/',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$RepoRoot = 'D:\Develop_code\GraduationProject'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') { throw "RunId 形状非法: $RunId" }
# 标识符 token：MySQL 库名/账号名不接受 '-'，故把 runId 的 '-' 换成 '_'
$token   = $RunId -replace '-', '_'
$metaDb  = "analytics_meta_$token"
$metricDb= "analytics_metric_$token"
$suffix  = ($RunId -split '-')[2] + '_' + ($RunId -split '-')[3]     # 例如 1358_l4e3
$metaUser   = "v25it_${suffix}_meta"
$pubUser    = "v25it_${suffix}_metric_pub"
$readUser   = "v25it_${suffix}_metric_read"

$scratch = Join-Path $RepoRoot "target\e3-run\$RunId"
New-Item -ItemType Directory -Force -Path $scratch | Out-Null
$credFile = Join-Path $scratch 'credref.properties'

# ── 门禁 1：端口白名单（3306 连都不连）────────────────────────────────
if ($AllowedPorts -notcontains $DbPort) {
  Write-Host "拒绝：-DbPort $DbPort 不在允许清单 $($AllowedPorts -join ', ') 内（3306 是宿主正式实例）。"
  exit 2
}
if ($DbHost -notin @('127.0.0.1','localhost','::1')) { Write-Host "拒绝：-DbHost $DbHost 非本机。"; exit 2 }

# ── 门禁 2：实例指纹（写前核对）──────────────────────────────────────
$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
try {
  $fp = & $MysqlExe "--host=$DbHost" "--port=$DbPort" --user=root -N -B `
        -e "SELECT @@port, @@server_uuid, @@datadir;" 2>&1
} finally { $env:MYSQL_PWD = $old }
if ($LASTEXITCODE -ne 0) { Write-Host "拒绝：隔离实例指纹核对失败（mysql 退出码 $LASTEXITCODE）：$fp"; exit 3 }
$c = ($fp | Select-Object -First 1) -split "`t"
if ([int]$c[0] -ne $DbPort -or $c[1] -ne $ExpectUuid -or $c[2] -ne $ExpectDatadir) {
  Write-Host "拒绝：实例指纹不符。实测 port=$($c[0]) uuid=$($c[1]) datadir=$($c[2])"; exit 3
}
Write-Host "[指纹] OK port=$($c[0]) uuid=$($c[1]) datadir=$($c[2])"

# ── 口令（随机；只落 scratch，仓库内不出现）──────────────────────────
function New-Secret([int]$len = 24) {
  $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
  $bytes = New-Object 'System.Byte[]' $len
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}
$metaPwd = New-Secret; $pubPwd = New-Secret; $readPwd = New-Secret

# ── 打印将创建的对象清单 ─────────────────────────────────────────────
Write-Host '=========== 将创建（3307 隔离实例）==========='
Write-Host "  metaDb        : $metaDb"
Write-Host "  metricDb      : $metricDb"
Write-Host "  meta user     : $metaUser@'%'   -> $metaDb.*  (SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP)"
Write-Host "  publish user  : $pubUser@'%'    -> $metricDb.*(同上)"
Write-Host "  read user     : $readUser@'%'   -> $metricDb.*(仅 SELECT)"
Write-Host "  credref       : $credFile  (仓库内 target/，已 gitignore；口令不回显)"

# ── 建库/建号/授权（幂等）────────────────────────────────────────────
$sql = @"
CREATE DATABASE IF NOT EXISTS ``$metaDb``   CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS ``$metricDb`` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS '$metaUser'@'%' IDENTIFIED WITH mysql_native_password BY '$metaPwd';
CREATE USER IF NOT EXISTS '$pubUser'@'%'  IDENTIFIED WITH mysql_native_password BY '$pubPwd';
CREATE USER IF NOT EXISTS '$readUser'@'%' IDENTIFIED WITH mysql_native_password BY '$readPwd';
ALTER USER '$metaUser'@'%' IDENTIFIED WITH mysql_native_password BY '$metaPwd';
ALTER USER '$pubUser'@'%'  IDENTIFIED WITH mysql_native_password BY '$pubPwd';
ALTER USER '$readUser'@'%' IDENTIFIED WITH mysql_native_password BY '$readPwd';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON ``$metaDb``.*   TO '$metaUser'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON ``$metricDb``.* TO '$pubUser'@'%';
GRANT SELECT ON ``$metricDb``.* TO '$readUser'@'%';
FLUSH PRIVILEGES;
SELECT 'SCHEMA', SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('$metaDb','$metricDb');
SELECT 'GRANT', GRANTEE, TABLE_SCHEMA, GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE)
  FROM information_schema.SCHEMA_PRIVILEGES
 WHERE GRANTEE IN ("'$metaUser'@'%'","'$pubUser'@'%'","'$readUser'@'%'")
 GROUP BY GRANTEE, TABLE_SCHEMA;
"@
$sqlFile = Join-Path $scratch 'create-isolation.sql'
Set-Content -Path $sqlFile -Value $sql -Encoding utf8

$env:MYSQL_PWD = '123456'
try {
  $out = & $MysqlExe "--host=$DbHost" "--port=$DbPort" --user=root --table -e "source $($sqlFile -replace '\\','/')" 2>&1
  $rc = $LASTEXITCODE
} finally { $env:MYSQL_PWD = $old }
$out | ForEach-Object { Write-Host $_ }
if ($rc -ne 0) { Write-Host "建库/授权失败（退出码 $rc）"; exit 4 }

# ── 凭据落 scratch（不回显）──────────────────────────────────────────
@(
  "# V25-E3 隔离凭据引用（仓库内 target/，gitignore）。口令不写入 docs/ 证据。",
  "# runId=$RunId instance=$DbHost`:$DbPort uuid=$ExpectUuid",
  "metaDb=$metaDb",
  "metricDb=$metricDb",
  "meta.username=$metaUser",
  "meta.password=$metaPwd",
  "publish.username=$pubUser",
  "publish.password=$pubPwd",
  "read.username=$readUser",
  "read.password=$readPwd"
) | Set-Content -Path $credFile -Encoding utf8
Write-Host "[2/2] 凭据已写 $credFile （不回显口令）"
exit 0
