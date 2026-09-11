# 一键启动（分析平台 + 模拟商城，两个进程、两个端口）
#   分析平台 platform-app :8091  ← 分析前端（web/，看板/AI/决策/流水线）
#   模拟商城 mall-simulator:8090  ← 商城前端（mall-frontend/，商城演示/商品后台/生成器）
# 边界：指导书 V2.0 §18.4 / §31 第 6 条——两个系统各自的前端、各自的库、各自的账号。
#
# 用法:
#   pwsh -File scripts/start-all.ps1                          # 两个都起
#   pwsh -File scripts/start-all.ps1 -PlatformOnly             # 只起分析平台（看板演示）
#   pwsh -File scripts/start-all.ps1 -MallOnly                 # 只起模拟商城（造数据）
# 前置: pwsh -File scripts/build-web-and-package.ps1
# 环境变量: MALL_DB_PASSWORD（商城库口令，默认取本机 LOCAL 演示口令）
param(
  [switch]$PlatformOnly,
  [switch]$MallOnly,
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
  param([string]$JarGlob, [string[]]$Args, [string]$LogName)
  $jar = Get-ChildItem $JarGlob -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
  if (-not $jar) { throw "未找到 jar（$JarGlob），请先运行 scripts/build-web-and-package.ps1" }
  $log = Join-Path $logDir $LogName
  Start-Process -FilePath 'java' -ArgumentList (@('-Dfile.encoding=UTF-8', '-jar', $jar.FullName) + $Args) `
    -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  Write-Host "  已启动 $($jar.Name)（日志 $log）"
  return $log
}

if (-not $MallOnly) {
  Write-Host '[1/4] 启动分析平台（platform-app, 8091）...'
  # 工作目录必须是仓库根：landing / spark-warehouse / derby-metastore 默认都是相对路径
  $pLog = Start-Jar -JarGlob (Join-Path $root 'analytics-server\platform-app\target\*.jar') `
    -Args @("-Dplatform.metric.publish.export-dir=$root\metric-staging") -LogName 'start-platform.log'
  Write-Host '[2/4] 探活分析平台 /api/v1/metrics/health ...'
  if (-not (Wait-Http -Url 'http://127.0.0.1:8091/api/v1/metrics/health' -Log $pLog)) {
    Write-Host '  分析平台未就绪'; exit 1
  }
  Write-Host '  分析平台 OK → http://127.0.0.1:8091/'
  Write-Host '  演示账号（仅此三个真实存在）：admin/admin123（管理员）、operator/operator123（运营）、analyst/analyst123（分析师）'
}

if (-not $PlatformOnly) {
  Write-Host '[3/4] 启动模拟商城（mall-simulator, 8090）...'
  $env:MALL_DB_PASSWORD = $MallDbPassword
  $mLog = Start-Jar -JarGlob (Join-Path $root 'mall-simulator\target\*.jar') -Args @() -LogName 'start-mall.log'
  Write-Host '[4/4] 探活模拟商城（SPA 首页）...'
  if (-not (Wait-Http -Url 'http://127.0.0.1:8090/' -Log $mLog)) {
    Write-Host '  模拟商城未就绪（商城库口令错误时也会失败，可用 -MallDbPassword 指定）'; exit 1
  }
  Write-Host '  模拟商城 OK → http://127.0.0.1:8090/（商城演示 / 商品后台 / 数据生成器）'
}

if (-not $MallOnly) { Write-Host '分析看板入口：http://127.0.0.1:8091/' }
if (-not $PlatformOnly) { Write-Host '造数据入口：http://127.0.0.1:8090/generator（生成后需在 8091 触发采集+流水线才会更新看板）' }
try { Start-Process ("http://127.0.0.1:" + $(if ($MallOnly) { '8090' } else { '8091' }) + '/') } catch {}
