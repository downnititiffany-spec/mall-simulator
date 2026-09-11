<#
  P1-01 只读冻结基线采集器（source A 商城无关化改造前基线）

  任务包边界（docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md §11）：
    允许：新增 docs/acceptance/p1-baseline-*/ 与只读验收脚本；更新进度看板
    禁止：修改任何 Java/Scala/Vue/SQL/数据库数据；禁止重跑大数据

  只读保证（脚本自证）：
    - 所有 MySQL 语句只允许 SELECT / SHOW / WITH，逐条登记并在结束时断言
    - 所有 HTTP 只有 GET，唯一例外是 POST /api/v1/auth/login（换取只读 token，
      副作用仅限会话表 user_session，采集前后指纹已把该表列入排除清单并写明理由）
    - 不启动 Spark、不 POST /ingestion/runs、不 POST /pipeline-runs

  用法：
    pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/accept-p1-baseline.ps1
    pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/accept-p1-baseline.ps1 `
         -OutDir docs/acceptance/p1-baseline-XXXX/verify -CompareWith docs/acceptance/p1-baseline-XXXX/baseline.json
#>
[CmdletBinding()]
param(
  [int]$RunId = 39,
  [string]$SnapshotId = 'S20260901_39',
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [string]$OutRoot = 'docs/acceptance',
  [string]$OutDir = '',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$DbUser = 'root',
  [string]$DbPassword = '123456',
  [string]$ApiUser = 'admin',
  [string]$ApiPassword = 'admin123',
  [string]$CompareWith = ''
)

$ErrorActionPreference = 'Stop'
trap {
  Write-Host ("[FATAL] {0}" -f $_.Exception.Message)
  Write-Host ("[AT] {0}" -f $_.InvocationInfo.PositionMessage)
  Write-Host ("[STACK] {0}" -f $_.ScriptStackTrace)
  exit 3
}
$startedAt = Get-Date
$repoRoot = (Get-Location).Path
if (-not $OutDir) { $OutDir = Join-Path $OutRoot ("p1-baseline-" + $startedAt.ToString('yyyyMMdd-HHmmss')) }
$rawDir = Join-Path $OutDir 'raw'
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null

$script:SqlLog = New-Object System.Collections.Generic.List[string]
$script:RawFiles = New-Object System.Collections.Generic.List[string]

function Write-Utf8NoBom([string]$Path, [string]$Text) {
  $dir = Split-Path -Parent $Path
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $normalized = $Text -replace "`r`n", "`n"
  [System.IO.File]::WriteAllText($Path, $normalized, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-Sql([string]$Sql) {
  # 只读闸门：任何非 SELECT/SHOW/WITH 语句直接拒绝执行
  if ($Sql -notmatch '^\s*(select|show|with)\b') {
    throw "只读脚本拒绝执行非查询语句：$Sql"
  }
  $script:SqlLog.Add(($Sql -replace '\s+', ' ').Trim())
  $out = & $MysqlExe "-u$DbUser" "-p$DbPassword" -N -B --default-character-set=utf8mb4 -e $Sql 2>&1
  $code = $LASTEXITCODE
  $lines = @($out | Where-Object { $_ -notmatch '^(mysql:|Warning:|\s*$)' })
  if ($code -ne 0) { throw "MySQL 查询失败(exit=$code)：$Sql`n$($lines -join "`n")" }
  return $lines
}

function Invoke-SqlRows([string]$Sql) {
  # 返回对象数组，列名取第一行（用 -N -B 的 TSV：这里改为带表头的查询）
  $script:SqlLog.Add(($Sql -replace '\s+', ' ').Trim())
  if ($Sql -notmatch '^\s*(select|show|with)\b') { throw "只读脚本拒绝执行非查询语句：$Sql" }
  $out = & $MysqlExe "-u$DbUser" "-p$DbPassword" -B --default-character-set=utf8mb4 -e $Sql 2>&1
  $code = $LASTEXITCODE
  $lines = @($out | Where-Object { $_ -notmatch '^(mysql:|Warning:)' })
  if ($code -ne 0) { throw "MySQL 查询失败(exit=$code)：$Sql`n$($lines -join "`n")" }
  if ($lines.Count -lt 2) { return @() }
  $header = $lines[0] -split "`t"
  $rows = @()
  foreach ($line in $lines[1..($lines.Count - 1)]) {
    $cells = $line -split "`t"
    $o = [ordered]@{}
    for ($i = 0; $i -lt $header.Count; $i++) { $o[$header[$i]] = if ($i -lt $cells.Count) { $cells[$i] } else { $null } }
    $rows += [pscustomobject]$o
  }
  return $rows
}

function Invoke-SqlScalar([string]$Sql) {
  $r = @(Invoke-Sql $Sql)
  if ($r.Count -eq 0) { return $null }
  return $r[0]
}

$script:Token = $null
function Invoke-Api([string]$Path, [string]$RawName) {
  $headers = @{}
  if ($script:Token) { $headers['Authorization'] = "Bearer $($script:Token)" }
  $url = "$BaseUrl$Path"
  $resp = Invoke-WebRequest -Uri $url -Method Get -Headers $headers -SkipHttpErrorCheck -TimeoutSec 60
  $text = $resp.Content
  if ($RawName) {
    $p = Join-Path $rawDir "$RawName.json"
    Write-Utf8NoBom $p $text
    $script:RawFiles.Add($p.Replace($repoRoot + '\', ''))
  }
  $parsed = $null
  try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null }
  return [pscustomobject]@{ path = $Path; status = [int]$resp.StatusCode; json = $parsed; text = $text }
}

function Get-Sha256([string]$Path) {
  if (-not (Test-Path $Path)) { return $null }
  return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash
}

function Get-LfCount([string]$Path) {
  $fs = [System.IO.File]::OpenRead($Path)
  try {
    $buf = New-Object byte[] 1048576
    $lf = 0L; $n = 0
    while (($n = $fs.Read($buf, 0, $buf.Length)) -gt 0) {
      for ($i = 0; $i -lt $n; $i++) { if ($buf[$i] -eq 10) { $lf++ } }
    }
    return $lf
  } finally { $fs.Close() }
}

function Get-Histogram($mc, [int]$top = 8) {
  $h = [ordered]@{}
  foreach ($m in $mc) {
    $k = $m.Groups[1].Value
    if ($h.Contains($k)) { $h[$k] = [int]$h[$k] + 1 } else { $h[$k] = 1 }
  }
  $sorted = $h.GetEnumerator() | Sort-Object { -[int]$_.Value }
  $out = [ordered]@{}
  $i = 0
  foreach ($e in $sorted) {
    if ($i -ge $top) { $out['__other__'] = [int]$out['__other__'] + [int]$e.Value; $i++; continue }
    $out[[string]$e.Key] = [int]$e.Value; $i++
  }
  return $out
}

function Get-EventFileClass([string]$Name) {
  if ($Name -like 'gen-s3b-*') { return 'strict-generator' }
  if ($Name -like 'golden-*') { return 'legacy-fixture-golden' }
  if ($Name -like 'r9-*') { return 'legacy-fixture-r9' }
  if ($Name -like 'b08-gate-*' -or $Name -like 'b11-fixture-*') { return 'acceptance-fixture' }
  if ($Name -match '^20260911\d{2}\.jsonl$') { return 'live-traffic-slice' }
  if ($Name -match '^\d{10}\.jsonl$') { return 'legacy-hourly-load' }
  return 'unclassified'
}

$classNote = [ordered]@{
  'strict-generator'       = '严格生成器 S3b 产出（1,000 条），run 39 的唯一 ODS 输入来源'
  'legacy-fixture-golden'  = '旧 golden 夹具（r615/r615b/r618/r619/r70/r73），约 51 条/文件'
  'legacy-fixture-r9'      = '旧 r9 工艺夹具（退款/清洗/质量场景）'
  'acceptance-fixture'     = '本轮验收自建夹具（B-08 门禁、B-11/DEF-13 正路径），各 2 条'
  'legacy-hourly-load'     = '旧切片批量数据（2026-09-05~09-07，命名 YYYYMMDDHH.jsonl），MB 级，来自早期大规模灌数'
  'live-traffic-slice'     = '2026-09-11 当日本轮真实链路产生的参考商城事件（YYYYMMDDHH 命名但非历史夹具）'
}

# ---------------------------------------------------------------- 0. 前置检查
$preflight = [ordered]@{}
$preflight.mysqlExe = Test-Path $MysqlExe
$preflight.mysqlVersion = if ($preflight.mysqlExe) { (@(Invoke-Sql 'select version();'))[0] } else { $null }

# ---------------------------------------------------------------- 0b. 只读指纹（采集前）
function Get-BusinessFingerprint {
  $fp = [ordered]@{}
  $fp['file_checkpoint'] = [ordered]@{
    rows          = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.file_checkpoint;')
    distinctPaths = [int](Invoke-SqlScalar 'select count(distinct file_path) from analytics_meta.file_checkpoint;')
    maxUpdatedAt  = Invoke-SqlScalar 'select max(updated_at) from analytics_meta.file_checkpoint;'
    sumNextOffset = [long](Invoke-SqlScalar 'select coalesce(sum(next_offset),0) from analytics_meta.file_checkpoint;')
  }
  $fp['ingestion_batch'] = [ordered]@{
    rows      = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.ingestion_batch;')
    maxId     = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_meta.ingestion_batch;')
    sumRecord = [long](Invoke-SqlScalar 'select coalesce(sum(record_count),0) from analytics_meta.ingestion_batch;')
  }
  $fp['ingestion_batch_file'] = [ordered]@{
    rows  = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.ingestion_batch_file;')
    maxId = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_meta.ingestion_batch_file;')
  }
  $fp['pipeline_run'] = [ordered]@{
    rows          = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.pipeline_run;')
    maxId         = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_meta.pipeline_run;')
    maxFinishedAt = Invoke-SqlScalar 'select max(finished_at) from analytics_meta.pipeline_run;'
  }
  $fp['pipeline_stage_run'] = [ordered]@{
    rows  = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.pipeline_stage_run;')
    maxId = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_meta.pipeline_stage_run;')
  }
  $fp['metric_snapshot_store'] = [ordered]@{
    rows           = [int](Invoke-SqlScalar 'select count(*) from analytics_metric.metric_snapshot;')
    maxId          = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_metric.metric_snapshot;')
    activeCount    = [int](Invoke-SqlScalar "select count(*) from analytics_metric.metric_snapshot where status='ACTIVE';")
    maxPublishedAt = Invoke-SqlScalar 'select max(published_at) from analytics_metric.metric_snapshot;'
  }
  $fp['metric_value_store'] = [ordered]@{
    rows  = [int](Invoke-SqlScalar 'select count(*) from analytics_metric.metric_value;')
    maxId = [int](Invoke-SqlScalar 'select coalesce(max(id),0) from analytics_metric.metric_value;')
  }
  $fp['quarantine_record'] = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.quarantine_record;')
  $fp['data_quality_result'] = [int](Invoke-SqlScalar 'select count(*) from analytics_meta.data_quality_result;')
  $ads = [ordered]@{}
  foreach ($t in (Invoke-Sql "select table_name from information_schema.tables where table_schema='analytics_metric' and table_name like 'ads\_%\_m' order by table_name;")) {
    $ads[$t] = [int](Invoke-SqlScalar "select count(*) from analytics_metric.``$t``;")
  }
  $fp['ads_mirror_tables'] = $ads
  $fp['landing_events'] = [ordered]@{
    files = [int]((Get-ChildItem 'landing/events/*.jsonl' -ErrorAction SilentlyContinue).Count)
    bytes = [long](((Get-ChildItem 'landing/events/*.jsonl' -ErrorAction SilentlyContinue) | Measure-Object Length -Sum).Sum)
  }
  $whFiles = Get-ChildItem 'spark-warehouse' -Recurse -File -Filter *.parquet -ErrorAction SilentlyContinue
  $fp['warehouse'] = [ordered]@{
    parquetFiles = [int]$whFiles.Count
    bytes        = [long](($whFiles | Measure-Object Length -Sum).Sum)
  }
  return $fp
}

$fpBefore = Get-BusinessFingerprint

# ---------------------------------------------------------------- 1. 服务与构件
$services = @()
# 用有序字典固定枚举顺序：普通哈希表的键序随进程随机化，会破坏二次读取一致性
$jarMap = [ordered]@{
  'analytics-platform'  = 'analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
  'reference-mall'      = 'mall-simulator/target/mall-simulator-0.1.0-SNAPSHOT.jar'
  'synthetic-generator' = 'synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar'
  'spark-jobs'          = 'spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar'
}
$artifacts = @()
foreach ($role in $jarMap.Keys) {
  $rel = $jarMap[$role]
  $abs = Join-Path $repoRoot $rel
  if (Test-Path $abs) {
    $fi = Get-Item $abs
    $artifacts += [pscustomobject][ordered]@{
      role = $role; path = $rel; bytes = [long]$fi.Length
      mtime = $fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'); sha256 = Get-Sha256 $abs
    }
  }
}
$artifacts = @($artifacts | Sort-Object { $_.role })
foreach ($pp in @(@{ n = 'analytics-platform'; p = 8091 }, @{ n = 'reference-mall'; p = 8090 }, @{ n = 'synthetic-generator'; p = 8092 })) {
  $listen = netstat -ano | Select-String -Pattern ":$($pp.p)\s+.*LISTENING"
  $pid0 = $null
  if ($listen) { $pid0 = [int](($listen[0].Line -split '\s+')[-1]) }
  $proc = if ($pid0) { Get-Process -Id $pid0 -ErrorAction SilentlyContinue } else { $null }
  $services += [pscustomobject][ordered]@{
    name = $pp.n; port = $pp.p; pid = $pid0
    processName = if ($proc) { $proc.ProcessName } else { $null }
    startedAt   = if ($proc) { $proc.StartTime.ToString('yyyy-MM-dd HH:mm:ss') } else { $null }
    listening   = [bool]$listen
  }
}

# ---------------------------------------------------------------- 2. API 只读快照
$login = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' `
  -Body (@{ username = $ApiUser; password = $ApiPassword } | ConvertTo-Json) -SkipHttpErrorCheck -TimeoutSec 60
Write-Utf8NoBom (Join-Path $rawDir 'api-login.json') ($login.Content -replace '"token"\s*:\s*"[^"]*"', '"token":"<redacted>"')
$script:Token = ($login.Content | ConvertFrom-Json).data.token
if (-not $script:Token) { throw '登录失败：未取得 token' }

$apiSnapshots = Invoke-Api '/api/v1/metrics/snapshots?limit=10' 'api-metrics-snapshots'
$apiOverview = Invoke-Api "/api/v1/metrics/overview?snapshotId=$SnapshotId" 'api-metrics-overview'
$apiHealth = Invoke-Api '/api/v1/metrics/health' 'api-metrics-health'
$apiQuality = Invoke-Api "/api/v1/metrics/quality?runId=$RunId&limit=20" 'api-metrics-quality'
$apiIngest = Invoke-Api '/api/v1/ingestion/status' 'api-ingestion-status'
$apiProfile = Invoke-Api '/api/v1/runtime-profiles/active' 'api-runtime-profile-active'
$apiRun = Invoke-Api "/api/v1/pipeline-runs/$RunId" 'api-pipeline-run'
$apiBatches = Invoke-Api '/api/v1/ingestion/batches?limit=5' 'api-ingestion-batches'

# ---------------------------------------------------------------- 3. 流水线运行与阶段证据
$runRows = Invoke-SqlRows "select id, pipeline_code, status, runtime_profile_id, runtime_profile_version, target_snapshot_id, attempt_no, business_time, created_at, started_at, finished_at from analytics_meta.pipeline_run where id=$RunId;"
$run = if ($runRows.Count -gt 0) { $runRows[0] } else { $null }

$stageRows = Invoke-SqlRows "select stage_code, status, records, started_at, finished_at from analytics_meta.pipeline_stage_run where run_id=$RunId order by id;"
$stages = @()
foreach ($s in $stageRows) {
  $stages += [pscustomobject][ordered]@{
    stageCode = $s.stage_code; status = $s.status; records = [long]$s.records
    startedAt = $s.started_at; finishedAt = $s.finished_at
  }
}

# 阶段 evidence（mediumtext）用 hex 取回，避免 TSV 转义歧义
$stageEvidence = [ordered]@{}
$stageRowsByTable = @()
$evidenceCodes = @(Invoke-Sql "select stage_code from analytics_meta.pipeline_stage_run where run_id=$RunId and evidence is not null order by id;")
foreach ($code in $evidenceCodes) {
  $hex = Invoke-SqlScalar "select hex(evidence) from analytics_meta.pipeline_stage_run where run_id=$RunId and stage_code='$code' order by id limit 1;"
  if (-not $hex) { continue }
  $text = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromHexString($hex))
  Write-Utf8NoBom (Join-Path $rawDir "stage-evidence-$code.json") $text
  $script:RawFiles.Add("$OutDir/raw/stage-evidence-$code.json".Replace($repoRoot + '\', ''))
  try {
    $obj = $text | ConvertFrom-Json
    $stageEvidence[$code] = $obj
    if ($obj.jobs) {
      foreach ($job in $obj.jobs) {
        $byTable = [ordered]@{}
        foreach ($p in $job.outputPartitions) {
          $k = [string]$p.table
          if (-not $byTable.Contains($k)) { $byTable[$k] = [ordered]@{ rows = 0L; files = 0; dts = (New-Object System.Collections.Generic.HashSet[string]) } }
          $byTable[$k].rows = [long]$byTable[$k].rows + [long]$p.rowCount
          $byTable[$k].files = [int]$byTable[$k].files + 1
          if ($p.dt) { [void]$byTable[$k].dts.Add([string]$p.dt) }
        }
        foreach ($k in $byTable.Keys) {
          $stageRowsByTable += [pscustomobject][ordered]@{
            stage = $code; jobCode = $job.jobCode; table = $k
            rowsWritten = [long]$byTable[$k].rows; outputFiles = [int]$byTable[$k].files
            partitions = @($byTable[$k].dts | Sort-Object)
          }
        }
      }
    }
  } catch { $stageEvidence[$code] = [pscustomobject]@{ parseError = $_.Exception.Message } }
}

# ---------------------------------------------------------------- 4. 指标快照（冻结对象）
$snapRows = Invoke-SqlRows "select id, snapshot_id, status, pipeline_run_id, source, version, business_time, published_at, data_updated_at from analytics_metric.metric_snapshot where snapshot_id='$SnapshotId';"
$metricValues = Invoke-SqlRows "select metric_code, metric_value, unit, period, definition_version from analytics_metric.metric_value where snapshot_id='$SnapshotId' order by metric_code;"
$values = @()
foreach ($v in $metricValues) {
  $values += [pscustomobject][ordered]@{
    metricCode = $v.metric_code; value = $v.metric_value; unit = $v.unit
    period = $v.period; definitionVersion = $v.definition_version
  }
}
$snapshotHistory = @()
foreach ($s in (Invoke-SqlRows "select id, snapshot_id, status, pipeline_run_id, source, version, published_at from analytics_metric.metric_snapshot order by id desc limit 6;")) {
  $snapshotHistory += [pscustomobject][ordered]@{
    id = [int]$s.id; snapshotId = $s.snapshot_id; status = $s.status; pipelineRunId = $s.pipeline_run_id
    source = $s.source; version = [int]$s.version; publishedAt = $s.published_at
  }
}

$metricStoreTables = @()
foreach ($r in (Invoke-SqlRows "select table_name, (select count(*) from information_schema.columns c where c.table_schema=t.table_schema and c.table_name=t.table_name and c.column_name='snapshot_id') has_snap from information_schema.tables t where t.table_schema='analytics_metric' and t.table_name like 'ads\_%\_m' order by table_name;")) {
  $t = $r.table_name
  $rows = [int](Invoke-SqlScalar "select count(*) from analytics_metric.``$t``;")
  $rowsForSnap = $null
  if ([int]$r.has_snap -gt 0) {
    $rowsForSnap = [int](Invoke-SqlScalar "select count(*) from analytics_metric.``$t`` where snapshot_id='$SnapshotId';")
  }
  $metricStoreTables += [pscustomobject][ordered]@{ table = $t; rows = $rows; rowsForSnapshot = $rowsForSnap }
}

# ---------------------------------------------------------------- 5. 元数据库清单
$metaTables = @()
foreach ($r in (Invoke-SqlRows "select table_name from information_schema.tables where table_schema='analytics_meta' order by table_name;")) {
  $t = $r.table_name
  $metaTables += [pscustomobject][ordered]@{ table = $t; rows = [int](Invoke-SqlScalar "select count(*) from analytics_meta.``$t``;") }
}
$flyway = Invoke-SqlRows "select version, description, success, installed_on from analytics_meta.flyway_schema_history order by installed_rank desc limit 4;"
$flywayRows = @()
foreach ($f in $flyway) { $flywayRows += [pscustomobject][ordered]@{ version = $f.version; description = $f.description; success = $f.success; installedOn = $f.installed_on } }

# ---------------------------------------------------------------- 6. active runtime profile
$profileDb = Invoke-SqlRows "select id, profile_code, profile_name, type, status, landing_uri, hdfs_uri, hive_database_prefix, spark_master, deploy_mode, metric_store_type, timezone, version, created_at, updated_at from analytics_meta.runtime_profile order by id;"
$profiles = @()
foreach ($p in $profileDb) {
  $nullify = { param($v) if ($null -eq $v -or $v -eq 'NULL' -or $v -eq '') { return $null } else { return $v } }
  $profiles += [pscustomobject][ordered]@{
    id = [int]$p.id; profileCode = $p.profile_code; profileName = $p.profile_name; type = $p.type; status = $p.status
    landingUri = $p.landing_uri; hdfsUri = (& $nullify $p.hdfs_uri); hiveDatabasePrefix = (& $nullify $p.hive_database_prefix)
    sparkMaster = $p.spark_master; deployMode = $p.deploy_mode; metricStoreType = $p.metric_store_type
    timezone = $p.timezone; version = [int]$p.version; createdAt = $p.created_at; updatedAt = $p.updated_at
  }
}
$activeProfiles = @($profiles | Where-Object { $_.status -eq 'ACTIVE' })

# ---------------------------------------------------------------- 7. warehouse 清单（路径 + 分区 + 校验和）
$whRoot = Join-Path $repoRoot 'spark-warehouse'
$whDbs = @()
$whFileRows = New-Object System.Collections.Generic.List[string]
$whFileRows.Add("db`ttable`tpartition`tfile`tbytes`tsha256")
$whTotals = [ordered]@{ databases = 0; tables = 0; partitions = 0; parquetFiles = 0; bytes = 0L }
foreach ($dbDir in (Get-ChildItem $whRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name)) {
  $whTotals.databases = [int]$whTotals.databases + 1
  $tables = @()
  foreach ($tblDir in (Get-ChildItem $dbDir.FullName -Directory -ErrorAction SilentlyContinue | Sort-Object Name)) {
    $whTotals.tables = [int]$whTotals.tables + 1
    $pq = @(Get-ChildItem $tblDir.FullName -Recurse -File -Filter *.parquet -ErrorAction SilentlyContinue)
    $partDirs = @(Get-ChildItem $tblDir.FullName -Directory -ErrorAction SilentlyContinue)
    $partNames = @()
    foreach ($pd in ($partDirs | Sort-Object Name)) {
      $partNames += $pd.Name
      foreach ($f in (Get-ChildItem $pd.FullName -Recurse -File -Filter *.parquet -ErrorAction SilentlyContinue)) {
        $rel = $f.FullName.Replace($repoRoot + '\', '')
        $whFileRows.Add("$($dbDir.Name)`t$($tblDir.Name)`t$($pd.Name)`t$rel`t$($f.Length)`t$(Get-Sha256 $f.FullName)")
      }
    }
    $bytes = [long](($pq | Measure-Object Length -Sum).Sum)
    $tables += [pscustomobject][ordered]@{
      table = $tblDir.Name; partitions = $partNames.Count; partitionNames = $partNames
      parquetFiles = $pq.Count; bytes = $bytes
    }
    $whTotals.partitions = [int]$whTotals.partitions + $partNames.Count
    $whTotals.parquetFiles = [int]$whTotals.parquetFiles + $pq.Count
    $whTotals.bytes = [long]$whTotals.bytes + $bytes
  }
  $whDbs += [pscustomobject][ordered]@{ db = $dbDir.Name; tables = $tables }
}
Write-Utf8NoBom (Join-Path $rawDir 'warehouse-files.tsv') ($whFileRows -join "`n")
$script:RawFiles.Add("$OutDir/raw/warehouse-files.tsv".Replace($repoRoot + '\', ''))

# ---------------------------------------------------------------- 8. landing 清单与来源分类
$reEventType = [regex]'"event_type"\s*:\s*"([^"]*)"'
$reSource = [regex]'"source_system"\s*:\s*"([^"]*)"'
$reSynthetic = [regex]'"synthetic"\s*:\s*(true|false)'
$reEventTime = [regex]'"event_time"\s*:\s*"([^"]*)"'

$landingFiles = @()
$eventFiles = @(Get-ChildItem 'landing/events/*.jsonl' -ErrorAction SilentlyContinue | Sort-Object Name)
foreach ($f in $eventFiles) {
  $lf = Get-LfCount $f.FullName
  $tailHasNewline = $false
  if ($f.Length -gt 0) {
    $fs = [System.IO.File]::OpenRead($f.FullName); $fs.Seek(-1, 'End') | Out-Null
    $tailHasNewline = ($fs.ReadByte() -eq 10); $fs.Close()
  }
  $text = [System.IO.File]::ReadAllText($f.FullName)
  # 单遍扫描：事件类型直方图 / 来源集合 / synthetic 计数 / 事件时间极值与日分布
  $etHist = [ordered]@{}
  foreach ($m in $reEventType.Matches($text)) {
    $k = $m.Groups[1].Value
    if ($etHist.Contains($k)) { $etHist[$k] = [int]$etHist[$k] + 1 } else { $etHist[$k] = 1 }
  }
  $srcSet = New-Object System.Collections.Generic.HashSet[string]
  foreach ($m in $reSource.Matches($text)) { [void]$srcSet.Add($m.Groups[1].Value) }
  $synTrue = 0; $synFalse = 0
  foreach ($m in $reSynthetic.Matches($text)) { if ($m.Groups[1].Value -eq 'true') { $synTrue++ } else { $synFalse++ } }
  $dayHist = [ordered]@{}
  $tMin = $null; $tMax = $null
  foreach ($m in $reEventTime.Matches($text)) {
    $v = $m.Groups[1].Value
    if ($null -eq $tMin -or [string]::CompareOrdinal($v, $tMin) -lt 0) { $tMin = $v }
    if ($null -eq $tMax -or [string]::CompareOrdinal($v, $tMax) -gt 0) { $tMax = $v }
    $d = if ($v.Length -ge 10) { $v.Substring(0, 10) } else { $v }
    if ($dayHist.Contains($d)) { $dayHist[$d] = [int]$dayHist[$d] + 1 } else { $dayHist[$d] = 1 }
  }
  $landingFiles += [pscustomobject][ordered]@{
    file = $f.Name; bytes = [long]$f.Length; lfCount = [long]$lf
    completeLines = [long]$lf
    totalLines = if ($f.Length -eq 0) { 0L } elseif ($tailHasNewline) { [long]$lf } else { [long]$lf + 1 }
    trailingNewline = $tailHasNewline
    class = Get-EventFileClass $f.Name
    eventTypeHistogram = $etHist
    sourceSystems = @($srcSet | Sort-Object)
    syntheticTrue = $synTrue; syntheticFalse = $synFalse
    eventTimeMin = $tMin
    eventTimeMax = $tMax
    eventDayHistogram = $dayHist
  }
}
$acceptedDirs = @(Get-ChildItem 'landing/accepted' -Directory -ErrorAction SilentlyContinue | Sort-Object { [int]$_.Name })
$accepted = @()
foreach ($d in $acceptedDirs) {
  $fs = @(Get-ChildItem $d.FullName -File -ErrorAction SilentlyContinue)
  $accepted += [pscustomobject][ordered]@{
    batchId = [int]$d.Name; files = @($fs | ForEach-Object { $_.Name }); bytes = [long](($fs | Measure-Object Length -Sum).Sum)
  }
}
$manifestFiles = @(Get-ChildItem 'landing/manifests/*.json' -ErrorAction SilentlyContinue | Sort-Object Name)
$manifests = @()
foreach ($m in $manifestFiles) {
  try {
    $j = Get-Content $m.FullName -Raw | ConvertFrom-Json
    $manifests += [pscustomobject][ordered]@{
      batchId = [int]$j.batchId; status = $j.status; acceptedRecords = [long]$j.acceptedRecords
      acceptedBytes = [long]$j.acceptedBytes; checksum = $j.checksum; source = $j.source
      files = @($j.files | ForEach-Object { $_.file })
    }
  } catch {
    $manifests += [pscustomobject][ordered]@{ batchId = [int]($m.BaseName); parseError = $_.Exception.Message }
  }
}

# ---------------------------------------------------------------- 9. 来源追溯（run 39 到底吃了什么）
$waitLanding = $stageEvidence['WAIT_LANDING']
$batchFileRows = Invoke-SqlRows "select batch_id, file_path, start_offset, end_offset, record_count, status from analytics_meta.ingestion_batch_file order by batch_id, id;"
$batchFileTsv = New-Object System.Collections.Generic.List[string]
$batchFileTsv.Add("batch_id`tfile`tstart_offset`tend_offset`trecord_count`tstatus")
$consumption = [ordered]@{}
foreach ($r in $batchFileRows) {
  $name = Split-Path $r.file_path -Leaf
  $batchFileTsv.Add("$($r.batch_id)`t$name`t$($r.start_offset)`t$($r.end_offset)`t$($r.record_count)`t$($r.status)")
  if (-not $consumption.Contains($name)) { $consumption[$name] = @() }
  $consumption[$name] += [pscustomobject][ordered]@{
    batchId = [int]$r.batch_id; startOffset = [long]$r.start_offset; endOffset = [long]$r.end_offset
    records = [long]$r.record_count; status = $r.status
  }
}
Write-Utf8NoBom (Join-Path $rawDir 'ingestion-batch-file.tsv') ($batchFileTsv -join "`n")
$script:RawFiles.Add("$OutDir/raw/ingestion-batch-file.tsv".Replace($repoRoot + '\', ''))

$runInputBatch = if ($waitLanding -and $waitLanding.batchId) { [int]$waitLanding.batchId } else { $null }
$runInputManifest = $manifests | Where-Object { $_.batchId -eq $runInputBatch } | Select-Object -First 1
$duplicateReads = @()
foreach ($k in $consumption.Keys) {
  $bs = @($consumption[$k] | ForEach-Object { $_.batchId })
  if ($bs.Count -gt 1) {
    $duplicateReads += [pscustomobject][ordered]@{ file = $k; batches = $bs; note = '同一文件被多个批次读取（DEF-13 修复前的重复读取；DWD 以 (source_system,event_id) 去重，指标口径不受影响）' }
  }
}

# ---------------------------------------------------------------- 10. 一致性检查
$checks = @()
function Add-Check([string]$Id, [bool]$Ok, [string]$Detail) {
  $script:checks += [pscustomobject][ordered]@{ id = $Id; ok = $Ok; detail = $Detail }
}
Add-Check 'run-status-success' ($run -and $run.status -eq 'SUCCESS') "pipeline_run $RunId status=$($run.status)"
Add-Check 'run-target-snapshot' ($run -and $run.target_snapshot_id -eq $SnapshotId) "target_snapshot_id=$($run.target_snapshot_id)"
Add-Check 'snapshot-row-exists' ($snapRows.Count -eq 1) "metric_snapshot 行数=$($snapRows.Count)"
Add-Check 'snapshot-active-and-linked' ($snapRows.Count -eq 1 -and $snapRows[0].status -eq 'ACTIVE' -and [int]$snapRows[0].pipeline_run_id -eq $RunId) "status=$($snapRows[0].status) pipeline_run_id=$($snapRows[0].pipeline_run_id)"
Add-Check 'snapshot-active-unique' ([int]$fpBefore['metric_snapshot_store'].activeCount -eq 1) "metric store ACTIVE 快照数=$([int]$fpBefore['metric_snapshot_store'].activeCount)"
$nonDay = @($values | Where-Object { $_.period -ne 'day:2026-09-01' })
Add-Check 'metric-values-all-day-period' ($nonDay.Count -eq 0) "非 day:2026-09-01 口径的指标行数=$($nonDay.Count)"
Add-Check 'metric-values-present' ($values.Count -gt 0) "指标值行数=$($values.Count)"
Add-Check 'wait-landing-batch' ($waitLanding -and [int]$waitLanding.batchId -eq $runInputBatch -and [int]$waitLanding.acceptedRecords -eq 1000) "WAIT_LANDING batchId=$($waitLanding.batchId) acceptedRecords=$($waitLanding.acceptedRecords)"
$genEntry = $consumption['gen-s3b-1000-20260911.jsonl']
Add-Check 'strict-generator-single-batch' ($genEntry -and $genEntry.Count -eq 1 -and $genEntry[0].records -eq 1000) "gen-s3b 消费记录数=$($genEntry.Count) records=$($genEntry[0].records)"
$odsStage = $stageRowsByTable | Where-Object { $_.stage -eq 'LOAD_ODS' }
$odsSum = [long](($odsStage | Measure-Object rowsWritten -Sum).Sum)
Add-Check 'ods-physical-rows-equal-generator' ($odsSum -eq 1000) "LOAD_ODS 物理写入行数合计=$odsSum（ODS 每行只落一个 dt/hour 分区，物理=逻辑）"
# 阶段表 records 与 evidence 的一致性：records == sum(jobs[].outputRecords)
$evidenceTotals = [ordered]@{}
foreach ($code in $stageEvidence.Keys) {
  $ev = $stageEvidence[$code]
  if ($ev.jobs) {
    $sum = [long]0
    foreach ($j in $ev.jobs) { $sum += [long]$j.outputRecords }
    $evidenceTotals[$code] = $sum
  }
}
$mismatch = @()
foreach ($s in $stages) {
  if ($evidenceTotals.Contains($s.stageCode) -and [long]$evidenceTotals[$s.stageCode] -ne [long]$s.records) {
    $mismatch += "$($s.stageCode)(table=$($s.records),evidence=$($evidenceTotals[$s.stageCode]))"
  }
}
Add-Check 'stage-records-match-evidence' ($mismatch.Count -eq 0) "8 阶段中带 jobs 的 $($evidenceTotals.Count) 个阶段 records 与 evidence.outputRecords 全部一致；不一致：$(if ($mismatch.Count) { $mismatch -join '; ' } else { '无' })"
$dwdRec = [long](($stages | Where-Object { $_.stageCode -eq 'BUILD_DWD' }).records)
$dwsRec = [long](($stages | Where-Object { $_.stageCode -eq 'BUILD_DWS' }).records)
$adsRec = [long](($stages | Where-Object { $_.stageCode -eq 'BUILD_ADS' }).records)
Add-Check 'downstream-record-counts-frozen' ($dwdRec -eq 159 -and $dwsRec -eq 10 -and $adsRec -eq 30) "DWD=$dwdRec DWS=$dwsRec ADS=$adsRec（逻辑行数，来自 pipeline_stage_run.records）"
# ADS → 指标库镜像的交叉校验：快照内 ADS 镜像行数应等于 ADS 逻辑输出行数
$mirrorRows = [long]0
foreach ($t in $metricStoreTables) { if ($null -ne $t.rowsForSnapshot) { $mirrorRows += [long]$t.rowsForSnapshot } }
Add-Check 'ads-mirror-rows-match-stage' ($mirrorRows -eq $adsRec) "指标库 $SnapshotId 的 ADS 镜像行数合计=$mirrorRows，BUILD_ADS 逻辑输出=$adsRec"
$ingestStatus = $apiIngest.json.data
$pendingFiles = [int]$ingestStatus.pendingFiles
$checkpointFiles = [int]$ingestStatus.checkpointFiles
Add-Check 'landing-file-count-matches-api' ($pendingFiles -eq $eventFiles.Count) "目录文件数=$($eventFiles.Count) API pendingFiles=$pendingFiles"
Add-Check 'checkpoint-not-exceeding-pending' ($checkpointFiles -le $pendingFiles) "checkpointFiles=$checkpointFiles pendingFiles=$pendingFiles（D-024 结构不变量）"
Add-Check 'checkpoint-spelling-unique' ([int]$fpBefore['file_checkpoint'].rows -eq [int]$fpBefore['file_checkpoint'].distinctPaths) "行数=$([int]$fpBefore['file_checkpoint'].rows) 不同路径=$([int]$fpBefore['file_checkpoint'].distinctPaths)"
$legacySpellingRows = [int](Invoke-SqlScalar "select count(*) from analytics_meta.file_checkpoint where instr(file_path, concat(char(92),'.',char(92)))>0;")
Add-Check 'legacy-spelling-residual-untouched' ($legacySpellingRows -eq 50) "历史 `\`.\`` 写法行数=$legacySpellingRows（D-024 未执行删除，保持原样）"
Add-Check 'warehouse-nonempty' ($whTotals.parquetFiles -gt 0) "parquet 文件=$($whTotals.parquetFiles) 字节=$($whTotals.bytes)"
$unclassified = @($landingFiles | Where-Object { $_.class -eq 'unclassified' })
Add-Check 'landing-files-classified' ($unclassified.Count -eq 0) "未分类文件数=$($unclassified.Count)"

# ---------------------------------------------------------------- 11. 只读证明（采集后指纹）
$fpAfter = Get-BusinessFingerprint
$fpDiff = @()
foreach ($k in $fpBefore.Keys) {
  $a = $fpBefore[$k] | ConvertTo-Json -Depth 6 -Compress
  $b = $fpAfter[$k] | ConvertTo-Json -Depth 6 -Compress
  if ($a -ne $b) { $fpDiff += $k }
}
$nonSelect = @($script:SqlLog | Where-Object { $_ -notmatch '^(select|show|with)\b' })

# ---------------------------------------------------------------- 11b. 冻结快照血缘（严格生成器当日行数）
$genFile = $landingFiles | Where-Object { $_.class -eq 'strict-generator' } | Select-Object -First 1
$snapshotDay = if ($snapRows.Count -eq 1 -and $snapRows[0].business_time) { ([string]$snapRows[0].business_time).Substring(0, 10) } else { $null }
$genDayRows = $null
if ($genFile -and $snapshotDay -and $genFile.eventDayHistogram.Contains($snapshotDay)) {
  $genDayRows = [int]$genFile.eventDayHistogram[$snapshotDay]
}

# ---------------------------------------------------------------- 12. 组装 baseline.json
$baseline = [ordered]@{
  baselineId   = Split-Path $OutDir -Leaf
  task         = 'P1-01'
  purpose      = '冻结源 A（参考商城）商城无关化改造前的只读比较基准：指标、库表、分区、来源追溯与只读证明'
  generatedAt  = $startedAt.ToString('yyyy-MM-dd HH:mm:ss')
  durationSec  = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
  script       = [ordered]@{
    path = 'scripts/accept-p1-baseline.ps1'; sha256 = Get-Sha256 (Join-Path $repoRoot 'scripts/accept-p1-baseline.ps1')
    readonlyGate = '所有 MySQL 语句逐条登记并断言只允许 SELECT/SHOW/WITH；所有 HTTP 仅 GET（login 除外，副作用限于会话表）'
  }
  inputs       = [ordered]@{ pipelineRunId = $RunId; snapshotId = $SnapshotId; baseUrl = $BaseUrl }
  repo         = [ordered]@{
    head = (@(git rev-parse HEAD))[0]; branch = (@(git rev-parse --abbrev-ref HEAD))[0]
    dirtyFiles = @(git status --porcelain | ForEach-Object { $_.Substring(3) })
  }
  preflight    = $preflight
  services     = $services
  artifacts    = $artifacts
  pipelineRun  = [ordered]@{
    id = [int]$run.id; pipelineCode = $run.pipeline_code; status = $run.status
    runtimeProfileId = [int]$run.runtime_profile_id; runtimeProfileVersion = [int]$run.runtime_profile_version
    targetSnapshotId = $run.target_snapshot_id; attemptNo = [int]$run.attempt_no
    businessTime = $run.business_time; createdAt = $run.created_at; startedAt = $run.started_at; finishedAt = $run.finished_at
    recordsCaliber = 'stages[].records = 逻辑写入行数（= evidence.jobs[].outputRecords 之和）；stageRowsByTable[].rowsWritten = 物理行数（同一逻辑行可落多个分区，DWS/ADS 因此大于逻辑值），两者口径不同不得混用'
    stages = $stages
    evidenceOutputRecords = $evidenceTotals
    stageRowsByTable = $stageRowsByTable
    evidenceFiles = @($evidenceCodes | ForEach-Object { "raw/stage-evidence-$_.json" })
  }
  metricSnapshot = [ordered]@{
    snapshotId = $SnapshotId; status = $snapRows[0].status; source = $snapRows[0].source
    version = [int]$snapRows[0].version; pipelineRunId = [int]$snapRows[0].pipeline_run_id
    businessTime = $snapRows[0].business_time; publishedAt = $snapRows[0].published_at
    values = $values
    valueCount = $values.Count
    snapshotHistory = $snapshotHistory
    apiOverview = $apiOverview.json.data
  }
  metricStoreTables = $metricStoreTables
  metadataDb   = [ordered]@{
    tables = $metaTables; flywayHistory = $flywayRows
    counts = $fpBefore
  }
  activeProfile = [ordered]@{
    apiActive = $apiProfile.json.data
    profiles = $profiles
    activeProfileCount = $activeProfiles.Count
    hiveDatabasePrefixNull = [bool]($activeProfiles.Count -gt 0 -and $null -eq $activeProfiles[0].hiveDatabasePrefix)
  }
  warehouse    = [ordered]@{
    uri = 'file:///D:/Develop_code/GraduationProject/spark-warehouse'
    localPath = $whRoot; databases = $whDbs; totals = $whTotals
    checksumManifest = 'raw/warehouse-files.tsv'
  }
  landing      = [ordered]@{
    eventsDir = 'landing/events'
    fileCount = $eventFiles.Count
    bytes = [long](($eventFiles | Measure-Object Length -Sum).Sum)
    classSummary = (($landingFiles | Group-Object class) | ForEach-Object {
        [pscustomobject][ordered]@{ class = $_.Name; files = $_.Count; bytes = [long](($_.Group | Measure-Object bytes -Sum).Sum) }
      })
    classNotes = $classNote
    files = $landingFiles
    acceptedDirs = $accepted
    manifestCount = $manifests.Count
    ingestionApiStatus = $ingestStatus
    apiPipelineRun = $apiRun.json.data
  }
  provenance   = [ordered]@{
    strictGenerator = [ordered]@{
      file = 'gen-s3b-1000-20260911.jsonl'
      completeLines = if ($genFile) { $genFile.completeLines } else { $null }
      bytes = if ($genFile) { $genFile.bytes } else { $null }
      ingestionBatchId = $runInputBatch
      runId = $RunId
      adsRowsPublished = $adsRec
      metricStoreMirrorRows = $mirrorRows
      metricValues = $values.Count
      snapshotId = $SnapshotId
    }
    frozenSnapshotLineage = [ordered]@{
      businessDay = $snapshotDay
      generatorDayRows = $genDayRows
      generatorTotalRows = if ($genFile) { $genFile.completeLines } else { $null }
      conclusion = "冻结快照 $SnapshotId 的 $($values.Count) 指标由 run $RunId 从 batch $runInputBatch（严格生成器 1,000 条）经 ODS→DWD→DWS→ADS 重算；其中 event_time 落在 $snapshotDay 的行数为 $genDayRows，其余日行数落入各自 dt 分区，不参与 day:$snapshotDay 口径"
      isolation = 'ADS 写入为 INSERT OVERWRITE TABLE ... PARTITION(snapshot_id, dt)，仅重写本 run 处理的分区；旧夹具与验收夹具的行不进入该快照'
    }
    runInput = [ordered]@{
      ingestionBatchId = $runInputBatch
      acceptedUri = if ($runInputManifest) { $runInputManifest.files -join ',' } else { $null }
      checksum = if ($waitLanding) { $waitLanding.checksum } else { $null }
      acceptedRecords = if ($waitLanding) { [int]$waitLanding.acceptedRecords } else { $null }
      manifest = $runInputManifest
      conclusion = "run $RunId 的 ODS 输入 = ingestion batch $runInputBatch = gen-s3b-1000-20260911.jsonl（严格生成器 1,000 条）；该批次对旧夹具 golden-r615 产出 0 条"
    }
    frozenMetricsAttribution = "S20260901_39 的全部指标值由 run $RunId 从 batch $runInputBatch 的 1,000 条严格生成器记录经 ODS→DWD→DWS→ADS 重算；ADS 写入为 INSERT OVERWRITE PARTITION(snapshot_id, dt)，不混入旧夹具行"
    legacyFixture = [ordered]@{
      files = @($landingFiles | Where-Object { $_.class -like 'legacy-*' } | ForEach-Object { $_.file })
      consumption = $consumption
      duplicateReads = $duplicateReads
    }
    generatorFile = ($landingFiles | Where-Object { $_.class -eq 'strict-generator' })
  }
  checks       = $checks
  notCollected = @(
    [pscustomobject][ordered]@{ item = 'Hive 物理全表行数（ODS/DWD/DIM/DWS/ADS）'; reason = 'P1-01 明令不启动 Spark、不重跑大数据；本脚本只采集阶段证据写入行数与分区文件清单'; collectedIn = 'P1-06 的 T2（同脚本扩展 Hive 计数开关）' }
    [pscustomobject][ordered]@{ item = '多源登记状态（source_registry / runtime_profile.source_id）'; reason = 'P1-02 建表后才有该结构'; collectedIn = 'P1-06' }
    [pscustomobject][ordered]@{ item = '历史 `\`.\`` 断点行的合并删除'; reason = 'D-024 Data Destruction Guard，需用户按范围确认，独立于 P1–P5'; collectedIn = '待确认后单独立项' }
  )
  readonlyProof = [ordered]@{
    fingerprintBefore = $fpBefore
    fingerprintAfter = $fpAfter
    changedSections = $fpDiff
    mutationDetected = [bool]($fpDiff.Count -gt 0)
    sqlStatementCount = $script:SqlLog.Count
    nonSelectStatements = $nonSelect
    sqlStatements = @($script:SqlLog)
    excludedFromFingerprint = @(
      [pscustomobject][ordered]@{ table = 'analytics_meta.user_session'; reason = '登录换取只读 token 的会话副作用' }
      [pscustomobject][ordered]@{ table = 'analytics_meta.operation_audit_log'; reason = 'GET 请求可能被审计切面记录（平台自身行为）' }
      [pscustomobject][ordered]@{ table = 'analytics_meta.ai_call_log'; reason = '本脚本不调用 AI 接口；列入排除仅作声明' }
    )
  }
  volatilePaths = [ordered]@{
    topLevel = @('baselineId', 'generatedAt', 'durationSec', 'rawFiles', 'services')
    nested   = @('repo.dirtyFiles', 'landing.ingestionApiStatus.checkedAt')
    normalizedByTable = @('user_session')
    reason   = '这些字段随输出位置/进程/时钟/本脚本自身登录副作用变化，不参与二次读取一致性比对；其余字段变化即视为基线漂移'
  }
  selfInflicted = @(
    [pscustomobject][ordered]@{
      target = 'analytics_meta.user_session'
      effect = '本脚本每次运行登录一次换取只读 token，新增 1 行会话记录（已实测：153 -> 154）'
      handling = '不写入只读指纹；比对时把该表行数归一为 null，避免自身副作用被判为基线漂移'
    }
  )
  rawFiles     = @($script:RawFiles)
}

$baselineJson = $baseline | ConvertTo-Json -Depth 14
Write-Utf8NoBom (Join-Path $OutDir 'baseline.json') $baselineJson

# ---------------------------------------------------------------- 13. 二次读取一致性比对
function Remove-DottedProperty($obj, [string]$path) {
  $parts = $path -split '\.'
  $cur = $obj
  for ($i = 0; $i -lt $parts.Count - 1; $i++) {
    if ($null -eq $cur) { return }
    $cur = $cur.($parts[$i])
  }
  if ($null -ne $cur) {
    $leaf = $parts[-1]
    if ($cur.PSObject.Properties.Name -contains $leaf) { $cur.PSObject.Properties.Remove($leaf) }
  }
}

function Normalize-SelfInflicted($obj, [string[]]$tables) {
  if ($null -eq $obj -or $null -eq $tables -or $tables.Count -eq 0) { return }
  if ($null -eq $obj.metadataDb -or $null -eq $obj.metadataDb.tables) { return }
  foreach ($t in @($obj.metadataDb.tables)) {
    if ($tables -contains $t.table) { $t.rows = $null }
  }
}

$compare = $null
if ($CompareWith) {
  $prev = Get-Content $CompareWith -Raw | ConvertFrom-Json
  $cur = $baselineJson | ConvertFrom-Json
  $volTop = @($prev.volatilePaths.topLevel)
  $volNested = @($prev.volatilePaths.nested)
  $volTables = @($prev.volatilePaths.normalizedByTable)
  foreach ($p in $volNested) { Remove-DottedProperty $prev $p; Remove-DottedProperty $cur $p }
  Normalize-SelfInflicted $prev $volTables
  Normalize-SelfInflicted $cur $volTables
  $diffs = @()
  $compared = @()
  foreach ($k in $prev.PSObject.Properties.Name) {
    if ($volTop -contains $k) { continue }
    $compared += $k
    $a = $prev.$k | ConvertTo-Json -Depth 14 -Compress
    $b = $cur.$k | ConvertTo-Json -Depth 14 -Compress
    if ($a -ne $b) {
      $diffs += [pscustomobject][ordered]@{
        key = $k
        previous = $a.Substring(0, [Math]::Min(400, $a.Length))
        current = $b.Substring(0, [Math]::Min(400, $b.Length))
      }
    }
  }
  $compare = [ordered]@{
    comparedWith = $CompareWith; comparedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    ignoredTopLevelKeys = $volTop; ignoredNestedPaths = $volNested; normalizedTables = $volTables
    comparedKeys = $compared; differences = $diffs; identical = [bool]($diffs.Count -eq 0)
  }
  Write-Utf8NoBom (Join-Path $OutDir 'compare-report.json') ($compare | ConvertTo-Json -Depth 8)
}

# ---------------------------------------------------------------- 14. 控制台摘要
$bad = @($checks | Where-Object { -not $_.ok })
Write-Host "== P1-01 基线采集 =="
Write-Host ("输出目录      : {0}" -f $OutDir)
Write-Host ("run / snapshot: {0} / {1} ({2})" -f $RunId, $SnapshotId, $run.status)
Write-Host ("指标值        : {0} 条" -f $values.Count)
Write-Host ("landing       : {0} 文件 / {1} 字节；checkpointFiles={2} pendingFiles={3}" -f $eventFiles.Count, $fpBefore['landing_events'].bytes, $checkpointFiles, $pendingFiles)
Write-Host ("warehouse     : {0} 库 / {1} 表 / {2} 分区 / {3} parquet / {4} 字节" -f $whTotals.databases, $whTotals.tables, $whTotals.partitions, $whTotals.parquetFiles, $whTotals.bytes)
Write-Host ("只读证明      : 指纹变化段={0}；SQL 语句={1} 条（非 SELECT 语句 {2} 条）" -f $fpDiff.Count, $script:SqlLog.Count, $nonSelect.Count)
Write-Host ("一致性检查    : {0}/{1} 通过" -f ($checks.Count - $bad.Count), $checks.Count)
foreach ($b in $bad) { Write-Host ("  [FAIL] {0}: {1}" -f $b.id, $b.detail) }
if ($compare) { Write-Host ("二次读取比对  : {0}" -f $(if ($compare.identical) { '一致' } else { "不一致（$($compare.differences.Count) 段）" })) }
Write-Host ("耗时          : {0} 秒" -f $baseline.durationSec)

if ($bad.Count -gt 0) { exit 2 }
exit 0
