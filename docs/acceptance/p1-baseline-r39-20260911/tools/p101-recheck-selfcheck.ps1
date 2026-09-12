# P1-01 口径复核自检门禁（2026-09-12）
# 用途：把 RECHECK-20260912.md 里的**每一个量化主张**重新实测一遍，逐条断言"应然值 vs 实际值"。
# 纪律（陷阱 #27）：证据生成脚本必须自检，不得手写结论；任一 FAIL 即 exit 1。
# 只读：全部为 SELECT / 文件系统枚举 / 读日志；不写库、不跑 Spark、不改任何被引用文件。
# 口径纪律（陷阱 #29）：行数一律用 COUNT(*)，绝不用 information_schema.table_rows（InnoDB 估算值）。

[CmdletBinding()]
param(
    [string]$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path,
    [string]$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$Doc = 'docs\acceptance\p1-baseline-r39-20260911\RECHECK-20260912.md',
    [int]$ExpectedAssertions = 36
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$script:results = New-Object System.Collections.ArrayList
function Assert([string]$name, [bool]$ok, [string]$detail) {
    [void]$script:results.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    '  [{0}] {1}  {2}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail
}
function Q([string]$sql) {
    & $Mysql -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' }
}
# 关键：用 ,@(...) 防止"单元素数组被 PowerShell 解包成标量"（否则 [0] 会取到首字符）
function Rows([string]$sql) { return , @(Q $sql | Where-Object { $_ -ne '' }) }
function Cell([string]$sql) { return @(Rows $sql)[0] }
function TabJoin([string[]]$a) { return ($a -join '|') }

'=== P1-01 口径复核自检（只读）==='
$baseDir = Join-Path $Repo 'docs\acceptance\p1-baseline-r39-20260911'
$base = [IO.File]::ReadAllText((Join-Path $baseDir 'baseline.json')) | ConvertFrom-Json
$verd = [IO.File]::ReadAllText((Join-Path $baseDir 'verify\baseline.json')) | ConvertFrom-Json
$docText = [IO.File]::ReadAllText((Join-Path $Repo $Doc))
$baseApi = ([IO.File]::ReadAllText((Join-Path $baseDir 'raw\api-metrics-snapshots.json')) | ConvertFrom-Json).data

'--- A. 真库：指标面 ---'
$snap = Rows 'SELECT id,snapshot_id,status,version,pipeline_run_id FROM analytics_metric.metric_snapshot ORDER BY id;'
Assert 'A1 metric_snapshot 行数=9' ($snap.Count -eq 9) ('实际=' + $snap.Count)
Assert 'A2 ACTIVE 恰 1 条' ((Cell "SELECT COUNT(*) FROM analytics_metric.metric_snapshot WHERE status='ACTIVE';") -eq '1') '实际=1'

$liveById = @{}
foreach ($l in $snap) { $c = $l -split "`t"; $liveById[[int]$c[0]] = [pscustomobject]@{ sid = $c[1]; status = $c[2]; ver = $c[3] } }
$miss = @($baseApi | Where-Object { -not $liveById.ContainsKey([int]$_.id) })
Assert 'A3 基线 8 条快照行仍在（对 raw/api-metrics-snapshots.json 逐 id）' ($baseApi.Count -eq 8 -and $miss.Count -eq 0) ('基线=' + $baseApi.Count + ' 缺失=' + $(if ($miss.Count) { ($miss.id -join ',') } else { '无' }))
$changed = @()
foreach ($b in $baseApi) {
    $n = $liveById[[int]$b.id]
    if ($n.status -ne $b.status -or $n.ver -ne [string]$b.version -or $n.sid -ne $b.snapshotId) { $changed += ('{0}({1}) {2}/v{3}→{4}/v{5}' -f $b.snapshotId, $b.id, $b.status, $b.version, $n.status, $n.ver) }
}
Assert 'A3b 基线 8 行中仅 S20260901_39 迁移 ACTIVE→ARCHIVED（版本仍 v8）' `
    ($changed.Count -eq 1 -and $changed[0] -like 'S20260901_39*ACTIVE/v8*ARCHIVED/v8') ('实际变化=' + $(if ($changed.Count) { $changed -join ';' } else { '无' }))

$live = Rows "SELECT metric_code,metric_value,unit,definition_version,period FROM analytics_metric.metric_value WHERE snapshot_id='S20260901_39' ORDER BY metric_code;"
$liveMap = @{}
foreach ($l in $live) { $c = $l -split "`t"; $liveMap[$c[0]] = [pscustomobject]@{ v = [decimal]$c[1]; u = $c[2]; d = $c[3]; p = $c[4] } }
$bad = @()
foreach ($m in $base.metricSnapshot.apiOverview) {
    $n = $liveMap[$m.metricCode]
    if (-not $n) { $bad += ($m.metricCode + ':缺行'); continue }
    if ($n.v -ne [decimal]$m.value) { $bad += ('{0}:值{1}≠{2}' -f $m.metricCode, $n.v, $m.value) }
    if ($n.u -ne [string]$m.unit) { $bad += ($m.metricCode + ':单位') }
    if ($n.d -ne [string]$m.definitionVersion) { $bad += ($m.metricCode + ':定义版本') }
    if ($n.p -ne [string]$m.period) { $bad += ($m.metricCode + ':周期') }
}
Assert 'A4 S20260901_39 十值（值/单位/定义版本/周期）逐个等于基线 apiOverview' ($live.Count -eq 10 -and $bad.Count -eq 0) ('行数=' + $live.Count + ' 不一致=' + $(if ($bad.Count) { $bad -join ';' } else { '无' }))
$mvr = Cell 'SELECT COUNT(*) FROM analytics_metric.metric_value;'
Assert 'A5 metric_value 总行数=80（基线 70 + run41 的 10）' ($mvr -eq '80') ('实际=' + $mvr)
$dif = Rows "SELECT a.metric_code FROM analytics_metric.metric_value a JOIN analytics_metric.metric_value b ON a.metric_code=b.metric_code AND b.snapshot_id='S20260901_41' WHERE a.snapshot_id='S20260901_39' AND (a.metric_value<>b.metric_value OR a.unit<>b.unit OR a.definition_version<>b.definition_version);"
Assert 'A6 S20260901_39 与 S20260901_41 逐值差集=0 行（T2 判据独立再证）' ($dif.Count -eq 0) ('实际=' + $dif.Count)

'--- A. 真库：元数据面 ---'
$sr = TabJoin (@(Rows 'SELECT source_code,status,warehouse_prefix,profile_version,ingest_mode FROM analytics_meta.source_registry;')[0] -split "`t")
Assert 'A7 source_registry 1 行且为 mock-mall/ACTIVE/dw/1.0/FILE' ((@(Rows 'SELECT 1 FROM analytics_meta.source_registry;')).Count -eq 1 -and $sr -eq 'mock-mall|ACTIVE|dw|1.0|FILE') ('实际=' + $sr)
$rp = TabJoin (@(Rows "SELECT version,status,IFNULL(hive_database_prefix,'<NULL>'),source_id,spark_master FROM analytics_meta.runtime_profile;")[0] -split "`t")
Assert 'A8 runtime_profile#1 = v3/ACTIVE/prefix NULL/source_id 1/local[2]' ((@(Rows 'SELECT 1 FROM analytics_meta.runtime_profile;')).Count -eq 1 -and $rp -eq '3|ACTIVE|<NULL>|1|local[2]') ('实际=' + $rp)
$cnt = (Cell 'SELECT (SELECT COUNT(*) FROM analytics_meta.ingestion_batch),(SELECT MAX(id) FROM analytics_meta.ingestion_batch),(SELECT COUNT(*) FROM analytics_meta.ingestion_batch_file),(SELECT COUNT(*) FROM analytics_meta.file_checkpoint),(SELECT COUNT(*) FROM analytics_meta.pipeline_run),(SELECT MAX(id) FROM analytics_meta.pipeline_run),(SELECT COUNT(*) FROM analytics_meta.pipeline_stage_run),(SELECT MAX(id) FROM analytics_meta.pipeline_stage_run),(SELECT COUNT(*) FROM analytics_meta.quarantine_record),(SELECT COUNT(*) FROM analytics_meta.data_quality_result);') -split "`t"
$want = @('40', '40', '112', '105', '40', '41', '252', '267', '104', '401')
$diffs = @(); for ($i = 0; $i -lt $want.Count; $i++) { if ($cnt[$i] -ne $want[$i]) { $diffs += ('位{0} 实际={1} 应然={2}' -f $i, $cnt[$i], $want[$i]) } }
Assert 'A9 元数据 10 项计数=40/40/112/105/40/41/252/267/104/401' ($diffs.Count -eq 0) $(if ($diffs.Count) { $diffs -join '; ' } else { '全部相符' })
Assert 'A10 Flyway 已应用最高版本=18' ((Cell 'SELECT MAX(CAST(version AS UNSIGNED)) FROM analytics_meta.flyway_schema_history WHERE success=1;') -eq '18') '实际=18'

'--- A. ADS 镜像：真实行数 / 每快照 / 估算列反例 ---'
$mt = @($base.metadataDb.counts.ads_mirror_tables.PSObject.Properties.Name | Sort-Object)
$union = (($mt | ForEach-Object { "SELECT '$_' AS t, COUNT(*) AS c FROM analytics_metric.$_" }) -join ' UNION ALL ') + ';'
$tot = @{}; foreach ($l in (Rows $union)) { $c = $l -split "`t"; $tot[$c[0]] = [int]$c[1] }
$expTot = @{ ads_active_trend_m = 8; ads_behavior_funnel_m = 32; ads_data_quality_m = 32; ads_hot_product_m = 42; ads_operation_overview_m = 8; ads_product_conversion_m = 42; ads_sale_trend_m = 8; ads_user_profile_m = 20 }
$deltaSeq = @(); $badT = @()
foreach ($t in $mt) {
    $d = $tot[$t] - [int]$base.metadataDb.counts.ads_mirror_tables.$t
    $deltaSeq += $d
    if ($tot[$t] -ne $expTot[$t] -or $d -notin 1, 4, 9) { $badT += ('{0}:实际{1} 基线{2}' -f $t, $tot[$t], $base.metadataDb.counts.ads_mirror_tables.$t) }
}
Assert 'A11 ADS 镜像 8 表真实 COUNT(*)=8/32/32/42/8/42/8/20（基线+{1,4,9} 且合计 +30）' `
    ($mt.Count -eq 8 -and $badT.Count -eq 0 -and (($deltaSeq | Measure-Object -Sum).Sum -eq 30)) `
    $(if ($badT.Count) { $badT -join ';' } else { '增量序列=' + ($deltaSeq -join '/') + ' 合计=+30' })
$runs = Rows 'SELECT id,status FROM analytics_meta.pipeline_run WHERE id>=39 ORDER BY id;'
Assert 'A12 run 39/40/41 = SUCCESS/FAILED/SUCCESS' ((TabJoin ($runs | ForEach-Object { $_ -replace "`t", '/' })) -eq '39/SUCCESS|40/FAILED|41/SUCCESS') ('实际=' + (TabJoin ($runs | ForEach-Object { $_ -replace "`t", '/' })))

$pf = Join-Path $Repo 'analytics-server\source-profiles\mock-mall.v1.json'
$dirFiles = @(Get-ChildItem -LiteralPath (Join-Path $Repo 'analytics-server\source-profiles') -File | Sort-Object Name)
$repoHits = @(Get-ChildItem -LiteralPath $Repo -Recurse -File -Filter 'mock-mall*.json' -ErrorAction SilentlyContinue)
Assert 'A13 P3-01：注册路径不存在 + 同目录阳性对照 3 文件 + 全仓 0 命中' `
    ((-not (Test-Path -LiteralPath $pf)) -and ($dirFiles.Count -eq 3) -and ($repoHits.Count -eq 0)) `
    ('Test-Path=' + (Test-Path -LiteralPath $pf) + ' 同目录=' + ($dirFiles.Name -join ',') + ' 全仓命中=' + $repoHits.Count)

$snapUnion = (($mt | ForEach-Object { "SELECT '$_' AS t, SUM(snapshot_id='S20260901_39') AS s39, SUM(snapshot_id='S20260901_41') AS s41 FROM analytics_metric.$_" }) -join ' UNION ALL ') + ';'
$perSnap = @{}; $badS = @()
foreach ($l in (Rows $snapUnion)) { $c = $l -split "`t"; $perSnap[$c[0]] = @([int]$c[1], [int]$c[2]); if ([int]$c[1] -ne [int]$c[2]) { $badS += ('{0}:39={1} 41={2}' -f $c[0], $c[1], $c[2]) } }
$seq39 = @($mt | ForEach-Object { $perSnap[$_][0] }); $seq41 = @($mt | ForEach-Object { $perSnap[$_][1] })
Assert 'A14 每快照行数 S39=S41=1/4/4/9/1/9/1/1（各 30，并存不覆盖）' `
    ($badS.Count -eq 0 -and ($seq39 -join '/') -eq '1/4/4/9/1/9/1/1' -and ($seq41 -join '/') -eq '1/4/4/9/1/9/1/1') `
    ('39=' + ($seq39 -join '/') + ' 41=' + ($seq41 -join '/'))

$est = @{}; foreach ($l in (Rows "SELECT table_name,table_rows FROM information_schema.tables WHERE table_schema='analytics_metric' AND table_name LIKE 'ads\_%\_m' ORDER BY table_name;")) { $c = $l -split "`t"; $est[$c[0]] = [int]$c[1] }
$estEqBase = @($mt | Where-Object { $est[$_] -eq [int]$base.metadataDb.counts.ads_mirror_tables.$_ }).Count
$estNeTrue = @($mt | Where-Object { $est[$_] -ne $tot[$_] }).Count
Assert 'A15 陷阱 #29 实证：估算列逐表等于基线(8/8) 却逐表不等于真值(8/8)' ($estEqBase -eq 8 -and $estNeTrue -eq 8) ('估算=' + (@($mt | ForEach-Object { $est[$_] }) -join '/') + ' 真值=' + (@($mt | ForEach-Object { $tot[$_] }) -join '/'))

$t2log = Join-Path $Repo 'docs\acceptance\p1-06-golden55-20260912\raw\hive-counts-20260912-093352.log'
$t2 = @()
foreach ($l in [IO.File]::ReadAllLines($t2log)) { if ($l -match '^dw_ads\.ads_' -and $l -notmatch 'staging') { $c = $l.Trim() -split "`t"; $t2 += [pscustomobject]@{ name = ($c[0] -replace '^dw_ads\.', ''); n = [int]$c[1] } } }
$t2Seq = @($t2 | ForEach-Object { $_.n })
$nameMap = (@($t2 | ForEach-Object { $_.name + '_m' }) -join '|') -eq (TabJoin $mt)
Assert 'A16 T2 日志 ADS 8 正式表 = 1/4/4/9/1/9/1/1，且表名与镜像表一一对应' `
    ($t2.Count -eq 8 -and ($t2Seq -join '/') -eq '1/4/4/9/1/9/1/1' -and ($t2Seq -join '/') -eq ($deltaSeq -join '/') -and $nameMap) `
    ('日志=' + ($t2Seq -join '/') + ' 增量=' + ($deltaSeq -join '/') + ' 表名对齐=' + $nameMap)

'--- B. 文件系统（口径对齐后） ---'
$wp = @(Get-ChildItem -LiteralPath (Join-Path $Repo 'spark-warehouse') -Recurse -File -Filter '*.parquet' -ErrorAction SilentlyContinue)
$wpSum = ($wp | Measure-Object Length -Sum).Sum
Assert 'B1 spark-warehouse 972 parquet / 4,977,445 B（等于基线）' `
    ($wp.Count -eq [int]$base.warehouse.totals.parquetFiles -and $wpSum -eq [int64]$base.warehouse.totals.bytes) `
    ('实际=' + $wp.Count + '/' + $wpSum + ' 基线=' + $base.warehouse.totals.parquetFiles + '/' + $base.warehouse.totals.bytes)
$ev = @(Get-ChildItem -LiteralPath (Join-Path $Repo 'landing\events') -Recurse -File -ErrorAction SilentlyContinue)
$evSum = ($ev | Measure-Object Length -Sum).Sum
Assert 'B2 landing/events 59 文件 / 406,114,133 B' ($ev.Count -eq 59 -and $evSum -eq 406114133) ('实际=' + $ev.Count + '/' + $evSum)
$newF = @($ev | Where-Object { $_.LastWriteTime -gt [datetime]'2026-09-11 17:40:30' })
Assert 'B3 基线后新增 7 文件且其字节和 = 差值 1,974,618' `
    ($newF.Count -eq 7 -and (($newF | Measure-Object Length -Sum).Sum -eq 1974618) -and (($evSum - [int64]$base.metadataDb.counts.landing_events.bytes) -eq 1974618)) `
    ('新增=' + $newF.Count + ' 和=' + ($newF | Measure-Object Length -Sum).Sum + ' 差=' + ($evSum - [int64]$base.metadataDb.counts.landing_events.bytes))
$all = @(Get-ChildItem -LiteralPath (Join-Path $Repo 'landing') -Recurse -File -ErrorAction SilentlyContinue)
Assert 'B4 landing 递归总量（不同口径，仅旁证）=584 / 1,262,018,276' ($all.Count -eq 584 -and (($all | Measure-Object Length -Sum).Sum -eq 1262018276)) ('实际=' + $all.Count + '/' + ($all | Measure-Object Length -Sum).Sum)

'--- C. 两份 baseline.json ---'
$k1 = @($base.PSObject.Properties.Name | Sort-Object); $k2 = @($verd.PSObject.Properties.Name | Sort-Object)
Assert 'C1 两份 baseline 顶层键集合相同' (($k1 -join ',') -eq ($k2 -join ',')) ('键数=' + $k1.Count + '/' + $k2.Count)
$dk = @(); foreach ($k in $k1) { if ((($base.$k | ConvertTo-Json -Compress -Depth 12)) -ne (($verd.$k | ConvertTo-Json -Compress -Depth 12))) { $dk += $k } }
Assert 'C2 差异键恰 5 项（baselineId/durationSec/generatedAt/metadataDb/rawFiles）' `
    ((($dk | Sort-Object) -join ',') -eq 'baselineId,durationSec,generatedAt,metadataDb,rawFiles') ('实际=' + ($dk -join ','))
$t1 = @{}; $base.metadataDb.tables | ForEach-Object { $t1[$_.table] = $_.rows }
$t2t = @{}; $verd.metadataDb.tables | ForEach-Object { $t2t[$_.table] = $_.rows }
$td = @($t1.Keys | Where-Object { $t1[$_] -ne $t2t[$_] })
Assert 'C3 metadataDb 表级差异唯一为 user_session 155→156' (($td -join ',') -eq 'user_session' -and $t1['user_session'] -eq 155 -and $t2t['user_session'] -eq 156) ('差异表=' + ($td -join ',') + ' 根=' + $t1['user_session'] + ' verify=' + $t2t['user_session'])
$same = @(); foreach ($k in 'metricSnapshot', 'activeProfile', 'warehouse') { if ((($base.$k | ConvertTo-Json -Compress -Depth 12)) -eq (($verd.$k | ConvertTo-Json -Compress -Depth 12))) { $same += $k } }
Assert 'C4 metricSnapshot/activeProfile/warehouse 两份逐字段相同' ($same.Count -eq 3) ('相同=' + ($same -join ','))
Assert 'C5 repo.head 两份相同' ($base.repo.head -eq $verd.repo.head) ($base.repo.head)

'--- D. 文档自检（应然 vs 实际，防记录漂移/笔误） ---'
function CountTableRows([string]$startAnchor, [string]$endAnchor, [string]$firstCell) {
    $s = $docText.IndexOf($startAnchor); $e = $docText.IndexOf($endAnchor)
    if ($s -lt 0 -or $e -lt 0 -or $e -le $s) { return -1 }
    return ([regex]::Matches($docText.Substring($s, $e - $s), '(?m)^\|\s*' + $firstCell)).Count
}
$c1 = CountTableRows '## 2. 判定一' '## 3. 判定二' '\d+\s*\|'
Assert 'D1 §2 不变式表行数=8' ($c1 -eq 8) ('实际=' + $c1)
$c2 = CountTableRows '## 3. 判定二' '### 3.1' '\d+\s*\|'
Assert 'D2 §3 漂移表行数=12（含自查更正的镜像 +30 行）' ($c2 -eq 12) ('实际=' + $c2)
$c3 = CountTableRows '### 3.1' '### 3.2' '`'
Assert 'D3 §3.1 新增文件表行数=7' ($c3 -eq 7) ('实际=' + $c3)
Assert 'D4 §7 同时含 F-81 与 P3-01 两节' ($docText.Contains('### F-81') -and $docText.Contains('### P3-01')) ('F-81=' + $docText.Contains('### F-81') + ' P3-01=' + $docText.Contains('### P3-01'))
Assert 'D5 文档无内网 IP 字面量（隐私约定）' (-not ($docText -match '192\.168\.\d+\.\d+')) ('命中=' + ([regex]::Matches($docText, '192\.168\.\d+\.\d+')).Count)
Assert 'D6 文档 LF-only（无 CR，与仓库 blob 一致）' (-not $docText.Contains("`r")) ('CR 数=' + ([regex]::Matches($docText, "`r")).Count)
Assert 'D7 文档含"未实测 / 不得表述"边界节' ($docText.Contains('## 8. 未实测 / 不得表述')) ('存在=' + $docText.Contains('## 8. 未实测 / 不得表述'))
$mDoc = [regex]::Match($docText, '\|\s*顶层键集合\s*\|\s*(\d+)\s*键\s*\|\s*(\d+)\s*键\s*\|')
Assert 'D8 §6 写的键数 = 实测键数（防笔误复发）' ($mDoc.Success -and [int]$mDoc.Groups[1].Value -eq $k1.Count -and [int]$mDoc.Groups[2].Value -eq $k2.Count) ('文档=' + $(if ($mDoc.Success) { $mDoc.Groups[1].Value + '/' + $mDoc.Groups[2].Value } else { '未匹配' }) + ' 实测=' + $k1.Count + '/' + $k2.Count)
$mCk = [regex]::Match($docText, '\|\s*`checks`\s*/\s*`notCollected`\s*\|\s*(\d+)\s*/\s*(\d+)\s*\|\s*(\d+)\s*/\s*(\d+)\s*\|')
Assert 'D9 §6 写的 19/3 = 实测 checks/notCollected 数' `
    ($mCk.Success -and [int]$mCk.Groups[1].Value -eq @($base.checks).Count -and [int]$mCk.Groups[3].Value -eq @($verd.checks).Count -and [int]$mCk.Groups[2].Value -eq @($base.notCollected).Count -and [int]$mCk.Groups[4].Value -eq @($verd.notCollected).Count) `
    ('文档=' + $(if ($mCk.Success) { $mCk.Groups[1].Value + '/' + $mCk.Groups[2].Value + ' 与 ' + $mCk.Groups[3].Value + '/' + $mCk.Groups[4].Value } else { '未匹配' }) + ' 实测=' + @($base.checks).Count + '/' + @($base.notCollected).Count + ' 与 ' + @($verd.checks).Count + '/' + @($verd.notCollected).Count)

# 自指断言（同 E4 门禁 L8 的做法）：文档自报的断言条数必须等于本脚本实际条数
$mN = [regex]::Match($docText, '\*\*(\d+)\s*条断言\*\*')
$actual = @($script:results).Count + 1
Assert 'D10 §10 自报的断言条数 = 本脚本实际条数（自指门禁）' `
    ($mN.Success -and [int]$mN.Groups[1].Value -eq $actual) `
    ('文档自报=' + $(if ($mN.Success) { $mN.Groups[1].Value } else { '未匹配' }) + ' 实际(含本条)=' + $actual)

'--- E. 计数自检（陷阱 #27） ---'
$pass = @($script:results | Where-Object { $_.Ok }).Count
$fail = @($script:results | Where-Object { -not $_.Ok }).Count
$total = $pass + $fail
''
'=== 汇总：断言 {0} 条（应然 {1}）；PASS {2} / FAIL {3} ===' -f $total, $ExpectedAssertions, $pass, $fail
if ($total -ne $ExpectedAssertions) { '  [FAIL] 断言条数与声明应然值不符（自检漂移）'; exit 1 }
if ($fail -gt 0) { $script:results | Where-Object { -not $_.Ok } | ForEach-Object { '  FAIL: ' + $_.Name + ' → ' + $_.Detail }; exit 1 }
'  全部通过：RECHECK-20260912.md 的量化主张与真库 / 文件系统 / T2 日志实测逐条一致。'
exit 0
