# E3 真链路取证（P2-01 / ODS v2 加法扩列）
#
# 目的：用**真** spark-submit + **真** Hive metastore(嵌入式 Derby) + **真** 落地区文件 + **真** spark-sql 读回，
#       证明 ① 5 个新列被写出且取值正确；② payload_json 与源行片段逐字节相同（独立 oracle）；
#       ③ v1 公共列/取值不受影响（加法双写）。
#
# 隔离纪律（硬约束，脚本内逐条断言）：
#   - 库名前缀 p201v2 / p201v2b（新建库），**绝不**使用 dw / dw_* 前缀；
#   - warehouse 与 Derby metastore 全在 spark-jobs\target\p2-01-e3\<runId>\ 下（本模块范围内）；
#   - 每次运行 use 新 runId 目录 ⇒ 不覆盖、不删除任何既有文件（本波次禁止删除文件）；
#   - 不触碰真实 D:\Develop_code\GraduationProject\spark-warehouse（脚本前后取指纹对比）。
#
# 用法：pwsh -NoProfile -File docs\acceptance\p2-01-ods-v2-20260912\harness\e3-real-chain.ps1

$ErrorActionPreference = 'Stop'

$repo        = 'D:\Develop_code\GraduationProject'
$runId       = Get-Date -Format 'yyyyMMdd-HHmmss'
$e3Root      = Join-Path $repo 'spark-jobs\target\p2-01-e3'
$work        = Join-Path $e3Root $runId
$warehouse   = Join-Path $work 'warehouse'
$derby       = Join-Path $work 'derby-metastore'
$classes     = Join-Path $repo 'spark-jobs\target\classes'
$builtDir    = Join-Path $repo 'spark-jobs\target\p2-01-built'
$jar         = Join-Path $builtDir 'spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar'
$eviDir      = Join-Path $repo 'docs\acceptance\p2-01-ods-v2-20260912\evidence'
$sqlFile     = Join-Path $repo 'docs\acceptance\p2-01-ods-v2-20260912\harness\e3-readback.sql'

$sparkSubmit = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd'
$sparkSql    = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd'
$jarTool     = 'D:\Develop\JAVA17\bin\jar.exe'

$landing     = 'file:///D:/Develop_code/GraduationProject/tests/golden-dataset/events'
$goldenFile  = Join-Path $repo 'tests\golden-dataset\events\golden-20260901.jsonl'

$prefixA     = 'p201v2'      # 主链路：注入值 = 行内值（mock-mall）
$prefixB     = 'p201v2b'     # 判别探针：注入值与行内值**故意不同**，用来证明两个通道互不污染
$srcA        = 'mock-mall'
$srcB        = 'probe-inj-2026'
$batchId     = '2026091201'
$bizDate     = '20260901'

function Say($m) { Write-Host "[e3] $m" }

# ── 0) 隔离断言（写路径必须落在本模块 target 下；绝不允许 dw_ 前缀）────────────────
$expectedUnder = (Join-Path $repo 'spark-jobs\target\p2-01-e3')
if (-not ($work.StartsWith($expectedUnder))) { throw "隔离断言失败：work=$work 不在 $expectedUnder 下" }
if ($prefixA -match '^dw' -or $prefixB -match '^dw') { throw "隔离断言失败：库名前缀不得以 dw 开头" }
if ($prefixA -eq $prefixB) { throw "隔离断言失败：两个前缀必须不同" }

New-Item -ItemType Directory -Force -Path $work, $builtDir | Out-Null
# 注意（实测教训）：**绝不**预建 $derby / $warehouse 目录。Derby `create=true` 若发现同名目录已存在
# 且不是合法 Derby 库，会直接 `ERROR XBM0J: Directory ... already exists.` 拒绝建库，表现为
# `Unable to instantiate ... SessionHiveMetaStoreClient`；本轮第 1 次 E3 运行即因此失败
# （原始日志：evidence/e3-20260912-135558-01-sci-p201v2.log）。只建父目录，其余交给 Derby/Spark 自建。
Say "runId=$runId"
Say "work=$work"

