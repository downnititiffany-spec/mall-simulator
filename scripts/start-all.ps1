# 一键启动（三个程序：分析平台 + 模拟商城 + 合成数据生成器；三个进程、三个端口、三个库）
#   分析平台 analytics-platform     :8091  ← 分析前端（analytics-web/，看板/AI/决策/流水线），库 analytics_meta + analytics_metric
#   模拟商城 reference-mall        :8090  ← 商城前端（mall-frontend/，商城演示/商品后台），库 mall_simulator
#   合成数据生成器 synthetic-data-generator :8092 ← 生成器 API（文件模式 CLI 亦可），库 generator_meta
# 边界：指导书 V2.1 §3.1/§3.4——三个程序各自的产物、配置、端口、日志、启动命令；停任一个不影响其余。
#       M1-8 验收记录见 docs/acceptance/M1-8-三程序独立启停验收记录.md。
#
# 用法:
#   pwsh -File scripts/start-all.ps1                          # 三个都起
#   pwsh -File scripts/start-all.ps1 -PlatformOnly             # 只起分析平台（看板演示）
#   pwsh -File scripts/start-all.ps1 -MallOnly                 # 只起模拟商城（商城演示）
#   pwsh -File scripts/start-all.ps1 -GeneratorOnly            # 只起生成器（造数据）
# 前置: pwsh -File scripts/build-web-and-package.ps1（商城/生成器各自也可独立构建：见接受验收记录 §2）
# 环境变量: MALL_DB_PASSWORD（商城库口令，默认取本机 LOCAL 演示口令）
param(
  [switch]$PlatformOnly,
  [switch]$MallOnly,
  [switch]$GeneratorOnly,
  [string]$MallDbPassword = $(if ($env:MALL_DB_PASSWORD) { $env:MALL_DB_PASSWORD } else { '123456' })
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.verify'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Wait-Http {
  param([string]$Url, [int]$Tries = 45, [string]$Log)
  foreach ($try in 1..$Tries) {
    Start-Sleep -Seconds 2
    try {
      $r = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
      if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $true }
    } catch {}
  }
  if ($Log -and (Test-Path $Log)) { Get-Content $Log -Tail 12 | ForEach-Object { Write-Host "  $_" } }
  return $false
}

function Start-Jar {
  param([string]$JarGlob, [string[]]$JvmArgs, [string]$LogName)
  $jar = Get-ChildItem $JarGlob -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
  if (-not $jar) { throw "未找到 jar（$JarGlob），请先运行 scripts/build-web-and-package.ps1" }
  $log = Join-Path $logDir $LogName
  Start-Process -FilePath 'java' -ArgumentList (@('-Dfile.encoding=UTF-8', '-jar', $jar.FullName) + $JvmArgs) `
    -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  Write-Host "  已启动 $($jar.Name)（日志 $log）"
  return $log
}

# 单选互斥：显式指定某一个时只起那一个
$startPlatform = -not ($MallOnly -or $GeneratorOnly)
$startMall = -not ($PlatformOnly -or $GeneratorOnly)
$startGenerator = -not ($PlatformOnly -or $MallOnly)

if ($startPlatform) {
  Write-Host '[1/6] 启动分析平台（analytics-platform, 8091）...'
  # 工作目录必须是仓库根：landing / spark-warehouse / derby-metastore 默认都是相对路径
  $pLog = Start-Jar -JarGlob (Join-Path $root 'analytics-server\platform-app\target\*.jar') `
    -JvmArgs @("-Dplatform.metric.publish.export-dir=$root\metric-staging") -LogName 'start-platform.log'
  Write-Host '[2/6] 探活分析平台 /api/v1/metrics/health ...'
  if (-not (Wait-Http -Url 'http://127.0.0.1:8091/api/v1/metrics/health' -Log $pLog)) {
    Write-Host '  分析平台未就绪'; exit 1
  }
  Write-Host '  分析平台 OK → http://127.0.0.1:8091/'
  Write-Host '  演示账号（仅此三个真实存在）：admin/admin123（管理员）、operator/operator123（运营）、analyst/analyst123（分析师）'
}

if ($startMall) {
  Write-Host '[3/6] 启动模拟商城（reference-mall, 8090）...'
  $env:MALL_DB_PASSWORD = $MallDbPassword
  $mLog = Start-Jar -JarGlob (Join-Path $root 'mall-simulator\target\*.jar') -JvmArgs @() -LogName 'start-mall.log'
  Write-Host '[4/6] 探活模拟商城（SPA 首页）...'
  if (-not (Wait-Http -Url 'http://127.0.0.1:8090/' -Log $mLog)) {
    Write-Host '  模拟商城未就绪（商城库口令错误时也会失败，可用 -MallDbPassword 指定）'; exit 1
  }
  Write-Host '  模拟商城 OK → http://127.0.0.1:8090/（商城演示 / 商品后台）'
}

if ($startGenerator) {
  Write-Host '[5/6] 启动合成数据生成器（synthetic-data-generator, 8092）...'
  $gLog = Start-Jar -JarGlob (Join-Path $root 'synthetic-data-generator\target\*.jar') -JvmArgs @() -LogName 'start-generator.log'
  Write-Host '[6/6] 探活生成器 /api/v1/scenarios ...'
  if (-not (Wait-Http -Url 'http://127.0.0.1:8092/api/v1/scenarios' -Log $gLog)) {
    Write-Host '  生成器未就绪（自有库 generator_meta，与平台/商城无依赖）'; exit 1
  }
  Write-Host '  生成器 OK → http://127.0.0.1:8092/api/v1/scenarios（文件模式产物写入独立目录，synthetic=true）'
}

if ($startPlatform) { Write-Host '分析看板入口：http://127.0.0.1:8091/' }
if ($startMall) { Write-Host '商城入口：http://127.0.0.1:8090/' }
if ($startGenerator) { Write-Host '生成器入口：http://127.0.0.1:8092/api/v1/scenarios（生成后需在 8091 触发采集+流水线才会更新看板）' }
$openPort = if ($MallOnly) { '8090' } elseif ($GeneratorOnly) { '8092/api/v1/scenarios' } else { '8091' }
try { Start-Process ("http://127.0.0.1:" + $openPort) } catch {}
