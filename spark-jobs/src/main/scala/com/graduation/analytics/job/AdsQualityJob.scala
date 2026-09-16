package com.graduation.analytics.job

import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ListBuffer

/**
 * R6-13 质量门（V2.0 §16.1/§16.3/§16.4，code=dqc）：
 * 在 ADS 写入**暂存分区之后、正式分区发布之前**检查暂存结果，全部阻断规则通过才允许发布。
 *
 * 规则（层次 = ADS_STAGING / PUBLISH，§16.5 运维页展示字段齐全）：
 *  1. ADS_STAGING_PRESENT        BLOCKING  8 张暂存表本次快照分区必须存在且行数 > 0
 *  2. ADS_STAGING_SNAPSHOT_ISOLATION ERROR 同一 dt 下不得混入其它 snapshot_id 的暂存分区（观察项，
 *     降级为 ERROR 的理由见规则 2 处注释：设为 BLOCKING 会造成发布死锁）
 *  3. ADS_STAGING_KEY_NOT_NULL   BLOCKING  关键列不得为空（逐表真实 COUNT）
 *  4. PUB_DQ_BLOCKING_RULES      BLOCKING  staging.ads_data_quality 的阻断规则必须 passed=1
 *  5. PUB_DQ_EVENT_ID_UNIQUE     ERROR     仅记录不阻断（与 Java QualityChecker.corePassed 口径一致）
 *  6. ADS_DWS_FUNNEL_RECONCILE   BLOCKING  ADS 漏斗 stage 汇总 = DWS 漏斗对应列（§16.4 跨层对账）
 *  7. ADS_DWS_FUNNEL_RATE_RECONCILE BLOCKING ADS 漏斗率列 = DWS 漏斗全站行同 dt 率列（S3-10）
 *  8. ADS_GMV_NET_SALE_INVARIANT BLOCKING ADS 大盘同归属口径不变量 GMV≥净销售≥0（S3-22，含 NULL 判不通过）
 *
 * 任一 BLOCKING 未通过 → JobResult.status=FAILED（JobRunner 退出码 1）→ 编排方置阶段失败、
 * **不执行 PUBLISH_METRIC**，正式分区与旧 ACTIVE 快照保持不变（§16.3）。
 * 本作业不 catch 异常：采集/查询失败即作业失败（失败必须留痕，不冒充通过）。
 */
class AdsQualityJob extends WarehouseJob {
  override val code: String = "dqc"
  override val description: String = "ADS 暂存分区质量门（发布前阻断，§16）"

  override def validate(args: JobArgs): Either[String, Unit] =
    args.outputSnapshotId.filter(_.nonEmpty)
      .toRight("dqc 需要 --outputSnapshotId（只检查本次快照的暂存分区）")
      .map(_ => ())

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val dt = args.businessDate
    val ns = WarehouseNamespace.fromArgs(args)
    val sid = args.outputSnapshotId.get
    val staging = FunnelAdsJob.stagingTables(ns)

    val checks = ListBuffer.empty[QualityCheck]
    // 分区证据来自 Hive 元数据实测（分区规格里的 snapshot_id + 真实 COUNT + Location）
    val parts = PartitionEvidence.collect(spark, staging, Some(sid), Some(dt))
    val current = parts.filter(_.snapshotId.contains(sid))
    val rowsOf = current.map(p => p.table -> p.rowCount).toMap
    val foreign = parts.filterNot(_.snapshotId.contains(sid))

    // 规则 1：暂存分区必须存在且非空
    val empty = staging.filter(t => rowsOf.getOrElse(t, 0L) <= 0L)
    checks += QualityCheck("ADS_STAGING_PRESENT", "ADS_STAGING", staging.mkString(","),
      staging.size, empty.size, "每表行数>0", "BLOCKING", empty.isEmpty,
      if (empty.isEmpty) s"8 张暂存表行数均>0（合计 ${current.map(_.rowCount).sum} 行）"
      else s"空/缺失暂存表: ${empty.mkString(",")}")

