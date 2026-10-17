# WP-F9b-FIX 交付报告：首轮验收红因修复（全测试侧连带收敛）

> **实施基线**：仓库 `mall-simulator`，分支 `codex/cloud-v3-development`  
> **依据规约**：`/home/hatch/workspace/projects/mall-simulator-notes/WP-F9b-FIX.md`  
> **执行约束**：仅使用文件读写工具；严格不运行任何构建、Shell 命令，不直连任何数据库；严禁修改任何生产代码（`src/main` 零变动）

---

## 一、修复背景与定位

WP-F9b 首轮总控亲跑验收（spark 门禁 RunId `wpf9bacc20261017` exit 7；default 定向亲跑 `MetricAdsDaoTest` 2 红）。经总控复核，生产代码（`AdsSql.channelSummary` 与 `DwsSql.funnelDay`、V15 DDL、`AnalysisService` 45/45 绿）完全正确。

全部红因均集中在测试侧：
1. **测试期望算错**：`AdsChannelSummarySpec.scala` 对 intent 用户及转化率口径复算偏差；
2. **冻结计数/清单连带漏更**：新增第 12 张 ADS 表后，数仓初始化 DDL 总数（45 → 47）、ADS 表总数（11 → 12）、夹具写入点白盒扫描清单、规则 3 暂存表覆盖集合、mxp 0 行夹具及 Java DAO 断言未同步顺延。

本轮修复严格钉死在测试侧与交付文档，无任何生产代码变更。

---

## 二、改动逐项清单

### 1. A. `AdsChannelSummarySpec.scala`（期望独立复算更正）
- **文件路径**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsChannelSummarySpec.scala`
- **改动详情**：
  - 依据种子事实 `intent = behavior_type IN ('favorite','cart_add')` 去重：
    - **APP 渠道**：User 1 (view×2, cart_add×1) + User 2 (view×1) ⇒ `pv = 3`, `view_users = 2`, `intent_users = 1`（仅 U1），`cart_users = 1`，`intent_rate = 0.5000`，`cart_rate = 0.5000`（原误写为 intent 2 与 rate 1.0000）；
    - **H5 渠道**：User 1 (view×1) + User 3 (cart_add×1) ⇒ `pv = 1`, `view_users = 1`, `intent_users = 1`（仅 U3），`cart_users = 1`，`intent_rate = 1.0000`，`cart_rate = 1.0000`（原误写为 intent 2 与 rate 2.0000）；
    - **PC 渠道**：保持不变（`pv = 0`, `view_users = 0`, `intent_users = 1`, `cart_users = 1`, 两率严格为 `NULL`）；
    - **UNKNOWN 渠道**：User 5 (view) + User 6 (view) ⇒ `pv = 2`, `view_users = 2`, `intent_users = 0`, `cart_users = 0`, `intent_rate = 0.0000`, `cart_rate = 0.0000`（原误写为 intent 2 与 rate 1.0000）；
  - 同步更正正式表写入用例中 APP 渠道 `intent_users` 断言为 `1L`；
  - 同步更新文件头部种子数据注释块数值。

### 2. B. 冻结计数连带修复（计数/清单/标题/注释）
1. `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala`:
   - L156/L159：`statements.size should be(45)` → `47`；测试标题「45 条…（5 建库 + 40 建表）」→「47 条…（5 建库 + 42 建表）」。
2. `spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala`:
   - L181：`statements.size should be(45)` → `47`；行内注释「5 建库 + 40 建表」→「5 建库 + 42 建表」。
3. `spark-jobs/src/test/scala/com/graduation/analytics/AdsHotProductHeatRuleVersionSpec.scala`:
   - L159-L160：`TABLES.size should be(11)` → `12`；测试标题「11 张」→「12 张」。
4. `spark-jobs/src/test/scala/com/graduation/analytics/AdsQualityRuleVersionSpec.scala`:
   - L193-L194：`TABLES.size should be(11)` → `12`；测试标题「11 张」→「12 张」。
5. `spark-jobs/src/test/scala/com/graduation/analytics/AdsCartRateSpec.scala`:
   - L249：`TABLES.size should be(11)` → `12`；注释更正为「8 张既有表 + 分类/城市等级 2 张 + 留存表 1 张 + 渠道表 1 张」。
6. `spark-jobs/src/test/scala/com/graduation/analytics/FixtureWriteShapeSpec.scala`:
   - `FrozenWriteCounts` 静态写入点冻结图：追加条目 `"AdsChannelSummarySpec.scala" -> 1`，保证白盒扫描的夹具写入点数与冻结清单完全吻合。

### 3. C. `AdsSaleTrendNetSaleSpec.scala` 规则 3 用例（豁免登记）
- **文件路径**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsSaleTrendNetSaleSpec.scala`
- **改动详情**：
  - 按照 WP-F9a 留存表先例，断言更正为：
    `(predicates.keySet + AdsSql.staging(ns, "ads_user_retention") + AdsSql.staging(ns, "ads_channel_summary")) should be(FunnelAdsJob.stagingTables(ns).toSet)`
  - 添加注释明确渠道表与留存表同例不入 `keyPredicates`（关键列由 DDL NOT NULL 与空值归一化保证）；
  - 测试用例标题「覆盖全部 10 张暂存表」保持不变；生产代码 `AdsQualityJob` 坚决不作修改。

