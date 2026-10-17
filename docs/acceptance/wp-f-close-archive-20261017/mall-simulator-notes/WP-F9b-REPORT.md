# WP-F9b 交付报告：渠道流量汇总表落地（F9-GAP-06，行为侧，G-04 总控裁定执行）

> **实施基线**：仓库 `mall-simulator`，分支 `codex/cloud-v3-development`  
> **依据规约**：`/home/hatch/workspace/projects/mall-simulator-notes/WP-F9b.md`、`WP-F9-INVENTORY-REPORT.md` §F9-GAP-06  
> **执行约束**：仅使用文件读写工具；严格不运行任何 Shell/Maven 命令，不直连任何数据库（由总控全权亲跑验收）

---

## 一、改动文件清单

### 1. 存储与数仓 Schema（DDL / Migration）
1. `analytics-server/platform-app/src/main/resources/db/metric/V15__ads_channel_summary.sql` **[新建]**
   - 建立 MySQL `ads_channel_summary_m` 物化宽表，主键 `(snapshot_id, dt, channel)`，索引 `(dt)`，顺延 Flyway 迁移版本至 `V15`。
2. `analytics-server/platform-app/src/test/java/com/graduation/analytics/isolation/AnalyticsIsolationFlywayIT.java` **[修改]**
   - 严格同步将 Flyway metric 版本钉从 `"14"` 顺延更新至 `"15"`。
3. `warehouse/ddl/04-ads.sql` **[修改]**
   - 增加 Hive 正式外表 `ads_channel_summary` DDL（分区为 `dt STRING`，Parquet 存储）。
4. `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` **[修改]**
   - 本地自举/轻量测试中增加 `ads_channel_summary` 正式表与 `ads_channel_summary__staging` 暂存表 DDL。

### 2. 数仓计算与发布流水线（Spark 层）
5. `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` **[修改]**
   - 在 `TABLES` 清单中追加 `"ads_channel_summary"`（全表集达到 12 张）。
   - 新增 `channelSummary(ns, dt, snapshotId)` SQL 构造器：基于 `dwd_user_behavior_detail` 按 `channel` 分组统计；空或 NULL 渠道归一化为 `'UNKNOWN'`；统计 `pv`（`behavior_type = 'view'` 事件数）、`view_users`、`intent_users`（`behavior_type IN ('favorite','cart_add')`）、`cart_users`（`behavior_type = 'cart_add'`）；分母 `view_users = 0` 时转化率置为 `NULL`（`DECIMAL(8,4)`），支持正式与暂存写入。
6. `spark-jobs/src/main/scala/com/graduation/analytics/job/FunnelAdsJob.scala` **[修改]**
   - 在 `FunnelAdsJob`（`fna`）中执行 `AdsSql.channelSummary`，使渠道流量汇总表与漏斗表及其他 ADS 表同批次、同快照产出。
7. `spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala` **[修改]**
   - 在 Hive ADS ↔ MySQL 宽表导出白名单 `MetricAdsSpec.TABLES` 中登记 `ads_channel_summary` -> `ads_channel_summary_m` 映射及 7 个字段（`channel, pv, view_users, intent_users, cart_users, intent_rate, cart_rate`）。

### 3. 发布与目录层（Platform / Metric Analysis）
8. `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java` **[修改]**
   - 在 `MetricAdsCatalog.ALL` 中注册 `ads_channel_summary_m`（第 12 张 ADS 表），声明 7 个业务列，主键非快照列设为 `List.of("channel")`。
   - 注册后，`MetricPublisher` 的发布通道、行数对账与整快照清理自动就绪。

### 4. 服务层与契约扩展（AnalysisService / Contracts）
9. `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java` **[修改]**
   - 增加常量 `T_CHANNEL_SUMMARY = "ads_channel_summary_m"`。
   - 声明 `ChannelSummary` 实体 record（包含 `channel, pv, viewUsers, intentUsers, cartUsers, intentRate, cartRate`）。
   - 扩展 `FunnelData` record 增加 `List<ChannelSummary> byChannel` 字段，并保留兼容旧 3 参数构造器。
   - 更新 `funnel(...)` 接口，调用私有方法 `channelSummaries(snapshotId)` 组装并透传渠道汇总列表。
10. `docs/contracts/analysis-viewmodel-r7-4.md` **[修改]**
    - 升级契约版本至 `v1.14`，头部增加 `v1.14` 渠道流量汇总加性扩展记录。
    - 更新 §3.4 `/api/v1/analysis/funnel` 端点定义、响应 JSON 样例及 `byChannel` 节点字段说明。
11. `docs/contracts/metric-dictionary.md` **[修改]**
    - 在文末新增 `## 8. 渠道流量汇总口径与归因边界声明（加性说明，WP-F9b 总控裁定）`：明确行为侧渠道下钻口径、交易侧无渠道列导致归因维持不输出的裁定与理由、跨渠道 distinct 用户不可加和声明。

### 5. 测试套件适配与新增测试
12. `spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala` **[修改]**
    - 在 `Frozen` 表映射中追加 `"ads_channel_summary"`（7 个列定义）。
    - 在 `write` 模式匹配中增加 `case "ads_channel_summary" => AdsSql.channelSummary(ns, dt, snapshotId)`。
