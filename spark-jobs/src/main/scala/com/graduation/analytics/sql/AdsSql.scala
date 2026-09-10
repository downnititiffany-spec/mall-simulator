package com.graduation.analytics.sql

/**
 * ADS 指标 SQL（§7.7，§12.4 修复）：
 * 8 张首期核心 ADS 全部真实执行；漏斗收编为模板；
 * 商品转化 buy_users 取商品销售 DWS 的去重买家数（不是 buy 件数）；
 * 用户画像按 RFM 规则分层（§21.6，与 algorithm/RfmScorer、QuartileStats 口径一致）；
 * 数据质量大盘 4 规则来自 ODS/DWD 真实统计（§5.4，与 QualityChecker 同阈值）。
 */
object AdsSql {

  /** 8 张首期核心 ADS（不含库前缀；R6-13 暂存/正式同名，仅分区不同） */
  val TABLES: Seq[String] = Seq(
    "ads_operation_overview", "ads_active_trend", "ads_behavior_funnel", "ads_hot_product",
    "ads_product_conversion", "ads_sale_trend", "ads_user_profile", "ads_data_quality")

  /** 正式 ADS 表（分区 dt；发布由 pub 作业用 Hive 元数据指针完成，§14.4） */
  def formal(table: String): String = s"dw_ads.$table"

  /** R6-13 暂存表（分区 snapshot_id + dt，物理路径 {table}__staging/snapshot_id=S/dt=D） */
  def staging(table: String): String = s"dw_ads.${table}__staging"

  /**
   * 写入目标 + 分区子句（§14.4 分区幂等协议）：
   * `snapshotId=None` → 直写正式分区（仅历史路径/局部重算使用）；
   * `Some(sid)` → 写暂存分区，正式分区只在质量门通过后由 pub 发布。
   */
  def insertTarget(table: String, dt: String, snapshotId: Option[String]): String = snapshotId match {
    case Some(sid) => s"INSERT OVERWRITE TABLE ${staging(table)} PARTITION(snapshot_id = '$sid', dt = '$dt')"
    case None      => s"INSERT OVERWRITE TABLE ${formal(table)} PARTITION(dt = '$dt')"
  }

