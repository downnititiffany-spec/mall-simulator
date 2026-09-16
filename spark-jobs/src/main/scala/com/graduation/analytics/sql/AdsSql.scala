package com.graduation.analytics.sql

import com.graduation.analytics.warehouse.WarehouseNamespace

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

  /** 正式 ADS 表（分区 dt；发布由 pub 作业用 Hive 元数据指针完成，§14.4）；库名由唯一所有者派生 */
  def formal(ns: WarehouseNamespace, table: String): String = ns.table("ads", table)

  /** R6-13 暂存表（分区 snapshot_id + dt，物理路径 {table}__staging/snapshot_id=S/dt=D） */
  def staging(ns: WarehouseNamespace, table: String): String = ns.table("ads", s"${table}__staging")

  /**
   * 写入目标 + 分区子句（§14.4 分区幂等协议）：
   * `snapshotId=None` → 直写正式分区（仅历史路径/局部重算使用）；
   * `Some(sid)` → 写暂存分区，正式分区只在质量门通过后由 pub 发布。
   */
  def insertTarget(ns: WarehouseNamespace, table: String, dt: String, snapshotId: Option[String]): String = snapshotId match {
    case Some(sid) => s"INSERT OVERWRITE TABLE ${staging(ns, table)} PARTITION(snapshot_id = '$sid', dt = '$dt')"
    case None      => s"INSERT OVERWRITE TABLE ${formal(ns, table)} PARTITION(dt = '$dt')"
  }

  /**
   * 运营大盘（§13.1 指标字典口径，R7-0 统一）：
   *  - pv   = view 行为事件数（字典「count(view 行为事件)」，**不再**把全部行为当 PV）
   *  - uv   = view 行为去重用户数
   *  - refund_rate      = 发生**已完成**退款的订单数 / 支付订单数（部分/全部退款都算，字典口径）
   *  - full_refund_rate = 全额退款订单数 / 支付订单数（新增独立指标，不偷换 refund_rate）
   *  - repeat_rate      = **有效复购率** = 有效购买 ≥2 次用户 / 支付用户（S3-03，设计 §11.2 L433）
   * 退款金额口径来自 OrderTradeCompiler：refund_amount 只累计 refund_completed 事件（按 refund_id 去重）。
   * 分母 0 → null（不满除零）。
   *
   * S3-03 复购率（设计 §11.2 L433「必须声明观察期和变体」/ §11.4 L449）：
   *  - 分子/分母都取 `dws_user_trade_period`（字典登记的复购源表）：分母 = 该观察期内的**支付用户数**
   *    （该表只收 `final_paid_flag = 1` 的行），分子 = 其中 `valid_order_count >= 2` 的用户数；
   *  - 「有效」= 剔除**全额退款**订单（`valid_order_count` 由 `DwsSql.userTradePeriod` 按
   *    `final_refunded_flag = 0` 计算）；本层不实现「支付复购率」变体（见 F-36 backlog）；
   *  - **观察期声明落库**：`repeat_period_start/end` 取上游 DWS 行自己写入的 `period_start/period_end`
   *    （即 `DwsSql.userTradePeriod` 的作业入参窗口，唯一所有者），ISO 化后随行发布 —— 这样 ADS 声明的窗口
   *    必然等于真正参与聚合的窗口，不会出现「作业按 A 窗口算、ADS 声明 B 窗口」；
   *  - 无支付用户时分子/分母与窗口声明**同时为 NULL**（不伪造窗口），由发布侧跳过该指标。
   */
  def operationOverview(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_operation_overview", dt, snapshotId)}
       |SELECT
       |  b.pv, b.uv, b.dau, t.order_count, t.sale_amount, t.net_sale_amount, t.avg_order_value,
       |  CASE WHEN t.order_count = 0 THEN NULL
       |       ELSE CAST(r.refunded_orders AS DECIMAL(18,2)) / t.order_count END AS refund_rate,
       |  CASE WHEN t.order_count = 0 THEN NULL
       |       ELSE CAST(r.full_refunded_orders AS DECIMAL(18,2)) / t.order_count END AS full_refund_rate,
       |  CASE WHEN u.pay_users = 0 THEN NULL
       |       ELSE CAST(u.repeat_users AS DECIMAL(8,4)) / u.pay_users END AS repeat_rate,
       |  ${isoDayCol("u.period_start")} AS repeat_period_start,
       |  ${isoDayCol("u.period_end")} AS repeat_period_end
       |FROM (SELECT
       |        COUNT(CASE WHEN behavior_type = 'view' THEN 1 END) AS pv,
       |        COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS uv,
       |        COUNT(DISTINCT user_id) AS dau
       |      FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt') b,
       |     (SELECT order_count, sale_amount, net_sale_amount, avg_order_value
       |      FROM ${ns.dws}.dws_trade_day WHERE dt = '$dt') t,
       |     (SELECT
       |        COUNT(DISTINCT CASE WHEN final_paid_flag = 1 AND refund_amount > 0
       |              THEN order_id END) AS refunded_orders,
       |        COUNT(DISTINCT CASE WHEN final_paid_flag = 1 AND final_refunded_flag = 1
       |              THEN order_id END) AS full_refunded_orders
       |      FROM ${ns.dwd}.dwd_order_detail WHERE dt = '$dt') r,
       |     (SELECT
       |        COUNT(DISTINCT user_id) AS pay_users,
       |        COUNT(DISTINCT CASE WHEN valid_order_count >= 2 THEN user_id END) AS repeat_users,
       |        MAX(period_start) AS period_start,
       |        MAX(period_end) AS period_end
       |      FROM ${ns.dws}.dws_user_trade_period WHERE dt = '$dt') u
       |""".stripMargin

  /** 活跃趋势（dt 为分区列，由 INSERT PARTITION 提供，不再投影） */
  def activeTrend(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_active_trend", dt, snapshotId)}
       |SELECT COUNT(DISTINCT user_id) AS dau, COUNT(*) AS behavior_count
       |FROM ${ns.dwd}.dwd_user_behavior_detail
       |WHERE dt = '$dt'
       |""".stripMargin

  /** 转化漏斗：dws_behavior_funnel_day 展开为 stage 行（dt 为分区列不投影） */
  def funnel(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_behavior_funnel", dt, snapshotId)}
       |SELECT 'view' AS stage, view_users AS user_count, NULL AS conversion_rate,
       |       overall_buy_rate
       |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'intent', intent_users, intent_rate, overall_buy_rate
       |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'order', order_users, order_rate, overall_buy_rate
       |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'
       |UNION ALL
       |SELECT 'pay', pay_users, pay_rate, overall_buy_rate
       |FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'
       |""".stripMargin

  /**
   * 热门商品 TopN（热度权重来自配置，默认 §21.7 对数公式）。
   *
   * DEF-08：`dim_product` 是**按业务日的快照**，只覆盖当日 `product_created/product_updated` 事件；
   * 当日无商品事件时该分区 0 行（实测 run 37：dim_product(20260901)=0，dws_product_behavior_day=9 个商品），
   * LEFT JOIN 会把商品名全部打成 NULL → `ads_hot_product__staging` 9/9 行 `product_name` 为空 →
   * BLOCKING 规则 `ADS_STAGING_KEY_NOT_NULL` 拦截整条发布。故名称按维度表既有 unknown 约定兜底
   * （`DimSql.productSnapshot` 同样写 'UNKNOWN'），**不用 NULL**：宁可显式 unknown，不留空关键列。
   *
   * 稳定次序键（§11.5 L455「商品排行按热度/销量/金额并有**稳定次序键**」）：热度并列时按 `product_id`
   * 升序定名次。无此键时同热度商品的名次取决于扫描/落文件顺序，重跑会改变 `rank_no`（实测 S2-06：
   * 同热度三商品升序落盘得 `{101→1,102→2,103→3}`、降序落盘得 `{103→1,102→2,101→3}`）；而 `rank_no`
   * 既是 `WHERE rank_no <= topN` 的入选判据，又是 `ads_hot_product_m` 的键列与榜单排序键
   * （`AnalysisService` 按 `rank_no` 升序取 TopN）⇒ 名次漂移会直接改变用户看到的榜单内容。
   */
  def hotProduct(ns: WarehouseNamespace, dt: String, topN: Int, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_hot_product", dt, snapshotId)}
       |SELECT t.product_id, COALESCE(p.product_name, 'UNKNOWN') AS product_name, heat_score, pv, fav, cart, buy, rank_no
       |FROM (
       |  SELECT product_id,
       |         1.0*LOG1P(pv) + 2.0*LOG1P(fav) + 3.0*LOG1P(cart) + 5.0*LOG1P(buy) AS heat_score,
       |         pv, fav, cart, buy,
       |         ROW_NUMBER() OVER (ORDER BY 1.0*LOG1P(pv) + 2.0*LOG1P(fav) + 3.0*LOG1P(cart) + 5.0*LOG1P(buy) DESC,
                                         product_id ASC) AS rank_no
       |  FROM ${ns.dws}.dws_product_behavior_day
       |  WHERE dt = '$dt'
       |) t
       |LEFT JOIN ${ns.dim}.dim_product p ON p.product_id = t.product_id AND p.dt = '$dt'
       |WHERE rank_no <= $topN
       |""".stripMargin

  /** 商品转化：pv_users=浏览用户，buy_users=商品销售 DWS 去重买家数 */
  def productConversion(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_product_conversion", dt, snapshotId)}
       |SELECT b.product_id,
       |       b.uv AS pv_users,
       |       COALESCE(s.buyer_count, 0) AS buy_users,
       |       CASE WHEN b.uv = 0 THEN NULL
       |            ELSE CAST(COALESCE(s.buyer_count, 0) AS DECIMAL(8,4)) / b.uv END AS conversion_rate
       |FROM ${ns.dws}.dws_product_behavior_day b
       |LEFT JOIN ${ns.dws}.dws_product_sale_day s
       |  ON s.product_id = b.product_id AND s.dt = b.dt
       |WHERE b.dt = '$dt'
       |""".stripMargin

  /**
   * 销售趋势（§9.3 L333「`ads_sale_trend`：历史已发布，**net_sale 等字段需补**」）。
   *
   * 净销售额口径 = 设计 §11.2 **L428**「同口径支付金额 − 成功退款金额；真实收入方向，
   * 退款归属期需冻结」。这里**直接透传** `dws_trade_day.net_sale_amount`，不在 ADS 另写算法：
   *  - 退款只计 `final_paid_flag = 1` 的行（未支付/已取消订单的退款金额不参与扣减），
   *    与 `operationOverview` 的净额同源；
   *  - 由此保证同一 dt 上 `ads_sale_trend.net_sale_amount` 与 `ads_operation_overview.net_sale_amount`
   *    逐值相等（两张 ADS 表不得给出两种收入口径，实测见 `AdsSaleTrendNetSaleSpec` 的跨表对账）；
   *  - 「退款归属期冻结」的现状：退款金额由 `OrderTradeCompiler` 按 `refund_completed` 事件累计到
   *    该订单所在业务日的明细行（与大盘表完全一致），**跨业务日的退款重结未实现**（见 F-35 边界）。
   */
  def saleTrend(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_sale_trend", dt, snapshotId)}
       |SELECT order_count, buyer_count, sale_amount, avg_order_value, net_sale_amount
       |FROM ${ns.dws}.dws_trade_day
       |WHERE dt = '$dt'
       |""".stripMargin

  /**
   * 观察窗口 → ISO 日期（yyyy-MM-dd）的唯一实现。
   *
   * **为什么不用 `regexp_replace(…, '(\d{4})…', …)`（S3-01 实测事实，不是偏好）**：
   * Scala 的 `s"""…"""` 会把源码里的双反斜杠还原成一个反斜杠，而 **Spark SQL 的字符串字面量会吃掉
   * 未识别的反斜杠转义** —— 实测 SQL 文本 `'(\d{4})'` 的解析结果是 `(d{4})`（写成 `'(\\d{4})'` 才是
   * `(\d{4})`），于是 `regexp_replace` 不匹配、**原样返回** `20260901`；而
   * `DATEDIFF('20260901','2026-09-01')` = NULL。旧写法正是前者 ⇒ `ads_user_profile` 的 `r_ntile`
   * 排序键恒为 NULL（全平局 ⇒ R 分档退化成 user_id 次序）、`active_level` 恒「低」、
   * `lifecycle_state` 恒「活跃」且「新用户」永不成立。
   * 这里改用不含反斜杠的 `substr/concat`：无解析器歧义，8 位紧凑日期展开为 yyyy-MM-dd，
   * 其他输入原样保留（与旧正则在 8 位紧凑/已是 ISO 两个域上的行为完全一致）。
   */
  private def isoDay(v: String): String =
    s"""CASE WHEN length('$v') = 8
       |     THEN concat(substr('$v', 1, 4), '-', substr('$v', 5, 2), '-', substr('$v', 7, 2))
       |     ELSE '$v' END""".stripMargin

  /**
   * 列版本的 8 位紧凑日期展开（yyyyMMdd → yyyy-MM-dd，与 `isoDay` 同构，但作用于**列**）。
   * 用于把 DWS 行里声明的观察期窗口随 ADS 行一起发布（S3-03）；NULL 保持 NULL（不伪造窗口）。
   * 单行表达式、不含反斜杠（避免解析器歧义，见 S3-01 的 `isoDay` 修复）。
   */
  private def isoDayCol(expr: String): String =
    s"CASE WHEN $expr IS NULL THEN NULL WHEN length($expr) = 8 THEN " +
      s"concat(substr($expr, 1, 4), '-', substr($expr, 5, 2), '-', substr($expr, 7, 2)) ELSE $expr END"

  /**
   * 用户画像 RFM 分层（§21.6）：
   * r/f/m 为观察期 [periodStart, periodEnd] 的近似五分位（NTILE(5)）；
   * R 反向（间隔越小分越高），F/M 正向；观察期参数为 yyyyMMdd，SQL 内转 yyyy-MM-dd 比较；
   * value_group 八类标签与 algorithm/RfmScorer.octant 一致；
   * lifecycle_state 与 algorithm/RfmScorer.lifecycle 一致
   *   （新用户=观察期首购且仅 1 单；流失风险 rDays>60；沉默 30<rDays<=60；否则活跃）；
   * active_level 按最近活跃天数（<=7 高 / <=30 中 / 其余低）。
   *
   * DEF-10：RFM 队列来自 `dws_user_trade_period`（有下单的用户），而"偏好分类/最近活跃日"来自
   * 当日行为明细的 LEFT JOIN —— 下单但当日无行为事件的用户会得到 NULL。发布侧 `ads_user_profile_m`
   * 的这两列是 **NOT NULL**（显式写 NULL 即约束报错，DEFAULT 0/'' 只在"不写该列"时生效），
   * 实测 run 38 的 `PUBLISH_METRIC` 因此报 `RUN_METRIC_PUBLISH_FAILED`
   * （`Column 'favorite_category' cannot be null`）。故按库内既有 unknown 约定兜底：
   * 分类用哨兵 **-1**（与 `DwdSql.behaviorClean` 的 `COALESCE(p.category_id, -1)` 同口径），
   * 日期用 **''**（DDL 自身声明的 unknown 载体），此时 `active_level` 的 ELSE 分支给 '低'。
   *
   * 稳定次序键（§11.4 L447「NTILE 的同值加**稳定源/用户 ID 排序**保证可复现」）：三个 NTILE 的排序键
   * 后均追加 `user_id ASC`（库名已按源派生，单次计算只含一个源 ⇒ 稳定源即用户 ID）。原值并列时
   * （同日下单、单数与金额相同）无此键则桶号随物理顺序变化：实测 S2-06 同值 5 用户升序落盘得
   * `1→(5,1,1) … 5→(1,5,5)`、降序落盘得完全相反的 `1→(1,5,5) … 5→(5,1,1)` ⇒ 同一份数据重跑会
   * 给人打上相反的 RFM 标签，八类 `value_group` 也随之翻转。
   *
   * S3-01 原值与窗口（§11.4 L447「**记录 R/F/M 原值、score、segment、窗口、rule_version，不仅存标签**」，
   * §9.3 L334「RFM 完整性需补」）：随分档一并落 **R/F/M 原值** `r_days`（窗口末日 − 末次购买日，天）/
   * `f_count`（窗口内有效支付订单数）/ `m_amount`（窗口内有效支付金额）与**观察窗口**
   * `period_start`/`period_end`。窗口取**本次评分实际使用的值**（即 SQL 参数派生、参与
   * `DATEDIFF`/`NTILE` 的那个窗口），格式统一为 yyyy-MM-dd —— 与同表 `last_buy_date`/
   * `last_active_date` 同形，因此 `r_days = DATEDIFF(period_end, last_buy_date)` 可被 SQL 直接复核；
   * 上游 `dws_user_trade_period` 的 `period_start/period_end` 仍是原始 yyyyMMdd 口径，两者各自稳定、
   * 不互相改写。只有分档没有原值时，"r=5" 究竟是「昨天买过」还是「窗口内最早一天买过」无从判断。
   *
   * S3-01 同轮修掉的既有缺陷：窗口转换原先走 `regexp_replace` + 反斜杠正则，被解析器吃掉转义后
   * 窗口原样保留、`DATEDIFF` 恒 NULL（链路后果与实测见 {@link isoDay}）。**该修正改变了
   * `r`/`value_group`/`active_level`/`lifecycle_state` 的取值**，故 `rule_version` 由 `rfm-v1`
   * 升为 `rfm-v2`：新旧数据口径不同、不得混算（§11.4 要求记录规则版本正是为此）。
   */
  def userProfile(ns: WarehouseNamespace, dt: String, periodStart: String, periodEnd: String,
                  snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_user_profile", dt, snapshotId)}
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
       |  COALESCE(a.favorite_category, -1) AS favorite_category,
       |  COALESCE(a.last_active_date, '') AS last_active_date,
       |  tp.last_buy_date,
       |  CASE WHEN tp.order_count = 1 AND tp.last_buy_date = ps THEN '新用户'
       |       WHEN DATEDIFF(pe, tp.last_buy_date) > 60 THEN '流失风险'
       |       WHEN DATEDIFF(pe, tp.last_buy_date) > 30 THEN '沉默'
       |       ELSE '活跃' END AS lifecycle_state,
       |  'rfm-v2' AS rule_version,
       |  '$dt' AS calc_date,
       |  DATEDIFF(pe, tp.last_buy_date) AS r_days,
       |  tp.order_count AS f_count,
       |  tp.sale_amount AS m_amount,
       |  tp.ps AS period_start,
       |  tp.pe AS period_end
       |FROM (
       |  SELECT user_id, last_buy_date, order_count, sale_amount,
       |         ${isoDay(periodStart)} AS ps,
       |         ${isoDay(periodEnd)} AS pe,
       |         NTILE(5) OVER (ORDER BY DATEDIFF(
       |           ${isoDay(periodEnd)}, last_buy_date) ASC, user_id ASC) AS r_ntile,
       |         NTILE(5) OVER (ORDER BY order_count ASC, user_id ASC) AS f_ntile,
       |         NTILE(5) OVER (ORDER BY sale_amount ASC, user_id ASC) AS m_ntile
       |  FROM ${ns.dws}.dws_user_trade_period
       |  WHERE dt = '$dt'
       |) tp
       |LEFT JOIN (
       |  SELECT user_id, event_date AS last_active_date, category_id AS favorite_category
       |  FROM (
       |    SELECT user_id, category_id, event_date,
       |           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY cnt DESC, category_id ASC) AS rn
       |    FROM (
       |      SELECT user_id, category_id, MAX(event_date) AS event_date, COUNT(*) AS cnt
       |      FROM ${ns.dwd}.dwd_user_behavior_detail
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
  def dataQuality(ns: WarehouseNamespace, dt: String, snapshotId: Option[String] = None): String =
    s"""
       |${insertTarget(ns, "ads_data_quality", dt, snapshotId)}
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
       |    FROM ${ns.dwd}.dwd_order_detail
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
       |  FROM ${ns.dwd}.dwd_user_behavior_detail
       |  WHERE dt = '$dt'
       |  UNION ALL
       |  SELECT 'EVENT_ID_UNIQUE' AS rule_code,
       |         CAST((SELECT COUNT(*) FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt') AS BIGINT) AS check_count,
       |         CAST((SELECT COUNT(*) FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') AS BIGINT) AS error_count,
       |         CAST((SELECT COUNT(*) FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') AS DECIMAL(8,6))
       |           / (SELECT COUNT(*) FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt') AS error_rate,
       |         CASE WHEN (SELECT COUNT(*) FROM ${ns.dwd}.dwd_reject_record WHERE dt = '$dt' AND reject_reason = 'DUPLICATE_EVENT') * 2000
       |                <= (SELECT COUNT(*) FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt') THEN 1 ELSE 0 END AS passed,
       |         '0.0005' AS threshold
       |  UNION ALL
       |  SELECT 'ENUM_WHITELIST' AS rule_code,
       |         CAST(COUNT(*) AS BIGINT) AS check_count,
       |         CAST(SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) AS BIGINT) AS error_count,
       |         CAST(SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) AS DECIMAL(8,6)) / COUNT(*) AS error_rate,
       |         CASE WHEN SUM(CASE WHEN behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search') THEN 1 ELSE 0 END) = 0 THEN 1 ELSE 0 END AS passed,
       |         '0' AS threshold
       |  FROM ${ns.dwd}.dwd_user_behavior_detail
       |  WHERE dt = '$dt'
       |) q
       |""".stripMargin
}