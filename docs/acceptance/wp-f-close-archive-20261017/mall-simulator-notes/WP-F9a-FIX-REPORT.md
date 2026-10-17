# WP-F9a-FIX 修复交付报告：连带冻结断言同步

> **实施基线**：仓库 `mall-simulator`，分支 `codex/cloud-v3-development`  
> **依据规约**：`/home/hatch/workspace/projects/mall-simulator-notes/WP-F9a-FIX.md`  
> **执行约束**：仅使用文件读写工具；不运行任何 Shell 命令、Maven 构建或测试（由总控亲跑验证）；只修改测试文件，生产代码零改动。

---

## 修复项逐项核对清单

### A. spark 表数断言 10→11

#### 1. `MetricAdsSpecTest.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala`
- **位置**：L44、L47、L70
- **改前**：
  ```scala
  // L44, L47
  MetricAdsSpec.TABLES.size should be(10)
  ...
  MetricAdsSpec.byMysqlTable.size should be(10)
  ...
  // L70
  javaCatalog.size should be(10)
  ```
- **改后**：
  ```scala
  // L44, L47 (注：L47 与 L44 同属一个用例，因首轮在 L44 失败短路未直接报错，同属 10->11 同步范围，一并修复)
  MetricAdsSpec.TABLES.size should be(11)
  ...
  MetricAdsSpec.byMysqlTable.size should be(11)
  ...
  // L70
  javaCatalog.size should be(11)
  ```

#### 2. `AdsHotProductHeatRuleVersionSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsHotProductHeatRuleVersionSpec.scala`
- **位置**：L159-L160
- **改前**：
  ```scala
  "ADS 热门商品列清单" should "末尾追加 rule_version（热度权重定义版本），且当前 ADS 表集为 10 张" in {
    MetricAdsSpec.TABLES.size should be(10)
  ```
- **改后**：
  ```scala
  "ADS 热门商品列清单" should "末尾追加 rule_version（热度权重定义版本），且当前 ADS 表集为 11 张" in {
    MetricAdsSpec.TABLES.size should be(11)
  ```

#### 3. `AdsQualityRuleVersionSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsQualityRuleVersionSpec.scala`
- **位置**：L193-L194
- **改前**：
  ```scala
  "ADS 质量大盘列清单" should "末尾追加 rule_version（定义版本），且当前 ADS 表集为 10 张" in {
    MetricAdsSpec.TABLES.size should be(10)
  ```
- **改后**：
  ```scala
  "ADS 质量大盘列清单" should "末尾追加 rule_version（定义版本），且当前 ADS 表集为 11 张" in {
    MetricAdsSpec.TABLES.size should be(11)
  ```

#### 4. `AdsCartRateSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsCartRateSpec.scala`
- **位置**：L249
- **改前**：
  ```scala
  MetricAdsSpec.TABLES.size should be(10) // 当前 ADS 镜像含 8 张既有表 + 本批分类/城市等级 2 张
  ```
- **改后**：
  ```scala
  MetricAdsSpec.TABLES.size should be(11) // 当前 ADS 镜像含 8 张既有表 + 本批分类/城市等级 2 张 + 留存表 1 张
  ```

#### 5. `DwsAdsChainExecSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala`
- **位置**：L150
- **改前**：
  ```scala
  cap.step("fna").result.outputPartitions.size should be(10)
  ```
- **改后**：
  ```scala
  cap.step("fna").result.outputPartitions.size should be(11)
  ```

#### 6. `AdsSaleTrendNetSaleSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsSaleTrendNetSaleSpec.scala`
- **位置**：L239
- **改前**：
  ```scala
  predicates.keySet should be(FunnelAdsJob.stagingTables(ns).toSet)
  ```
- **改后**：
  ```scala
  (predicates.keySet + AdsSql.staging(ns, "ads_user_retention")) should be(FunnelAdsJob.stagingTables(ns).toSet)
  ```

---

### B. DDL 总数断言 43→45

#### 7. `WarehouseNamespaceSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala`
- **位置**：L156-L159
- **改前**：
  ```scala
  it should "INIT_SCHEMA 的 43 条 DDL 全部只引用当前命名空间的库（5 建库 + 38 建表）" in {
    val ns = WarehouseNamespace.of("dw_b")
    val statements = LocalSchemaInitJob.statements(ns)
    statements.size should be(43)
  ```
