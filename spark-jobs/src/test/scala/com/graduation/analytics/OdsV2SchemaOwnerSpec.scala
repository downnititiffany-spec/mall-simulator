package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.OdsV2Columns
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * P2-01 A1–A3 / A11 / A12：ODS v2 公共列的**结构与「唯一所有者」对账**（不需要 Spark）。
 *
 * 本类钉住三件事：
 *  1. v2 列集合是**加法扩列**：v1 九业务列 + `trace_id` + 全部 `payload_*` 一个不少、类型不变、
 *     相对顺序不变；新增列只追加。`event_time` 保持 STRING（类型收敛不在本任务）。
 *  2. 四张 ODS 表共享同一份 12 列公共列**逐字一致**（名字+类型+顺序），业务列各表自有。
 *  3. **唯一所有者**落地：`warehouse/ddl/00-ods.sql`（静态 SQL）与
 *     `LocalSchemaInitJob.statements`（派生）都与 `OdsV2Columns` 逐列一致；
 *     `LocalSchemaInitJob` 的 ODS 建表列**由所有者派生**，不再是手抄副本。
 */
class OdsV2SchemaOwnerSpec extends AnyFlatSpec with Matchers {

  import OdsV2Columns._

  private val ddlRelative = "warehouse/ddl/00-ods.sql"

  // ── A1：加法扩列（v1 列一个不动）────────────────────────────────────────

  /** 独立 oracle：**改造前**的 v1 物理列序（从 `warehouse/ddl/00-ods.sql` 快照逐字抄下） */
  private val V1Oracle: Map[String, Seq[(String, String)]] = Map(
    "ods_user_event" -> Seq(
      "event_id" -> "STRING", "event_type" -> "STRING", "event_time" -> "STRING",
      "ingest_time" -> "STRING", "source_system" -> "STRING", "schema_version" -> "STRING",
      "trace_id" -> "STRING", "payload_user_id" -> "STRING", "payload_age_group" -> "STRING",
      "payload_city_level" -> "STRING", "payload_member_level" -> "STRING",
      "payload_register_time" -> "STRING", "source_file" -> "STRING",
      "ingest_batch_id" -> "BIGINT"),
    "ods_product_event" -> Seq(
      "event_id" -> "STRING", "event_type" -> "STRING", "event_time" -> "STRING",
      "ingest_time" -> "STRING", "source_system" -> "STRING", "schema_version" -> "STRING",
      "trace_id" -> "STRING", "payload_product_id" -> "STRING",
      "payload_product_name" -> "STRING", "payload_category_id" -> "STRING",
      "payload_category_name" -> "STRING", "payload_parent_category_id" -> "STRING",
      "payload_parent_category_name" -> "STRING", "payload_brand_id" -> "STRING",
      "payload_price" -> "DECIMAL(18,2)", "payload_cost" -> "DECIMAL(18,2)",
      "payload_status" -> "STRING", "source_file" -> "STRING",
      "ingest_batch_id" -> "BIGINT"),
    "ods_behavior_event" -> Seq(
      "event_id" -> "STRING", "event_type" -> "STRING", "event_time" -> "STRING",
      "ingest_time" -> "STRING", "source_system" -> "STRING", "schema_version" -> "STRING",
      "trace_id" -> "STRING", "payload_user_id" -> "STRING", "payload_product_id" -> "STRING",
      "payload_session_id" -> "STRING", "payload_behavior_type" -> "STRING",
      "payload_channel" -> "STRING", "source_file" -> "STRING",
      "ingest_batch_id" -> "BIGINT"),
    "ods_trade_event" -> Seq(
      "event_id" -> "STRING", "event_type" -> "STRING", "event_time" -> "STRING",
      "ingest_time" -> "STRING", "source_system" -> "STRING", "schema_version" -> "STRING",
      "trace_id" -> "STRING", "payload_order_id" -> "STRING", "payload_user_id" -> "STRING",
      "payload_payment_id" -> "STRING", "payload_refund_id" -> "STRING",
      "payload_product_id" -> "STRING", "payload_amount" -> "DECIMAL(18,2)",
      "payload_total_amount" -> "DECIMAL(18,2)", "payload_status" -> "STRING",
      "payload_reason" -> "STRING", "payload_items" -> "STRING",
      "source_file" -> "STRING", "ingest_batch_id" -> "BIGINT")
  )

