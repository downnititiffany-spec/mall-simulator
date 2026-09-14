# 第三批：③ 等长改写负例重跑（修正探针自身期望）＋ T02 两组 Maven 运行（修正 surefire 过滤参数）
# 关键修正：surefire 3.1.2 用 -Dsurefire.failIfNoSpecifiedTests=false（不是 -DfailIfNoSpecifiedTests）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8
chcp.com 65001 | Out-Null
Set-Location 'D:\Develop_code\GraduationProject'
$raw = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-t01-t02-baseline-20260914\raw'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
$repo = '-Dmaven.repo.local=D:\maven_repository'
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

# ── ③ 等长改写负例（保留第一次运行日志作为"探针自身期望写错"的历史）─────────
if (Test-Path "$raw\t02-tamper-negative.log") {
    Move-Item "$raw\t02-tamper-negative.log" "$raw\t02-tamper-negative-firstrun.log" -Force
}
$out = "$raw\probe\t02tamperout"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$j = 'D:\maven_repository\com\fasterxml\jackson\core'
$cp = "$j\jackson-databind\2.17.2\jackson-databind-2.17.2.jar;$j\jackson-core\2.17.2\jackson-core-2.17.2.jar;$j\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar"
$log = "$raw\t02-tamper-negative.log"
@"
### 命令（逐字，第二版；冻结登记全部 43 个文件）
###   javac -encoding UTF-8 -cp "<jackson 2.17.2>" -d $out `
###       <仓>\analytics-server\platform-common\src\test\java\com\graduation\analytics\testsupport\RepoRoot.java `
###       <仓>\analytics-server\platform-app\src\test\java\com\graduation\analytics\ingestion\IngestionManifestSchemaSubset.java `
###       <仓>\analytics-server\platform-app\src\test\java\com\graduation\analytics\ingestion\ManifestFreezePatrol.java `
###       <仓>\$($raw -replace [regex]::Escape('D:\Develop_code\GraduationProject\'),'')\probe\T02TamperProbe.java
###   java -Dfile.encoding=UTF-8 -cp "$out;<仓>\analytics-server\platform-app\src\test\resources;<jackson 2.17.2>" `
###       com.graduation.analytics.ingestion.T02TamperProbe <仓> <临时工作目录>
### JDK: D:\Develop\JAVA17
### 时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD: $(git rev-parse HEAD)
### 目的: 证明巡检判红依赖 sha256 而非文件名存在性：等长改写（字节数不变、内容不同）
### 第一版运行见 t02-tamper-negative-firstrun.log（第一版只冻结了 2.json 一个条目，
### 其余 42 个文件因此按设计被登记为"新到件"，探针自己的期望写错了；巡检行为本身正确）
"@ | Set-Content -Encoding UTF8 $log
& D:\Develop\JAVA17\bin\javac.exe -encoding UTF-8 -cp $cp -d $out `
    'analytics-server\platform-common\src\test\java\com\graduation\analytics\testsupport\RepoRoot.java' `
    'analytics-server\platform-app\src\test\java\com\graduation\analytics\ingestion\IngestionManifestSchemaSubset.java' `
    'analytics-server\platform-app\src\test\java\com\graduation\analytics\ingestion\ManifestFreezePatrol.java' `
    "$raw\probe\T02TamperProbe.java" 2>&1 | Select-Object -First 20
"### JAVAC-EXIT=$LASTEXITCODE" | Add-Content -Encoding UTF8 $log
if ($LASTEXITCODE -eq 0) {
    $work = Join-Path $env:TEMP 't02tamper-work2'
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    & D:\Develop\JAVA17\bin\java.exe '-Dfile.encoding=UTF-8' -cp "$out;analytics-server\platform-app\src\test\resources;$cp" `
        com.graduation.analytics.ingestion.T02TamperProbe 'D:\Develop_code\GraduationProject' $work 2>&1 |
        Tee-Object -FilePath $log -Append
    "### JAVA-EXIT=$LASTEXITCODE" | Add-Content -Encoding UTF8 $log
}

# ── T02 两组：等 platform-common 可编译（其他泳道在改 metric 包，需要重试）───
function Run-Group([string]$tag, [string]$testClass) {
    for ($i = 1; $i -le 8; $i++) {
        $log = Join-Path $raw "$tag.log"
        "### 命令: mvn.cmd -o $repo -f analytics-server/pom.xml -pl platform-app -am -Dtest=$testClass -Dsurefire.failIfNoSpecifiedTests=false test" |
            Set-Content -Encoding UTF8 $log
        "### 尝试 $i / 8   开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $log
        & $mvn -o $repo -f analytics-server/pom.xml -pl platform-app -am "-Dtest=$testClass" -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 |
            Tee-Object -FilePath $log -Append | Out-Null
        $code = $LASTEXITCODE
        "### EXIT=$code" | Add-Content -Encoding UTF8 $log
        $compileBroken = (Select-String -Path $log -Pattern 'Compilation failure|找不到符号|不兼容的类型' -Quiet)
        "### platform-common 编译是否被外部泳道打断: $compileBroken" | Add-Content -Encoding UTF8 $log
        Write-Host "[$tag] 第 $i 次 EXIT=$code 编译被打断=$compileBroken"
        if (-not $compileBroken) { return $code }
        Start-Sleep -Seconds 25
    }
    return 99
}

Run-Group 'final-20a-t02-contract-group'  'IngestionManifestSourceSchemaTest'      | Out-Null
Run-Group 'final-20b-t02-runtime-patrol-group' 'IngestionManifestRuntimePatrolTest' | Out-Null

# ── T02 专项：等长改写那个 JUnit 用例（T 级证据）────────────────────────────
Run-Group 'final-20c-t02-tamper-method' 'IngestionManifestRuntimePatrolTest#patrolSeparatesArrivalsFromRealViolations' | Out-Null

# ── 全反应堆收集口径（外部红不阻断；聚合 failure>0 即整体 FAIL）────────────
$log = Join-Path $raw 'final-21-full-reactor-collect.log'
"### 命令: mvn.cmd -o $repo -f analytics-server/pom.xml -Dmaven.test.failure.ignore=true test" | Set-Content -Encoding UTF8 $log
"### 开始: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')   HEAD=$(git rev-parse HEAD)" | Add-Content -Encoding UTF8 $log
& $mvn -o $repo -f analytics-server/pom.xml -Dmaven.test.failure.ignore=true test 2>&1 |
    Tee-Object -FilePath $log -Append | Out-Null
"### EXIT=$LASTEXITCODE" | Add-Content -Encoding UTF8 $log

# ── 摘要 ─────────────────────────────────────────────────────────────────
$sum = Join-Path $raw 'final-22-summary.txt'
'=== 第三批关键行摘要 ===' | Set-Content -Encoding UTF8 $sum
Get-ChildItem $raw -Filter 'final-2*.log' | Sort-Object Name | ForEach-Object {
    "--- $($_.Name) ---" | Add-Content -Encoding UTF8 $sum
    (Select-String -Path $_.FullName -Pattern 'Tests run:.*(Failures|Errors)|BUILD SUCCESS|BUILD FAILURE|^### EXIT=|^### 尝试|SUCCESS \[|FAILURE \[|SKIPPED' |
        ForEach-Object { $_.Line.Trim() }) | Add-Content -Encoding UTF8 $sum
}
Write-Host 'DONE3'
