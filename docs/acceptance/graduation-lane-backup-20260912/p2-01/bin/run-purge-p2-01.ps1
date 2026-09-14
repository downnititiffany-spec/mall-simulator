# =====================================================================
# P2-01 泳道：Maven 运行封装（唯一入口，避免逐次手打 JVM 参数）
# 硬约束遵守：
#   - 不含任何删除类命令（无 Remove-Item / rd / del / ri / robocopy /MIR）
#   - 函数/变量名一律不与 PowerShell 内置别名同名（别名 > 函数 > cmdlet）
#   - Maven 一律 -o 离线
# 用法：
#   pwsh -File run-purge-p2-01.ps1 -Task e2                 # E2 全量 scalatest
#   pwsh -File run-purge-p2-01.ps1 -Task e2only -Suite X    # 只跑一个 suite
#   pwsh -File run-purge-p2-01.ps1 -Task e1                 # E1 package -DskipTests（会覆盖在产 jar!）
#   pwsh -File run-purge-p2-01.ps1 -Task platform           # 平台侧 platform-app -am
# =====================================================================
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('e2', 'e2only', 'e1', 'platform')]
  [string]$Task,
  [string]$Suite = '',
  [string]$LogName = ''
)

$ErrorActionPreference = 'Continue'

$mvnExe   = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$repoRoot = 'D:\Develop_code\GraduationProject'
$logDir   = 'D:\Develop_code\graduation-lane-backup\p2-01\logs'

if (-not (Test-Path -LiteralPath $mvnExe))   { throw "Maven 不存在：$mvnExe" }
if (-not (Test-Path -LiteralPath $repoRoot)) { throw "仓库不存在：$repoRoot" }
if (-not (Test-Path -LiteralPath $logDir))   { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

$env:JAVA_HOME           = 'D:\Develop\JAVA17'
$env:SPARK_DRIVER_MEMORY = '512m'

# JDK17 下 Spark 3.5.1 在 JVM 内运行需要开放这些包（实测 IllegalAccessError: sun.nio.ch.DirectBuffer）
$jvmExtra = @(
  '--add-exports=java.base/sun.nio.ch=ALL-UNNAMED',
  '--add-opens=java.base/java.nio=ALL-UNNAMED',
  '--add-opens=java.base/java.lang=ALL-UNNAMED',
  '--add-opens=java.base/java.util=ALL-UNNAMED',
  '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
  '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED'
) -join ' '

if ([string]::IsNullOrWhiteSpace($LogName)) {
  $LogName = "$Task-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log'
}
$logPath = Join-Path $logDir $LogName

$mvnArgs = switch ($Task) {
  'e1'       { @('-o', '-f', 'spark-jobs/pom.xml', 'package', '-DskipTests') }
  'e2'       { @('-o', '-f', 'spark-jobs/pom.xml', 'test-compile', 'scalatest:test', "-DargLine=$jvmExtra") }
  'e2only'   { @('-o', '-f', 'spark-jobs/pom.xml', 'test-compile', 'scalatest:test', "-DargLine=$jvmExtra", "-Dsuites=$Suite") }
  'platform' { @('-o', 'test', '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am', '-DforkCount=0') }
}

Write-Output "[runner] task=$Task log=$logPath"
Write-Output "[runner] mvn $($mvnArgs -join ' ')"

Push-Location -LiteralPath $repoRoot
try {
  & $mvnExe @mvnArgs *>&1 | Tee-Object -FilePath $logPath
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}

$logItem = Get-Item -LiteralPath $logPath
$logHash = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
Write-Output "[runner] EXITCODE=$code"
Write-Output "[runner] LOG_BYTES=$($logItem.Length)"
Write-Output "[runner] LOG_SHA256=$logHash"
exit $code
