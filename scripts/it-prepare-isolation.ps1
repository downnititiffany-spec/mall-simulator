# 测试隔离环境准备（V25-S03）
#
# ⚠️ 本轮**不得执行**本脚本。
#    执行门（两个条件都要满足）：
#      1. W03 交付 WSL 独立 MySQL 实例（端口 3307）；
#      2. parent 明确确认可以执行。
#    在此之前本脚本只作为"将创建哪些对象"的清单与实现，不产生任何副作用。
#
# 设计要点（V25-S02/S03 隔离判据）：
#   * 隔离实例 = **WSL 里另起的 MySQL**，端口 3307。宿主 3306 上的任何库
#     （含 mall_simulator_test、generator_meta、所有 analytics_*）都不是隔离环境。
#   * 每次运行用 **runId 前缀**命名库与账号：库名必须带 runId，
#     因此"复用同名库"在这套命名下不可能发生。
#   * 账号是**本次运行自己的受限账号**（只对本 runId 的库有权限）。
#     应用/测试连接**绝不用** root / metric_pub / mall_app / meta_app；
#     只有"在隔离实例(3307)上建库+建账号"这一步需要管理员身份，
#     且必须显式给出 -AllowRootOnIsolated 才执行。
#   * 口令**不落明文到任何入库文件**：脚本生成随机口令 → 写入仓内且被 gitignore
#     覆盖的 credref-<runId>-{mall,generator}.properties（位于两模块工作目录，
#     即守卫 requireCredential 的 CWD 候选路径），配置文件里只放 credref:<id> 引用。
#     文件格式按守卫实际读取的键名写：单键 password（不是 mall.password）。
#   * 幂等：CREATE DATABASE/USER 都带 IF NOT EXISTS；重复执行不报错、不重复建。
#   * 执行前先打印"将创建的对象清单"（-WhatIf 也支持）。
#
# 用法（待 W03 + parent 确认后）：
#   pwsh -File scripts/it-prepare-isolation.ps1 -RunId mallit-20260914-130000-a1b2 -DryRun
#   pwsh -File scripts/it-prepare-isolation.ps1 -RunId <runId> -Confirm
param(
  # 本次运行标识：也是库名/账号名的前缀。形状校验见下。
  [Parameter(Mandatory = $true)][string]$RunId,
  # 隔离实例（WSL 内的 MySQL）。端口必须落在白名单。
  # ⚠️ 参数名**不能**叫 Host：$Host 是 PowerShell 只读自动变量，参数绑定阶段即抛
  #    「无法覆盖变量 Host，因为它是只读变量或常量」——-File 下 exit 1，
  #    -Command 下 exit 0 **静默失败**（L5 2026-09-14 实测，4 种调用方式全失败）。
  #    保留 -Host / -HostName 作为别名，兼容既有调用写法。
  [Alias('Host', 'HostName')][string]$DbHost = '127.0.0.1',
  [int]$Port = 3307,
  # 允许的隔离实例端口清单（与 TestIsolationGuard.ALLOWED_INSTANCE_PORTS 对齐）
  [int[]]$AllowedPorts = @(3307),
  # WSL 发行版名（留空用默认发行版）
  [string]$WslDistro = '',
  # 只需看清单、不做任何改动（默认行为，见下）
  [switch]$DryRun,
  # 真正执行。**未显式给出时脚本只打印清单并退出**（默认拒绝执行）。
  [switch]$Confirm,
  # 允许在本机隔离实例(3307)上用 root 做建库/授权。脚本不改动 root 本身的口令。
  # 未显式给出时，即使 -Confirm 也拒绝执行（防止"顺手用 root"）。
  [switch]$AllowRootOnIsolated,
  # 凭据文件目录。留空 = 直接写到两个模块的工作目录（守卫的 CWD 候选路径，
  # 仓内且被 .gitignore 覆盖），**不再写仓库外**（用户 2026-09-14 硬约束）。
  [string]$CredRefDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ── 0. 门禁：默认不执行 ────────────────────────────────────────────────
$execute = $Confirm -and (-not $DryRun)

# ── 1. runId 形状校验（与 TestIsolationGuard.TEST_RUN_ID 同一形状）────
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$') {
  Write-Host "拒绝：-RunId '$RunId' 形状非法（要求 ^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$）。"
  Write-Host '  例：mallit-20260914-130000-a1b2'
  exit 2
}

# ── 2. 端口白名单校验：宿主 3306 一律拒绝 ──────────────────────────────
if ($AllowedPorts -notcontains $Port) {
  Write-Host ("拒绝：-Port {0} 不在允许的隔离实例端口清单 {1} 内。" -f $Port, ($AllowedPorts -join ', '))
  if ($Port -eq 3306) {
    Write-Host '  3306 是宿主正式 MySQL 实例：它上面的任何库（含 *_test）都不是隔离环境。'
  }
  exit 2
}
if ($DbHost -notin @('127.0.0.1', 'localhost', '::1')) {
  Write-Host "拒绝：-DbHost '$DbHost' 不是本机。隔离实例必须是本机 WSL 成员。"
  exit 2
}

