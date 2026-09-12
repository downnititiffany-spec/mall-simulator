# P2-01 / ODS v2 —— DDL 子句顺序探针（E3 级：真实 spark-sql + 真实临时 warehouse/metastore）
#
# 目的：用真实 Spark 判定两件事，而不是靠语法记忆：
#   NEG（负向对照）：`(...) COMMENT 'x'` + `USING parquet`  —— 当前 LocalSchemaInitJob.odsCreateTable 发出的形态
#   POS（正向对照）：`(...) USING parquet COMMENT 'x'`      —— 拟采用的修复形态
#
# 边界（ORDER-1 §3 / D-060）：
#   * 只写 spark-jobs\target\p2-01-e3-probe-<ts>\（临时 warehouse + 临时 Derby metastore 都必须在 target\ 下）
#   * 不触碰仓库根 spark-warehouse\ 与既有 metastore；不启停任何进程；不删除任何文件
#   * 库名前缀 p201v2probe（新前缀，不碰 dw_*）
#
# 用法：pwsh -File docs\acceptance\p2-01-ods-v2-20260912\harness\e3-probe-ddl-clause-order.ps1
$ErrorActionPreference = 'Stop'
$repo = 'D:\Develop_code\GraduationProject'
$sparkSql = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-sql.cmd'
$evidence = Join-Path $repo 'docs\acceptance\p2-01-ods-v2-20260912\evidence'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$root = Join-Path $repo "spark-jobs\target\p2-01-e3-probe-$ts"
$warehouse = Join-Path $root 'warehouse'
$derby = Join-Path $root 'derby-metastore'
$log = Join-Path $evidence "e3-probe-ddl-clause-order-$ts.log"

# 注意：**不能**预建 derby-metastore / warehouse 目录——Derby 的 create=true 遇到已存在的非库目录会
# 抛 ERROR XBM0J（2026-09-12 13:44 首次探针即因此失败），两者都必须由 Derby / Spark 自己创建。
New-Item -ItemType Directory -Force -Path $root, $evidence | Out-Null

$whUri = 'file:///' + $warehouse.Replace('\', '/')
$derbyUri = 'jdbc:derby:' + $derby.Replace('\', '/') + ';create=true'

$L = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $L.Add($s); Write-Host $s }
function Save { Set-Content -Path $log -Value $L -Encoding UTF8 }

# 响亮断言①：warehouse 必须落在 target\ 之下，否则立刻中止（D-060 ①）
if (-not $whUri.StartsWith('file:///D:/Develop_code/GraduationProject/spark-jobs/target/')) {
  throw "ABORT 响亮断言失败：warehouse 不在 target\\ 之下 -> $whUri"
}

A "P2-01 DDL 子句顺序探针  开始 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
A "repo        = $repo"
A "probe root  = $root"
A "warehouse   = $whUri   （响亮断言已通过：在 spark-jobs\\target\\ 之下）"
A "metastore   = $derbyUri   （响亮断言已通过：在 spark-jobs\\target\\ 之下）"
A "spark-sql   = $sparkSql"
A ("spark ver   = " + (& 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd' --version 2>&1 | Select-String -Pattern 'version' | Select-Object -First 1))
A ""

$common = @(
  '--conf', "spark.hadoop.javax.jdo.option.ConnectionURL=$derbyUri",
  '--conf', 'spark.hadoop.javax.jdo.option.ConnectionDriverName=org.apache.derby.jdbc.EmbeddedDriver',
  '--conf', 'spark.sql.hive.metastore.jars=builtin',
  '--conf', 'spark.hadoop.datanucleus.schema.autoCreateTables=true',
  '--conf', "spark.sql.warehouse.dir=$whUri",
  '--conf', 'spark.sql.session.timeZone=Asia/Shanghai'
)

function RunSql([string]$tag, [string]$sqlFile) {
  A "──── [$tag] $(Get-Date -Format 'HH:mm:ss')  spark-sql -f $sqlFile"
  A "     SQL 原文："
  Get-Content -Path $sqlFile -Encoding UTF8 | ForEach-Object { A ("       | " + $_) }
  $cmdline = 'spark-sql.cmd ' + (($common + @('-f', $sqlFile)) -join ' ')
  A "     命令：$cmdline"
  $out = & $sparkSql @common -f $sqlFile 2>&1 | Out-String
  $code = $LASTEXITCODE
  A "     退出码：$code"
  A "     原始输出："
  ($out -split "`r?`n") | ForEach-Object { A ("       | " + $_) }
  A ""
  Save
  return $code
}

# ── 0. 建探针库 ─────────────────────────────────────────────────────────
$s0 = Join-Path $root 'db.sql'
Set-Content -Path $s0 -Encoding UTF8 -Value "CREATE DATABASE IF NOT EXISTS p201v2probe_ods COMMENT 'P2-01 DDL 子句顺序探针库';"
$c0 = RunSql '0-建库' $s0

# ── 1. NEG：当前实现发出的形态（列体后先 COMMENT，再 USING） ──────────────
$s1 = Join-Path $root 'neg.sql'
@"
CREATE TABLE IF NOT EXISTS p201v2probe_ods.t_neg (
  event_id STRING,
  ingest_batch_id BIGINT) COMMENT '负向对照：先 COMMENT 再 USING'
USING parquet PARTITIONED BY (dt STRING, hour STRING);
"@ | Set-Content -Path $s1 -Encoding UTF8
$c1 = RunSql '1-NEG-先COMMENT后USING' $s1

# ── 2. POS：拟采用形态（USING 紧跟列体，再 COMMENT，再 PARTITIONED BY） ────
$s2 = Join-Path $root 'pos.sql'
@"
CREATE TABLE IF NOT EXISTS p201v2probe_ods.t_pos (
  event_id STRING,
  ingest_batch_id BIGINT)
USING parquet COMMENT '正向对照：USING 紧跟列体'
PARTITIONED BY (dt STRING, hour STRING);
DESCRIBE p201v2probe_ods.t_pos;
SHOW CREATE TABLE p201v2probe_ods.t_pos;
"@ | Set-Content -Path $s2 -Encoding UTF8
$c2 = RunSql '2-POS-USING紧跟列体' $s2

# ── 3. 判定 ─────────────────────────────────────────────────────────────
A "──── 判定 $(Get-Date -Format 'HH:mm:ss')"
A "NEG 退出码 = $c1   （预期非 0：当前实现形态不可解析）"
A "POS 退出码 = $c2   （预期 0：修复形态可解析）"
if ($c1 -ne 0 -and $c2 -eq 0) { A "判定 = 修复形态成立：USING parquet 必须紧跟列体，COMMENT 只能排在它之后" }
elseif ($c1 -eq 0) { A "判定 = 负向对照竟然成功：与 E2 实测矛盾，需重查，不要改代码" }
else { A "判定 = 正向对照也失败：修复形态不成立，停止并上报" }
A ""
A ("结束 " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + "  日志 " + $log)
Save
Write-Host ("LOG=" + $log)