- **改后**：
  ```scala
  it should "INIT_SCHEMA 的 45 条 DDL 全部只引用当前命名空间的库（5 建库 + 40 建表）" in {
    val ns = WarehouseNamespace.of("dw_b")
    val statements = LocalSchemaInitJob.statements(ns)
    statements.size should be(45)
  ```

#### 8. `OdsV2SchemaOwnerSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala`
- **位置**：L181
- **改前**：
  ```scala
  statements.size should be(43) // 5 建库 + 38 建表；含维度日期/地区、分类与 staging 表
  ```
- **改后**：
  ```scala
  statements.size should be(45) // 5 建库 + 40 建表；含维度日期/地区、分类与 staging 表
  ```

---

### C. FixtureWriteShapeSpec 与新测试种子范式

#### 9. `FixtureWriteShapeSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/FixtureWriteShapeSpec.scala`
- **位置**：L91
- **改前**：
  ```scala
    "DwsRegionNetSaleSpec.scala" -> 1,
    "SurrogateKeySpec.scala" -> 1
  )
  ```
- **改后**：
  ```scala
    "DwsRegionNetSaleSpec.scala" -> 1,
    "SurrogateKeySpec.scala" -> 1,
    "AdsUserRetentionSpec.scala" -> 4
  )
  ```

#### 10. `AdsUserRetentionSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/AdsUserRetentionSpec.scala`
- **位置**：L46-L105
- **改前**：4 处直写的 `INSERT OVERWRITE ... SELECT 'b_999_0', 999L...`
- **改后**：改写为既有夹具标准投影范式（外层显式选择 15 个同位数据列，内层子查询 `FROM (...) v` 映射，种子数据与 5 个行为测试断言逐字严格未改）：
  ```scala
  private def seedData(): Unit = {
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260831')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_999_0' AS behavior_id, 999L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260831' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's0' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260901')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_101_1' AS behavior_id, 101L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260901' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_102_1' AS behavior_id, 102L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260901' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260902')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_101_2' AS behavior_id, 101L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260902' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_103_2' AS behavior_id, 103L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260902' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)

    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260903')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  SELECT 'b_103_3' AS behavior_id, 103L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260903' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |  UNION ALL
         |  SELECT 'b_104_3' AS behavior_id, 104L AS user_id, 1L AS product_id, 1L AS category_id,
         |         'view' AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time, '20260903' AS event_date,
         |         10 AS event_hour, 'tier_1' AS city_level, 'APP' AS channel, 's1' AS session_id,
         |         1L AS source_batch_id, 1L AS user_key, 1L AS product_key, 1L AS category_key
         |) v""".stripMargin)
  }
  ```

---

### D. MetricExportZeroRowSpec

#### 11. `MetricExportZeroRowSpec.scala`
- **文件**：`spark-jobs/src/test/scala/com/graduation/analytics/MetricExportZeroRowSpec.scala`
- **位置**：L88、L95、L182、L242、L261-L263、L368、L375
- **改前**：
  ```scala
  // L88, L95
  "mxp 对含 0 行专题的 10 表导出整体 SUCCESS（修复前此处抛 UNABLE_TO_INFER_SCHEMA）" should
    "SNAPSHOT_PINNED / EXPORT_ROWS / EXPORT_COMPLETE 全部通过" in {
      ...
      rows should have size 10
  ...
  // L182
  "manifest 总账（§12.5 L528）" should "10 表齐全、snapshotId/dt 正确、totalRows 为逐表之和、hivePath 指向本次快照" in {
  ...
  // L242 rows 定义仅铺设 10 张表，缺 ads_user_retention
  ...
  // L368, L375
  private val ZeroRowTables = Seq("ads_hot_product", "ads_product_conversion", "ads_category_sale", "ads_region_sale")
  ...
  private val ZeroRowMysql: Set[String] = Set(
    "ads_hot_product_m", "ads_product_conversion_m", "ads_category_sale_m", "ads_region_sale_m")
  ```