# ── 2b. root 门：默认拒绝在隔离实例上用 root（先于任何落盘/连接）─────────
if ($execute -and (-not $AllowRootOnIsolated)) {
  Write-Host '拒绝：真正执行需要管理员身份建空库/建受限账号，但未显式给出 -AllowRootOnIsolated。'
  Write-Host '  约定：应用与测试的连接一律不用 root；只有本步（隔离实例 3307）例外。'
  Write-Host ("  确认后重跑：pwsh -File scripts/it-prepare-isolation.ps1 -RunId {0} -Confirm -AllowRootOnIsolated" -f $RunId)
  exit 2
}

# ── 3. 将创建的对象清单（**先打印，再决定是否执行**）──────────────────
$mallDb      = "${RunId}_mall"
$generatorDb = "${RunId}_generator"
$mallUser    = "${RunId}_mallapp"
$genUser     = "${RunId}_genapp"
# 守卫 requireCredential 的候选路径 = 各模块的工作目录（CWD-relative）。
# surefire 的 CWD 就是模块目录，故凭据文件写到模块根即可被找到；
# 两处都在仓库内，且被 .gitignore 的 credref-*.properties 覆盖。
$repoRoot        = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mallDir         = if ($CredRefDir) { $CredRefDir } else { Join-Path $repoRoot 'mall-simulator' }
$genDir          = if ($CredRefDir) { $CredRefDir } else { Join-Path $repoRoot 'synthetic-data-generator' }
$credRefIdMall   = "$RunId-mall"
$credRefIdGen    = "$RunId-generator"
$credRefFileMall = Join-Path $mallDir "credref-$credRefIdMall.properties"
$credRefFileGen  = Join-Path $genDir  "credref-$credRefIdGen.properties"

Write-Host '════════════════════════════════════════════════════════════════'
Write-Host ' 测试隔离环境准备 —— 将创建的对象清单'
Write-Host '════════════════════════════════════════════════════════════════'
Write-Host (" 实例        : {0}:{1}（WSL 内独立 MySQL；宿主 3306 不参与）" -f $DbHost, $Port)
Write-Host (" runId       : {0}" -f $RunId)
Write-Host ' 将创建（幂等，IF NOT EXISTS）：'
Write-Host ("   数据库     : {0}" -f $mallDb)
Write-Host ("   数据库     : {0}" -f $generatorDb)
Write-Host ("   受限账号   : {0}@'%'  （只对 {1} 有权限）" -f $mallUser, $mallDb)
Write-Host ("   受限账号   : {0}@'%'  （只对 {1} 有权限）" -f $genUser, $generatorDb)
Write-Host (" 将写入的凭据文件（仓内、gitignore 覆盖、单键 password）：")
Write-Host ("               : {0}" -f $credRefFileMall)
Write-Host ("               : {0}" -f $credRefFileGen)
Write-Host ' 不会做的事：'
Write-Host '   * 不触碰宿主 3306 上的任何库'
Write-Host '   * 不创建/修改 root、metric_pub、mall_app、meta_app 等正式账号'
Write-Host '   * 不写入任何业务数据（只建空库 + 授权）'
Write-Host '   * 不修改仓库内任何已提交文件'
Write-Host '   * 不在仓库外创建任何文件（凭据文件落两模块工作目录）'
Write-Host '════════════════════════════════════════════════════════════════'

if (-not $execute) {
  Write-Host ''
  Write-Host '默认不执行：上面只是清单。'
  Write-Host '  确认 W03 已交付 3307 实例、且 parent 已确认后，加 -Confirm 重跑：'
  Write-Host ("    pwsh -File scripts/it-prepare-isolation.ps1 -RunId {0} -Confirm" -f $RunId)
  exit 0
}

# ── 4. 生成随机口令（不落明文到仓库）──────────────────────────────────
function New-Secret([int]$len = 24) {
  $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
  $bytes = New-Object 'System.Byte[]' $len
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}
$mallPwd = New-Secret
$genPwd  = New-Secret

if ($CredRefDir -and -not (Test-Path $CredRefDir)) { New-Item -ItemType Directory -Force -Path $CredRefDir | Out-Null }
@(
  "# 测试隔离凭据引用（V25-S03）。仓内文件，被 .gitignore 的 credref-*.properties 覆盖：禁止提交。",
  "# runId=$RunId instance=$DbHost`:$Port user=$mallUser",
  "username=$mallUser",
  "password=$mallPwd"
) | Set-Content -Path $credRefFileMall -Encoding utf8
@(
  "# 测试隔离凭据引用（V25-S03）。仓内文件，被 .gitignore 的 credref-*.properties 覆盖：禁止提交。",
  "# runId=$RunId instance=$DbHost`:$Port user=$genUser",
  "username=$genUser",
  "password=$genPwd"
) | Set-Content -Path $credRefFileGen -Encoding utf8
Write-Host ("[1/2] 已写凭据文件：{0}" -f $credRefFileMall)
Write-Host ("               : {0}（均不回显口令）" -f $credRefFileGen)

