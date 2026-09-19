# Current Verification Batch

> 状态：**production FAIL（MP_ADS_WRITE）— Batch T-R2 共 4 次 attempt（前 3 次 BLOCKED_ENV 已由环境修复解除；第 4 次全链真实执行到发布第二步后被新缺陷阻断），VERIFY_CURRENT_BATCH 保持开放**。
> T-R1 已登记 `FAIL_PRODUCTION_RUN_PUBLISH_FAILED`（mxp 对 0 行暂存分区直读抛 `UNABLE_TO_INFER_SCHEMA`）；被测修复（D-020，`1ae091b`）**已在实链证实其声明范围**（mxp SUCCESS、两张 0 行表 hive=0 export=0、manifest 被消费），T-R1 所指缺陷证实修复。
> **新失败点（2026-09-19 13:30 登记）**：metric 库历史迁移 V3 的 `ads_data_quality_m.error_rate NOT NULL` 拒绝 D-019 合法空态语义的 `error_rate=NULL` 行——`MetricAdsWriter` 插入时 `DataIntegrityViolationException`，事务回滚 42 行，pipeline 11 终态 FAILED。修复建议（追加式 V11 迁移使 error_rate 可空）已交总控/用户裁决（结果文档 §12），批准后走 T-R3。

## Current batch

- **Batch ID**：`BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`1ae091b2b118df03632a799ffb9fb664f19a3eda`（mxp 合法 0 行导出修复 + MetricExportZeroRowSpec + spark 基线 312→320，D-020）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **Predecessor**：Batch T-R1 / `FAIL_PRODUCTION_RUN_PUBLISH_FAILED`（result：`docs/verification/results/BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`，attempt `attempt-20260919_094931_381` / pipeline runId=8）
- **Pinned S-R1 producer evidence**：`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`（1011 unique / 0 duplicate，SHA256 `f32906…bbcc`，businessDate 2026-09-18，mock-mall）
- **Overall**：`FAIL_PRODUCTION_RUN_PUBLISH_FAILED（MP_ADS_WRITE）`（4 次 attempt 的最终终态，2026-09-19 登记；开放时为 READY，总控裁决原文：「APPROVED：mxp 合法 0 行 ADS 导出修复。T-R2：条件批准，待修复 + developer tests + 回归完成，并将 T-R1 结果和修复提交真正落到远端后，再置 READY。禁止把合法空态扩大成"所有 schema 读取失败都按空表处理"。」）

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

## T-R2 实际结果（2026-09-19 登记：4 次 attempt，最终 production FAIL，详见结果文档）

结果：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`（Exact SHA `1ae091b`，RunId `stage7q1_20260918_152245`，harness exit 7 ×4）。

| # | attempt | outcome | 失败点 |
|---|---|---|---|
| 1 | `attempt-20260919_112031_598` | PIPELINE_TIMEOUT | QUALITY_CHECK：dqc JVM 提交后 0-CPU 挂起（7 作业 SUCCESS 后第 8 个挂；当时无 jstack，签名归因） |
| 2 | `attempt-20260919_113916_916` | 平台未就绪 | 平台 JVM 出生 ~3s 被 CTRL_C 杀死（exitCode -1073741510，stderr 0 字节；attempt-3 同条件存活证伪其可复现性 → 瞬时环境信号） |
| 3 | `attempt-20260919_115603_058` | PIPELINE_TIMEOUT | INIT_SCHEMA：driver JVM（pid 58920）SparkContext 初始化中 jar 自下载永久阻塞——3 份 jstack 证明 main 线程卡在 `NettyRpcEnv$FileDownloadChannel.read → SocketDispatcher.read0`，CPU 计数 6.5 分钟零变化、无 ESTABLISHED 套接字 |
| 4 | `attempt-20260919_132732_589` | 终态 FAILED | **环境修复 A（动态端口恢复默认 49152–65535）后复跑**：12 个 Spark JVM 0 挂起，全链 7 阶段 + pub（4 项 PUB_* 检查全过）+ **mxp SUCCESS（`ads_hot_product_m: hive=0 export=0`、`ads_product_conversion_m: hive=0 export=0`——D-020 声明范围实链证实，无 UNABLE_TO_INFER_SCHEMA）**；MetricPublisher 消费 manifest 写 42 行后，`ads_data_quality_m` INSERT 抛 `Column 'error_rate' cannot be null`（`MetricAdsWriter.java:136`），回滚 42 行，终态 `MP_ADS_WRITE` |

关键事实：

- **attempt-4 = production FAIL**（§6 口径）：被测代码执行至真实业务终态失败；**D-020 修复声明成立**——T-R1 所指缺陷（mxp UNABLE_TO_INFER_SCHEMA）实链证实修复，T-R1 事件正式处置留总控裁决；
- **新缺陷（接棒为当前门失败点）**：`db/metric/V3__metric_ads_r7.sql` 历史迁移 `error_rate DECIMAL(12,6) NOT NULL DEFAULT 0` 与 D-019 合法空态语义（0 行专题输出 `0/0/passed=1/error_rate=NULL`）冲突；该路径在 Batch T/T-R1/R4 中均未被执行过，本次首次触达即暴露；
- 环境修复 A 已执行（UAC 提升，v4/v6 动态端口 1024–15000 → 49152–65535，回滚命令已记录）：复跑 12 次 JVM 提交 0 挂起（原挂起率 ~12%/作业）；
- 每次 attempt 的 producer handoff 均成立（batchId 11/12/13，各 1011/0/0，钉死输入）；DB 为 runId 域内复用 + 幂等 prep 口令重置（T-R1 先例）；3306 未触碰、阈值未改、未把任何读取失败当空表；
- **治理后果**：T-R2 未 PASS → VERIFY_CURRENT_BATCH 保持开放；BATCH-U 不开放；
- **修复建议（未执行，待总控/用户裁决，结果文档 §12）**：**D-021 候选（推荐）**= 追加式 metric 迁移 `V11__ads_data_quality_error_rate_nullable.sql`（`MODIFY error_rate ... NULL DEFAULT NULL`）+ developer/IT 测试 → 批准后走 T-R3（同钉定契约复跑，PASS 判据 = PUBLISH_METRIC 全链 SUCCESS + exit 0）；备选（NULL 强转 0.0 / 改 dqc 输出）均属伪造 0/0 语义，不建议。

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

**当前（2026-09-19）**：T-R2 未 PASS（终态 production FAIL `MP_ADS_WRITE`，4 次 attempt；前 3 次 BLOCKED_ENV 已由环境修复 A 解除），上述推进未发生。新缺陷（metric 库 V3 `error_rate NOT NULL` vs D-019 合法 NULL 行）的修复建议 **D-021**（追加式 `V11__ads_data_quality_error_rate_nullable.sql` 使 `ads_data_quality_m.error_rate` 可空）已交总控/用户裁决，批准后走 **T-R3**（同钉定契约复跑，PASS 判据 = PUBLISH_METRIC 全链 SUCCESS + harness exit 0）。
