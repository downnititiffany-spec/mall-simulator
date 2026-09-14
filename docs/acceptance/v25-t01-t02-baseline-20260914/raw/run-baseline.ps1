# V25-T01/T02 基线取证（改动前）
# 逐字命令见 README.md；本脚本只是把四条命令串起来，不改变任何命令参数。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp.com 65001 > $null
$root = 'D:\Develop_code\GraduationProject'
$log  = Join-Path $root 'docs\acceptance\v25-t01-t02-baseline-20260914\raw'
New-Item -ItemType Directory -Force -Path $log | Out-Null
Set-Location $root

$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$repo = '-Dmaven.repo.local=D:\maven_repository'
$pom  = 'analytics-server/pom.xml'

# ── 环境指纹 ──────────────────────────────────────────────────────────
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-File "$log\baseline-env-java.txt" -Encoding utf8
(& $mvn -v) 2>&1 | Out-File "$log\baseline-env-maven.txt" -Encoding utf8
git rev-parse HEAD | Out-File "$log\baseline-git-head.txt" -Encoding utf8
git status --porcelain | Out-File "$log\baseline-git-status-porcelain.txt" -Encoding utf8
git diff --stat | Out-File "$log\baseline-git-diff-stat.txt" -Encoding utf8
Get-ChildItem 'landing\manifests' -Filter *.json | Sort-Object { [int]$_.BaseName } |
    ForEach-Object { '{0}  {1}  {2}  {3:yyyy-MM-dd HH:mm:ss}' -f $_.Name, $_.Length,
        (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.LastWriteTime } |
    Out-File "$log\baseline-landing-manifests-mtime-sha256.txt" -Encoding utf8

$summary = @()
function Run([string]$tag, [string[]]$mvnArgs) {
    $file = Join-Path $log "$tag.log"
    & $mvn @mvnArgs 2>&1 | Tee-Object -FilePath $file
    $code = $LASTEXITCODE
    $script:summary += "$tag : EXIT=$code"
    "EXIT=$code" | Out-File -Append -Encoding utf8 $file
    Write-Host "== $tag EXIT=$code =="
}

Run 'baseline-01-platform-common-test' @('-o', $repo, '-f', $pom, '-pl', 'platform-common', '-am', 'test')
Run 'baseline-02-platform-app-am-test' @('-o', $repo, '-f', $pom, '-pl', 'platform-app', '-am', 'test')
Run 'baseline-03-platform-app-am-test-failure-ignore' @('-o', $repo, '-f', $pom, '-pl', 'platform-app', '-am', 'test', '-Dmaven.test.failure.ignore=true')
Run 'baseline-04-full-reactor-test' @('-o', $repo, '-f', $pom, 'test')

$summary | Out-File "$log\baseline-exit-codes.txt" -Encoding utf8
$summary | Write-Host
