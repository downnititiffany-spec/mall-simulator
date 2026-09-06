package com.graduation.analytics.sql

/**
 * DWS 主题聚合 SQL（§7.3/§7.4）。
 */
object DwsSql {

  /** 用户×日期行为宽表 */
  def userBehaviorDay(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_dws.dws_user_behavior_day PARTITION(dt = '$dt')
       |SELECT
       |  user_id,
       |  SUM(CASE WHEN behavior_type = 'view' THEN 1 ELSE 0 END) AS pv,
       |  SUM(CASE WHEN behavior_type = 'favorite' THEN 1 ELSE 0 END) AS fav,
       |  SUM(CASE WHEN behavior_type = 'cart_add' THEN 1 ELSE 0 END) AS cart,
       |  SUM(CASE WHEN behavior_type = 'search' THEN 1 ELSE 0 END) AS search,
       |  COUNT(DISTINCT event_hour) AS active_hours
       |FROM dw_dwd.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |GROUP BY user_id
       |""".stripMargin

  /** 行为漏斗 DWS（宽松用户口径，全站维度） */
  def funnelDay(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_dws.dws_behavior_funnel_day PARTITION(dt = '$dt')
       |SELECT
       |  -1 AS category_id, 'all' AS channel,
       |  COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS view_users,
       |  COUNT(DISTINCT CASE WHEN behavior_type IN ('favorite','cart_add') THEN user_id END) AS intent_users,
       |  0 AS order_users, 0 AS pay_users,
       |  NULL AS intent_rate, NULL AS order_rate, NULL AS pay_rate,
       |  NULL AS overall_buy_rate
       |FROM dw_dwd.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |""".stripMargin

  /** 商品×日期行为宽表 */
  def productBehaviorDay(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_dws.dws_product_behavior_day PARTITION(dt = '$dt')
       |SELECT
       |  product_id, category_id,
       |  SUM(CASE WHEN behavior_type = 'view' THEN 1 ELSE 0 END) AS pv,
       |  COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS uv,
       |  SUM(CASE WHEN behavior_type = 'favorite' THEN 1 ELSE 0 END) AS fav,
       |  SUM(CASE WHEN behavior_type = 'cart_add' THEN 1 ELSE 0 END) AS cart,
       |  SUM(CASE WHEN behavior_type = 'buy' THEN 1 ELSE 0 END) AS buy
       |FROM dw_dwd.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |GROUP BY product_id, category_id
       |""".stripMargin

  /** 交易日汇总（有效支付口径） */
  def tradeDay(dt: String): String =
    s"""
       |INSERT OVERWRITE TABLE dw_dws.dws_trade_day PARTITION(dt = '$dt')
       |SELECT
       |  COUNT(DISTINCT order_id) AS order_count,
       |  COUNT(DISTINCT user_id) AS buyer_count,
       |  SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END) AS sale_amount,
       |  COALESCE(SUM(CASE WHEN final_refunded_flag = 1 THEN refund_amount ELSE 0 END), 0) AS refund_amount,
       |  SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END)
       |    - COALESCE(SUM(CASE WHEN final_refunded_flag = 1 THEN refund_amount ELSE 0 END), 0) AS net_sale_amount,
       |  CASE WHEN COUNT(DISTINCT order_id) = 0 THEN NULL
       |       ELSE SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END)
       |            / COUNT(DISTINCT order_id) END AS avg_order_value
       |FROM dw_dwd.dwd_order_detail
       |WHERE dt = '$dt'
       |""".stripMargin
}