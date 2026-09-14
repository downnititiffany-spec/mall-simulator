<#
V25-E3 | 从**隔离库（3307）**只读导出编排/质量证据（pipeline_run / stage / spark_job_run /
        data_quality_result / metric_snapshot / metric_value / ingestion_batch）。

为什么直连而非走接口：PipelineController 只暴露 run 列表/详情，没有 stage/job/规则子资源；
本脚本**纯 SELECT**，且只连 3307（端口不符直接抛错，不连接）。

用法：pwsh -File <this>
#>
param(
  [string]$DbHost = '127.0.0.1',
  [int]$DbPort = 3307,
  [string]$MetaDb = 'analytics_meta_v25it_20260914_1358_l4e3',
  [string]$MetricDb = 'analytics_metric_v25it_20260914_1358_l4e3',
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutName = '21-isolated-meta-evidence.txt',
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($DbPort -ne 3307) { throw "本脚本只允许 3307（当前 $DbPort）" }

$out = Join-Path $EvidenceRoot "raw\$OutName"
$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
function Q([string]$title, [string]$sql) {
  "`n### $title" | Add-Content $out
  "SQL: $sql" | Add-Content $out
  & $MysqlExe "--host=$DbHost" "--port=$DbPort" --user=root -t -e $sql 2>&1 | Add-Content $out
}
try {
  "V25-E3 隔离库只读证据导出  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  实例 port=$DbPort" | Set-Content $out
  Q 'pipeline_run' "SELECT id,pipeline_code,business_time,source_data_version,input_batch_id,status,current_stage,error_code,attempt_no,target_snapshot_id,started_at,finished_at FROM $MetaDb.pipeline_run ORDER BY id;"
  Q 'pipeline_stage_run' "SELECT id,run_id,stage_code,status,records,COALESCE(error_code,'') AS error_code,LEFT(COALESCE(evidence,''),160) AS evidence_head FROM $MetaDb.pipeline_stage_run ORDER BY id;"
  Q 'spark_job_run' "SELECT id,pipeline_run_id,stage_code,job_code,status,input_records,output_records,rejected_records,submitter_type,COALESCE(error_code,'') AS error_code FROM $MetaDb.spark_job_run ORDER BY id;"
  Q 'spark_job_run 提交参数（arguments_json，看 --master/--businessDate）' "SELECT id,job_code,LEFT(arguments_json,400) AS args FROM $MetaDb.spark_job_run ORDER BY id;"
  Q 'data_quality_result（F-88 关注）' "SELECT id,run_id,layer,rule_code,severity,passed,check_count,error_count,threshold,LEFT(COALESCE(detail,''),160) AS detail FROM $MetaDb.data_quality_result ORDER BY id;"
  Q 'data_quality_result 严重度落地分布' "SELECT layer,rule_code,severity,passed,COUNT(*) AS rows_cnt FROM $MetaDb.data_quality_result GROUP BY layer,rule_code,severity,passed ORDER BY layer,rule_code;"
  Q 'data_quality_result 表结构（F-88：有无 rule_version/effective_severity/rule_fingerprint 列）' "SELECT ORDINAL_POSITION,COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$MetaDb' AND TABLE_NAME='data_quality_result' ORDER BY ORDINAL_POSITION;"
  Q 'quarantine_record' "SELECT * FROM $MetaDb.quarantine_record ORDER BY id LIMIT 20;"
  Q 'metric_definition 条数' "SELECT COUNT(*) AS metric_defs FROM $MetaDb.metric_definition;"
  Q 'metric_snapshot' "SELECT snapshot_id,status,source,pipeline_run_id,created_at FROM $MetricDb.metric_snapshot ORDER BY created_at;"
  Q 'metric_value 按快照计数' "SELECT snapshot_id,COUNT(*) AS rows_cnt,COUNT(DISTINCT metric_code) AS metrics FROM $MetricDb.metric_value GROUP BY snapshot_id ORDER BY snapshot_id;"
  Q 'metric_value 样本（前 20 行）' "SELECT snapshot_id,metric_code,period,dimension_key,metric_value,definition_version FROM $MetricDb.metric_value ORDER BY id LIMIT 20;"
  Q 'ingestion_batch' "SELECT id,batch_no,source,status,record_count,error_count,quarantine_count,source_id FROM $MetaDb.ingestion_batch ORDER BY id;"
} finally { $env:MYSQL_PWD = $old }
Write-Host "证据已写 $out"
