# V25-T01 / V25-T02 官方证据采集（编译恢复后）
# 逐字命令见 README §4；每步结束后把 EXIT= 追加进日志。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null

$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$raw = Join-Path $root 'docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$repo = '-Dmaven.repo.local=D:\maven_repository'

function Get-Jdk([string]$home) {
    $env:JAVA_HOME = $home
    $env:PATH = "$home\bin;" + ($env:PATH -replace [regex]::Escape("$home\bin;"), '')
    return (& "$home\bin\java.exe" -version 2>&1 | Select-Object -First 1)
}

function Run-Mvn([string]$tag, [string[]]$mvnArgs, [string]$jdkHome) {
    $log = Join-Path $raw "$tag.log"
    "### 命令: mvn.cmd $($mvnArgs -join ' ')" | Set-Content -Encoding UTF8 $log
    "### JDK: $(Get-Jdk $jdkHome)" | Add-Content -Encoding UTF8 $log
    "### 开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $log
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
    & $mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
    $code = $LASTEXITCODE
    "### EXIT=$code" | Add-Content -Encoding UTF8 $log
    "### 结束: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $log
    Write-Host "[$tag] EXIT=$code  → $log"
    return $code
}

# ── 0) 采集前指纹 ─────────────────────────────────────────────────────────
$env:JAVA_HOME = 'D:\Develop\JAVA17'
git rev-parse HEAD                       | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-git-head.txt')
git status --porcelain                   | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-git-status-porcelain.txt')
git diff --stat                          | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-git-diff-stat.txt')
(& "$env:JAVA_HOME\bin\java.exe" -version 2>&1) | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-java.txt')
(& $mvn -v 2>&1) | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-maven.txt')
'--- landing/manifests 采集前快照（名字/字节/时间/sha256）---' | Set-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')
Get-ChildItem 'landing\manifests' -File | Sort-Object Name | ForEach-Object {
    "$($_.Name)`t$($_.Length)`t$($_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))`t$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)"
} | Add-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')
'--- git check-ignore -v landing/manifests/40.json ---' | Add-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')
(git check-ignore -v landing/manifests/40.json) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')
'--- git status --porcelain landing（应为空：landing/ 被忽略）---' | Add-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')
(git status --porcelain landing) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-00-landing-before.txt')

# ── 1) T01 红证：HEAD 原版门禁用例（逐字取出、类名加 LegacyProbe 后缀）────────
$probeDir = 'analytics-server\platform-common\src\test\java\com\graduation\analytics\warehouse'
$probe = Join-Path $probeDir 'WarehouseNameLiteralGateLegacyProbeTest.java'
$head = git show 'HEAD:analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java'
$header = @'
// 【临时探针，非交付物】本文件由 V25-T01 证据脚本从 HEAD 逐字取出并仅改类名：
//   git show HEAD:analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java
// 用途：把"修改前的门禁实现"原样跑一遍，作为 T01 的**红证**（证明旧口径确实判红这两行）。
// 取证后立即删除，不留在工作树里（删除记录见 README §2.2）。
'@
($header | Set-Content -Encoding UTF8 $probe)
(($head -join "`n").Replace('class WarehouseNameLiteralGateTest', 'class WarehouseNameLiteralGateLegacyProbeTest') |
    Add-Content -Encoding UTF8 $probe)
$probeHash = (Get-FileHash $probe -Algorithm SHA256).Hash
"探针文件=$probe`n探针 sha256=$probeHash`n来源=HEAD:.../WarehouseNameLiteralGateTest.java（仅改类名）" |
    Set-Content -Encoding UTF8 (Join-Path $raw 'final-01-t01-legacy-probe-provenance.txt')
$head | Set-Content -Encoding UTF8 (Join-Path $raw 'final-01-t01-legacy-probe-source-head.java')

Run-Mvn 'final-01-t01-red-legacy-probe' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-common', '-am',
    '-Dtest=WarehouseNameLiteralGateLegacyProbeTest', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 2) 复现「-pl platform-app -am 在 platform-common 处中止 ⇒ 掩盖 T02」 ──────
# 不加 -Dtest：让 platform-common 跑到红（探针），观察反应堆是否根本没走到 platform-app。
Run-Mvn 'final-02-platform-app-am-masking-repro' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 3) 删除临时探针（取证后不留在工作树）──────────────────────────────────
Remove-Item $probe -Force
"删除时间=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n删除后 Test-Path=$([bool](Test-Path $probe))" |
    Add-Content -Encoding UTF8 (Join-Path $raw 'final-01-t01-legacy-probe-provenance.txt')
(git status --porcelain $probeDir) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-01-t01-legacy-probe-provenance.txt')

# ── 4) T01 绿证：platform-common 全量 ──────────────────────────────────────
Run-Mvn 'final-03-platform-common-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-common', '-am', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 5) T02 两组分开跑 ─────────────────────────────────────────────────────
Run-Mvn 'final-04a-t02-contract-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestSourceSchemaTest', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null
Run-Mvn 'final-04b-t02-runtime-patrol-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 6) platform-app 全量（T0 要求的第二条命令）─────────────────────────────
Run-Mvn 'final-05-platform-app-am-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 7) 全反应堆 ───────────────────────────────────────────────────────────
Run-Mvn 'final-06-full-reactor-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── 8) spark-jobs（JDK8，基线 111/111）────────────────────────────────────
Run-Mvn 'final-07-spark-jobs-jdk8-test' @(
    '-o', $repo, '-f', 'spark-jobs/pom.xml', 'test'
) 'D:\Develop\JDK1.8' | Out-Null

# ── 9) 采集后：T02 未动证据 + 摘要 ────────────────────────────────────────
'--- landing/manifests 采集后快照（名字/字节/时间/sha256）---' | Set-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
Get-ChildItem 'landing\manifests' -File | Sort-Object Name | ForEach-Object {
    "$($_.Name)`t$($_.Length)`t$($_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))`t$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)"
} | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
'--- git status --porcelain landing（应为空）---' | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
(git status --porcelain landing) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
'--- git check-ignore -v landing/manifests/40.json ---' | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
(git check-ignore -v landing/manifests/40.json) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
'--- 采集后 git HEAD ---' | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')
(git rev-parse HEAD) | Out-String | Add-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-after.txt')

$diff = Compare-Object (Get-Content (Join-Path $raw 'final-00-landing-before.txt')) (Get-Content (Join-Path $raw 'final-08-landing-after.txt'))
$verdict = if ($null -eq $diff) {
    'BEFORE/AFTER 快照逐行相同（landing/manifests 未被动过）'
} else {
    ($diff | Format-Table -AutoSize | Out-String)
}
$verdict | Set-Content -Encoding UTF8 (Join-Path $raw 'final-08-landing-untouched-verdict.txt')

# 摘要
'=== 关键行摘要 ===' | Set-Content -Encoding UTF8 (Join-Path $raw 'final-09-summary.txt')
Get-ChildItem $raw -Filter 'final-*.log' | Sort-Object Name | ForEach-Object {
    "--- $($_.Name) ---" | Add-Content -Encoding UTF8 (Join-Path $raw 'final-09-summary.txt')
    (Select-String -Path $_.FullName -Pattern 'Tests run:.*(Failures|Errors)|BUILD SUCCESS|BUILD FAILURE|^### EXIT=|Reactor Summary|SKIPPED|SUCCESS \[|FAILURE \[' |
        ForEach-Object { $_.Line.Trim() }) | Add-Content -Encoding UTF8 (Join-Path $raw 'final-09-summary.txt')
}
Write-Host "DONE. 摘要: $(Join-Path $raw 'final-09-summary.txt')"