# ── 5. 在 WSL 内执行 SQL（幂等）────────────────────────────────────────
$sql = @"
CREATE DATABASE IF NOT EXISTS ``$mallDb`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ``$generatorDb`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$mallUser'@'%' IDENTIFIED BY '$mallPwd';
CREATE USER IF NOT EXISTS '$genUser'@'%'  IDENTIFIED BY '$genPwd';
ALTER USER '$mallUser'@'%' IDENTIFIED BY '$mallPwd';
ALTER USER '$genUser'@'%'  IDENTIFIED BY '$genPwd';
GRANT ALL PRIVILEGES ON ``$mallDb``.* TO '$mallUser'@'%';
GRANT ALL PRIVILEGES ON ``$generatorDb``.* TO '$genUser'@'%';
FLUSH PRIVILEGES;
SELECT 'created', SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('$mallDb','$generatorDb');
SELECT 'granted', GRANTEE, TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE LIKE '%$mallUser%' OR GRANTEE LIKE '%$genUser%';
"@
# 注意：新建账号的口令经 stdin（SQL 文本）传入，不出现在命令行/进程列表。
# 管理员(root)口令如提供，只经环境变量 MYSQL_PWD 进入 WSL 内的 mysql 进程；
# 它只作用于本机隔离实例 3307，不涉及宿主 3306；不回显、不落盘。
$wslArgs = if ($WslDistro) { @('-d', $WslDistro) } else { @() }
$adminArgs = @()
if ($env:V25IT_ADMIN_PWD) {
  $adminArgs = @('env', "MYSQL_PWD=$($env:V25IT_ADMIN_PWD)")
  Write-Host '[admin] 管理员口令取自环境变量 V25IT_ADMIN_PWD（不回显）。'
} else {
  Write-Host '[admin] 未提供 V25IT_ADMIN_PWD：按 WSL 内 root 免密(auth_socket)尝试；失败则退出码 3。'
}
$sql | & wsl @wslArgs -- @adminArgs mysql -h 127.0.0.1 -P $Port -uroot --batch --silent 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host ("[2/2] ⚠️ 建库/授权失败（wsl mysql 退出码 {0}）。" -f $LASTEXITCODE)
  Write-Host '      常见原因：W03 的 3307 实例未起、或 WSL 内 mysql 客户端不在 PATH。'
  Write-Host '      凭据文件已生成；修好实例后重跑本脚本（幂等）。'
  exit 3
}
Write-Host '[2/2] 建库/授权完成。'

# ── 6. 打印可直接使用的配置片段（供人工写入隔离档案）──────────────────
Write-Host ''
Write-Host '把下面内容写入各模块的隔离档案（口令只放引用，不放明文）：'
Write-Host ("  # mall-simulator/mall-isolation.local.properties（写到模块工作目录，即 CWD 候选；勿提交）")
Write-Host ("  enabled=true")
Write-Host ("  testRunId={0}" -f $RunId)
Write-Host ("  serverFingerprint=<在 3307 实例上执行 SELECT @@hostname 得到；若与宿主同名，用 port/uuid 区分>")
Write-Host ("  mysql.host={0}:{1}" -f $DbHost, $Port)
Write-Host ("  mysql.url=jdbc:mysql://{0}:{1}/{2}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true" -f $DbHost, $Port, $mallDb)
Write-Host ("  mysql.username={0}" -f $mallUser)
Write-Host ("  mysql.password=credref:{0}" -f $credRefIdMall)
Write-Host ("  flyway.enabled=true")
Write-Host ''
Write-Host ("  # synthetic-data-generator/it-guard.local.properties（写到模块工作目录，即 CWD 候选；勿提交）")
Write-Host ("  # 键名 runId/url/user/password/instancePorts")
Write-Host ("  runId={0}" -f $RunId)
Write-Host ("  instancePorts={0}" -f $Port)
Write-Host ("  url=jdbc:mysql://{0}:{1}/{2}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true" -f $DbHost, $Port, $generatorDb)
Write-Host ("  user={0}" -f $genUser)
Write-Host ("  password=credref:{0}" -f $credRefIdGen)
Write-Host ''
Write-Host '  # 注意：这两个档案只被各模块的门禁(IsolationGuard)读取，Spring 不读它们；'
Write-Host '  #       数据源地址仍须用环境变量/系统属性提供（见 application-test.yml）。'
exit 0