$whUri    = 'file:///' + $warehouse.Replace('\', '/')
$derbyUri = 'jdbc:derby:' + $derby.Replace('\', '/') + ';create=true'
Say "warehouseUri=$whUri"
Say "derbyUri=$derbyUri"

# ── 0b) 真实 spark-warehouse 指纹（前后对比；本脚本不应改动它）─────────────────────
$realWh = Join-Path $repo 'spark-warehouse'
function WhFingerprint($p) {
  if (-not (Test-Path $p)) { return 'ABSENT' }
  $items = Get-ChildItem $p -Recurse -File | Sort-Object FullName |
    ForEach-Object { "$($_.FullName)|$($_.Length)" }
  $joined = ($items -join "`n")
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($joined)
  return 'FILES=' + $items.Count + ' SHA256=' + ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLower()
}
$whBefore = WhFingerprint $realWh
Say "realWarehouseBefore: $whBefore"

# ── 1) 用当前 target\classes 打一个**独立路径**的 jar ─────────────────────────────
# 为什么不用 mvn package：模块 target 下的 spark-jobs-0.1.0-SNAPSHOT.jar 是在产平台读取的活产物，
# 本任务只允许在 spark-jobs/** 内动手，但不应把这个活产物换成取证专用的构建；pom 是纯 maven-jar-plugin
# （无 shade/assembly），所以由 target\classes 直接打包与 mvn package 的类内容等价。
& $jarTool cf $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "打包失败：jar cf 退出码 $LASTEXITCODE" }
$jarItem = Get-Item $jar
$jarSha = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()
Say "jar=$jar bytes=$($jarItem.Length) sha256=$jarSha"
$classCount = (Get-ChildItem $classes -Recurse -Filter '*.class').Count
# classes 目录的内容指纹（jar 的 zip 头含时间戳 ⇒ jar 字节不可复现；类内容才是有意义的指纹）
$clsLines = Get-ChildItem $classes -Recurse -File | Sort-Object FullName | ForEach-Object {
  "{0}|{1}|{2}" -f $_.FullName.Substring($classes.Length + 1), $_.Length, (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
}
$clsSha = [BitConverter]::ToString(
  [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes($clsLines -join "`n"))).Replace('-', '').ToLower()
Say "classes=$classes files=$((Get-ChildItem $classes -Recurse -File).Count) classFiles=$classCount contentSha256=$clsSha"

# ── 2) 黄金夹具指纹（独立于测试框架：这里手工数行数/算哈希）───────────────────────
$goldenText = [System.IO.File]::ReadAllText($goldenFile, [System.Text.Encoding]::UTF8)
$goldenLines = ($goldenText -split "`n") | ForEach-Object { $_.TrimEnd("`r") } | Where-Object { $_ -ne '' }
$goldenSha = (Get-FileHash $goldenFile -Algorithm SHA256).Hash.ToLower()
Say "goldenFile=$goldenFile bytes=$((Get-Item $goldenFile).Length) lines=$($goldenLines.Count) sha256=$goldenSha"

# ── 3) spark-submit 公共参数 ─────────────────────────────────────────────────────
function RunJob($tag, $prefix, $jobCode, $sourceSystem, $outFile) {
  $sparkArgs = @(
    '--master', 'local[2]',
    '--class', 'com.graduation.analytics.job.JobRunner',
    '--conf', "spark.hadoop.javax.jdo.option.ConnectionURL=$derbyUri",
    '--conf', 'spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver',
    '--conf', 'spark.sql.hive.metastore.jars=builtin',
    '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
    '--conf', "spark.sql.warehouse.dir=$whUri",
    $jar,
    '--runtimeProfileId=7', "--jobCode=$jobCode", "--businessDate=$bizDate", '--attemptNo=1',
    "--hiveDatabasePrefix=$prefix"
  )
  if ($jobCode -eq 'odl') {
    $sparkArgs += @("--landingDir=$landing", "--sourceSystem=$sourceSystem", "--batchId=$batchId")
  }
  $cmdLine = 'spark-submit ' + ($sparkArgs -join ' ')
  Say "[$tag] $cmdLine"
  $out = & $sparkSubmit @sparkArgs 2>&1 | Out-String
  $out | Set-Content -Path $outFile -Encoding UTF8
  $json = ($out -split "`n" | Where-Object { $_ -match "`"jobCode`":`"$jobCode`"" } | Select-Object -Last 1)
  Say "[$tag] exit=$LASTEXITCODE result=$($json.Trim())"
  return @{ Exit = $LASTEXITCODE; Json = $json.Trim(); Log = $outFile }
}

$r1 = RunJob 'sci-A' $prefixA 'sci' '' (Join-Path $eviDir "e3-$runId-01-sci-$prefixA.log")
if ($r1.Exit -ne 0) { throw "sci($prefixA) 失败，见 $($r1.Log)" }
Start-Sleep -Seconds 3   # Derby 嵌入式锁释放

$r2 = RunJob 'odl-A' $prefixA 'odl' $srcA (Join-Path $eviDir "e3-$runId-02-odl-$prefixA.log")
if ($r2.Exit -ne 0) { throw "odl($prefixA) 失败，见 $($r2.Log)" }
Start-Sleep -Seconds 3

$r3 = RunJob 'sci-B' $prefixB 'sci' '' (Join-Path $eviDir "e3-$runId-03-sci-$prefixB.log")
if ($r3.Exit -ne 0) { throw "sci($prefixB) 失败，见 $($r3.Log)" }
Start-Sleep -Seconds 3

$r4 = RunJob 'odl-B' $prefixB 'odl' $srcB (Join-Path $eviDir "e3-$runId-04-odl-$prefixB.log")
if ($r4.Exit -ne 0) { throw "odl($prefixB) 失败，见 $($r4.Log)" }
Start-Sleep -Seconds 3

# ── 4) 真 spark-sql 读回 ─────────────────────────────────────────────────────────
$sqlLog = Join-Path $eviDir "e3-$runId-05-readback.sql.log"
$sqlArgs = @(
  '--master', 'local[2]',
  '--conf', "spark.hadoop.javax.jdo.option.ConnectionURL=$derbyUri",
  '--conf', 'spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver',
  '--conf', 'spark.sql.hive.metastore.jars=builtin',
  '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
  '--conf', "spark.sql.warehouse.dir=$whUri",
  '--conf', 'spark.sql.cli.print.header=true',
  '-f', $sqlFile
)
Say "[readback] spark-sql -f $sqlFile"
$sqlOut = & $sparkSql @sqlArgs 2>&1 | Out-String
$sqlOut | Set-Content -Path $sqlLog -Encoding UTF8
Say "[readback] exit=$LASTEXITCODE log=$sqlLog"

# ── 5) 解析 chk/value 表 + SHOW/DESCRIBE 结构输出 ────────────────────────────────
# 实测输出格式（别再按 `| a | b |` 解析——那样一条都读不到，已踩过）：
#   spark-sql 非交互输出是 **TAB 分隔**、且因 spark.sql.cli.print.header=true 先打一行**表头**
#   （`chk<TAB>value`）。检查键一律以**大写字母**开头，据此把 DESCRIBE/SAMPLE 的小写表头
#   （col_name / event_id / namespace …）挡在外面。
$checks = [ordered]@{}
foreach ($line in ($sqlOut -split "`r?`n")) {
  if ($line -cmatch '^([A-Z][A-Za-z0-9_]*)\t(.*)$') {
    $k = $Matches[1]; $v = $Matches[2].Trim()
    if ($k -ne 'CHK' -and $k -ne 'MARKER') { $checks[$k] = $v }
  }
}
Say "parsed chk rows: $($checks.Count)"

$odsTables = @(); $databases = @(); $descCols = @()
$mode = ''
foreach ($line in ($sqlOut -split "`r?`n")) {
  if     ($line -match 'SHOW_TABLES_BEGIN')     { $mode = 'tables';  continue }
  elseif ($line -match 'SHOW_TABLES_END')       { $mode = '';        continue }
  elseif ($line -match 'SHOW_DATABASES_BEGIN')  { $mode = 'dbs';     continue }
  elseif ($line -match 'SHOW_DATABASES_END')    { $mode = '';        continue }
  elseif ($line -match 'DESCRIBE_RESULT_BEGIN') { $mode = 'desc';    continue }
  elseif ($line -match 'DESCRIBE_RESULT_END')   { $mode = '';        continue }
  if ($mode -eq '' -or $line -match '^Time taken') { continue }
  $cells = @($line -split "`t")
  $f1 = ($cells[0] -replace '\s+$', '')
  # 结束标记前一行是下一句 SELECT 的**表头**（`marker`），必须挡掉，否则会混进库名/列名
  if ($f1 -in @('marker', 'chk', '')) { continue }
  if ($mode -eq 'tables') {
    # SHOW TABLES 的行可能只有表名一格（namespace/isTemporary 为空），也可能是三格
    $tbl = if ($cells.Count -ge 2) { $cells[1].Trim() } else { $f1 }
    if ($tbl -match '^ods_') { $odsTables += $tbl }
  } elseif ($mode -eq 'dbs') {
    if ($f1 -and $f1 -ne 'namespace') { $databases += $f1 }
  } elseif ($mode -eq 'desc') {
    # DESCRIBE 会先把分区列 dt/hour 列在数据列后面，再打 `# Partition Information`
    # 并**重复**列一遍 dt/hour。碰到这个分区段标记就停收，否则列序尾部会多出 dt,hour ✗
    if ($f1 -match '^#\s*Partition Information') { $mode = 'desc_done'; continue }
    if ($f1 -and $f1 -notmatch '^#' -and $f1 -ne 'col_name') {
      $descCols += ,@($f1, $(if ($cells.Count -ge 2) { $cells[1].Trim() } else { '' }))
    }
  }
}
$checks['A2_ods_table_count']  = "$($odsTables.Count)"
$checks['A3_describe_newcols'] = (@($descCols | ForEach-Object { $_[0] } | Select-Object -Skip 14 -First 5) -join ',')
$checks['E0_databases']        = ($databases -join '|')
$checks['E1_dw_databases']     = "$(@($databases | Where-Object { $_ -match '^dw' }).Count)"
$checks['E2_prefix_databases'] = "$(@($databases | Where-Object { $_ -match "^($prefixA|$prefixB)_" }).Count)"
$checks['E3_other_databases']  = "$(@($databases | Where-Object { $_ -ne 'default' -and $_ -notmatch "^($prefixA|$prefixB)_" }).Count)"
Say "odsTables=$($odsTables -join ',')"
Say "databases=$($checks['E0_databases'])"
Say "describeCols=$(@($descCols | ForEach-Object { $_[0] }) -join ',')"

# ── 6) 断言 ─────────────────────────────────────────────────────────────────────
$expectedUserCols = @(
  'event_id','event_type','event_time','ingest_time','source_system','schema_version','trace_id',
  'payload_user_id','payload_age_group','payload_city_level','payload_member_level','payload_register_time',
  'source_file','ingest_batch_id',
  'raw_event_type','raw_source_system','landing_file','payload_json','payload_hash',
  'dt','hour')
$descNames = @($descCols | ForEach-Object { $_[0] })
$descTypes = @($descCols | ForEach-Object { $_[1] })

$results = [System.Collections.Generic.List[string]]::new()
function Check($name, $ok, $detail) {
  $script:results.Add("$name`t$([int][bool]$ok)`t$detail")
  Say ("{0} {1} :: {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
  return [bool]$ok
}

$allOk = $true
$allOk = (Check 'E3_00_jar_built'          (Test-Path $jar) "jar=$jar bytes=$($jarItem.Length) sha=$jarSha classes=$classCount classesContentSha=$clsSha") -and $allOk
$allOk = (Check 'E3_01_sci_exit_ok'        ($r1.Exit -eq 0) "sci($prefixA) exit=$($r1.Exit)") -and $allOk
$allOk = (Check 'E3_02_odl_exit_ok'        ($r2.Exit -eq 0) "odl($prefixA) exit=$($r2.Exit)") -and $allOk
$allOk = (Check 'E3_03_golden_bytes'       ($goldenLines.Count -eq 55) "lines=$($goldenLines.Count) bytes=$((Get-Item $goldenFile).Length) sha=$goldenSha") -and $allOk
# 只加列不变式（ORDER-1 §4.1 指定的非回归读数）：sci 的语句数仍是 37
$allOk = (Check 'E3_04_sci_statements_37'    ($r1.Json -match '"outputRecords":37') "sci($prefixA) = $($r1.Json)") -and $allOk
$allOk = (Check 'E3_05_sciB_statements_37'   ($r3.Json -match '"outputRecords":37') "sci($prefixB) = $($r3.Json)") -and $allOk
$allOk = (Check 'E3_06_odl_status_success'   ($r2.Json -match '"status":"SUCCESS"') 'odl status must be SUCCESS') -and $allOk
$allOk = (Check 'E3_07_odlB_status_success'  ($r4.Json -match '"status":"SUCCESS"') 'odl-B status must be SUCCESS') -and $allOk

# ① 计数：输入 55 / 入库 52 / 拒绝 3（JobResult 与 SQL 两侧对齐）
$allOk = (Check 'E3_10_jobresult_input'    ($r2.Json -match '"inputRecords":55') "odl json: $($r2.Json)") -and $allOk
$allOk = (Check 'E3_11_jobresult_accepted' ($r2.Json -match '"outputRecords":52') 'expect outputRecords=52') -and $allOk
$allOk = (Check 'E3_12_jobresult_rejected' ($r2.Json -match '"rejectedRecords":3') 'expect rejectedRecords=3') -and $allOk
$allOk = (Check 'E3_13_sql_total_rows'     ($checks['A1_total_accepted'] -eq '52') "A1_total_accepted=$($checks['A1_total_accepted'])") -and $allOk
$allOk = (Check 'E3_14_sql_tables_4'       ($checks['A2_ods_table_count'] -eq '4') "A2_ods_table_count=$($checks['A2_ods_table_count']) tables=$($odsTables -join ',')") -and $allOk
$rowSum = 0; foreach ($k in @('F1_rows_user','F1_rows_product','F1_rows_behavior','F1_rows_trade')) { $rowSum += [int]$checks[$k] }
$allOk = (Check 'E3_15_table_row_sum_52'   ($rowSum -eq 52) "user+product+behavior+trade=$rowSum (user=$($checks['F1_rows_user']) product=$($checks['F1_rows_product']) behavior=$($checks['F1_rows_behavior']) trade=$($checks['F1_rows_trade']))") -and $allOk
$allOk = (Check 'E3_16_probe_dual_channel_52' ($checks['F3_probe_dual_channel_ok'] -eq '52') "F3_probe_dual_channel_ok=$($checks['F3_probe_dual_channel_ok'])/52 (source_system=$srcB 且 raw_source_system=$srcA)") -and $allOk
$allOk = (Check 'E3_18_probe_payload_nonnull_52' ($checks['F4_probe_payload_nonnull_rows'] -eq '52') "F4_probe_payload_nonnull_rows=$($checks['F4_probe_payload_nonnull_rows'])/52 (换注入值不影响 v1 payload 列)") -and $allOk
$allOk = (Check 'E3_17_odl_message_facts'  ($r2.Json -match 'accepted=52 rejectedVersionKeys=3 topics=4 sourceSystem=mock-mall') "message=$($r2.Json -replace '.*(\"message\":\"[^\"]*\").*','$1')") -and $allOk

# ② 5 个新列：存在 + 非空 + 取值正确
$allOk = (Check 'E3_20_newcols_present'    ($checks['A3_describe_newcols'] -eq 'raw_event_type,raw_source_system,landing_file,payload_json,payload_hash') "A3=$($checks['A3_describe_newcols'])") -and $allOk
$allOk = (Check 'E3_21_newcols_not_null'   ($checks['A4_newcol_nulls'] -eq '0') "A4_newcol_nulls=$($checks['A4_newcol_nulls'])") -and $allOk
$allOk = (Check 'E3_22_raw_event_type'     ($checks['A5_raw_event_type_mismatch'] -eq '0') "A5_raw_event_type_mismatch=$($checks['A5_raw_event_type_mismatch'])") -and $allOk
$allOk = (Check 'E3_23_landing_file_value' ($checks['A6_landing_file'] -like '*golden-20260901.jsonl*') "A6_landing_file=$($checks['A6_landing_file'])") -and $allOk
$allOk = (Check 'E3_24_source_file_eq'     ($checks['A7_source_file_ne_landing_file'] -eq '0') "A7_source_file_ne_landing_file=$($checks['A7_source_file_ne_landing_file'])") -and $allOk

# ③ 字节保真：payload_json 与源行片段逐字节相同 + payload_hash 自洽（四张表 52 行全查）
$allOk = (Check 'E3_30_join_matched'       ($checks['B0_join_rows'] -eq '52') "B0_join_rows=$($checks['B0_join_rows'])/52（四表 ⋈ 源行）") -and $allOk
$allOk = (Check 'E3_31_payload_byte_equal' ($checks['B1_payload_ne_source_slice'] -eq '0') "B1_payload_ne_source_slice=$($checks['B1_payload_ne_source_slice'])") -and $allOk
$allOk = (Check 'E3_32_payload_hash_self'  ($checks['B2_hash_ne_sha2_payload'] -eq '0') "B2_hash_ne_sha2_payload=$($checks['B2_hash_ne_sha2_payload'])") -and $allOk
$allOk = (Check 'E3_33_hash_matches_src'   ($checks['B3_hash_ne_sha2_source_slice'] -eq '0') "B3_hash_ne_sha2_source_slice=$($checks['B3_hash_ne_sha2_source_slice'])") -and $allOk
$allOk = (Check 'E3_34_sample_len'         ([int]$checks['B4_avg_payload_len'] -gt 0) "B4_avg/min/max payload len=$($checks['B4_avg_payload_len'])/$($checks['B4_min_payload_len'])/$($checks['B4_max_payload_len'])") -and $allOk
$allOk = (Check 'E3_35_payload_braced'     ($checks['B5_payload_braced'] -eq '52') "B5_payload_braced=$($checks['B5_payload_braced'])/52 行形如 {...}") -and $allOk
$allOk = (Check 'E3_36_hash_shape'         ($checks['B6_payload_hash_lowerhex64'] -eq '0') "B6_payload_hash_lowerhex64=$($checks['B6_payload_hash_lowerhex64'])（小写十六进制 64 位）") -and $allOk
$allOk = (Check 'E3_37_join_rows_user'     ($checks['B7_rows_user'] -eq '4')  "B7_rows_user=$($checks['B7_rows_user'])") -and $allOk
$allOk = (Check 'E3_38_join_rows_trade'    ($checks['B8_rows_trade'] -eq '18') "B8_rows_trade=$($checks['B8_rows_trade'])") -and $allOk

# ④ 加法：v1 列名/类型/序号一格不动，取值仍由同一份行数据填充
$allOk = (Check 'E3_40_v1_col_order'       (($descNames -join ',') -eq ($expectedUserCols -join ',')) "describe=$($descNames -join ',')") -and $allOk
$allOk = (Check 'E3_41_v1_types'           (($descTypes[0..13] -join ',') -eq 'string,string,string,string,string,string,string,string,string,string,string,string,string,bigint') "types[0..13]=$($descTypes[0..13] -join ',')") -and $allOk
$allOk = (Check 'E3_42_v1_envelope_values' ($checks['C1_v1_envelope_mismatch'] -eq '0') "C1_v1_envelope_mismatch=$($checks['C1_v1_envelope_mismatch'])（四表）") -and $allOk
$allOk = (Check 'E3_43_v1_payload_values'  ($checks['C2_user_payload_mismatch'] -eq '0') "C2_user_payload_mismatch=$($checks['C2_user_payload_mismatch'])（独立 oracle: get_json_object）") -and $allOk
$allOk = (Check 'E3_44_ingest_batch_id'    ($checks['C3_ingest_batch_id'] -eq $batchId) "C3_ingest_batch_id=$($checks['C3_ingest_batch_id']) batchId=$batchId") -and $allOk
$c4 = @($checks['C4_product_payload_mismatch'], $checks['C4_behavior_payload_mismatch'], $checks['C4_trade_payload_mismatch'])
$allOk = (Check 'E3_45_v1_payload_all4'    (@($c4 | Where-Object { $_ -ne '0' }).Count -eq 0) "C4 product=$($c4[0]) behavior=$($c4[1]) trade=$($c4[2])（+ user 见 E3_43）") -and $allOk
$c5ok = (($checks['C5_user_payload_nonnull_rows'] -eq '4') -and ($checks['C5_product_payload_nonnull_rows'] -eq '14') -and
         ($checks['C5_behavior_payload_nonnull_rows'] -eq '16') -and ($checks['C5_trade_payload_nonnull_rows'] -eq '18'))
$allOk = (Check 'E3_46_v1_payload_nonnull_rows' $c5ok "C5 首列非空行数 user=$($checks['C5_user_payload_nonnull_rows']) product=$($checks['C5_product_payload_nonnull_rows']) behavior=$($checks['C5_behavior_payload_nonnull_rows']) trade=$($checks['C5_trade_payload_nonnull_rows'])（期望 4/14/16/18；缺陷期为全 0）") -and $allOk
$allOk = (Check 'E3_47_v1_projection_oracle' (($checks['C7_user_projection_mismatch'] -eq '0') -and ($checks['C7_trade_projection_mismatch'] -eq '0')) "C7 user=$($checks['C7_user_projection_mismatch']) trade=$($checks['C7_trade_projection_mismatch'])（v1 闭合 schema 解析投影；含 DECIMAL 与数组型 items）") -and $allOk
$allOk = (Check 'E3_48_trade_items_reading' ($null -ne $checks['C5_trade_items_ods']) "实测读数 C5_trade_items_ods=$($checks['C5_trade_items_ods']) src_head=$($checks['C5_trade_items_src_head'])（记录用途，不断言语义）") -and $allOk
# 夹具属性读数（记录用途，不做等值断言）：golden 里有一个 event_id 出现两行，
# 所以 B0 用「按 event_id 取一行规范原文」的 src 视图对账；这条把 55/53/52/1/2 全部留痕。
$allOk = (Check 'E3_49_fixture_dup_event_id' ($null -ne $checks['A13_src_dup_event_id_lines']) "实测夹具属性：源行 A10=$($checks['A10_src_lines']) 含 event_id 行 A11=$($checks['A11_src_lines_with_event_id']) 不同 event_id A12=$($checks['A12_src_distinct_event_ids']) 重复 event_id 个数 A13=$($checks['A13_src_dup_event_id_lines']) ODS 侧同 id 行数>1 的个数 A14=$($checks['A14_ods_dup_event_ids'])（B0=52 即按 event_id 规范成一行后的对账行数）") -and $allOk

# ⑤ 双通道判别：注入值 vs 行内原值（D-056）
$allOk = (Check 'E3_50_injected_channel'   ($checks['D1_probe_source_system'] -eq $srcB) "D1_probe_source_system=$($checks['D1_probe_source_system']) expect=$srcB") -and $allOk
$allOk = (Check 'E3_51_row_channel'        ($checks['D2_probe_raw_source_system'] -eq $srcA) "D2_probe_raw_source_system=$($checks['D2_probe_raw_source_system']) expect=$srcA") -and $allOk
$allOk = (Check 'E3_52_main_both_equal'    ($checks['D3_main_source_pair'] -eq "$srcA#$srcA") "D3_main_source_pair=$($checks['D3_main_source_pair'])（主库两通道同值；SQL 里用 concat(source_system,'#',raw_source_system) 拼的，分隔符是 #）") -and $allOk
$allOk = (Check 'E3_53_probe_message'      ($r4.Json -match "sourceSystem=$srcB") "odl-B message 含 sourceSystem=$srcB") -and $allOk
$allOk = (Check 'E3_54_probe_55_52_3'      (($r4.Json -match '"inputRecords":55') -and ($r4.Json -match '"outputRecords":52') -and ($r4.Json -match '"rejectedRecords":3')) "odl-B 同样 55/52/3（换注入值不改变行集）") -and $allOk

# ⑥ 隔离：本 metastore 里没有任何 dw_* 库；真实 spark-warehouse 未变
$allOk = (Check 'E3_60_no_dw_databases'    ($checks['E1_dw_databases'] -eq '0') "E1_dw_databases=$($checks['E1_dw_databases']) dbs=$($checks['E0_databases'])") -and $allOk
$allOk = (Check 'E3_61_prefix_databases'   ($checks['E2_prefix_databases'] -eq '10') "E2_prefix_databases=$($checks['E2_prefix_databases'])（2 前缀 × 5 层）") -and $allOk
$allOk = (Check 'E3_63_no_unexpected_databases' ($checks['E3_other_databases'] -eq '0') "E3_other_databases=$($checks['E3_other_databases'])（除 default 与本任务两个前缀外无其它库）") -and $allOk
$whAfter = WhFingerprint $realWh
$allOk = (Check 'E3_62_real_warehouse_unchanged' ($whBefore -eq $whAfter) "before=$whBefore after=$whAfter") -and $allOk

# ⑦ 物理落地：每个表的 dt 分区目录真实存在且非空
$partInfo = @()
foreach ($t in @('ods_user_event','ods_product_event','ods_behavior_event','ods_trade_event')) {
  foreach ($p in @($prefixA, $prefixB)) {
    $d = Join-Path $warehouse "$($p)_ods.db\$t\dt=$bizDate"
    if (Test-Path $d) {
      $n = (Get-ChildItem $d -Recurse -File -Filter '*.parquet').Count
      $partInfo += "$p.$t=$n"
    } else { $partInfo += "$p.$t=MISSING" }
  }
}
$partOk = -not ($partInfo -match 'MISSING')
$allOk = (Check 'E3_70_partition_files' $partOk ("[" + ($partInfo -join ' ') + "]")) -and $allOk

# ── 7) 落盘小结 ─────────────────────────────────────────────────────────────────
$tsv = Join-Path $eviDir "e3-$runId-checks.tsv"
$hdr = "chk`tpass`tdetail"
@($hdr) + $results | Set-Content -Path $tsv -Encoding UTF8
$summary = Join-Path $eviDir "e3-$runId-summary.txt"
@(
  "runId=$runId",
  "E3_RESULT=$(if ($allOk) { 'PASS' } else { 'FAIL' })",
  "jar=$jar bytes=$($jarItem.Length) sha256=$jarSha classes=$classCount",
  "classesContentSha256=$clsSha",
  "golden=$goldenFile lines=$($goldenLines.Count) bytes=$((Get-Item $goldenFile).Length) sha256=$goldenSha",
  "warehouseUri=$whUri",
  "derbyUri=$derbyUri",
  "prefixes=$prefixA,$prefixB sourceSystems=$srcA,$srcB batchId=$batchId",
  "sciA=$(Join-Path $eviDir "e3-$runId-01-sci-$prefixA.log")",
  "odlA=$(Join-Path $eviDir "e3-$runId-02-odl-$prefixA.log")",
  "sciB=$(Join-Path $eviDir "e3-$runId-03-sci-$prefixB.log")",
  "odlB=$(Join-Path $eviDir "e3-$runId-04-odl-$prefixB.log")",
  "readback=$sqlLog",
  "checks=$tsv",
  "realWarehouseBefore=$whBefore",
  "realWarehouseAfter=$whAfter"
) | Set-Content -Path $summary -Encoding UTF8

Say "checks tsv: $tsv"
Say "summary   : $summary"
Say "E3_RESULT=$(if ($allOk) { 'PASS' } else { 'FAIL' })"
if (-not $allOk) { exit 1 }
exit 0
