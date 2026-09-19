# Current Verification Batch

> 状态：**Batch T-R3 PASS 已收口并完成授权推送（2026-09-19）：远端 origin/feature/v3-development HEAD = `104db41`（`520d673..104db41` 纯 fast-forward，已验证）。Stage 7「localfile 分析链」验证门 T→T-R1→T-R2→T-R3 收敛关闭，T-R2 的 production FAIL `MP_ADS_WRITE` 事件正式关闭。**
> **BATCH-U（Flume→HDFS SpoolDir 实链）：READY——总控已批准启动，永久计划已钉死**（`docs/verification/batches/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-PLAN.md`：Exact SHA `104db41`、spooldir 钉死、RunId 隔离 HDFS 目标、11 项 PASS 判据、七类失败分类、零 MySQL）；**执行待总控对计划检查后放行，未放行不得执行任何 Phase 命令**。T-R3 通过不证明：REMOTE_CLUSTER、浏览器 E2E、真实 LLM。
> T-R3 结果文档：`docs/verification/results/BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`。

## Current batch：BATCH-U（READY——计划已钉死，执行待总控放行）

- **Batch ID**：`BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN`
- **Permanent plan**：`docs/verification/batches/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-PLAN.md`（本次由 DRAFT 升级为 READY）
- **Exact source/test baseline**：`104db41117ea251b5b8e6c1f32c9f61ca4acd659`（当前 HEAD；本批零仓内代码/配置变更，docs-only HEAD 前移不触发重钉；引入仓内变更须先提交并重钉）
- **Branch context**：`feature/v3-development`
- **RunId**：执行时新生成 `stage7u_<yyyyMMdd_HHmmss>`；恢复子例隔离资源用 `<RunId>-rec`
- **Predecessor**：Batch T-R3 PASS（被测 SHA `7850e9b`，结果 `104db41`，远端 HEAD 已验证）
- **范围（总控钉死）**：producer 完成文件（spool input）→ Flume source/channel/sink → HDFS landing → 文件真实可读、行数/event_id 可对账；不含平台 FLUME_RAW 采集、Spark、浏览器、真实 LLM、REMOTE_CLUSTER。
- **关键钉死**：Source = Spooling Directory Source（设计 §8.2 强制，taildir 排除，不得执行时切换）；HDFS = WSL 单节点 1NN/1DN replication=1（run-scoped conf，NN RPC 钉 9100）；Flume 1.11.0 需前置下载（SHA512 校验）+ 注入 hadoop-3.3.4 client jars（2026-09-19 实测：本机无 Flume，WSL /opt 有出厂空配置 hadoop-3.3.4，默认 JDK11）；输入 = S-R1 pinned 1011 行完成文件只读副本（SHA256 `f32906…bbcc`，主键 `event_id`，1011 unique）；全程零 MySQL（3306/3307 均不碰）、零 platform/Spark。
- **PASS 判据**：总控 11 项钉死判据逐条对应（计划 §5）；**失败分类**：FAIL_CONF / FAIL_PORT_NET / FAIL_HDFS_UNREACHABLE / FAIL_PERM / FAIL_RECON / FAIL_CHAIN / BLOCKED_ENV（计划 §6）。
- **Git 状态注记（D-001）**：BATCH-U 计划与状态文档提交仅本地；push 需总控另行授权。

## Closed batch：T-R3（2026-09-19 收口，历史）

- **Batch ID**：`BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`7850e9b403ec4e83c7b41edced513ccc8562a41f`（D-021：V11 迁移 + 迁移脚本测试 + isolated 真库 IT + run-tests.ps1 基线同步）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **Predecessor**：Batch T-R2 / production FAIL `MP_ADS_WRITE`（result：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`）
- **Outcome**：**PASS**（outer `attempt-20260919_154256_467` / http `attempt-20260919_154256_825`，2026-09-19 15:42–15:47，`HARNESS_EXIT=0`）
- **Git 状态注记（D-001）**：`7850e9b`（D-021 修复）与 `a11ee42`（D-021 文档）及本结果文档提交当前仅在本地，push 归 ChatGPT 角色、待总控授权。

