package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.DwsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * S3-11：DWS 物理表形的**三方一致**守卫（不需要 Spark）。
 *
 * 背景（20260917 实测）：`warehouse/ddl/03-dws.sql` 是 DWS 表形的**第二所有者**（参考副本），
 * 与唯一所有者 `LocalSchemaInitJob.statements`（`CREATE TABLE … USING parquet`）以及**写入投影**
 * （`DwsSql.*` 的 `INSERT OVERWRITE … SELECT` 列表）三份之间的漂移**无人看守**：
 * Spark 按**位置**写 Parquet，三份列序只要有一份不同，就会出现"值串列"的静默错数
 * （不报错、不加行数、`SELECT *` 也看不出）。本轮实测发现该漂移**已经发生**：
 * `dws_user_behavior_day` 在参考副本里是 `… cart, buy, search, active_hours`，而所有者与写入投影是
 * `… cart, search, active_hours, buy` ⇒ 若按参考副本建表、按写入投影灌数，`search/active_hours/buy`
 * 三列互换。本类把三方钉死，使这类漂移**在编译后的测试里立刻红**，而不是等真集群上出现错数。
 *
 * 本类钉四件事：
 *  1. 参考副本 `warehouse/ddl/03-dws.sql` ↔ 所有者：列**名/类型/顺序**逐列一致，分区列一致；
 *  2. 写入投影（`DwsSql`）↔ 所有者：**列序**一致（投影无类型可对）；
 *  3. 三方**表集**一致（不许多、不许少）、写入语句确实指向它自称的那张表；
 *  4. 一份**冻结快照** `Frozen`：防止"三份一起漂"（例如三处同时漏加一列）时全绿。
 *
 * 归因边界（不得越界表述）：本类只判**表形/列序**，不判任何口径、不判任何指标值、不判数据类型在
 * 运行期的物理落盘（本地链与真 Hive 不等价 —— 见 `P2TestSupport` 自陈的测试域）。
 * 加列/改列是**有意为之**的变更 ⇒ 必须同批改所有者＋参考副本＋写入投影，并**有意**更新 `Frozen`。
 */
class DwsSchemaOwnerSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace
  private val dt = "20260901"
  private val ddlRelative = "warehouse/ddl/03-dws.sql"

  /** 写入语句 = 物理写入的**列序真相**（Spark 按位置落 Parquet） */
  private val writes: Seq[(String, String)] = Seq(
    "dws_user_behavior_day" -> DwsSql.userBehaviorDay(ns, dt),
    "dws_behavior_funnel_day" -> DwsSql.funnelDay(ns, dt),
    "dws_product_behavior_day" -> DwsSql.productBehaviorDay(ns, dt),
    "dws_trade_day" -> DwsSql.tradeDay(ns, dt),
    "dws_product_sale_day" -> DwsSql.productSaleDay(ns, dt),
    "dws_user_trade_period" -> DwsSql.userTradePeriod(ns, dt, "20260601", dt),
    "dws_region_sale_day" -> DwsSql.regionSaleDay(ns, dt)
  )

  /**
   * S3-11 冻结快照（列名 → 类型，逐表按序）＝ 本轮实测的三方一致值。
   *
   * 它是**独立的第四份**：只在下述情况红 —— 有人**同时**改了所有者＋参考副本＋写入投影
   * （剩下三份互等，前三个用例全绿），此时必须**显式**改这里，逼一次"这确实是有意的表形变更"的判断。
   * 本快照依据：设计 §12.1 L313-L319 的 DWS 主题表清单 + 现状实测（不含 `dt` 分区列）。
   */
  private val Frozen: Map[String, Seq[(String, String)]] = Map(
    "dws_user_behavior_day" -> Seq(
      "user_id" -> "BIGINT", "pv" -> "BIGINT", "fav" -> "BIGINT", "cart" -> "BIGINT",
      "search" -> "BIGINT", "active_hours" -> "INT", "buy" -> "BIGINT"),
    "dws_behavior_funnel_day" -> Seq(
      "category_id" -> "BIGINT", "channel" -> "STRING", "view_users" -> "BIGINT",
      "intent_users" -> "BIGINT", "order_users" -> "BIGINT", "pay_users" -> "BIGINT",
      "intent_rate" -> "DECIMAL(8,4)", "order_rate" -> "DECIMAL(8,4)", "pay_rate" -> "DECIMAL(8,4)",
      "overall_buy_rate" -> "DECIMAL(8,4)", "cart_users" -> "BIGINT", "cart_rate" -> "DECIMAL(8,4)"),
    "dws_product_behavior_day" -> Seq(
      "product_id" -> "BIGINT", "category_id" -> "BIGINT", "pv" -> "BIGINT", "uv" -> "BIGINT",
      "fav" -> "BIGINT", "cart" -> "BIGINT", "buy" -> "BIGINT"),
    "dws_product_sale_day" -> Seq(
      "product_id" -> "BIGINT", "category_id" -> "BIGINT", "sale_count" -> "BIGINT",
      "sale_amount" -> "DECIMAL(18,2)", "buyer_count" -> "BIGINT"),
    "dws_trade_day" -> Seq(
      "order_count" -> "BIGINT", "buyer_count" -> "BIGINT", "sale_amount" -> "DECIMAL(18,2)",
      "refund_amount" -> "DECIMAL(18,2)", "net_sale_amount" -> "DECIMAL(18,2)",
      "avg_order_value" -> "DECIMAL(18,2)"),
    "dws_user_trade_period" -> Seq(
      "user_id" -> "BIGINT", "last_buy_date" -> "STRING", "order_count" -> "BIGINT",
      "sale_amount" -> "DECIMAL(18,2)", "period_start" -> "STRING", "period_end" -> "STRING",
      "valid_order_count" -> "BIGINT"),
    "dws_region_sale_day" -> Seq(
      "region" -> "STRING", "buyer_count" -> "BIGINT", "order_count" -> "BIGINT",
      "sale_amount" -> "DECIMAL(18,2)")
  )

  // ── 夹具 ────────────────────────────────────────────────────────────────

  /** 参考副本 `warehouse/ddl/03-dws.sql`（存在且非空先断言） */
  private def readDdl(): String = {
    val path: Path = P2TestSupport.repoRoot.resolve(ddlRelative)
    P2TestSupport.requireNonEmpty(path)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  /** 参考副本解析结果（库名形态任意，键取裸表名） */
  private def ddlParsed: StaticOdsDdl.Parsed = StaticOdsDdl.parse(readDdl())

  /**
   * 唯一所有者：`LocalSchemaInitJob` 里 namespace = `ns.dws` 的**建表**语句。
   *
   * 实测（20260917，RED 首轮）：`ns.dws` 名下共 **8** 条语句 —— 第 8 条是
   * `CREATE DATABASE IF NOT EXISTS dw_dws`（`LocalSchemaInitJob.scala:40`），
   * 故必须按 `CREATE TABLE` 过滤；若只按命名空间过滤，本夹具会以「8 ≠ 7」红在**自己的夹具**上。
   */
  private def ownerStatements: Seq[String] = {
    val all = LocalSchemaInitJob.statements(ns)
      .filter(_._1 == ns.dws).map(_._2).filter(_.contains("CREATE TABLE"))
    // 夹具自检：过滤条件若因命名空间写法变化而落空，必须**响**，不能静默返回空集
    all.size should be(Frozen.size)
    all
  }

  private def ownerParsed: StaticOdsDdl.Parsed =
    StaticOdsDdl.parse(ownerStatements.mkString("\n;\n"))

  private def columnSeqOf(parsed: StaticOdsDdl.Parsed, table: String): Seq[(String, String)] =
    withClue(s"$table: ") { parsed.columnSeq(table) }

  /**
   * 剥掉 `--` 行注释（逐行截断到第一个 `--`）。
   *
   * 局限（如实声明）：不识别字符串字面量里的 `--`（本文件没有这种写法）；
   * 若将来出现，本守卫会**偏严**（把字面量当注释切掉）⇒ 宁可偏严，也不放过真语句。
   */
  private def stripLineComments(sql: String): String =
    sql.linesIterator.map { line =>
      val at = line.indexOf("--")
      if (at >= 0) line.substring(0, at) else line
    }.mkString("\n")

  // ── 1. 参考副本 ↔ 唯一所有者 ───────────────────────────────────────────

  it should "A1 参考副本 03-dws.sql 的 7 张 DWS 表与唯一所有者逐列一致（列名/类型/顺序）" in {
    val ddl = ddlParsed
    val owner = ownerParsed
    Frozen.keys.foreach { table =>
      withClue(s"$table 参考副本与所有者不一致: ") {
        columnSeqOf(ddl, table) should be(columnSeqOf(owner, table))
      }
    }
  }

  it should "A2 参考副本与所有者的分区列一致，且分区列不得混进普通列" in {
    val ddl = ddlParsed
    val owner = ownerParsed
    Frozen.keys.foreach { table =>
      withClue(s"$table: ") {
        ddl.partitions(table) should be(Seq("dt" -> "STRING"))
        owner.partitions(table) should be(Seq("dt" -> "STRING"))
        ddl.columnSeq(table).map(_._1) should not contain "dt"
        owner.columnSeq(table).map(_._1) should not contain "dt"
      }
    }
  }

  it should "A3 参考副本不得用 ALTER TABLE 旁路加列（否则「唯一所有者」断言可被绕过）" in {
    // 只扫**语句**：先剥掉 `--` 行注释（本文件头部就逐字写着"不得用 ALTER TABLE 旁路"这句提示，
    // 不剥注释会把自己的提示当成违规 —— 实测 20260917 GREEN 首轮即如此，属守卫实现缺陷，非文档缺陷）。
    val statementsOnly = stripLineComments(readDdl())
    withClue("03-dws.sql 出现 ALTER TABLE 语句：") {
      "(?is)\\bALTER\\s+TABLE\\b".r.findFirstIn(statementsOnly) should be(None)
    }
  }

  // ── 2. 写入投影 ↔ 唯一所有者 ───────────────────────────────────────────

  it should "B1 写入投影（DwsSql 的 INSERT … SELECT 列序）与所有者列序逐表一致" in {
    val owner = ownerParsed
    writes.foreach { case (expectedTable, sql) =>
      val w = DwsWriteProjection.parse(sql)
      withClue(s"$expectedTable 写入投影与所有者列序不一致: ") {
        w.table should be(expectedTable)
        w.columns should be(columnSeqOf(owner, expectedTable).map(_._1))
      }
    }
  }

  it should "B2 每张 DWS 表恰好一条 INSERT OVERWRITE（不多写、不漏写）" in {
    val ownerTables = Frozen.keys.toSet
    writes.map(_._1).toSet should be(ownerTables)
    writes.map { case (_, sql) => DwsWriteProjection.parse(sql).table }.toSet should be(ownerTables)
    writes.size should be(ownerTables.size)
  }

  // ── 3. 三方一致（含冻结快照）────────────────────────────────────────────

  it should "C1 所有者列名/类型逐表等于 S3-11 冻结快照（防「三份一起漂」）" in {
    val owner = ownerParsed
    Frozen.foreach { case (table, expected) =>
      withClue(s"$table 与冻结快照不一致: ") {
        columnSeqOf(owner, table) should be(expected)
      }
    }
  }

  it should "C2 参考副本表集与所有者表集一致（不许多表、不许漏表）" in {
    val ddlTables = ddlParsed.columns.keySet
    ddlTables should be(Frozen.keys.toSet)
  }
}

