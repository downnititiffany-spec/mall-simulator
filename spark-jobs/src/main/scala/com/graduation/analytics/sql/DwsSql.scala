package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

/**
 * DWS 主题聚合 SQL（§7.3/§7.4，§12.1 修复）：
 * 7 张核心 DWS 全部真实执行——漏斗不再硬编码 order/pay，
 * 商品 buy 一律来自 dwd_order_detail 有效支付（final_paid_flag=1），
 * 转化率分母为 0 返回 NULL。
 */
object DwsSql {

  /** 用户×日期行为宽表（buy=当日有效支付商品件数，来自订单明细） */
  def userBehaviorDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_user_behavior_day PARTITION(dt = '$dt')
      |SELECT
      |  b.user_id,
      |  SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END) AS pv,
      |  SUM(CASE WHEN b.behavior_type = 'favorite' THEN 1 ELSE 0 END) AS fav,
      |  SUM(CASE WHEN b.behavior_type = 'cart_add' THEN 1 ELSE 0 END) AS cart,
      |  SUM(CASE WHEN b.behavior_type = 'search' THEN 1 ELSE 0 END) AS search,
      |  COUNT(DISTINCT b.event_hour) AS active_hours,
      |  COALESCE(s.buy, 0) AS buy
      |FROM ${ns.dwd}.dwd_user_behavior_detail b
      |LEFT JOIN (
      |  SELECT user_id, SUM(quantity) AS buy
      |  FROM ${ns.dwd}.dwd_order_detail
      |  WHERE dt = '$dt' AND final_paid_flag = 1
      |  GROUP BY user_id
      |) s ON s.user_id = b.user_id
      |WHERE b.dt = '$dt'
      |GROUP BY b.user_id, COALESCE(s.buy, 0)
      |""".stripMargin

  /**
   * 行为漏斗 DWS（§12.2 宽松用户口径，全站维度）：
   * view_users=当日 view 去重用户；intent_users=favorite|cart_add 去重；
   * order_users=当日创建订单去重用户（订单明细全量）；
   * pay_users=当日有效支付去重用户（final_paid_flag=1）。
   * 转化率分母为 0 → NULL，绝不返回硬编码 0。
   */
  def funnelDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_behavior_funnel_day PARTITION(dt = '$dt')
      |SELECT
      |  -1 AS category_id, 'all' AS channel,
      |  b.view_users, b.intent_users, o.order_users, p.pay_users,
      |  CASE WHEN b.view_users = 0 THEN NULL
      |       ELSE CAST(b.intent_users AS DECIMAL(8,4)) / b.view_users END AS intent_rate,
      |  CASE WHEN b.intent_users = 0 THEN NULL
      |       ELSE CAST(o.order_users AS DECIMAL(8,4)) / b.intent_users END AS order_rate,
      |  CASE WHEN o.order_users = 0 THEN NULL
      |       ELSE CAST(p.pay_users AS DECIMAL(8,4)) / o.order_users END AS pay_rate,
      |  CASE WHEN b.view_users = 0 THEN NULL
      |       ELSE CAST(p.pay_users AS DECIMAL(8,4)) / b.view_users END AS overall_buy_rate
      |FROM (
      |  SELECT
      |    COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS view_users,
      |    COUNT(DISTINCT CASE WHEN behavior_type IN ('favorite','cart_add') THEN user_id END) AS intent_users
      |  FROM ${ns.dwd}.dwd_user_behavior_detail
      |  WHERE dt = '$dt'
      |) b,
      |(
      |  SELECT COUNT(DISTINCT user_id) AS order_users
      |  FROM ${ns.dwd}.dwd_order_detail
      |  WHERE dt = '$dt'
      |) o,
      |(
      |  SELECT COUNT(DISTINCT user_id) AS pay_users
      |  FROM ${ns.dwd}.dwd_order_detail
      |  WHERE dt = '$dt' AND final_paid_flag = 1
      |) p
      |""".stripMargin

  /**
   * 商品×日期行为宽表（§12.3 修复）：
   * buy 来自 dwd_order_detail 有效支付商品件数（SUM(quantity)），
   * 绝不从行为表取不存在的 buy 枚举。
   */
  def productBehaviorDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_product_behavior_day PARTITION(dt = '$dt')
      |SELECT
      |  b.product_id, b.category_id,
      |  SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END) AS pv,
      |  COUNT(DISTINCT CASE WHEN b.behavior_type = 'view' THEN b.user_id END) AS uv,
      |  SUM(CASE WHEN b.behavior_type = 'favorite' THEN 1 ELSE 0 END) AS fav,
      |  SUM(CASE WHEN b.behavior_type = 'cart_add' THEN 1 ELSE 0 END) AS cart,
      |  COALESCE(s.buy, 0) AS buy
      |FROM ${ns.dwd}.dwd_user_behavior_detail b
      |LEFT JOIN (
      |  SELECT product_id, SUM(quantity) AS buy
      |  FROM ${ns.dwd}.dwd_order_detail
      |  WHERE dt = '$dt' AND final_paid_flag = 1
      |  GROUP BY product_id
      |) s ON s.product_id = b.product_id
      |WHERE b.dt = '$dt'
      |GROUP BY b.product_id, b.category_id, COALESCE(s.buy, 0)
      |""".stripMargin

  /** 交易日汇总（§21.3 有效支付口径）：order_count/buyer_count 仅计 final_paid_flag=1 */
  def tradeDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_trade_day PARTITION(dt = '$dt')
      |SELECT
      |  COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) AS order_count,
      |  COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN user_id END) AS buyer_count,
      |  SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END) AS sale_amount,
      |  COALESCE(SUM(CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END), 0) AS refund_amount,
      |  SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END)
      |    - COALESCE(SUM(CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END), 0) AS net_sale_amount,
      |  CASE WHEN COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) = 0 THEN NULL
      |       ELSE SUM(CASE WHEN final_paid_flag = 1 THEN amount ELSE 0 END)
      |            / COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) END AS avg_order_value
      |FROM ${ns.dwd}.dwd_order_detail
      |WHERE dt = '$dt'
      |""".stripMargin

  /** 商品×日期销售宽表（§12.1 第 4 张）：有效支付商品件数/金额/去重买家 */
  def productSaleDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_product_sale_day PARTITION(dt = '$dt')
      |SELECT
      |  product_id, category_id,
      |  SUM(quantity) AS sale_count,
      |  SUM(amount) AS sale_amount,
      |  COUNT(DISTINCT user_id) AS buyer_count
      |FROM ${ns.dwd}.dwd_order_detail
      |WHERE dt = '$dt' AND final_paid_flag = 1
      |GROUP BY product_id, category_id
      |""".stripMargin

  /**
   * 用户×统计周期交易汇总（复购/RFM 输入，§12.1 第 6 张）。
   * 观察期 [periodStart, periodEnd] 取有效支付订单；分区 dt 为统计日。
   *
   * S3-03（设计 §11.2 L433「完全退款不算有效复购订单」）：新增 `valid_order_count` =
   * 观察期内**仍在有效状态**的支付订单数（`final_refunded_flag = 0`，即未发生全额退款）。
   * 为什么不能复用 `order_count`：它含全额退款订单 ⇒ 用「`order_count >= 2`」算复购率会得到
   * 支付复购率（黄金夹具同构口径下 0.6667），而复购率的默认变体是有效复购率（0.3333）。
   * 列追加在表末尾（Hive 侧只能 `ADD COLUMNS` 追加，且本语句按位置写入，列序必须与 DDL 一致）。
   */
  def userTradePeriod(ns: WarehouseNamespace, dt: String, periodStart: String, periodEnd: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_user_trade_period PARTITION(dt = '$dt')
      |SELECT
      |  user_id,
      |  MAX(order_date) AS last_buy_date,
      |  COUNT(DISTINCT order_id) AS order_count,
      |  SUM(amount) AS sale_amount,
      |  '$periodStart' AS period_start,
      |  '$periodEnd' AS period_end,
      |  COUNT(DISTINCT CASE WHEN final_refunded_flag = 0 THEN order_id END) AS valid_order_count
      |FROM ${ns.dwd}.dwd_order_detail
      |WHERE dt >= '$periodStart' AND dt <= '$periodEnd' AND final_paid_flag = 1
      |GROUP BY user_id
      |""".stripMargin

  /** 地区×日期销售汇总（§12.1 第 7 张）：城市等级 = region */
  def regionSaleDay(ns: WarehouseNamespace, dt: String): String =
    s"""
      |INSERT OVERWRITE TABLE ${ns.dws}.dws_region_sale_day PARTITION(dt = '$dt')
      |SELECT
      |  COALESCE(city_level, 'unknown') AS region,
      |  COUNT(DISTINCT user_id) AS buyer_count,
      |  COUNT(DISTINCT order_id) AS order_count,
      |  SUM(amount) AS sale_amount
      |FROM ${ns.dwd}.dwd_order_detail
      |WHERE dt = '$dt' AND final_paid_flag = 1
      |GROUP BY COALESCE(city_level, 'unknown')
      |""".stripMargin
}