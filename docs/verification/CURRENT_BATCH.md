# Current Verification Batch

> 状态：**READY — Batch T-R2 待执行，VERIFY_CURRENT_BATCH 开放**（2026-09-19，总控 APPROVED「mxp 合法 0 行 ADS 导出修复」后按其 READY 条件开放）。
> T-R1 已登记 `FAIL_PRODUCTION_RUN_PUBLISH_FAILED`（mxp 对 0 行暂存分区直读抛 `UNABLE_TO_INFER_SCHEMA`）；修复（D-020）已按总控边界落地并经 developer tests + 三档回归验证；T-R1 结果（`d28afa2`）与修复提交（`1ae091b`）均已真正落到 origin/feature/v3-development。

## Current batch

- **Batch ID**：`BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`1ae091b2b118df03632a799ffb9fb664f19a3eda`（mxp 合法 0 行导出修复 + MetricExportZeroRowSpec + spark 基线 312→320，D-020）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **Predecessor**：Batch T-R1 / `FAIL_PRODUCTION_RUN_PUBLISH_FAILED`（result：`docs/verification/results/BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`，attempt `attempt-20260919_094931_381` / pipeline runId=8）
- **Pinned S-R1 producer evidence**：`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`（1011 unique / 0 duplicate，SHA256 `f32906…bbcc`，businessDate 2026-09-18，mock-mall）
- **Overall**：`READY`（总控裁决原文：「APPROVED：mxp 合法 0 行 ADS 导出修复。T-R2：条件批准，待修复 + developer tests + 回归完成，并将 T-R1 结果和修复提交真正落到远端后，再置 READY。禁止把合法空态扩大成"所有 schema 读取失败都按空表处理"。」）

## mxp 修复与三档回归（2026-09-19，T-R2 前置已全部满足）

**修复（D-020，提交 `1ae091b`）**：`MetricExportJob` 导出循环以 `PartitionEvidence.collect` 的 metastore `COUNT(*)` 为判据——仅当该 snapshot+dt 分区存在且真实 0 行（hiveRows==0）时，改走 `spark.table(...).select(契约列).limit(0)` catalog-backed 空态导出（真实 0 字节 JSONL、manifest rowCount=0、checksum="0"）；非 0 行与分区缺失（countOf 缺项记 -1）一律保持 `spark.read.parquet` 物理读取路径 fail-closed；无任何 catch-跳过。发布侧既有设计兼容合法空表：`MP_REQUIRED_TABLES_NONEMPTY` 只约束概览/趋势/漏斗/活跃四表。

**developer tests**：新增 `MetricExportZeroRowSpec` **8/8**——0 行 pub 放行（v2 `PUB_STAGING_READY` 点名 0 行专题）、mxp 空态导出整体 SUCCESS（修复前此处抛 UNABLE_TO_INFER_SCHEMA）、负向对照（0 行分区物理读取确实抛 UNABLE_TO_INFER_SCHEMA，证明空态分支是被测路径）、0 行表 MXP_EXPORT_ROWS 为 hive=0 export=0（对账而非跳过）、0 字节 JSONL/rowCount=0/checksum="0"、非 0 行 6 表不回归、manifest 总账。

**三档回归**：

- spark：fresh **320/320**（39 套件，JDK8，RunId `devmxpfull_20260919_1046`，DRIFT 放行量数）→ 基线 312→320 补记 → MATCH PASS exit=0（`devmxpmatch_20260919_1050`）；
- default：analytics **1036 MATCH**（F=1 为既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）/ mall **14 MATCH** / generator **111 MATCH**（RunId `devmxpdef_20260919_1055`，合计 1161=基线）；
- isolated：fresh runId `tir2iso_20260919_105926` **60/60**（mall 30 + generator 19 + analytics 11 = IsolationGuard 6 + MetricAds 2 + MetricPublisher 3），五路门禁 6 探针全 OK（实例 UUID pin 匹配），口令按 T-R1 先例由幂等 prep 以进程内新生成口令重置 run 账号（只走 PowerShell Process env）。

**边界不变**：3306 未触碰、未改 V1~V28、未改质量阈值、未 force push；空态语义未被扩大（分区缺失/路径丢失/schema 损坏仍 fail-closed）。

## T-R2 PASS criteria（摘要，全文见 plan §5）

ingestion 1011/0 quarantine → Pipeline 全链 SUCCESS **through PUBLISH_METRIC** → 0 行表真实空 JSONL（rowCount=0/checksum="0"）+ 非 0 行表逐表对账 → MetricPublisher 消费 manifest → outer harness exit 0。任何失败按 evidence-only 分类（production FAIL vs BLOCKED_ENV vs harness issue），不猜测；mxp 若再失败**不得**把读取失败当空表。

## Accepted Batch T facts（钉死 producer 输入的既有证据）

Exact tested SHA `cbc41919df79bba20e1f91fe1724061ae151d12e`（Batch T）:

- producer input physical SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- sourceLineCount = 1011; uniqueEventIdCount = 1011; duplicateEventIdCount = 0;
- businessDate = 2026-09-18; source_system = mock-mall;
- ingestion SUCCESS：recordCount = 1011，quarantineCount = 0，errorCount = 0，noNewData = false.

Pipeline `runId=5` 真实启动并推进至 BUILD_DWS 后平台进程消失（无 ExitCode 证据）→ Batch T 记 `BLOCKED_PLATFORM_EXIT_UNDIAGNOSED`，由此催生 T-R1 诊断门。

## T-R1 实际结果（2026-09-19 登记，保持为 T-R2 的 predecessor 事实）

- 平台全程存活：PID 39964 `hasExited=false`、pollErrorCount=0——Batch T 的消失未复现，harness 诊断增强按设计工作；
- 链路真实推进：WAIT_LANDING/INIT_SCHEMA/LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS/QUALITY_CHECK 全 SUCCESS（v2 修复被实链验证：`PUB_STAGING_READY` 放行 0 行专题），pub 作业 SUCCESS、8 张正式指针切换完成；
- 失败点：PUBLISH_METRIC 的 mxp exit=1 `[UNABLE_TO_INFER_SCHEMA]`——0 行暂存分区目录只有 `_SUCCESS` 无 parquet 文件，`MetricExportJob.scala:78` 直读路径文件级 schema 推断失败；
- 分类：**production FAIL**（`FAIL_PRODUCTION_RUN_PUBLISH_FAILED`），非 BLOCKED_ENV、非 harness issue；该事件由 T-R2 的同链复跑关闭。

## After T-R2

T-R2 PASS → 关闭 T-R1 事件并开放 **BATCH-U**（Flume→HDFS，现为 DRAFT）。T-R2 通过仍不证明：REMOTE_CLUSTER、浏览器 E2E、真实 LLM。
