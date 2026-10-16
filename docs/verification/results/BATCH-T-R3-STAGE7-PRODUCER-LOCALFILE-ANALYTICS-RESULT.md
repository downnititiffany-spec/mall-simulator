# BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Controller Review

> Overall: **PASS**——单次 attempt 全链真实执行至 PUBLISH_METRIC 成功收口，**D-021 核心断言在字节级证实**：D-019 合法空态的 `error_rate=NULL` 行真实落入 `ads_data_quality_m`（未被强转 0、无回滚、无 DataIntegrityViolation），`MP_ADS_WRITE` 事件正式关闭。
> Exact tested SHA: `7850e9b403ec4e83c7b41edced513ccc8562a41f`（D-021 修复：append-only metric Flyway `V11__ads_data_quality_error_rate_nullable.sql`； Independent Reviewer VERDICT: APPROVE；detach 后 `mvn -DskipTests package` 重建两 JAR，branch 在链尾恢复 `feature/v3-development`）
> RunId: `stage7q1_20260918_152245`
> Attempts: **1**（2026-09-19 15:42–15:47；outer `attempt-20260919_154256_467`，http `attempt-20260919_154256_825`）
> 执行授权：用户 2026-09-19 指示「你去执行获取结果，继续推进项目」（D-021 裁决后的 T-R3 执行环节）。

## 1. 执行链与前置条件（全链一次通过）

`[1/5]` `git switch --detach 7850e9b…`（rev-parse 确认精确 SHA）→ `[2/5]` analytics-server 构建 `ANALYTICS_BUILD_EXIT=0` → `[3/5]` spark-jobs 构建 `SPARK_BUILD_EXIT=0` → `[4/5]` 单进程 runner（口令仅进程内生成/传递）：

- 幂等 prep（`it-prepare-isolation.ps1 -RunId stage7q1_20260918_152245 -IncludeAnalytics -Confirm -AllowRootOnIsolated`）：3307 上 IF NOT EXISTS 重建/确认 4 库（mall / generator / analytics_meta / analytics_metric）+ 4 受限账号授权 + 凭据文件写入（credref，gitignore 覆盖）；`PREP_EXIT=0`；
- e2e harness（`stage7-localfile-e2e.ps1 -ProducerEvidence …attempt-20260918_195657_077\stage7-producer-result.json -Confirm`）→ 委托 `stage7-http-isolated.ps1`；`HARNESS_EXIT=0`、`RUNNER_EXIT=0`；

`[5/5]` 分支恢复 `feature/v3-development`（`CHAIN_DONE`）。预检事实：3307 可达（fingerprint dahaishui:3307 / server_uuid `de8ebbea-…`）、动态端口 49152–65535（环境修复 A 保持）、8091 空闲、JDK17（`D:\Develop\JAVA17`，与 T-R2 attempt-4 同环境）。

## 2. Pinned input handoff（成立）

producer 输入为计划 §2 钉死的 S-R1 证据（`attempt-20260918_195657_077`，物理 SHA256 `f32906…bbcc`）：1011/1011/0、businessDate=2026-09-18、mock-mall。ingestion batchId=**14**（batchNo `ing-20260919154306-4f7555d1`）：**1011 accepted / 0 quarantine / 0 error / noNewData=false**（455814 bytes，单文件）。Pipeline idempotency key 新鲜（`stage7-stage7q1_20260918_152245-attempt-20260919_154256_825`），runId=**12**（未复用 5/8/11）。

## 3. Pipeline 全链证据（8 阶段全 SUCCESS）

runId=12，targetSnapshotId=`S20260918_12`，attemptNo=1，15:43:06 → 15:46:06（约 3 分钟）：