### 4. D. `MetricExportZeroRowSpec.scala`（渠道表 0 行专题连带）
- **文件路径**：`spark-jobs/src/test/scala/com/graduation/analytics/MetricExportZeroRowSpec.scala`
- **改动详情**：
  - 夹具 `rows` 列表追加一条：
    `"ads_channel_summary" -> "SELECT 'APP', 0L, 0L, 0L, 0L, CAST(0.0000 AS DECIMAL(8,4)), CAST(0.0000 AS DECIMAL(8,4)) FROM (SELECT 1) dual WHERE 1 = 0"`；
  - 夹具注释「铺 11 张暂存分区」更新为 12 张；
  - `ZeroRowTables` 追加 `"ads_channel_summary"`；`ZeroRowMysql` 追加 `"ads_channel_summary_m"`；
  - mxp 导出用例：标题更新为「12 表导出」，`rows should have size 11` → `12`；
  - manifest 总账用例：标题更新为「12 表齐全」；
  - `nonEmpty`（6 张）与 `totalRows`（`RealRowTables.size`）保持不动。

### 5. E. `MetricAdsDaoTest.java`（Java 平台侧连带）
- **文件路径**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsDaoTest.java`
- **改动详情**：
  - `catalogContainsAllCurrentAdsTables`：DisplayName 更新为「12 张」，`hasSize(11)` → `12`，`containsExactly(...)` 末尾顺延追加 `"ads_channel_summary_m"`（与 `MetricAdsCatalog.ALL` 逐字对齐）；
  - `deleteSnapshotCoversAllTables`：DisplayName 更新为「全部 12 张表」，`times(11)` → `12`，`deleted` 期望返回值从 22 更新为 24。

### 6. F. 报告更正
- **文件路径**：`WP-F9b-REPORT.md`
- **改动详情**：
  - §四冻结清单全量补齐 B、C、D、E 各套件变更记录；
  - §五新增与更新测试清单中，APP、H5、UNKNOWN 期望数值与转化率按 A 节独立复算结论更正。

---

## 三、未做项与红线声明（Non-Goals）

1. **严禁修改任何生产代码**：
   - 生产源码（`spark-jobs/src/main/...`、`analytics-server/**/src/main/...`）未做任何编辑；
   - `AdsSql.channelSummary` 逻辑与精度未做任何改动；
   - `AdsQualityJob` 关键列谓词未做改动；
2. **严禁直连数据库与调用外部服务**：
   - 未连接 MySQL 3306/3307；
   - 未运行 Docker 容器；
   - 未调用 DeepSeek 或其它外部大模型；
3. **严禁越权执行命令**：
   - 全程仅使用文件读写工具；
   - Maven 编译、Spark 测试套件与回归验证门禁交由总控亲跑。

---

WPF9BFIX-DONE
