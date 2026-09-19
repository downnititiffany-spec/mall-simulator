# Current Verification Batch

> 状态：**READY — Batch T-R3（D-021 修复已落地：append-only metric Flyway V11 使 ads_data_quality_m.error_rate 可空；三档回归全绿；Independent Reviewer APPROVE；待实链复跑）**。
> T-R2 终态为 production FAIL（`MP_ADS_WRITE`）：D-019 合法空态质量行（`error_rate=NULL`）被 metric 库历史迁移 V3 的 `NOT NULL DEFAULT 0` 拒绝，42 行回滚、pipeline 11 FAILED。该缺陷已由 **D-021**（总控裁决 APPROVED）修复并按裁决完成全部前置（developer + isolated MySQL IT、三档回归、Independent Reviewer），T-R3 以修复后的精确 SHA `7850e9b` 置 READY。**BATCH-U 继续关闭（总控裁决）**。

## Current batch

- **Batch ID**：`BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`7850e9b403ec4e83c7b41edced513ccc8562a41f`（D-021：V11 迁移 + 迁移脚本测试 + isolated 真库 IT + run-tests.ps1 基线同步）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **Predecessor**：Batch T-R2 / production FAIL `MP_ADS_WRITE`（result：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`，attempt `attempt-20260919_132732_589` / pipeline runId=11）
- **Pinned S-R1 producer evidence**：`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`（1011 unique / 0 duplicate，SHA256 `f32906…bbcc`，businessDate 2026-09-18，mock-mall）
- **Overall**：`READY`（总控裁决原文：「**总控裁决：D-021 APPROVED。按 append-only metric Flyway V11 将 ads_data_quality_m.error_rate 改为 DECIMAL(12,6) NULL DEFAULT NULL；不改 V3、不做 NULL→0 转换、不改变 D-019。补 developer + isolated MySQL IT、跑三档回归，并在 T-R3 前完成 Independent Reviewer。完成后以修复后的精确 SHA 建 T-R3；BATCH-U 继续关闭。先将本地 badbf2a、520d673 推送到 feature/v3-development，再继续。**」）
- **Git 状态注记（D-001）**：`7850e9b` 当前仅在本地（裁决授权的 push 仅 badbf2a、520d673，已完成；新提交 push 待总控授权）。

## D-021 修复与三档回归（2026-09-19，T-R3 前置已全部满足）

**修复（D-021，提交 `7850e9b`）**：append-only metric 迁移 `V11__ads_data_quality_error_rate_nullable.sql`，单语句 `ALTER TABLE ads_data_quality_m MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL`。不改 V3、不做 NULL→0 转换、不改变 D-019 语义与阈值；无 Java 生产代码改动（`MetricAdsWriter` 将行值原样传 JDBC，SQL NULL 自然流动）；无 error_rate 读侧消费者（AnalysisService.quality 只读 rule_code/passed/rule_version；PipelineService 侧 error_rate 属 meta 库 `data_quality_result` 另表，保持 NOT NULL）。V11 标注真库（3306）执行状态【未执行】——3306 冻结，真实 MySQL 证据只来自 3307 隔离库。

**developer tests**：迁移脚本测试 `AdsDataQualityErrorRateNullableMigrationScriptTest` **3/3**（V11 全量体精确匹配 DECIMAL(12,6) NULL DEFAULT NULL；append-only + 版本序 V11 为下一版且 V1~V10 不动；负向对照验证解析器能捕获违规）。

**isolated 真库 IT**：`AdsDataQualityErrorRateNullableMySqlIT` **2/2**（information_schema：IS_NULLABLE=YES / COLUMN_TYPE decimal(12,6) / COLUMN_DEFAULT NULL + flyway_schema_history v11、v3 success=1；D-019 形状 NULL 行经 `MetricAdsWriter.insertRows` 真实入库读回 + 0.333333 对照行）。

**三档回归**：