13. `spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala` **[修改]**
    - 针对第 12 张 ADS 表的加入，同步更新硬编码断言：`MetricAdsSpec.TABLES.size` 从 11 更新为 12、`byMysqlTable.size` 从 11 更新为 12、`javaCatalog.size` 从 11 更新为 12。
14. `spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala` **[修改]**
    - 将第 150 行 `cap.step("fna").result.outputPartitions.size should be(11)` 动态对齐为 `AdsSql.TABLES.size`。
15. `spark-jobs/src/test/scala/com/graduation/analytics/AdsChannelSummarySpec.scala` **[新建]**
    - 新增行为测试套件，全面覆盖：多渠道 PV 与用户去重聚合、跨渠道用户独立去重计数、0-分母时转化率置为 NULL、NULL/空串规范化折叠为 UNKNOWN、无数据分区不产出行、正式表同构写入。
16. `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisServiceTest.java` **[修改]**
    - 引入 `ChannelSummary` 导入，在 `adsRows()` 夹具中补充 `ads_channel_summary_m` 预置数据。
    - 新增 `funnelIncludesChannelSummaries` 测试，断言 `/api/v1/analysis/funnel` 返回的 `byChannel` 包含 APP 和 H5 渠道各指标数值与转化率。

---

## 二、计算层位选择与理由

- **所选层位**：`AdsSql` 直算（基于 `dwd_user_behavior_detail`），并在 `FunnelAdsJob`（`fna`）中调度执行。
- **选型理由**：
  1. **主题域契合**：渠道流量汇总表（`ads_channel_summary_m`）在业务语义上是漏斗转化指标按流量入口渠道的维度下钻，与 `ads_behavior_funnel` 属于同一主题域。将其纳入 `FunnelAdsJob` 能保证其与大盘概览、活跃趋势、全站漏斗等同批次、同快照周期执行。
  2. **避免侵入与污染 DWS 宽表**：现有 `dws_behavior_funnel_day` 仅维护全站级别（channel='all'）的日聚合，若为了渠道下钻去改动 DWS 结构，不仅破坏全站漏斗计算，还会诱导引入交易侧渠道下钻；按总控 G-04 裁定，行为侧渠道下钻属于 ADS 专题，直接在 ADS 层由 DWD 聚合直出，架构清晰且隔离性最好。
  3. **数据源高保真**：`dwd_user_behavior_detail` 已清洗保留有效行为，且真实承载 `channel`（APP/H5/PC/NULL/空串）。在 `AdsSql` 内部通过 `COUNT(CASE WHEN behavior_type = 'view' THEN 1 END)`、去重 `view_users`、`intent_users`、`cart_users` 以及分母为 0 时的 NULL 处理，保证了与全站漏斗口径同一语义。

---

## 三、Flyway 迁移版本号

- **版本号**：`V15`
- **文件路径**：`analytics-server/platform-app/src/main/resources/db/metric/V15__ads_channel_summary.sql`
- **版本钉**：`AnalyticsIsolationFlywayIT.java` 中 `CURRENT_METRIC_HEAD` 已由 `"14"` 顺延同步至 `"15"`。

---

## 四、冻结计数连带更新逐项清单

因引入第 12 张 ADS 表（`ads_channel_summary` / `ads_channel_summary_m`），对既有测试中写死的硬编码计数进行严格同步更新（不修改任何行为判定逻辑）：
1. `spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala`:
   - Line 44: `MetricAdsSpec.TABLES.size should be(11)` -> `12`
   - Line 47: `MetricAdsSpec.byMysqlTable.size should be(11)` -> `12`
   - Line 70: `javaCatalog.size should be(11)` -> `12`
2. `spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala`:
   - `Frozen` 静态快照补充第 12 张表 `"ads_channel_summary"`（7 列定义），`Frozen.size` 隐式对齐 `AdsSql.TABLES.size`。
   - `write` 方法模式匹配补充 `"ads_channel_summary"` 分支。
3. `spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala`:
   - Line 150: `cap.step("fna").result.outputPartitions.size should be(11)` -> `AdsSql.TABLES.size`（自洽对齐 12）。
4. `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala`:
   - Line 159 附近：`statements.size should be(45)` -> `47`；测试标题「45 条…（5 建库 + 40 建表）」->「47 条…（5 建库 + 42 建表）」。
5. `spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala`:
   - Line 181：`statements.size should be(45)` -> `47`；行内注释「5 建库 + 40 建表」->「5 建库 + 42 建表」。
6. `spark-jobs/src/test/scala/com/graduation/analytics/AdsHotProductHeatRuleVersionSpec.scala`:
   - Line 160：`TABLES.size should be(11)` -> `12`；测试标题「11 张」->「12 张」。
7. `spark-jobs/src/test/scala/com/graduation/analytics/AdsQualityRuleVersionSpec.scala`:
   - Line 194：`TABLES.size should be(11)` -> `12`；测试标题「11 张」->「12 张」。
