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
 * S3-07 **热门商品热度权重版本 + 三级次序键**（阶段 3「指标计算」：口径固定 + 制品可追溯）。
 *
 * 设计标尺（逐字）：
 *  - 设计 §11.2 L434「product_heat | 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数) |
 *    **版本化业务权重**，不称学习模型」；
 *  - 设计 §9.3 L320「ADS 下节 10 个逻辑专题，**每行带 snapshot / 定义版本 / 业务日期**，发布可追溯」；
 *  - 设计 §11.5 L455「商品排行按热度/销量/金额并有**稳定次序键**」；
 *  - 指导书 §7 阶段 3 ①「固定粒度、分子分母、时间窗口、金额/退款口径、空值规则和**版本**」。
 *
 * 现状事实（V3.0 起点，`docs/audit/v2-completeness-audit.md` L177/L178）：
 *  ① 「权重必须保存在规则版本中 | **未做** | `AdsSql` 字面常量；`ads_hot_product` 无 `rule_version` 列 |
 *     权重调整无版本可溯」；
 *  ② 「排行 `row_number(order by heat desc, buy desc, product_id asc)` | **部分** | 仅
 *     `ORDER BY heat DESC`…缺决胜键 → 并列热度排名不确定」（S2-06 已补 `product_id ASC`，
 *     仍缺中间一级 `buy DESC`；该三级形态的来源见 V2 指导书历史
 *     `docs/guidance/history/项目完整实施指导书 V2.0.md` L639）。
 *
 * 本节口径声明（设计只要求「版本化业务权重」，不指定版本号取值）：
 *  1. 版本语义 = **指标定义版本**，与指标字典 `docs/contracts/metric-dictionary.md:31`
 *     「权重来自业务设定，**存配置表**」所指的配置表 —— meta 库 `metric_definition`
 *     （`db/meta/V2__platform_pipeline_quality.sql:75` 的 `product_heat` 行，`definition_version = 'v1'`）
 *     —— **同一枚键**：ADS 行 `rule_version` 直接等于该表的 `definition_version`，
 *     与 `metric_value.definition_version`（发布侧 `MP_METRIC_DICT_VERSION` 校验的那一列）同一命名空间，
 *     类型 `STRING`，与 `ads_user_profile.rule_version`（`'rfm-v2'`）同型；
 *  2. 权重仍是那四个业务常数 `1/2/3/5`，公式仍是 `ln(1+x)` 加权和 ——
 *     本节**不改权重、不改公式、不引入学习模型**（§11.2 L434「不称学习模型」）；
 *  3. 热度公式从「同一 SQL 里写两遍」收敛为**一处文字属主**（子查询算 `heat_score`，窗口函数按
 *     `heat_score` 排），值不变（下方逐行复算断言），消除两处字面量将来各自漂移的风险；
 *  4. 次序键补齐为 `heat DESC, buy DESC, product_id ASC`：只影响**热度并列**时的名次分配，
 *     名次语义（1 = 最高）与名次取值集合不变；
 *  5. 列追加在**末尾**（ADS 插入按位置写，列序 = `MetricAdsSpec.columns` 顺序）。
 *
 * 判定方式（不读 SQL 文本下结论）：
 *  ① 真跑 DWS → `ads_hot_product`（暂存），断言每行 `rule_version = 'v1'` 且非空；
 *  ② `heat_score` 逐行等于**测试内独立复算**的 `1·ln(1+pv)+2·ln(1+fav)+3·ln(1+cart)+5·ln(1+buy)`
 *     （不引用生产 SQL 文本），确认「公式收敛成一处」没有顺带改口径；
 *  ③ 决胜键**行为判别力**：夹具里 101/102/105 三个商品热度**逐位相等**（复算断言逐位相等），
 *     其中 102 的 `buy` 更高 ⇒ 期望名次 `{103→1, 102→2, 101→3, 105→4, 104→5}`；
 *     只按 `product_id` 决胜的旧实现会给出 `{101→2, 102→3, …}`（RED）；
 *  ④ TopN=2 的**入选集合**同为 `{103, 102}`（旧实现会错选 101 ⇒ 榜单内容随口径而错）；
 *  ⑤ 以上两种物理落盘顺序（`product_id` 升序/降序）结果必须相同（可复现，§11.5 L455）；
 *  ⑥ 结构钉住：生成 SQL 里热度公式只有一个属主（`log1p(` 恰好 4 次）、三段次序键与版本列显式出现；
 *  ⑦ 正式/暂存 DDL 列与列序 = `MetricAdsSpec.columns`（插入按位置写，列序漂移即写错列）。
 *
 * 与 meta 指标字典种子的一致性由 `warehouse-pipeline` 的 `AdsHotProductHeatWeightDriftTest` 承担
 * （Scala 测试不便解析 meta 迁移文本，故该守卫解析两侧文本逐字对账版本与权重）。
 *
 * 只读夹具与隔离仓库由 `P2TestSupport.spark` 提供，绝不触碰在产 `spark-warehouse`。
 */
class AdsHotProductHeatRuleVersionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val Dt = "20260901"
  private val SnapAsc = "S_HEAT_ASC"
  private val SnapDesc = "S_HEAT_DESC"

  /** 指标字典 `product_heat.definition_version`（`db/meta/V2__platform_pipeline_quality.sql:75`）
    * —— 故意硬编码，字典发新版本而 ADS 不同步时本用例即红 */
  private val DictHeatRuleVersion = "v1"

  /** 冻结业务权重（§11.2 L434；与指标字典种子里的公式文本同源） */
  private val Weights = (1.0, 2.0, 3.0, 5.0)

  /**
   * 夹具：`(product_id, pv, fav, cart, buy)`。
   *
   * 判别力设计（见类注释 ③）：`101/102/105` 的热度必须**逐位相等**才能考出决胜键 ——
   *  101 = 1·ln(1+31) = ln32；102 = 5·ln(1+1) = 5·ln2；105 = 1·ln(1+31) = ln32；
   * `ln32` 与 `5·ln2` 在 IEEE double 下逐位相同（下方 `热度并列前提` 用例钉住这一前提，
   * 不成立的夹具会让决胜键用例静默失去判别力）。
   * 102 的 `buy` 更高 ⇒ 新实现给 102 名次 2；旧实现（只按 `product_id` 决胜）给 101。
   */
  private val Fixture: Seq[(Long, Long, Long, Long, Long)] = Seq(
    (103L, 31L, 0L, 0L, 1L), // 热度 ln32 + 5ln2（最高）
    (102L, 0L, 0L, 0L, 1L),  // 热度 5ln2，与 101/105 并列，buy 更高
    (101L, 31L, 0L, 0L, 0L), // 热度 ln32
    (105L, 31L, 0L, 0L, 0L), // 热度 ln32
    (104L, 0L, 0L, 0L, 0L))  // 热度 0（最低）

  private var spark: SparkSession = _
  private var ns: WarehouseNamespace = _

  override def beforeAll(): Unit = {
    spark = P2TestSupport.spark("ads-hot-product-heat-rule-version")
    ns = WarehouseNamespace.of("dw_heatver")
    LocalSchemaInitJob.statements(ns).foreach { case (_, stmt) => spark.sql(stmt) }

    writeProductBehavior(ascending = true)
    spark.sql(AdsSql.hotProduct(ns, Dt, 10, Some(SnapAsc)))

    writeProductBehavior(ascending = false)
    spark.sql(AdsSql.hotProduct(ns, Dt, 10, Some(SnapDesc)))
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ---------------------------------------------------------------- 夹具

  /** 同一份逻辑输入按 `product_id` 升序/降序落文件：逻辑内容相同、物理顺序相反 */
  private def writeProductBehavior(ascending: Boolean): Unit = {
    val order = if (ascending) "ASC" else "DESC"
    val row = (r: (Long, Long, Long, Long, Long)) =>
      s"""SELECT ${r._1}L AS product_id, 9L AS category_id, ${r._2}L AS pv, 5L AS uv,
         |       ${r._3}L AS fav, ${r._4}L AS cart, ${r._5}L AS buy""".stripMargin
    spark.sql(
      s"""INSERT OVERWRITE TABLE ${ns.dws}.dws_product_behavior_day PARTITION(dt = '$Dt')
         |SELECT product_id, category_id, pv, uv, fav, cart, buy FROM (
         |  ${Fixture.map(row).mkString(" UNION ALL ")}
         |) v ORDER BY product_id $order""".stripMargin)
  }

  // ---------------------------------------------------------------- 口径（独立复算，不引用生产 SQL）

  /** 测试侧独立复算的热度原值（double，与 `dws_product_behavior_day` 的 BIGINT 列同源） */
  private def heatOf(pv: Long, fav: Long, cart: Long, buy: Long): Double =
    Weights._1 * Math.log1p(pv) + Weights._2 * Math.log1p(fav) +
      Weights._3 * Math.log1p(cart) + Weights._4 * Math.log1p(buy)

  /** 期望落库文本：`heat_score` 是 `DECIMAL(18,4)` ⇒ 四舍五入到 4 位 */
  private def heatText(pv: Long, fav: Long, cart: Long, buy: Long): String =
    BigDecimal(heatOf(pv, fav, cart, buy)).setScale(4, BigDecimal.RoundingMode.HALF_UP).toString

  private def spec = MetricAdsSpec.byMysqlTable("ads_hot_product_m")

  private def rows(snapshot: String): Map[Long, Row] =
    spark.table(AdsSql.staging(ns, "ads_hot_product"))
      .where(s"snapshot_id = '$snapshot' AND dt = '$Dt'")
      .collect().map(r => r.getAs[Long]("product_id") -> r).toMap

  private def cell(row: Row, name: String): Any = {
    if (!row.schema.fieldNames.contains(name)) {
      fail(s"ads_hot_product 缺列 $name（现有列=${row.schema.fieldNames.mkString(",")}）")
    }
    row.getAs[Any](name)
  }

  // ---------------------------------------------------------------- 用例

  "夹具前提" should "101/102/105 三个商品的热度逐位相等（否则决胜键用例失去判别力）" in {
    val h101 = heatOf(31L, 0L, 0L, 0L)
    val h102 = heatOf(0L, 0L, 0L, 1L)
    withClue(s"热度必须逐位并列：101=${java.lang.Double.toHexString(h101)}($h101) " +
      s"102=${java.lang.Double.toHexString(h102)}($h102)；") {
      java.lang.Double.compare(h101, h102) should be(0)
    }
  }

  "ADS 热门商品列清单" should "末尾追加 rule_version（热度权重定义版本），且仍是 8 张 ADS 表" in {
    MetricAdsSpec.TABLES.size should be(8)
    spec.columns.last should be("rule_version")
    spec.columns should be(Seq("product_id", "product_name", "heat_score", "pv", "fav", "cart", "buy",
      "rank_no", "rule_version"))
  }

  "ADS 热门商品" should "每行带热度权重定义版本（§9.3 L320「每行带 snapshot / 定义版本 / 业务日期」）" in {
    Seq(SnapAsc, SnapDesc).foreach { snap =>
      val byProduct = rows(snap)
      byProduct.keySet should be(Fixture.map(_._1).toSet)
      byProduct.foreach { case (pid, row) =>
        withClue(s"$snap/$pid: ") {
          cell(row, "rule_version") should be(DictHeatRuleVersion)
          (cell(row, "rule_version") == null) should be(false)
        }
      }
    }
  }

  "ADS 热门商品热度分" should
    "逐行等于按冻结权重 1/2/3/5 独立复算的 log1p 加权和（§11.2 L434 版本化业务权重）" in {
    val byProduct = rows(SnapAsc)
    Fixture.foreach { case (pid, pv, fav, cart, buy) =>
      withClue(s"$pid: pv=$pv fav=$fav cart=$cart buy=$buy 期望=${heatText(pv, fav, cart, buy)}；") {
        cell(byProduct(pid), "heat_score").toString should be(heatText(pv, fav, cart, buy))
      }
    }
    // 分量列必须与夹具同源（防止"热度对但明细列串位"的假绿）
    Fixture.foreach { case (pid, pv, fav, cart, buy) =>
      val row = byProduct(pid)
      withClue(s"$pid: ") {
        cell(row, "pv") should be(pv)
        cell(row, "fav") should be(fav)
        cell(row, "cart") should be(cart)
        cell(row, "buy") should be(buy)
      }
    }
  }

  "ads_hot_product.rank_no（§11.5 L455 稳定次序键）" should
    "热度并列时先按 buy 降序、再按 product_id 升序，且名次与输入物理顺序无关" in {
    val expected = Map(103L -> 1, 102L -> 2, 101L -> 3, 105L -> 4, 104L -> 5)
    val asc = rows(SnapAsc).map { case (pid, row) => pid -> row.getAs[Int]("rank_no") }
    val desc = rows(SnapDesc).map { case (pid, row) => pid -> row.getAs[Int]("rank_no") }
    withClue(s"并列热度须按 buy 降序决胜：升序落盘=$asc 降序落盘=$desc；") {
      asc should be(expected)
      desc should be(asc)
    }
  }

  "ads_hot_product 的 TopN 截断（§11.5 排行稳定）" should
    "按同一决胜键取前 N（并列热度里 buy 更高者入选），不随扫描顺序漂移" in {
    val ascTopSnap = "S_HEAT_ASC2"
    val descTopSnap = "S_HEAT_DESC2"
    writeProductBehavior(ascending = true)
    spark.sql(AdsSql.hotProduct(ns, Dt, 2, Some(ascTopSnap)))
    writeProductBehavior(ascending = false)
    spark.sql(AdsSql.hotProduct(ns, Dt, 2, Some(descTopSnap)))

    val ascTop = rows(ascTopSnap).keySet
    val descTop = rows(descTopSnap).keySet
    withClue(s"TopN 入选集合必须稳定：升序落盘=$ascTop 降序落盘=$descTop；") {
      ascTop should be(Set(103L, 102L))
      descTop should be(ascTop)
    }
  }

  // ── 结构钉住：行为用例依赖"热度公式只有一处属主"与三段次序键，若未来编辑把公式复制回窗口函数
  //    或弱化决胜键，行为用例可能静默失去判别力 ⇒ 同时钉住生成 SQL 文本。
  "AdsSql 的热度权重与次序键" should
    "在生成 SQL 文本里热度公式只有一个文字属主，且三段次序键与版本列显式出现" in {
    val hot = AdsSql.hotProduct(ns, Dt, 10, Some("S_HEAT_PIN")).toLowerCase
    withClue("热度公式必须只有一处（log1p 恰好 4 次 = 1/2/3/5 四项）：") {
      "log1p\\(".r.findAllIn(hot).length should be(4)
    }
    withClue("rank_no 缺三段次序键（heat 降序 → buy 降序 → product_id 升序）：") {
      hot should include("order by heat_score desc, buy desc, product_id asc) as rank_no")
    }
    withClue("rule_version 缺版本列（值须等于指标字典 product_heat.definition_version）：") {
      hot should include(s"'$DictHeatRuleVersion' as rule_version")
    }
  }

  "ADS 热门商品表结构" should
    "正式/暂存 DDL 的列与列序都与 MetricAdsSpec 一致（插入按位置写，列序漂移即写错列）" in {
    val formalCols = spark.table(AdsSql.formal(ns, "ads_hot_product")).columns.toSeq
    withClue(s"正式表列=$formalCols；spec 列=${spec.columns}；") {
      formalCols should be(spec.columns :+ "dt")
    }

    val stagingCols = spark.table(AdsSql.staging(ns, "ads_hot_product")).columns.toSeq
    withClue(s"暂存表列=$stagingCols；spec 列=${spec.columns}；") {
      stagingCols.take(spec.columns.size) should be(spec.columns)
      stagingCols.drop(spec.columns.size).toSet should be(Set("snapshot_id", "dt"))
    }
  }
}
