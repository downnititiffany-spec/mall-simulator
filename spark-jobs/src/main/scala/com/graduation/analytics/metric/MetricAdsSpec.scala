package com.graduation.analytics.metric

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * R7-3（V2.0 §17.3/§17.4）：Hive 正式 ADS ↔ analytics_metric 物化宽表 的**唯一映射清单**。
 *
 * 本清单是"列名/顺序"的单一事实来源（Scala 侧导出用它决定投影列，Java 侧
 * `MetricAdsCatalog` 决定建表与插入列，两侧必须逐列一致；`MetricAdsSpecTest` 做一致性回归）。
 * 列名与 Hive ADS DDL（`LocalSchemaInitJob`）以及 MySQL 宽表 DDL（`db/metric/V2/V3`）三者对齐。
 *
 * P1-04：本清单**只声明表名**，不含库名前缀；库限定名一律经 {@link WarehouseNamespace}
 * 派生（`hiveTable(ns)`），库名字面量不再出现在此。
 *
 * @param table      Hive 正式 ADS 表名（不含库名）
 * @param mysqlTable analytics_metric 物化宽表
 * @param columns    业务列（不含 snapshot_id / dt）
 */
case class MetricAdsTable(table: String, mysqlTable: String, columns: Seq[String]) {

  /** Hive 正式 ADS 表（库限定）：库名由唯一所有者派生 */
  def hiveTable(ns: WarehouseNamespace): String = ns.table("ads", table)
}

object MetricAdsSpec {

  /** 首期 8 张 ADS（Hive 侧只有 8 张；类目/地区 ADS 未产出，故不建对应 _m 表） */
  val TABLES: Seq[MetricAdsTable] = Seq(
    MetricAdsTable("ads_operation_overview", "ads_operation_overview_m",
      Seq("pv", "uv", "dau", "order_count", "sale_amount", "net_sale_amount",
        "avg_order_value", "refund_rate", "full_refund_rate")),
    MetricAdsTable("ads_sale_trend", "ads_sale_trend_m",
      Seq("order_count", "buyer_count", "sale_amount", "avg_order_value")),
    MetricAdsTable("ads_behavior_funnel", "ads_behavior_funnel_m",
      Seq("stage", "user_count", "conversion_rate", "overall_buy_rate")),
    MetricAdsTable("ads_active_trend", "ads_active_trend_m",
      Seq("dau", "behavior_count")),
    MetricAdsTable("ads_hot_product", "ads_hot_product_m",
      Seq("product_id", "product_name", "heat_score", "pv", "fav", "cart", "buy", "rank_no")),
    MetricAdsTable("ads_product_conversion", "ads_product_conversion_m",
      Seq("product_id", "pv_users", "buy_users", "conversion_rate")),
    MetricAdsTable("ads_user_profile", "ads_user_profile_m",
      Seq("user_id", "r", "f", "m", "value_group", "active_level", "favorite_category",
        "last_active_date", "last_buy_date", "lifecycle_state", "rule_version", "calc_date")),
    MetricAdsTable("ads_data_quality", "ads_data_quality_m",
      Seq("rule_code", "check_count", "error_count", "error_rate", "passed", "threshold")))

  val EXPORT_MANIFEST: String = "_export.json"

  def byMysqlTable: Map[String, MetricAdsTable] = TABLES.map(t => t.mysqlTable -> t).toMap
}
