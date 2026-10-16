# scripts/it-clean-isolation.ps1
# 3307 隔离实例白名单清理脚本（WP-F5b / F-93-08）
#
# 依据总控裁决（docs/PROJECT_STATUS.md L2106/L2109 与 WP-F5b.md）：
#   - 3307 隔离实例遗留库/账号清理必须走 -AllowedCleanDbs 白名单清理，禁止手写 DROP；
#   - 严格锁定 3307 端口，严禁触碰宿主 3306 正式实例；
#   - 执行前先核对 @@port=3307 且 @@server_uuid 等于 -InstanceUuid；
#   - 待清理库名及其 runId 前缀必须通过严格的隔离命名形状门禁；
#   - 探针阶段核对白名单库真实存在，不存在则退出码 7 报错；
#   - 由 runId 前缀通过 isolation-naming.ps1 联动推导四种角色账号（mallapp/genapp/metaapp/metricapp），
#     仅对 mysql.user 中真实存在的推导账号执行 DROP USER IF EXISTS；
#   - 默认 DryRun 预演（退出码 0），仅打印拟执行 DROP 清单；显式给出 -Confirm 时才真正执行；
#   - 所有进入 SQL 的库名/账号名均经正则校验并以反引号包裹标识符。
#
# 退出码：
#   0 = 成功（含 DryRun 完成）
#   5 = 门禁拒绝（端口/指纹/形状/系统库/通配符）
#   7 = 执行期失败（探针连不上、白名单库不存在、DROP 失败）

param(
  # 待清理数据库白名单清单（精确库名数组，必填，不接受通配符 % / * 或转义）
  [Parameter(Mandatory = $true)][string[]]$AllowedCleanDbs,

  # 预期的 3307 隔离实例 UUID（必填，无默认值，防打偏）
  [Parameter(Mandatory = $true)][string]$InstanceUuid,

  # 隔离实例主机地址（默认 127.0.0.1）
  [Alias('Host', 'HostName')][string]$DbHost = '127.0.0.1',

  # 隔离实例端口（必须严格等于 3307）
  [int]$Port = 3307,

  # mysql 客户端可执行文件路径（默认与 run-isolated-tests.ps1 对齐）
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',

  # 真实执行开关。未显式给出时默认仅预演（DryRun）并打印 DROP 清单
  [switch]$Confirm,

  # 预演模式显式开关（未给 -Confirm 时默认即为预演）
  [switch]$DryRun,

  # 可选日志输出目录
  [string]$LogDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

# 导入隔离命名工具脚本（推导账号名）
$namingScript = Join-Path $PSScriptRoot 'isolation-naming.ps1'
if (-not (Test-Path -LiteralPath $namingScript)) {
  Write-Host ("[REFUSE exit=7] 找不到命名工具脚本：{0}" -f $namingScript)
  exit 7
}
. $namingScript

$FormalPort = 3306
$SystemDbs = @('mysql', 'sys', 'information_schema', 'performance_schema')
$FormalDbs = @('mall_simulator', 'generator_meta', 'analytics_meta', 'analytics_metric')
$dbPattern = '^[A-Za-z][A-Za-z0-9_]*_(mall|generator|analytics_meta|analytics_metric)$'
$runIdPattern = '^[a-z][a-z0-9]*_\d{8}_\d{4}(_[a-z0-9]+)?$'
$suffixPattern = '_(mall|generator|analytics_meta|analytics_metric)$'

# 日志初始化（如提供 -LogDir）
$logFilePath = $null
if ($LogDir) {
  if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
  }
  $logFileName = "it-clean-isolation-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss')
  $logFilePath = Join-Path $LogDir $logFileName
}

function Log-Info([string]$msg) {
  Write-Host $msg
  if ($logFilePath) {
    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'
    Add-Content -Path $logFilePath -Value "[$timestamp] $msg" -Encoding utf8
  }
}

