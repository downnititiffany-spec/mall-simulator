# R9 D-R9-1 缺陷修复验证：AdsPublishJob 暂存清理必须按 dt 限定
#   背景：修复前 prune 不带 dt 限定 → 发布任一业务日期会删除**其他业务日期**尚未发布的
#         __staging 分区，使交错/并发运行在 QUALITY_CHECK 误报 ADS_STAGING_PRESENT（8 表全空，
#         BLOCKING）而失败，并使失败运行的 resume/retry-from-stage 因暂存被清空而永久失败（run 26 实测）。
#   本脚本：① 在真实元数据库埋一个**异日期**暂存分区（snapshot_id=S20260907_TEST, dt=20260907）
#           ② 触发一次真实黄金链 run（dt=20260901）并等待终态
#           ③ 断言：异日期分区必须存活（修复前必被删除）＋ 同日期历史暂存仍按设计被清理 ＋ ACTIVE 为黄金值
param(
  [string]$Snapshot = 'S20260901_25',
  [string]$EvidenceDir = 'D:\Develop_code\GraduationProject\docs\acceptance\r9-20260911-fabc6cb-run25-S20260901_25'
)
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$base = 'http://127.0.0.1:8091'
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$SQL = @(
  '--conf', 'spark.sql.warehouse.dir=file:///D:/Develop_code/GraduationProject/spark-warehouse',
  '--conf', 'spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:D:/Develop_code/GraduationProject/derby-metastore;create=true',
  '--conf', 'spark.sql.hive.metastore.jars=builtin',
  '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
  '--conf', 'spark.ui.enabled=false', '-S'
)
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
function Q([string]$sql) { & $MYSQL -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1 | Where-Object { $_ -notmatch 'Using a password' } }
function HiveSql([string]$file, [string]$log) { & 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd' @SQL -f $file 2>&1 | Set-Content $log -Encoding UTF8; Get-Content $log | Where-Object { $_ -match '^(snapshot_id|dt)=|\||^dwd_|^dws_' } }
function Ck($name, $expect, $actual, $pass, $note) { $script:rows.Add(("{0}`t{1}`t{2}`t{3}`t{4}" -f $name, $expect, $actual, $(if ($pass) { 'PASS' } else { 'FAIL' }), $note)) }

$rows = New-Object System.Collections.Generic.List[string]
$rows.Add("check`t期望`t实测`t结果`t备注")
$stamp = Get-Date -Format 'HHmmss'
$log = "$EvidenceDir\27-prune-fix-verify.log"
"R9 D-R9-1 暂存清理 dt 限定修复验证 @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content $log -Encoding UTF8

# ── ① 埋异日期暂存分区 + 记录清理前分区清单 ─────────────────────────────
@"
INSERT INTO dw_ads.ads_operation_overview__staging PARTITION(snapshot_id='S20260907_TEST', dt='20260907')
  (pv,uv,dau,order_count,sale_amount,net_sale_amount,avg_order_value,refund_rate,full_refund_rate)
  SELECT 1,1,1,1,1.00,1.00,1.00,0.0000,0.0000;
SHOW PARTITIONS dw_ads.ads_operation_overview__staging;
"@ | Set-Content .verify\r9-prune-seed.sql -Encoding UTF8
$before = HiveSql '.verify\r9-prune-seed.sql' '.verify\r9-prune-seed.log'
$beforeList = ($before | Where-Object { $_ -match '^snapshot_id=' }) -join ' || '
"埋点后 partition 清单: $beforeList" | Add-Content $log -Encoding UTF8
$seeded = $beforeList -match 'S20260907_TEST/dt=20260907'
Ck 'P0 埋点异日期暂存分区' 'S20260907_TEST/dt=20260907 存在' $beforeList $seeded '真实元数据库注入，模拟"另一业务日期在飞/未发布"的暂存分区'

# ── ② 投放黄金夹具（R9 实测：landing 数据被消费后重跑必得 RUN_EMPTY_DATA）
#      再触发真实黄金链 run（新 sourceDataVersion）并等待终态 ────────────────
$golden = Get-Content "$root\tests\golden-dataset\events\golden-20260901.jsonl" -Raw
$fixture = "$root\landing\events\r9-prunefix-$stamp.jsonl"
[System.IO.File]::WriteAllText($fixture, $golden, (New-Object System.Text.UTF8Encoding($false)))
$lines = (Get-Content $fixture | Measure-Object -Line).Lines
"投放黄金夹具 $fixture lines=$lines" | Add-Content $log -Encoding UTF8
$tok = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token
$h = @{ Authorization = "Bearer $tok" }
Invoke-RestMethod -Method Post -Uri "$base/api/v1/ingestion/runs" -Headers $h | Out-Null
$batch = Q "SELECT CONCAT(id,'/',status,'/',record_count,'/',COALESCE(quarantine_count,0)) FROM analytics_meta.ingestion_batch ORDER BY id DESC LIMIT 1;"
Ck 'P0b 夹具被采集（批次状态与隔离行数）' 'QUARANTINED/51/4（口径分歧已登记）' ($batch -join '') ($batch -join '' -match '/QUARANTINED/51/4') '平台隔离策略：accepted 51 入库、4 行隔离（3 非法行 + 1 重复 event_id 037）；合同口径 accept 52/reject 3 的分歧见 remediation-status.md；LOAD_ODS 实测装载 51 条'
$sdv = "r9-prunefix-$stamp"
$body = @{ runtimeProfileId = 1; pipelineCode = 'ODS_TO_ADS'; businessTime = '2026-09-01T00:00:00'; sourceDataVersion = $sdv } | ConvertTo-Json
$run = (Invoke-RestMethod -Method Post -Uri "$base/api/v1/pipeline-runs" -Headers $h -ContentType 'application/json' -Body $body).data
"创建 run $($run.runId)（sourceDataVersion=$sdv）" | Add-Content $log -Encoding UTF8
$deadline = (Get-Date).AddMinutes(12); $fin = $null
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 15
  $fin = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/pipeline-runs/$($run.runId)" -Headers $h).data
  if ($fin.status -in @('SUCCESS', 'FAILED', 'PARTIAL')) { break }
}
$fin | ConvertTo-Json -Depth 12 | Set-Content "$EvidenceDir\28-prunefix-run.json" -Encoding UTF8
Ck 'P1 黄金链在新 jar 下重跑' 'SUCCESS（暂存清理修复未破坏主链）' "runId=$($run.runId) status=$($fin.status) stage=$($fin.currentStage) error=$($fin.errorCode) snapshot=$($fin.targetSnapshotId)" ($fin.status -eq 'SUCCESS') "新增 run，snapshot=$($fin.targetSnapshotId)"