  /** v1 数据列的类型若被改动，这里会先红——它是「零破坏」断言的独立依据 */
  it should "A3c v1 数据列的列序/顺序在所有者中与改造前快照逐位一致（一个字面量都不许漂）" in {
    V1Oracle.foreach { case (table, oracle) =>
      withClue(s"$table v1 段: ") {
        v1Columns(table).map(c => c.name -> c.sqlType) should be(oracle)
      }
    }
  }

  it should "A1 v2 新增列全部追加在 v1 列之后（既有列的序号一格不动）" in {
    V1Oracle.foreach { case (table, oracle) =>
      withClue(s"$table: ") {
        val actual = dataColumns(table).map(c => c.name -> c.sqlType)
        // 前 len(v1) 位与改造前快照逐位相等 ⇒ 既有列的名字、类型、序号三者都没动
        actual.take(oracle.size) should be(oracle)
        // 新增列追加在后
        actual.drop(oracle.size) should be(V2NewColumns.map(c => c.name -> c.sqlType))
      }
    }
  }

  it should "A1b 12 公共列在四张表里都在，且共享段（信封 7 列）逐位一致" in {
    val shared = CommonColumns.take(7).map(c => c.name -> c.sqlType)
    OdsTables.foreach { table =>
      withClue(s"$table: ") {
        val actual = dataColumns(table).map(c => c.name -> c.sqlType).toMap
        CommonColumns.foreach { c =>
          withClue(s"缺公共列 ${c.name}: ") { actual.get(c.name) should be(Some(c.sqlType)) }
        }
        dataColumns(table).take(7).map(c => c.name -> c.sqlType) should be(shared)
      }
    }
    CommonColumns.map(_.name) should be(Seq(
      "event_id", "event_type", "event_time", "ingest_time", "source_system",
      "schema_version", "trace_id", "raw_event_type", "raw_source_system", "landing_file",
      "payload_json", "payload_hash", "ingest_batch_id"))
    // D-052 裁决的 12 列 = 上表的 13 项去掉 `ingest_batch_id`（它在 v1 就是审计列、物理上排末尾）
    val twelve = CommonColumns.map(_.name).toSet - "ingest_batch_id"
    twelve should be(Set("event_id", "event_type", "event_time", "ingest_time", "source_system",
      "schema_version", "trace_id", "raw_event_type", "raw_source_system", "landing_file",
      "payload_json", "payload_hash"))
    twelve.size should be(12)
  }

  it should "A2 event_time 保持 STRING（类型收敛不在本任务范围）" in {
    OdsTables.foreach { t =>
      withClue(s"$t: ") { columnType(t, "event_time") should be("STRING") }
    }
  }

  it should "A3 四张表两两不同（业务列各表自有，不是把同一份 payload_* 抄四遍）" in {
    val payloadSets = OdsTables.map(t => PayloadColumnsByTable(t).map(_.name).toSet)
    payloadSets.distinct.size should be(4)
    OdsTables.map(t => dataColumns(t).map(_.name)).distinct.size should be(4)
    // 四张表的列数：v1 各自不同 + 5 个新增列
    val sizes = OdsTables.map(t => dataColumns(t).size)
    sizes should be(V1Oracle.toSeq.map { case (t, cols) => cols.size + V2NewColumns.size })
  }

  it should "A3b 信封共享列名不含 payload_ 前缀，业务列名必须含 payload_ 前缀（分层不混）" in {
    // 共享/新增/审计段里带 payload_ 前缀的只有这两列（v2 的 payload 保真列）
    (V2NewColumns.map(_.name)).filter(_.startsWith("payload_")) should be(
      Seq("payload_json", "payload_hash"))
    (CommonColumns.map(_.name) ++ AuditColumns.map(_.name))
      .filter(_.startsWith("payload_")) should be(Seq("payload_json", "payload_hash"))
    PayloadColumnsByTable.foreach { case (t, cols) =>
      withClue(s"$t: ") { cols.map(_.name).filterNot(_.startsWith("payload_")) should be(empty) }
    }
    AuditColumns.map(_.name) should be(Seq("source_file", "ingest_batch_id"))
    PartitionColumns.map(_.name) should be(Seq("dt", "hour"))
  }

