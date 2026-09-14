# V25-T01 / V25-T02 官方证据采集（第二批：修正 JAVA_HOME 传参 bug；含外部泳道红线的归属取证）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null

$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$raw = Join-Path $root 'docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$repo = '-Dmaven.repo.local=D:\maven_repository'

function Set-Jdk([string]$jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;" + $env:PATH
    return (& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1)
}

function Run-Mvn([string]$tag, [string[]]$mvnArgs, [string]$jdk) {
    $log = Join-Path $raw "$tag.log"
    $jdkLine = Set-Jdk $jdk
    "### 命令: mvn.cmd $($mvnArgs -join ' ')" | Set-Content -Encoding UTF8 $log
    "### JAVA_HOME: $jdk  →  $jdkLine" | Add-Content -Encoding UTF8 $log
    "### 开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  HEAD=$(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $log
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
    & $mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
    $code = $LASTEXITCODE
    "### EXIT=$code" | Add-Content -Encoding UTF8 $log
    "### 结束: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $log
    Write-Host "[$tag] EXIT=$code"
    return $code
}

# ── A) 冻结契约组（platform-common 用 -Dtest 过滤，避开外部泳道 TestIsolationGuardTest 的红）──
Run-Mvn 'final-10a-t02-contract-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestSourceSchemaTest', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── B) 运行时巡检组 ────────────────────────────────────────────────────────
Run-Mvn 'final-10b-t02-runtime-patrol-group' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am',
    '-Dtest=IngestionManifestRuntimePatrolTest', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── C) T01 绿证（platform-common 内本泳道范围）─────────────────────────────
Run-Mvn 'final-11-t01-warehouse-gate-green' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-common', '-am',
    '-Dtest=Warehouse*', '-DfailIfNoSpecifiedTests=false', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── D) 逐字命令原样复跑（如实记录：外部泳道红线挡住反应堆）─────────────────
Run-Mvn 'final-12-verbatim-platform-common-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-common', '-am', 'test'
) 'D:\Develop\JAVA17' | Out-Null
Run-Mvn 'final-13-verbatim-platform-app-am-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-pl', 'platform-app', '-am', 'test'
) 'D:\Develop\JAVA17' | Out-Null
Run-Mvn 'final-14-verbatim-full-reactor-test' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── E) 全反应堆收集口径（外部红不阻断；聚合 failure>0 即整体 FAIL）──────────
Run-Mvn 'final-15-full-reactor-collect' @(
    '-o', $repo, '-f', 'analytics-server/pom.xml', '-Dmaven.test.failure.ignore=true', 'test'
) 'D:\Develop\JAVA17' | Out-Null

# ── F) spark-jobs（JDK8）──────────────────────────────────────────────────
Run-Mvn 'final-16-spark-jobs-jdk8-test' @(
    '-o', $repo, '-f', 'spark-jobs/pom.xml', 'test'
) 'D:\Develop\JDK1.8' | Out-Null

# ── G) 外部泳道红线归属取证 ───────────────────────────────────────────────
$attr = Join-Path $raw 'final-17-foreign-red-attribution.txt'
'=== 外部泳道红线归属（TestIsolationGuard / TestIsolationGuardTest 不是本泳道产物）===' | Set-Content -Encoding UTF8 $attr
"采集时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content -Encoding UTF8 $attr
"HEAD: $(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $attr
'' | Add-Content -Encoding UTF8 $attr
'--- 这两个文件在 HEAD 中是否存在（不存在 ⇒ 本泳道运行期间由其他泳道新建/改动）---' | Add-Content -Encoding UTF8 $attr
foreach ($p in @(
    'analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuard.java',
    'analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuardTest.java')) {
    git cat-file -e "HEAD:$p" 2>$null
    $inHead = $LASTEXITCODE
    "$p  →  git cat-file -e HEAD 退出码=$inHead  （非 0 = HEAD 里没有这个文件 ⇒ 本泳道运行期间由其他泳道新建）" | Add-Content -Encoding UTF8 $attr
}
'' | Add-Content -Encoding UTF8 $attr
'--- git status（本泳道只碰 warehouse/ingestion 两个目录）---' | Add-Content -Encoding UTF8 $attr
(git status --porcelain -- analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport) | Out-String | Add-Content -Encoding UTF8 $attr
'' | Add-Content -Encoding UTF8 $attr
'--- 文件 mtime / sha256（用于说明"正在被其他泳道改动"）---' | Add-Content -Encoding UTF8 $attr
Get-ChildItem 'analytics-server\platform-common\src\test\java\com\graduation\analytics\testsupport' -File |
    Sort-Object Name | ForEach-Object {
        "$($_.Name)`t$($_.Length)`t$($_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))`t$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)"
    } | Add-Content -Encoding UTF8 $attr
'' | Add-Content -Encoding UTF8 $attr
'--- 本泳道是否改动过 testsupport（应为空）---' | Add-Content -Encoding UTF8 $attr
"本泳道改动清单（git status 里属于本泳道的条目）:" | Add-Content -Encoding UTF8 $attr
(git status --porcelain -- analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse analytics-server/platform-app/src/test docs/acceptance/v25-t01-t02-baseline-20260914) |
    Out-String | Add-Content -Encoding UTF8 $attr

# ── H) landing/manifests 未动：只比数据行（名字/字节/时间/sha256），忽略表头差异 ──
$before = Get-Content (Join-Path $raw 'final-00-landing-before.txt') | Where-Object { $_ -match '^\S+\.json\t' }
$after  = Get-Content (Join-Path $raw 'final-08-landing-after.txt')  | Where-Object { $_ -match '^\S+\.json\t' }
$verdict = Join-Path $raw 'final-18-landing-rows-verdict.txt'
"C) landing/manifests 数据行比对（只看 名字/字节/mtime/sha256 三类事实，忽略表头与 HEAD 行）" | Set-Content -Encoding UTF8 $verdict
"采集前数据行数=$($before.Count)  采集后数据行数=$($after.Count)  （应相同）" | Add-Content -Encoding UTF8 $verdict
$d = Compare-Object $before $after
$judge = if ($null -eq $d) {
    '判定: 逐行完全相同 ⇒ 1..43.json 内容、字节数、mtime、sha256 均未被动过'
} else {
    ('判定: 有差异！' + "`n" + ($d | Format-Table -AutoSize | Out-String))
}
$judge | Add-Content -Encoding UTF8 $verdict
"`n完整 43 行（采集后）:" | Add-Content -Encoding UTF8 $verdict
$after | Add-Content -Encoding UTF8 $verdict

# ── I) 摘要 ───────────────────────────────────────────────────────────────
$sum = Join-Path $raw 'final-19-summary.txt'
'=== 第二批关键行摘要 ===' | Set-Content -Encoding UTF8 $sum
Get-ChildItem $raw -Filter 'final-1*.log' | Sort-Object Name | ForEach-Object {
    "--- $($_.Name) ---" | Add-Content -Encoding UTF8 $sum
    (Select-String -Path $_.FullName -Pattern 'Tests run:.*(Failures|Errors)|BUILD SUCCESS|BUILD FAILURE|^### EXIT=|SUCCESS \[|FAILURE \[|SKIPPED' |
        ForEach-Object { $_.Line.Trim() }) | Add-Content -Encoding UTF8 $sum
}
Write-Host 'DONE2'