# ── ③ 清理后分区清单：异日期必须存活；同日期历史快照应被清理 ──────────────
@'
SHOW PARTITIONS dw_ads.ads_operation_overview__staging;
'@ | Set-Content .verify\r9-prune-after.sql -Encoding UTF8
$after = HiveSql '.verify\r9-prune-after.sql' '.verify\r9-prune-after.log'
$afterList = ($after | Where-Object { $_ -match '^snapshot_id=' }) -join ' || '
"发布后 partition 清单: $afterList" | Add-Content $log -Encoding UTF8
$foreignAlive = $afterList -match 'S20260907_TEST/dt=20260907'
Ck 'P2 异日期暂存分区存活（修复点）' 'S20260907_TEST/dt=20260907 仍在' $afterList $foreignAlive '修复前该分区会被本次发布删除（AdsPublishJob 原实现无 dt 限定）'
$sameDtPruned = $afterList -notmatch "snapshot_id=$Snapshot/dt=20260901"
Ck 'P3 同日期历史暂存仍按设计清理' "snapshot_id=$Snapshot/dt=20260901 已被清理" $afterList $sameDtPruned 'dt 限定后同 dt 的历史快照仍被回收（正式分区已指向新快照）'

# ── ④ 发布检查项原文 + ACTIVE 值 ─────────────────────────────────────────
$ev = (Q "SELECT evidence FROM analytics_meta.pipeline_stage_run WHERE run_id=$($run.runId) AND stage_code='PUBLISH_METRIC';") -join "`n"
$m = [regex]::Match($ev, '\{"ruleCode":"PUB_STAGING_PRUNE".*?\}')
"PUB_STAGING_PRUNE 检查项: $($m.Value)" | Add-Content $log -Encoding UTF8
Ck 'P4 发布检查项自述按 dt 限定' 'PUB_STAGING_PRUNE 说明含 dt=20260901' $(if ($m.Success) { $m.Value } else { '（未取到）' }) ($m.Success -and $m.Value -match 'dt=20260901') '证据 = pipeline_stage_run.evidence 原文'
$act = (Q "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;" | Where-Object { $_ -notmatch '^snapshot_id' }) -join ''
$mv = (Q "SELECT CONCAT(pv,'/',uv,'/',dau,'/',order_count,'/',sale_amount,'/',net_sale_amount,'/',avg_order_value,'/',refund_rate,'/',full_refund_rate) FROM analytics_metric.ads_operation_overview_m WHERE snapshot_id='$act';") -join ''
Ck 'P5 发布后 ACTIVE 为本次黄金快照' "$($fin.targetSnapshotId)" $act ($act -eq "$($fin.targetSnapshotId)") '发布语义：新快照置 ACTIVE，旧快照 ARCHIVED'
Ck 'P6 ACTIVE 指标 = 黄金标准答案' '7/3/3/5/2042.00/1493.00/408.40/0.6000/0.2000' $mv ($mv -eq '7/3/3/5/2042.00/1493.00/408.40/0.6000/0.2000') '跨日期暂存清理不得影响黄金口径'

