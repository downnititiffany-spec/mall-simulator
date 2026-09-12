package com.graduation.analytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.graduation.analytics.job.{JobArgs, LocalSchemaInitJob, TradeDwdJob}
import com.graduation.analytics.metric.MetricAdsSpec
import com.graduation.analytics.sql.{AdsSql, DimSql, DwdSql, DwsSql, OdsLoadSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

/**
 * 数仓库名规格对账测试（P1-04，Scala 侧；规格 `parity.scalaTest` 点名的就是本类）。
 *
 * 权威是机器可读规格 `contract-specs/specs/warehouse-namespace.v1.json`：
 *  - 本侧常量（缺省前缀/分隔符/白名单/层顺序/保留字/错误码/检查顺序）必须与 `rule` 段逐字一致；
 *  - 规格里每个向量都跑一遍（Java 侧 `WarehouseNamespaceContractTest` 读同一份文件）；
 *  - 再加一层 Scala 专属断言：**所有 SQL 模板与 INIT_SCHEMA 的库名都随命名空间走**，
 *    换前缀（`dw_b`）后 SQL 里不得残留任何 `dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads`。
 *
 * 纪律：期望值只来自规格文件与「前缀_层」派生式，本测试不另造一套口径。
 */
class WarehouseNamespaceSpec extends AnyFlatSpec with Matchers {

  import WarehouseNamespaceSpec._

  private lazy val spec: JsonNode = readSpec()
  private lazy val rule: JsonNode = spec.get("rule")

  // ── 规格常量对账 ─────────────────────────────────────────────────────────

  "WarehouseNamespace" should "常量与规格 rule 段逐字一致（含层顺序与检查顺序）" in {
    rule.get("defaultPrefix").asText() should be(WarehouseNamespace.DefaultPrefix)
    rule.get("separator").asText() should be(WarehouseNamespace.Separator)
    rule.get("prefixPattern").asText() should be(WarehouseNamespace.PrefixPattern)
    textSeq(rule.get("layerSuffixes")) should be(WarehouseNamespace.LayerSuffixes)
    textSeq(rule.get("checkOrder")) should be(Seq(
      WarehouseNamespace.ErrPattern,
      WarehouseNamespace.ErrUnderscore,
      WarehouseNamespace.ErrReserved,
      WarehouseNamespace.ErrLayerSuffix))
    textSeq(rule.get("reserved")).toSet should be(WarehouseNamespace.Reserved)
    rule.get("blankIsDefault").asBoolean() should be(true)
    rule.get("errorCodes").size() should be(4)

    // 规格登记的两侧测试类名必须就是本类与 Java 侧那个：改名即失联，必须一起改
    spec.at("/parity/scalaTest").asText() should be(getClass.getName)
    spec.at("/parity/javaTest").asText() should be(
      "com.graduation.analytics.warehouse.WarehouseNamespaceContractTest")
  }

  // ── 逐向量对账 ───────────────────────────────────────────────────────────

  it should "规格 vectors 逐向量对账（Scala 侧）" in {
    val vectors = spec.get("vectors")
    vectors.isArray should be(true)
    vectors.size() should be >= 15

    var checked = 0
    val it = vectors.elements()
    while (it.hasNext) {
      val v = it.next()
      val input = if (v.get("input").isNull) null else v.get("input").asText()
      val expect = v.get("expect").asText()
      val why = if (v.hasNonNull("why")) v.get("why").asText() else ""
      withClue(s"向量 input=${Option(input).map("\"" + _ + "\"").getOrElse("null")}（$why）: ") {
        classify(input) should be(expect)
        if (expect == Default || expect == Ok) {
          val ns = WarehouseNamespace.of(input)
          val prefix = if (expect == Default) WarehouseNamespace.DefaultPrefix else input
          val derived = WarehouseNamespace.LayerSuffixes
            .map(l => l -> s"$prefix${WarehouseNamespace.Separator}$l").toMap
          ns.layers.toMap should be(derived)
          ns.layers.keys.toSeq should be(WarehouseNamespace.LayerSuffixes) // 顺序即规格顺序
          ns.prefix should be(prefix)
          ns.table("ads", "ads_operation_overview") should be(derived("ads") + ".ads_operation_overview")
          if (v.has("names")) jsonMap(v.get("names")) should be(derived)
        } else {
          WarehouseNamespace.validate(input) should be(Some(expect))
          val ex = the[IllegalArgumentException] thrownBy WarehouseNamespace.of(input)
          ex.getMessage should include(expect)
          ex.getMessage should include(String.valueOf(input))
        }
      }
      checked += 1
    }
    checked should be(vectors.size())
  }

  // ── 行为护栏 ─────────────────────────────────────────────────────────────

  it should "行为护栏：null/空串缺省、不 trim、未知层与非法表名直接失败" in {
    WarehouseNamespace.of(null) should be(WarehouseNamespace.defaultNamespace)
    WarehouseNamespace.of("") should be(WarehouseNamespace.defaultNamespace)
    WarehouseNamespace.validate(null) should be(None)
    WarehouseNamespace.validate("") should be(None)
    WarehouseNamespace.validate(" ") should be(Some(WarehouseNamespace.ErrPattern)) // 空白串不是缺省

    val ns = WarehouseNamespace.defaultNamespace
    a[IllegalArgumentException] should be thrownBy ns.layerDb("ODS")
    a[IllegalArgumentException] should be thrownBy ns.table("ods", "a.b")
    a[IllegalArgumentException] should be thrownBy ns.table("ods", "")
    ns.table("ods", "ods_user_event") should be("dw_ods.ods_user_event")
    ns should be(WarehouseNamespace.of("dw"))
    ns should not be WarehouseNamespace.of("a")
  }

  it should "fromArgs 读 --hiveDatabasePrefix，缺省走 dw，非法前缀在作业内也照样失败" in {
    def jobArgs(extra: String*): JobArgs =
      JobArgs.parse((Seq("--runtimeProfileId=1", "--jobCode=odl",
        "--businessDate=20260901", "--attemptNo=1") ++ extra).toArray).right.get

    WarehouseNamespace.fromArgs(jobArgs()).prefix should be(WarehouseNamespace.DefaultPrefix)
    WarehouseNamespace.fromArgs(jobArgs("--hiveDatabasePrefix=dw_c")).ads should be("dw_c_ads")
    an[IllegalArgumentException] should be thrownBy WarehouseNamespace.fromArgs(
      jobArgs("--hiveDatabasePrefix=DW"))
  }

  // ── Scala 专属：模板/DDL 真的随命名空间走 ────────────────────────────────

  it should "所有 SQL 模板与 INIT_SCHEMA 随命名空间走（换前缀后不残留 dw_* 库名）" in {
    Producers.all.size should be >= 25

    Producers.all.foreach { case (label, produce) =>
      withClue(s"$label: ") {
        val sql = produce(WarehouseNamespace.defaultNamespace)
        sql should include("dw_")
        // 缺省命名空间必须产出**完整**的源 A 既有库名（不是只含 "dw_" 前缀的巧合）
        FrozenDefaultNames.exists(sql.contains) should be(true)
      }
    }

    // 换源：同一批模板在 dw_b 下不得出现任何 dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads
    Seq("dw_b", "a").foreach { prefix =>
      val ns = WarehouseNamespace.of(prefix)
      Producers.all.foreach { case (label, produce) =>
        withClue(s"$label @ $prefix: ") {
          val sql = produce(ns)
          sql should include(s"${prefix}_")
          FrozenDefaultNames.foreach { frozen =>
            withClue(s"不得残留 $frozen: ") {
              sql should not include s"$frozen."
              sql should not include s"$frozen`"
              sql should not include s"$frozen\n"
            }
          }
        }
      }
    }
  }

  it should "INIT_SCHEMA 的 37 条 DDL 全部只引用当前命名空间的库（5 建库 + 32 建表）" in {
    val ns = WarehouseNamespace.of("dw_b")
    val statements = LocalSchemaInitJob.statements(ns)
    statements.size should be(37)
    val allowed = ns.layers.values.toSet
    statements.map(_._1).toSet should be(allowed)
    statements.foreach { case (db, ddl) =>
      withClue(s"$db: ") {
        allowed.contains(db) should be(true)
        FrozenDefaultNames.foreach { frozen => ddl should not include s"$frozen." }
      }
    }
  }
}

