# 一键构建发布包：前端 dist → 后端 static → 可执行 jar
# 用法: pwsh -File scripts/build-web-and-package.ps1   （需 MALL_DB_PASSWORD 于部署时提供）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '[1/4] 构建前端...'
Push-Location (Join-Path $root 'web')
npm run build
Pop-Location

Write-Host '[2/4] 拷贝 dist 到后端静态资源...'
$staticDir = Join-Path $root 'mall-simulator\src\main\resources\static'
if (Test-Path $staticDir) { Remove-Item $staticDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
Copy-Item (Join-Path $root 'web\dist\*') $staticDir -Recurse -Force
Write-Host "  dist → $staticDir（$( (Get-ChildItem $staticDir -Recurse -File).Count ) 个文件）"

Write-Host '[3/4] 打包可执行 jar（跳过测试）...'
Push-Location (Join-Path $root 'mall-simulator')
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Duser.language=en'
mvn -q package -DskipTests
Pop-Location

Write-Host '[4/4] 产物：'
Get-ChildItem (Join-Path $root 'mall-simulator\target\*.jar') | Where-Object { $_.Name -notlike '*original*' } | ForEach-Object {
  Write-Host "  $($_.Name)（$([Math]::Round($_.Length/1MB,1)) MB）"
}
Write-Host '完成。启动方式：pwsh -File scripts/start-all.ps1'