  // ── A11/A12：静态 DDL 与所有者对账 ──────────────────────────────────────

  it should "A11 warehouse/ddl/00-ods.sql 的四张表列名/类型/顺序与唯一所有者逐列一致" in {
    val ddl = StaticOdsDdl.parse(readStaticDdl())
    OdsTables.foreach { table =>
      withClue(s"$table: ") {
        ddl.columnSeq(table) should be(dataColumns(table).map(c => c.name -> c.sqlType))
        ddl.partitions(table) should be(PartitionColumns.map(c => c.name -> c.sqlType))
      }
    }
  }

  it should "A11b 静态 DDL 中 v1 全部列与改造前快照逐位一致（只允许在尾部追加）" in {
    val ddl = StaticOdsDdl.parse(readStaticDdl())
    V1Oracle.foreach { case (table, oracle) =>
      val actual = ddl.columnSeq(table)
      withClue(s"$table: ") {
        // 前 len(oracle) 位逐位相等 ⇒ v1 的名字/类型/顺序三者都没动
        actual.take(oracle.size) should be(oracle)
        // 多出来的只能是 v2 新增列
        actual.drop(oracle.size) should be(V2NewColumns.map(c => c.name -> c.sqlType))
      }
    }
  }

  it should "A11c 静态 DDL 的最终列集与唯一所有者逐列一致，分区列在 PARTITIONED BY 中" in {
    val ddl = StaticOdsDdl.parse(readStaticDdl())
    OdsTables.foreach { table =>
      withClue(s"$table: ") {
        ddl.columnSeq(table) should be(dataColumns(table).map(c => c.name -> c.sqlType))
        ddl.partitions(table) should be(PartitionColumns.map(c => c.name -> c.sqlType))
        // 分区列不得混进普通列
        ddl.columnSeq(table).map(_._1) should not contain "dt"
        ddl.columnSeq(table).map(_._1) should not contain "hour"
      }
    }
  }

