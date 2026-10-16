package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.{AdsSql, DwsSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-23：ADS 大盘**同过滤条件**不变量「UV ≤ PV」（在产质量门规则 9）。
 *
 * 设计标尺（逐字）：设计 §12.3 L507「9. 同过滤条件UV≤PV。」；L512「每条规则记录作用域、阈值、
 * 版本、阶段、实际值、passed、原始/生效严重度。付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，
 * 不能用一个 AMOUNT_RECONCILE 覆盖」—— 故本规则**独立成码**（`ADS_UV_PV_INVARIANT`），
 * 既不与第 8 项「GMV ≥ 净销售 ≥ 0」（`ADS_GMV_NET_SALE_INVARIANT`）合并，也不与任何
 * 次数/对账码合并；指导书 §7 阶段 3 L148「对每个指标固定粒度、**分子分母**、时间窗口、
 * 金额/退款口径、空值规则和版本」。
 *
 * **「同过滤条件」不是装饰词，而是本规则的作用域判据**（本轮的关键实测事实）：
 * `AdsSql.operationOverview` 里 `pv` 与 `uv` 出自**同一个** `CASE WHEN behavior_type = 'view'`
 * 过滤条件（同表 `dwd_user_behavior_detail`、同 dt），因此 `uv ≤ pv` 是构造性不变量
 * （同过滤条件下的去重用户数不可能超过次数）。同表另有一列 `dau = COUNT(DISTINCT user_id)`
 * 是**全事件**去重用户数，**不同过滤条件** ⇒ `dau > uv` 完全合法，**不得**纳入本规则
 * （见用例「dau 不受牵连」）。这正是设计写「同过滤条件」四个字要防的错。
 *
 * 本层实现的口径声明（设计只给不变量与限定词，落点与空值规则由本节声明并登记）：
 *  1. **作用域**：`ads_operation_overview__staging` 的**本次快照 + 本次 dt** 分区；
 *     该表按生产 SQL 恒为**单行/分区**。
 *  2. **判据**：`uv > pv` 的行数必须为 0；`check_count` = 被检查行数，`error_count` = 违反行数。
 *  3. **空值规则**：`pv`/`uv` 为 NULL 时 `uv > pv` 求值为 NULL，本规则**不**把它计为违反，
 *     即 NULL 的判定**唯一所有者**是同一次 job 内既有的 `ADS_STAGING_KEY_NOT_NULL`
 *     （`keyPredicates` 对大盘表的谓词已含 `pv IS NULL … uv IS NULL`，档位 BLOCKING）
 *     ⇒ 该行在**发布层面**依然不放行，「不可证明者不得放行」未被削弱，且**同一缺陷不被
 *     两条规则重复计数/双重阻断**。用例「NULL 归属既有规则」把这个唯一所有者钉住：
 *     若哪天该非空谓词被移除，本规格立刻失败（本声明的成立条件随之失效，须重新裁决）。
 *  4. **只判不改**：违规行不得被静默修正/裁剪/置 0，规则只产出结论（读侧不加启发式纠正）。
 *     `uv > pv` 意味着两列取自**不同过滤条件**（口径破坏）而非展示问题，故档位 BLOCKING
 *     （与第 8 项、漏斗跨层对账同族；设计 L508 的「宽松口径不一概阻断」说的是第 10 项
 *     「支付/浏览用户比」，是**跨口径比率**，与本项的同口径不变量不是一回事）。
 *
 * 判定方式（不读 SQL 文本下结论，除用例 ⑧ 明确标注为结构守卫）：
 *  ① 真跑生产链路 `dwd_user_behavior_detail → AdsSql.operationOverview`，断言规则**通过**
 *     且 pv/uv 确有值且**不相等**（判别力：夹具 pv=3/uv=2，不是 0=0 的假绿）；
 *  ② 破坏形态 `uv > pv` 命中，并给出两列实际值；
 *  ③ 边界：`uv == pv` 与 `0 == 0` 必须**通过**（「≤」不是「<」）；
 *  ④ 快照隔离：同 dt 下**别的快照**的违规行不得影响本快照判定；
 *  ⑤ 违规行的实际值在判定后**原样保留**（只判不改）；
 *  ⑥ NULL 归属既有非空规则（含唯一所有者耦合守卫）；
 *  ⑦ `dau > uv` 合法（不同过滤条件），本规则不得牵连；
 *  ⑧ 「同过滤条件」结构守卫：生产 SQL 中 pv/uv 两行取自**同一个**过滤条件字面，
 *     且 pv 是次数（无 DISTINCT）、uv 是 `COUNT(DISTINCT user_id)`。
 *
 * 夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsUvPvInvariantSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val RuleCode = "ADS_UV_PV_INVARIANT"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-uv-pv-invariant")
    ns = WarehouseNamespace.of("dw_uvpv")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  private def staging = AdsSql.staging(ns, "ads_operation_overview")

  /** 订单明细夹具：`(orderId, userId, amount, refundAmount, finalPaidFlag)`（与 S3-22 同型）。 */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, String, String, Int)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, String, String, Int)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST(10.00 AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(${r._3} AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS order_amount,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS net_paid_amount,
         |       ${r._5} AS final_paid_flag, 0 AS final_refunded_flag,
         |       CAST(NULL AS BIGINT) AS user_key, CAST(NULL AS BIGINT) AS product_key,
         |       CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_order_detail PARTITION(dt = '$dt')
         |SELECT order_id, user_id, product_id, category_id, quantity, unit_price, discount, amount,
         |       order_status, order_time, order_date, city_level, paid_at, order_amount, paid_amount,
         |       refund_amount, net_paid_amount, final_paid_flag, final_refunded_flag,
         |       user_key, product_key, category_key FROM (
         |  ${rows.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /**
   * 行为明细：`users` 的**每个元素产出一条 `view` 事件**（`behavior_id` 唯一）。
   * 于是 `pv = users.size`、`uv = users.distinct.size` —— 重复用户即可造出 `pv > uv` 的合法判别力，
   * 这是本规格 ①「非空且不相等」与边界 ③（`uv == pv`）的构造手段。
   */
  private def writeBehavior(dt: String, users: Seq[Long]): Unit = {
    if (users.isEmpty) return
    var id = 0L
    val row = (u: Long) => {
      id += 1
      s"""SELECT ${id}L AS behavior_id, ${u}L AS user_id, CAST(NULL AS BIGINT) AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, 'view' AS behavior_type,
         |       CAST(NULL AS TIMESTAMP) AS event_time, '$dt' AS event_date,
         |       CAST(NULL AS INT) AS event_hour, CAST(NULL AS STRING) AS city_level,
         |       CAST(NULL AS STRING) AS channel, CAST(NULL AS STRING) AS session_id,
         |       CAST(NULL AS BIGINT) AS source_batch_id, CAST(NULL AS BIGINT) AS user_key,
         |       CAST(NULL AS BIGINT) AS product_key, CAST(NULL AS BIGINT) AS category_key""".stripMargin
    }
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  ${users.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /**
   * 真跑生产链路：`dwd_user_behavior_detail + dwd_order_detail → dws_trade_day →
   * dws_user_trade_period → AdsSql.operationOverview`。
   *
   * 浏览序列 `Seq(1L, 1L, 2L)` ⇒ 期望 `pv = 3`、`uv = 2`（两用户、其中一人看两次）。
   * 订单不可省：`operationOverview` 的 `t`/`r`/`u` 三个子查询来自 DWS 聚合表（非聚合子查询），
   * 表为空会让大盘**一行都不产出** —— 那样规则在空分区上「通过」，正是要避免的假绿。
   */
  private def buildChainPartition(dt: String, sid: String): Unit = {
    writeBehavior(dt, Seq(1L, 1L, 2L))
    writeOrders(dt, Seq(
      (101L, 1L, "10.00", "0.00", 1),
      (102L, 2L, "20.00", "0.00", 1)))
    spark.sql(DwsSql.tradeDay(ns, dt))
    spark.sql(DwsSql.userTradePeriod(ns, dt, dt, dt))
    spark.sql(AdsSql.operationOverview(ns, dt, Some(sid)))
  }

  /**
   * 写一条**pv/uv 可指定**的大盘行到独立快照分区（用于制造违反形态与边界形态）。
   *
   * 为什么不用「复制基线行再改两列」：spark 禁止 `INSERT OVERWRITE TABLE t … SELECT … FROM t`
   * （实测 `cannotOverwriteTableThatIsBeingReadFromError`）；而动态拼投影列还会让
   * `FixtureWriteShapeSpec` 的静态写入形状守卫失去判别力（它要求**逐列命名**、与唯一所有者同序）。
   * 故此处按 `LocalSchemaInitJob` 的 `ads_operation_overview__staging` **唯一所有者列序**逐列命名写死：
   * 14 个非分区列（`snapshot_id`/`dt` 为静态分区），列名/列序由守卫（A3/A4）静态核对。
   * `pv`/`uv` 两列由参数给出（`None` ⇒ NULL）、`dau` 亦可指定；其余 11 列取固定的**非空合法**值，
   * 以便「空跑绿」不可能发生。
   */
  private def writeOverview(dst: String, dt: String,
                            pv: Option[String], uv: Option[String], dau: Long): Unit = {
    def num(v: Option[String]): String =
      v.map(x => s"CAST('$x' AS BIGINT)").getOrElse("CAST(NULL AS BIGINT)")
    spark.sql(
      s"""INSERT OVERWRITE TABLE $staging PARTITION(snapshot_id = '$dst', dt = '$dt')
         |SELECT ${num(pv)} AS pv, ${num(uv)} AS uv, ${dau}L AS dau, 2L AS order_count,
         |       CAST('30.00' AS DECIMAL(18,2)) AS sale_amount,
         |       CAST('28.00' AS DECIMAL(18,2)) AS net_sale_amount,
         |       CAST('15.00' AS DECIMAL(18,2)) AS avg_order_value,
         |       CAST('0.0667' AS DECIMAL(8,4)) AS refund_rate,
         |       CAST('0.0000' AS DECIMAL(8,4)) AS full_refund_rate,
         |       CAST('0.0000' AS DECIMAL(8,4)) AS repeat_rate,
         |       '20260901' AS repeat_period_start,
         |       '20260930' AS repeat_period_end,
         |       3L AS fav_cnt, 4L AS cart_add_cnt""".stripMargin)
  }

  private def check(sid: String, dt: String) = AdsQualityJob.uvPvInvariantCheck(spark, ns, sid, dt)

  private def pairOf(sid: String, dt: String): (String, String) = {
    val r = spark.sql(
      s"SELECT pv, uv FROM $staging WHERE snapshot_id = '$sid' AND dt = '$dt'").collect().head
    (String.valueOf(r.get(0)), String.valueOf(r.get(1)))
  }

  // ---------------------------------------------------------------- 用例

  "生产链路（dwd_user_behavior_detail → ads_operation_overview，两用户共三次浏览）" should
    "通过规则，且 pv/uv 确有值且不相等（非空跑绿）" in {
    val dt = "20260901"
    buildChainPartition(dt, "S_UVPV_CHAIN")

    val (pv, uv) = pairOf("S_UVPV_CHAIN", dt)
    withClue(s"夹具判别力：pv=$pv uv=$uv 必须是 3/2（非空、不相等），否则本用例会因'0 ≤ 0'而假绿；") {
      pv should be("3")
      uv should be("2")
    }

    val c = check("S_UVPV_CHAIN", dt)
    c.ruleCode should be(RuleCode)
    c.severity should be("BLOCKING")
    c.layer should be("ADS_STAGING")
    c.targetTable should endWith("ads_operation_overview__staging")
    c.passed should be(true)
    c.checkCount should be(1L)
    c.errorCount should be(0L)
    c.detail should include("3")
    c.detail should include("2")
  }

  "uv > pv（两列取自不同过滤条件）" should "命中，并同时给出两列实际值" in {
    val dt = "20260902"
    writeOverview("S_UVPV_GT", dt, Some("2"), Some("5"), 5L)

    val c = check("S_UVPV_GT", dt)
    c.passed should be(false)
    c.checkCount should be(1L)
    c.errorCount should be(1L)
    c.detail should include("pv=2")
    c.detail should include("uv=5")
  }

  "uv == pv（每个用户恰好一次浏览）" should "通过（「≤」不是「<」）" in {
    val dt = "20260903"
    writeOverview("S_UVPV_EQUAL", dt, Some("2"), Some("2"), 2L)

    val c = check("S_UVPV_EQUAL", dt)
    c.passed should be(true)
    c.errorCount should be(0L)
  }

  "0 == 0（当日无浏览事件）" should "通过（不存在即 0，与大盘 pv 同型）" in {
    val dt = "20260904"
    writeOverview("S_UVPV_ZERO", dt, Some("0"), Some("0"), 0L)

    val c = check("S_UVPV_ZERO", dt)
    c.passed should be(true)
    c.checkCount should be(1L)
    c.errorCount should be(0L)
  }

  "同 dt 下别的快照违规" should "不影响本快照判定（快照隔离）" in {
    val dt = "20260905"
    writeOverview("S_UVPV_GOOD", dt, Some("9"), Some("4"), 4L)
    writeOverview("S_UVPV_OTHERBAD", dt, Some("1"), Some("7"), 7L)

    val good = check("S_UVPV_GOOD", dt)
    good.passed should be(true)
    good.checkCount should be(1L)
    good.errorCount should be(0L)

    val other = check("S_UVPV_OTHERBAD", dt)
    other.passed should be(false)
    other.errorCount should be(1L)
  }

  "违规行" should "在判定后原样保留（只判不改）" in {
    val dt = "20260906"
    writeOverview("S_UVPV_KEEP", dt, Some("2"), Some("5"), 5L)

    check("S_UVPV_KEEP", dt).passed should be(false)
    pairOf("S_UVPV_KEEP", dt) should be(("2", "5"))
  }

  "pv 为 NULL" should "不由本规则重复判定（唯一所有者是既有 ADS_STAGING_KEY_NOT_NULL，档位 BLOCKING）" in {
    val dt = "20260907"
    writeOverview("S_UVPV_NULLPV", dt, None, Some("3"), 3L)

    val c = check("S_UVPV_NULLPV", dt)
    c.passed should be(true)         // 本规则不把 NULL 计为违反（不与唯一所有者重复判定）
    c.errorCount should be(0L)
    c.checkCount should be(1L)

    // 唯一所有者耦合守卫：本声明的成立前提是「同一次 job 内既有的非空阻断规则覆盖 pv/uv」。
    // 若该谓词被移除，NULL 行就既不被本规则判、也不被任何规则判 ⇒ 本声明的口径失效，
    // 故此断言必须失败（而不是静默放行）。
    val predicate = AdsQualityJob.keyPredicates(ns)(staging)
    withClue("pv/uv 的非空判定必须仍由 ADS_STAGING_KEY_NOT_NULL 承担：") {
      predicate should include("pv IS NULL")
      predicate should include("uv IS NULL")
    }
  }

  "dau > uv（全事件去重用户数，与 pv/uv 不同过滤条件）" should
    "通过，本规则不牵连非同一过滤条件的列" in {
    val dt = "20260908"
    // 4 个活跃用户，其中 2 人只下单/只收藏而不浏览 ⇒ uv=2 ≤ pv=2 合法，而 dau=4 > uv。
    writeOverview("S_UVPV_DAU", dt, Some("2"), Some("2"), 4L)

    val r = spark.sql(s"SELECT pv, uv, dau FROM $staging " +
      s"WHERE snapshot_id = 'S_UVPV_DAU' AND dt = '$dt'").collect().head
    withClue("夹具判别力：必须真的是 dau > uv > 0，否则本用例证明不了作用域边界；") {
      r.getLong(2) should be(4L)
      r.getLong(1) should be(2L)
    }

    check("S_UVPV_DAU", dt).passed should be(true)
  }

  "「同过滤条件」结构守卫" should "钉住 pv/uv 出自生产 SQL 中同一个过滤条件字面（pv 为次数、uv 为去重用户）" in {
    val sql = AdsSql.operationOverview(ns, "20260901")
    val pvLine = sql.linesIterator.find(_.contains("AS pv")).getOrElse(fail("大盘 SQL 中找不到 pv 投影"))
    val uvLine = sql.linesIterator.find(_.contains("AS uv")).getOrElse(fail("大盘 SQL 中找不到 uv 投影"))

    def viewFilter(line: String): String = {
      val from = line.indexOf("CASE WHEN")
      val to = line.indexOf(" THEN")
      from should be >= 0
      to should be > from
      line.substring(from + "CASE WHEN".length, to).trim
    }

    withClue("pv/uv 必须取同一个过滤条件，否则「同过滤条件 UV≤PV」这个前提本身不成立：") {
      viewFilter(pvLine) should be(viewFilter(uvLine))
      viewFilter(pvLine) should be("behavior_type = 'view'")
    }
    pvLine should include("COUNT(CASE WHEN")
    pvLine should not include "DISTINCT"
    uvLine should include("COUNT(DISTINCT CASE WHEN")
    uvLine should include("user_id")
  }
}