    // 规则 2：同一 dt 的快照隔离 —— 只记录不阻断（ERROR）。
    // 理由（R6-13 实测后定稿）：发布是按"本次快照的暂存路径"逐表切换元数据指针，
    // 陈旧暂存分区**不可能**污染正式分区；若把它设为 BLOCKING，则清理只发生在 pub（发布成功后）而
    // pub 又在 dqc 之后，一旦存在未被引用的历史暂存分区，dqc 将永久阻断 → 发布死锁（运维陷阱）。
    // 故此处降级为观察项（运维页可见），陈旧分区由 pub 按"是否被正式分区引用"清理（§14.4 保留期）。
    checks += QualityCheck("ADS_STAGING_SNAPSHOT_ISOLATION", "ADS_STAGING", staging.mkString(","),
      parts.size.max(staging.size), foreign.size, "0 个非本次快照分区（观察项）", "ERROR", foreign.isEmpty,
      if (foreign.isEmpty) s"dt=$dt 下仅有本快照 $sid 的暂存分区"
      else s"存在其它快照暂存分区（不阻断发布，pub 将按引用关系清理）: " +
        foreign.map(p => s"${p.table}/${p.snapshotId.getOrElse("null")}(${p.rowCount}行)").mkString(","))

    // 规则 3：关键列非空（逐表真实 COUNT，阈值 0）；判据表见伴生对象（可行为验证，不只钉字符串）
    val keyPredicates = AdsQualityJob.keyPredicates(ns)
    var keyChecked = 0L
    var keyErrors = 0L
    val keyDetail = ListBuffer.empty[String]
    keyPredicates.foreach { case (table, pred) =>
      val row = spark.sql(
        s"SELECT COUNT(*), SUM(CASE WHEN $pred THEN 1 ELSE 0 END) FROM $table " +
          s"WHERE snapshot_id = '$sid' AND dt = '$dt'").collect()(0)
      val checked = row.getLong(0)
      val bad = Option(row.get(1)).map(_.toString.toLong).getOrElse(0L)
      keyChecked += checked
      keyErrors += bad
      if (bad > 0) keyDetail += s"$table=$bad"
    }
    checks += QualityCheck("ADS_STAGING_KEY_NOT_NULL", "ADS_STAGING", staging.mkString(","),
      keyChecked, keyErrors, "0 空关键列", "BLOCKING", keyErrors == 0L,
      if (keyErrors == 0L) s"$keyChecked 行关键列全部非空" else s"空关键列: ${keyDetail.mkString(",")}")

    // 规则 4/5：staging 质量大盘的规则结果（阻断规则硬失败，观察项只记录）
    val blockingRules = Set("AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "ENUM_WHITELIST")
    val dqTable = AdsSql.staging(ns, "ads_data_quality")
    val dqRows = spark.sql(
      s"SELECT rule_code, check_count, error_count, passed FROM $dqTable " +
        s"WHERE snapshot_id = '$sid' AND dt = '$dt'").collect()
    val byRule = dqRows.map(r => r.getString(0) -> r).toMap
    val missingRules = blockingRules.filterNot(byRule.contains)
    val failedBlocking = blockingRules.filter(r => byRule.get(r).exists(_.getInt(3) != 1))
    checks += QualityCheck("PUB_DQ_BLOCKING_RULES", "PUBLISH", dqTable,
      blockingRules.size, (missingRules ++ failedBlocking).size, "全部 passed=1", "BLOCKING",
      missingRules.isEmpty && failedBlocking.isEmpty,
      s"阻断规则结果: ${blockingRules.toSeq.sorted.map(r =>
        s"$r=${byRule.get(r).map(x => s"passed=${x.getInt(3)},err=${x.getLong(2)}").getOrElse("缺失")}").mkString("; ")}")
    byRule.get("EVENT_ID_UNIQUE").foreach { r =>
      checks += QualityCheck("PUB_DQ_EVENT_ID_UNIQUE", "PUBLISH", dqTable,
        r.getLong(1), r.getLong(2), "0.0005", "ERROR", r.getInt(3) == 1,
        "观察项：重复 event_id 比率，不阻断发布")
    }