  it should "A12 LocalSchemaInitJob 的 ODS 建表列由唯一所有者派生，逐列一致且语句总数不变" in {
    val ns = WarehouseNamespace.defaultNamespace
    val statements = LocalSchemaInitJob.statements(ns)
    statements.size should be(37) // 派生改造不得增删语句

    OdsTables.foreach { table =>
      val ddl = statements.map(_._2).find(s => s.contains(s".$table (")).getOrElse(
        fail(s"INIT_SCHEMA 未包含 $table 建表语句"))
      val parsed = StaticOdsDdl.parseCreateTable(ddl)
      withClue(s"$table: ") {
        parsed.columnSeq(table) should be(dataColumns(table).map(c => c.name -> c.sqlType))
        parsed.partitions(table) should be(PartitionColumns.map(c => c.name -> c.sqlType))
      }
    }
    // 与静态 DDL 同源：两者对同一张表给出同一份列定义
    val static = StaticOdsDdl.parse(readStaticDdl())
    OdsTables.foreach { t =>
      val derived = statements.map(_._2).find(_.contains(s".$t (")).getOrElse(
        fail(s"INIT_SCHEMA 未包含 $t 建表语句"))
      withClue(s"$t 静态/派生不一致: ") {
        static.columnSeq(t) should be(StaticOdsDdl.parse(derived).columnSeq(t))
      }
    }
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────

  /** 读静态 DDL（存在且非空先断言） */
  private def readStaticDdl(): String = {
    val path: Path = P2TestSupport.repoRoot.resolve(ddlRelative)
    P2TestSupport.requireNonEmpty(path)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}

/**
 * 静态 ODS DDL 的极简解析器。
 *
 * 只认 `warehouse/ddl/00-ods.sql` 与 `LocalSchemaInitJob.statements` 这两种已知形态：
 * `CREATE [EXTERNAL] TABLE [IF NOT EXISTS] <任意库名>.<table> ( <列…> ) … [PARTITIONED BY ( … )]`。
 *
 * 列体用**括号配平扫描**取，不用正则 —— `DECIMAL(18,2)` 里的逗号/括号会让朴素正则错位。
 * 列定义允许 `COMMENT '…'`（含逗号、括号、转义单引号）。
 */
object StaticOdsDdl {

  final case class Parsed(columns: Map[String, Seq[(String, String)]],
                          parts: Map[String, Seq[(String, String)]]) {
    def columnSeq(table: String): Seq[(String, String)] =
      columns.getOrElse(table, throw new IllegalArgumentException(s"DDL 未解析到表 $table"))
    def partitions(table: String): Seq[(String, String)] =
      parts.getOrElse(table, Seq.empty)
  }

  def parse(sql: String): Parsed = {
    val cols = scala.collection.mutable.LinkedHashMap.empty[String, Seq[(String, String)]]
    val parts = scala.collection.mutable.LinkedHashMap.empty[String, Seq[(String, String)]]
    createTableRegex.findAllMatchIn(sql).foreach { m =>
      val table = bareTable(m.group(1))
      val open = sql.indexOf('(', m.start)
      require(open >= 0, s"$table: CREATE TABLE 后无 '('")
      val close = matchParen(sql, open)
      cols += table -> splitTopLevel(sql.substring(open + 1, close)).flatMap(parseColumn)
      // 分区子句在同一条语句内（静态 DDL 在列体后、语句结尾 ';' 之前）
      val stmtEnd = {
        val semi = sql.indexOf(';', close)
        if (semi >= 0) semi else sql.length
      }
      partitionedByRegex.findFirstMatchIn(sql.substring(close, stmtEnd)).foreach { pm =>
        parts += table -> splitTopLevel(pm.group(1)).flatMap(parseColumn)
      }
    }
    Parsed(cols.toMap, parts.toMap)
  }

  def parseCreateTable(sql: String): Parsed = parse(sql)

  /**
   * 库名可以是 `dw_ods`、`${WAREHOUSE_PREFIX}_ods`、`` `db` ``、`dw_b_ods` 等任意形态
   * ⇒ 表名捕获必须容许 `<库名>.` 限定符（库名里还含 `${…}` 占位）与反引号，再取最后一段。
   *
   * 20260912 归属裁定（**测试侧缺陷**）：原正则 `(\w+)\s*\(` 只认**裸表名**，遇到静态 DDL 的
   * `${WAREHOUSE_PREFIX}_ods.ods_user_event` 与派生 DDL 的 `dw_ods.ods_user_event` 都零匹配，
   * A11/A11b/A11c/A12 直接抛「DDL 未解析到表 ods_user_event」——与本对象自己的 KDoc
   * （第 217 行「`<任意库名>.<table>`」）和下面这行注释自相矛盾。修的是正则，不是断言。
   */
  private val createTableRegex =
    """(?is)CREATE\s+(?:EXTERNAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([^\s(]+)\s*\(""".r

  /** `db.table` / `${PREFIX}_ods.table` / `` `db`.table `` → 裸表名（查表键） */
  private def bareTable(qualified: String): String =
    qualified.substring(qualified.lastIndexOf('.') + 1).stripPrefix("`").stripSuffix("`")

  private val partitionedByRegex = """(?is)PARTITIONED\s+BY\s*\(([^)]*)\)""".r

  /** 从 `open` 处的 '(' 找到配对的 ')'（引号与转义单引号内的括号不计） */
  private def matchParen(s: String, open: Int): Int = {
    var depth = 0
    var inStr = false
    var i = open
    while (i < s.length) {
      val c = s.charAt(i)
      if (inStr) {
        if (c == '\'') {
          if (i + 1 < s.length && s.charAt(i + 1) == '\'') i += 1 else inStr = false
        }
      } else c match {
        case '\'' => inStr = true
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if (depth == 0) return i
        case _ =>
      }
      i += 1
    }
    throw new IllegalArgumentException(s"括号未配平：offset=$open")
  }

  /** `name TYPE [COMMENT '…']` → (`name`, 归一化类型)；注释与多余空白丢弃 */
  private def parseColumn(raw: String): Option[(String, String)] = {
    val stripped = raw.replaceAll("(?is)\\s+COMMENT\\s+'(?:[^']|'')*'", "").trim
    if (stripped.isEmpty) None
    else {
      val parts = stripped.split("\\s+", 2)
      val name = parts(0).trim
      val tpe = if (parts.length > 1) parts(1).trim.toUpperCase.replaceAll("\\s+", "") else ""
      Some(name -> tpe)
    }
  }

  /** 顶层逗号切分（括号内逗号不切；引号内不切）——`DECIMAL(18,2)` 靠这个活下来 */
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
