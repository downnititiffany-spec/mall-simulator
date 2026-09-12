package com.graduation.analytics

import com.graduation.analytics.sql.{DwdSql, OdsLoadSql, OdsV2Columns}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * P2-01 A9 / A10：四个 ODS INSERT 模板的**列契约**（不需要 Spark）。
 *
 * 钉住：
 *  - 每个模板的 SELECT 列数/列序与目标表（唯一所有者 `OdsV2Columns`）**逐位对齐**；
 *    Hive/Spark 的 `INSERT … SELECT` 按位置匹配，位置错就是数据错，且不会报错——
 *    所以这条必须是显式断言而不是靠人看。
 *  - 常量 `'landing' AS source_file` **必须消失**（D-057）。
 *  - `source_system` 必须来自平台参数通道 `${sourceSystem}`（D-056），
 *    行内原值只能落到 `raw_source_system`（原样保真列）。
 *  - DWD 既有下游一个都不碰新增列（加法扩列不得牵连下游）。
 */
class OdsV2SqlContractSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace
  private val batchId = 7L

  /** 平台注入值（D-056）；在产由 `--sourceSystem=<source_registry.source_code>` 传入 */
  private val SrcSys = "mock-mall"

  /** 注入值的 SQL 字面量形态（含 `'` 转义）= 模板里 `source_system` 表达式应有的样子 */
  private val SrcSysLiteral = "'mock-mall'"

  private case class Rendered(table: String, sql: String)

  private val rendered: Seq[Rendered] = Seq(
    Rendered("ods_user_event", OdsLoadSql.userFromLanding(ns, SrcSys, batchId)),
    Rendered("ods_product_event", OdsLoadSql.productFromLanding(ns, SrcSys, batchId)),
    Rendered("ods_behavior_event", OdsLoadSql.behaviorFromLanding(ns, SrcSys, batchId)),
    Rendered("ods_trade_event", OdsLoadSql.tradeFromLanding(ns, SrcSys, batchId))
  )

  "OdsLoadSql" should "A9 四个模板的 SELECT 表达式与目标表列逐位对齐（数据列 + dt/hour 分区派生列）" in {
    rendered.foreach { case Rendered(table, sql) =>
      withClue(s"$table: ") {
        val selected = OdsSqlText.selectExpressions(sql)
        // 全 SELECT 列表 = dataColumns ++ PartitionColumns（唯一所有者序）。
        // 断言对象必须含末尾两个分区派生列：`INSERT OVERWRITE … PARTITION (dt, hour)` 要求
        // SELECT 恰好给出「数据列 + 分区列」，多一列/少一列/错序都是**静默**数据错（D-059）。
        selected.size should be(OdsV2Columns.allColumns(table).size)
        // 数据列段（去掉末尾 dt/hour）逐位等于 dataColumns
        OdsSqlText.dataExpressions(sql).map(OdsSqlText.aliasOf) should be(
          OdsV2Columns.dataColumns(table).map(_.name))
        // 分区列段：末尾恰好是 dt/hour，且顺序与所有者一致（防未来重排）
        selected.takeRight(OdsV2Columns.PartitionColumns.size).map(OdsSqlText.aliasOf) should be(
          OdsV2Columns.PartitionColumns.map(_.name))
      }
    }
  }

  it should "A9b 常量 'landing' AS source_file 已消失，source_file 与 landing_file 同源同值" in {
    rendered.foreach { case Rendered(table, sql) =>
      withClue(s"$table: ") {
        sql.toLowerCase should not include "'landing' as source_file"
        sql should not include "'landing'"
        // source_file 与 landing_file 必须同源（D-057：source_file == landing_file）。
        // 比的是**表达式本体**：`AS <别名>` 后缀不属于表达式，而 `renderSelect` 只在
        // 表达式与列名不同时才补 `AS <列名>`（`OdsLoadSql.scala:154-158`），
        // 所以直接比 SELECT 项原文会把「别名差异」误判成「取值来源差异」。
        val exprs = OdsSqlText.selectExpressions(sql)
        val byAlias = exprs.map(e => OdsSqlText.aliasOf(e) -> e).toMap
        OdsSqlText.expressionOf(byAlias("source_file")).toLowerCase should be(
          OdsSqlText.expressionOf(byAlias("landing_file")).toLowerCase)
        // 本体必须是视图里的真实落地文件**列引用**，不得是任何常量字面量
        OdsSqlText.expressionOf(byAlias("landing_file")) should be(OdsLoadSql.ColLandingFile)
        OdsSqlText.expressionOf(byAlias("landing_file")).trim should not startWith "'"
        // `_metadata.file_path`（D-060 已实测非空、`input_file_name()` 返回空串）是**作业层**事实：
        // 模板的 FROM 是临时视图 `landing_valid`（`OdsLoadSql.scala:16`「模板 FROM 视图而非
        // json.`path`」），视图上没有 `_metadata` 伪列可引用 ⇒ 该取值只能由作业侧
        // `EventOdsLoadJob.scala:65` 落进 `landing_file` 列，其**行为**断言在 A8
        //（`OdsV2ByteFidelitySpec`：landing_file 含真实文件名，非常量）。
      }
    }
  }

  it should "A10 source_system 来自平台注入值（不是行内值），行内原值只落 raw_source_system" in {
    rendered.foreach { case Rendered(table, sql) =>
      withClue(s"$table: ") {
        val exprs = OdsSqlText.selectExpressions(sql)
        val byAlias: Map[String, String] = exprs.map(e => OdsSqlText.aliasOf(e) -> e).toMap
        // 表达式本体（去掉 `AS source_system` 后缀）才是「取值来源」
        val sourceSystemExpr = OdsSqlText.expressionOf(byAlias("source_system"))
        val rawSourceSystemExpr = OdsSqlText.expressionOf(byAlias("raw_source_system"))
        sourceSystemExpr should be(SrcSysLiteral)
        rawSourceSystemExpr.toLowerCase should be("source_system")
        // 契约列取的是**字面量**，不是任何列引用（既不是行内 source_system，也不是别的列）
        sourceSystemExpr should fullyMatch regex """'[^']*'"""
        sourceSystemExpr.equals("source_system") should be(false)
      }
    }
  }

  it should "A10b 注入值经 SQL 字面量渲染并做 ' 转义（值来自平台，不得拼出可注入 SQL）" in {
    val sneaky = "src'; DROP TABLE x; --"
    val rendered2 = OdsLoadSql.userFromLanding(ns, sneaky, batchId)
    val exprs2: Seq[String] = OdsSqlText.selectExpressions(rendered2)
    val byAlias2: Map[String, String] = exprs2.map(e => OdsSqlText.aliasOf(e) -> e).toMap
    // 表达式本体 = 单个字面量，注入值里的 ' 必须被**加倍**（否则字面量提前闭合）
    OdsSqlText.expressionOf(byAlias2("source_system")) should be("'src''; DROP TABLE x; --'")
    // 真正的注入判定：把字面量整体摘掉后，可执行骨架里不得残留任何注入 token。
    // （不能用「SQL 文本里不含 drop table」这类朴素子串检查：注入值原样保留在字面量里，
    //   那种检查与「值必须保真」直接冲突，且拦不住真正的裸拼接。）
    val skeleton = OdsSqlText.stripStringLiterals(rendered2)
    skeleton.toLowerCase should not include "drop table"
    skeleton should not include ";"
    skeleton should not include "--"
    // 转义函数的独立小样例（手写期望值，不复述实现输出）
    OdsLoadSql.sourceSystemLiteral("a'b") should be("'a''b'")
    OdsLoadSql.sourceSystemLiteral("a''b") should be("'a''''b'")
    // 空/空白注入值必须直接报错（不是静默退回上一个值、也不是信任行内值）
    an[IllegalArgumentException] should be thrownBy OdsLoadSql.userFromLanding(ns, "", batchId)
    an[IllegalArgumentException] should be thrownBy OdsLoadSql.userFromLanding(ns, "   ", batchId)
    an[IllegalArgumentException] should be thrownBy OdsLoadSql.userFromLanding(ns, null, batchId)
  }

  it should "A9c raw_event_type 为行内 event_type 原样值，payload_json/payload_hash 由原样切片产出" in {
    rendered.foreach { case Rendered(table, sql) =>
      withClue(s"$table: ") {
        val byAlias = OdsSqlText.selectExpressions(sql)
          .map(e => OdsSqlText.aliasOf(e) -> e).toMap
        // 同样比表达式本体：`event_type AS raw_event_type` 的取值来源就是 `event_type`
        OdsSqlText.expressionOf(byAlias("raw_event_type")).toLowerCase should be("event_type")
        OdsSqlText.expressionOf(byAlias("payload_json")).toLowerCase should be(
          s"${OdsLoadSql.UDF_SLICE_PAYLOAD}(${OdsLoadSql.ColRawLine})".toLowerCase)
        OdsSqlText.expressionOf(byAlias("payload_hash")).toLowerCase should be(
          s"${OdsLoadSql.UDF_SHA256_HEX}(${OdsLoadSql.ColRawLine})".toLowerCase)
        // 原样切片的**输入必须是原始整行**，不是二次序列化产物
        OdsSqlText.expressionOf(byAlias("payload_json")).toLowerCase should include(
          OdsLoadSql.ColRawLine)
        // 明确禁止重序列化路径：不得用 to_json 造 payload_json
        sql.toLowerCase should not include "to_json"
        sql.toLowerCase should not include "get_json_object"
      }
    }
  }

  it should "A9d INSERT 目标列与 SELECT 表达式的表名/分区子句保持改造前形态" in {
    rendered.foreach { case Rendered(table, sql) =>
      val lower = sql.toLowerCase
      withClue(s"$table: ") {
        lower should include(s"insert overwrite table ${ns.ods}.$table partition (dt, hour)")
        lower should include("schema_version = '1.0'")
        lower should include("event_id is not null and event_time is not null")
        lower should include(s"$batchId as ingest_batch_id")
        lower should include("regexp_replace(substr(event_time, 1, 10), '-', '') as dt")
        lower should include("substr(event_time, 12, 2) as hour")
      }
    }
  }

  it should "A9e 四张表的业务列表达式仍从 payload 结构体取，v1 口径未变" in {
    val product = OdsSqlText.selectExpressions(OdsLoadSql.productFromLanding(ns, SrcSys, batchId))
    val trade = OdsSqlText.selectExpressions(OdsLoadSql.tradeFromLanding(ns, SrcSys, batchId))
    product.mkString(" ") should include("CAST(payload.price AS DECIMAL(18,2))")
    product.mkString(" ") should include("CAST(payload.cost AS DECIMAL(18,2))")
    trade.mkString(" ") should include("CAST(payload.amount AS DECIMAL(18,2))")
    trade.mkString(" ") should include("CAST(payload.total_amount AS DECIMAL(18,2))")
    trade.mkString(" ") should include("payload.items")
  }

  it should "A12b 既有 DWD 模板一个都不引用 v2 新增列（加法扩列不牵连下游）" in {
    val v2Only = Seq("payload_json", "payload_hash", "landing_file", "raw_event_type", "raw_source_system")
    val dwdTexts = Seq(
      "dwd_reject_record" -> DwdSql.duplicateReject(ns, "20260901"),
      "dwd_user_behavior_detail" -> DwdSql.behaviorClean(ns, "20260901"))
    dwdTexts.foreach { case (label, sql) =>
      v2Only.foreach { col =>
        withClue(s"$label 不得引用 $col: ") { sql.toLowerCase should not include col }
      }
    }
  }
}

