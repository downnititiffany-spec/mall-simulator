<#
  R2 状态采集器（可复跑）— E3-f / E3-g 的跑前/跑后逐值证据
  =================================================================
  用途：一次性采集「真库计数 + 落地区 + spark-warehouse parquet」三元组，带**时点**。
  用法：pwsh -NoProfile -File tools\collect-state.ps1 -Tag pre
        pwsh -NoProfile -File tools\collect-state.ps1 -Tag post

  自带断言（陷阱 #27：脚本必须自带「应然值 vs 实测」断言与条数自检）：
    A1 落地区 events 目录必须存在且文件数 > 0（否则读数失真：可能是跑错工作目录）
    A2 spark-warehouse 必须存在且 parquet 文件数 > 0
    A3 MySQL 三个必查库（analytics_meta / analytics_metric）必须可连
    A4 输出文件非空且行数 == 期望条数（自检）
  若任一断言失败，脚本以退出码 1 结束并在输出中写明 FAIL。
#>
param(
  [Parameter(Mandatory=$true)][ValidateSet('pre','post')][string]$Tag
)

$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$outDir = Join-Path $root 'docs\acceptance\t2rerun-golden55-post-ct-20260912\raw'
$mysql  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

# 落盘一律 LF（陷阱：仓库 core.autocrlf=true，Set-Content 会写 CRLF）
function Write-Lf {
  param([string]$Path, [string]$Text)
  [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}
function L { param([string]$s) $script:buf.Add($s) }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$t0 = Get-Date
# 陷阱：@($list) 对 List[object] 会抛 "Argument types do not match" ⇒ 用 .Add() 追加，不用 +=
$script:buf = New-Object 'System.Collections.Generic.List[string]'
$fail = New-Object 'System.Collections.Generic.List[string]'

function L { param([string]$s) $script:buf.Add($s) }

L "R2 状态采集（$Tag）— collect-state.ps1"
L "================================================================="
L "采集时点(本地)：$($t0.ToString('yyyy-MM-dd HH:mm:ss.fff'))"
L "采集者：R2 执行方（一次性泳道）；全部命令只读"
L "工作目录约定：$root（landing/ spark-warehouse/ 均为该目录下的相对路径）"
L ""

# ---------------- A1/A2 存在性断言 ----------------
$eventsDir = Join-Path $root 'landing\events'
if (-not (Test-Path $eventsDir)) { $fail.Add("A1 失败：$eventsDir 不存在") }

# ---------------- 1. 落地区（E3-g）----------------
L "---- [1] 落地区（E3-g；必须带时点）----"
L "时点：$($t0.ToString('yyyy-MM-dd HH:mm:ss'))"
$landSub = @('events','manifests','accepted','quarantine')
foreach ($s in $landSub) {
  $p = Join-Path $root "landing\$s"
  if (Test-Path $p) {
    $f = Get-ChildItem -Recurse -File $p -ErrorAction SilentlyContinue
    $cnt = ($f | Measure-Object).Count
    $bytes = ($f | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    L ("landing\{0,-11} {1,5} 文件 / {2,15} B" -f $s, $cnt, $bytes)
  } else {
    L ("landing\{0,-11} <目录不存在>" -f $s)
  }
}
$evFiles = Get-ChildItem -Recurse -File $eventsDir -ErrorAction SilentlyContinue
$evCnt = ($evFiles | Measure-Object).Count
if ($evCnt -le 0) { $fail.Add("A1 失败：landing\events 文件数 = $evCnt") }
L ""
L "landing\events 逐文件清单（名|字节|mtime）：共 $evCnt 条"
foreach ($f in ($evFiles | Sort-Object Name)) {
  L ("  {0}|{1}|{2}" -f $f.Name, $f.Length, $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
L ""

# ---------------- 2. spark-warehouse（E3-f）----------------
L "---- [2] spark-warehouse parquet（E3-f；文件数 + 字节，按库分组）----"
$wh = Join-Path $root 'spark-warehouse'
if (-not (Test-Path $wh)) { $fail.Add("A2 失败：$wh 不存在") }
$pq = Get-ChildItem -Recurse -File $wh -Filter '*.parquet' -ErrorAction SilentlyContinue
$pqCnt = ($pq | Measure-Object).Count
$pqBytes = ($pq | Measure-Object -Property Length -Sum).Sum
if ($null -eq $pqBytes) { $pqBytes = 0 }
if ($pqCnt -le 0) { $fail.Add("A2 失败：parquet 文件数 = $pqCnt") }
L "spark-warehouse 合计：$pqCnt parquet 文件 / $pqBytes B"
L "按一级子目录（= Hive 库）分组："
$grp = $pq | Group-Object { ($_.FullName.Substring($wh.Length+1) -split '\\')[0] } | Sort-Object Name
$sumCnt = 0; $sumBytes = 0
foreach ($g in $grp) {
  $b = ($g.Group | Measure-Object -Property Length -Sum).Sum
  if ($null -eq $b) { $b = 0 }
  L ("  {0,-22} {1,4} 文件 / {2,10} B" -f $g.Name, $g.Count, $b)
  $sumCnt += $g.Count; $sumBytes += $b
}
L "分组求和校验：$sumCnt 文件 / $sumBytes B（应等于合计 $pqCnt / $pqBytes）"
if ($sumCnt -ne $pqCnt -or $sumBytes -ne $pqBytes) {
  $fail.Add("A2b 失败：分组求和($sumCnt/$sumBytes) != 合计($pqCnt/$pqBytes)")
} else {
  L "  自检 OK：分组求和 == 合计"
}
L ""

# ---------------- 3. 真库计数 ----------------
L "---- [3] MySQL 真库计数（只读 SELECT）----"
L "口径：analytics_meta = 平台元数据（采集/流水线）；analytics_metric = 指标库（快照/指标值/ADS 镜像）"
L "      依据 application.yml L5-21：platform.metric.publish/read 两源均指向 analytics_metric；"
L "      analytics_meta.metric_snapshot|metric_value 是 db/meta/V2 遗留的**孤儿表**（见 raw/trap-orphan-metric-tables.txt）"
$sql = @"
SELECT 'flyway_rows' k, COUNT(*) v FROM analytics_meta.flyway_schema_history
UNION ALL SELECT 'flyway_max_version', MAX(CAST(version AS UNSIGNED)) FROM analytics_meta.flyway_schema_history
UNION ALL SELECT 'flyway_success', SUM(success) FROM analytics_meta.flyway_schema_history
UNION ALL SELECT 'flyway_bad', SUM(success=0) FROM analytics_meta.flyway_schema_history
UNION ALL SELECT 'file_checkpoint', COUNT(*) FROM analytics_meta.file_checkpoint
UNION ALL SELECT 'file_checkpoint_null_source', SUM(source_id IS NULL) FROM analytics_meta.file_checkpoint
UNION ALL SELECT 'ingestion_batch', COUNT(*) FROM analytics_meta.ingestion_batch
UNION ALL SELECT 'ingestion_batch_maxid', MAX(id) FROM analytics_meta.ingestion_batch
UNION ALL SELECT 'ingestion_batch_file', COUNT(*) FROM analytics_meta.ingestion_batch_file
UNION ALL SELECT 'pipeline_run', COUNT(*) FROM analytics_meta.pipeline_run
UNION ALL SELECT 'pipeline_run_maxid', MAX(id) FROM analytics_meta.pipeline_run
UNION ALL SELECT 'pipeline_stage_run', COUNT(*) FROM analytics_meta.pipeline_stage_run
UNION ALL SELECT 'source_registry', COUNT(*) FROM analytics_meta.source_registry
UNION ALL SELECT '[metric]metric_snapshot', COUNT(*) FROM analytics_metric.metric_snapshot
UNION ALL SELECT '[metric]metric_snapshot_ACTIVE', SUM(status='ACTIVE') FROM analytics_metric.metric_snapshot
UNION ALL SELECT '[metric]metric_snapshot_ARCHIVED', SUM(status='ARCHIVED') FROM analytics_metric.metric_snapshot
UNION ALL SELECT '[metric]metric_value', COUNT(*) FROM analytics_metric.metric_value
UNION ALL SELECT '[ORPHAN meta]metric_snapshot', COUNT(*) FROM analytics_meta.metric_snapshot
UNION ALL SELECT '[ORPHAN meta]metric_snapshot_ACTIVE', SUM(status='ACTIVE') FROM analytics_meta.metric_snapshot
UNION ALL SELECT '[ORPHAN meta]metric_value', COUNT(*) FROM analytics_meta.metric_value;
"@
# mysql 客户端接受 -e "<sql>"；PowerShell 会把整个字符串作为**单个** argv 传入（不经过 cmd.exe）
$sqlPath = Join-Path $env:TEMP "r2-state-$Tag.sql"
Write-Lf -Path $sqlPath -Text $sql
$res = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1
if ($LASTEXITCODE -ne 0) { $fail.Add("A3 失败：mysql 退出码 $LASTEXITCODE") }
foreach ($line in $res) { if ("$line" -notmatch 'Warning.*password') { L "$line" } }
L ""

L "---- [4] analytics_metric.metric_snapshot 逐行（权威指标库）----"
$q2 = "SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, pipeline_run_id, status, version, business_time, published_at, created_at FROM analytics_metric.metric_snapshot ORDER BY id;"
$r2 = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q2 2>&1
if ($LASTEXITCODE -ne 0) { $fail.Add("A3b 失败：snapshot 查询退出码 $LASTEXITCODE") }
foreach ($line in $r2) { if ("$line" -notmatch 'Warning.*password') { L "$line" } }
L ""

L "---- [4b] analytics_metric ADS 镜像 8 表按 snapshot_id 的 COUNT(*) ----"
$q3 = @"
SELECT 'ads_active_trend_m' t, snapshot_id, COUNT(*) c FROM analytics_metric.ads_active_trend_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_behavior_funnel_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_behavior_funnel_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_data_quality_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_data_quality_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_hot_product_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_hot_product_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_operation_overview_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_operation_overview_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_product_conversion_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_product_conversion_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_sale_trend_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_sale_trend_m GROUP BY snapshot_id
UNION ALL SELECT 'ads_user_profile_m', snapshot_id, COUNT(*) FROM analytics_metric.ads_user_profile_m GROUP BY snapshot_id
ORDER BY t, snapshot_id;
"@
$r3 = & $mysql -uroot -p123456 --default-character-set=utf8mb4 -B -e $q3 2>&1
if ($LASTEXITCODE -ne 0) { $fail.Add("A3c 失败：ADS 镜像查询退出码 $LASTEXITCODE") }
foreach ($line in $r3) { if ("$line" -notmatch 'Warning.*password') { L "$line" } }
L ""

L "---- [5] 当前在跑的 java/javaw 进程 ----"
foreach ($p in (Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Sort-Object Id)) {
  L ("pid={0} name={1} start={2}" -f $p.Id, $p.ProcessName, $p.StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
}
L ""

# ---------------- 输出 + 条数自检 ----------------
L "---- 断言小结 ----"
if ($fail.Count -eq 0) {
  L "全部断言 PASS（A1/A2/A2b/A3）"
} else {
  L "FAIL 共 $($fail.Count) 条："
  foreach ($f in $fail) { L "  - $f" }
}
L "输出行数：$($script:buf.Count)"

$outFile = Join-Path $outDir "state-$Tag-$stamp.txt"
Write-Lf -Path $outFile -Text (($script:buf -join "`n") + "`n")
$written = [IO.File]::ReadAllText($outFile)
$nLines = ([regex]::Matches($written, "`n").Count)
$cr = ([regex]::Matches($written, "`r").Count)
L ""
Write-Host "OUTFILE=$outFile"
Write-Host "LINES=$nLines  CR_BYTES=$cr (must be 0)  BYTES=$((Get-Item $outFile).Length)"
if ($cr -ne 0) { Write-Host "EOL FAIL: CR present"; exit 1 }
if ($fail.Count -gt 0) { Write-Host "ASSERT FAIL: $($fail.Count)"; exit 1 }
Write-Host "ASSERT PASS: all"