function Fail([int]$code, [string]$msg) {
  $outputMsg = ("[REFUSE exit={0}] {1}" -f $code, $msg)
  Write-Host $outputMsg
  if ($logFilePath) {
    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'
    Add-Content -Path $logFilePath -Value "[$timestamp] $outputMsg" -Encoding utf8
  }
  exit $code
}

# ── 1. 端口与参数门禁 ──────────────────────────────────────────────────
if ($Port -ne 3307) {
  Fail 5 ("端口 {0} 不是 3307：拒绝（清理脚本仅允许在 3307 隔离实例上运行）" -f $Port)
}
if ($Port -eq $FormalPort) {
  Fail 5 ("端口 {0} 是宿主正式实例：拒绝（正式库绝对禁止任何清理操作）" -f $FormalPort)
}
if ($DbHost -notin @('127.0.0.1', 'localhost', '::1')) {
  Fail 5 ("-DbHost '{0}' 不是本机地址。隔离实例必须为本机地址（127.0.0.1 / localhost / ::1）。" -f $DbHost)
}

# 检查所有绑定参数值，任何字段出现 3306 字样一律以退出码 5 拒绝
foreach ($kv in $PSBoundParameters.GetEnumerator()) {
  $valStr = "$($kv.Value)"
  if ($valStr -match "$FormalPort") {
    Fail 5 ("参数 -{0} 包含正式库端口 {1} 字样：拒绝（严禁触碰正式库相关配置）" -f $kv.Key, $FormalPort)
  }
}

if ([string]::IsNullOrWhiteSpace($InstanceUuid)) {
  Fail 5 "未提供实例 UUID：-InstanceUuid 为必填参数"
}

if ($null -eq $AllowedCleanDbs -or $AllowedCleanDbs.Count -eq 0) {
  Fail 5 "未提供待清理库名：-AllowedCleanDbs 为必填参数且不能为空"
}

# ── 2. 库名形状与通配符门禁 ─────────────────────────────────────────────
$vettedDbs = @()
$uniqueRunIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

foreach ($rawDb in $AllowedCleanDbs) {
  if ([string]::IsNullOrWhiteSpace($rawDb)) {
    Fail 5 "待清理库名数组中包含空元素：拒绝"
  }
  $db = $rawDb.Trim()

  # 1) 通配符与转义绕过门禁：禁止 %、* 以及 \ 转义绕过尝试
  if ($db.Contains('%') -or $db.Contains('*') -or $db.Contains('\')) {
    Fail 5 ("待清理库名 '{0}' 包含通配符或转义字符（禁止 `%` / `*` / 转义绕过）：拒绝" -f $db)
  }

  # 2) 系统库显式拒绝
  if ($SystemDbs -contains $db.ToLowerInvariant()) {
    Fail 5 ("待清理库名 '{0}' 是系统库：绝对禁止清理，拒绝" -f $db)
  }

  # 3) 正式业务库显式拒绝
  if ($FormalDbs -contains $db.ToLowerInvariant()) {
    Fail 5 ("待清理库名 '{0}' 是正式业务库：绝对禁止清理，拒绝" -f $db)
  }

  # 4) 库名全匹配正则门禁
  if ($db -cnotmatch $dbPattern) {
    Fail 5 ("待清理库名 '{0}' 形状非法（要求全匹配 {1}）：拒绝" -f $db, $dbPattern)
  }

  # 5) runId 前缀正则门禁（必须包含日期与时间段）
  $runIdPrefix = [System.Text.RegularExpressions.Regex]::Replace($db, $suffixPattern, '')
  if ($runIdPrefix -cnotmatch $runIdPattern) {
    Fail 5 ("待清理库名 '{0}' 的 runId 前缀 '{1}' 形状非法（要求全匹配 {2}，必须包含日期与时间段）：拒绝" -f $db, $runIdPrefix, $runIdPattern)
  }

  $vettedDbs += $db
  [void]$uniqueRunIds.Add($runIdPrefix)
}

# ── 3. 客户端与凭据准备 ─────────────────────────────────────────────────
$resolvedMysql = $null
if (Test-Path -LiteralPath $MysqlExe) {
  $resolvedMysql = $MysqlExe
} elseif (Get-Command $MysqlExe -ErrorAction SilentlyContinue) {
  $resolvedMysql = $MysqlExe
} elseif (Get-Command 'mysql' -ErrorAction SilentlyContinue) {
  $resolvedMysql = 'mysql'
}
if (-not $resolvedMysql) {
  Fail 7 ("找不到 mysql 客户端：{0}" -f $MysqlExe)
}

# 凭据只从环境变量读取（照抄 it-prepare-isolation.ps1 变量名与用法，不落盘、不回显、不进 CLI）
$adminPwd = [Environment]::GetEnvironmentVariable('V25IT_ADMIN_PWD', 'Process')
if (-not $adminPwd) {
  $adminPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
}
if ($adminPwd) {
  Log-Info '[admin] 管理员口令已从环境变量读取（不回显）。'
} else {
  Log-Info '[admin] 未设置 V25IT_ADMIN_PWD / MYSQL_PWD：按 root 免密尝试。'
}

function Invoke-MySqlQuery {
  param(
    [Parameter(Mandatory = $true)][string]$Sql,
    [switch]$SkipColumnNames = $true
  )

  $argsList = @(
    "--host=$DbHost",
    "--port=$Port",
    "--user=root",
    '--batch',
    '--silent'
  )
  if ($SkipColumnNames) {
    $argsList += '--skip-column-names'
  }
  $argsList += @('-e', $Sql)

  $previousMysqlPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
  $env:MYSQL_PWD = $adminPwd
  try {
    $output = & $resolvedMysql @argsList 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
      ExitCode = $exitCode
      Output   = $output
    }
  } finally {
    if ($null -eq $previousMysqlPwd) {
      Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
      $env:MYSQL_PWD = $previousMysqlPwd
    }
  }
}