/**
 * DWS 写入语句的**投影列序**解析器（S3-11 反熵守卫的夹具，不是生产代码）。
 *
 * 只认本项目 `DwsSql.*` 的已知形态：`INSERT OVERWRITE TABLE <库>.<表> PARTITION(dt=…)\nSELECT <列表> FROM …`。
 * 列表按**顶层逗号**切（`DECIMAL(8,4)`/`CASE … END` 里的逗号与括号不切），每个元素取
 * `AS <别名>`；无 `AS` 时只接受**纯列引用**（`b.user_id` / `product_id`）取最后一段。
 * 认不出的形态**直接抛异常**——守卫宁可响，也不许把解析落空当成"零列一致"。
 */
object DwsWriteProjection {

  final case class Write(table: String, columns: Seq[String])

  private val insertRe = """(?is)INSERT\s+OVERWRITE\s+TABLE\s+([^\s(]+)""".r
  private val aliasRe = """(?is)^(.*?)\s+AS\s+([A-Za-z_][A-Za-z0-9_]*)$""".r
  private val plainRefRe = """^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""".r

  def parse(sql: String): Write = {
    val insert = insertRe.findFirstMatchIn(sql).getOrElse(
      throw new IllegalArgumentException("写入语句里找不到 INSERT OVERWRITE TABLE"))
    val qualified = insert.group(1)
    val table = qualified.substring(qualified.lastIndexOf('.') + 1).stripPrefix("`").stripSuffix("`")

    val selectAt = indexOfKeyword(sql, "SELECT", insert.end)
    require(selectAt >= 0, s"$table: INSERT 之后找不到 SELECT")
    val fromAt = indexOfKeyword(sql, "FROM", selectAt + "SELECT".length)
    require(fromAt >= 0, s"$table: SELECT 列表之后找不到顶层 FROM")

    val body = sql.substring(selectAt + "SELECT".length, fromAt)
    val columns = splitTopLevel(body).map(item => columnOf(table, item))
    require(columns.nonEmpty, s"$table: 投影解析出 0 列")
    Write(table, columns)
  }