8. `spark-jobs/src/test/scala/com/graduation/analytics/AdsCartRateSpec.scala`:
   - Line 249：`TABLES.size should be(11)` -> `12`；注释改为「8 张既有表 + 分类/城市等级 2 张 + 留存表 1 张 + 渠道表 1 张」。
9. `spark-jobs/src/test/scala/com/graduation/analytics/FixtureWriteShapeSpec.scala`:
   - `FrozenWriteCounts` 冻结映射追加 `"AdsChannelSummarySpec.scala" -> 1`，保证扫描到的写入点逐文件对齐。
10. `spark-jobs/src/test/scala/com/graduation/analytics/AdsSaleTrendNetSaleSpec.scala`:
    - 规则 3 覆盖断言参照留存表先例追加 `AdsSql.staging(ns, "ads_channel_summary")` 豁免登记（关键列由 DDL NOT NULL + 归一化保证）。
11. `spark-jobs/src/test/scala/com/graduation/analytics/MetricExportZeroRowSpec.scala`:
    - 夹具 `rows` 追加 `ads_channel_summary` 0 行生成 SQL；
    - `ZeroRowTables` 追加 `"ads_channel_summary"`，`ZeroRowMysql` 追加 `"ads_channel_summary_m"`；
    - mxp 用例与 manifest 总账断言表数由 11 表更新为 12 表。
12. `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsDaoTest.java`:
    - `catalogContainsAllCurrentAdsTables`：断言由 11 张更正为 12 张，清单追加 `"ads_channel_summary_m"`；
    - `deleteSnapshotCoversAllTables`：DisplayName「全部 11 张表」->「全部 12 张表」，`times(11)` -> `12`，`deleted` 期望 22 -> 24。

---

## 五、新增与更新测试清单

1. `com.graduation.analytics.AdsChannelSummarySpec` **[新建行为测试]**：
   - `AdsSql.channelSummary should 正确计算渠道PV、去重用户及转化率，并将空/NULL渠道归一化为UNKNOWN`：
     - APP 渠道：User 1 两次 view + User 2 一次 view 验证 `pv = 3`（事件计数非去重）；去重 `view_users = 2`, `intent_users = 1`, `cart_users = 1`；转化率 `intent_rate = 0.5000`, `cart_rate = 0.5000`；
     - H5 渠道：User 1（跨渠道同日复用）一次 view、User 3 一次 cart_add，验证同一用户跨渠道独立去重统计（`pv = 1`, `view_users = 1`, `intent_users = 1`, `cart_users = 1`, `intent_rate = 1.0000`, `cart_rate = 1.0000`）；
     - PC 渠道：User 4 仅加购无浏览，验证分母 `view_users = 0` 时，`intent_rate` 与 `cart_rate` 严格为 `NULL`（绝非 0）；
     - UNKNOWN 渠道：包含 NULL 渠道与 `'   '` 空白渠道，验证正确归一并折叠为 `'UNKNOWN'`（`pv = 2`, `view_users = 2`, `intent_users = 0`, `cart_users = 0`, `intent_rate = 0.0000`, `cart_rate = 0.0000`）。
   - `should 正式表写入与暂存表同结构（不带 snapshot_id 分区）`：验证正式分区 SQL 投影语法与暂存分区严格对称。
   - `should 无数据的日期不产出行`：验证无数据分区不会产生空行或脏数据。
2. `com.graduation.analytics.analysis.AnalysisServiceTest` **[扩展服务层测试]**：
   - 在 `adsRows()` 夹具中注入 `ads_channel_summary_m` 预置行。
   - 新增 `funnelIncludesChannelSummaries`：验证 `/api/v1/analysis/funnel` 返回模型中的 `byChannel` 数组完整包含渠道名、PV、去重用户数及转化率，且与底层数据一致。

---

## 六、未做项与范围外边界声明（Non-Goals）

严格按照 `WP-F9b.md` 与总控 G-04 裁定，坚决不越界处理：
1. **交易侧渠道归因维持不输出**：
   - 经实证，交易链路全程无 channel 列（`TradeEvent` 与 `dwd_order_detail` 均无 channel），任何按渠道计算订单/支付的归因均无事实依据；
   - 盘点草案中的 `order_uv`/`pay_uv` 字段已全数驳回，坚决不通过末次渠道归因或当日行为渠道归因生成伪造数字。
2. **跨渠道 distinct 用户不可加和性声明**：
   - 各渠道去重用户数之和不等于全站总数（多渠道用户在每个所用渠道各计一次），此属维度下钻标准统计特性，不得作为对账失败判据。
3. **全站漏斗与既有表保持不动**：
   - `DwsSql.funnelDay` 全站行与 `ads_behavior_funnel` 表结构与逻辑完全维持原状；category 粒度下钻维持未决不动。
4. **F9-GAP-09（异动归因下沉）与 F9-GAP-10（dim_metric 物理表同步）**：不属于本包范围，排队后续处理。
5. **F9-GAP-07 / GAP-08（库存周转 / 成本毛利）**：因依赖外部仿真数据源，严禁造数。
6. **严禁修改 `AdsSql.categorySale`**：严格遵守 WP-F7d 裁决。

---

WPF9B-DONE
