# 隔离档唯一入口：跑 mall-simulator / synthetic-data-generator 的隔离集成套件（V25-S02 闭环）
#
# 解决什么：`scripts/it-prepare-isolation.ps1` 建好 3307 上的空库/受限账号后，只把两段
# 配置片段**打印**出来，由人手工抄进模块工作目录的 `*.local.properties`，口令还写成
# `credref:<id>`。手工环节既是漏配源，也让「真值落盘」无法收口。
# 本脚本按 2026-09-14 用户裁决 A 收口：
#   * 模板落库：`scripts/it-isolation.env.template`（只有占位符）；
#   * 真值只走环境变量：本脚本把值注入**当前进程**环境后拉起 Maven，不写任何文件、不落盘；
#   * 环境变量优先级高于残留档案（门禁取值顺序：系统属性 → 环境变量 → 档案），
#     所以旧的手抄档案即使还在，也不会把本次运行带偏。
#
# 关键安全门（任一不过即拒绝，且先于任何 SQL 与任何 Maven 调用）：
#   1) 端口必须 ∈ -AllowedPorts 且 != 3306（宿主正式实例，一次误跑可能是不可逆迁移）
#   2) RunId 形状合法（库名/账号名都由它派生，形状错会打到别的库）
#   3) 账号一律 root/mall_app 之类正式账号 ⇒ 拒绝
#   4) 口令只从环境变量取，缺失即拒（本脚本**没有** -Password 参数，避免出现在进程列表）
#   5) 未显式 -Confirm 不跑（除 -DryRun）
#   6) 只读探针：用**本次 runId 的受限账号**连目标实例，核对 @@port / @@server_uuid /
#      目标库存在性 —— 同时验证"账号能连"和"实例是隔离实例"，并据此得出**唯一的实例身份**
#      `@@hostname:@@port`（探针读到的真实值，不写死）注入 IT_GUARD_SERVERFINGERPRINT。
#      DEV-002：此前注入的是 `@@server_uuid`，而 mall/generator 两侧 `IsolationGuard.fingerprintMatches`
#      只认 hostname / hostname:port / port / 127.0.0.1:port / localhost:port —— 两侧判据都不认 uuid，
#      于是登录成功的用例在写前校验里被判"服务实例指纹不匹配"（30 个 mall 用例假红）。
#      `@@server_uuid` 不取消：它继续作为「是不是那台预定隔离实例」的独立校验（门禁 6 的 Fail 5）。
#
# 取值顺序与键名依据（源码事实）：
#   * `itguard.IsolationGuard`（mall / generator 两侧同源）：`-Dit.guard.<key>` → `IT_GUARD_<KEY>`
#     → 档案；本脚本用环境变量通道，键名严格照抄 `envName()`：`IT_GUARD_INSTANCEPORTS`
#     （instancePorts 全大写、**无下划线**）、`IT_GUARD_RUNID`、`IT_GUARD_SERVERFINGERPRINT`、
#     `IT_GUARD_URL` / `IT_GUARD_USER` / `IT_GUARD_PASSWORD`。
#   * mall 的 Spring 测试上下文读 `${MALL_ISOLATION_URL|USER|PASSWORD|FLYWAY_ENABLED}`
#     （`mall-simulator/src/test/resources/application-test.yml`，**无默认值**）。
#   * generator 的 Spring 测试上下文**没有**自己的 test application.yml，直接用主
#     `synthetic-data-generator/src/main/resources/application.yml`——那份配置指向
#     `jdbc:mysql://127.0.0.1:3306/generator_meta?...createDatabaseIfNotExist=true`、flyway
#     `classpath:db/generator`。必须用 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 这个标准
#     relaxed-binding 通道覆盖到 3307 隔离库，否则 generator 的 Spring 用例会连宿主正式实例
#     （DEV-002；3306 冻结期尤其不能靠"它恰好没有迁移"来兜底）。
#   * 库名/账号名与 `scripts/it-prepare-isolation.ps1:89-92` 同源：`<runId>_mall` /
#     `<runId>_generator` / `<runId>_mallapp` / `<runId>_genapp`。
#
# 退出码（对齐 scripts/smoke-pipeline.ps1:32 的 0/2/3/4/5/6 契约中与本脚本相关的语义）：
#   0 = 全部模块套件通过
#   1 = 参数/环境错误（找不到 mvn、脚本自身错误）
#   5 = 执行前被门禁拒绝（端口/形状/账号/缺口令/未确认/指纹不符/目标库不存在）
#   6 = 只读探针取数失败（连不上、查不到）
#   7 = Maven 套件失败（有模块非 0 退出；各模块退出码在输出中列出）
#
# 用法：
#   # 1) 预演：不连库、不跑 Maven、不落盘，只打印门禁与将要注入的环境变量（口令掩码）
#   pwsh -NoProfile -File scripts/run-isolated-tests.ps1 -RunId v25it_20260914_1700_ctl1 -DryRun
#   # 2) 真跑（先跑 it-prepare-isolation.ps1 建好库/账号；口令经环境变量给）
#   $env:IT_GUARD_PASSWORD = '<本 runId 的受限账号口令>'
#   pwsh -NoProfile -File scripts/run-isolated-tests.ps1 -RunId v25it_20260914_1700_ctl1 -Module both -Confirm
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [ValidateSet('mall', 'generator', 'both')][string]$Module = 'both',
  [Alias('Host', 'HostName')][string]$DbHost = '127.0.0.1',
  [int]$Port = 3307,
  [int[]]$AllowedPorts = @(3307),
  [string]$InstanceUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd',
  [string]$MavenRepoLocal = 'D:\maven_repository',
  [string]$LogDir = '',
  [switch]$DryRun,
  [switch]$Confirm
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

