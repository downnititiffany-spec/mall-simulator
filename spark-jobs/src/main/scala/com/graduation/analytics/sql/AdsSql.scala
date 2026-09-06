package com.graduation.analytics.sql

/**
 * ADS 指标 SQL（§7.7）：页面/AI 直接使用，字段口径固定。
 */
object AdsSql {

  /** 运营大盘（含退款率，分母 0 → null） */
  def operationOverview(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ads.ads_operation_overview PARTITION(dt = '$dt')
       |SELECT
       |  b.pv, b.uv, b.dau, t.order_count, t.sale_amount, t.net_sale_amount, t.avg_order_value,
       |  CASE WHEN t.order_count = 0 THEN NULL
       |       ELSE CAST(r.refunded_orders AS DECIMAL(18,2)) / t.order_count END AS refund_rate,
       |  '$dt' AS snapshot_id
       |FROM (SELECT
       |        COUNT(*) AS pv,
       |        COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS uv,
       |        COUNT(DISTINCT user_id) AS dau
       |      FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt') b,
       |     (SELECT order_count, sale_amount, net_sale_amount, avg_order_value
       |      FROM dw_dws.dws_trade_day WHERE dt = '$dt') t,
       |     (SELECT COUNT(DISTINCT order_id) AS refunded_orders
       |      FROM dw_dwd.dwd_order_detail WHERE dt = '$dt' AND final_refunded_flag = 1) r
       |""".stripMargin

  /** 活跃趋势 */
  def activeTrend(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ads.ads_active_trend PARTITION(dt = '$dt')
       |SELECT '$dt' AS dt, COUNT(DISTINCT user_id) AS dau, COUNT(*) AS behavior_count
       |FROM dw_dwd.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |""".stripMargin

  /** 热门商品 TopN（热度权重来自配置，默认 §21.7 对数公式） */
  def hotProduct(dt: String, topN: Int): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ads.ads_hot_product PARTITION(dt = '$dt')
       |SELECT product_id, p.product_name, heat_score, pv, fav, cart, buy, rank_no
       |FROM (
       |  SELECT product_id,
       |         1.0*LOG1P(pv) + 2.0*LOG1P(fav) + 3.0*LOG1P(cart) + 5.0*LOG1P(buy) AS heat_score,
       |         pv, fav, cart, buy,
       |         ROW_NUMBER() OVER (ORDER BY 1.0*LOG1P(pv) + 2.0*LOG1P(fav) + 3.0*LOG1P(cart) + 5.0*LOG1P(buy) DESC) AS rank_no
       |  FROM dw_dws.dws_product_behavior_day
       |  WHERE dt = '$dt'
       |) t
       |LEFT JOIN dw_dim.dim_product p ON p.product_id = t.product_id AND p.dt = '$dt'
       |WHERE rank_no <= $topN
       |""".stripMargin

  /** 商品转化 */
  def productConversion(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ads.ads_product_conversion PARTITION(dt = '$dt')
       |SELECT product_id, uv AS pv_users,
       |       CAST(buy AS BIGINT) AS buy_users,
       |       CASE WHEN uv = 0 THEN NULL ELSE CAST(buy AS DECIMAL(8,4)) / uv END AS conversion_rate
       |FROM dw_dws.dws_product_behavior_day
       |WHERE dt = '$dt'
       |""".stripMargin

  /** 销售趋势 */
  def saleTrend(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_ads.ads_sale_trend PARTITION(dt = '$dt')
       |SELECT order_count, buyer_count, sale_amount, avg_order_value
       |FROM dw_dws.dws_trade_day
       |WHERE dt = '$dt'
       |""".stripMargin
}