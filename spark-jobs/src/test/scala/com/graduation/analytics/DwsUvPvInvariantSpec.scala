package com.graduation.analytics

import com.graduation.analytics.job.{AdsQualityJob, LocalSchemaInitJob}
import com.graduation.analytics.sql.DwsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-25：DWS 商品×日期行为宽表**同过滤条件**不变量「UV ≤ PV」—— 设计 §12.3 第 9 项 L507
 * 在 **DWS 层的同型站点**（在产质量门规则 10）。
 *
 * 设计标尺（逐字）：设计 §12.3 L507「9. 同过滤条件UV≤PV。」；同节 L512「每条规则记录作用域、阈值、
 * 版本、阶段、实际值、passed、原始/生效严重度。付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，
 * 不能用一个 AMOUNT_RECONCILE 覆盖」—— 故本规则**独立成码**（`DWS_UV_PV_INVARIANT`）：
 * 它与 ADS 侧的 `ADS_UV_PV_INVARIANT`（S3-23）**同型但不同站点**，两处的表、粒度、分区维度都不同
 * （ADS 大盘＝一次聚合成一行/分区、有 snapshot_id；本表＝按 `product_id×category_id` 逐行、
 * **无** snapshot 维度），一处通过不能证明另一处通过，故不得合并。
 * 指导书 §7 阶段 3 L148「对每个指标固定粒度、**分子分母**、时间窗口、金额/退款口径、空值规则和版本」。
 *
 * **「同过滤条件」不是装饰词，而是本规则的作用域判据**（本轮的关键实测事实）：
 * `DwsSql.productBehaviorDay` 里 `pv = SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END)`、
 * `uv = COUNT(DISTINCT CASE WHEN b.behavior_type = 'view' THEN b.user_id END)` —— 两列出自
 * **同一个** `CASE WHEN behavior_type = 'view'` 过滤条件（同表 `dwd_user_behavior_detail` 别名 b、
 * 同 `b.dt = '$dt'`），因此 `uv ≤ pv` 是构造性不变量（同过滤条件下的去重用户数不可能超过次数）。
 * 本表另有 `fav`/`cart`（favorite/cart_add）与 `buy`（来自 `dwd_order_detail`）—— **不同过滤条件**，
 * 它们的列间大小关系（如 `buy > uv`）完全合法，**不得**纳入本规则（见用例「fav/cart/buy 不受牵连」）。
 *
 * 本层实现的口径声明（设计只给不变量与限定词，落点与空值规则由本节声明并登记，见 V28 与
 * `docs/acceptance/s3-25-dws-uv-pv-20260916/`）：
 *  1. **作用域**：`dws_product_behavior_day` 的**本次 dt 分区**。该表在 `LocalSchemaInitJob` 里是
 *     `(product_id, category_id, pv, uv, fav, cart, buy) PARTITIONED BY (dt)` —— **没有 snapshot 维度**，
 *     故本规则不接收 `sid`（与既有的 `ADS_DWS_FUNNEL_RECONCILE` 同 dt 读 DWS 的作用域同型）；
 *     跨快照隔离由 ADS 侧规则负责，本规则不冒充。
 *  2. **判据**：`pv IS NULL OR uv IS NULL OR uv > pv` 的行数必须为 0；
 *     `check_count` = 该 dt 分区行数，`error_count` = 违反行数。
 *  3. **空值规则**：`pv`/`uv` 任一为 NULL ⇒ **不通过**。理由（实测）：ADS 站点上这两列的 NULL 有
 *     **直接**唯一所有者（`AdsQualityJob.keyPredicates` 对 `ads_operation_overview` 的谓词含
 *     `pv IS NULL OR uv IS NULL`），故 S3-23 的 ADS 规则不重复判定；而**本表在 ADS 暂存表之外
 *     没有任何关键列非空谓词**（实测 `keyPredicates` 的键集），按「唯一所有者原则」本层空缺即由
 *     本码承担；三值逻辑下 `uv > pv` 在 NULL 时求值为 NULL，不显式判 NULL 则「两列整体未计算」
 *     会被静默放行（不可证明的不变量不得放行）。
 *  4. **空分区不判违反**：`checked = 0` ⇒ `passed = true`。Stage 7 T-R1 证明来源当天可以合法
 *     没有 behavior；这时没有商品行为事实可检查，不能凭“0 行”制造 UV/PV 违规。
 *     `ADS_STAGING_PRESENT` 只负责确认派生暂存分区真实存在且 Location 可读，允许 rowCount=0；
 *     一旦本表有行，NULL/UV>PV 仍由本码严格阻断。
 *  5. **只判不改**：违规行不得被静默修正/裁剪/置 0。`uv > pv` 意味着两列取自**不同过滤条件**
 *     （口径破坏）而非展示问题，故档位 BLOCKING（设计 L508 的「宽松口径不一概阻断」说的是第 10 项
 *     「支付/浏览用户比」这类**跨口径比率**，与本项的同口径不变量不是一回事）。
 *
 * 判定方式（不读 SQL 文本下结论，除用例 ⑧ 明确标注为结构守卫）：
 *  ① 真跑生产链路 `dwd_user_behavior_detail (+ dwd_order_detail) → DwsSql.productBehaviorDay`，
 *     断言规则**通过**、`check_count = 1`，且 pv/uv 确有值且**不相等**（判别力：夹具 pv=3/uv=2，
 *     不是 0=0 的假绿）；
 *  ② 破坏形态 `uv > pv` 命中，给出 product_id 与两列实际值；
 *  ③ 边界：`uv == pv` 与 `0 == 0` 必须**通过**（「≤」不是「<」）；
 *  ④ NULL（pv 或 uv 为 NULL）**判不通过**（本层无直接所有者）+ 唯一所有者耦合守卫；
 *  ⑤ 违规行的实际值在判定后**原样保留**（只判不改）；
 *  ⑥ 多行时逐行计数（一行违规只计一行）；
 *  ⑦ `fav`/`cart`/`buy` 与 pv/uv 是不同过滤条件，本规则不得牵连；
 *  ⑧ 结构守卫：生产 SQL 中 pv/uv 两行取自**同一个**过滤条件字面，pv 是次数（无 DISTINCT）、
 *     uv 是 `COUNT(DISTINCT user_id)`；
 *  ⑨ 空分区：`checked = 0`、**不通过为 false**（存在性另有所有者，附派生表守卫证据）。
 *
 * 夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class DwsUvPvInvariantSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val RuleCode = "DWS_UV_PV_INVARIANT"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("dws-uv-pv-invariant")
    ns = WarehouseNamespace.of("dw_dwsuvpv")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** 行为明细：`(userId, productId, categoryId)` 每个元素产出一条 `view` 事件。 */
  private def writeBehavior(dt: String, rows: Seq[(Long, Long, Long)]): Unit = {
    if (rows.isEmpty) return
    var id = 0L
    val row = (r: (Long, Long, Long)) => {
      id += 1
      s"""SELECT ${id}L AS behavior_id, ${r._1}L AS user_id, ${r._2}L AS product_id,
         |       ${r._3}L AS category_id, 'view' AS behavior_type,
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
         |  ${rows.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /** 订单明细：`(orderId, userId, productId, amount, refundAmount, finalPaidFlag)`（本表的 `buy` 来源）。 */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, Long, String, String, Int)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, Long, String, String, Int)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, ${r._3}L AS product_id, 10L AS category_id,
         |       1 AS quantity, CAST(10.00 AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(${r._4} AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS order_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(${r._5} AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS net_paid_amount,
         |       ${r._6} AS final_paid_flag, 0 AS final_refunded_flag,
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

  private def productBehavior = s"${ns.dws}.dws_product_behavior_day"

  /**
   * 直接写目标表的一条/多条行（制造违反形态、边界形态与 NULL 形态）。
   *
   * 为什么不用「复制基线行再改两列」：Spark 禁止 `INSERT OVERWRITE TABLE t … SELECT … FROM t`
   * （实测 `cannotOverwriteTableThatIsBeingReadFromError`）；而动态拼投影列会让
   * `FixtureWriteShapeSpec` 的静态写入形状守卫失去判别力。故按唯一所有者列序
   * （`product_id, category_id, pv, uv, fav, cart, buy`，`dt` 为静态分区）**逐列命名**写死；
   * `pv`/`uv` 由参数给出（`None` ⇒ NULL），其余 5 列取固定的**非空合法**值 ⇒ 空跑绿不可能发生。
   */
  private def writeProductBehaviorRows(dt: String,
                                       rows: Seq[(Long, Long, Option[String], Option[String],
                                         Long, Long, Long)]): Unit = {
    if (rows.isEmpty) return
    def num(v: Option[String]): String =
      v.map(x => s"CAST('$x' AS BIGINT)").getOrElse("CAST(NULL AS BIGINT)")
    val row = (r: (Long, Long, Option[String], Option[String], Long, Long, Long)) =>
      s"""SELECT ${r._1}L AS product_id, ${r._2}L AS category_id,
         |       ${num(r._3)} AS pv, ${num(r._4)} AS uv,
         |       ${r._5}L AS fav, ${r._6}L AS cart, ${r._7}L AS buy""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_product_behavior_day PARTITION(dt = '$dt')
         |SELECT product_id, category_id, pv, uv, fav, cart, buy FROM (
         |  ${rows.map(row).mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /**
   * 真跑生产链路：`dwd_user_behavior_detail + dwd_order_detail → DwsSql.productBehaviorDay`。
   *
   * 浏览序列 `Seq((1,1,10), (1,1,10), (2,1,10))` ⇒ 商品 1 期望 `pv = 3`、`uv = 2`
   * （两用户、其中一人看两次）—— 这就是用例 ① 的判别力（pv ≠ uv 且都非 0）。
   */
  private def buildChainPartition(dt: String): Unit = {
    writeBehavior(dt, Seq((1L, 1L, 10L), (1L, 1L, 10L), (2L, 1L, 10L)))
    writeOrders(dt, Seq((101L, 1L, 1L, "10.00", "0.00", 1), (102L, 2L, 1L, "20.00", "0.00", 1)))
    spark.sql(DwsSql.productBehaviorDay(ns, dt))
  }

  private def check(dt: String) = AdsQualityJob.dwsUvPvInvariantCheck(spark, ns, dt)

  private def rowOf(dt: String, productId: Long): (String, String) = {
    val r = spark.sql(
      s"SELECT pv, uv FROM $productBehavior WHERE dt = '$dt' AND product_id = $productId")
      .collect().head
    (String.valueOf(r.get(0)), String.valueOf(r.get(1)))
  }

  // ---------------------------------------------------------------- 用例

  "生产链路（dwd_user_behavior_detail → dws_product_behavior_day，商品 1 两用户共三次浏览）" should
    "通过规则，且 check_count=1、pv/uv 确有值且不相等（非空跑绿）" in {
    val dt = "20260901"
    buildChainPartition(dt)

    val r = check(dt)
    r.ruleCode should be(RuleCode)
    r.severity should be("BLOCKING")
    r.passed should be(true)
    r.errorCount should be(0L)
    r.checkCount should be(1L)
    withClue(s"商品 1 的 DWS 行 pv/uv=${rowOf(dt, 1L)}（须为 3/2，否则本用例的判别力不成立）：") {
      rowOf(dt, 1L) should be("3" -> "2")
    }
    r.detail should include("1 行均满足 uv ≤ pv")
  }

  "破坏形态（某商品 uv > pv）" should "判不通过，并在 detail 里给出 product_id 与两列实际值" in {
    val dt = "20260902"
    writeProductBehaviorRows(dt, Seq((7L, 10L, Some("1"), Some("2"), 0L, 0L, 0L)))

    val r = check(dt)
    r.passed should be(false)
    r.checkCount should be(1L)
    r.errorCount should be(1L)
    r.detail should include("product_id=7")
    r.detail should include("pv=1")
    r.detail should include("uv=2")
  }

  "边界形态" should "uv == pv 与 0 == 0 都通过（「≤」不是「<」）" in {
    val dt = "20260903"
    writeProductBehaviorRows(dt, Seq(
      (1L, 10L, Some("2"), Some("2"), 0L, 0L, 0L),
      (2L, 10L, Some("0"), Some("0"), 0L, 0L, 0L)))

    val r = check(dt)
    r.checkCount should be(2L)
    r.errorCount should be(0L)
    r.passed should be(true)
  }

  "空值形态（pv 或 uv 为 NULL）" should "判不通过：本层没有直接的关键列非空所有者，由本码自判" in {
    val dt = "20260904"
    writeProductBehaviorRows(dt, Seq(
      (1L, 10L, None, Some("1"), 0L, 0L, 0L),
      (2L, 10L, Some("1"), None, 0L, 0L, 0L)))

    val r = check(dt)
    r.checkCount should be(2L)
    r.errorCount should be(2L)
    r.passed should be(false)
    r.detail should include("product_id=1")
    r.detail should include("product_id=2")
  }

  "唯一所有者耦合守卫" should "钉住「本表在 ADS 暂存表之外没有关键列非空谓词」这一口径前提" in {
    // 若哪天有人给 dws_product_behavior_day 加了直接的非空守卫（keyPredicates 出现本表），
    // 本规则的 NULL 自判就与「唯一所有者原则」冲突 ⇒ 本用例立刻失败，口径须重新裁决。
    val predicates = AdsQualityJob.keyPredicates(ns)
    predicates.keySet should not contain productBehavior
    withClue("派生表 ads_product_conversion 的 pv_users 非空守卫应存在（空值另有下游所有者）：") {
      predicates.values.exists(_.contains("pv_users IS NULL")) should be(true)
    }
  }

  "只判不改" should "违规行的 pv/uv 在判定后原样保留（规则不修数据）" in {
    val dt = "20260905"
    writeProductBehaviorRows(dt, Seq((9L, 10L, Some("1"), Some("5"), 1L, 2L, 0L)))

    check(dt).passed should be(false)
    rowOf(dt, 9L) should be("1" -> "5")
    check(dt).passed should be(false)
  }

  "多行计数" should "逐行判定：两行中一行违反则 error_count=1、check_count=2" in {
    val dt = "20260906"
    writeProductBehaviorRows(dt, Seq(
      (1L, 10L, Some("5"), Some("3"), 0L, 0L, 0L),
      (2L, 10L, Some("1"), Some("4"), 0L, 0L, 0L)))

    val r = check(dt)
    r.checkCount should be(2L)
    r.errorCount should be(1L)
    r.passed should be(false)
    r.detail should not include "product_id=1"
    r.detail should include("product_id=2")
  }

  "fav/cart/buy 不受牵连" should "不同过滤条件（favorite/cart_add/订单）的列间大小关系不纳入本规则" in {
    val dt = "20260907"
    // buy=0/fav=0/cart=0 而 uv=1、pv=1；再给一组 fav/cart 远大于 pv 的合法行
    writeProductBehaviorRows(dt, Seq(
      (1L, 10L, Some("1"), Some("1"), 0L, 0L, 0L),
      (2L, 10L, Some("1"), Some("1"), 9L, 9L, 9L)))

    val r = check(dt)
    r.checkCount should be(2L)
    r.errorCount should be(0L)
    r.passed should be(true)
  }

  "结构守卫（标注为源码文本断言）" should
    "生产 SQL 中 pv/uv 两行取自同一个 behavior_type = 'view' 过滤条件，且 uv 是 COUNT(DISTINCT user_id)" in {
    val src = new String(
      java.nio.file.Files.readAllBytes(
        P2TestSupport.repoRoot.resolve(
          "spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala")),
      java.nio.charset.StandardCharsets.UTF_8)

    val pvLine = "SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END) AS pv"
    val uvLine = "COUNT(DISTINCT CASE WHEN b.behavior_type = 'view' THEN b.user_id END) AS uv"
    withClue("pv 行（次数，不得出现 DISTINCT）：") {
      src should include(pvLine)
    }
    withClue("uv 行（去重用户）：") {
      src should include(uvLine)
    }
    // 两条字面量必须来自**同一个**过滤条件字面：把 pv/uv 两行都换成空串后，
    // 生产 SQL 里不应再剩下别的 view 过滤条件（否则说明两列可能取自不同条件）
    src.replace(pvLine, "").replace(uvLine, "") should not include "behavior_type = 'view' THEN 1"
  }

  "空分区" should "不判违反（check_count=0、passed=true）：无行为事实时保持合法空态" in {
    val dt = "20260908"
    val r = check(dt)
    r.checkCount should be(0L)
    r.errorCount should be(0L)
    r.passed should be(true)
    // 若后续真的产出 ads_product_conversion 行，关键列非空守卫仍然存在；这里只是不把 0 行空态冒充错误。
    AdsQualityJob.keyPredicates(ns).values.exists(_.contains("pv_users IS NULL")) should be(true)
  }
}
