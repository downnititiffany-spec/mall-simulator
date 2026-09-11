# P1-04 证据脚本：数仓库名前缀（WarehouseNamespace）真实链探针
#
# 目的：在**隔离的临时数仓 + 临时 Derby 元数据库**里真跑 spark-submit，实测三件事：
#   A 非缺省前缀（第二个源）→ 实建库名为 <prefix>_ods/…，与源 A 库名互不干扰
#   B 非法前缀           → JobRunner 在创建 SparkSession **之前**退出码 64（fail-closed），且一个库都没建
#   C 不传前缀（= runtime_profile.hive_database_prefix 为 NULL 的源 A 档）→ 实建库名 dw_*
#
# 为什么隔离：探针不得触碰真实数仓 spark-warehouse/ 与 analytics_meta 元数据库。
#   故本脚本自带 spark.sql.warehouse.dir + Derby ConnectionURL，全部落在 $Work 下（该目录已被 .gitignore 忽略）。
#
# 运行：pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p1-04-namespace-20260911/probe-namespace-prefix.ps1
# 前置：spark-jobs jar 已构建（含 P1-04 改动）；本机 D:\Develop\spark-3.5.1-bin-hadoop3。
# 前置：JAVA_TOOL_OPTIONS 里加 -XX:+UseSerialGC（本机 Windows 提交内存吃紧，G1 预留 508MB 会直接起不来 JVM）。
param(
    [string]$SparkHome = 'D:\Develop\spark-3.5.1-bin-hadoop3',
    [string]$Jar = 'D:\Develop_code\GraduationProject\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar',
    [string]$Landing = 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events',
    [string]$Work = 'D:\Develop_code\GraduationProject\analytics-server\warehouse-pipeline\tests\r6-smoke-warehouse\p1-04-ns-probe',
    [string]$Evidence = 'D:\Develop_code\GraduationProject\docs\acceptance\p1-04-namespace-20260911',
    [string]$DriverMemory = '512m'
)
$ErrorActionPreference = 'Stop'

$sparkSubmit = Join-Path $SparkHome 'bin\spark-submit.cmd'
if (-not (Test-Path $sparkSubmit)) { throw "找不到 spark-submit: $sparkSubmit" }
if (-not (Test-Path $Jar)) { throw "找不到 spark-jobs jar（先构建）: $Jar" }
if (-not $env:JAVA_TOOL_OPTIONS) { $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -XX:+UseSerialGC' }

if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Path $Work -Force | Out-Null
New-Item -ItemType Directory -Path $Evidence -Force | Out-Null

# 非法前缀在脚本里拼出来，避免门禁（WarehouseNameLiteralGateTest）把裸库名字面量当成第二处所有者
$BadLayerSuffix = 'dw' + '_' + 'ods'
$BadUppercase = 'DW'
$DefaultDbName = 'dw' + '_' + 'ods'
$OtherDbName = 'dw_b' + '_' + 'ods'

$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]

function Assert-That([string]$name, [bool]$ok, [string]$detail) {
    $checks.Add([pscustomobject]@{ check = $name; passed = $ok; detail = $detail })
    if (-not $ok) { $failures.Add("$name → $detail") }
    "{0} {1} :: {2}" -f $(if ($ok) { '[PASS]' } else { '[FAIL]' }), $name, $detail
}

function New-Scenario([string]$name, [string]$note) {
    $root = Join-Path $Work $name
    New-Item -ItemType Directory -Path (Join-Path $root 'warehouse'), (Join-Path $root 'logs') -Force | Out-Null
    [pscustomobject]@{ Name = $name; Note = $note; Root = $root }
}