function Test-InstanceFingerprint {
  $probeSql = "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@hostname);"
  $res = Invoke-MySqlQuery -Sql $probeSql
  if ($res.ExitCode -ne 0) {
    Fail 7 ("探针执行失败（mysql 退出码 {0}）：{1}`n       请确认 3307 实例在跑且凭据正确。" -f $res.ExitCode, ($res.Output -join ' '))
  }
  $line = ($res.Output | Where-Object { $_ -match '\|' } | Select-Object -First 1)
  if (-not $line) {
    Fail 7 ("探针输出不可解析：{0}" -f ($res.Output -join ' '))
  }
  $parts = "$line".Trim().Split('|')
  if ($parts.Count -lt 3) {
    Fail 7 ("探针输出字段不足（期望 port|uuid|hostname）：{0}" -f ($res.Output -join ' '))
  }
  $probePort = $parts[0].Trim()
  $probeUuid = $parts[1].Trim()
  $probeHostname = $parts[2].Trim()

  if ($probePort -ne "$Port" -or $probePort -ne '3307') {
    Fail 5 ("探针读到的 @@port={0} 与 3307 不符：拒绝（严禁连非 3307 实例）" -f $probePort)
  }
  if ($probeUuid -ne $InstanceUuid) {
    Fail 5 ("实例指纹不符：@@server_uuid={0}，期望 {1}（疑似打到别的实例）：拒绝" -f $probeUuid, $InstanceUuid)
  }
  return [pscustomobject]@{
    Port     = $probePort
    Uuid     = $probeUuid
    Hostname = $probeHostname
  }
}

# ── 4. 指纹探针先行 ─────────────────────────────────────────────────────
Log-Info '=== 3307 隔离实例白名单清理门禁检查 ==='
$probe1 = Test-InstanceFingerprint
Log-Info ("  [门禁3-探针OK] 实例指纹核对通过：port={0} uuid={1} hostname={2}" -f $probe1.Port, $probe1.Uuid, $probe1.Hostname)

