package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * S3-01 ADS 用户画像补齐 **R/F/M 原值与观察窗口**（阶段 3「指标计算」第 1/4 项）：
 *  - 设计 §11.4 L447「R低更好，F/M高更好；五分位评分… 必须展示评分规则；
 *    **记录 R/F/M 原值、score、segment、窗口、rule_version，不仅存标签**」；
 *  - 设计 §9.3 L334「`ads_user_profile` / `ads_user_profile_m`：历史已发布，**RFM 完整性需补**」；
 *  - 指导书 §7 阶段 3 ①「对每个指标固定粒度、分子分母、**时间窗口**…和版本」。
 *
 * 改动前该表只有分档（r/f/m 五分位）与标签（value_group/active_level/lifecycle_state），
 * 原值与窗口都丢失：`r=5` 到底是「昨天买过」还是「窗口内最早一天买过」无从判断，
 * 也无法回答「这批用户的观察期是哪个区间」——即「只存标签」。
 *
 * 本 spec 判定**不是读 SQL 文本**，而是把已知原值的夹具写进上游 DWS/DWD 后跑真链路，逐项断言 ADS 行：
 *  ① 原值/窗口等于上游输入；② 原值与记录的窗口自洽（`r_days = DATEDIFF(period_end, last_buy_date)`）；
 *  ③ 原值与上游 `dws_user_trade_period` 同行一致（**血缘**，不是照着期望值重算的常量）；
 *  ④ `r` 分档确实由 `r_days` 决定（R 原值越小分越高）——夹具刻意让 recency 次序与 user_id 次序**相反**，
 *     这样「分档退化成 user_id 次序」与「分档真按 recency」会给出不同结果（S3-01 实测到的缺陷，见下）；
 *  ⑤ 正式/暂存 DDL 的列与列序与 `MetricAdsSpec` 完全一致（插入按位置写，列序漂移即写错列）。
 *
 * **S3-01 实测到的既有缺陷（本 spec 的 ③④ 是它的判别探针）**：`AdsSql.userProfile` 原来用
 * `regexp_replace('20260901', '(\d{4})(\d{2})(\d{2})', '$1-$2-$3')` 把紧凑窗口转 ISO。
 * 实测（Spark 3.5.1）：SQL 字符串字面量会**吃掉**未识别的反斜杠转义 —— `'(\d{4})'` 被解析成 `(d{4})`，
 * 于是 `regexp_replace` 原样返回 `20260901`；`DATEDIFF('20260901','2026-09-01')` = NULL。
 * 后果：`r_ntile` 的排序键恒为 NULL（全平局 ⇒ 退化成 user_id 次序）、`active_level` 恒「低」、
 * `lifecycle_state` 恒「活跃」且「新用户」永不成立。现改为无反斜杠的 `substr/concat`。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsRfmRawValueSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  /** 统计日（分区 dt，yyyyMMdd） */
  private val Dt = "20260901"

  /** 观察窗口覆盖 8 月整月：窗口边界与统计日不同，才能分辨「记的是统计日还是真窗口」 */
  private val PeriodStart = "20260801"
  private val PeriodEnd = "20260901"

  private val Snap = "S_RFMRAW"

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-rfm-raw-value")
    ns = WarehouseNamespace.of("dw_rfmraw")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** 紧凑 yyyyMMdd → ISO yyyy-MM-dd（期望值在 **Scala 侧**算，避免与被测 SQL 共用同一种写法） */
  private def iso(compact: String): String =
    s"${compact.take(4)}-${compact.slice(4, 6)}-${compact.slice(6, 8)}"

  /**
   * 5 个用户，R/F/M 原值互不相同，且**recency 次序与 user_id 次序相反**（判别力关键）；
   * 同时覆盖 `lifecycle_state` 四个分支与 `active_level` 三个分支：
   *
   * | user | last_buy_date | r_days | orders | amount | lifecycle            | 行为日 → active |
   * |------|---------------|--------|--------|--------|----------------------|-----------------|
   * | 1    | 2026-06-01    | 92     | 2      | 20.00  | 流失风险(>60)        | 08-25 → 高(7)   |
   * | 2    | 2026-07-20    | 43     | 4      | 40.00  | 沉默(30<r<=60)       | 08-10 → 中(22)  |
   * | 3    | 2026-08-20    | 12     | 6      | 60.00  | 活跃                 | 无行 → 低       |
   * | 4    | 2026-08-01    | 31     | 1      | 10.00  | 新用户(首购且仅 1 单) | 09-01 → 高(0)   |
   * | 5    | 2026-09-01    | 0      | 8      | 80.00  | 活跃                 | 07-01 → 低(62)  |
   *
   * 「新用户」判据是 `order_count = 1 AND last_buy_date = period_start`（观察期首购）——
   * user 4 的 `last_buy_date` 正是窗口起 `2026-08-01`。
   */
  private def writeUserTradePeriod(): Unit = {
    // valid_order_count = order_count：本夹具不建模退款（S3-03 加列后按位置写入需补齐，语义=无全退）
    val row = (u: Long, lastBuy: String, orders: Long, amount: String) =>
      s"""SELECT ${u}L AS user_id, '$lastBuy' AS last_buy_date, ${orders}L AS order_count,
         |       CAST($amount AS DECIMAL(18,2)) AS sale_amount,
         |       '$PeriodStart' AS period_start, '$PeriodEnd' AS period_end,
         |       ${orders}L AS valid_order_count""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_user_trade_period PARTITION(dt = '$Dt')
         |SELECT user_id, last_buy_date, order_count, sale_amount, period_start, period_end,
         |       valid_order_count FROM (
         |  ${row(1L, "2026-06-01", 2L, "20.00")}
         |  UNION ALL ${row(2L, "2026-07-20", 4L, "40.00")}
         |  UNION ALL ${row(3L, "2026-08-20", 6L, "60.00")}
         |  UNION ALL ${row(4L, iso(PeriodStart), 1L, "10.00")}
         |  UNION ALL ${row(5L, "2026-09-01", 8L, "80.00")}
         |) v""".stripMargin)
  }

  /**
   * 行为明细（`active_level`/`favorite_category` 的输入）：窗口末日 2026-09-01 之下
   * 7 天 → 高、22 天 → 中、无行为 → 低、62 天 → 低；user 3 无行 ⇒ 检验 COALESCE 兜底。
   */
  private def writeUserBehavior(): Unit = {
    val row = (u: Long, cat: Long, day: String) =>
      s"""SELECT CAST(NULL AS STRING) AS behavior_id, ${u}L AS user_id,
         |       CAST(NULL AS BIGINT) AS product_id, ${cat}L AS category_id,
         |       CAST(NULL AS STRING) AS behavior_type, CAST(NULL AS TIMESTAMP) AS event_time,
         |       '$day' AS event_date, CAST(NULL AS INT) AS event_hour,
         |       CAST(NULL AS STRING) AS city_level, CAST(NULL AS STRING) AS channel,
         |       CAST(NULL AS STRING) AS session_id, CAST(NULL AS BIGINT) AS source_batch_id,
         |       CAST(NULL AS BIGINT) AS user_key, CAST(NULL AS BIGINT) AS product_key,
         |       CAST(NULL AS BIGINT) AS category_key""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '$Dt')
         |SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time,
         |       event_date, event_hour, city_level, channel, session_id, source_batch_id,
         |       user_key, product_key, category_key FROM (
         |  ${row(1L, 11L, "2026-08-25")}
         |  UNION ALL ${row(2L, 21L, "2026-08-10")}
         |  UNION ALL ${row(4L, 31L, "2026-09-01")}
         |  UNION ALL ${row(5L, 41L, "2026-07-01")}
         |) v""".stripMargin)
  }

  private def staging = AdsSql.staging(ns, "ads_user_profile")

  /** 每列逐列转字符串读（按列名取，**不按序号**；`<NULL>` 显式可见 —— NULL 在 SQL 比较里会静默通过） */
  private val Watched = Seq(
    "r", "f", "m", "value_group", "active_level", "favorite_category", "lifecycle_state",
    "rule_version", "last_buy_date", "calc_date",
    "r_days", "f_count", "m_amount", "period_start", "period_end")

  private def rows(): Map[Long, Map[String, String]] =
    spark.sql(
      s"""SELECT user_id, ${Watched.mkString(", ")}
         |FROM $staging WHERE snapshot_id = '$Snap' AND dt = '$Dt'""".stripMargin)
      .collect()
      .map { r =>
        val vals = Watched.map { c =>
          c -> Option(r.getAs[Any](c)).map(_.toString).getOrElse("<NULL>")
        }.toMap
        r.getAs[Any]("user_id").toString.toLong -> vals
      }
      .toMap

  private def spec = MetricAdsSpec.TABLES
    .find(_.mysqlTable == "ads_user_profile_m")
    .getOrElse(fail("MetricAdsSpec 未登记 ads_user_profile_m"))

  private def run(): Unit = {
    writeUserTradePeriod()
    writeUserBehavior()
    spark.sql(AdsSql.userProfile(ns, Dt, PeriodStart, PeriodEnd, Some(Snap)))
  }

  // ---------------------------------------------------------------- 用例

  "ads_user_profile 的 R/F/M 原值与观察窗口（§11.4 L447）" should
    "随分档一并落库，且分档与标签确实由原值/窗口算出" in {
    run()

    val actual = rows()
    val win = Map("period_start" -> iso(PeriodStart), "period_end" -> iso(PeriodEnd))
    def row(r: String, f: String, m: String, group: String, active: String, fav: String,
            life: String, days: String, cnt: String, amt: String, lastBuy: String) =
      Map("r" -> r, "f" -> f, "m" -> m, "value_group" -> group, "active_level" -> active,
        "favorite_category" -> fav, "lifecycle_state" -> life, "rule_version" -> "rfm-v2",
        "last_buy_date" -> lastBuy, "calc_date" -> Dt,
        "r_days" -> days, "f_count" -> cnt, "m_amount" -> amt) ++ win

    withClue(s"ADS 落库行=${actual.mkString(";")}；") {
      // value_group 按 §11.4 规则由 (r_ntile, f_ntile, m_ntile) 推出（r_ntile = 6 - r）：
      //   u1 (5,2,2) m<4 且 r>2 且 f<4 → 一般挽留   u4 (3,1,1) 同上 → 一般挽留
      //   u2 (4,3,3) 同上 → 一般挽留              u3 (2,4,4) m>=4 且 r<=2 且 f>=4 → 重要价值
      //   u5 (1,5,5) 同上 → 重要价值
      actual should be(Map(
        // r_days 92 ⇒ 最久未购 ⇒ R 分最低；7 天内活跃 ⇒ 高；>60 天未购 ⇒ 流失风险
        1L -> row("1", "2", "2", "一般挽留", "高", "11", "流失风险", "92", "2", "20.00", "2026-06-01"),
        // 43 天未购 ⇒ 沉默；22 天前活跃 ⇒ 中
        2L -> row("2", "3", "3", "一般挽留", "中", "21", "沉默", "43", "4", "40.00", "2026-07-20"),
        // 无行为行 ⇒ favorite_category 兜底 -1、active_level 低
        3L -> row("4", "4", "4", "重要价值", "低", "-1", "活跃", "12", "6", "60.00", "2026-08-20"),
        // 首购且仅 1 单且末次购买 = 窗口起 ⇒ 新用户（修正前该分支永不成立）
        4L -> row("3", "1", "1", "一般挽留", "高", "31", "新用户", "31", "1", "10.00", "2026-08-01"),
        5L -> row("5", "5", "5", "重要价值", "低", "41", "活跃", "0", "8", "80.00", "2026-09-01")))
    }
  }

  "原值列与记录的窗口" should
    "自洽：r_days = DATEDIFF(period_end, last_buy_date)，窗口 = 本次评分实际使用的窗口（NULL 也判失败）" in {
    run()

    // 用 SQL 现算、期望值在 Scala 侧拼（不与被测 SQL 共用写法）。显式查 NULL：
    // `NULL <> x` 求值为 NULL、不计入 WHERE，只写 <> 会让空值静默通过（S3-01 实测踩到）。
    val inconsistent = spark.sql(
      s"""SELECT COUNT(*) FROM $staging
         |WHERE snapshot_id = '$Snap' AND dt = '$Dt'
         |  AND (r_days IS NULL OR f_count IS NULL OR m_amount IS NULL
         |       OR period_start IS NULL OR period_end IS NULL OR last_buy_date IS NULL
         |       OR r_days <> DATEDIFF(period_end, last_buy_date)
         |       OR period_start <> '${iso(PeriodStart)}'
         |       OR period_end <> '${iso(PeriodEnd)}')""".stripMargin)
      .head().getLong(0)

    withClue(s"记录窗口/原值不自洽的行数=$inconsistent；") { inconsistent should be(0L) }
  }

  "R 分档" should
    "由 R 原值决定（r_days 越小 r 越高），而不是退化成 user_id 次序" in {
    run()

    val byR = rows().toSeq.sortBy { case (_, v) => -v("r").toInt }
    val days = byR.map { case (_, v) => v("r_days").toInt }
    withClue(s"按 r 降序的 r_days=$days（应升序）；") { days should be(days.sorted) }
    // 夹具的 recency 次序是 user 5,3,4,2,1；若 R 分档退化成 user_id 次序，u1 会拿到 r=5 而不是 1
    byR.map(_._1).take(2).toSet should be(Set(3L, 5L))
    days.head should be(0)
  }

  "原值列" should
    "与上游 dws_user_trade_period 同行一致（血缘一致，而非另算一套口径）" in {
    run()

    val drifted = spark.sql(
      s"""SELECT COUNT(*) FROM $staging s
         |JOIN ${ns.dws}.dws_user_trade_period d ON d.user_id = s.user_id AND d.dt = s.dt
         |WHERE s.snapshot_id = '$Snap'
         |  AND (s.f_count IS NULL OR s.m_amount IS NULL OR s.r_days IS NULL
         |       OR s.f_count <> d.order_count
         |       OR s.m_amount <> d.sale_amount
         |       OR s.last_buy_date <> d.last_buy_date
         |       OR s.r_days <> DATEDIFF(s.period_end, d.last_buy_date)
         |       OR s.period_start <> concat(substr(d.period_start, 1, 4), '-',
         |                                   substr(d.period_start, 5, 2), '-',
         |                                   substr(d.period_start, 7, 2))
         |       OR s.period_end <> concat(substr(d.period_end, 1, 4), '-',
         |                                 substr(d.period_end, 5, 2), '-',
         |                                 substr(d.period_end, 7, 2)))""".stripMargin)
      .head().getLong(0)

    withClue(s"与上游 DWS 不一致的行数=$drifted；") { drifted should be(0L) }
  }

  "ADS 画像表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
    val formalCols = spark.table(AdsSql.formal(ns, "ads_user_profile")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(staging).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }

  // ── 结构钉住：行为用例的判别力来自 SQL 真的选了这几列；若未来编辑移除，
  //    行为用例可能因列缺失直接解析失败（好）或改由别处兜底（坏）⇒ 同时钉住 SQL 文本。
  "AdsSql.userProfile" should
    "在生成 SQL 文本中显式选择 R/F/M 原值与窗口，且窗口转换不含易被解析器吃掉的转义" in {
    val sql = AdsSql.userProfile(ns, Dt, PeriodStart, PeriodEnd, Some("S_PIN")).toLowerCase
    Seq("as r_days", "as f_count", "as m_amount", "as period_start", "as period_end").foreach { expr =>
      withClue(s"生成 SQL 缺少原值/窗口投影 $expr：") { sql should include(expr) }
    }
    // S3-01 实测：`'(\\d{4})'` 这种字面量在 Scala 侧变成 `'(\d{4})'`，Spark SQL 解析器把 `\d` 吃成 `d`
    // ⇒ 正则不匹配、窗口原样返回、DATEDIFF 恒 NULL。这里钉住「不再出现反斜杠转义」。
    withClue(s"生成 SQL 又出现了反斜杠转义（解析器会吃掉，见本文件头 KDOC）：") {
      sql should not include "\\d"
    }
    // 口径实现修正后新数据一律 rfm-v2：与修正前落库的 rfm-v1 数据不可混算（§11.4 要求记录版本）
    sql should include("'rfm-v2' as rule_version")
  }
}
