# =====================================================================
# P2-05 只读取证脚本（可复算）— ODS 重建守卫与失败保旧 ACTIVE
# 任务行：看板 V2.2 L224
#   P2-05 | ODS 重建守卫与失败保旧 ACTIVE | TODO | 总控/B | P2-01 |
#   备份清单、限定 namespace、审计、失败恢复。现状 EventOdsLoadJob.scala:121 仍直接 INSERT OVERWRITE（守卫未实现）。
#
# 纪律：本脚本**只读**。不写库、不写仓、不跑 Maven/Spark、不动 git 索引。
# 用法（仓库根，pwsh）： pwsh -File docs/acceptance/p2-05-ods-rebuild-guard-20260912/raw/collect-evidence-p2-05.ps1
# =====================================================================

$ErrorActionPreference = 'Continue'
Set-Location (git rev-parse --show-toplevel)

function Sec([string]$t) { Write-Output ""; Write-Output "===== $t =====" }

Write-Output "== GET-DATE (取证时点) =="
Get-Date -Format "yyyy-MM-dd HH:mm:ss"

# ---------------------------------------------------------------- R-01
Sec "R-01 EventOdsLoadJob.scala 落盘位置与 blob（INSERT OVERWRITE 调用点）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count) mtime=$((Get-Item $f).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Select-String -LiteralPath $f -Pattern 'INSERT OVERWRITE|spark\.sql\(sql\)|outputTables|ns\.ods|namespace|Namespace' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-02
Sec "R-02 实际 INSERT OVERWRITE 语句体（OdsLoadSql.scala insert 模板）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 155; $i -lt 200 -and $i -lt $c.Count; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }

# ---------------------------------------------------------------- R-03
Sec "R-03 spark-jobs 全域 INSERT OVERWRITE 清点（判断哪些层无暂存隔离）"
git grep -n -I "INSERT OVERWRITE" -- spark-jobs/src/main 2>&1

# ---------------------------------------------------------------- R-04
Sec "R-04 namespace 唯一所有者 WarehouseNamespace.scala（P2-05『限定 namespace』对象）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
Get-Content -LiteralPath $f -Encoding UTF8 | ForEach-Object -Begin { $i = 0 } -Process { $i++; "{0,4}: {1}" -f $i, $_ }

# ---------------------------------------------------------------- R-05
Sec "R-05 ODS 目标表分区规格（是否含 snapshot_id 暂存隔离）"
$f = 'warehouse/ddl/00-ods.sql'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
Select-String -LiteralPath $f -Pattern 'CREATE TABLE|PARTITIONED BY|raw_source_system|landing_file|payload_json' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-06
Sec "R-06 暂存发布路径：ADS 写 __staging 后由 PUBLISH_METRIC 发布（OMS 无此机制）"
git grep -n -I "__staging" -- analytics-server/warehouse-pipeline/src/main spark-jobs/src/main 2>&1
Write-Output "-- AdsSql 暂存/正式分区选择 --"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala'
Write-Output "blob=$(git hash-object $f)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 20; $i -lt 40 -and $i -lt $c.Count; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }

# ---------------------------------------------------------------- R-07
Sec "R-07 指导书 V2.4 §12.3 重建授权条文（P2-05 核心依据）"
$f = 'docs/项目完整实施指导书 V2.4.md'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 660; $i -lt 672; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }
Write-Output "-- §7.4 失败语义 L478-490 --"
for ($i = 477; $i -lt 490; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }
Write-Output "-- §8.4 审计环境激活 L574-578 --"
for ($i = 573; $i -lt 578; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }

# ---------------------------------------------------------------- R-08
Sec "R-08 INIT_SCHEMA 审计步骤在平台侧的落点（PipelineService）"
git grep -n -I "INIT_SCHEMA" -- analytics-server/warehouse-pipeline/src/main 2>&1
Write-Output "-- SparkStageExecutor 阶段→作业映射 --"
$f = 'analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/spark/SparkStageExecutor.java'
Write-Output "blob=$(git hash-object $f)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 35; $i -lt 60 -and $i -lt $c.Count; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }

# ---------------------------------------------------------------- R-09
Sec "R-09 既有审计载体 operation_audit_log（P2-05『审计』候选落点）"
$f = 'analytics-server/platform-app/src/main/resources/db/meta/V14__r8_identity_decision.sql'
Write-Output "blob=$(git hash-object $f)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 33; $i -lt 54; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }
Write-Output "-- 源登记域已定义的动作码（P1-03 SourceAuditActions）--"
$f = 'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/SourceAuditActions.java'
Write-Output "blob=$(git hash-object $f)"
Select-String -LiteralPath $f -Pattern 'ACTION_|RESOURCE_' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-10
Sec "R-10 迁移台账：现存 V 号与备份清单候选载体"
Get-ChildItem 'analytics-server/platform-app/src/main/resources/db/meta' -File | ForEach-Object { $_.Name }
Write-Output "-- V18（P2-07 源级前缀）是否已在真库执行：见 R-11 --"

# ---------------------------------------------------------------- R-11
Sec "R-11 真库只读读数（analytics_meta / analytics_metric）"
Write-Output "-- flyway_schema_history 头部（判断最大已落版本）--"
mysql --host=127.0.0.1 --user=root --password=123456 --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute="SELECT installed_rank, version, description, success, installed_on FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
Write-Output "-- source_registry（源级 namespace 前缀唯一权威）--"
mysql --host=127.0.0.1 --user=root --password=123456 --default-character-set=utf8mb4 --batch --raw --execute="SELECT id, source_code, warehouse_prefix, status, profile_version FROM analytics_meta.source_registry;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
Write-Output "-- metric_snapshot ACTIVE（旧 ACTIVE 可读性的对象）--"
mysql --host=127.0.0.1 --user=root --password=123456 --default-character-set=utf8mb4 --batch --raw --execute="SELECT id, snapshot_id, status, active_flag, pipeline_run_id FROM analytics_metric.metric_snapshot ORDER BY id DESC LIMIT 5;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
Write-Output "-- operation_audit_log 现有动作码分布（判断是否已有重建类审计码）--"
mysql --host=127.0.0.1 --user=root --password=123456 --default-character-set=utf8mb4 --batch --raw --execute="SELECT action, resource_type, COUNT(*) c FROM analytics_meta.operation_audit_log GROUP BY action, resource_type ORDER BY c DESC;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
Write-Output "-- 是否存在备份清单/重建审计类表 --"
mysql --host=127.0.0.1 --user=root --password=123456 --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute="SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND (TABLE_NAME LIKE '%backup%' OR TABLE_NAME LIKE '%manifest%' OR TABLE_NAME LIKE '%audit%' OR TABLE_NAME LIKE '%rebuild%');" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