- default：analytics **1039 MATCH**（基线 1036→1039；F=1 为既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）/ mall **14 MATCH** / generator **111 MATCH**（RunId `d021def_20260919_143033`）；
- spark：fresh **320/320** exit 0（JDK8，RunId `d021spark_20260919_143243`）；
- isolated：fresh runId `d021iso_20260919_145240` **62/62** exit 0（mall 30 + generator 19 + analytics 13 = IsolationGuard 6 + MetricAds 2 + MetricPublisher 3 + 新增 AdsDataQualityErrorRateNullable 2；同 reactor 依赖构建 platform-common=115 不计入）。首轮 `d021iso_20260919_144858` 在 schema 步骤被 external kill 中止，属基础设施中断、无测试结果，不记成败。口令通道按 T-R1/T-R2 先例只走 PowerShell Process env。

**Independent Reviewer**（governance §1.4/§8、D-004、ADR-0001，钉定 `7850e9b` 独立复核）：**VERDICT: APPROVE**——9/9 项符合（含 V1~V10/V3 blob-hash 字节级独立复核、迁移脚本测试离线复跑 3/3、error_rate 读侧 NULL-safety 穷举 grep）；2 条 LOW：① DECISION_LOG 缺 D-021 条目（已随本轮补录）；② 与 docs/status-history/开发过程事实与决策记录.md:242 无关系列「D-021」撞号（DECISION_LOG D-021 条目顶部已加消歧注记）。

**边界不变**：3306 未触碰（零写入、不切 ACTIVE）、未改 V1~V28 及 V3、未改质量阈值、未 force push、未做 NULL→0 转换、D-019 语义未变。

## T-R3 PASS criteria（摘要，全文见 plan §5）

ingestion 1011/0 quarantine → Pipeline 全链 SUCCESS **through PUBLISH_METRIC**（跨过 T-R2 的失败点 MP_ADS_WRITE）→ 0 行表真实空 JSONL（rowCount=0/checksum="0"）+ 非 0 行表逐表对账 → **D-021 断言：D-019 合法空态质量行以 error_rate=NULL 真实落库，MP_ADS_WRITE 通过、无回滚、无 NULL→0 改写痕迹** → outer harness exit 0。任何失败按 evidence-only 分类（production FAIL vs BLOCKED_ENV vs harness issue），不猜测；不得 NULL→0、不得改历史迁移、不得把写入失败当空态放行。

## T-R2 实际结果（2026-09-19 登记：4 次 attempt，最终 production FAIL — 保持为 T-R3 的 predecessor 事实）