    // 规则 6：跨层对账 ADS 漏斗 vs DWS 漏斗（§16.4 对账公式）
    val funnelStaging = AdsSql.staging(ns, "ads_behavior_funnel")
    val stageMap = Seq("view" -> "view_users", "intent" -> "intent_users",
      "order" -> "order_users", "pay" -> "pay_users")
    val adsSum = spark.sql(
      s"SELECT stage, SUM(user_count) FROM $funnelStaging WHERE snapshot_id = '$sid' AND dt = '$dt' GROUP BY stage")
      .collect().map(r => r.getString(0) -> r.getLong(1)).toMap
    val dwsSum = spark.sql(
      s"SELECT SUM(view_users), SUM(intent_users), SUM(order_users), SUM(pay_users) " +
        s"FROM ${ns.dws}.dws_behavior_funnel_day WHERE dt = '$dt'").collect()(0)
    val mismatch = stageMap.zipWithIndex.filter { case ((stage, _), i) =>
      adsSum.getOrElse(stage, -1L) != Option(dwsSum.get(i)).map(_.toString.toLong).getOrElse(0L)
    }.map(_._1._1)
    checks += QualityCheck("ADS_DWS_FUNNEL_RECONCILE", "ADS_STAGING", funnelStaging,
      stageMap.size, mismatch.size, "逐 stage 差值=0", "BLOCKING", mismatch.isEmpty,
      if (mismatch.isEmpty) s"4 个 stage 汇总与 dws_behavior_funnel_day 一致"
      else s"不一致 stage: ${mismatch.mkString(",")}；ADS=${adsSum.mkString(",")}")

    // 规则 7：跨层对账 ADS 漏斗**率列** vs DWS 漏斗全站行（S3-10，关闭 S3-04 R-1）；
    // 判据表与比对逻辑在伴生对象（可行为验证），本作业只负责把结论放进 JobResult.checks
    checks += AdsQualityJob.funnelRateCheck(spark, ns, sid, dt)

    // 规则 8：ADS 大盘**同归属口径**不变量 GMV≥净销售≥0（S3-22，设计 §12.3 第 8 项 line 506）；
    // 判据与比对逻辑在伴生对象（可行为验证），本作业只负责把结论放进 JobResult.checks
    checks += AdsQualityJob.gmvNetSaleInvariantCheck(spark, ns, sid, dt)

    val all = checks.toList
    val blockingFailed = all.filter(c => c.severity == "BLOCKING" && !c.passed)
    val elapsed = System.currentTimeMillis() - start
    if (blockingFailed.nonEmpty) {
      JobResult.failed(code, args.attemptNo,
        s"质量门阻断发布: ${blockingFailed.map(_.ruleCode).mkString(",")}", elapsed, current, all)
    } else {
      JobResult.success(code, current.map(_.rowCount).sum, all.size,
        all.map(_.errorCount).sum, Some(sid), args.attemptNo, elapsed, current, all)
    }
  }
}

object AdsQualityJob {
  val instance: AdsQualityJob = new AdsQualityJob()

  /**
   * 规则 3「关键列非空」判据表：**暂存表 → 谓词**（键必须覆盖 `FunnelAdsJob.stagingTables` 全部表）。
   *
   * 抽到伴生对象是为了让这条**发布前阻断**规则的覆盖范围可以被**行为验证**：判据命中一个
   * 真构造出来的空值行，而不是只断言源码里有某个字符串（见 `AdsSaleTrendNetSaleSpec`）。
   * 语义仍是"谓词为真的行数必须为 0"，与作业内使用方式一致。
   */
  def keyPredicates(ns: WarehouseNamespace): Map[String, String] = Map(
    AdsSql.staging(ns, "ads_operation_overview") -> "pv IS NULL OR uv IS NULL OR dau IS NULL",
    AdsSql.staging(ns, "ads_active_trend") -> "dau IS NULL",
    AdsSql.staging(ns, "ads_behavior_funnel") -> "stage IS NULL OR user_count IS NULL",
    AdsSql.staging(ns, "ads_hot_product") -> "product_id IS NULL OR product_name IS NULL OR heat_score IS NULL",
    AdsSql.staging(ns, "ads_product_conversion") -> "product_id IS NULL OR pv_users IS NULL",
    AdsSql.staging(ns, "ads_sale_trend") -> "order_count IS NULL OR sale_amount IS NULL OR net_sale_amount IS NULL",
    AdsSql.staging(ns, "ads_user_profile") -> "user_id IS NULL OR r IS NULL OR f IS NULL OR m IS NULL",
    AdsSql.staging(ns, "ads_data_quality") -> "rule_code IS NULL OR check_count IS NULL OR passed IS NULL")