  /** 归一化单个投影元素 → 列名 */
  private def columnOf(table: String, raw: String): String = {
    val item = raw.replaceAll("\\s+", " ").trim.stripPrefix("|").trim
    item match {
      case aliasRe(_, alias) => alias
      case plainRef if plainRefRe.pattern.matcher(plainRef).matches() =>
        plainRef.substring(plainRef.lastIndexOf('.') + 1)
      case other =>
        throw new IllegalArgumentException(s"$table: 投影元素既无 AS 别名也不是纯列引用：[$other]")
    }
  }

  /** 找 `keyword`（整词、大小写不敏感、**顶层**即在括号外的第一次出现） */
  private def indexOfKeyword(s: String, keyword: String, from: Int): Int = {
    var depth = 0
    var inStr = false
    var i = from
    while (i < s.length) {
      val c = s.charAt(i)
      if (inStr) {
        if (c == '\'') {
          if (i + 1 < s.length && s.charAt(i + 1) == '\'') i += 1 else inStr = false
        }
      } else if (c == '\'') inStr = true
      else if (c == '(') depth += 1
      else if (c == ')') depth -= 1
      else if (depth == 0 && regionMatchesKeyword(s, i, keyword)) return i
      i += 1
    }
    -1
  }

  private def regionMatchesKeyword(s: String, at: Int, keyword: String): Boolean = {
    if (at + keyword.length > s.length) return false
    if (!s.regionMatches(true, at, keyword, 0, keyword.length)) return false
    val before = if (at == 0) ' ' else s.charAt(at - 1)
    val afterIdx = at + keyword.length
    val after = if (afterIdx >= s.length) ' ' else s.charAt(afterIdx)
    !isWordChar(before) && !isWordChar(after)
  }

  private def isWordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  /** 顶层逗号切分（括号内/字符串内的逗号不切） */
  private def splitTopLevel(body: String): Seq[String] = {
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val sb = new StringBuilder
    var depth = 0
    var inStr = false
    var i = 0
    while (i < body.length) {
      val c = body.charAt(i)
      if (inStr) {
        sb.append(c)
        if (c == '\'') inStr = false
      } else c match {
        case '\'' => inStr = true; sb.append(c)
        case '(' => depth += 1; sb.append(c)
        case ')' => depth -= 1; sb.append(c)
        case ',' if depth == 0 => out += sb.toString; sb.clear()
        case other => sb.append(other)
      }
      i += 1
    }
    if (sb.toString.trim.nonEmpty) out += sb.toString
    out.toSeq
  }
}
