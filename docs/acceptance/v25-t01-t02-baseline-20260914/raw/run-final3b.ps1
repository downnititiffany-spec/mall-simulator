# 第三批修正：所有 -D 参数必须作为**带引号的单个字符串**传入（PS 会在点号处切开未加引号的 -Dsurefire.xxx）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null
Set-Location 'D:\Develop_code\GraduationProject'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

function Run-Retry([string]$tag, [string[]]$mvnArgs, [int]$tries) {
    for ($i = 1; $i -le $tries; $i++) {
        $log = Join-Path $raw "$tag.log"
        "### 命令: mvn.cmd $($mvnArgs -join ' ')" | Set-Content -Encoding UTF8 $log
        "### 尝试 $i / $tries   开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $log
        & $mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
        $code = $LASTEXITCODE
        "### EXIT=$code" | Add-Content -Encoding UTF8 $log
        "### 结束: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $log
        $broken = (Select-String -Path $log -Pattern 'Compilation failure|找不到符号|不兼容的类型|Unknown lifecycle phase' -Quiet)
        Write-Host "[$tag] 第 $i 次 EXIT=$code 上游编译被打断=$broken"
        if (-not $broken) { return $code }
        Start-Sleep -Seconds 30
    }
    return 99
}

$repo = '-Dmaven.repo.local=D:\maven_repository'
$skipNoTest = '-Dsurefire.failIfNoSpecifiedTests=false'

Run-Retry 'final-23a-t02-contract-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestSourceSchemaTest', $skipNoTest, 'test') 8 | Out-Null

Run-Retry 'final-23b-t02-runtime-patrol-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest', $skipNoTest, 'test') 8 | Out-Null

Run-Retry 'final-23c-t02-tamper-method' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest#patrolSeparatesArrivalsFromRealViolations', $skipNoTest, 'test') 8 | Out-Null

Run-Retry 'final-24-full-reactor-collect' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-Dmaven.test.failure.ignore=true', 'test') 3 | Out-Null

$sum = Join-Path $raw 'final-25-summary.txt'
'=== 第三批修正版摘要 ===' | Set-Content -Encoding UTF8 $sum
Get-ChildItem $raw -Filter 'final-2[345]*.log' | Sort-Object Name | ForEach-Object {
    "--- $($_.Name) ---" | Add-Content -Encoding UTF8 $sum
    (Select-String -Path $_.FullName -Pattern 'Tests run:.*(Failures|Errors)|BUILD SUCCESS|BUILD FAILURE|^### EXIT=|^### 尝试|SUCCESS \[|FAILURE \[|SKIPPED|Total number of tests' |
        ForEach-Object { $_.Line.Trim() }) | Add-Content -Encoding UTF8 $sum
}
Write-Host 'DONE3B'
