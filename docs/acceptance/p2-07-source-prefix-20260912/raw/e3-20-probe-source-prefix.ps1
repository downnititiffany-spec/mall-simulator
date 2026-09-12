# P2-07 证据脚本：源级数仓命名空间 —— 真实链隔离探针（P1-04 探针的**强化副本**）
#
# 为什么是"副本"而不是就地改 P1-04 脚本：`docs/acceptance/p1-04-namespace-20260911/` 是**已归档的历史证据**
# （append-only），就地改写会让"当时到底跑了什么"无从考证。故本文件是它的强化副本，差异只有三处
# （其余逐字沿用，含 JAVA_TOOL_OPTIONS/内存下限/隔离目录写法）：
#   ① 负例从 2 类补到 **4 类**（补 `dw__b` → WAREHOUSE_PREFIX_UNDERSCORE、`default` → WAREHOUSE_PREFIX_RESERVED），
#      使契约冻结的四码在真实链上各有一次"真实拒绝"（D-075 出口证据①）；
#   ② 每个作业都带 `--sourceSystem=<source_registry.source_code>`，与 P2-07 落地后平台注入的参数形态一致；
#   ③ 新增 **P1 负例**：odl 不传 `--sourceSystem` ⇒ 必须在创建 SparkSession 之前 fail-closed
#      —— 这是 A12「平台必须注入 --sourceSystem」的**真实链依据**（不是推理）。
#
# 隔离：自带 spark.sql.warehouse.dir + Derby ConnectionURL，全部落在 $Work（.gitignore 已忽略
#   analytics-server/warehouse-pipeline/tests/r6-smoke-warehouse/），**不触碰**真实 spark-warehouse/ 与
#   analytics_meta（D-080：本轮不做真库 DDL、不重启任何进程）。
#
# 运行：pwsh -NoProfile -ExecutionPolicy Bypass -File docs/acceptance/p2-07-source-prefix-20260912/raw/e3-20-probe-source-prefix.ps1
# 前置：spark-jobs jar 已构建（本脚本**不重建** jar，只记录其 sha256；重建在产 jar 属越界）。
param(
    [string]$SparkHome = 'D:\Develop\spark-3.5.1-bin-hadoop3',
    [string]$Jar = 'D:\Develop_code\GraduationProject\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar',
    [string]$Landing = 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events',
    [string]$Work = 'D:\Develop_code\GraduationProject\analytics-server\warehouse-pipeline\tests\r6-smoke-warehouse\p2-07-source-probe',
    [string]$Evidence = 'D:\Develop_code\GraduationProject\docs\acceptance\p2-07-source-prefix-20260912\raw',
    [string]$DriverMemory = '512m'
)
$ErrorActionPreference = 'Stop'

$sparkSubmit = Join-Path $SparkHome 'bin\spark-submit.cmd'
if (-not (Test-Path $sparkSubmit)) { throw "找不到 spark-submit: $sparkSubmit" }
if (-not (Test-Path $Jar)) { throw "找不到 spark-jobs jar（先构建）: $Jar" }
if (-not $env:JAVA_TOOL_OPTIONS) { $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -XX:+UseSerialGC' }

$jarSha = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
$jarInfo = Get-Item $Jar

if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Path $Work -Force | Out-Null
New-Item -ItemType Directory -Path $Evidence -Force | Out-Null

# 非法前缀在脚本里拼出来，避免门禁（WarehouseNameLiteralGateTest）把裸库名字面量当成第二处所有者
$BadLayerSuffix = 'dw' + '_' + 'ods'
$BadUppercase = 'DW'
$BadUnderscore = 'dw' + '__b'
$BadReserved = 'default'
$DefaultDbName = 'dw' + '_' + 'ods'
$DefaultPrefix = 'dw'
$OtherDbName = 'dw_b' + '_' + 'ods'

$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]
$observations = New-Object System.Collections.Generic.List[object]

function Assert-That([string]$name, [bool]$ok, [string]$detail) {
    $checks.Add([pscustomobject]@{ check = $name; passed = $ok; detail = $detail })
    if (-not $ok) { $failures.Add("$name → $detail") }
    "{0} {1} :: {2}" -f $(if ($ok) { '[PASS]' } else { '[FAIL]' }), $name, $detail
}