  /** 运营大盘（退款率=完全退款订单/支付订单，分母 0 → null） */
  def operationOverview(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_operation_overview", dt, snapshotId)}
       |SELECT
       |  b.pv, b.uv, b.dau, t.order_count, t.sale_amount, t.net_sale_amount, t.avg_order_value,
       |  CASE WHEN t.order_count = 0 THEN NULL
       |       ELSE CAST(r.refunded_orders AS DECIMAL(18,2)) / t.order_count END AS refund_rate
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

  /** 活跃趋势（dt 为分区列，由 INSERT PARTITION 提供，不再投影） */
  def activeTrend(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_active_trend", dt, snapshotId)}
       |SELECT COUNT(DISTINCT user_id) AS dau, COUNT(*) AS behavior_count
       |FROM dw_dwd.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |""".stripMargin

  /** 转化漏斗：dws_behavior_funnel_day 展开为 stage 行（dt 为分区列不投影） */
  def funnel(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_behavior_funnel", dt, snapshotId)}
       |SELECT 'view' AS stage, view_users AS user_count, NULL AS conversion_rate,
       |       overall_buy_rate
       |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'intent', intent_users, intent_rate, overall_buy_rate
       |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'order', order_users, order_rate, overall_buy_rate
       |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'pay', pay_users, pay_rate, overall_buy_rate
       |FROM dw_dws.dws_behavior_funnel_day WHERE dt = '$dt'
       |""".stripMargin

  /** 热门商品 TopN（热度权重来自配置，默认 §21.7 对数公式） */
  def hotProduct(dt: String, topN: Int, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_hot_product", dt, snapshotId)}
       |SELECT t.product_id, p.product_name, heat_score, pv, fav, cart, buy, rank_no
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

  /** 商品转化：pv_users=浏览用户，buy_users=商品销售 DWS 去重买家数 */
  def productConversion(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_product_conversion", dt, snapshotId)}
       |SELECT b.product_id,
       |       b.uv AS pv_users,
       |       COALESCE(s.buyer_count, 0) AS buy_users,
       |       CASE WHEN b.uv = 0 THEN NULL
       |            ELSE CAST(COALESCE(s.buyer_count, 0) AS DECIMAL(8,4)) / b.uv END AS conversion_rate
       |FROM dw_dws.dws_product_behavior_day b
       |LEFT JOIN dw_dws.dws_product_sale_day s
       |  ON s.product_id = b.product_id AND s.dt = b.dt
       |WHERE b.dt = '$dt'
       |""".stripMargin

  /** 销售趋势 */
  def saleTrend(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_sale_trend", dt, snapshotId)}
       |SELECT order_count, buyer_count, sale_amount, avg_order_value
       |FROM dw_dws.dws_trade_day
       |WHERE dt = '$dt'
       |""".stripMargin

  /**
   * 用户画像 RFM 分层（§21.6）：
   * r/f/m 为观察期 [periodStart, periodEnd] 的近似五分位（NTILE(5)）；
   * R 反向（间隔越小分越高），F/M 正向；观察期参数为 yyyyMMdd，SQL 内转 yyyy-MM-dd 比较；
   * value_group 八类标签与 algorithm/RfmScorer.octant 一致；
   * lifecycle_state 与 algorithm/RfmScorer.lifecycle 一致
   *   （新用户=观察期首购且仅 1 单；流失风险 rDays>60；沉默 30<rDays<=60；否则活跃）；
   * active_level 按最近活跃天数（<=7 高 / <=30 中 / 其余低）。
   */
  def userProfile(dt: String, periodStart: String, periodEnd: String,
                  snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_user_profile", dt, snapshotId)}
       |SELECT
       |  tp.user_id,
       |  6 - r_ntile AS r,
       |  f_ntile AS f,
       |  m_ntile AS m,
       |  CASE WHEN m_ntile >= 4 THEN
       |    CASE WHEN r_ntile <= 2 THEN
       |      CASE WHEN f_ntile >= 4 THEN '重要价值' ELSE '重要发展' END
       |    ELSE
       |      CASE WHEN f_ntile >= 4 THEN '重要保持' ELSE '重要挽留' END
       |    END
       |  ELSE
       |    CASE WHEN r_ntile <= 2 THEN
       |      CASE WHEN f_ntile >= 4 THEN '一般价值' ELSE '一般发展' END
       |    ELSE
       |      CASE WHEN f_ntile >= 4 THEN '一般保持' ELSE '一般挽留' END
       |    END
       |  END AS value_group,
       |  CASE WHEN DATEDIFF(pe, a.last_active_date) <= 7 THEN '高'
       |       WHEN DATEDIFF(pe, a.last_active_date) <= 30 THEN '中'
       |       ELSE '低' END AS active_level,
       |  a.favorite_category,
       |  a.last_active_date,
       |  tp.last_buy_date,
       |  CASE WHEN tp.order_count = 1 AND tp.last_buy_date = ps THEN '新用户'
       |       WHEN DATEDIFF(pe, tp.last_buy_date) > 60 THEN '流失风险'
       |       WHEN DATEDIFF(pe, tp.last_buy_date) > 30 THEN '沉默'
       |       ELSE '活跃' END AS lifecycle_state,
       |  'rfm-v1' AS rule_version,
       |  '$dt' AS calc_date
       |FROM (
       |  SELECT user_id, last_buy_date, order_count, sale_amount,
       |         regexp_replace('$periodStart', '(\\d{4})(\\d{2})(\\d{2})', '$$1-$$2-$$3') AS ps,
       |         regexp_replace('$periodEnd', '(\\d{4})(\\d{2})(\\d{2})', '$$1-$$2-$$3') AS pe,
       |         NTILE(5) OVER (ORDER BY DATEDIFF(
       |           regexp_replace('$periodEnd', '(\\d{4})(\\d{2})(\\d{2})', '$$1-$$2-$$3'), last_buy_date) ASC) AS r_ntile,
       |         NTILE(5) OVER (ORDER BY order_count ASC) AS f_ntile,
       |         NTILE(5) OVER (ORDER BY sale_amount ASC) AS m_ntile
       |  FROM dw_dws.dws_user_trade_period
       |  WHERE dt = '$dt'
       |) tp
       |LEFT JOIN (
       |  SELECT user_id, event_date AS last_active_date, category_id AS favorite_category
       |  FROM (
       |    SELECT user_id, category_id, event_date,
       |           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY cnt DESC, category_id ASC) AS rn
       |    FROM (
       |      SELECT user_id, category_id, MAX(event_date) AS event_date, COUNT(*) AS cnt
       |      FROM dw_dwd.dwd_user_behavior_detail
       |      WHERE dt = '$dt'
       |      GROUP BY user_id, category_id
       |    ) g
       |  ) r
       |  WHERE rn = 1
       |) a ON a.user_id = tp.user_id
       |""".stripMargin

  /**
   * 数据质量大盘（§5.4）：4 规则与 QualityChecker 同名同阈值，
   * 统计来自 ODS/DWD 真实数据，非 SQL 字符串自检。
   */
  def dataQuality(dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget("ads_data_quality", dt, snapshotId)}
       |SELECT rule_code, check_count, error_count, error_rate, passed, threshold FROM (
       |  SELECT 'AMOUNT_RECONCILE' AS rule_code,
       |         CAST(SUM(fp) AS BIGINT) AS check_count,
       |         CAST(SUM(bad) AS BIGINT) AS error_count,
       |         CASE WHEN SUM(fp) = 0 THEN NULL
       |              ELSE CAST(SUM(bad) AS DECIMAL(8,6)) / SUM(fp) END AS error_rate,
       |         CASE WHEN SUM(bad) = 0 THEN 1 ELSE 0 END AS passed,
       |         '0.01' AS threshold
       |  FROM (
       |    SELECT order_id,
       |           MAX(final_paid_flag) AS fp,
       |           CASE WHEN MAX(final_paid_flag) = 1
       |                     AND ABS(MAX(order_amount) - MAX(paid_amount)) > 0.01 THEN 1 ELSE 0 END AS bad
       |    FROM dw_dwd.dwd_order_detail
       |    WHERE dt = '$dt'
       |    GROUP BY order_id
       |  ) o
       |  UNION ALL
       |  SELECT 'REQUIRED_FIELD_NULL_RATE' AS rule_code,
       |         CAST(COUNT(*) AS BIGINT) AS check_count,
       |         CAST(SUM(CASE WHEN user_id IS NULL OR product_id IS NULL THEN 1 ELSE 0 END) AS BIGINT) AS error_count,
       |         CAST(SUM(CASE WHEN user_id IS NULL OR product_id IS NULL THEN 1 ELSE 0 END) AS DECIMAL(8,6)) / COUNT(*) AS error_rate,
       |         CASE WHEN SUM(CASE WHEN user_id IS NULL OR product_id IS NULL THEN 1 ELSE 0 END) * 1000 <= COUNT(*) THEN 1 ELSE 0 END AS passed,
       |         '0.001' AS threshold
       |  FROM dw_dwd.dwd_user_behavior_detail
       |  WHERE dt = '$dt'
       |  UNION ALL
       |  SELECT 'EVENT_ID_UNIQUE' AS rule_code,
       |         CAST((SELECT COUNT(*) FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt') AS BIGINT) AS check_count,
       |         CAST((SELECT COUNT(*) FROM dw_dwd.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') AS BIGINT) AS error_count,
       |         CAST((SELECT COUNT(*) FROM dw_dwd.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') AS DECIMAL(8,6))
       |           / (SELECT COUNT(*) FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt') AS error_rate,
       |         CASE WHEN (SELECT COUNT(*) FROM dw_dwd.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') * 2000
       |                <= (SELECT COUNT(*) FROM dw_dwd.dwd_user_behavior_detail WHERE dt = '$dt') THEN 1 ELSE 0 END AS passed,
       |         '0.0005' AS threshold
       |  UNION ALL
       |  SELECT 'ENUM_WHITELIST' AS rule_code,
       |         CAST(COUNT(*) AS BIGINT) AS check_count,
       |         CAST(SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) AS BIGINT) AS error_count,
       |         CAST(SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) AS DECIMAL(8,6)) / COUNT(*) AS error_rate,
       |         CASE WHEN SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) = 0 THEN 1 ELSE 0 END AS passed,
       |         '0' AS threshold
       |  FROM dw_dwd.dwd_user_behavior_detail
       |  WHERE dt = '$dt'
       |) q
       |""".stripMargin
}