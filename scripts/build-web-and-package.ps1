# 一键构建发布包（前端边界对齐版，指导书 V2.0 §18.4 / §31 第 6 条）
#
# 边界：分析前端（web/）只属于**分析平台**（platform-app，8091）；
#       商城演示与商品后台前端（mall-frontend/）只属于**模拟商城**（mall-simulator，8090）。
#       两个前端各自构建、各自进自己的 jar，互不打包。
#
# 用法: pwsh -File scripts/build-web-and-package.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Copy-DistToStatic {
    param([string]$DistDir, [string]$StaticDir, [string]$Label)
    if (-not (Test-Path $DistDir)) { throw "缺少前端产物：$DistDir（先执行前端构建）" }
    if (Test-Path $StaticDir) { Remove-Item $StaticDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $StaticDir | Out-Null
    Copy-Item (Join-Path $DistDir '*') $StaticDir -Recurse -Force
    Write-Host "  $Label dist → $StaticDir（$( (Get-ChildItem $StaticDir -Recurse -File).Count ) 个文件）"
}

# 边界自检（§18.4）：两个 jar 的静态资源必须互不混入对面的页面 chunk。
# 历史坑：mall-simulator 静态目录里残留过整份分析 SPA（Overview/Sales/Rfm/… + echarts），
# 导致"分析看板由商城进程提供"；Copy-DistToStatic 会清目录，这里再加一道产物级断言。
function Assert-FrontendBoundary {
    param([string]$StaticDir, [string[]]$Forbidden, [string]$Label)
    $bad = @(Get-ChildItem $StaticDir -Recurse -File | Where-Object {
        $n = $_.Name
        @($Forbidden | Where-Object { $n -like "$_*" }).Count -gt 0
    })
    if ($bad.Count -gt 0) {
        throw "$Label 静态资源混入越界页面 chunk：$($bad.Name -join ', ')（应为空目录后只拷自己的 dist）"
    }
    Write-Host "  边界自检 OK：$Label 无越界页面（$((Get-ChildItem $StaticDir -Recurse -File).Count) 个文件）"
}

# jar 级边界自检：源目录干净 ≠ jar 干净——`mvn package` 不清理 target/classes，
# 上一轮残留的静态 chunk 会继续被打进 jar（本轮实测：商城 jar 里混进过整份分析 SPA）。
function Assert-JarBoundary {
    param([string]$Jar, [string[]]$Forbidden, [string]$Label)
    if (-not (Test-Path $Jar)) { throw "缺少产物：$Jar" }
    $jarExe = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path $jarExe)) { $jarExe = 'jar' }
    $names = @(& $jarExe tf $Jar | Where-Object { $_ -like 'BOOT-INF/classes/static/*' -and $_ -notlike '*/' } |
        ForEach-Object { Split-Path $_ -Leaf })
    $bad = @($names | Where-Object { $n = $_; @($Forbidden | Where-Object { $n -like "$_*" }).Count -gt 0 })
    if ($bad.Count -gt 0) {
        throw "$Label 的 jar 内混入越界页面 chunk：$($bad -join ', ')（需先 mvn clean 再打包）"
    }
    Write-Host "  jar 边界自检 OK：$Label 内含 $($names.Count) 个静态文件，无越界页面"
}

Write-Host '[1/6] 构建分析平台前端（web/）...'
Push-Location (Join-Path $root 'web')
npm run build
Pop-Location

Write-Host '[2/6] 分析前端 dist → platform-app 静态资源...'
Copy-DistToStatic -DistDir (Join-Path $root 'web\dist') `
    -StaticDir (Join-Path $root 'analytics-server\platform-app\src\main\resources\static') `
    -Label '分析前端'
Assert-FrontendBoundary -StaticDir (Join-Path $root 'analytics-server\platform-app\src\main\resources\static') `
    -Forbidden @('Mall-', 'Generator-', 'AdminProducts-') -Label '分析平台(8091)'

Write-Host '[3/6] 打包分析平台 jar（clean + 跳过测试）...'
Push-Location (Join-Path $root 'analytics-server')
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
mvn -q -pl platform-app -am clean package -DskipTests
Pop-Location

$mallFront = Join-Path $root 'mall-frontend'
if (Test-Path (Join-Path $mallFront 'package.json')) {
    Write-Host '[4/6] 构建模拟商城前端（mall-frontend/）...'
    Push-Location $mallFront
    npm run build
    Pop-Location

    Write-Host '[5/6] 商城前端 dist → mall-simulator 静态资源...'
    Copy-DistToStatic -DistDir (Join-Path $mallFront 'dist') `
        -StaticDir (Join-Path $root 'mall-simulator\src\main\resources\static') `
        -Label '商城前端'
    Assert-FrontendBoundary -StaticDir (Join-Path $root 'mall-simulator\src\main\resources\static') `
        -Forbidden @('Overview-', 'Sales-', 'Rfm-', 'Behavior-', 'Products-', 'Pipeline-', 'Ops-',
                     'AiAssistant-', 'Decisions-', 'ChartState-', 'echarts-') -Label '模拟商城(8090)'
} else {
    Write-Host '[4-5/6] 未找到 mall-frontend/，跳过商城前端构建（分析平台产物不受影响）'
}

Write-Host '[6/6] 打包模拟商城 jar（clean + 跳过测试）...'
Push-Location (Join-Path $root 'mall-simulator')
mvn -q clean package -DskipTests
Pop-Location

# 产物级边界自检（jar 内静态资源不得含对面页面 chunk）
$pJar = Get-ChildItem (Join-Path $root 'analytics-server\platform-app\target\*.jar') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
$mJar = Get-ChildItem (Join-Path $root 'mall-simulator\target\*.jar') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
if ($pJar) { Assert-JarBoundary -Jar $pJar.FullName -Forbidden @('Mall-', 'Generator-', 'AdminProducts-') -Label '分析平台(8091)' }
if ($mJar) {
    Assert-JarBoundary -Jar $mJar.FullName `
        -Forbidden @('Overview-', 'Sales-', 'Rfm-', 'Behavior-', 'Products-', 'Pipeline-', 'Ops-',
                     'AiAssistant-', 'Decisions-', 'ChartState-', 'echarts-') -Label '模拟商城(8090)'
}

Write-Host '产物：'
Get-ChildItem (Join-Path $root 'analytics-server\platform-app\target\*.jar'), `
    (Join-Path $root 'mall-simulator\target\*.jar') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | ForEach-Object {
        Write-Host "  $($_.Name)（$([Math]::Round($_.Length/1MB,1)) MB）"
    }
Write-Host '完成。启动方式：pwsh -File scripts/start-all.ps1'