## T-R3 实际结果（2026-09-19 登记：PASS）

结果：`docs/verification/results/BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`（Exact SHA `7850e9b`，RunId `stage7q1_20260918_152245`，1 次 attempt，全链约 3 分钟）。

- **Pinned input handoff 成立**：钉死 producer 证据（SHA256 `f32906…bbcc`，1011/1011/0，businessDate 2026-09-18，mock-mall）；ingestion batchId=14，1011 accepted / 0 quarantine / 0 error / noNewData=false；pipeline runId=**12**（未复用 5/8/11），idempotency key 新鲜；
- **Pipeline 8 阶段全 SUCCESS**：WAIT_LANDING → INIT_SCHEMA(37) → LOAD_ODS(1011=441+520+50) → BUILD_DWD(294) → BUILD_DWS(0，纯交易源合法空态) → BUILD_ADS(46) → QUALITY_CHECK(10 项检查全过，`ADS_STAGING_PRESENT` v2 放行 0 行专题) → PUBLISH_METRIC；snapshot `S20260918_12`，8 张正式指针切换完成（pub 4 项检查全过）；
- **mxp 8 表完成**：`MXP_EXPORT_ROWS` 逐表对账一致（含 hot_product 0/0、product_conversion 0/0、data_quality 4/4），合计 46 行——D-020 修复持续有效无回归；
- **MetricPublisher 14 项 MP_* 检查全过**：`MP_ADS_ROWS_MATCH`（ads_data_quality_m=4/4）、`MP_ADS_ROWS_DB_MATCH`（只读账号实读逐表一致）、`MP_ACTIVE_SNAPSHOT`、`MP_OLD_ACTIVE_ARCHIVED`（S20260901_4→S20260918_12）等；`metricPublish.ok=true, message=发布成功`；
- **D-021 断言（字节级证实）**：① 隔离 metric 库 `flyway_schema_history` 追加 `11 | ads data quality error rate nullable | success=1 | 2026-09-19 15:43:01`（平台启动期 `MetricFlywayInitializer` 自动应用，V1–V10 时间戳不变）；② `DESCRIBE` 显示 `error_rate decimal(12,6) Null=YES Default=NULL`；③ 3307 直查 `S20260918_12` 4 行：0/0 空态三行（ENUM_WHITELIST/EVENT_ID_UNIQUE/REQUIRED_FIELD_NULL_RATE）`error_rate` 为**真 NULL**（passed=1，未被强转 0），AMOUNT_RECONCILE（80 检查/0 错误）为 0.000000 真实比率；④ `platform.log` 无 DataIntegrityViolation、无 cannot be null、无回滚，`MetricAdsWriter` 正常写 4 行——T-R2 的 42 行回滚路径未复现；
- **分类**：PASS（§6 口径 production run full-chain success；平台 PID 44184 全程存活 pollErrorCount=0，harness 收口精确清理；12 个 Spark JVM 0 挂起，环境修复 A 持续有效；3306 未触碰、阈值未改、无 Fake executor、未把任何读取失败当空表）。

## D-021 修复与三档回归（2026-09-19，T-R3 前置，全部满足）

**修复（D-021，提交 `7850e9b`）**：append-only metric 迁移 `V11__ads_data_quality_error_rate_nullable.sql`，单语句 `ALTER TABLE ads_data_quality_m MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL`。不改 V3、不做 NULL→0 转换、不改变 D-019 语义与阈值；无 Java 生产代码改动（`MetricAdsWriter` 将行值原样传 JDBC，SQL NULL 自然流动）；无 error_rate 读侧消费者（AnalysisService.quality 只读 rule_code/passed/rule_version；PipelineService 侧 error_rate 属 meta 库 `data_quality_result` 另表，保持 NOT NULL）。V11 标注真库（3306）执行状态【未执行】——3306 冻结，真实 MySQL 证据只来自 3307 隔离库。

**developer tests**：迁移脚本测试 `AdsDataQualityErrorRateNullableMigrationScriptTest` **3/3**（V11 全量体精确匹配 DECIMAL(12,6) NULL DEFAULT NULL；append-only + 版本序 V11 为下一版且 V1~V10 不动；负向对照验证解析器能捕获违规）。

