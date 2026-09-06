# 一键启动（单进程生产模式）：探活 MySQL → 启动后端 jar（后台）→ 探活 → 打开浏览器
# 用法: pwsh -File scripts/start-all.ps1          （需先 build-web-and-package.ps1 产 jar）
# 环境变量: MALL_DB_PASSWORD（必填）、可选 MALL_READER_PASSWORD / LLM_API_KEY 等
param(
  [string]$Port = '8090'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem (Join-Path $root 'mall-simulator\target\*.jar') |
  Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
if (-not $jar) { Write-Host '未找到 jar，请先运行 scripts/build-web-and-package.ps1'; exit 1 }
if (-not $env:MALL_DB_PASSWORD) { Write-Host '缺少 MALL_DB_PASSWORD 环境变量'; exit 1 }

# 1) 探活 MySQL
Write-Host '[1/3] 探活 MySQL...'
$mysqlOk = $false
foreach ($try in 1..5) {
  try {
    & mysqladmin -uroot -p"$env:MALL_DB_PASSWORD" ping 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $mysqlOk = $true; break }
  } catch {}
  Start-Sleep -Seconds 2
}
if (-not $mysqlOk) { Write-Host 'MySQL 未就绪（请启动 MySQL 服务）'; exit 1 }
Write-Host '  MySQL OK'

# 2) 启动后端 jar（后台；若端口占用提示先停旧进程）
$log = Join-Path $env:TEMP 'mall-server.log'
Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName) -WorkingDirectory (Split-Path $jar.FullName) -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
Write-Host '[2/3] 后端启动中...'
$ready = $false
foreach ($try in 1..40) {
  Start-Sleep -Seconds 2
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/v1/metrics/health" -TimeoutSec 3 -UseBasicParsing
    if ($r.StatusCode -eq 200) { $ready = $true; break }
  } catch {}
}
if (-not $ready) {
  Write-Host "后端未就绪，日志: $log"
  Get-Content $log -Tail 8 | ForEach-Object { Write-Host "  $_" }
  exit 1
}

# 3) 提示 + 打开浏览器
Write-Host "[3/3] 系统已启动 → http://127.0.0.1:$Port/"
Write-Host '  演示账号：admin/admin123（系统管理员）、operator/operator123（运营）、analyst/analyst123（分析师）'
try { Start-Process "http://127.0.0.1:$Port/" } catch {}
Write-Host "日志: $log"