# =====================================================================
# P2-04 只读取证脚本（可复算）— DWD 从 SourceProfile 投影
# 任务行：看板 V2.2 L223
#   P2-04 | DWD 从 SourceProfile 投影 | TODO | B | P2-02/03 |
#   缺字段显式 NULL+DQ；禁止通用 CAST BIGINT。现状 DwdSql.scala:31 仍 PARTITION BY event_id。
#
# 纪律：本脚本**只读**。不写库、不写仓、不跑 Maven/Spark、不动 git 索引。
# 用法（仓库根，pwsh）： pwsh -File docs/acceptance/p2-04-dwd-source-projection-20260912/raw/collect-evidence-p2-04.ps1
# 取证时点必须与脚本首行 Get-Date 输出同时阅读（D-091：行号/指纹是取证时点读数）。
# =====================================================================

$ErrorActionPreference = 'Continue'
Set-Location (git rev-parse --show-toplevel)

function Sec([string]$t) { Write-Output ""; Write-Output "===== $t =====" }

Write-Output "== GET-DATE (取证时点) =="
Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Output "== PWD =="
(Get-Location).Path

# ---------------------------------------------------------------- R-01
Sec "R-01 git 工作区状态（含并发写入者）"
git status --porcelain=v1

# ---------------------------------------------------------------- R-02
Sec "R-02 HEAD 与分支"
git rev-parse HEAD
git rev-parse --abbrev-ref HEAD

# ---------------------------------------------------------------- R-03
Sec "R-03 spark-jobs/** 被并发写入文件的 blob sha + mtime（行号可能漂移）"
foreach ($f in @(
    'spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala',
    'spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala'
  )) {
  if (Test-Path $f) {
    $blob = git hash-object $f
    $m = (Get-Item $f).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    $n = (Get-Content -LiteralPath $f -Encoding UTF8).Count
    Write-Output "$f`tblob=$blob`tmtime=$m`tlines=$n"
  } else { Write-Output "$f`tMISSING" }
}

# ---------------------------------------------------------------- R-04
Sec "R-04 DwdSql.scala 去重键（worktree 现状，行号以本时点为准）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala'
Select-String -LiteralPath $f -Pattern 'PARTITION BY|rn\.rn|event_id|source_system|user_key|JOIN|ON ' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-05
Sec "R-05 DwdSql.scala 在 HEAD（工作基线，D-091 Q17）中的同项行号"
git show HEAD:spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala |
  Select-String -Pattern 'PARTITION BY|rn\.rn|event_id|source_system|JOIN|ON ' |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }
Write-Output "HEAD blob = $(git rev-parse HEAD:spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala)"

# ---------------------------------------------------------------- R-06
Sec "R-06 HEAD 版 DwdSql.scala 全文（P2-04 施工面：投影位、JOIN 谓词、去重键）"
git show HEAD:spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala |
  ForEach-Object -Begin { $i = 0 } -Process { $i++; "{0,4}: {1}" -f $i, $_ }

# ---------------------------------------------------------------- R-07
Sec "R-07 source_instance_id 在 spark-jobs / warehouse 的命中（含阳性对照）"
Write-Output "-- 目标检索：source_instance_id --"
$hits = git grep -n -I "source_instance_id" -- spark-jobs warehouse 2>&1
if ($hits) { $hits } else { Write-Output "(零命中 —— 见紧随其后的阳性对照)" }
Write-Output "-- 阳性对照：同一命令形态检索 source_system（证明工具不假零）--"
git grep -c -I "source_system" -- spark-jobs 2>&1 | Select-Object -First 8

# ---------------------------------------------------------------- R-08
Sec "R-08 DWD 目标表 DDL 列序（warehouse/ddl/01-dwd.sql）"
$f = 'warehouse/ddl/01-dwd.sql'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
$c = Get-Content -LiteralPath $f -Encoding UTF8
for ($i = 0; $i -lt $c.Count; $i++) { "{0,4}: {1}" -f ($i + 1), $c[$i] }

# ---------------------------------------------------------------- R-09
Sec "R-09 ODS v2 实际落库列集（类级唯一所有者 OdsV2Columns.scala）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala'
Write-Output "blob=$(git hash-object $f)"
Select-String -LiteralPath $f -Pattern 'V2NewColumns|CommonColumns|V1Shared|AuditColumns|raw_source_system|dataColumns|v1Columns' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-10
Sec "R-10 ODS 建表列序（warehouse/ddl/00-ods.sql）—— 冻结 4 列 vs 实现 5 列"
$f = 'warehouse/ddl/00-ods.sql'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
Select-String -LiteralPath $f -Pattern 'raw_event_type|raw_source_system|landing_file|payload_json|payload_hash|PARTITIONED BY|CREATE TABLE' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }

# ---------------------------------------------------------------- R-11
Sec "R-11 IdCodec 通用 CAST BIGINT 现状（P2-04 要求禁止通用 CAST）"
$f = 'spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
Get-Content -LiteralPath $f -Encoding UTF8 | ForEach-Object -Begin { $i = 0 } -Process { $i++; "{0,4}: {1}" -f $i, $_ }

# ---------------------------------------------------------------- R-12
Sec "R-12 全仓 CAST(... AS BIGINT) 形态清点（spark-jobs 主源）"
git grep -n -I -E "CAST\([^)]*AS BIGINT" -- spark-jobs/src/main 2>&1

# ---------------------------------------------------------------- R-13
Sec "R-13 SourceProfile 概念在 spark-jobs 的命中（含阳性对照）"
Write-Output "-- 目标检索：SourceProfile / source-profile / sourceProfile --"
$a = git grep -n -I -E "SourceProfile|source-profile|sourceProfile" -- spark-jobs 2>&1
if ($a) { $a } else { Write-Output "(零命中 —— 见阳性对照)" }
Write-Output "-- 阳性对照：同形态检索 sourceSystem --"
git grep -c -I "sourceSystem" -- spark-jobs 2>&1 | Select-Object -First 8

# ---------------------------------------------------------------- R-14
Sec "R-14 契约侧去重语义（CT-2 = D-062 已落盘）"
$f = 'docs/contracts/event-contract.md'
Write-Output "blob=$(git hash-object $f)"
Select-String -LiteralPath $f -Pattern '去重' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }
$f = 'contract-specs/schemas/canonical-event.v1.schema.json'
Write-Output "blob=$(git hash-object $f)"
Select-String -LiteralPath $f -Pattern 'source_system|去重' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim().Substring(0, [Math]::Min(220, $_.Line.Trim().Length)) }

# ---------------------------------------------------------------- R-15
Sec "R-15 指导书 V2.4 权威条文（§2.1 权威顺序 / §5.1 去重键 / §7.3-7.4 质量与快照）"
$f = 'docs/guidance/history/项目完整实施指导书 V2.4.md'
Write-Output "blob=$(git hash-object $f) lines=$((Get-Content -LiteralPath $f -Encoding UTF8).Count)"
Select-String -LiteralPath $f -Pattern 'source_instance_id|去重|ACTIVE' -Encoding UTF8 |
  ForEach-Object { "L{0}: {1}" -f $_.LineNumber, $_.Line.Trim().Substring(0, [Math]::Min(240, $_.Line.Trim().Length)) }