- **改后**：
  ```scala
  // L88, L95
  "mxp 对含 0 行专题的 11 表导出整体 SUCCESS（修复前此处抛 UNABLE_TO_INFER_SCHEMA）" should
    "SNAPSHOT_PINNED / EXPORT_ROWS / EXPORT_COMPLETE 全部通过" in {
      ...
      rows should have size 11
  ...
  // L182
  "manifest 总账（§12.5 L528）" should "11 表齐全、snapshotId/dt 正确、totalRows 为逐表之和、hivePath 指向本次快照" in {
  ...
  // L242, L261 铺设 11 张暂存分区，补入零行留存表夹具：
  "ads_user_retention" ->
    "SELECT 0L, 0L, CAST(0.0000 AS DECIMAL(8,4)), 0L, CAST(0.0000 AS DECIMAL(8,4)), 0L, CAST(0.0000 AS DECIMAL(8,4)) FROM (SELECT 1) dual WHERE 1 = 0"
  ...
  // L368, L375 零行清单追加留存表：
  private val ZeroRowTables = Seq("ads_hot_product", "ads_product_conversion", "ads_category_sale", "ads_region_sale", "ads_user_retention")
  ...
  private val ZeroRowMysql: Set[String] = Set(
    "ads_hot_product_m", "ads_product_conversion_m", "ads_category_sale_m", "ads_region_sale_m", "ads_user_retention_m")
  ```

---

### E. Java 目录断言

#### 12. `MetricAdsDaoTest.java`
- **文件**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsDaoTest.java`
- **位置**：L41-L47、L120-L128
- **改前**：
  ```java
  @Test
  @DisplayName("10 张 ADS 白名单包含已实现的 category/region 表")
  void catalogContainsAllCurrentAdsTables() {
      assertThat(MetricAdsCatalog.ALL).hasSize(10);
      assertThat(MetricAdsCatalog.ALL).extracting(MetricAdsCatalog::name)
              .containsExactly("ads_operation_overview_m", "ads_sale_trend_m", "ads_behavior_funnel_m",
                      "ads_active_trend_m", "ads_hot_product_m", "ads_product_conversion_m",
                      "ads_user_profile_m", "ads_data_quality_m", "ads_category_sale_m", "ads_region_sale_m");
  ...
  @Test
  @DisplayName("deleteSnapshot 覆盖全部 10 张表并按 snapshot_id 过滤")
  void deleteSnapshotCoversAllTables() {
      ...
      verify(publishJdbc, times(10)).update(sql.capture(), any(Object[].class));
      assertThat(deleted).isEqualTo(20);
  ```
- **改后**：
  ```java
  @Test
  @DisplayName("11 张 ADS 白名单包含已实现的 category/region 表")
  void catalogContainsAllCurrentAdsTables() {
      assertThat(MetricAdsCatalog.ALL).hasSize(11);
      assertThat(MetricAdsCatalog.ALL).extracting(MetricAdsCatalog::name)
              .containsExactly("ads_operation_overview_m", "ads_sale_trend_m", "ads_behavior_funnel_m",
                      "ads_active_trend_m", "ads_hot_product_m", "ads_product_conversion_m",
                      "ads_user_profile_m", "ads_data_quality_m", "ads_category_sale_m", "ads_region_sale_m",
                      "ads_user_retention_m");
  ...
  @Test
  @DisplayName("deleteSnapshot 覆盖全部 11 张表并按 snapshot_id 过滤")
  void deleteSnapshotCoversAllTables() {
      ...
      verify(publishJdbc, times(11)).update(sql.capture(), any(Object[].class));
      assertThat(deleted).isEqualTo(22);
  ```

---

## 红线守则遵循声明

1. **生产代码零修改**：未触碰 `AdsSql.userRetention`、目录、迁移、服务层等任何生产文件。
2. **种子语义与断言完整保全**：未修改 `AdsUserRetentionSpec` 中 5 个用例的任何断言与种子数据值。
3. **范围严格隔离**：未触碰 `synthetic-data-generator`、`web`、`scripts` 或任何非清单测试文件。
4. **纯文件工具操作**：未执行任何 Shell 命令、Maven 构建或单元测试，全量留由总控亲跑复验。

WPF9AFIX-DONE