# ── 5. 存在性核对 ───────────────────────────────────────────────────────
$schemaSql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA;"
$schemaRes = Invoke-MySqlQuery -Sql $schemaSql
if ($schemaRes.ExitCode -ne 0) {
  Fail 7 ("查询 information_schema.SCHEMATA 失败（退出码 {0}）：{1}" -f $schemaRes.ExitCode, ($schemaRes.Output -join ' '))
}
$existingSchemas = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($line in $schemaRes.Output) {
  $s = "$line".Trim()
  if ($s) {
    [void]$existingSchemas.Add($s)
  }
}

$missingDbs = @()
foreach ($db in $vettedDbs) {
  if (-not $existingSchemas.Contains($db)) {
    $missingDbs += $db
  }
}
if ($missingDbs.Count -gt 0) {
  Fail 7 ("白名单库在实例中不存在：[{0}]。全部待清理库必须真实存在，不得静默跳过；拒绝。" -f ($missingDbs -join ', '))
}
Log-Info ("  [门禁5-存在性OK] 白名单库全部存在：{0} 个" -f $vettedDbs.Count)

# ── 6. 账号联动推导与存在性核对 ─────────────────────────────────────────
$userSql = "SELECT User, Host FROM mysql.user;"
$userRes = Invoke-MySqlQuery -Sql $userSql
if ($userRes.ExitCode -ne 0) {
  Fail 7 ("查询 mysql.user 失败（退出码 {0}）：{1}" -f $userRes.ExitCode, ($userRes.Output -join ' '))
}
$existingUsers = @{}
foreach ($line in $userRes.Output) {
  $parts = "$line".Trim() -split "`t"
  if ($parts.Count -lt 2) {
    $parts = "$line".Trim() -split '\s+'
  }
  if ($parts.Count -ge 2) {
    $u = $parts[0].Trim()
    $h = $parts[1].Trim()
    if ($u) {
      if (-not $existingUsers.ContainsKey($u)) {
        $existingUsers[$u] = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
      }
      [void]$existingUsers[$u].Add($h)
    }
  }
}

$roles = @('mallapp', 'genapp', 'metaapp', 'metricapp')
$accountsToDrop = @()
$droppedAccountKeys = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

foreach ($r in $uniqueRunIds) {
  foreach ($role in $roles) {
    $derivedUser = New-IsolationUserName -RunId $r -Role $role
    if ($derivedUser -notmatch '^[a-z0-9_]{1,32}$') {
      Fail 5 ("推导账号名 '{0}' 形状非法：拒绝" -f $derivedUser)
    }
    if ($existingUsers.ContainsKey($derivedUser)) {
      foreach ($h in $existingUsers[$derivedUser]) {
        if ($h -notmatch '^[a-zA-Z0-9_.%:-]+$') {
          Fail 5 ("mysql.user 中账号 '{0}' 的 Host '{1}' 包含非法字符：拒绝" -f $derivedUser, $h)
        }
        $key = "$derivedUser@$h"
        if (-not $droppedAccountKeys.Contains($key)) {
          [void]$droppedAccountKeys.Add($key)
          $accountsToDrop += [pscustomobject]@{
            User = $derivedUser
            Host = $h
            Sql  = "DROP USER IF EXISTS ``$derivedUser``@``$h``;"
          }
        }
      }
    }
  }
}
Log-Info ("  [门禁6-账号联动OK] 匹配到需清理的真实存在账号：{0} 个" -f $accountsToDrop.Count)

# ── 7. 拟执行清单构建与展示 ─────────────────────────────────────────────
$dbsToDrop = @()
foreach ($db in $vettedDbs) {
  $dbsToDrop += [pscustomobject]@{
    Db  = $db
    Sql = "DROP DATABASE ``$db``;"
  }
}

