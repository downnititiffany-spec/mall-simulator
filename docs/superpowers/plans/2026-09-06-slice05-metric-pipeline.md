# Slice05: 指标快照发布 + MySQL MetricStore + 流水线状态机 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 mall-simulator 内实现阶段 6 的可运行核心：`MySqlMetricStore`（§19.3 接口）、指标快照发布状态机 `BUILDING→VERIFYING→ACTIVE`（§21.11，同环境同业务时间唯一 ACTIVE、旧快照 ARCHIVED、事务切换）、全链路流水线 `WAIT_LANDING→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC→SUCCESS`（§23.1，幂等键），以及指标查询 API。LOCAL 模式由纯 Java `MetricCalculator` 承担 ADS 计算角色（集群模式由 spark-jobs 产出 ADS，发布机制一致——可替换适配器思想，同 Flume/LocalFileIngestor 分工）。

**Architecture:** `metric` 包 = MetricStore 接口 + `MySqlMetricStore`（publish 写 `metric_value` + 事务切换 ACTIVE 指针）；`pipeline` 包 = 流水线状态机 + 阶段处理器 + 幂等键（`runtimeProfileId+pipelineCode+businessTime+sourceDataVersion`，§23.1）。`MetricCalculator` 只依赖事件解析结果（复用 EventEnvelope），口径完全来自 `docs/contracts/metric-dictionary.md` v1；`QualityChecker` 执行金额对账（order_paid=order_created.total 求和一致）、空值率、主键重复、波动规则，任一核心规则失败 → 快照 VERIFYING 不过 → 不发布（保留旧 ACTIVE）。API：POST /api/v1/pipeline-runs（Idempotency-Key 支持）、GET /{id}、POST /{id}/retry、GET /api/v1/metrics/overview、GET /api/v1/snapshots。

**Tech Stack:** Java 17、Spring Boot 3.2.5、MyBatis-Plus、Flyway V3、JUnit 5（MallTestSupport）。

**外部约束（V2.2 文稿）:** §19.3 MetricStore/LandingStorage 接口；§21.11 快照发布 7 步与状态机；§23.1 流水线阶段/幂等键/失败恢复；§24.1 元数据表；§24.3 API 清单；T10 测试用例（金额不一致→阻止 ADS 更新）。

---

### Task 1: Flyway V3 元数据表

**Files:** `db/migration/V3__metrics_pipeline.sql`
- `metric_definition`（metric_code PK、公式/粒度/版本）+ 15 项指标字典种子（v1）
- `metric_snapshot`（snapshot_id 唯一、runtime_profile_id、business_time、status、version、published_at；uk(runtime_profile_id, business_time) 部分唯一——仅一个 ACTIVE 由业务事务保证）
- `metric_value`（uk(snapshot_id, metric_code)、value DECIMAL(18,4)、unit、period、definition_version）
- `pipeline_run`（idempotency_key 唯一、runtime_profile_id、pipeline_code、business_time、source_data_version、attempt_no、status、error_code、trace_id）
- `pipeline_stage_run`（run_id、stage_code、status、external_job_id、records、error_code、started/finished_at）
- `data_quality_result`（run_id、rule_code、check_count、error_count、error_rate、threshold、passed、detail）

### Task 2: 实体与 Mapper（6 表）

### Task 3: MetricStore

**Files:** `metric/MetricStore.java`（§19.3：`type()/query(MetricQuery)/publish(SnapshotRef, datasets)/healthCheck()`）、`metric/MySqlMetricStore.java`（publish：事务内 upsert metric_value + 旧 ACTIVE→ARCHIVED + 新快照→ACTIVE）、`metric/MetricDtos.java`
- Test: `metric/MySqlMetricStoreTest`（publish→query 返回一致；同快照重复 publish 幂等；旧 ACTIVE 自动 ARCHIVED 且新增 ACTIVE 唯一）

### Task 4: MetricCalculator（LOCAL ADS 角色，§21.3/§21.4 口径）

**Files:** `metric/MetricCalculator.java` — 输入 List<EventEnvelope>（按事件主题过滤），输出 MetricDataset（pv/uv/dau/fav/cart/paid_order_cnt/gmv/net_sale/avg_order_value/refund_rate/漏斗四阶段）；口径：event_time 归属日、user_id 去重、有效支付。
- Test: `metric/MetricCalculatorTest` — 用黄金数据 golden-20260901.jsonl 计算并**逐项等于标准答案**（复用 expected JSON，与 GoldenDatasetTest 同源）

### Task 5: QualityChecker（§5.4）

**Files:** `pipeline/QualityChecker.java` — 规则：金额对账（求和 order_paid.amount == Σ(order_created total)）、必要字段空值率 ≤0.1%、event_id 重复率 ≤0.05%、非法枚举=0；LEGEND：核心规则失败 → 阻断发布。落 data_quality_result。
- Test: `pipeline/QualityCheckerTest` — 正常事件全过；构造 amount 不平事件 → 核心规则失败

### Task 6: PipelineService（§23.1）

**Files:** `pipeline/PipelineService.java` + `pipeline/PipelineStageExecutor.java` — 阶段：WAIT_LANDING（events 目录有数据）→LOAD_ODS（行数统计）→BUILD_DWD（校验统计）→BUILD_DWS（主题聚合计数）→BUILD_ADS（MetricCalculator 出数）→QUALITY_CHECK→PUBLISH_METRIC（快照发布）→SUCCESS；幂等键：已存在非终态→返回原 run；终态且同键→返回原 run；attempt_no 递增。每阶段落 pipeline_stage_run。
- Test: `pipeline/PipelineRunTest` — 黄金数据作 events 源 → 全链 SUCCESS + 快照 ACTIVE + 指标 API 可查；重复提交同幂等键 → 原 run_id；金额不平源 → QUALITY_CHECK 失败 → 快照 FAILED/不发布、旧快照保留

### Task 7: Controller（§24.3 子集）

**Files:** `controller/PipelineController.java`、`controller/MetricController.java` — POST /api/v1/pipeline-runs（读 Idempotency-Key 头）、GET /api/v1/pipeline-runs/{id}、POST /api/v1/pipeline-runs/{id}/retry、GET /api/v1/metrics/overview?dt=&snapshotId=、GET /api/v1/snapshots

### Task 8: 验收

- [ ] `mvn test` 全绿（47 + 新增）
- [ ] 冒烟：生成→采集→建流水线 run（黄金数据源）→ 快照 ACTIVE → metrics API 返回一致值 → 重复幂等键返回原 run
- [ ] 更新 README（阶段 6 ✅），提交

**验收（本轮完成定义）：** 指标 API 可返回与黄金数据标准答案一致的值；快照发布状态机完整（唯一 ACTIVE、旧快照 ARCHIVED、失败保留旧版本）；流水线幂等与失败阻断可验证；与 spark-jobs 的接口已定义（快照发布接受 Spark 产出的 MetricDataset，集群模式替换 MetricCalculator 的输入）。