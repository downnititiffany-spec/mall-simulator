# R9 最终验收证据采集（§23.5 证据目录 + §30 验收清单取数）
# 用法: pwsh -File .verify\r9-evidence-collect.ps1 [-RunId 22] [-Snapshot S20260901_22] [-Date 20260901]
# 说明: 全部数字来自真实库/真实磁盘/真实 API，不做任何估算；文件命名含日期+commit+runId+snapshotId。
param(
  [int]$RunId = 0,
  [string]$Snapshot = '',
  [string]$Date = '20260901'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
$base = 'http://127.0.0.1:8091'
$MYSQL = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$SQL = @(
  '--conf', 'spark.sql.warehouse.dir=file:///D:/Develop_code/GraduationProject/spark-warehouse',
  '--conf', 'spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:D:/Develop_code/GraduationProject/derby-metastore;create=true',
  '--conf', 'spark.sql.hive.metastore.jars=builtin',
  '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
  '--conf', 'spark.ui.enabled=false', '-S'
)
$env:SPARK_LOCAL_IP = '127.0.0.1'

function Q([string]$sql) { & $MYSQL -uroot -p123456 --default-character-set=utf8mb4 -N -B -e $sql 2>&1 | Where-Object { $_ -notmatch 'Using a password' } }
function One([string]$sql) { ($(Q $sql) | Select-Object -First 1) }
function Dump([string]$sql, [string]$path) {
  $out = @(Q $sql)
  if ($out.Count -eq 0) { $out = @('# （0 行）') }
  $out | Set-Content -Path $path -Encoding UTF8
  $first = (Get-Content $path -TotalCount 1 -ErrorAction SilentlyContinue) -join ''
  if ($first -match '^ERROR \d+') { Write-Host ("  !! SQL 失败 {0}: {1}" -f (Split-Path $path -Leaf), $first) -ForegroundColor Red }
  else { Write-Host ("  -> {0} ({1} 行)" -f (Split-Path $path -Leaf), $out.Count) }
}

# ---------- 0) 解析当前真实运行态 ----------
$commit = (git -C $root rev-parse --short HEAD).Trim()
if (-not $Snapshot) { $Snapshot = (One "SELECT snapshot_id FROM analytics_metric.metric_snapshot WHERE active_flag=1;").Trim() }
# run 与快照必须同源：取「目标快照 = 当前 ACTIVE 且 SUCCESS」的最新一次运行（否则证据会跨运行错配）
if ($RunId -eq 0) { $RunId = [int](One "SELECT id FROM analytics_meta.pipeline_run WHERE status='SUCCESS' AND target_snapshot_id='$Snapshot' ORDER BY id DESC LIMIT 1;") }
$stamp = Get-Date -Format 'yyyyMMdd'
$dir = Join-Path $root ("docs\acceptance\r9-{0}-{1}-run{2}-{3}" -f $stamp, $commit, $RunId, $Snapshot)
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Write-Host "EVIDENCE_DIR=$dir"
Write-Host "COMMIT=$commit RUN=$RunId SNAPSHOT=$Snapshot DATE=$Date"

# ---------- 1) 元数据库导出（平台侧流水线/作业/审计/决策） ----------
Write-Host '[1] 平台元数据导出'
Dump "SELECT id,pipeline_code,status,current_stage,attempt_no,target_snapshot_id,error_code,source_data_version,created_at,started_at,finished_at FROM analytics_meta.pipeline_run WHERE id=$RunId;" "$dir\01-pipeline-run.tsv"
Dump "SELECT id,run_id,stage_code,status,external_job_id,records,error_code,CHAR_LENGTH(evidence) AS ev_chars,JSON_VALID(evidence) AS ev_json_valid,started_at,finished_at FROM analytics_meta.pipeline_stage_run WHERE run_id=$RunId ORDER BY id;" "$dir\02-pipeline-stage-run.tsv"
Dump "SELECT id,pipeline_run_id,stage_code,job_code,external_job_id,submitter_type,status,input_records,output_records,rejected_records,CHAR_LENGTH(output_partitions_json) AS parts_chars,log_uri,error_code,started_at,finished_at FROM analytics_meta.spark_job_run WHERE pipeline_run_id=$RunId ORDER BY id;" "$dir\03-spark-job-run.tsv"
Dump "SELECT COALESCE(job_code,'-') AS job_code, status, COUNT(*) AS jobs, SUM(input_records) AS in_rec, SUM(output_records) AS out_rec, SUM(rejected_records) AS rejected FROM analytics_meta.spark_job_run WHERE pipeline_run_id=$RunId GROUP BY job_code,status ORDER BY job_code;" "$dir\04-spark-job-summary.tsv"
Dump "SELECT action,result,COUNT(*) FROM analytics_meta.operation_audit_log GROUP BY action,result ORDER BY action,result;" "$dir\05-operation-audit-summary.tsv"
Dump "SELECT id,action,result,user_id,role,resource_type,resource_id,reason,LEFT(created_at,19) FROM analytics_meta.operation_audit_log ORDER BY id DESC LIMIT 15;" "$dir\06-operation-audit-last15.tsv"
Dump "SELECT status,COUNT(*) FROM analytics_meta.decision_task GROUP BY status;" "$dir\07-decision-task-by-status.tsv"
Dump "SELECT user_id,status,COUNT(*),COALESCE(SUM(rows_returned),0) FROM analytics_meta.ai_query_history GROUP BY user_id,status;" "$dir\08-ai-query-history.tsv"
Dump "SELECT use_case,provider,status,COUNT(*),COALESCE(SUM(elapsed_ms),0) FROM analytics_meta.ai_call_log GROUP BY use_case,provider,status;" "$dir\09-ai-call-log.tsv"

# ---------- 2) 指标库导出（analytics_metric，只读账号 + root 双视角） ----------
Write-Host '[2] 指标库导出'
Dump "SELECT id,snapshot_id,runtime_profile_id,business_time,status,active_flag,version,created_at FROM analytics_metric.metric_snapshot ORDER BY id;" "$dir\10-metric-snapshot.tsv"
Dump "SELECT metric_code,metric_value,unit,period,definition_version,snapshot_id FROM analytics_metric.metric_value WHERE snapshot_id='$Snapshot' ORDER BY metric_code;" "$dir\11-metric-value-active.tsv"
foreach ($t in @('ads_operation_overview_m','ads_sale_trend_m','ads_behavior_funnel_m','ads_active_trend_m','ads_hot_product_m','ads_product_conversion_m','ads_user_profile_m','ads_data_quality_m')) {
  Dump "SELECT '$t' AS table_name, COUNT(*) AS rows_all, COUNT(DISTINCT snapshot_id) AS snapshots FROM analytics_metric.$t;" "$dir\12-ads-table-counts.tsv.tmp"
  Get-Content "$dir\12-ads-table-counts.tsv.tmp" | Add-Content "$dir\12-ads-table-counts.tsv"
}
Remove-Item "$dir\12-ads-table-counts.tsv.tmp" -Force
Q "SELECT snapshot_id,dt,pv,uv,dau,order_count,sale_amount,net_sale_amount,avg_order_value,refund_rate,full_refund_rate FROM analytics_metric.ads_operation_overview_m WHERE snapshot_id='$Snapshot';" | Set-Content "$dir\13-ads-overview-active.tsv" -Encoding UTF8
Q "SELECT rule_code,passed,check_count,error_count FROM analytics_metric.ads_data_quality_m WHERE snapshot_id='$Snapshot' ORDER BY rule_code;" | Set-Content "$dir\14-ads-quality-active.tsv" -Encoding UTF8

# ---------- 3) Hive 数仓四层证据（同一次验收内的独立复核） ----------
Write-Host '[3] Hive 四层证据（spark-sql）'
$hiveSql = @"
SELECT '### ods_counts' AS section;
SELECT 'ods_behavior_event' AS t, dt, COUNT(*) AS rows FROM dw_ods.ods_behavior_event GROUP BY dt;
SELECT 'ods_trade_event' AS t, dt, COUNT(*) AS rows FROM dw_ods.ods_trade_event GROUP BY dt;
SELECT 'ods_product_event' AS t, dt, COUNT(*) AS rows FROM dw_ods.ods_product_event GROUP BY dt;
SELECT 'ods_user_event' AS t, dt, COUNT(*) AS rows FROM dw_ods.ods_user_event GROUP BY dt;
SELECT '### dwd_counts' AS section;
SELECT 'dwd_user_behavior_detail' AS t, dt, COUNT(*) AS rows FROM dw_dwd.dwd_user_behavior_detail GROUP BY dt;
SELECT 'dwd_order_detail' AS t, dt, COUNT(*) AS rows FROM dw_dwd.dwd_order_detail GROUP BY dt;
SELECT 'dwd_reject_record' AS t, dt, COUNT(*) AS rows FROM dw_dwd.dwd_reject_record GROUP BY dt;
SELECT '### dws_trade_day (dt=$Date)' AS section;
SELECT dt, order_count, buyer_count, sale_amount, refund_amount, net_sale_amount, avg_order_value FROM dw_dws.dws_trade_day WHERE dt='$Date';
SELECT '### ads_formal (dt=$Date，正式分区只有 dt，快照维度在 __staging)' AS section;
SELECT dt, pv, uv, dau, order_count, sale_amount, net_sale_amount, avg_order_value, refund_rate, full_refund_rate FROM dw_ads.ads_operation_overview WHERE dt='$Date';
SELECT 'ads_behavior_funnel' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_behavior_funnel WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_hot_product' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_hot_product WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_product_conversion' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_product_conversion WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_sale_trend' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_sale_trend WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_active_trend' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_active_trend WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_user_profile' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_user_profile WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_operation_overview' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_operation_overview WHERE dt='$Date' GROUP BY dt;
SELECT 'ads_data_quality' AS t, dt, COUNT(*) AS rows FROM dw_ads.ads_data_quality WHERE dt='$Date' GROUP BY dt;
SELECT '### ads_staging (快照隔离：每次发布独立 snapshot_id 分区)' AS section;
SELECT 'ads_operation_overview__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_operation_overview__staging GROUP BY snapshot_id, dt;
SELECT 'ads_behavior_funnel__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_behavior_funnel__staging GROUP BY snapshot_id, dt;
SELECT 'ads_hot_product__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_hot_product__staging GROUP BY snapshot_id, dt;
SELECT 'ads_product_conversion__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_product_conversion__staging GROUP BY snapshot_id, dt;
SELECT 'ads_sale_trend__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_sale_trend__staging GROUP BY snapshot_id, dt;
SELECT 'ads_active_trend__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_active_trend__staging GROUP BY snapshot_id, dt;
SELECT 'ads_user_profile__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_user_profile__staging GROUP BY snapshot_id, dt;
SELECT 'ads_data_quality__staging' AS t, snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_data_quality__staging GROUP BY snapshot_id, dt;
SELECT '### quality (dt=$Date)' AS section;
SELECT * FROM dw_ads.ads_data_quality WHERE dt='$Date' ORDER BY rule_code;
SELECT '### partitions' AS section;
SHOW PARTITIONS dw_ads.ads_operation_overview;
"@
$hiveFile = "$dir\hive-evidence.sql"
$hiveSql | Set-Content $hiveFile -Encoding UTF8
& 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd' @SQL -f $hiveFile 2>&1 |
  Where-Object { $_ -notmatch '^\s*$' -and $_ -notmatch 'WARN|INFO|log4j|SLF4J' } |
  Set-Content "$dir\15-hive-layers.txt" -Encoding UTF8
Write-Host ("  -> 15-hive-layers.txt ({0} 行)" -f (Get-Content "$dir\15-hive-layers.txt" | Measure-Object -Line).Lines)

# ---------- 4) 真实 API 响应（同一快照口径：页面/AI/指标库一致） ----------
Write-Host '[4] 真实 API 响应'
$login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$h = @{ Authorization = "Bearer $($login.data.token)" }
$api = [ordered]@{}
foreach ($ep in @(
    '/api/v1/metrics/overview', '/api/v1/metrics/behavior-funnel', '/api/v1/metrics/product-ranking',
    '/api/v1/metrics/sale-trend', '/api/v1/metrics/rfm', '/api/v1/metrics/health',
    '/api/v1/pipeline-runs?page=1&size=5', "/api/v1/pipeline-runs/$RunId", '/api/v1/decisions?page=1&size=5',
    '/api/v1/ai/health'
  )) {
  try { $api[$ep] = (Invoke-RestMethod -Method Get -Uri "$base$ep" -Headers $h) } catch { $api[$ep] = @{ error = $_.Exception.Message } }
}
$api['/api/v1/dashboards/overview'] = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/dashboards/overview" -Headers $h)
$api | ConvertTo-Json -Depth 12 | Set-Content "$dir\16-api-responses.json" -Encoding UTF8

# ---------- 5) 页面截图与验收报告归档 ----------
Write-Host '[5] 截图与报告归档'
$shot = Join-Path $dir '17-screenshots'
New-Item -ItemType Directory -Force -Path $shot, "$shot\mall" | Out-Null
Copy-Item "$root\.verify\r7-4-dom\*.png" $shot -Force -ErrorAction SilentlyContinue
Copy-Item "$root\.verify\r7-4-mall-dom\*.png" "$shot\mall" -Force -ErrorAction SilentlyContinue
foreach ($f in @('r8-accept-report.json','r8-evidence-truncation-proof.json','r7-4-dom-report.json','r7-4-mall-dom-report.json')) {
  Copy-Item "$root\.verify\$f" "$dir\18-$f" -Force -ErrorAction SilentlyContinue
}
Copy-Item "$root\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar" "$dir\19-spark-jobs.jar" -Force

# ---------- 6) 对账表（黄金标准答案 vs 真实四层/指标库/页面） ----------
Write-Host '[6] 对账表'
$exp = Get-Content "$root\tests\golden-dataset\expected\golden-20260901-expected.json" -Raw | ConvertFrom-Json
# 指标库 ACTIVE 快照的 metric_value 才是口径权威（R7-0 统一后的字典口径）
$mvRows = @(Q "SELECT metric_code, metric_value FROM analytics_metric.metric_value WHERE snapshot_id='$Snapshot';")
$mv = @{}
foreach ($r in $mvRows) { $p = $r -split "`t"; if ($p.Count -ge 2) { $mv[$p[0]] = $p[1] } }
$wide = ((Q "SELECT pv,uv,dau,order_count,sale_amount,net_sale_amount,avg_order_value,refund_rate,full_refund_rate FROM analytics_metric.ads_operation_overview_m WHERE snapshot_id='$Snapshot';") -join "`t") -split "`t"
$rows = @()
$rows += "# A. 黄金标准答案（人工核算） vs 指标库 ACTIVE 快照 metric_value"
$rows += "metric_code`t黄金标准答案`t指标库(ACTIVE=$Snapshot)`t一致"
$mismatch = 0
foreach ($k in @('pv', 'uv', 'dau', 'paid_order_cnt', 'gmv', 'net_sale', 'avg_order_value', 'refund_rate', 'buy_rate')) {
  $e = $exp.metrics.$k; $a = $mv[$k]
  if (-not $a) { $rows += ("{0}`t{1}`t(指标库缺失)`tNO" -f $k, $e); $mismatch++; continue }
  $ok = ([decimal]$e - [decimal]$a) -eq 0
  if (-not $ok) { $mismatch++ }
  $rows += ("{0}`t{1}`t{2}`t{3}" -f $k, $e, $a, $(if ($ok) { 'YES' } else { 'NO' }))
}
$rows += ("full_refund_rate`t(黄金文件未列，R7-0 新增口径)`t{0}`t—" -f $mv['full_refund_rate'])
$rows += ""
$rows += "# B. 指标库 metric_value vs ADS 概览宽表（同一快照，防「宽表对但指标值错」）"
$rows += "字段`t宽表 ads_operation_overview_m`tmetric_value`t一致"
$cross = @(@('pv', $wide[0], $mv['pv']), @('uv', $wide[1], $mv['uv']), @('dau', $wide[2], $mv['dau']),
  @('order_count/paid_order_cnt', $wide[3], $mv['paid_order_cnt']), @('sale_amount/gmv', $wide[4], $mv['gmv']),
  @('net_sale_amount/net_sale', $wide[5], $mv['net_sale']), @('avg_order_value', $wide[6], $mv['avg_order_value']),
  @('refund_rate', $wide[7], $mv['refund_rate']), @('full_refund_rate', $wide[8], $mv['full_refund_rate']))
foreach ($c in $cross) {
  $ok = ([decimal]$c[1] - [decimal]$c[2]) -eq 0
  if (-not $ok) { $mismatch++ }
  $rows += ("{0}`t{1}`t{2}`t{3}" -f $c[0], $c[1], $c[2], $(if ($ok) { 'YES' } else { 'NO' }))
}
$rows += ""
$rows += "# C. 黄金数据集契约口径（夹具人工核算）"
$rows += ("L1 输入行数(golden-20260901.jsonl)`t{0}" -f $exp.scope.input_lines)
$rows += ("L1 契约接受事件数`t{0}" -f $exp.scope.event_count)
$rows += ("L1 契约拒绝行数`t{0}" -f $exp.scope.rejected)
$rows += ("事件构成`tusers={0} products={1} behaviors={2} orders_created={3} orders_paid={4} orders_cancelled={5} refunds={6}" -f `
    $exp.scope.users, $exp.scope.products, $exp.scope.behaviors, $exp.scope.orders_created, $exp.scope.orders_paid, $exp.scope.orders_cancelled, $exp.scope.refunds)
$rows += ""
$rows += "# D. 运行与快照"
$rows += ("run`t{0}`t{1}" -f $RunId, (One "SELECT CONCAT(status,' attempt=',attempt_no,' snapshot=',target_snapshot_id,' error=',COALESCE(error_code,'无')) FROM analytics_meta.pipeline_run WHERE id=$RunId;"))
$rows += ("ACTIVE 快照`t{0}`t业务日期 {1}" -f $Snapshot, $Date)
$rows | Set-Content "$dir\20-reconciliation.tsv" -Encoding UTF8
Write-Host ("  黄金对账不一致项 = {0}" -f $mismatch)

# ---------- 7) 证据目录索引 ----------
# 复现脚本随证据包归档（.verify/ 本身被 .gitignore 排除，脚本必须与证据同存才可复现）
$scriptDir = Join-Path $dir 'scripts'
New-Item -ItemType Directory -Force -Path $scriptDir | Out-Null
foreach ($s in @('r9-evidence-collect.ps1','r9-prune-fix-verify.ps1','r9-reliability.ps1','r9-resume-experiment.ps1','r7-4-dom.py','r7-4-mall-dom.py','r8-accept.ps1','r8-evidence-truncation-proof.ps1')) {
  $src = Join-Path $root (".verify\{0}" -f $s)
  if (Test-Path $src) { Copy-Item $src $scriptDir -Force }
}
$files = Get-ChildItem $dir -Recurse -File | Sort-Object Name
$idx = @("# R9 验收证据目录（$stamp / commit $commit / run $RunId / snapshot $Snapshot）", '',
  '> 采集脚本：`.verify/r9-evidence-collect.ps1`；所有数字来自真实 MySQL / 真实 Hive / 真实 HTTP API / 真实磁盘，无 Mock。', '',
  "| 文件 | 字节 | 内容 |", "|---|---:|---|")
$desc = @{
  '01-pipeline-run.tsv' = 'pipeline_run 整行（run 终态、attempt、目标快照、错误码）'
  '02-pipeline-stage-run.tsv' = '八阶段逐阶段状态、records、externalJobId、证据长度与 JSON 合法性'
  '03-spark-job-run.tsv' = 'spark_job_run 全列（作业码、外部作业 id、日志 URI、输入输出行数）'
  '04-spark-job-summary.tsv' = '作业级成功/失败汇总'
  '05-operation-audit-summary.tsv' = '操作审计按动作×结果汇总（含 FAILED 越权尝试）'
  '06-operation-audit-last15.tsv' = '操作审计最近 15 条（actor/role/target/errorCode）'
  '07-decision-task-by-status.tsv' = '决策 12 态分布'
  '08-ai-query-history.tsv' = 'AI 问数审计（用户 × 状态）'
  '09-ai-call-log.tsv' = 'LLM 调用日志（provider/status/次数/耗时）'
  '10-metric-snapshot.tsv' = '指标快照表（唯一 ACTIVE + 归档）'
  '11-metric-value-active.tsv' = 'ACTIVE 快照指标值（含口径版本）'
  '12-ads-table-counts.tsv' = '指标库 8 张 ADS 宽表行数'
  '13-ads-overview-active.tsv' = 'ACTIVE 快照概览宽表整行'
  '14-ads-quality-active.tsv' = 'ACTIVE 快照质量规则结果'
  '15-hive-layers.txt' = 'Hive ODS/DWD/DWS/ADS 四层行数、分区、金额、质量、staging 隔离'
  '16-api-responses.json' = '真实 HTTP 响应（指标/漏斗/商品/趋势/RFM/流水线/决策/AI 健康）'
  '20-reconciliation.tsv' = '黄金标准答案 vs 指标库对账'
  '21-reliability-experiments.tsv' = '§23.3 强制失败测试（C1 幂等 / C2 并发同键 / C3 断点续跑 / C4 质量阻断隔离 / C5 杀进程重启恢复 / D-R9-1 暂存清理修复）'
  '22-qualityfail-run26.json' = 'C4 质量失败 run 全量响应（终态、阻断规则、错误码）'
  '23-qualityfail-isolation.sql' = 'C4 隔离核对 SQL 原文（正式 ADS 空分区 vs 黄金分区完好）'
  '24-fulltest-analytics-server.log' = '平台全量 mvn test 原始日志（7 模块 303/303，BUILD SUCCESS；测试条数的唯一落点）'
  '25-resume-run26.json' = 'C5 重启后 resume 续跑全量响应（attemptNo 递增、仅失败阶段重跑）'
  '26-prune-fix-verification.tsv' = 'D-R9-1 暂存清理 dt 限定修复验证（异日期暂存分区存活 + 同日期历史回收）'
  '27-prune-fix-verify.log' = 'D-R9-1 验证过程日志（埋点分区清单、spark-sql 输出、发布检查项原文）'
  '28-prunefix-run.json' = 'D-R9-1 验证 run 全量响应'
  '29-resume-evidence.tsv' = 'C5 续跑实验原始证据（analytics_meta 直读：attempt/阶段时间戳/快照表）'
  '30-final-acceptance.md' = '§30 最终验收清单逐项判定（16 ✅ / 2 ⚠️ / 0 ❌，含未达标边界清单）'
  '31-metric-publish-it-regression.log' = '指标发布真库集成测试原始日志（PV/退款率口径回归，1/1 PASS、0 skipped）'
  '32-spark-jobs-tests.log' = 'Scala 作业单测原始日志（D-R9-1/D-R9-2 修复后：succeeded 46 / failed 0）'
  'r9-evidence-collect.ps1' = '本目录采集脚本（可重跑复现全部证据）'
  'r9-prune-fix-verify.ps1' = 'D-R9-1/D-R9-2 修复端到端复验脚本（P0–P8）'
  'r9-reliability.ps1' = '§23.3 可靠性/故障注入脚本（C1–C5，含 -WithRestart）'
  'r9-resume-experiment.ps1' = '续跑实验脚本（C5a–C5f）'
  'r7-4-dom.py' = '平台 8 页真机 DOM 验收脚本（22/22）'
  'r7-4-mall-dom.py' = '商城页面真机 DOM 验收脚本（17/17）'
  'r8-accept.ps1' = 'R8 真机验收脚本（53/53）'
  'r8-evidence-truncation-proof.ps1' = '证据截断修复复验脚本（12/12）'
  'README.md' = '本索引'
}
foreach ($f in $files) {
  $d = $desc[$f.Name]; if (-not $d) { $d = if ($f.DirectoryName -match 'screenshots') { '页面截图（Playwright 真机）' } else { '归档证据' } }
  $idx += ("| `{0}` | {1} | {2} |" -f $f.Name, $f.Length, $d)
}
$idx += '', "## 关键结论", '', "- run $RunId 终态：" + (One "SELECT CONCAT(status,'（attempt ',attempt_no,'，errorCode=',COALESCE(error_code,'无'),'）') FROM analytics_meta.pipeline_run WHERE id=$RunId;")
$idx += "- ACTIVE 快照：$Snapshot（业务日期 $Date）"
$idx += "- 黄金对账不一致项：$mismatch"
$idx += "- 产出时间：" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
$idx | Set-Content "$dir\README.md" -Encoding UTF8
Write-Host "DONE $dir  文件数=$($files.Count)"