**isolated 真库 IT**：`AdsDataQualityErrorRateNullableMySqlIT` **2/2**（information_schema：IS_NULLABLE=YES / COLUMN_TYPE decimal(12,6) / COLUMN_DEFAULT NULL + flyway_schema_history v11、v3 success=1；D-019 形状 NULL 行经 `MetricAdsWriter.insertRows` 真实入库读回 + 0.333333 对照行）。

**三档回归**：

- default：analytics **1039 MATCH**（基线 1036→1039；F=1 为既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）/ mall **14 MATCH** / generator **111 MATCH**（RunId `d021def_20260919_143033`）；
- spark：fresh **320/320** exit 0（JDK8，RunId `d021spark_20260919_143243`）；
- isolated：fresh runId `d021iso_20260919_145240` **62/62** exit 0（mall 30 + generator 19 + analytics 13 = IsolationGuard 6 + MetricAds 2 + MetricPublisher 3 + 新增 AdsDataQualityErrorRateNullable 2；同 reactor 依赖构建 platform-common=115 不计入）。首轮 `d021iso_20260919_144858` 在 schema 步骤被 external kill 中止，属基础设施中断、无测试结果，不记成败。口令通道按 T-R1/T-R2 先例只走 PowerShell Process env。

**Independent Reviewer**（governance §1.4/§8、D-004、ADR-0001，钉定 `7850e9b` 独立复核）：**VERDICT: APPROVE**——9/9 项符合（含 V1~V10/V3 blob-hash 字节级独立复核、迁移脚本测试离线复跑 3/3、error_rate 读侧 NULL-safety 穷举 grep）；2 条 LOW：① DECISION_LOG 缺 D-021 条目（已随本轮补录）；② 与 docs/status-history/开发过程事实与决策记录.md:242 无关系列「D-021」撞号（DECISION_LOG D-021 条目顶部已加消歧注记）。

**边界不变**：3306 未触碰（零写入、不切 ACTIVE）、未改 V1~V28 及 V3、未改质量阈值、未 force push、未做 NULL→0 转换、D-019 语义未变。

## T-R2 实际结果（2026-09-19 登记：4 次 attempt，最终 production FAIL — 保持为历史 predecessor 事实）