| stage | 状态 | records | 备注 |
|---|---|---|---|
| WAIT_LANDING | SUCCESS | 1011 | batchId=14, checksum a836a0fb |
| INIT_SCHEMA | SUCCESS | 37 | sci 四层库表自举（12s） |
| LOAD_ODS | SUCCESS | 1011 | ods_product 441 / ods_trade 520 / ods_user 50 |
| BUILD_DWD | SUCCESS | 294 | |
| BUILD_DWS | SUCCESS | 0 | 纯交易源合法空态（D-019 语义） |
| BUILD_ADS | SUCCESS | 46 | 8 张 ADS 暂存（含 0 行专题 2 张） |
| QUALITY_CHECK | SUCCESS | 10 | dqc 10 项检查全过（15:45:09→15:45:29） |
| PUBLISH_METRIC | SUCCESS | 92 | pub + mxp + MetricPublisher（15:45:29→15:46:06） |

QUALITY_CHECK 关键检查：`ADS_STAGING_PRESENT`（8/8 就绪，0 行专题按 v2 语义放行）、`PUB_DQ_BLOCKING_RULES`（AMOUNT_RECONCILE / ENUM_WHITELIST / REQUIRED_FIELD_NULL_RATE 全 passed=1）、`ADS_GMV_NET_SALE_INVARIANT` 等全部通过，`prePublishGate.passed=true`。

pub 作业（`lp-1789803929917-c7fe1a`）：`PUB_STAGING_READY` 8/8、`PUB_FORMAL_PARTITION_MATCH` 8/8（合计 46 行）、`PUB_POINTER_SWITCH` 切换 8 张、`PUB_STAGING_PRUNE` 无待清理——**8 张正式指针切换完成**。

## 4. mxp 导出（8 表全部完成）

mxp（`lp-1789803952048-ef9bd7`）SUCCESS：`MXP_SNAPSHOT_PINNED` 8/8、`MXP_EXPORT_ROWS` 逐表对账一致（overview 1/1、sale_trend 1/1、behavior_funnel 4/4、active_trend 1/1、**hot_product 0/0**、**product_conversion 0/0**、user_profile 35/35、**data_quality 4/4**）、`MXP_EXPORT_COMPLETE` 合计 46 行/8 张表——D-020 修复持续有效，无回归。

## 5. MetricPublisher（14 项 MP_* 检查全过）

`MP_MANIFEST_SNAPSHOT/TABLES`、`MP_HIVE_PATH_PINNED`、`MP_EXPORT_FILES/CHECKSUM`（逐表 CRC32 重算一致）、`MP_ADS_ROWS_MATCH`（**ads_data_quality_m=manifest:4/written:4**）、`MP_REQUIRED_TABLES_NONEMPTY`（概览/趋势/漏斗/活跃 4 表有数）、`MP_ROW_SHAPE_CONSISTENT`、`MP_OVERVIEW_CORE_NOT_NULL`、`MP_METRIC_DICT_VERSION`、`MP_VALUE_MATCH_ADS`、`MP_METRIC_VALUE_COUNT`（12 码）、`MP_ACTIVE_SNAPSHOT`（active=S20260918_12）、`MP_ADS_ROWS_DB_MATCH`（只读账号实读逐表 = 清单行数）、`MP_METRIC_VALUE_DB_MATCH`（12/12）、`MP_OLD_ACTIVE_ARCHIVED`（上一 ACTIVE=S20260901_4 已归档）。`metricPublish.ok=true, errorCode=null, message=发布成功`。

## 6. D-021 核心断言（字节级证实）

1. **V11 真实应用**：隔离 metric 库 `flyway_schema_history` 追加行 `11 | ads data quality error rate nullable | success=1 | installed_on=2026-09-19 15:43:01`——平台启动期由 `MetricFlywayInitializer`（classpath:db/metric）自动迁移，V1–V10 保持 2026-09-18 原时间戳（历史迁移未被触碰）。
2. **表结构生效**：`DESCRIBE ads_data_quality_m` → `error_rate decimal(12,6) | Null=YES | Default=NULL`。
3. **NULL 行真实落库（3307 直查）**：快照 `S20260918_12 / dt=20260918` 共 4 行——

