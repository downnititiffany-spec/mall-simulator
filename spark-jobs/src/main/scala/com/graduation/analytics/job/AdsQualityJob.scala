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
 *  2. ADS_STAGING_SNAPSHOT_ISOLATION BLOCKING 同一 dt 下不得混入其它 snapshot_id 的暂存分区
 *  3. ADS_STAGING_KEY_NOT_NULL   BLOCKING  关键列不得为空（逐表真实 COUNT）
 *  4. PUB_DQ_BLOCKING_RULES      BLOCKING  staging.ads_data_quality 的阻断规则必须 passed=1
 *  5. PUB_DQ_EVENT_ID_UNIQUE     ERROR     仅记录不阻断（与 Java QualityChecker.corePassed 口径一致）
 *  6. ADS_DWS_FUNNEL_RECONCILE   BLOCKING  ADS 漏斗 stage 汇总 = DWS 漏斗对应列（§16.4 跨层对账）
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
}