结果：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`（Exact SHA `1ae091b`，RunId `stage7q1_20260918_152245`，harness exit 7 ×4）。

| # | attempt | outcome | 失败点 |
|---|---|---|---|
| 1 | `attempt-20260919_112031_598` | PIPELINE_TIMEOUT | QUALITY_CHECK：dqc JVM 提交后 0-CPU 挂起（7 作业 SUCCESS 后第 8 个挂；当时无 jstack，签名归因） |
| 2 | `attempt-20260919_113916_916` | 平台未就绪 | 平台 JVM 出生 ~3s 被 CTRL_C 杀死（exitCode -1073741510，stderr 0 字节；attempt-3 同条件存活证伪其可复现性 → 瞬时环境信号） |
| 3 | `attempt-20260919_115603_058` | PIPELINE_TIMEOUT | INIT_SCHEMA：driver JVM（pid 58920）SparkContext 初始化中 jar 自下载永久阻塞——3 份 jstack 证明 main 线程卡在 `NettyRpcEnv$FileDownloadChannel.read → SocketDispatcher.read0`，CPU 计数 6.5 分钟零变化、无 ESTABLISHED 套接字 |
| 4 | `attempt-20260919_132732_589` | 终态 FAILED | **环境修复 A（动态端口恢复默认 49152–65535）后复跑**：12 个 Spark JVM 0 挂起，全链 7 阶段 + pub（4 项 PUB_* 检查全过）+ **mxp SUCCESS（`ads_hot_product_m: hive=0 export=0`、`ads_product_conversion_m: hive=0 export=0`——D-020 声明范围实链证实，无 UNABLE_TO_INFER_SCHEMA）**；MetricPublisher 消费 manifest 写 42 行后，`ads_data_quality_m` INSERT 抛 `Column 'error_rate' cannot be null`（`MetricAdsWriter.java:136`），回滚 42 行，终态 `MP_ADS_WRITE` |

关键事实：

- **attempt-4 = production FAIL**（§6 口径）：被测代码执行至真实业务终态失败；**D-020 修复声明成立**——T-R1 所指缺陷（mxp UNABLE_TO_INFER_SCHEMA）实链证实修复；
- **缺陷根因（已由 D-021 修复、经 T-R3 实链关闭）**：`db/metric/V3__metric_ads_r7.sql` 历史迁移 `error_rate DECIMAL(12,6) NOT NULL DEFAULT 0` 与 D-019 合法空态语义冲突；
- 环境修复 A 已执行（UAC 提升，v4/v6 动态端口 1024–15000 → 49152–65535，回滚命令已记录）；
- 每次 attempt 的 producer handoff 均成立（batchId 11/12/13，各 1011/0/0，钉死输入）；DB 为 runId 域内复用 + 幂等 prep 口令重置（T-R1 先例）；3306 未触碰、阈值未改、未把任何读取失败当空表；
- **治理后果（终态）**：该事件由 T-R3 同链复跑成功正式关闭（2026-09-19）。

## Accepted Batch T facts（钉死 producer 输入的既有证据）

Exact tested SHA `cbc41919df79bba20e1f91fe1724061ae151d12e`（Batch T）:

- producer input physical SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- sourceLineCount = 1011; uniqueEventIdCount = 1011; duplicateEventIdCount = 0;
- businessDate = 2026-09-18; source_system = mock-mall;
- ingestion SUCCESS：recordCount = 1011，quarantineCount = 0，errorCount = 0，noNewData = false.

Pipeline `runId=5` 真实启动并推进至 BUILD_DWS 后平台进程消失（无 ExitCode 证据）→ Batch T 记 `BLOCKED_PLATFORM_EXIT_UNDIAGNOSED`，由此催生 T-R1 诊断门。

## T-R1 实际结果（2026-09-19 登记，保持为历史 predecessor 事实）

- 平台全程存活：PID 39964 `hasExited=false`、pollErrorCount=0——Batch T 的消失未复现，harness 诊断增强按设计工作；
- 链路真实推进：WAIT_LANDING/INIT_SCHEMA/LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS/QUALITY_CHECK 全 SUCCESS（v2 修复被实链验证：`PUB_STAGING_READY` 放行 0 行专题），pub 作业 SUCCESS、8 张正式指针切换完成；
- 失败点：PUBLISH_METRIC 的 mxp exit=1 `[UNABLE_TO_INFER_SCHEMA]`——0 行暂存分区目录只有 `_SUCCESS` 无 parquet 文件，`MetricExportJob.scala:78` 直读路径文件级 schema 推断失败；
- 分类：**production FAIL**（`FAIL_PRODUCTION_RUN_PUBLISH_FAILED`）；该事件已由 T-R2 attempt-4（mxp SUCCESS）实链证实修复、随 T-R3 收敛关闭。

## After T-R3 → BATCH-U（当前状态，2026-09-19）

总控两项裁决均已执行：① T→T-R1→T-R2→T-R3 三个既有提交（`7850e9b` → `a11ee42` → `104db41`）按原 ancestry fast-forward 推送 origin/feature/v3-development（`520d673..104db41`，无 force/rebase/amend/squash、无夹带），远端 HEAD = `104db41117ea251b5b8e6c1f32c9f61ca4acd659` 已验证；② BATCH-U 批准启动，永久计划已按治理流程编制并置 READY（本文件已同步），**执行待总控对计划检查后放行**。

状态矩阵：Stage 1–6 完成；Stage 7：LocalFile 分析链 PASS/CLOSED、Flume→HDFS NEXT/BATCH-U READY（执行待放行）、REMOTE_CLUSTER 未验证、浏览器 E2E 未验证、真实 LLM 未验证；Stage 8 未开始。

BATCH-U 通过不证明：平台 FLUME_RAW 采集与 manifest 对账、Spark 下游、REMOTE_CLUSTER、浏览器 E2E、真实 LLM、端到端 exactly-once、整个 Stage 7 完成。