Log-Info ''
Log-Info '════════════════════════════════════════════════════════════════'
Log-Info ' 3307 隔离实例白名单清理 —— 拟执行清单'
Log-Info '════════════════════════════════════════════════════════════════'
Log-Info (" 目标实例    : {0}:{1}（UUID: {2}）" -f $DbHost, $Port, $InstanceUuid)
Log-Info (" 执行模式    : {0}" -f $(if ($Confirm -and -not $DryRun) { '【真执行模式 -Confirm】' } else { '【预演模式 DryRun（默认不执行）】' }))
Log-Info (" 拟删除数据库 ({0} 个)：" -f $dbsToDrop.Count)
foreach ($d in $dbsToDrop) {
  Log-Info ("   SQL: {0}" -f $d.Sql)
}
Log-Info (" 拟删除账号 ({0} 个，仅限 mysql.user 中真实存在的推导账号)：" -f $accountsToDrop.Count)
if ($accountsToDrop.Count -eq 0) {
  Log-Info '   （无匹配的推导账号存在于 mysql.user 中）'
} else {
  foreach ($a in $accountsToDrop) {
    Log-Info ("   SQL: {0}" -f $a.Sql)
  }
}
Log-Info '════════════════════════════════════════════════════════════════'

$isRealExecution = $Confirm -and (-not $DryRun)
if (-not $isRealExecution) {
  Log-Info ''
  Log-Info '[DRY-RUN 预演完成] 未执行任何 DROP 操作，退出码 0。'
  Log-Info '若确认真正执行清理，请附加 -Confirm 参数重新运行：'
  Log-Info ("  pwsh -File scripts/it-clean-isolation.ps1 -AllowedCleanDbs {0} -InstanceUuid '{1}' -Confirm" -f `
      (($vettedDbs | ForEach-Object { "'$_'" }) -join ', '), $InstanceUuid)
  exit 0
}

# ── 8. 真执行模式（-Confirm）────────────────────────────────────────────
Log-Info ''
Log-Info '开始执行清理...'

# 执行前重跑一次指纹探针
Log-Info '[1/3] 执行前重跑指纹探针核对...'
$probe2 = Test-InstanceFingerprint
Log-Info ("  [指纹重验 OK] port={0} uuid={1} hostname={2}" -f $probe2.Port, $probe2.Uuid, $probe2.Hostname)

# 逐项执行数据库删除
Log-Info '[2/3] 执行数据库删除...'
$droppedDbsCount = 0
foreach ($d in $dbsToDrop) {
  Log-Info ("  正在执行: {0}" -f $d.Sql)
  $dropDbRes = Invoke-MySqlQuery -Sql $d.Sql
  if ($dropDbRes.ExitCode -ne 0) {
    Fail 7 ("执行 '{0}' 失败（mysql 退出码 {1}）：{2}" -f $d.Sql, $dropDbRes.ExitCode, ($dropDbRes.Output -join ' '))
  }
  Log-Info ("  [OK] 数据库 '{0}' 已成功删除" -f $d.Db)
  $droppedDbsCount++
}

# 逐项执行受限账号删除
Log-Info '[3/3] 执行推导受限账号删除...'
$droppedUsersCount = 0
foreach ($a in $accountsToDrop) {
  Log-Info ("  正在执行: {0}" -f $a.Sql)
  $dropUserRes = Invoke-MySqlQuery -Sql $a.Sql
  if ($dropUserRes.ExitCode -ne 0) {
    Fail 7 ("执行 '{0}' 失败（mysql 退出码 {1}）：{2}" -f $a.Sql, $dropUserRes.ExitCode, ($dropUserRes.Output -join ' '))
  }
  Log-Info ("  [OK] 账号 '{0}'@'{1}' 已成功删除" -f $a.User, $a.Host)
  $droppedUsersCount++
}

Log-Info ''
Log-Info '════════════════════════════════════════════════════════════════'
Log-Info ("清理完成汇总：成功删除 {0} 个数据库，{1} 个受限账号。" -f $droppedDbsCount, $droppedUsersCount)
Log-Info '════════════════════════════════════════════════════════════════'
exit 0