/**
 * ODS SQL 文本的极简解析器：只服务于「列契约」断言。
 * 认 `INSERT OVERWRITE TABLE <t> PARTITION (…) SELECT <表达式…> FROM …` 这一种形态。
 */
object OdsSqlText {

  /**
   * SELECT 列表的表达式序列（顶层逗号切分；别名原样保留）。
   *
   * **止于 `FROM`**：模板在 SELECT 列表之后不再有别的子句，分区派生表达式 `dt`/`hour` 也在
   * 同一列表里（`FROM $LANDING_VIEW` 之后才是 WHERE）——所以这里以最后一个 `\nFROM ` 为界，
   * 不是第一个（表达式里可能出现 `from` 之外的同名子串）。
   */
  def selectExpressions(sql: String): Seq[String] = {
    val lower = sql.toLowerCase
    val selectIdx = lower.indexOf("select")
    val fromIdx = lower.lastIndexOf("\nfrom ")
    require(selectIdx >= 0 && fromIdx > selectIdx, s"无法解析 SELECT 列表：${sql.take(120)}…")
    splitTopLevel(sql.substring(selectIdx + "select".length, fromIdx))
      .map(_.trim)
      .filter(_.nonEmpty)
  }

  /** SELECT 列表里的**数据列**表达式（去掉 dt/hour 两个分区派生列） */
  def dataExpressions(sql: String): Seq[String] = {
    val all = selectExpressions(sql)
    val partitionCount = 2 // dt, hour
    all.dropRight(partitionCount)
  }