| rule_code | check_count | error_count | error_rate | is_null | passed |
|---|---|---|---|---|---|
| AMOUNT_RECONCILE | 80 | 0 | 0.000000 | 0 | 1 |
| ENUM_WHITELIST | 0 | 0 | **NULL** | **1** | 1 |
| EVENT_ID_UNIQUE | 0 | 0 | **NULL** | **1** | 1 |
| REQUIRED_FIELD_NULL_RATE | 0 | 0 | **NULL** | **1** | 1 |

   0/0 合法空态三行 `error_rate` 为**真 NULL**（`SUM(error_rate IS NULL)=3`），未被强转 0；有数行为 0.000000（0/80 的真实比率）语义正确；D-019 语义原样保留。上一快照 S20260901_4 的 4 行历史数据未受影响。
4. **无回滚/无异常**：`platform.log` 中 `DataIntegrityViolation` 0 次、`cannot be null` 0 次、无 rollback 记录；`MetricAdsWriter` 正常记录 `metric ads insert ads_data_quality_m (snapshot=S20260918_12, dt=20260918): 4 行 / 9 列`。T-R2 attempt-4 的 42 行回滚（MP_ADS_WRITE）路径未复现。

## 7. 计划 §5 PASS 判据逐项核对

- ✅ producer 输入 1011 unique / 0 duplicate、businessDate=2026-09-18；
- ✅ ingestion recordCount=1011、quarantineCount=0、noNewData=false；
- ✅ fresh Pipeline run 至 **PUBLISH_METRIC 全链 SUCCESS**（8 阶段全绿）；
- ✅ 质量门 PASS（v2 `ADS_STAGING_PRESENT` 放行 0 行专题照旧）；
- ✅ mxp 8 表完成：0 行表真实空导出 hive=0 export=0，非 0 行表逐表对账一致；
- ✅ MetricPublisher 消费 manifest：14 项 MP_* 全过，0 行表 0=0 对账；
- ✅ **D-021 断言：error_rate=NULL 行落库、无回滚、无 NULL→0**（§6 三重证据）;
- ✅ outer harness exit 0 / outcome PASS。

## 8. 分类（计划 §6 口径）

**PASS（production run full-chain success）**——非 BLOCKED_ENV、非 harness issue。平台 PID 44184 全程存活（`hasExited=false`、pollErrorCount=0、末次采样 WS 449MB），收口由 harness 精确清理（platform 44184 + 后代 42188）。12 个 Spark JVM 作业提交 0 挂起（环境修复 A 持续有效）。本次未把任何读取失败当空表放行；未触碰 3306；质量阈值未改；无 Fake executor。

## 9. 治理后果与边界

- **`MP_ADS_WRITE` 事件（T-R2 终态失败点）正式关闭**：同链复跑成功证明 D-021 修复关闭该缺口；VERIFY_CURRENT_BATCH 关闭；
- **BATCH-U 解锁**：按 D-021 裁决与 CURRENT_BATCH 既定门（T-R3 PASS 后开放），BATCH-U（Flume→HDFS，DRAFT）可进入计划编制；
- T-R3 PASS **不证明**：REMOTE_CLUSTER、浏览器 E2E、真实 LLM、Flume/HDFS 独立批次（属 BATCH-U 及后续）；
- 被测 SHA `7850e9b` 与文档提交 `a11ee42` 及本结果文档提交在登记时点为**本地提交，推送归 ChatGPT 角色（D-001），待总控授权**。

## 10. 证据工件索引

- outer：`target/v25-it/stage7q1_20260918_152245/localfile-e2e/attempt-20260919_154256_467/stage7-localfile-e2e-result.json`（outcome=PASS）；
- inner：`target/v25-it/stage7q1_20260918_152245/http/attempt-20260919_154256_825/stage7-http-result.json`（outcome=PASS；platform PID/资源采样、runtime profile、ingestion、8 阶段、mp/mxp 检查明细）；
- 日志：`…/http/attempt-20260919_154256_825/logs/platform.log`、`landing/logs/pipeline-12-*.log`；
- DB 直查（3307，root 只读 SELECT）：`flyway_schema_history`（V11 行）、`ads_data_quality_m` 行值（§6）、`DESCRIBE`（V11 生效）；
- 链日志：`%TEMP%\tr3-full-chain.log`（PREP_EXIT/HARNESS_EXIT/RUNNER_EXIT/CHAIN_DONE 全 0）。
