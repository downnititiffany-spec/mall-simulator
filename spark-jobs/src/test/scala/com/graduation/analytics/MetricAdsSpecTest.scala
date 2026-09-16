package com.graduation.analytics

import com.graduation.analytics.job.MetricExportJob
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * R7-3 回归：Hive 正式 ADS ↔ analytics_metric 宽表的映射清单必须与 Java 侧
 * `MetricAdsCatalog`（analytics_metric 建表/插入列白名单）**逐表逐列一致**。
 *
 * S3-30：Java 侧清单**不再在本类里硬编码镜像**。原先那种写法使本类成为列清单的**第二所有者**，
 * 实测（`s330_blindspot1.log`）：把 `MetricAdsCatalog.java` 的 `ads_operation_overview_m.cart_add_cnt`
 * 删掉后本类**仍 5/5 绿**（本类只拿 Scala 导出与自己的副本相比），恰好放过它本该拦住的
 * 「Java 侧少一列、Spark 照旧导出」事故。现改为**读取所有者源文件**（`MetricAdsCatalogSource.parse`）
 * 取期望值，本类只负责比较。
 *
 * 「两侧一起漂」的拦截责任仍在三方链上：`MetricAdsCatalogDdlConsistencyTest`（Java 白名单 ↔ 迁移 DDL）
 * ＋ `AdsSchemaOwnerSpec`（Spark 投影 ↔ DDL 所有者）⇒ 去掉副本后并无新的静默面（S3-30 登记 §3）。
 */
class MetricAdsSpecTest extends AnyFlatSpec with Matchers {

  private val javaCatalogRelative =
    "analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java"
  private val specSelfRelative =
    "spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala"

  private def readRepoFile(relative: String): String =
    new String(Files.readAllBytes(P2TestSupport.repoRoot.resolve(relative)), StandardCharsets.UTF_8)

  /** 期望列清单＝**唯一所有者**（Java 白名单）源文件的解析结果；本类不自带任何副本。 */
  private val javaCatalog: Seq[(String, Seq[String])] =
    MetricAdsCatalogSource.parse(readRepoFile(javaCatalogRelative))

  private val javaCatalogMap = javaCatalog.toMap

  private val ns = WarehouseNamespace.defaultNamespace

  "MetricAdsSpec" should "覆盖 8 张 ADS 且 Hive/MySQL 表名一一对应" in {
    MetricAdsSpec.TABLES.size should be(8)
    MetricAdsSpec.TABLES.map(_.mysqlTable) should be(javaCatalog.map(_._1))
    MetricAdsSpec.TABLES.map(_.hiveTable(ns)).foreach(_ should startWith(s"${ns.ads}.ads_"))
    MetricAdsSpec.byMysqlTable.size should be(8)
  }

  it should "P1-04：库名前缀可替换，表名映射不写死（换源时同一套规格复用）" in {
    val other = WarehouseNamespace.of("dw_b")
    val names = MetricAdsSpec.TABLES.map(_.hiveTable(other))
    names.foreach(_ should startWith(s"${other.ads}.ads_"))
    names.foreach(_ should not startWith "dw_ads.")
    // 表名本身（层内名）与 MySQL 映射不随库名变化
    MetricAdsSpec.TABLES.map(_.table) should be(MetricAdsSpec.TABLES.map(_.mysqlTable.dropRight(2)))
  }

  it should "列清单与 Java 侧 MetricAdsCatalog 完全一致（含顺序）" in {
    MetricAdsSpec.TABLES.foreach { t =>
      val expected = javaCatalogMap(t.mysqlTable)
      withClue(s"${t.mysqlTable}: ") {
        t.columns should be(expected)
      }
    }
  }

  it should "S3-30：期望列清单读取自唯一所有者文件（不是本类内的硬编码镜像）" in {
    // 解析面非空自检：解析器与所有者形态脱节时必须红，而不是让下面的逐表比对空转
    javaCatalog.size should be(8)
    withClue("解析结果必须逐表非空：") {
      javaCatalog.foreach { case (table, cols) => cols should not be empty }
    }
    val selfSource = readRepoFile(specSelfRelative)
    withClue("本类源文件必须真的来自所有者文件：") {
      selfSource should include("MetricAdsCatalogSource.parse")
      selfSource should include("MetricAdsCatalog.java")
    }
    // 反证（第二所有者不许复活）：本类源文件里一旦再出现「表名 → Seq(列名…)」形态的镜像即红。
    // 判定式取**行首条目**形态（真正的镜像是 `val ... = Seq(` 里一行一条映射；行内断言/合成样例
    // 里的同名片段不算）。实测教训见 S3-30 登记 §6.1：前两版判定式先后匹配到本用例自己的注释文本
    // 与合成样例 `Seq("ads_x_m" -> Seq("a", "b"))`，把自己判红两次。
    val mirrorPattern = """(?m)^\s*"ads_[a-z0-9_]+_m"\s*->\s*Seq\(""".r
    withClue("本类又出现了硬编码列清单镜像：") {
      mirrorPattern.findFirstIn(selfSource) should be(None)
    }
  }

  it should "S3-30：解析器有牙齿（所有者真加/减列时解析结果随之变化；形态脱节即抛错）" in {
    val synthetic = """public static final List<MetricAdsCatalog> ALL = List.of(
                     |        new MetricAdsCatalog("ads_x_m", List.of("a", "b"), List.of("a")),
                     |        new MetricAdsCatalog("ads_y_m", List.of("c"), List.of()));""".stripMargin
    MetricAdsCatalogSource.parse(synthetic) should be(Seq("ads_x_m" -> Seq("a", "b"), "ads_y_m" -> Seq("c")))
    val dropped = synthetic.replace("""List.of("a", "b")""", """List.of("a")""")
    MetricAdsCatalogSource.parse(dropped) should be(Seq("ads_x_m" -> Seq("a"), "ads_y_m" -> Seq("c")))
    // 找不到 ALL 清单时必须抛错并说明，不得静默返回空清单（否则守卫会变成空转）
    an[IllegalArgumentException] should be thrownBy MetricAdsCatalogSource.parse("class X {}")
  }

  it should "R7-0 口径列已贯通到导出口径（full_refund_rate 必须导出）" in {
    val overview = MetricAdsSpec.byMysqlTable("ads_operation_overview_m")
    overview.columns should contain("refund_rate")
    overview.columns should contain("full_refund_rate")
  }

  "MetricExportJob.posix" should "把 Windows 路径规范成正斜杠（清单是 JSON，反斜杠会变非法转义）" in {
    // 真实缺陷回归：run 21 的 _export.json 写了 "D:\Develop\..."，发布侧 Jackson 报
    // Unrecognized character escape 'D'（MP_MANIFEST_READ）导致整条发布失败。
    MetricExportJob.posix("""D:\Develop_code\GraduationProject\metric-staging\S20260901_21\ads_hot_product_m.jsonl""") should be(
      "D:/Develop_code/GraduationProject/metric-staging/S20260901_21/ads_hot_product_m.jsonl")
    MetricExportJob.posix("file:/D:/spark-warehouse/dw_ads.db/t/x=1") should be(
      "file:/D:/spark-warehouse/dw_ads.db/t/x=1")
    MetricExportJob.posix("/opt/platform/metric-staging/S1/a.jsonl") should be(
      "/opt/platform/metric-staging/S1/a.jsonl")
  }
}