结果：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`（Exact SHA `1ae091b`，RunId `stage7q1_20260918_152245`，harness exit 7 ×4）。

| # | attempt | outcome | 失败点 |
|---|---|---|---|
| 1 | `attempt-20260919_112031_598` | PIPELINE_TIMEOUT | QUALITY_CHECK：dqc JVM 提交后 0-CPU 挂起（7 作业 SUCCESS 后第 8 个挂；当时无 jstack，签名归因） |
| 2 | `attempt-20260919_113916_916` | 平台未就绪 | 平台 JVM 出生 ~3s 被 CTRL_C 杀死（exitCode -1073741510，stderr 0 字节；attempt-3 同条件存活证伪其可复现性 → 瞬时环境信号） |
| 3 | `attempt-20260919_115603_058` | PIPELINE_TIMEOUT | INIT_SCHEMA：driver JVM（pid 58920）SparkContext 初始化中 jar 自下载永久阻塞——3 份 jstack 证明 main 线程卡在 `NettyRpcEnv$FileDownloadChannel.read → SocketDispatcher.read0`，CPU 计数 6.5 分钟零变化、无 ESTABLISHED 套接字 |
| 4 | `attempt-20260919_132732_589` | 终态 FAILED | **环境修复 A（动态端口恢复默认 49152–65535）后复跑**：12 个 Spark JVM 0 挂起，全链 7 阶段 + pub（4 项 PUB_* 检查全过）+ **mxp SUCCESS（`ads_hot_product_m: hive=0 export=0`、`ads_product_conversion_m: hive=0 export=0`——D-020 声明范围实链证实，无 UNABLE_TO_INFER_SCHEMA）**；MetricPublisher 消费 manifest 写 42 行后，`ads_data_quality_m` INSERT 抛 `Column 'error_rate' cannot be null`（`MetricAdsWriter.java:136`），回滚 42 行，终态 `MP_ADS_WRITE` |

关键事实：

- **attempt-4 = production FAIL**（§6 口径）：被测代码执行至真实业务终态失败；**D-020 修复声明成立**——T-R1 所指缺陷（mxp UNABLE_TO_INFER_SCHEMA）实链证实修复，T-R1 事件正式处置留总控裁决；
- **缺陷根因（已由 D-021 修复）**：`db/metric/V3__metric_ads_r7.sql` 历史迁移 `error_rate DECIMAL(12,6) NOT NULL DEFAULT 0` 与 D-019 合法空态语义（0 行专题输出 `0/0/passed=1/error_rate=NULL`）冲突；该路径在 Batch T/T-R1/R2 attempt 1–3 中均未被执行过，attempt-4 首次触达即暴露；
- 环境修复 A 已执行（UAC 提升，v4/v6 动态端口 1024–15000 → 49152–65535，回滚命令已记录）：复跑 12 次 JVM 提交 0 挂起（原挂起率 ~12%/作业）；
- 每次 attempt 的 producer handoff 均成立（batchId 11/12/13，各 1011/0/0，钉死输入）；DB 为 runId 域内复用 + 幂等 prep 口令重置（T-R1 先例）；3306 未触碰、阈值未改、未把任何读取失败当空表；
- **治理后果**：T-R2 未 PASS → 该事件由 T-R3 的同链复跑关闭（D-021 裁决）；BATCH-U 在 T-R3 PASS 前保持关闭。

## Accepted Batch T facts（钉死 producer 输入的既有证据）

Exact tested SHA `cbc41919df79bba20e1f91fe1724061ae151d12e`（Batch T）:

- producer input physical SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- sourceLineCount = 1011; uniqueEventIdCount = 1011; duplicateEventIdCount = 0;
- businessDate = 2026-09-18; source_system = mock-mall;
- ingestion SUCCESS：recordCount = 1011，quarantineCount = 0，errorCount = 0，noNewData = false.

Pipeline `runId=5` 真实启动并推进至 BUILD_DWS 后平台进程消失（无 ExitCode 证据）→ Batch T 记 `BLOCKED_PLATFORM_EXIT_UNDIAGNOSED`，由此催生 T-R1 诊断门。

## T-R1 实际结果（2026-09-19 登记，保持为 T-R2/T-R3 的 predecessor 事实）

- 平台全程存活：PID 39964 `hasExited=false`、pollErrorCount=0——Batch T 的消失未复现，harness 诊断增强按设计工作；
- 链路真实推进：WAIT_LANDING/INIT_SCHEMA/LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS/QUALITY_CHECK 全 SUCCESS（v2 修复被实链验证：`PUB_STAGING_READY` 放行 0 行专题），pub 作业 SUCCESS、8 张正式指针切换完成；
- 失败点：PUBLISH_METRIC 的 mxp exit=1 `[UNABLE_TO_INFER_SCHEMA]`——0 行暂存分区目录只有 `_SUCCESS` 无 parquet 文件，`MetricExportJob.scala:78` 直读路径文件级 schema 推断失败；
- 分类：**production FAIL**（`FAIL_PRODUCTION_RUN_PUBLISH_FAILED`），非 BLOCKED_ENV、非 harness issue；该事件已由 T-R2 attempt-4 实链证实修复（mxp SUCCESS），事件处置随 T-R3 收敛。

## After T-R3

T-R3 PASS → 关闭 T-R2 的 production FAIL `MP_ADS_WRITE` 事件，Stage 7「localfile 分析链」（mock-mall 纯交易源）验证门序列 T→T-R1→T-R2→T-R3 收敛，并开放 **BATCH-U**（Flume→HDFS，现为 DRAFT）。T-R3 通过仍不证明：REMOTE_CLUSTER、浏览器 E2E、真实 LLM。

**当前（2026-09-19）**：D-021 已落地（`7850e9b`），T-R3 READY，待按 plan §4 执行。