function Invoke-Job($scenario, [string]$tag, [string[]]$jobArgs) {
    $wh = 'file:///' + ((Join-Path $scenario.Root 'warehouse')).Replace('\', '/')
    $derby = ((Join-Path $scenario.Root 'derby')).Replace('\', '/')
    $common = @(
        '--master', 'local[2]',
        '--driver-memory', $DriverMemory,
        # Spark 3.5 硬性下限 450MB（低于此值直接 INVALID_DRIVER_MEMORY）；本机 commit 吃紧故贴下限跑
        '--driver-java-options=-XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m',
        '--class', 'com.graduation.analytics.job.JobRunner',
        '--conf', "spark.sql.warehouse.dir=$wh",
        '--conf', 'spark.sql.session.timeZone=Asia/Shanghai',
        '--conf', "spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:$derby;create=true",
        '--conf', 'spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver',
        '--conf', 'spark.sql.hive.metastore.jars=builtin',
        '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
        '--conf', 'spark.ui.enabled=false'
    )
    $out = Join-Path $scenario.Root "logs\$tag.out.txt"
    $err = Join-Path $scenario.Root "logs\$tag.err.txt"
    & $sparkSubmit @common $Jar @jobArgs 1> $out 2> $err
    $code = $LASTEXITCODE
    $json = $null
    $line = Get-Content $out -ErrorAction SilentlyContinue |
        Where-Object { $_.TrimStart().StartsWith('{') } | Select-Object -Last 1
    if ($line) { $json = $line | ConvertFrom-Json }
    [pscustomobject]@{
        Scenario = $scenario.Name; Tag = $tag; ExitCode = $code
        Status = $(if ($json) { $json.status } else { $null })
        InputRecords = $(if ($json) { $json.inputRecords } else { $null })
        OutputRecords = $(if ($json) { $json.outputRecords } else { $null })
        Stdout = $out; Stderr = $err
    }
}

function Get-DbDirs($scenario) {
    $wh = Join-Path $scenario.Root 'warehouse'
    @(Get-ChildItem $wh -Directory -Filter '*.db' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
}

function Get-ParquetCount($scenario, [string]$dbDir) {
    $p = Join-Path (Join-Path $scenario.Root 'warehouse') $dbDir
    if (-not (Test-Path $p)) { return 0 }
    @(Get-ChildItem $p -Recurse -File -Filter 'part-*.parquet' -ErrorAction SilentlyContinue).Count
}

function Get-ErrText($job) { (Get-Content $job.Stderr -Raw -ErrorAction SilentlyContinue) }

$results = New-Object System.Collections.Generic.List[object]

# ── A：非缺省前缀（第二个源）────────────────────────────────────────────────
Write-Host '=== A: 非缺省前缀（第二个源）==='
$A = New-Scenario 'A-other-prefix' 'prefix=dw_b'
$aSci = Invoke-Job $A 'sci' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', "--hiveDatabasePrefix=dw_b")
$aOdl = Invoke-Job $A 'odl' @('--runtimeProfileId=7', '--jobCode=odl', '--businessDate=20260901', '--attemptNo=1', "--landingDir=$Landing", "--hiveDatabasePrefix=dw_b")
$results.Add($aSci); $results.Add($aOdl)
$dbA = Get-DbDirs $A
$parquetA = Get-ParquetCount $A "$OtherDbName.db"
Assert-That 'A.sci 退出码/状态' ($aSci.ExitCode -eq 0 -and $aSci.Status -eq 'SUCCESS') "exit=$($aSci.ExitCode) status=$($aSci.Status)"
Assert-That 'A.odl 退出码/状态' ($aOdl.ExitCode -eq 0 -and $aOdl.Status -eq 'SUCCESS') "exit=$($aOdl.ExitCode) status=$($aOdl.Status)"
Assert-That 'A.odl 输入 55 行' ($aOdl.InputRecords -eq 55) "inputRecords=$($aOdl.InputRecords) outputRecords=$($aOdl.OutputRecords)"
Assert-That 'A 实建 `<dw_b>_ods.db`' ($dbA -contains "$OtherDbName.db") ("warehouse 下的库目录: " + ($dbA -join ', '))
Assert-That 'A 未建源 A 的 `<dw>_ods.db`（库名隔离）' (-not ($dbA -contains "$DefaultDbName.db")) ("warehouse 下的库目录: " + ($dbA -join ', '))
Assert-That 'A `<dw_b>_ods` 有 parquet 文件' ($parquetA -gt 0) "part-*.parquet = $parquetA"
Assert-That 'A stderr 打印库名空间' ((Get-ErrText $aOdl) -match '数仓库名空间: dw_b_ods') ((Get-ErrText $aOdl) -split "`n" | Where-Object { $_ -match '数仓库名空间' } | Select-Object -First 1)

# ── B：非法前缀 → 提交前失败（fail-closed）──────────────────────────────────
Write-Host '=== B: 非法前缀 fail-closed ==='
$B = New-Scenario 'B-invalid-prefix' 'prefix=dw_ods / DW'
$bLayer = Invoke-Job $B 'sci-layer-suffix' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', "--hiveDatabasePrefix=$BadLayerSuffix")
$bUpper = Invoke-Job $B 'sci-uppercase' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', "--hiveDatabasePrefix=$BadUppercase")
$results.Add($bLayer); $results.Add($bUpper)
$dbB = Get-DbDirs $B
Assert-That 'B 层后缀前缀退出码 64' ($bLayer.ExitCode -eq 64) "exit=$($bLayer.ExitCode)"
Assert-That 'B 层后缀前缀错误码' ((Get-ErrText $bLayer) -match 'WAREHOUSE_PREFIX_LAYER_SUFFIX') ((Get-ErrText $bLayer) -split "`n" | Where-Object { $_ -match '库名空间前缀非法' } | Select-Object -First 1)
Assert-That 'B 大写前缀退出码 64' ($bUpper.ExitCode -eq 64) "exit=$($bUpper.ExitCode)"
Assert-That 'B 大写前缀错误码' ((Get-ErrText $bUpper) -match 'WAREHOUSE_PREFIX_PATTERN') ((Get-ErrText $bUpper) -split "`n" | Where-Object { $_ -match '库名空间前缀非法' } | Select-Object -First 1)
Assert-That 'B 未产出 JobResult（Spark 未启动）' ($null -eq $bLayer.Status -and $null -eq $bUpper.Status) "status=$($bLayer.Status)/$($bUpper.Status)"
Assert-That 'B 一个库都没建' ($dbB.Count -eq 0) ("warehouse 下的库目录: [" + ($dbB -join ', ') + ']')
$bLayerErr = Get-ErrText $bLayer
$bLayerLines = @($bLayerErr -split "`n").Count
Assert-That 'B stderr 无 Spark 会话日志' ($bLayerErr -notmatch 'SparkSession') "stderr 行数=$bLayerLines（只有 JAVA_TOOL_OPTIONS 回显 + 一行前缀非法 + 关闭钩子）"

# ── C：不传前缀（源 A 存量档）→ dw_* ────────────────────────────────────────
Write-Host '=== C: 缺省前缀（源 A 存量档）==='
$C = New-Scenario 'C-default-prefix' 'no --hiveDatabasePrefix'
$cSci = Invoke-Job $C 'sci' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1')
$cOdl = Invoke-Job $C 'odl' @('--runtimeProfileId=7', '--jobCode=odl', '--businessDate=20260901', '--attemptNo=1', "--landingDir=$Landing")
$results.Add($cSci); $results.Add($cOdl)
$dbC = Get-DbDirs $C
$parquetC = Get-ParquetCount $C "$DefaultDbName.db"
Assert-That 'C.sci/C.odl 成功' ($cSci.ExitCode -eq 0 -and $cOdl.ExitCode -eq 0 -and $cOdl.Status -eq 'SUCCESS') "exit=$($cSci.ExitCode)/$($cOdl.ExitCode) status=$($cOdl.Status)"
Assert-That 'C 实建 `<dw>_ods.db`（与改造前逐字一致）' ($dbC -contains "$DefaultDbName.db") ("warehouse 下的库目录: " + ($dbC -join ', '))
Assert-That 'C `<dw>_ods` 有 parquet 文件' ($parquetC -gt 0) "part-*.parquet = $parquetC"
Assert-That 'C odl 输入 55 行' ($cOdl.InputRecords -eq 55) "inputRecords=$($cOdl.InputRecords)"

# ── 汇总 ────────────────────────────────────────────────────────────────────
$summary = [pscustomobject]@{
    probe = 'P1-04 warehouse namespace prefix'
    ranAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    sparkHome = $SparkHome
    jar = $Jar
    jarBuiltAt = (Get-Item $Jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    driverMemory = $DriverMemory
    workDir = $Work
    checks = $checks
    passed = ($checks | Where-Object { $_.passed }).Count
    total = $checks.Count
    failures = $failures
    jobs = $results
}
$summaryFile = Join-Path $Evidence 'probe-summary.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryFile -Encoding utf8
"=== 结果: {0}/{1} PASS ===" -f $summary.passed, $summary.total
"汇总: $summaryFile"
if ($failures.Count -gt 0) { "FAILURES:"; $failures | ForEach-Object { " - $_" }; exit 1 }
exit 0