object WarehouseNamespaceSpec {

  private val Default = "DEFAULT"
  private val Ok = "OK"

  /** 规格文件相对仓根的路径 */
  private val SpecRelative = "contract-specs/specs/warehouse-namespace.v1.json"

  /** 改造前的裸库名（缺省命名空间下应当出现，换源后必须消失） */
  private val FrozenDefaultNames: Seq[String] = Seq("dw_ods", "dw_dwd", "dw_dim", "dw_dws", "dw_ads")

  /** 与实现同源的判定：仅 null 与空串是缺省，其余交给白名单校验 */
  private def classify(input: String): String =
    if (input == null || input.isEmpty) Default
    else WarehouseNamespace.parse(input).left.getOrElse(Ok)

  private def readSpec(): JsonNode = {
    val path = findRepoRoot().resolve(SpecRelative)
    if (!Files.isRegularFile(path)) throw new IllegalStateException(s"规格文件缺失: $path")
    new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8))
  }

  private def findRepoRoot(): Path = {
    val start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    var dir: Path = start
    while (dir != null && !Files.isRegularFile(dir.resolve(SpecRelative))) dir = dir.getParent
    if (dir == null) throw new IllegalStateException(s"找不到仓库根：从 $start 向上未发现 $SpecRelative")
    dir
  }

  private def textSeq(node: JsonNode): Seq[String] = {
    val b = Seq.newBuilder[String]
    val it = node.elements()
    while (it.hasNext) b += it.next().asText()
    b.result()
  }

  private def jsonMap(node: JsonNode): Map[String, String] = {
    val out = mutable.LinkedHashMap.empty[String, String]
    val it = node.fieldNames()
    while (it.hasNext) {
      val k = it.next()
      out += (k -> node.get(k).asText())
    }
    out.toMap
  }

  /**
   * 所有产出「含库名的 SQL/DDL 文本」的模板（P1-04 的消费面清单）：
   * 少了一个模板就说明有人的 SQL 还没走命名空间，本清单本身即验收对象。
   */
  private object Producers {
    /** P2-01：ODS 模板的 source_system 注入值（在产由 `--sourceSystem` 传入） */
    private val srcSys = "mock-mall"
    val all: Seq[(String, WarehouseNamespace => String)] = Seq(
      "OdsLoadSql.behaviorFromLanding" -> (ns => OdsLoadSql.behaviorFromLanding(ns, srcSys, 7L)),
      "OdsLoadSql.userFromLanding" -> (ns => OdsLoadSql.userFromLanding(ns, srcSys, 8L)),
      "OdsLoadSql.productFromLanding" -> (ns => OdsLoadSql.productFromLanding(ns, srcSys, 9L)),
      "OdsLoadSql.tradeFromLanding" -> (ns => OdsLoadSql.tradeFromLanding(ns, srcSys, 10L)),
      "DimSql.userSnapshot" -> (ns => DimSql.userSnapshot(ns, "20260901")),
      "DimSql.productSnapshot" -> (ns => DimSql.productSnapshot(ns, "20260901")),
      "DwdSql.behaviorClean" -> (ns => DwdSql.behaviorClean(ns, "20260901")),
      "DwdSql.duplicateReject" -> (ns => DwdSql.duplicateReject(ns, "20260901")),
      "DwsSql.userBehaviorDay" -> (ns => DwsSql.userBehaviorDay(ns, "20260901")),
      "DwsSql.funnelDay" -> (ns => DwsSql.funnelDay(ns, "20260901")),
      "DwsSql.productBehaviorDay" -> (ns => DwsSql.productBehaviorDay(ns, "20260901")),
      "DwsSql.tradeDay" -> (ns => DwsSql.tradeDay(ns, "20260901")),
      "DwsSql.productSaleDay" -> (ns => DwsSql.productSaleDay(ns, "20260901")),
      "DwsSql.regionSaleDay" -> (ns => DwsSql.regionSaleDay(ns, "20260901")),
      "DwsSql.userTradePeriod" -> (ns => DwsSql.userTradePeriod(ns, "20260901", "20260601", "20260901")),
      "AdsSql.operationOverview" -> (ns => AdsSql.operationOverview(ns, "20260901")),
      "AdsSql.operationOverview(staging)" -> (ns => AdsSql.operationOverview(ns, "20260901", Some("S20260901_99"))),
      "AdsSql.activeTrend" -> (ns => AdsSql.activeTrend(ns, "20260901")),
      "AdsSql.funnel" -> (ns => AdsSql.funnel(ns, "20260901")),
      "AdsSql.hotProduct" -> (ns => AdsSql.hotProduct(ns, "20260901", 50)),
      "AdsSql.productConversion" -> (ns => AdsSql.productConversion(ns, "20260901")),
      "AdsSql.saleTrend" -> (ns => AdsSql.saleTrend(ns, "20260901")),
      "AdsSql.userProfile" -> (ns => AdsSql.userProfile(ns, "20260901", "20260601", "20260901")),
      "AdsSql.dataQuality" -> (ns => AdsSql.dataQuality(ns, "20260901")),
      "AdsSql.staging" -> (ns => AdsSql.staging(ns, "ads_operation_overview")),
      "AdsSql.formal" -> (ns => AdsSql.formal(ns, "ads_operation_overview")),
      "TradeDwdJob.orderDetailInsertSql" -> (ns => TradeDwdJob.orderDetailInsertSql(ns, "20260901")),
      "MetricAdsSpec.hiveTable" -> (ns => MetricAdsSpec.TABLES.map(_.hiveTable(ns)).mkString("\n")),
      "LocalSchemaInitJob.statements" -> (ns => LocalSchemaInitJob.statements(ns).map(_._2).mkString("\n"))
    )
  }
}
