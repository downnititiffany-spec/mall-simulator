# Current Verification Batch

> 状态：**BATCH-U（Flume→HDFS SpoolDir 实链）PASS 已收口（2026-09-19 17:07–17:55，RunId `stage7u_20260919_170729`）：主链 1011 行 event_id 逐项对账零丢失/零新增/零重复；kill -9 恢复子例两次 attempt 均 LOST=0（重复如实登记 500/0，at-least-once）；全程零 MySQL、零 platform/Spark、`/opt` 与 3306 未触碰。Stage 7 状态矩阵仅 Flume→HDFS 一格置 PASS/CLOSED。**
> **当前无开放验证门**；下一步按总控指示直接推进 Stage 7 剩余面（REMOTE_CLUSTER、浏览器 E2E、真实 LLM——待总控排程）。BATCH-U 通过不证明：平台 FLUME_RAW 采集与 manifest 对账、Spark/LOAD_ODS 下游、REMOTE_CLUSTER、浏览器 E2E、真实 LLM、端到端 exactly-once、整个 Stage 7 完成。
> 结果文档：`docs/verification/results/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-RESULT.md`；T-R3 结果：`docs/verification/results/BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`。
> Git 注记（D-001）：BATCH-U 结果/状态文档提交仅本地；push 需总控另行授权。

## Closed batch：BATCH-U（2026-09-19 收口，历史）

- **Batch ID**：`BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN`
- **Permanent plan**：`docs/verification/batches/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-PLAN.md`（执行前由总控裁决二直接批准：确认远端 HEAD `f136af5` 后按 Phase 0–6 连续执行，无需第二次确认）
- **Exact source/test baseline**：`104db41117ea251b5b8e6c1f32c9f61ca4acd659`（执行 HEAD `f136af5` 为 docs-only 前移，不触发重钉；本批零仓内代码/配置变更）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7u_20260919_170729`（恢复子例隔离资源 `<RunId>-rec`）
- **Predecessor**：Batch T-R3 PASS（被测 SHA `7850e9b`，结果 `104db41`，远端 HEAD 已验证）
- **Outcome**：**PASS**（计划 §5 十一项判据全部满足；七类失败分类零触发；3 起环境级事件登记：flume-ng 默认 `-Xmx20m` 堆 OOM → run-scoped `flume-env.sh` 官方覆写点修复、NN/DN SIGHUP → setsid 无损恢复、Phase 4 竞速 echo 截断 → 留证日志诚实重构）
- **Git 状态注记（D-001）**：结果/状态文档提交仅本地；push 需总控另行授权。

## BATCH-U 实际结果（2026-09-19 登记：PASS）

结果：`docs/verification/results/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-RESULT.md`（Exact SHA `104db41`，RunId `stage7u_20260919_170729`，2026-09-19 17:07–17:55 +0800，WSL run root 隔离，零仓内变更）。

- **主链（Phase 0–3/5/6）**：Flume 1.11.0 官方 SHA512 校验通过、run-scoped 安装；run conf 由仓内 `flume-spooldir.conf` 派生、diff 恰 4 处路径替换（各恰 8 行）；HDFS run-scoped 1NN/1DN（RPC 9100 运行时证实）；pinned 1011 行输入（SHA `f32906…bbcc`）copy 投入 → `.COMPLETED`（deletePolicy=never，SHA 复核一致）→ HDFS data file `events-.1789810142459` **456,825 B / 1011 行**（checksum `2446c8f7…`）；60s 双快照不变；event_id 集合双向比对 **1011=1011 `MAIN_SET_EQUAL`，LOST=0、×1 无重复**。
- **恢复子例（Phase 4，隔离 `-rec`）**：attempt-1（×20=20,220 行）kill -9 后重放 `put: 20220, take: 500 → Queue 20220`，交付 20,720 = 20,220+**500 在途重投**（如实登记），distinct 1011、LOST=0；attempt-2（×100=101,100 行）kill 前 101,100 事件全部 commit 进 File Channel、kill 落在 sink 排空，重放 `put: 101100 → Queue 99100`，交付**恰 101,100（零净超额、无二次摄取）**、LOST=0；合并 multiset **INPUT_ONLY=0 / DELIVERED_ONLY=500**（`rec-multiset-diff-fixed.txt`）。File Channel kill -9 后重放日志干净 → FAIL_CHAIN 不适用；checkpoint/data 分离持久留证。
- **登记事件（3，均非 FAIL_\* 类）**：① flume-ng 脚本硬编码 `-Xmx20m` 堆 OOM 首启即死 → run-scoped `flume-env.sh` `JAVA_OPTS=-Xmx512m`（flume-ng 326–331 行官方覆写点，`-f` run conf 未动）；② NN/DN 因 WSL 会话拆除收 SIGHUP（各唯一 1 条 ERROR）→ `setsid nohup` 无损恢复（不 reformat），Flume 存活、1011 事件安全滞留 channel，NN 中断期间 sink 重试留 3 个 0 字节残迹文件（未承载数据，如实登记）；③ Phase 4 attempt-2 竞速 echo 行位于截断输出段 → 由 agent-3 日志 + 重放计数 + HDFS 快照诚实重构。
- **边界**：全程零 MySQL（3306/3307 零连接）、零 platform/Spark；`/opt/hadoop-3.3.4` 原配置零修改、无系统服务注册；证据 62 件 + flume conf 4 件 + hadoop 日志 2 件归档 `target/v25-it/stage7u_20260919_170729/`（含 `evidence-summary.json`）。**不证明**：平台 FLUME_RAW、Spark 下游、REMOTE_CLUSTER、浏览器 E2E、真实 LLM、exactly-once、整个 Stage 7 完成。

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

## After BATCH-U（当前状态，2026-09-19）

总控两项裁决均已执行：① T→T-R1→T-R2→T-R3 三个既有提交（`7850e9b` → `a11ee42` → `104db41`）按原 ancestry fast-forward 推送 origin/feature/v3-development（`520d673..104db41`，无 force/rebase/amend/squash、无夹带），远端 HEAD = `104db41117ea251b5b8e6c1f32c9f61ca4acd659` 已验证；② BATCH-U 按裁决二在确认远端 HEAD `f136af5`（docs-only 前移）后直接执行 Phase 0–6，**已 PASS 收口**（本文件顶部与「BATCH-U 实际结果」节；结果文档 `docs/verification/results/BATCH-U-STAGE7-FLUME-HDFS-SPOOL-CHAIN-RESULT.md`）。

状态矩阵：Stage 1–6 完成；Stage 7：LocalFile 分析链 PASS/CLOSED、**Flume→HDFS PASS/CLOSED**、REMOTE_CLUSTER 未验证、浏览器 E2E 未验证、真实 LLM 未验证；Stage 8 未开始。当前无开放验证门；下一步按总控指示直接推进 Stage 7 剩余面（REMOTE_CLUSTER、浏览器 E2E、真实 LLM）。

BATCH-U 通过不证明：平台 FLUME_RAW 采集与 manifest 对账、Spark 下游、REMOTE_CLUSTER、浏览器 E2E、真实 LLM、端到端 exactly-once、整个 Stage 7 完成。
