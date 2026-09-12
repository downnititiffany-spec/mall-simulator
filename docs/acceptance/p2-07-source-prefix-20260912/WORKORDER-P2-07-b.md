# P2-07-b 施工单：血缘库名必须来自「快照自己的源」（2026-09-12 总控出单）

> 本单一句话：**`metric_snapshot` 补 `source_id`（预期 V19），血缘页面的库名按快照的源解析，而不是按当前 ACTIVE 档案解析。**
> 依据：`docs/acceptance/p2-07-source-prefix-20260912/RULINGS-20260912.md` **D-077**（本轮明确不做，另立任务）。本单只出**施工要求**，不含任何实现。

## 0 为什么必须做（实测事实，非推断）

`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java`：

- **L56**：`private final WarehouseNamespaceProvider namespaceProvider;`（注入的第二读取端口）
- **L65**：`MetricSnapshot meta = metricStore.findSnapshot(snapshotId);`
- **L302**：`private EvidencePackage.Lineage lineage(String snapshotId, MetricSnapshot meta, …)`
- **L316**：`MetricLineage.hiveTables(mysql, namespaceProvider.current())` ← **身份取自 `snapshotId`/`meta`，库名却取自 `current()`**
- **L317**：`meta == null ? null : meta.getPipelineRunId(), snapshotId`

即：**同一行里，身份是历史快照，库名是当前档案**。P2-07 落地后 `current()` 会经 ACTIVE 档案的 `source_id` 解析 ⇒ 行为仍是"当前源"。于是**切换数据源之后，历史快照的血缘会显示新源的库名**，属可复现的错误展示（不是崩溃，是静默错误）。

## 1 已核实的库/表事实（本单出单时实测，执行者须**重取**）

| 事实 | 读数 | 取证方式 |
|---|---|---|
| `metric_snapshot` 存在于 **10 个 schema** | `analytics_meta`、`analytics_meta_p103`、`analytics_meta_p105`、`analytics_meta_p105it`、`analytics_meta_v17probe`、`analytics_metric`、`analytics_metric_p103`、`analytics_metric_p105`、`mall_simulator`、`mall_simulator_test` | `information_schema.TABLES` 只读查询 |
| `analytics_metric.metric_snapshot` 列（最新 5 行 `source='spark-ads'`） | 有 **`source varchar(32) NOT NULL`**，**无** `source_id`；另有 `runtime_profile_id`、`runtime_profile_version`、`pipeline_run_id`、`snapshot_id`、`status`、`active_flag` 等 | 同上 + `SELECT … ORDER BY id DESC LIMIT 5` |
| `pipeline_run` 在 `analytics_meta`（**不在** `analytics_metric`） | —— | 同上 |

> **⚠ 执行前第一步（强制）**：先查清**平台实际读写哪一个 schema 的哪一张 `metric_snapshot`**（看数据源配置与 `MySqlMetricStore`），再动手。同名表跨 10 个 schema，其中 7 个是历史副本/探针残留（见看板 **F-40**）——**打错库是本任务的头号风险**，写迁移之前必须把目标 schema 名写进报告并给出判断依据。

## 2 交付要求（A1–A7）

- **A1 迁移**：`analytics-server/platform-app/src/main/resources/db/meta/V19__metric_snapshot_source_id.sql`（号位由总控分配；实测真库 `flyway_schema_history` 最高 17，V18 由 P2-07 占用）。
  三步式（与 D-071/V18 同构，**不设 DEFAULT**）：① `ADD COLUMN source_id BIGINT NULL`；② 回填——**只回填能确定的**：凡该快照的 `pipeline_run_id` 能追到唯一 `runtime_profile.source_id` 者方可回填，**追不到的一律留 NULL，不得猜**（回填语句须显式 `updated_at = updated_at`）；③ 加索引 `idx_metric_snapshot_source(source_id)`；**不加 NOT NULL**（历史行无法确定 ⇒ 收紧会失败，且 NULL 是**有语义的**"未知源"，见 A4）。
- **A2 写入侧**：发布快照时写入 `source_id`，取值**必须复用 P2-07 建立的同一"运行源身份"**（不得另建解析链；同一份运行只能有一个源身份所有者）。
- **A3 实体/DTO**：`MetricSnapshot` 增字段 `sourceId`（Java 名），与既有字段风格一致；对外 API 形状**只增不改**（缺省 `null`）。
- **A4 读取侧（本单的真正目的）**：`EvidenceBuilder.lineage(...)` 改为按**快照的源**解析库名：`meta.getSourceId()` 非空 ⇒ `namespaceProvider.forSource(meta.getSourceId())`；`source_id` 为 **NULL ⇒ 血缘里显式标注"源未知"**（例如库名列表留空 + 一条 warning 进入证据包），**严禁**回落到 `current()` 或任何默认前缀 —— 静默给错库名比给不出更糟（fail-closed，同 D-073 精神）。
- **A5 残留引用**：改完后 `EvidenceBuilder` 内**不得**再出现为血缘服务的 `namespaceProvider.current()`；其它调用点如仍用 `current()` 须逐个说明理由（"当时"语义 vs "当前"语义必须显式区分）。
- **A6 测试**：① 快照源 = A、ACTIVE 源 = B ⇒ 血缘给 **A 的库名**（这是本任务的核心断言，必须真跑）；② `source_id` 为 NULL 的快照 ⇒ 显式"源未知"，且**断言不出现任何 `dw_*` 猜测**；③ 迁移在**只读转储副本库**上执行并通过（沿用 P1-05 `e3-20` / P2-07 E3 的副本模式），**不得**对真库执行；④ 现有血缘测试全绿（回归）。
- **A7 证据与报告**：`docs/acceptance/p2-07-source-prefix-20260912/` 下新增 `P2-07-B-REPORT.md` + `raw/` 原始日志（命令、退出码、`Tests run:` 原文）。

## 3 硬约束

- **模块单写者**：本任务写 `analytics-server/**`；开单前必须确认 P2-07 实施泳道**已收工**（同一模块禁并发写、禁并发 Maven）。
- **`spark-jobs/**` 零改动**；**不启停任何进程**；**真库只读**（迁移只在副本库跑）。
- 不改 `contract-specs/**`、不改看板、不做 git 写操作（总控提交）。
- 命中以下情况**停下报告**：① 目标 `metric_snapshot` 不在预期 schema；② 回填需要"猜"；③ 需要重启 8091；④ 需要改 `spark-jobs/**`；⑤ 工作区有本泳道之外的文件消失。

## 4 完成判据（E1/E2/E3）

- **E1**：`analytics-server` 编译通过（命令与退出码）。
- **E2**：模块单测通过，**含 A6 的四条**（原始日志 `Tests run: …` 落盘）。
- **E3**：V19 在**副本库**上执行成功、行数与回填分布（非空/NULL 各多少）有读数；**真库未执行**（明确写出）。
- **边界声明**：切换源后的真实血缘页面**未在真链路验证**（需重启 8091 + 前移 ACTIVE，属 D-075 未批准块）⇒ 报告须写"未取证"，**不得**声称"血缘已按快照"。

## 5 与其它任务的关系

- **P2-07-c**（Scala 侧契约引用改指 + 22 向量复跑）在 P2-01 释放 `spark-jobs/**` 后开。
- **P2-03 实施**（代理键）排在 P2-01 之后；与 P2-07-b 同属 `analytics-server`/`spark-jobs` 交错区 ⇒ **同一时刻只开一条**。
- **F-40**（同名表跨 schema + 副本残留）与本单共用"先认库"的前置动作。