$FormalPort = 3306
$RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$'
$ForbiddenAccounts = @('root', 'mall_app', 'generator_app', 'mysql.sys', 'mysql.session')

function Fail([int]$code, [string]$msg) {
  Write-Host ("[REFUSE exit={0}] {1}" -f $code, $msg)
  exit $code
}
function Mask([string]$v) {
  if (-not $v) { return '<未设置>' }
  if ($v.Length -le 2) { return '<已设置:长度 ' + $v.Length + '>' }
  $dots = if ($v.Length -gt 7) { '…' } else { '' }
  return ('<' + $v.Substring(0, 1) + ('*' * ([Math]::Min(6, $v.Length - 1))) + $dots + '长度 ' + $v.Length + '>')
}

Write-Host '=== 隔离套件运行门禁（V25-S02 唯一入口）==='
Write-Host ("  目标      : {0}:{1}  （库前缀 {2}_*）" -f $DbHost, $Port, $RunId)
Write-Host ("  实例指纹  : 运行期由门禁6探针取 @@hostname:@@port 注入 IT_GUARD_SERVERFINGERPRINT；并校验 @@server_uuid = {0}" -f $InstanceUuid)
Write-Host ("  模块      : {0}" -f $Module)

# ── 门禁 1：端口 ───────────────────────────────────────────────────────────
if ($AllowedPorts -notcontains $Port) {
  Fail 5 ("端口 {0} 不在允许清单 [{1}] 内：拒绝（隔离实例只允许白名单端口）" -f $Port, ($AllowedPorts -join ','))
}
if ($Port -eq $FormalPort) {
  Fail 5 ("端口 {0} 是宿主正式实例：拒绝（正式库禁止任何写入型验证）" -f $FormalPort)
}
Write-Host ("  [门禁1] 端口 OK：{0} ∈ [{1}] 且 != {2}" -f $Port, ($AllowedPorts -join ','), $FormalPort)

# ── 门禁 2：runId 形状 ─────────────────────────────────────────────────────
if ($RunId -notmatch $RunIdPattern) {
  Fail 5 ("RunId '{0}' 形状非法（要求 {1}）：库名/账号名由它派生，形状错会打到别的库" -f $RunId, $RunIdPattern)
}
Write-Host ("  [门禁2] RunId 形状 OK：{0}" -f $RunId)

# ── 门禁 3：派生目标名 + 账号禁清单 ────────────────────────────────────────
$targets = @()
if ($Module -in @('mall', 'both')) {
  $targets += [pscustomobject]@{
    name = 'mall'; db = "${RunId}_mall"; user = "${RunId}_mallapp"
    pwdEnv = @('IT_GUARD_PASSWORD_MALL', 'IT_GUARD_PASSWORD')
    pom = 'mall-simulator\pom.xml'
  }
}
if ($Module -in @('generator', 'both')) {
  $targets += [pscustomobject]@{
    name = 'generator'; db = "${RunId}_generator"; user = "${RunId}_genapp"
    pwdEnv = @('IT_GUARD_PASSWORD_GENERATOR', 'IT_GUARD_PASSWORD')
    pom = 'synthetic-data-generator\pom.xml'
  }
}
foreach ($t in $targets) {
  if ($ForbiddenAccounts -contains $t.user) { Fail 5 ("账号 {0} 在禁用清单内：拒绝" -f $t.user) }
  if ($t.db -in @('mall_simulator', 'generator_meta', 'analytics_meta', 'analytics_metric')) {
    Fail 5 ("目标库 {0} 是正式库名：拒绝" -f $t.db)
  }
}
Write-Host ("  [门禁3] 派生目标 OK：{0}" -f (($targets | ForEach-Object { "{0}->{1}/{2}" -f $_.name, $_.db, $_.user }) -join '  '))