  /**
   * 规则 7「ADS 漏斗**率列** ↔ DWS 漏斗全站行」跨层对账（S3-10，关闭 S3-04 R-1）。
   *
   * 为什么需要它：`ads_behavior_funnel` 的 `overall_buy_rate`/`overall_cart_rate` 由 ADS
   * **只透传不重算**（S3-04 口径④，见 `AdsSql.funnel` 的投影），因此
   * 「ADS 率列 = DWS 同 dt 全站行率列」是构造性不变量。但在此之前，在产阻断规则
   * `ADS_DWS_FUNNEL_RECONCILE` 只对账 4 个 stage 的 `user_count` 汇总，两个整体率列
   * **没有任何在产守卫**（等价断言只存在于 S3-04 的 spec 里，已登记为 S3-04 R-1）。
   * 率列一旦错位（改写、串列、拿别的 dt 或别的维度的行填充），发布出去的漏斗结论就是错的，
   * 而「行数一致」「关键列非空」都可能同时正常。
   *
   * 口径边界（避免与其它规则重复或越界）：
   *  - 只判**跨层是否一致**，不判「比率数值是否异常」：设计 §12.3 第 10 项明确
   *    「支付/浏览用户比及 cohort 解释：宽松口径异常不一概作为阻断规则」，
   *    故本规则不引入任何比率阈值，只做逐格相等判定；
   *  - `user_count` 的 stage 级汇总仍由规则 6 承担，本规则**不**重复对账计数列；
   *  - DWS 侧只取**全站行**（`category_id = -1 AND channel = 'all'`，即 `DwsSql.funnelDay`
   *    写死的唯一一行）：漏斗表带分类/渠道维度列，将来 G-04 落维度行时，
   *    不得把维度口径当全站口径参与比较；
   *  - NULL 语义：分母为 0 时率列是 NULL（`DwsSql.funnelDay` 的 `CASE WHEN ... THEN NULL`），
   *    **NULL 与 NULL 判等**、NULL 与任何数值判不等 —— 既不得把「未计算」当 0，
   *    也不得把 NULL 当「无复购」。
   *
   * 三条率列族各自独立比对（`conversion_rate` 逐 stage、两个整体率列逐行），
   * 返回的 `QualityCheck` 中 `checkCount` = 比对单元格数、`errorCount` = 不一致单元格数，
   * `detail` 逐格给出 `stage/列(ADS=..., DWS=...)`（超过 6 处截断）。
   */
  def funnelRateCheck(spark: SparkSession, ns: WarehouseNamespace,
                      sid: String, dt: String): QualityCheck = {
    val funnelStaging = AdsSql.staging(ns, "ads_behavior_funnel")
    val adsRows = spark.sql(
      s"SELECT stage, conversion_rate, overall_buy_rate, overall_cart_rate FROM $funnelStaging " +
        s"WHERE snapshot_id = '$sid' AND dt = '$dt' ORDER BY stage").collect()
    // DWS 侧：只取全站行（DwsSql.funnelDay 每个 dt 只写这一行）
    val dwsRow = spark.sql(
      s"SELECT intent_rate, order_rate, pay_rate, overall_buy_rate, cart_rate " +
        s"FROM ${ns.dws}.dws_behavior_funnel_day " +
        s"WHERE dt = '$dt' AND category_id = -1 AND channel = 'all'").collect().headOption

    def rate(v: Any): Option[BigDecimal] = Option(v).map(x => BigDecimal(x.toString))
    def dwsRate(i: Int): Option[BigDecimal] = dwsRow.flatMap(r => rate(r.get(i)))

    /** 期望的 stage 级转换率：view 无前序步骤（`AdsSql.funnel` 写 NULL），其余取 DWS 同名率列。 */
    val expectedConversion: Map[String, Option[BigDecimal]] = Map(
      "view" -> None,
      "intent" -> dwsRate(0),
      "order" -> dwsRate(1),
      "pay" -> dwsRate(2))
    val expectedBuyRate = dwsRate(3)
    val expectedCartRate = dwsRate(4)

    def same(a: Option[BigDecimal], b: Option[BigDecimal]): Boolean = (a, b) match {
      case (Some(x), Some(y)) => x.compare(y) == 0
      case (None, None) => true
      case _ => false
    }

    def show(v: Option[BigDecimal]): String = v.map(_.bigDecimal.toPlainString).getOrElse("NULL")
    def cell(stage: String, column: String, a: Option[BigDecimal], b: Option[BigDecimal]): String =
      s"$stage/$column(ADS=${show(a)} ≠ DWS=${show(b)})"

    val bad = ListBuffer.empty[String]
    var checked = 0L
    adsRows.foreach { r =>
      val stage = r.getString(0)
      val conversion = rate(r.get(1))
      val buy = rate(r.get(2))
      val cart = rate(r.get(3))
      checked += 3L
      expectedConversion.get(stage) match {
        case Some(expected) =>
          if (!same(conversion, expected)) bad += cell(stage, "conversion_rate", conversion, expected)
        case None =>
          // ADS 漏斗只有 view/intent/order/pay 四步（设计 §11.3 L441）；
          // 出现第五步说明暂存分区被别的写入方污染，属口径破坏，不能静默跳过
          bad += s"$stage/conversion_rate(未知 stage: ADS=${show(conversion)}，无 DWS 对应率列)"
      }
      if (!same(buy, expectedBuyRate)) bad += cell(stage, "overall_buy_rate", buy, expectedBuyRate)
      if (!same(cart, expectedCartRate)) bad += cell(stage, "overall_cart_rate", cart, expectedCartRate)
    }

    val shown = bad.take(6).mkString(",")
    QualityCheck("ADS_DWS_FUNNEL_RATE_RECONCILE", "ADS_STAGING", funnelStaging,
      checked, bad.size.toLong, "逐 stage/率列 差值=0（NULL 与 NULL 判等）", "BLOCKING", bad.isEmpty,
      if (bad.isEmpty) s"$checked 个率单元格与 dws_behavior_funnel_day 全站行逐格相等"
      else s"率列跨层不一致 ${bad.size} 处: $shown" + (if (bad.size > 6) "…" else ""))
  }