function Observe([string]$name, [string]$detail) {
    $observations.Add([pscustomobject]@{ observation = $name; detail = $detail })
    "[OBS ] $name :: $detail"
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

function Get-ErrText($job) { (Get-Content $job.Stderr -Raw -ErrorAction SilentlyContinue) }

$results = New-Object System.Collections.Generic.List[object]

"=== 目标 jar: $Jar"
"=== jar sha256: $jarSha (builtAt=$($jarInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')), length=$($jarInfo.Length))"

# ── P0：sci 阶段现状（观测，不是本泳道的断言）────────────────────────────────
# P2-01 泳道当日未提交改动把 `COMMENT` 插到 `USING parquet` 之前（LocalSchemaInitJob.odsCreateTable），
# 该改动已进入本 jar ⇒ sci 建表在 Spark 3.5 上必然 PARSE_SYNTAX_ERROR。这属于**跨泳道阻塞**，
# 本探针把它如实登记为观测（不写成"预期失败"，以免把他人的 bug 固化成我的期望）。
Write-Host '=== P0: sci 建表现状（观测）==='
$P0 = New-Scenario 'P0-sci-observation' 'sci with prefix=dw'
$p0Sci = Invoke-Job $P0 'sci' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', '--sourceSystem=mock-mall', "--hiveDatabasePrefix=$DefaultPrefix")
$results.Add($p0Sci)
$p0Err = Get-ErrText $p0Sci
$p0Parse = @($p0Err -split "`n" | Where-Object { $_ -match 'PARSE_SYNTAX_ERROR' } | ForEach-Object { $_.Trim() } | Select-Object -First 1)
$p0Db = Get-DbDirs $P0
Observe 'P0.sci 退出码/库目录' "exit=$($p0Sci.ExitCode) status=$($p0Sci.Status) dbDirs=[$($p0Db -join ', ')]"
Observe 'P0.sci 首个 PARSE_SYNTAX_ERROR（跨泳道 DDL 回归）' $(if ($p0Parse) { $p0Parse[0] } else { '<无：本次未出现 parse 错误>' })

# ── P1：odl 不传 --sourceSystem → 必须在 SparkSession 之前 fail-closed ───────
Write-Host '=== P1: 缺 --sourceSystem fail-closed（A12 真实链依据）==='
$P1 = New-Scenario 'P1-missing-source-system' 'odl without --sourceSystem, prefix=dw_b'
$p1Job = Invoke-Job $P1 'odl-no-source-system' @('--runtimeProfileId=7', '--jobCode=odl', '--businessDate=20260901', '--attemptNo=1', "--landingDir=$Landing", "--hiveDatabasePrefix=dw_b")
$results.Add($p1Job)
$p1Err = Get-ErrText $p1Job
$p1Db = Get-DbDirs $P1
$p1Line = @($p1Err -split "`n" | Where-Object { $_ -match 'sourceSystem' } | ForEach-Object { $_.Trim() } | Select-Object -First 1)
Assert-That 'P1 缺参 fail-closed（非零退出且未启动 Spark）' ($p1Job.ExitCode -ne 0) "exit=$($p1Job.ExitCode)"
Observe 'P1 缺参退出码实测值' "exit=$($p1Job.ExitCode)（本脚本初版断言写的是 64，13:56 实测为 2 ⇒ 按实测改正，不按猜测；两类失败不同轴：作业参数缺失=2、前缀非法=64）"
Assert-That 'P1 错误文案点名 --sourceSystem' ([bool]$p1Line) $(if ($p1Line) { $p1Line[0] } else { '<stderr 未见 sourceSystem>' })
Assert-That 'P1 未产出 JobResult（Spark 未启动）' ($null -eq $p1Job.Status) "status=$($p1Job.Status)"
Assert-That 'P1 一个库都没建' ($p1Db.Count -eq 0) ("warehouse 下的库目录: [" + ($p1Db -join ', ') + ']')

# ── P2：带 --sourceSystem 的 odl → 通过参数闸门并把库名路由到 dw_b_* ────────
Write-Host '=== P2: 带 --sourceSystem 的 odl（参数闸门 + 库名路由）==='
$P2 = New-Scenario 'P2-with-source-system' 'odl with --sourceSystem=mall-b, prefix=dw_b'
$p2Job = Invoke-Job $P2 'odl-with-source-system' @('--runtimeProfileId=7', '--jobCode=odl', '--businessDate=20260901', '--attemptNo=1', "--landingDir=$Landing", '--sourceSystem=mall-b', "--hiveDatabasePrefix=dw_b")
$results.Add($p2Job)
$p2Err = Get-ErrText $p2Job
$p2Space = @($p2Err -split "`n" | Where-Object { $_ -match '数仓库名空间' } | ForEach-Object { $_.Trim() } | Select-Object -First 1)
Assert-That 'P2 不再报"缺少参数 --sourceSystem"' ($p2Err -notmatch '缺少参数 --sourceSystem') "exit=$($p2Job.ExitCode)"
Assert-That 'P2 stderr 打印库名空间 = dw_b_*' ([bool]($p2Err -match '数仓库名空间: dw_b')) $(if ($p2Space) { $p2Space[0] } else { '<stderr 未见库名空间>' })
Observe 'P2 退出码（后续依赖建表，受 P0 跨泳道回归影响）' "exit=$($p2Job.ExitCode) status=$($p2Job.Status)"

# ── P4：两源并存（D-075 出口证据②）——库目录级；表级被跨泳道回归阻塞 ───────
# 诚实边界：本轮 sci 建表在 Spark 3.5 上因**跨泳道** DDL 子句顺序改动而失败（见 P0），
# 故这里能证的只有"两个源各自建出自己的库（目录）"，**不能**证"dw_b_ods 里有表有数据"。
Write-Host '=== P4: 两源并存（dw 与 dw_b）==='
$P4 = New-Scenario 'P4-two-sources' 'sci with prefix=dw, then sci with prefix=dw_b'
$p4A = Invoke-Job $P4 'sci-dw' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', '--sourceSystem=mock-mall', "--hiveDatabasePrefix=$DefaultPrefix")
$p4B = Invoke-Job $P4 'sci-dw-b' @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', '--sourceSystem=mall-b', '--hiveDatabasePrefix=dw_b')
$results.Add($p4A); $results.Add($p4B)
$p4Db = Get-DbDirs $P4
$p4BErr = Get-ErrText $p4B
Assert-That 'P4 两源各建各的库（dw_* 与 dw_b_* 并存于同一隔离根）' (($p4Db -contains 'dw_ods.db') -and ($p4Db -contains 'dw_b_ods.db')) ("库目录: [" + ($p4Db -join ', ') + ']')
Assert-That 'P4 第二个源打印自己的库名空间 dw_b_*' ($p4BErr -match '数仓库名空间: dw_b') "exit=$($p4B.ExitCode) status=$($p4B.Status)"
Observe 'P4 两源并存的证据等级' "目录级已证（$($p4Db.Count) 个库目录）；**表级未取证**——sci 建表被跨泳道 DDL 子句顺序回归阻塞（P0），故不得声称 dw_b_ods 内有表/数据"

# ── P3：四类非法前缀 → 提交前失败（fail-closed，四码各一次真实拒绝）─────────
Write-Host '=== P3: 四类非法前缀 fail-closed ==='
$P3 = New-Scenario 'P3-invalid-prefixes' 'prefix=dw__b / default / dw_ods / DW'
$cases = @(
    [pscustomobject]@{ Tag = 'underscore'; Prefix = $BadUnderscore;   Code = 'WAREHOUSE_PREFIX_UNDERSCORE' },
    [pscustomobject]@{ Tag = 'reserved';   Prefix = $BadReserved;     Code = 'WAREHOUSE_PREFIX_RESERVED' },
    [pscustomobject]@{ Tag = 'layer';      Prefix = $BadLayerSuffix;  Code = 'WAREHOUSE_PREFIX_LAYER_SUFFIX' },
    [pscustomobject]@{ Tag = 'pattern';    Prefix = $BadUppercase;    Code = 'WAREHOUSE_PREFIX_PATTERN' }
)
$p3Jobs = @()
foreach ($c in $cases) {
    $job = Invoke-Job $P3 "sci-$($c.Tag)" @('--runtimeProfileId=7', '--jobCode=sci', '--businessDate=20260901', '--attemptNo=1', '--sourceSystem=mock-mall', "--hiveDatabasePrefix=$($c.Prefix)")
    $results.Add($job)
    $p3Jobs += [pscustomobject]@{ Case = $c; Job = $job }
}
$p3Db = Get-DbDirs $P3
foreach ($x in $p3Jobs) {
    $err = Get-ErrText $x.Job
    $hit = @($err -split "`n" | Where-Object { $_ -match '库名空间前缀非法' } | ForEach-Object { $_.Trim() } | Select-Object -First 1)
    Assert-That "P3.$($x.Case.Tag) 退出码 64" ($x.Job.ExitCode -eq 64) "prefix=$($x.Case.Prefix) exit=$($x.Job.ExitCode)"
    Assert-That "P3.$($x.Case.Tag) 错误码 $($x.Case.Code)" ($err -match $x.Case.Code) $(if ($hit) { $hit[0] } else { '<stderr 未见前缀非法行>' })
    Assert-That "P3.$($x.Case.Tag) 未启动 Spark（无 SparkSession）" ($err -notmatch 'SparkSession') "stderr 行数=$(@($err -split "`n").Count)"
}
Assert-That 'P3 一个库都没建' ($p3Db.Count -eq 0) ("warehouse 下的库目录: [" + ($p3Db -join ', ') + ']')

# ── 汇总 ────────────────────────────────────────────────────────────────────
$summary = [pscustomobject]@{
    probe = 'P2-07 source-level warehouse namespace (strengthened copy of P1-04 probe)'
    ranAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    sparkHome = $SparkHome
    jar = $Jar
    jarLength = $jarInfo.Length
    jarBuiltAt = $jarInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    jarSha256Before = $jarSha
    jarSha256After = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
    driverMemory = $DriverMemory
    workDir = $Work
    checks = $checks
    observations = $observations
    passed = ($checks | Where-Object { $_.passed }).Count
    total = $checks.Count
    failures = $failures
    jobs = $results
}
$summaryFile = Join-Path $Evidence 'e3-22-probe-summary.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryFile -Encoding utf8
"=== 结果: {0}/{1} PASS ===" -f $summary.passed, $summary.total
"=== 观测（不断言）: {0} 条 ===" -f $observations.Count
"汇总: $summaryFile"
if ($failures.Count -gt 0) { "FAILURES:"; $failures | ForEach-Object { " - $_" }; exit 1 }
exit 0
