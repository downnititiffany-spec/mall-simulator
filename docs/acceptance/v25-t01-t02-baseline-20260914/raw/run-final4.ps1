# 第四批：T02 运行时巡检组 + 篡改用例 + 逐字全反应堆（platform-common 已由其他泳道修绿）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null
Set-Location 'D:\Develop_code\GraduationProject'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$repo = '-Dmaven.repo.local=D:\maven_repository'
$skip = '-Dsurefire.failIfNoSpecifiedTests=false'

function Invoke-Mvn([string]$tag, [string[]]$a) {
    $log = Join-Path $raw "$tag.log"
    "### 命令: mvn.cmd $($a -join ' ')" | Set-Content -Encoding UTF8 $log
    "### 开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $log
    & $mvn @a 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
    "### EXIT=$LASTEXITCODE" | Add-Content -Encoding UTF8 $log
    "### 结束: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $log
    Write-Host "[$tag] EXIT=$LASTEXITCODE"
}

Invoke-Mvn 'final-23b-t02-runtime-patrol-group' @('-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest', $skip, 'test')
Invoke-Mvn 'final-23c-t02-tamper-method' @('-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest#patrolSeparatesArrivalsFromRealViolations', $skip, 'test')
Invoke-Mvn 'final-26-verbatim-full-reactor-test' @('-o', $repo, '-f', 'analytics-server/pom.xml', 'test')
Invoke-Mvn 'final-24-full-reactor-collect' @('-o', $repo, '-f', 'analytics-server/pom.xml', '-Dmaven.test.failure.ignore=true', 'test')

# 采集后再次确认 landing 未被动过
$after = Get-ChildItem 'landing\manifests' -File | Sort-Object Name | ForEach-Object {
    "$($_.Name)`t$($_.Length)`t$($_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))`t$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)"
}
$before = Get-Content (Join-Path $raw 'final-00-landing-before.txt') | Where-Object { $_ -match '^\S+\.json\t' }
$v = Join-Path $raw 'final-27-landing-untouched-final.txt'
"最终确认时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" | Set-Content -Encoding UTF8 $v
"文件数: $($after.Count)（应 43）" | Add-Content -Encoding UTF8 $v
$judge = if ($null -eq (Compare-Object $before $after)) { '与采集前逐行相同 ⇒ 1..43.json 内容/字节/mtime/sha256 全程未变' } else { '有差异！' }
$judge | Add-Content -Encoding UTF8 $v
'--- git status --porcelain landing（应为空）---' | Add-Content -Encoding UTF8 $v
(git status --porcelain landing) | Out-String | Add-Content -Encoding UTF8 $v
'--- git check-ignore -v landing/manifests/40.json ---' | Add-Content -Encoding UTF8 $v
(git check-ignore -v landing/manifests/40.json) | Out-String | Add-Content -Encoding UTF8 $v

$sum = Join-Path $raw 'final-28-summary.txt'
'=== 第四批摘要 ===' | Set-Content -Encoding UTF8 $sum
Get-ChildItem $raw -Filter 'final-2[346]*.log' | Sort-Object Name | ForEach-Object {
    "--- $($_.Name) ---" | Add-Content -Encoding UTF8 $sum
    (Select-String -Path $_.FullName -Pattern 'Tests run:.*(Failures|Errors)|BUILD SUCCESS|BUILD FAILURE|^### EXIT=|SUCCESS \[|FAILURE \[|SKIPPED' |
        ForEach-Object { $_.Line.Trim() }) | Add-Content -Encoding UTF8 $sum
}
Write-Host 'DONE4'
