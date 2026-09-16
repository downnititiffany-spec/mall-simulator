package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-05 **质量大盘带规则定义版本**（阶段 3「指标计算」：口径固定 + 制品可追溯）。
 *
 * 设计标尺（逐字）：
 *  - 设计 §9.3 L335「`ads_data_quality`/`ads_data_quality_m` | 历史已发布，**规则版本与实时结果待接齐**」；
 *  - 设计 §9.3 L320「ADS 下节 10 个逻辑专题，**每行带 snapshot / 定义版本 / 业务日期**，发布可追溯」；
 *  - 设计 §12.3 L512「每条规则记录**作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度**」；
 *  - 指导书 §7 阶段 3 ①「固定粒度、分子分母、时间窗口、金额/退款口径、空值规则和**版本**」。
 *
 * 现状事实（V3.0 起点，`docs/audit/v2-completeness-audit.md` §24.8-1）：`ads_data_quality_m` 只有
 * `rule_code/check_count/error_count/error_rate/passed/threshold` 六列 —— 有「实际值 + 阈值 + 判定」，
 * **没有定义版本**，因此大盘行无法回答「这一行是依据哪一版规则判的」。
 *
 * 本节口径声明（设计只要求声明、不指定具体版本号取值）：
 *  1. 版本语义 = **规则定义版本**，与 `quality_rule_definition`（`QualityRuleCatalog`，单一所有者）
 *     的 `version` 同义，类型 `INT`，与 meta 侧 `data_quality_result.rule_version`
 *     （`V20__data_quality_result_rule_version.sql`，同样是 `INT`）**同名列同类型**；
 *  2. 四条规则当前目录版本均为 1 ⇒ ADS 行 `rule_version = 1`；本 spec 把这个字面量**钉住**：
 *     目录一旦发新版本，本用例必红，迫使实现同步（漂移不许静默）；
 *  3. **不在 ADS 重复落作用域/阶段/严重度**：那三者的运行时真值由 meta 侧 `data_quality_result`
 *     记录（`layer`/`target_table`/`severity`/`effective_severity`/`rule_version`/`rule_fingerprint`），
 *     ADS 只补「定义版本」这一枚可追溯键，避免制造第二个严重度属主；
 *  4. 列追加在**末尾**（ADS 插入按位置写，列序 = `MetricAdsSpec.columns` 顺序）；
 *  5. 规则集合、阈值、"实际值"计算方式一律**不变**（本节不新增/不删除规则，不改阈值语义）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 DWD → `ads_data_quality`（暂存 + 正式），断言 4 行 `rule_version = 1` 且非空；
 *  ② **判别力**：`20260901` 四条规则各含 1 个错误 ⇒ `passed = 0`；`20260902` 与夹具全对齐 ⇒
 *     四条规则 `error_count = 0 / error_rate = 0 / passed = 1` ⇒ `passed` 是算出来的，不是常量；
 *  ③ 阈值列逐条比对（`0.01` / `0.001` / `0.0005` / `0`），确认加列没有顺带改口径；
 *  ④ 规则集合仍是那四条（不得因加列顺手加规则）；
 *  ⑤ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`（插入按位置写，列序漂移即写错列）；
 *  ⑥ 表数仍是 8 张（本节不新增 ADS 表）。
 *
 * 与 Java 侧的一致性由 `warehouse-pipeline` 的 `QualityRuleThresholdDriftTest` 承担：
 * Scala 测试无法读 Java 目录，故该守卫解析 `AdsSql.scala` 文本 + `QualityChecker.java` 文本 +
 * `QualityRuleCatalog` 定义，逐条钉住「规则码 / 版本 / 阈值」三方一致。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsQualityRuleVersionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 统计日 1：四条规则各含 1 个错误（`passed = 0`） */
  private val DtBad = "20260901"

  /** 统计日 2：与夹具完全对齐（四条规则 `passed = 1`） */
  private val DtOk = "20260902"

  private val SnapBad = "S_DQ_BAD"
  private val SnapOk = "S_DQ_OK"

  /** 目录版本（`QualityRuleCatalog` 四条规则 version 均为 1）—— 故意硬编码，漂移即红 */
  private val RuleVersion = 1

  private val Rules = Seq("AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "EVENT_ID_UNIQUE",
    "ENUM_WHITELIST")

  /** 预期阈值（字符串形态，与 `QualityRuleCatalog.thresholdJson` 的数值一致） */
  private val Thresholds = Map(
    "AMOUNT_RECONCILE" -> "0.01",
    "REQUIRED_FIELD_NULL_RATE" -> "0.001",
    "EVENT_ID_UNIQUE" -> "0.0005",
    "ENUM_WHITELIST" -> "0")

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-quality-rule-version")
    ns = WarehouseNamespace.of("dw_dqver")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }

    // 20260901：行为 4 行（1 行 product_id 空 + 1 行非法枚举）、拒绝 1 行重复事件、订单 2 单（1 单金额不符）
    writeBehavior(DtBad, Seq((1L, Some(10L), "view"), (2L, Some(11L), "view"),
      (3L, None, "view"), (4L, Some(12L), "bogus")))
    writeReject(DtBad, 1)
    writeOrders(DtBad, Seq((301L, 1L, 10.00, 10.00), (302L, 2L, 10.00, 9.50)))

    // 20260902：行为 2 行全部合法、无拒绝、订单 1 单金额相符
    writeBehavior(DtOk, Seq((5L, Some(13L), "view"), (6L, Some(14L), "cart_add")))
    writeOrders(DtOk, Seq((401L, 5L, 10.00, 10.00)))

    spark.sql(AdsSql.dataQuality(ns, DtBad, Some(SnapBad)))
    spark.sql(AdsSql.dataQuality(ns, DtBad))
    spark.sql(AdsSql.dataQuality(ns, DtOk, Some(SnapOk)))
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** 行为明细：`dwd_user_behavior_detail`（列清单与 `LocalSchemaInitJob` 一致；`None` ⇒ `product_id` 为 NULL） */
  private def writeBehavior(dt: String, rows: Seq[(Long, Option[Long], String)]): Unit = {
    if (rows.isEmpty) return
    var behaviorId = 0L
    val row = (r: (Long, Option[Long], String)) => {
      behaviorId += 1
      s"""SELECT ${behaviorId}L AS behavior_id, ${r._1}L AS user_id,
         |       ${r._2.map(v => s"${v}L").getOrElse("CAST(NULL AS BIGINT)")} AS product_id,
         |       CAST(NULL AS BIGINT) AS category_id, '${r._3}' AS behavior_type,
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

  /** 拒绝记录：`dwd_reject_record`（`reject_reason = 'DUPLICATE_EVENT'` ⇒ EVENT_ID_UNIQUE 的 error_count） */
  private def writeReject(dt: String, duplicateCount: Int): Unit = {
    if (duplicateCount <= 0) return
    val rows = (1 to duplicateCount).map { i =>
      s"""SELECT 'r$i' AS reject_id, 'behavior' AS source_table, 'DUPLICATE_EVENT' AS reject_reason,
         |       '{}' AS raw_payload, CAST(NULL AS TIMESTAMP) AS reject_time""".stripMargin
    }
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_reject_record PARTITION(dt = '$dt')
         |SELECT reject_id, source_table, reject_reason, raw_payload, reject_time FROM (
         |  ${rows.mkString(" UNION ALL ")}
         |) v""".stripMargin)
  }

  /** 订单明细：`dwd_order_detail`（`final_paid_flag = 1` 才进 AMOUNT_RECONCILE 对账） */
  private def writeOrders(dt: String, rows: Seq[(Long, Long, BigDecimal, BigDecimal)]): Unit = {
    if (rows.isEmpty) return
    val row = (r: (Long, Long, BigDecimal, BigDecimal)) =>
      s"""SELECT ${r._1}L AS order_id, ${r._2}L AS user_id, 1L AS product_id, 1L AS category_id,
         |       1 AS quantity, CAST(10.00 AS DECIMAL(18,2)) AS unit_price,
         |       CAST(0.00 AS DECIMAL(18,2)) AS discount, CAST(10.00 AS DECIMAL(18,2)) AS amount,
         |       'PAID' AS order_status, CAST(NULL AS TIMESTAMP) AS order_time, '$dt' AS order_date,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS TIMESTAMP) AS paid_at,
         |       CAST(${r._3} AS DECIMAL(18,2)) AS order_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS paid_amount,
         |       CAST(0.00 AS DECIMAL(18,2)) AS refund_amount,
         |       CAST(${r._4} AS DECIMAL(18,2)) AS net_paid_amount,
         |       1 AS final_paid_flag, 0 AS final_refunded_flag,
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

  // ---------------------------------------------------------------- 断言

  private def spec = MetricAdsSpec.byMysqlTable("ads_data_quality_m")

  private def rows(snapshot: String): Map[String, Row] = {
    val all = spark.table(AdsSql.staging(ns, "ads_data_quality"))
      .where(s"snapshot_id = '$snapshot'").collect()
    all.map(r => r.getAs[String]("rule_code") -> r).toMap
  }

  private def cell(row: Row, name: String): Any = {
    if (!row.schema.fieldNames.contains(name)) {
      fail(s"ads_data_quality 缺列 $name（现有列=${row.schema.fieldNames.mkString(",")}）")
    }
    row.getAs[Any](name)
  }

  "ADS 质量大盘列清单" should "末尾追加 rule_version（定义版本），且仍是 8 张 ADS 表" in {
    MetricAdsSpec.TABLES.size should be(8)
    spec.columns.last should be("rule_version")
    spec.columns should be(Seq("rule_code", "check_count", "error_count", "error_rate", "passed",
      "threshold", "rule_version"))
  }

  "ADS 质量大盘" should "四条规则逐行带规则定义版本（设计 §9.3 L335「规则版本…待接齐」）" in {
    val bad = rows(SnapBad)
    bad.keySet should be(Rules.toSet)
    bad.foreach { case (code, row) =>
      withClue(s"$code: ") {
        cell(row, "rule_version") should be(RuleVersion)
        (cell(row, "rule_version") == null) should be(false)
      }
    }
    val ok = rows(SnapOk)
    ok.keySet should be(Rules.toSet)
    ok.foreach { case (code, row) =>
      withClue(s"$code: ") { cell(row, "rule_version") should be(RuleVersion) }
    }
  }

  "ADS 质量大盘（含 1 个错误的日）" should "实际值/阈值/判定与既有口径逐条一致（加列不改口径）" in {
    val bad = rows(SnapBad)
    val expected = Seq(
      ("AMOUNT_RECONCILE", 2L, 1L, "0.500000", 0),
      ("REQUIRED_FIELD_NULL_RATE", 4L, 1L, "0.250000", 0),
      ("EVENT_ID_UNIQUE", 4L, 1L, "0.250000", 0),
      ("ENUM_WHITELIST", 4L, 1L, "0.250000", 0))
    expected.foreach { case (code, checks, errors, rate, passed) =>
      val row = bad(code)
      withClue(s"$code: ") {
        cell(row, "check_count") should be(checks)
        cell(row, "error_count") should be(errors)
        cell(row, "error_rate").toString should be(rate)
        cell(row, "passed") should be(passed)
        cell(row, "threshold") should be(Thresholds(code))
      }
    }
  }

  "ADS 质量大盘（全对齐的日）" should "四条规则 error_count=0 / passed=1（passed 是算出来的，不是常量）" in {
    val ok = rows(SnapOk)
    ok.size should be(4)
    ok.foreach { case (code, row) =>
      withClue(s"$code: ") {
        cell(row, "check_count") should be(if (code == "AMOUNT_RECONCILE") 1L else 2L)
        cell(row, "error_count") should be(0L)
        cell(row, "error_rate").toString should be("0.000000")
        cell(row, "passed") should be(1)
        cell(row, "threshold") should be(Thresholds(code))
      }
    }
  }

  "ADS 质量大盘表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
    val formalCols = spark.table(AdsSql.formal(ns, "ads_data_quality")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(AdsSql.staging(ns, "ads_data_quality")).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }
}
