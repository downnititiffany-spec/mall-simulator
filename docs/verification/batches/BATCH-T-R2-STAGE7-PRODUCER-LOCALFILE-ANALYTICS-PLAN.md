# BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Verification Plan

> 状态：**READY**
> Exact source/test SHA：`1ae091b2b118df03632a799ffb9fb664f19a3eda`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：Batch T-R1 / `FAIL_PRODUCTION_RUN_PUBLISH_FAILED`
> 批准依据（2026-09-19）：总控裁决「**APPROVED：mxp 合法 0 行 ADS 导出修复。T-R2：条件批准，待修复 + developer tests + 回归完成，并将 T-R1 结果和修复提交真正落到远端后，再置 READY。禁止把合法空态扩大成"所有 schema 读取失败都按空表处理"。**」前置条件已全部满足：修复（D-020）提交 `1ae091b`（MetricExportJob.scala catalog-backed 空态分支 + 新增 developer spec `MetricExportZeroRowSpec` **8/8** + spark 基线 312→320）；三档回归 spark fresh **320/320** → MATCH PASS（RunId `devmxpfull_20260919_1046` / `devmxpmatch_20260919_1050`）、default analytics **1036 MATCH** / mall 14 / generator 111（`devmxpdef_20260919_1055`，唯一红=既有 manifest patrol）、isolated fresh runId `tir2iso_20260919_105926` **60/60**；T-R1 结果（`d28afa2`）与修复提交（`1ae091b`）均已真正落到 origin/feature/v3-development，CURRENT_BATCH.md 据此置 READY。
> 口令通道注记（与 T-R1 相同）：执行前若进程环境口令缺失，由幂等 prep 重跑（`it-prepare-isolation.ps1`，ALTER USER 语义）以进程内新生成口令重置 run 账号，口令仍只走 PowerShell Process env、不落盘不进命令行。

## 1. Purpose

Repeat the exact pinned producer rolling → analytics chain after the mxp legal-empty-state export fix (D-020).

T-R1 已证明 WAIT_LANDING→QUALITY_CHECK 全 SUCCESS、失败点被精确定位在 mxp 对 0 行暂存分区的 `spark.read.parquet` 直读（`[UNABLE_TO_INFER_SCHEMA]`）。本门证明修复后全链闭环：

1. 0 行暂存分区（ads_hot_product、ads_product_conversion）由 catalog-backed 空态路径导出真实空 JSONL（manifest rowCount=0、checksum="0"），不再抛 UNABLE_TO_INFER_SCHEMA；
2. 非 0 行 6 表保持既有物理读取路径正常导出（不回归）；
3. MetricPublisher 正常消费含空表的 manifest 并完成对账/激活，PUBLISH_METRIC SUCCESS，harness exit 0。

## 2. Pinned producer input

Do **not** use the mutable producer latest pointer for this rerun.

Pin（与 T-R1 完全相同）：

`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`

That evidence already proves:

- lineCount = 1011;
- uniqueEventIdCount = 1011;
- duplicateEventIdCount = 0;
- physical rolling SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- source_system = mock-mall;
- businessDate = 2026-09-18.

## 3. Safety and exact runtime

- 3307 only;
- no 3306 fallback;
- fresh run-scoped analytics meta/metric databases (幂等 prep 可重建)；
- secrets only from current PowerShell Process env；
- 8091 must be free;
- fresh LocalFile attempt / fresh HTTP attempt / fresh Pipeline idempotency key;
- exact analytics platform and Spark JARs rebuilt from the detached T-R2 SHA;
- no quality threshold change;
- no Fake executor;
- no reuse of Pipeline runId 5/8.

## 4. Exact execution

Run in the PowerShell process that owns `V25_IT_META_PASSWORD` and `V25_IT_METRIC_PUBLISH_PASSWORD`（缺失时先按 T-R1 先例做幂等 prep 重跑，口令只走进程 env）:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

git switch --detach 1ae091b2b118df03632a799ffb9fb664f19a3eda
git rev-parse HEAD

mvn -f .\analytics-server\pom.xml -DskipTests package
mvn -f .\spark-jobs\pom.xml -DskipTests package

pwsh -NoProfile -File .\scripts\stage7-localfile-e2e.ps1 -RunId stage7q1_20260918_152245 -ProducerEvidence ".\target\v25-it\stage7q1_20260918_152245\producer\attempt-20260918_195657_077\stage7-producer-result.json" -Confirm

$BatchTR2Exit = $LASTEXITCODE

git switch feature/v3-development

"BATCH_T_R2_EXIT=$BatchTR2Exit"
~~~

## 5. PASS criteria

Same Batch T contract, extended by the zero-row export assertions:

- physical producer input still 1011 unique / 0 duplicate;
- businessDate = 2026-09-18;
- ingestion recordCount = 1011;
- quarantineCount = 0;
- noNewData = false;
- fresh Pipeline run reaches SUCCESS **through PUBLISH_METRIC**（T-R1 已证到 QUALITY_CHECK；本门必须全链）;
- quality gate PASS（v2 `PUB_STAGING_READY` 放行 0 行专题照旧）;
- mxp 导出 8 表全部完成：0 行表（ads_hot_product、ads_product_conversion）产出真实空 JSONL 且 manifest `rowCount=0`、`checksum="0"`；非 0 行表 rowCount 与 Hive 分区行数逐表对账一致；manifest columns 与正式 ADS 契约一致;
- MetricPublisher 消费 manifest：`MP_EXPORT_*` / `MP_ADS_ROWS_MATCH` 等检查通过，0 行表按 0=0 对账（`MP_REQUIRED_TABLES_NONEMPTY` 只约束概览/趋势/漏斗/活跃四表）；
- outer harness exit 0 / outcome PASS。

## 6. Classification

Do not classify from the console line alone — use evidence only（同 T-R1 §6：platform PID/exitCode/resource 快照 + 每次 poll 的 Pipeline stage/status + Spark/platform 日志 → production FAIL vs BLOCKED_ENV vs harness issue）。

特别地：若 mxp 再次失败，按 production FAIL 登记完整异常与 MXP_EXPORT_* 检查明细；**不得**把读取失败静默当成空表处理（该扩大语义已被总控明令禁止）。

## 7. After PASS

T-R2 PASS 关闭 T-R1 的 `FAIL_PRODUCTION_RUN_PUBLISH_FAILED` 事件（同链复跑成功即证明该缺口被修复关闭），并开放 BATCH-U（Flume→HDFS，现为 DRAFT）。T-R2 通过仍不证明：REMOTE_CLUSTER、浏览器 E2E、真实 LLM。