  /**
   * 规则 8「ADS 大盘同归属口径不变量 GMV ≥ 净销售 ≥ 0」的在产阻断守卫（S3-22，设计 §12.3 第 8 项）。
   *
   * 为什么需要它：`ads_operation_overview` 是页面大盘与指标库 `MP_OVERVIEW_CORE_NOT_NULL`、
   * `MP_VALUE_MATCH_ADS` 的数值来源，但在此之前，在产阻断规则对这张表**只覆盖**
   * `pv/uv/dau` 三个关键列非空（`keyPredicates`）；`sale_amount`/`net_sale_amount` 两个金额列
   * **没有任何在产守卫**，等价断言只存在于各 spec 的黄金值里。而净销售是发布口径
   * 「支付 − 成功退款」（设计 line 428）：一旦「净销售 > GMV」或金额为负，
   * GMV、净销售、客单价、退款率一整组结论都不可信，而暂存存在性、关键列非空、漏斗对账
   * 可能同时全绿 —— 只有同归属口径的不变量能发现本类破坏。
   *
   * 口径声明（设计只给不变量；落点与空值规则在此声明并登记，见 V26 的 rationale 与
   * `docs/acceptance/s3-22-...` 下的差异登记）：
   *  - **作用域**：`ads_operation_overview__staging` 的**本次快照 + 本次 dt** 分区；
   *    该表按 `AdsSql.operationOverview` 的写法恒为**单行/分区**（两列由同一次聚合产出，
   *    故「同归属口径」成立）；
   *  - **判据**：`NOT (sale_amount >= net_sale_amount AND net_sale_amount >= 0)`；
   *    `checkCount` = 该分区行数，`errorCount` = 违反该式的行数；
   *  - **NULL 规则**：任一金额列为 NULL ⇒ **不通过**。理由：三值逻辑下 NULL 参与比较得 NULL，
   *    若按「跳过」处理，「金额列整体未计算」（空跑绿）会被静默放行，与本表既有口径
   *    （`keyPredicates` 对 `ads_sale_trend` 已要求两列非空）不一致；不可证明的不变量不得放行；
   *  - **只判不改**：本规则不修改/回填/置 0 任何数据，只产出结论（读侧不加启发式纠正）；
   *  - **不合并**：设计 §12.3 line 512 要求「付款 vs 订单、订单项公式、DWD/DWS 对账三者独立」，
   *    第 9 项「UV ≤ PV」同为大盘行不变量但**另立一码**（本轮不落地，留作后续子项），本函数不判它；
   *  - **不设阈值**：不变量逐行可判，无「宽松口径」问题，`threshold_json` 留 NULL。
   *
   * `detail`：通过时给出被检查行数与两列实际值（证明判据看到了真数据，而非空分区跑绿）；
   * 不通过时给出最多 6 行违反行的两列实际值（NULL 显示为 `NULL`）。
   */
  def gmvNetSaleInvariantCheck(spark: SparkSession, ns: WarehouseNamespace,
                               sid: String, dt: String): QualityCheck = {
    val staging = AdsSql.staging(ns, "ads_operation_overview")
    val where = s"snapshot_id = '$sid' AND dt = '$dt'"
    // 违反式显式把 NULL 写进谓词：`sale_amount >= net_sale_amount` 在 NULL 时求值为 NULL，
    // 若不显式判 NULL，空值行会**静默通过**（这正是本规则最容易写错的地方）
    val violation = "(sale_amount IS NULL OR net_sale_amount IS NULL " +
      "OR sale_amount < 0 OR net_sale_amount < 0 OR sale_amount < net_sale_amount)"

    val agg = spark.sql(
      s"SELECT COUNT(*) AS checked, " +
        s"SUM(CASE WHEN $violation THEN 1 ELSE 0 END) AS bad, " +
        s"MAX(sale_amount) AS max_sale, MAX(net_sale_amount) AS max_net " +
        s"FROM $staging WHERE $where").collect()(0)
    val checked = agg.getLong(0)
    val bad = Option(agg.get(1)).map(_.toString.toLong).getOrElse(0L)

    def dec(v: Any): String = Option(v).map(_.toString).getOrElse("NULL")

    if (bad == 0L) {
      QualityCheck("ADS_GMV_NET_SALE_INVARIANT", "ADS_STAGING", staging,
        checked, 0L, "sale_amount ≥ net_sale_amount ≥ 0（NULL 判不通过）", "BLOCKING", passed = true,
        s"$checked 行均满足 sale_amount ≥ net_sale_amount ≥ 0" +
          s"（sale_amount 最大=${dec(agg.get(2))}, net_sale_amount 最大=${dec(agg.get(3))}；" +
          s"本表按 dt 恒为单行）")
    } else {
      val rows = spark.sql(
        s"SELECT sale_amount, net_sale_amount FROM $staging WHERE $where AND $violation LIMIT 6").collect()
      val shown = rows.map(r => s"sale_amount=${dec(r.get(0))}/net_sale_amount=${dec(r.get(1))}").mkString(",")
      QualityCheck("ADS_GMV_NET_SALE_INVARIANT", "ADS_STAGING", staging,
        checked, bad, "sale_amount ≥ net_sale_amount ≥ 0（NULL 判不通过）", "BLOCKING", passed = false,
        s"违反同归属口径不变量 $bad/$checked 行（净销售 > GMV，或金额为负，或金额列为 NULL）: $shown" +
          (if (bad > 6L) "…" else ""))
    }
  }
}
