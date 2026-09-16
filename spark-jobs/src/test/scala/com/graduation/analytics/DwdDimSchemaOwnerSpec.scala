package com.graduation.analytics

import com.graduation.analytics.job.{LocalSchemaInitJob, TradeDwdJob}
import com.graduation.analytics.sql.{DimSql, DwdSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * S3-13：DWD / DIM 物理表形的**三方一致**守卫（不需要 Spark）。
 *
 * 与 S3-11（`DwsSchemaOwnerSpec`）同族、同型：把「参考副本」「唯一所有者」「写入投影」三份之间的
 * 漂移钉在编译后的测试里。本类只覆盖 **DWD 3 张 + DIM 2 张有所有者的表**（DWS/ODS/ADS 各有自己的
 * 守卫或已登记缺口，不在此重复）。
 *
 * 本类钉六件事：
 *  1. 参考副本 `warehouse/ddl/01-dwd.sql`（3 张）与 `02-dims.sql`（其中 2 张）↔ 唯一所有者
 *     `LocalSchemaInitJob.statements`：列**名/类型/顺序**逐列一致，分区列一致；
 *  2. 两份参考副本都不得用 `ALTER TABLE` 旁路加列（否则「唯一所有者」断言可被绕过）；
 *  3. 写入投影（`DwdSql` / `DimSql` / `TradeDwdJob` 的 `INSERT OVERWRITE … SELECT` 列表）↔ 所有者**列序**一致；
 *     Spark 按**位置**写 Parquet，投影与 DDL 列序只要差一位就是「值串列」的静默错数；
 *  4. `dwd_order_detail` 的**动态分区**形态：分区列 `dt` 必须落在 SELECT 列表**最末位**
 *     （`TradeDwdJob` L128-148 记录的 run 44/45/46 连续失败真根因就是这个位置），并附**守卫自检**
 *     （把 `dt` 挪到非末位时同一断言必须红 —— 否则本守卫是空的）；
 *  5. 一份**冻结快照** `Frozen`：防止"三份一起漂"（例如三处同时漏加一列）时全绿；
 *  6. DIM 的**参考副本已声明、所有者不建**的 3 张表（`dim_date`/`dim_region`/`dim_metric`）列为
 *     **显式白名单**，并断言它们既无所有者、也无写入投影 —— 白名单一旦漂移立刻红。
 *
 * 归因边界（不得越界表述）：本类只判**表形/列序/写入目标**，不判任何口径、不判任何指标值、不判
 * 真实 Hive 上的物理落盘（本地链与真 Hive 不等价，见 `P2TestSupport` 自陈的测试域）；也不断言
 * 白名单里那 3 张 DIM 表"应当有数据"——它们缺生产链的事实登记在
 * `docs/status-history/项目实施进度与任务看板.md:437` 与 `docs/audit/v2-completeness-audit.md:133-135`，
 * 本类只保证"没人偷偷建一半"。
 *
 * 加列/改列是**有意为之**的变更 ⇒ 必须同批改所有者＋参考副本＋写入投影，并**有意**更新 `Frozen`。
 */
class DwdDimSchemaOwnerSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace
  private val dt = "20260901"
  private val srcSys = "mock-mall"
  private val dwdDdlRelative = "warehouse/ddl/01-dwd.sql"
  private val dimDdlRelative = "warehouse/ddl/02-dims.sql"

  /** 写入语句 = 物理写入的**列序真相**（Spark 按位置落 Parquet） */
  private val writes: Seq[(String, String)] = Seq(
    "dwd_user_behavior_detail" -> DwdSql.behaviorClean(ns, dt),
    "dwd_reject_record" -> DwdSql.duplicateReject(ns, dt),
    "dwd_order_detail" -> TradeDwdJob.orderDetailInsertSql(ns, dt, srcSys),
    "dim_user" -> DimSql.userSnapshot(ns, dt),
    "dim_product" -> DimSql.productSnapshot(ns, dt)
  )

  /**
   * S3-13 冻结快照（列名 → 类型，逐表按序）＝ 本轮实测的三方一致值（不含 `dt` 分区列）。
   *
   * 它是**独立的第四份**：只在下述情况红 —— 有人**同时**改了所有者＋参考副本＋写入投影
   * （剩下三份互等，前几个用例全绿），此时必须**显式**改这里，逼一次"这确实是有意的表形变更"的判断。
   * 依据：`LocalSchemaInitJob.statements` + `warehouse/ddl/01-dwd.sql` / `02-dims.sql` 逐列实测。
   */
  private val Frozen: Map[String, Seq[(String, String)]] = Map(
    "dwd_user_behavior_detail" -> Seq(
      "behavior_id" -> "STRING", "user_id" -> "BIGINT", "product_id" -> "BIGINT",
      "category_id" -> "BIGINT", "behavior_type" -> "STRING", "event_time" -> "TIMESTAMP",
      "event_date" -> "STRING", "event_hour" -> "INT", "city_level" -> "STRING",
      "channel" -> "STRING", "session_id" -> "STRING", "source_batch_id" -> "BIGINT",
      "user_key" -> "BIGINT", "product_key" -> "BIGINT", "category_key" -> "BIGINT"),
    "dwd_reject_record" -> Seq(
      "reject_id" -> "STRING", "source_table" -> "STRING", "reject_reason" -> "STRING",
      "raw_payload" -> "STRING", "reject_time" -> "TIMESTAMP"),
    "dwd_order_detail" -> Seq(
      "order_id" -> "BIGINT", "user_id" -> "BIGINT", "product_id" -> "BIGINT",
      "category_id" -> "BIGINT", "quantity" -> "INT", "unit_price" -> "DECIMAL(18,2)",
      "discount" -> "DECIMAL(18,2)", "amount" -> "DECIMAL(18,2)", "order_status" -> "STRING",
      "order_time" -> "TIMESTAMP", "order_date" -> "STRING", "city_level" -> "STRING",
      "paid_at" -> "TIMESTAMP", "order_amount" -> "DECIMAL(18,2)",
      "paid_amount" -> "DECIMAL(18,2)", "refund_amount" -> "DECIMAL(18,2)",
      "net_paid_amount" -> "DECIMAL(18,2)", "final_paid_flag" -> "INT",
      "final_refunded_flag" -> "INT", "user_key" -> "BIGINT", "product_key" -> "BIGINT",
      "category_key" -> "BIGINT"),
    "dim_user" -> Seq(
      "user_id" -> "BIGINT", "age_group" -> "STRING", "city_level" -> "STRING",
      "member_level" -> "STRING", "register_date" -> "STRING", "register_time" -> "TIMESTAMP",
      "source_batch_id" -> "BIGINT", "user_key" -> "BIGINT"),
    "dim_product" -> Seq(
      "product_id" -> "BIGINT", "product_name" -> "STRING", "category_id" -> "BIGINT",
      "category_name" -> "STRING", "parent_category_id" -> "BIGINT",
      "parent_category_name" -> "STRING", "brand_id" -> "BIGINT", "price" -> "DECIMAL(18,2)",
      "cost" -> "DECIMAL(18,2)", "status" -> "STRING", "source_batch_id" -> "BIGINT",
      "product_key" -> "BIGINT", "category_key" -> "BIGINT",
      "parent_category_key" -> "BIGINT", "brand_key" -> "BIGINT")
  )

  /**
   * 参考副本**已声明**、唯一所有者**不建**、且全仓 main 源码**零写入**的 DIM 表 —— 显式白名单。
   *
   * 事实（实测，非推测）：`warehouse/ddl/02-dims.sql:49-83` 声明了这 3 张表，而
   * `LocalSchemaInitJob.statements` 的 `ns.dim` 名下只有 `dim_user`/`dim_product` 两条建表语句；
   * 设计 §9.2 **L309** 要求这三张表"日期区间幂等；地区未知成员；指标字典同步，**不只建空表**"，
   * 同节 **L322** 逐字写着"已知DIM仅user/product有日常产出…需逐项补证"。
   * 本轮**只登记不实现**（补生产链涉及分区/版本/同步方式等未决口径，须走设计差异裁定），
   * 故用白名单把"参考副本多出来 3 张"这件事**写死**：任何一张被删、被建、或被写入，本类立即红。
   */
  private val UnownedDimTables: Set[String] = Set("dim_date", "dim_region", "dim_metric")

  // ── 夹具 ────────────────────────────────────────────────────────────────

  private def readRepoFile(relative: String): String = {
    val path: Path = P2TestSupport.repoRoot.resolve(relative)
    P2TestSupport.requireNonEmpty(path)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  private def dwdRef: StaticOdsDdl.Parsed = StaticOdsDdl.parse(readRepoFile(dwdDdlRelative))
  private def dimRef: StaticOdsDdl.Parsed = StaticOdsDdl.parse(readRepoFile(dimDdlRelative))

  /**
   * 唯一所有者：`LocalSchemaInitJob` 里 namespace = `ns.dwd` / `ns.dim` 的**建表**语句。
   *
   * 必须按 `CREATE TABLE` 过滤：同名空间下还有 `CREATE DATABASE IF NOT EXISTS …` 一条
   * （S3-11 RED 首轮的夹具缺陷），不过滤会让夹具红在**自己**身上。
   */
  private def ownerStatements(namespace: String): Seq[String] = {
    val all = LocalSchemaInitJob.statements(ns)
      .filter(_._1 == namespace).map(_._2).filter(_.contains("CREATE TABLE"))
    all.size should be > 0
    all
  }

  private def ownerDwd: StaticOdsDdl.Parsed =
    StaticOdsDdl.parse(ownerStatements(ns.dwd).mkString("\n;\n"))

  private def ownerDim: StaticOdsDdl.Parsed =
    StaticOdsDdl.parse(ownerStatements(ns.dim).mkString("\n;\n"))

  private def ownerAll: StaticOdsDdl.Parsed =
    StaticOdsDdl.parse(
      (ownerStatements(ns.dwd) ++ ownerStatements(ns.dim)).mkString("\n;\n"))

  private def columnSeqOf(parsed: StaticOdsDdl.Parsed, table: String): Seq[(String, String)] =
    withClue(s"$table: ") { parsed.columnSeq(table) }

  /** 有所有者的 DWD 表集 / DIM 表集（由冻结快照的键推导，且必须先自检与所有者一致） */
  private def ownedTables: Set[String] = Frozen.keys.toSet

  /**
   * 剥掉 `--` 行注释（逐行截断到第一个 `--`）。局限（如实声明）：不识别字符串字面量里的 `--`
   * （本仓库这两种 DDL 与 `TradeDwdJob` 的动态分区投影里都没有这种写法）；若将来出现，本守卫会
   * **偏严**（把字面量当注释切掉）⇒ 宁可偏严，也不放过真语句。
   */
  private def stripLineComments(sql: String): String =
    sql.linesIterator.map { line =>
      val at = line.indexOf("--")
      if (at >= 0) line.substring(0, at) else line
    }.mkString("\n")

  /** 写入投影 ↔ 所有者：列序、分区列名、写入目标三对齐（B1/B2 共用的唯一判定点） */
  private def checkProjection(table: String, w: DmlWriteProjection.Write): Unit = {
    val owner = ownerAll
    val ownerCols = columnSeqOf(owner, table).map(_._1)
    val ownerParts = owner.partitions(table).map(_._1)
    withClue(s"$table 写入投影与所有者不一致: ") {
      w.table should be(table)
      // 分区列名：静态分区（带 = 值）与动态分区（无值）都必须与所有者声明的分区列同名同序
      (w.staticPartitions.map(_._1) ++ w.dynamicPartitions) should be(ownerParts)
      w.staticPartitions.foreach { case (name, value) =>
        withClue(s"$table 静态分区 $name 的值应为字符串字面量: ") {
          value should fullyMatch regex """'[^']*'"""
        }
      }
      // 动态分区列必须由 SELECT 提供（Spark 要求它落在列表里），因此投影 = 数据列 ++ 动态分区列
      w.columns should be(ownerCols ++ w.dynamicPartitions)
    }
  }

  /** 全仓 main 源码里的 `INSERT OVERWRITE TABLE` 目标表 → 出现次数（B3 的"第二所有者"扫描） */
  private def insertTargetCounts: Map[String, Int] = {
    val root: Path = P2TestSupport.repoRoot.resolve("spark-jobs/src/main/scala")
    val stream = Files.walk(root)
    val files =
      try {
        stream.toArray.map(_.asInstanceOf[Path])
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
          .toSeq
      } finally stream.close()
    // 夹具自检：扫描面若因目录搬迁落空，必须响，不能静默给出"零命中"的全绿
    files.size should be > 0
    files.flatMap { p =>
      val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
      DmlWriteProjection.insertTargets(text)
    }.groupBy(identity).map { case (t, hits) => t -> hits.size }
  }

  // ── 1. 参考副本 ↔ 唯一所有者 ───────────────────────────────────────────

  it should "A1 参考副本 01-dwd.sql 的 3 张 DWD 表与唯一所有者逐列一致（列名/类型/顺序）" in {
    val ddl = dwdRef
    val owner = ownerDwd
    val tables = Frozen.keys.filter(_.startsWith("dwd_")).toSet
    tables should be(ddl.columns.keySet)
    tables.foreach { table =>
      withClue(s"$table 参考副本与所有者不一致: ") {
        columnSeqOf(ddl, table) should be(columnSeqOf(owner, table))
      }
    }
  }

  it should "A2 参考副本 02-dims.sql 中有所有者的 2 张 DIM 表与唯一所有者逐列一致" in {
    val ddl = dimRef
    val owner = ownerDim
    val tables = Frozen.keys.filter(_.startsWith("dim_")).toSet
    tables.foreach { table =>
      withClue(s"$table 参考副本与所有者不一致: ") {
        columnSeqOf(ddl, table) should be(columnSeqOf(owner, table))
      }
    }
  }

  it should "A3 两份参考副本与所有者的分区列一致（都是 dt STRING），且分区列不得混进普通列" in {
    val dwdDdl = dwdRef
    val dimDdl = dimRef
    val owner = ownerAll
    Frozen.keys.foreach { table =>
      val ddl = if (table.startsWith("dwd_")) dwdDdl else dimDdl
      withClue(s"$table: ") {
        ddl.partitions(table) should be(Seq("dt" -> "STRING"))
        owner.partitions(table) should be(Seq("dt" -> "STRING"))
        ddl.columnSeq(table).map(_._1) should not contain "dt"
        owner.columnSeq(table).map(_._1) should not contain "dt"
      }
    }
  }

  it should "A4 两份参考副本都不得用 ALTER TABLE 旁路加列（否则「唯一所有者」断言可被绕过）" in {
    // 只扫**语句**：先剥 `--` 行注释（两份 DDL 头部都逐字写着"不得用 ALTER TABLE 旁路"这类提示，
    // 不剥注释会把自己的提示当成违规 —— S3-11 GREEN 首轮即如此，属守卫实现缺陷，非文档缺陷）。
    Seq(dwdDdlRelative -> dwdRefFileStripped, dimDdlRelative -> dimRefFileStripped).foreach {
      case (relative, statementsOnly) =>
        withClue(s"$relative 出现 ALTER TABLE 语句：") {
          "(?is)\\bALTER\\s+TABLE\\b".r.findFirstIn(statementsOnly) should be(None)
        }
    }
  }

  private def dwdRefFileStripped: String = stripLineComments(readRepoFile(dwdDdlRelative))
  private def dimRefFileStripped: String = stripLineComments(readRepoFile(dimDdlRelative))

  // ── 2. 写入投影 ↔ 唯一所有者 ───────────────────────────────────────────

  it should "B1 写入投影（DwdSql/DimSql/TradeDwdJob 的 INSERT … SELECT 列序）与所有者逐表一致" in {
    writes.foreach { case (expectedTable, sql) =>
      checkProjection(expectedTable, DmlWriteProjection.parse(sql))
    }
  }

  it should "B2 动态分区写入的分区列必须落在 SELECT 最末位，且错序必须被本守卫检出（守卫自检）" in {
    val orderDetail = DmlWriteProjection.parse(
      writes.toMap.apply("dwd_order_detail"))
    withClue("dwd_order_detail 动态分区列: ") {
      orderDetail.dynamicPartitions should be(Seq("dt"))
      orderDetail.columns.last should be("dt")
    }
    // 守卫自检：把 dt 从末位挪到第 2 项（同类型仍可解析、Spark 也可能照写）⇒ 同一判定必须红。
    val misplaced =
      """|INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt)
         |SELECT a AS order_id, t.dt, b AS user_key FROM t""".stripMargin
    val bad = DmlWriteProjection.parse(misplaced)
    an[TestFailedException] should be thrownBy checkProjection("dwd_order_detail", bad)
  }

  it should "B3 每张有所有者的 DWD/DIM 表在全仓 main 源码里恰好一条 INSERT OVERWRITE（无第二所有者）" in {
    val counts = insertTargetCounts
    ownedTables.toSeq.sorted.foreach { table =>
      withClue(s"$table 的 INSERT OVERWRITE 条数: ") {
        counts.getOrElse(table, 0) should be(1)
      }
    }
    // 白名单里的 3 张表必须**零写入**（有 DDL 无生产链是登记事实，但不能有人偷偷写一半）
    UnownedDimTables.toSeq.sorted.foreach { table =>
      withClue(s"$table 不应有写入语句: ") {
        counts.getOrElse(table, 0) should be(0)
      }
    }
  }

  // ── 3. 冻结快照与表集 ──────────────────────────────────────────────────

  it should "C1 所有者列名/类型逐表等于 S3-13 冻结快照（防「三份一起漂」）" in {
    val owner = ownerAll
    Frozen.foreach { case (table, expected) =>
      withClue(s"$table 与冻结快照不一致: ") {
        columnSeqOf(owner, table) should be(expected)
      }
    }
  }

  it should "C2 参考副本已声明但所有者不建的 DIM 表恰好等于显式白名单，且这 3 张无所有者" in {
    val ddlTables = dimRef.columns.keySet
    val dimOwned = Frozen.keys.filter(_.startsWith("dim_")).toSet
    withClue("02-dims.sql 的表集与「所有者表集 + 白名单」不一致: ") {
      ddlTables should be(dimOwned ++ UnownedDimTables)
    }
    val ownerTables = ownerDim.columns.keySet
    UnownedDimTables.foreach { table =>
      withClue(s"$table 不应有所有者: ") { ownerTables should not contain table }
    }
  }

  it should "C3 所有者 DWD/DIM 表集等于冻结快照表集（不许多表、不许漏表）" in {
    val ownerTables = ownerDwd.columns.keySet ++ ownerDim.columns.keySet
    withClue("所有者表集与冻结快照表集不一致: ") {
      ownerTables should be(Frozen.keys.toSet)
    }
  }
}

/**
 * DWD/DIM 写入语句的**投影列序**解析器（S3-13 反熵守卫的夹具，不是生产代码）。
 *
 * 与 S3-11 的 `DwsWriteProjection` 的差异（刻意的，不是遗漏）：
 *  - 支持**动态分区** `PARTITION (dt)`：分区列由 SELECT 提供，必须落在列表末位；
 *  - `AS` 别名识别必须**区分括号层级** —— `CAST(t.quantity AS INT)` 里的 `AS` 在括号内，
 *    朴素的"末尾 AS 正则"会把它当成别名 `INT`（这正是 P2-03 位置错位那一类静默错数的入口），
 *    故本解析器只在**顶层**找 `AS`，末尾不是裸标识符就**抛异常**，绝不猜。
 *
 * 只认本项目已知形态：`INSERT OVERWRITE TABLE <库>.<表> [PARTITION(…)] SELECT <列表> FROM …`。
 * 列表按**顶层逗号**切（`DECIMAL(18,2)`/`CASE … END` 里的逗号与括号不切），每个元素取顶层 `AS <别名>`；
 * 无 `AS` 时只接受**纯列引用**（`t.unit_price` / `product_id`）取最后一段。认不出的形态直接抛异常
 * —— 守卫宁可响，也不许把解析落空当成"零列一致"。
 */
object DmlWriteProjection {

  final case class Write(table: String,
                         columns: Seq[String],
                         staticPartitions: Seq[(String, String)],
                         dynamicPartitions: Seq[String])

  private val insertRe = """(?is)INSERT\s+OVERWRITE\s+TABLE\s+([^\s(]+)""".r
  private val partitionRe = """(?is)^\s*PARTITION\s*\(([^)]*)\)""".r
  private val aliasRe = """^[A-Za-z_][A-Za-z0-9_]*$""".r
  private val plainRefRe = """^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""".r

  /** 扫描一段源码文本里所有 `INSERT OVERWRITE TABLE` 的目标裸表名（B3 的"第二所有者"扫描） */
  def insertTargets(sourceText: String): Seq[String] =
    insertRe.findAllMatchIn(sourceText).map(m => bareTable(m.group(1))).toSeq

  def parse(sql: String): Write = {
    val cleaned = stripLineComments(sql)
    val insert = insertRe.findFirstMatchIn(cleaned).getOrElse(
      throw new IllegalArgumentException("写入语句里找不到 INSERT OVERWRITE TABLE"))
    val table = bareTable(insert.group(1))

    // 分区子句：紧跟在表名后（`PARTITION (dt)` 动态 / `PARTITION(dt = '20260901')` 静态）
    val afterTable = cleaned.substring(insert.end)
    val static = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val dynamic = scala.collection.mutable.ListBuffer.empty[String]
    var cursor = insert.end
    partitionRe.findFirstMatchIn(afterTable) match {
      case Some(pm) if afterTable.substring(0, pm.start).trim.isEmpty =>
        splitTopLevel(pm.group(1)).foreach { item =>
          val eq = item.indexOf('=')
          if (eq >= 0) static += (item.substring(0, eq).trim -> item.substring(eq + 1).trim)
          else dynamic += item.trim
        }
        cursor += pm.end
      case _ =>
    }

    val selectAt = indexOfKeyword(cleaned, "SELECT", cursor)
    require(selectAt >= 0, s"$table: INSERT 之后找不到 SELECT")
    val fromAt = indexOfKeyword(cleaned, "FROM", selectAt + "SELECT".length)
    require(fromAt >= 0, s"$table: SELECT 列表之后找不到顶层 FROM")

    val body = cleaned.substring(selectAt + "SELECT".length, fromAt)
    val columns = splitTopLevel(body).map(item => columnOf(table, item))
    require(columns.nonEmpty, s"$table: 投影解析出 0 列")
    require(dynamic.isEmpty || static.isEmpty,
      s"$table: 同一语句里混用静态与动态分区（本项目无此形态）：[$body]")
    Write(table, columns, static.toSeq, dynamic.toSeq)
  }

  private def bareTable(qualified: String): String =
    qualified.substring(qualified.lastIndexOf('.') + 1).stripPrefix("`").stripSuffix("`")

  /** 归一化单个投影元素 → 列名 */
  private def columnOf(table: String, raw: String): String = {
    val item = raw.replaceAll("\\s+", " ").trim.stripPrefix("|").trim
    topLevelAlias(item) match {
      case Some(alias) => alias
      case None if plainRefRe.pattern.matcher(item).matches() =>
        item.substring(item.lastIndexOf('.') + 1)
      case _ =>
        // 失败信息必须带上**肇事元素本身**：RED 首轮曾误写 `case other => …[$other]`，
        // 而 `other` 绑定的是 `None`（匹配值），输出成 `[None]` —— 等于把线索丢了（夹具缺陷，已修）。
        throw new IllegalArgumentException(
          s"$table: 投影元素既无顶层 AS 别名也不是纯列引用：[$item]")
    }
  }

  /** 找**顶层**（括号外、字符串外）最后一个 `AS <裸标识符>`；括号内的 `AS`（如 `CAST(x AS INT)`）不算 */
  private def topLevelAlias(item: String): Option[String] = {
    var depth = 0
    var inStr = false
    var lastAs = -1
    var i = 0
    while (i < item.length) {
      val c = item.charAt(i)
      if (inStr) {
        if (c == '\'') {
          if (i + 1 < item.length && item.charAt(i + 1) == '\'') i += 1 else inStr = false
        }
      } else if (c == '\'') inStr = true
      else if (c == '(') depth += 1
      else if (c == ')') depth -= 1
      else if (depth == 0 && regionMatchesKeyword(item, i, "AS")) lastAs = i
      i += 1
    }
    if (lastAs < 0) None
    else {
      val tail = item.substring(lastAs + 2).trim
      if (aliasRe.pattern.matcher(tail).matches()) Some(tail) else None
    }
  }

  /** 剥 `--` 行注释（逐行截断；局限见 `DwdDimSchemaOwnerSpec.stripLineComments` 的同名说明） */
  private def stripLineComments(sql: String): String =
    sql.linesIterator.map { line =>
      val at = line.indexOf("--")
      if (at >= 0) line.substring(0, at) else line
    }.mkString("\n")

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
