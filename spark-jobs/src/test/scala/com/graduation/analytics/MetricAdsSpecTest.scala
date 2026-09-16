package com.graduation.analytics

import com.graduation.analytics.job.MetricExportJob
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * R7-3 回归：Hive 正式 ADS ↔ analytics_metric 宽表的映射清单必须与 Java 侧
 * `MetricAdsCatalog`（analytics_metric 建表/插入列白名单）**逐表逐列一致**。
 *
 * 这里刻意把 Java 侧清单**硬编码**一份：一旦任一侧加了列/改了顺序，本用例立刻变红，
 * 避免"Spark 导出的列与 MySQL 表不匹配"这种只会在发布时才炸的问题。
 */
class MetricAdsSpecTest extends AnyFlatSpec with Matchers {

  /** Java 侧 MetricAdsCatalog.ALL（顺序与列必须逐字一致） */
  private val javaCatalog = Seq(
    "ads_operation_overview_m" -> Seq("pv", "uv", "dau", "order_count", "sale_amount", "net_sale_amount",
      "avg_order_value", "refund_rate", "full_refund_rate"),
    "ads_sale_trend_m" -> Seq("order_count", "buyer_count", "sale_amount", "avg_order_value"),
    "ads_behavior_funnel_m" -> Seq("stage", "user_count", "conversion_rate", "overall_buy_rate"),
    "ads_active_trend_m" -> Seq("dau", "behavior_count"),
    "ads_hot_product_m" -> Seq("product_id", "product_name", "heat_score", "pv", "fav", "cart", "buy", "rank_no"),
    "ads_product_conversion_m" -> Seq("product_id", "pv_users", "buy_users", "conversion_rate"),
    "ads_user_profile_m" -> Seq("user_id", "r", "f", "m", "value_group", "active_level", "favorite_category",
      "last_active_date", "last_buy_date", "lifecycle_state", "rule_version", "calc_date",
      "r_days", "f_count", "m_amount", "period_start", "period_end"),
    "ads_data_quality_m" -> Seq("rule_code", "check_count", "error_count", "error_rate", "passed", "threshold"))

  private val javaCatalogMap = javaCatalog.toMap

  private val ns = WarehouseNamespace.defaultNamespace

  "MetricAdsSpec" should "覆盖 8 张 ADS 且 Hive/MySQL 表名一一对应" in {
    MetricAdsSpec.TABLES.size should be(8)
    MetricAdsSpec.TABLES.map(_.mysqlTable) should be(javaCatalog.map(_._1))
    MetricAdsSpec.TABLES.map(_.hiveTable(ns)).foreach(_ should startWith(s"${ns.ads}.ads_"))
    MetricAdsSpec.byMysqlTable.size should be(8)
  }

  it should "P1-04：库名前缀可替换，表名映射不写死（换源时同一套规格复用）" in {
    val other = WarehouseNamespace.of("dw_b")
    val names = MetricAdsSpec.TABLES.map(_.hiveTable(other))
    names.foreach(_ should startWith(s"${other.ads}.ads_"))
    names.foreach(_ should not startWith "dw_ads.")
    // 表名本身（层内名）与 MySQL 映射不随库名变化
    MetricAdsSpec.TABLES.map(_.table) should be(MetricAdsSpec.TABLES.map(_.mysqlTable.dropRight(2)))
  }

  it should "列清单与 Java 侧 MetricAdsCatalog 完全一致（含顺序）" in {
    MetricAdsSpec.TABLES.foreach { t =>
      val expected = javaCatalogMap(t.mysqlTable)
      withClue(s"${t.mysqlTable}: ") {
        t.columns should be(expected)
      }
    }
  }

  it should "R7-0 口径列已贯通到导出口径（full_refund_rate 必须导出）" in {
    val overview = MetricAdsSpec.byMysqlTable("ads_operation_overview_m")
    overview.columns should contain("refund_rate")
    overview.columns should contain("full_refund_rate")
  }

  "MetricExportJob.posix" should "把 Windows 路径规范成正斜杠（清单是 JSON，反斜杠会变非法转义）" in {
    // 真实缺陷回归：run 21 的 _export.json 写了 "D:\Develop\..."，发布侧 Jackson 报
    // Unrecognized character escape 'D'（MP_MANIFEST_READ）导致整条发布失败。
    MetricExportJob.posix("""D:\Develop_code\GraduationProject\metric-staging\S20260901_21\ads_hot_product_m.jsonl""") should be(
      "D:/Develop_code/GraduationProject/metric-staging/S20260901_21/ads_hot_product_m.jsonl")
    MetricExportJob.posix("file:/D:/spark-warehouse/dw_ads.db/t/x=1") should be(
      "file:/D:/spark-warehouse/dw_ads.db/t/x=1")
    MetricExportJob.posix("/opt/platform/metric-staging/S1/a.jsonl") should be(
      "/opt/platform/metric-staging/S1/a.jsonl")
  }
}