# ── 门禁 4：口令只从环境变量取 ─────────────────────────────────────────────
foreach ($t in $targets) {
  $pwd = $null; $src = $null
  foreach ($k in $t.pwdEnv) {
    $v = [Environment]::GetEnvironmentVariable($k, 'Process')
    if ($v) { $pwd = $v; $src = $k; break }
  }
  Add-Member -InputObject $t -NotePropertyName pwd -NotePropertyValue $pwd
  Add-Member -InputObject $t -NotePropertyName pwdSrc -NotePropertyValue $src
  Write-Host ("  [门禁4] {0,-9} 口令来源 {1,-28} 值 {2}" -f $t.name, ($src ?? '<缺失>'), (Mask $pwd))
}
$missing = @($targets | Where-Object { -not $_.pwd })
if ($missing.Count -gt 0) {
  if ($DryRun) {
    Write-Host ("  [门禁4] ⚠️ 预演模式：{0} 缺口令（真跑会被拒，退出码 5）" -f (($missing | ForEach-Object { $_.name }) -join ','))
  } else {
    Fail 5 ("缺口令：{0}。请设置环境变量 IT_GUARD_PASSWORD（或 {1}）；本脚本不提供 -Password 参数。" -f `
        (($missing | ForEach-Object { $_.name }) -join ','), (($missing | ForEach-Object { $_.pwdEnv[0] }) -join '/'))
  }
}

# ── 门禁 5：显式确认 ───────────────────────────────────────────────────────
if (-not $DryRun -and -not $Confirm) {
  Fail 5 '未显式给出 -Confirm：拒绝（真实运行会连隔离实例并执行写入型测试）'
}
Write-Host ("  [门禁5] 确认 OK：{0}" -f ($(if ($DryRun) { '-DryRun 预演' } else { '-Confirm' })))

# ── 构造环境变量清单（真值只在内存）─────────────────────────────────────────
# 实例身份只有一处：`@@hostname:@@port`（门禁 6 探针从目标实例读到后填进来）。
# 不在这里写死，也不用 server_uuid 冒充——mall/generator 两侧判据的共同接受形态就是它。
$InstanceFingerprint = '<运行期由门禁6探针确定：@@hostname:@@port>'
$envCommon = [ordered]@{
  'IT_GUARD_ENABLED'          = 'true'
  'IT_GUARD_INSTANCEPORTS'    = "$Port"
  'IT_GUARD_RUNID'            = $RunId
  'IT_GUARD_SERVERFINGERPRINT' = $InstanceFingerprint
}
foreach ($t in $targets) {
  $t | Add-Member -NotePropertyName url -NotePropertyValue ('jdbc:mysql://{0}:{1}/{2}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true' -f $DbHost, $Port, $t.db)
}
Write-Host ''
Write-Host '将要注入当前进程的环境变量（真值不落盘、不进命令行；口令 masked）：'
foreach ($k in $envCommon.Keys) { Write-Host ("  {0,-28} = {1}" -f $k, $envCommon[$k]) }
foreach ($t in $targets) {
  $tag = '(' + $t.name + ')'
  Write-Host ("  {0,-28} = {1}   {2}" -f 'IT_GUARD_URL', $t.url, $tag)
  Write-Host ("  {0,-28} = {1}   {2}" -f 'IT_GUARD_USER', $t.user, $tag)
  Write-Host ("  {0,-28} = {1}   {2}" -f 'IT_GUARD_PASSWORD', (Mask $t.pwd), $tag)
}
Write-Host '  说明：IT_GUARD_URL/USER/PASSWORD 为共用键名，本脚本在每个模块运行前重设为本模块的值，'
Write-Host '        因此两个模块不会串用彼此的口令/库（见下方循环内的重设语句）。'
Write-Host '        IT_GUARD_SERVERFINGERPRINT 由门禁6探针读到 @@hostname:@@port 后填入（预演阶段还没有值）。'
if ($Module -in @('generator', 'both')) {
  $genTarget = $targets | Where-Object { $_.name -eq 'generator' }
  Write-Host ("  {0,-28} = {1}" -f 'SPRING_DATASOURCE_URL', $genTarget.url)
  Write-Host ("  {0,-28} = {1}" -f 'SPRING_DATASOURCE_USERNAME', $genTarget.user)
  Write-Host ("  {0,-28} = {1}" -f 'SPRING_DATASOURCE_PASSWORD', (Mask $genTarget.pwd))
  Write-Host '        ↑ generator 的 Spring 测试上下文没有自己的 test application.yml，必须靠这三个键'
  Write-Host '          覆盖主 application.yml 里的 3306 地址（否则会连宿主正式实例）。'
}
if ($Module -in @('mall', 'both')) {
  $mallTarget = $targets | Where-Object { $_.name -eq 'mall' }
  Write-Host ("  {0,-28} = {1}" -f 'MALL_ISOLATION_URL', $mallTarget.url)
  Write-Host ("  {0,-28} = {1}" -f 'MALL_ISOLATION_USER', "${RunId}_mallapp")
  Write-Host ("  {0,-28} = {1}" -f 'MALL_ISOLATION_PASSWORD', (Mask $mallTarget.pwd))
  Write-Host ("  {0,-28} = {1}" -f 'MALL_ISOLATION_FLYWAY_ENABLED', 'true')
}

if ($DryRun) {
  Write-Host ''
  Write-Host '[预演结束] 未连库、未跑 Maven、未落盘。去掉 -DryRun 并加 -Confirm 才会真跑。'
  exit 0
}

# ── 门禁 6：只读探针（用本次 runId 的受限账号）──────────────────────────────
if (-not (Test-Path -LiteralPath $MysqlExe)) { Fail 1 ("找不到 mysql 客户端：{0}" -f $MysqlExe) }
foreach ($t in $targets) {
  $sql = "SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@hostname,'|', (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$($t.db)')) AS probe;"
  $old = $env:MYSQL_PWD
  $env:MYSQL_PWD = $t.pwd
  try {
    $out = & $MysqlExe "--host=$DbHost" "--port=$Port" "--user=$($t.user)" '--batch' '--silent' '--skip-column-names' '-e' $sql 2>&1
    $code = $LASTEXITCODE
  } finally {
    if ($null -eq $old) { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $old }
  }
  if ($code -ne 0) {
    Fail 6 ("只读探针取数失败（{0} 账号连 {1}:{2} 退出码 {3}）：{4}`n       先确认 3307 实例在跑、且已执行 scripts/it-prepare-isolation.ps1 建好 {5} 与账号 {6}。" -f `
        $t.name, $DbHost, $Port, $code, ($out -join ' '), $t.db, $t.user)
  }
  $line = ($out | Where-Object { $_ -match '\|' } | Select-Object -First 1)
  $parts = "$line".Split('|')
  if ($parts.Count -lt 4) { Fail 6 ("只读探针输出不可解析：{0}" -f ($out -join ' ')) }
  if ($parts[0] -ne "$Port") { Fail 5 ("探针读到的 @@port={0} 与请求 {1} 不符：拒绝" -f $parts[0], $Port) }
  if ($parts[1] -ne $InstanceUuid) { Fail 5 ("实例指纹不符：@@server_uuid={0}，期望 {1}（疑似打到别的实例）：拒绝" -f $parts[1], $InstanceUuid) }
  if ([int]$parts[3] -lt 1) { Fail 5 ("目标库 {0} 不存在：先执行 scripts/it-prepare-isolation.ps1 -RunId {1} -Confirm -AllowRootOnIsolated" -f $t.db, $RunId) }
  # 唯一的实例身份 = 探针读到的 @@hostname:@@port（真实值；不写死，也不用 server_uuid 冒充）
  $probeFingerprint = '{0}:{1}' -f $parts[2], $parts[0]
  if ($InstanceFingerprint -like '<*') {
    $InstanceFingerprint = $probeFingerprint
  } elseif ($InstanceFingerprint -ne $probeFingerprint) {
    Fail 5 ("同一轮里不同模块连到了不同实例：{0} vs {1}（实例漂移，拒绝）：" -f $InstanceFingerprint, $probeFingerprint)
  }
  Write-Host ("  [门禁6] {0,-9} 探针 OK：port={1} uuid={2} hostname={3} hist={4} 库存在" -f `
      $t.name, $parts[0], $parts[1], $parts[2], $parts[3])
}
$envCommon['IT_GUARD_SERVERFINGERPRINT'] = $InstanceFingerprint
Write-Host ("  [门禁6] 唯一的实例身份（注入 IT_GUARD_SERVERFINGERPRINT）= {0}" -f $InstanceFingerprint)

# ── 注入环境变量并跑套件 ───────────────────────────────────────────────────
if (-not $LogDir) { $LogDir = Join-Path $env:TEMP ("v25it-logs-{0}" -f $RunId) }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (-not (Test-Path -LiteralPath $MavenCmd)) { Fail 1 ("找不到 Maven：{0}" -f $MavenCmd) }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\Develop\JAVA17' }

$results = @()
foreach ($t in $targets) {
  # 每次运行前把"本次模块"的 guard 值落到共用键上（避免上一个模块的值残留）
  $env:IT_GUARD_ENABLED = 'true'
  $env:IT_GUARD_INSTANCEPORTS = "$Port"
  $env:IT_GUARD_RUNID = $RunId
  # 三个模块（mall / generator / analytics）共用同一份实例身份：探针读到的 @@hostname:@@port
  $env:IT_GUARD_SERVERFINGERPRINT = $InstanceFingerprint
  $env:IT_GUARD_URL = $t.url
  $env:IT_GUARD_USER = $t.user
  $env:IT_GUARD_PASSWORD = $t.pwd
  if ($t.name -eq 'mall') {
    $env:MALL_ISOLATION_URL = $t.url
    $env:MALL_ISOLATION_USER = $t.user
    $env:MALL_ISOLATION_PASSWORD = $t.pwd
    $env:MALL_ISOLATION_FLYWAY_ENABLED = 'true'
  }
  if ($t.name -eq 'generator') {
    # generator 的 Spring 测试上下文没有自己的 test application.yml：用标准 relaxed binding 键
    # 把主 application.yml 的 127.0.0.1:3306/generator_meta 覆盖成本次隔离库（DEV-002）
    $env:SPRING_DATASOURCE_URL = $t.url
    $env:SPRING_DATASOURCE_USERNAME = $t.user
    $env:SPRING_DATASOURCE_PASSWORD = $t.pwd
  } else {
    # 不留给别的模块：mall 的上下文不吃这三个键，残留只会让下一次运行的目标变得不可预期
    Remove-Item Env:\SPRING_DATASOURCE_URL, Env:\SPRING_DATASOURCE_USERNAME, `
        Env:\SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
  }
  # -Pisolated-tests 经 MAVEN_ARGS 注入（.cmd 包装器正是为绕开 pwsh→cmd 的 '-' 拆参问题）
  $env:MAVEN_ARGS = '-Pisolated-tests'
  $log = Join-Path $LogDir ("isolated-{0}.log" -f $t.name)
  Write-Host ''
  Write-Host ("=== 运行 {0} 隔离套件：mvn -o -Dmaven.repo.local=... -f {1} test （MAVEN_ARGS=-Pisolated-tests）===" -f $t.name, $t.pom)
  & $MavenCmd -o "-Dmaven.repo.local=$MavenRepoLocal" -f $t.pom test 2>&1 | Set-Content -LiteralPath $log -Encoding utf8
  $code = $LASTEXITCODE
  $tests = (Select-String -LiteralPath $log -Pattern 'Tests run:' | Select-Object -Last 1)
  $results += [pscustomobject]@{ module = $t.name; exit = $code; summary = $(if ($tests) { $tests.Line.Trim() } else { '<无 Tests run 行>' }); log = $log }
  Write-Host ("  {0,-9} exit={1}   {2}" -f $t.name, $code, $results[-1].summary)
}
Remove-Item Env:\MAVEN_ARGS -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=== 结果 ==='
foreach ($r in $results) { Write-Host ("  {0,-9} exit={1}  {2}`n            日志 {3}" -f $r.module, $r.exit, $r.summary, $r.log) }
$bad = @($results | Where-Object { $_.exit -ne 0 })
if ($bad.Count -gt 0) {
  Write-Host ("[FAIL exit=7] {0} 个模块套件非 0 退出。" -f $bad.Count)
  exit 7
}
Write-Host '[PASS exit=0] 全部门块隔离套件通过。'
exit 0