# ── ⑤ D-R9-2（本次新发现并修复）：维度快照跨分区扇出 ─────────────────────
#   现象：TradeDwdJob 关联 dw_dim 未按生效日期 dt 过滤，dim 每日快照累积出多分区后
#         形成笛卡尔放大 → dt=20260901 订单明细 7 行 → 28 行，GMV 2042.00 → 8168.00。
@'
SELECT CONCAT('dwd_order_rows=', COUNT(*)) FROM dw_dwd.dwd_order_detail WHERE dt='20260901';
SELECT CONCAT('dwd_order_distinct=', COUNT(DISTINCT order_id)) FROM dw_dwd.dwd_order_detail WHERE dt='20260901';
SELECT CONCAT('dws_trade=', order_count, '/', sale_amount, '/', net_sale_amount, '/', avg_order_value) FROM dw_dws.dws_trade_day WHERE dt='20260901';
'@ | Set-Content .verify\r9-dimfanout-check.sql -Encoding UTF8
$dq = HiveSql '.verify\r9-dimfanout-check.sql' '.verify\r9-dimfanout-check.log'
$join = ($dq -join ' ')
"维度扇出核对: $join" | Add-Content $log -Encoding UTF8
$dwdRows = [regex]::Match($join, 'dwd_order_rows=(\d+)').Groups[1].Value
$dws = [regex]::Match($join, 'dws_trade=([\d./]+)').Groups[1].Value
Ck 'P7 D-R9-2 修复：DWD 订单明细无维度扇出' 'dwd_order_rows=7（修复前实测 28 = 7 × dim 分区 4）' "dwd_order_rows=$dwdRows" ($dwdRows -eq '7') 'dim_user/dim_product 每日快照多分区 JOIN 未按生效日期过滤 → 笛卡尔放大；修复后与黄金行数一致'
Ck 'P8 D-R9-2 修复：DWS 交易汇总 = 黄金' '5/2042.00/1493.00/408.40' $dws ($dws -eq '5/2042.00/1493.00/408.40') '修复前实测 5/8168.00/5972.00/1633.60（GMV/净销售额 ×4）'

$rows | Set-Content "$EvidenceDir\26-prune-fix-verification.tsv" -Encoding UTF8
$fail = ($rows | Where-Object { $_ -match "`tFAIL`t" }).Count
Write-Host ($rows -join "`n")
Write-Host "`nD-R9-1 验证：PASS=$(($rows | Where-Object { $_ -match "`tPASS`t" }).Count) FAIL=$fail → $EvidenceDir\26-prune-fix-verification.tsv"
if ($fail -gt 0) { exit 1 }