  /** 取 `AS <alias>`（大小写不敏感）；无别名时返回表达式本身 */
  def aliasOf(expr: String): String = {
    // `.*?` 必须非贪婪：表达式自身可能含 `AS`（如 `CAST(x AS DECIMAL(18,2))`），
    // 贪婪匹配会把别名之外的尾巴也吞进表达式
    val m = """(?is)^(.*?)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)$""".r
    expr.trim match {
      case m(_, alias) => alias
      case other => other.trim
    }
  }

  /**
   * `aliasOf` 的逆：取 `AS <alias>` **之前**的表达式本体（无别名时返回表达式本身）。
   *
   * 为什么必须有它：`renderSelect` 只在「表达式 ≠ 列名」时才补 `AS <列名>`
   * （`OdsLoadSql.scala:154-158`），所以同一个取值来源会渲染成两种文本
   * （`landing_file` vs `landing_file AS source_file`）。要断言「取值来源相同」
   * 就必须比本体，否则断言测的是渲染细节而不是契约语义。
   */
  def expressionOf(expr: String): String = {
    val m = """(?is)^(.*?)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)$""".r
    expr.trim match {
      case m(body, _) => body.trim
      case other => other.trim
    }
  }

  /**
   * 摘掉 SQL 里的单引号字面量（整体替换为空字面量 `''`；`''` 视为转义引号，不结束字面量），
   * 返回**可执行骨架**。
   *
   * 用途：判定「注入值是否逃出字面量」——正确转义时骨架里什么注入 token 都不剩；
   * 若实现把注入值裸拼进 SQL，骨架里会留下 `; DROP TABLE …` 这类可执行片段。
   * 朴素子串检查（「SQL 里不含 drop table」）做不到这一点：注入值本来就要原样保留在字面量内。
   */
  def stripStringLiterals(sql: String): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < sql.length) {
      if (sql.charAt(i) != '\'') {
        sb.append(sql.charAt(i)); i += 1
      } else {
        sb.append("''") // 字面量 → 空字面量（保留占位，其余结构不变）
        i += 1
        var closed = false
        while (i < sql.length && !closed) {
          if (sql.charAt(i) == '\'' && i + 1 < sql.length && sql.charAt(i + 1) == '\'') i += 2
          else if (sql.charAt(i) == '\'') { i += 1; closed = true }
          else i += 1
        }
      }
    }
    sb.toString
  }

  /** 顶层逗号切分（括号/引号内不切） */
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
        case '(' | '[' => depth += 1; sb.append(c)
        case ')' | ']' => depth -= 1; sb.append(c)
        case ',' if depth == 0 => out += sb.toString; sb.clear()
        case other => sb.append(other)
      }
      i += 1
    }
    if (sb.toString.trim.nonEmpty) out += sb.toString
    out.toSeq
  }
}
