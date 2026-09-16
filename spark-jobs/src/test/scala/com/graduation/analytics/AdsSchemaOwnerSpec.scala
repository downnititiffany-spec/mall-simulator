package com.graduation.analytics

import com.graduation.analytics.job.LocalSchemaInitJob
import com.graduation.analytics.sql.AdsSql
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * S3-14：ADS 物理表形的**三方一致**守卫（不需要 Spark）。
 *
 * 与 S3-11（`DwsSchemaOwnerSpec`）、S3-13（`DwdDimSchemaOwnerSpec`）同族同型：把「参考副本」
 * 「唯一所有者」「写入投影」三份之间的漂移钉在编译后的测试里。至此四层（ODS / DWD+DIM / DWS / ADS）
 * 各有一份表形守卫（ODS 见 `OdsV2SchemaOwnerSpec`）。
 *
 * 本类钉六件事：
 *  1. 参考副本 `warehouse/ddl/04-ads.sql` ↔ 唯一所有者 `LocalSchemaInitJob.statements` 的 `ns.ads`
 *     建表语句：表集、列名、类型、顺序逐列一致；
 *  2. 参考副本与所有者之间**三处已登记差异**全部写成**显式白名单**，并逐条钉住精确形态
 *     （多一列、少两表、少八表）—— 白名单漂移一格即红，「差异悄悄长大」不可能静默；
 *  3. 写入投影（`AdsSql` 的 8 个 `INSERT OVERWRITE … SELECT`）↔ 所有者列序一致。
 *     Spark 按**位置**写 Parquet，投影与 DDL 列序差一位就是「值串列」的静默错数；
 *  4. ADS 写入的**唯一入口**：每张表恰好一处 `AdsSql.insertTarget` 调用，全仓 main 源码里
 *     不存在以 `ads_` 表名直写的旁路 `INSERT OVERWRITE`；
 *  5. 一份**冻结快照** `Frozen`（第四份独立依据）：防止"三份一起漂"（例如三处同时漏加一列）时全绿；
 *  6. 守卫**自检**：把投影列序对调、把静态分区子句换错时，同一判定点必须红 —— 否则本守卫是空的。
 *
 * 归因边界（不得越界表述）：本类只判**表形 / 列序 / 写入目标 / 分区子句形态**，不判任何口径、
 * 不判任何指标值、不判真实 Hive 上的物理落盘（本地链与真 Hive 不等价，见 `P2TestSupport` 自陈的测试域）；
 * 也不断言白名单里那 2 张镜像表"应当有数据"——它们缺生产链的事实登记在
 * `docs/PROJECT_STATUS.md`（G-04 / 门⑦⑧），本类只保证"没人偷偷建一半"。
 *
 * 加列 / 改列是**有意为之**的变更 ⇒ 必须同批改所有者＋参考副本＋写入投影，并**有意**更新 `Frozen`。
 */
class AdsSchemaOwnerSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace
  private val dt = "20260901"
  private val sid = "S20260916_47"
  private val topN = 10
  private val periodStart = "20260801"
  private val periodEnd = "20260901"
  private val adsDdlRelative = "warehouse/ddl/04-ads.sql"
  private val adsSqlRelative = "spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala"

  /**
   * S3-14 冻结快照（列名 → 类型，逐表按序）＝ 本轮实测的三方一致值（**不含**分区列 `dt`）。
   *
   * 它是**独立的第四份**：只在下述情况红 —— 有人**同时**改了所有者＋参考副本＋写入投影
   * （剩下三份互等，前几个用例全绿），此时必须**显式**改这里，逼一次"这确实是有意的表形变更"的判断。
   * 依据：`LocalSchemaInitJob.statements` 的 `ns.ads` 段（8 张正式建表语句）逐列实测。
   */
  private val Frozen: Map[String, Seq[(String, String)]] = Map(
    "ads_operation_overview" -> Seq(
      "pv" -> "BIGINT", "uv" -> "BIGINT", "dau" -> "BIGINT", "order_count" -> "BIGINT",
      "sale_amount" -> "DECIMAL(18,2)", "net_sale_amount" -> "DECIMAL(18,2)",
      "avg_order_value" -> "DECIMAL(18,2)", "refund_rate" -> "DECIMAL(8,4)",
      "full_refund_rate" -> "DECIMAL(8,4)", "repeat_rate" -> "DECIMAL(8,4)",
      "repeat_period_start" -> "STRING", "repeat_period_end" -> "STRING",
      "fav_cnt" -> "BIGINT", "cart_add_cnt" -> "BIGINT"),
    "ads_behavior_funnel" -> Seq(
      "stage" -> "STRING", "user_count" -> "BIGINT", "conversion_rate" -> "DECIMAL(8,4)",
      "overall_buy_rate" -> "DECIMAL(8,4)", "overall_cart_rate" -> "DECIMAL(8,4)"),
    "ads_active_trend" -> Seq(
      "dau" -> "BIGINT", "behavior_count" -> "BIGINT"),
    "ads_hot_product" -> Seq(
      "product_id" -> "BIGINT", "product_name" -> "STRING", "heat_score" -> "DECIMAL(18,4)",
      "pv" -> "BIGINT", "fav" -> "BIGINT", "cart" -> "BIGINT", "buy" -> "BIGINT",
      "rank_no" -> "INT", "rule_version" -> "STRING"),
    "ads_product_conversion" -> Seq(
      "product_id" -> "BIGINT", "pv_users" -> "BIGINT", "buy_users" -> "BIGINT",
      "conversion_rate" -> "DECIMAL(8,4)"),
    "ads_sale_trend" -> Seq(
      "order_count" -> "BIGINT", "buyer_count" -> "BIGINT", "sale_amount" -> "DECIMAL(18,2)",
      "avg_order_value" -> "DECIMAL(18,2)", "net_sale_amount" -> "DECIMAL(18,2)"),
    "ads_user_profile" -> Seq(
      "user_id" -> "BIGINT", "r" -> "INT", "f" -> "INT", "m" -> "INT",
      "value_group" -> "STRING", "active_level" -> "STRING", "favorite_category" -> "BIGINT",
      "last_active_date" -> "STRING", "last_buy_date" -> "STRING",
      "lifecycle_state" -> "STRING", "rule_version" -> "STRING", "calc_date" -> "STRING",
      "r_days" -> "INT", "f_count" -> "BIGINT", "m_amount" -> "DECIMAL(18,2)",
      "period_start" -> "STRING", "period_end" -> "STRING"),
    "ads_data_quality" -> Seq(
      "rule_code" -> "STRING", "check_count" -> "BIGINT", "error_count" -> "BIGINT",
      "error_rate" -> "DECIMAL(8,6)", "passed" -> "INT", "threshold" -> "STRING",
      "rule_version" -> "INT")
  )

  /**
   * 参考副本**已声明**、唯一所有者**不建**、且全仓 main 源码**零写入**的 2 张 ADS 镜像表 —— 显式白名单。
   *
   * 事实（实测，非推测）：`warehouse/ddl/04-ads.sql:99-118` 声明了 `ads_category_sale` /
   * `ads_region_sale`，而 `LocalSchemaInitJob.statements` 的 `ns.ads` 名下只有 8 张正式表 + 8 张
   * `__staging` 建表语句；`AdsSql.TABLES` 也不含这两张。设计 §9.2 L322 逐字写着
   * "已知DIM仅user/product有日常产出、**分类/地区ADS缺生产链**，需逐项补证"。
   * 本轮**只登记不实现**（分类/地区 ADS 的生产链涉及 unknown 维度保留等未决口径，
   * 且属已登记门⑦⑧/G-04），故用白名单把"参考副本多出来 2 张"写死：
   * 任何一张被删、被建、或被写入，本类立即红。
   */
  private val UnownedMirrorAdsTables: Set[String] = Set("ads_category_sale", "ads_region_sale")

  /**
   * 参考副本与所有者的**已登记缺陷**（不是有意差异）：`04-ads.sql` 的 `ads_operation_overview`
   * 多一个**数据列** `snapshot_id STRING`。
   *
   * 事实链（实测）：`04-ads.sql:13-14` 自己注明这属已登记缺陷 **D-09 / V25-C01**（静态 DDL 与运行时
   * DDL 漂移）；运行时真相是「`snapshot_id` **只是** `__staging` 的分区列」——
   * `LocalSchemaInitJob` 的暂存建表语句都是 `PARTITIONED BY (snapshot_id STRING, dt STRING)`，
   * 且 `reconcile`（L294-304）在真库上检测到正式表残留该列时会 **DROP + 重建**为无该列的当前结构
   * （注释逐字："值恒为 dt，语义错误"）。
   *
   * 本轮**不改参考副本**：删掉参考副本里**已声明的一列**落在 HARD DECISION GATE ① （DROP COLUMN）
   * 邻域，须走设计差异裁定；本类只把该漂移**精确钉住**（多一列、且在末位、且类型为 STRING），
   * 使其不可能悄悄长大。**参考副本一旦被修正，本用例会红 —— 那是要求删掉本白名单项的通知，不是回归。**
   */
  private val RegisteredSnapshotIdDriftTables: Set[String] = Set("ads_operation_overview")

  // ── 夹具 ────────────────────────────────────────────────────────────────

  private def readRepoFile(relative: String): String = {
    val path: Path = P2TestSupport.repoRoot.resolve(relative)
    P2TestSupport.requireNonEmpty(path)
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  private def adsRef: StaticOdsDdl.Parsed = StaticOdsDdl.parse(readRepoFile(adsDdlRelative))

  /**
   * 唯一所有者：`LocalSchemaInitJob` 里 namespace = `ns.ads` 的**建表**语句。
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

  private def ownerAds: StaticOdsDdl.Parsed =
    StaticOdsDdl.parse(ownerStatements(ns.ads).mkString("\n;\n"))

  private def columnSeqOf(parsed: StaticOdsDdl.Parsed, table: String): Seq[(String, String)] =
    withClue(s"$table: ") { parsed.columnSeq(table) }

  /**
   * 8 张 ADS 表的写入投影：`snapshotId=None` → 正式分区，`Some(_)` → `__staging` 暂存分区。
   *
   * 表名取自 `AdsSql.TABLES`（本体自陈的唯一表集），新增表若没在此登记，`case _` 直接抛 ——
   * 逼"新增 ADS 表"这件事必须在守卫里显式落地，不能靠默认分支溜过。
   */
  private def write(table: String, snapshotId: Option[String]): String = table match {
    case "ads_operation_overview" => AdsSql.operationOverview(ns, dt, snapshotId)
    case "ads_active_trend"       => AdsSql.activeTrend(ns, dt, snapshotId)
    case "ads_behavior_funnel"    => AdsSql.funnel(ns, dt, snapshotId)
    case "ads_hot_product"        => AdsSql.hotProduct(ns, dt, topN, snapshotId)
    case "ads_product_conversion" => AdsSql.productConversion(ns, dt, snapshotId)
    case "ads_sale_trend"         => AdsSql.saleTrend(ns, dt, snapshotId)
    case "ads_user_profile"       => AdsSql.userProfile(ns, dt, periodStart, periodEnd, snapshotId)
    case "ads_data_quality"       => AdsSql.dataQuality(ns, dt, snapshotId)
    case other =>
      throw new IllegalArgumentException(s"未登记的 ADS 写入投影：$other（新增 ADS 表必须在本守卫登记）")
  }

  /**
   * 剥掉 `--` 行注释（逐行截断到第一个 `--`）。局限（如实声明）：不识别字符串字面量里的 `--`
   * （`04-ads.sql` 与本仓库的 ADS 投影里都没有这种写法）；若将来出现，本守卫会**偏严**
   * （把字面量当注释切掉）⇒ 宁可偏严，也不放过真语句。
   */
  private def stripLineComments(sql: String): String =
    sql.linesIterator.map { line =>
      val at = line.indexOf("--")
      if (at >= 0) line.substring(0, at) else line
    }.mkString("\n")

  /** 写入投影 ↔ 所有者：列序、分区列名、写入目标三对齐（C1/C2/C4 共用的唯一判定点） */
  private def checkProjection(table: String, w: DmlWriteProjection.Write): Unit = {
    val owner = ownerAds
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

  /** 全仓 main 源码里的 `INSERT OVERWRITE TABLE` 目标表 → 出现次数（C3 的"旁路写入"扫描） */
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

  /** `AdsSql` 里 `insertTarget(ns, "<表名>", …)` 的调用表名（写入入口的唯一性依据） */
  private val adsInsertCallRe = """insertTarget\(ns,\s*"([a-z0-9_]+)"""".r

  // ── 1. 参考副本 ↔ 唯一所有者 ───────────────────────────────────────────

  it should "A1 参考副本 04-ads.sql 的表集 = 所有者 8 张 + 2 张镜像表白名单，其余表逐列一致（列名/类型/顺序）" in {
    val ddl = adsRef
    val owner = ownerAds
    val tables = Frozen.keySet
    // 白名单自检：条目数写死；表集必须**恰好**是多出这 2 张，多一张少一张都红
    UnownedMirrorAdsTables.size should be(2)
    ddl.columns.keySet should be(tables ++ UnownedMirrorAdsTables)
    // 白名单的另一半：镜像表既无所有者、也不在写入作业的表集里（"没人偷偷建一半"）
    UnownedMirrorAdsTables.foreach { table =>
      withClue(s"$table 白名单已漂移: ") {
        ownerStatements(ns.ads).exists(_.contains(table)) should be(false)
        AdsSql.TABLES should not contain table
      }
    }
    // 已登记漂移表之外的每一张表，参考副本与所有者必须逐列一致
    (tables -- RegisteredSnapshotIdDriftTables).foreach { table =>
      withClue(s"$table 参考副本与所有者不一致: ") {
        columnSeqOf(ddl, table) should be(columnSeqOf(owner, table))
      }
    }
  }

  it should "A2 已登记漂移被精确钉住：参考副本仅 ads_operation_overview 多一个末位 snapshot_id 数据列，所有者不含该列" in {
    val ddl = adsRef
    val owner = ownerAds
    // 漂移表集写死：只允许这一张表有这一处漂移；参考副本一旦被修正，本用例会红（那是要删白名单的通知）
    RegisteredSnapshotIdDriftTables should be(Set("ads_operation_overview"))
    RegisteredSnapshotIdDriftTables.foreach { table =>
      withClue(s"$table 已登记缺陷形态变了: ") {
        // 精确形态：参考副本 = 所有者列序 ++ **末位**一个 STRING 列 snapshot_id（位置与类型都钉住）
        columnSeqOf(ddl, table) should be(columnSeqOf(owner, table) :+ ("snapshot_id" -> "STRING"))
        columnSeqOf(owner, table).map(_._1) should not contain "snapshot_id"
      }
    }
  }

  it should "A3 参考副本与所有者的分区列一致（都是 dt STRING），且分区列不得混进普通列" in {
    val ddl = adsRef
    val owner = ownerAds
    Frozen.keySet.foreach { table =>
      withClue(s"$table: ") {
        ddl.partitions(table) should be(Seq("dt" -> "STRING"))
        owner.partitions(table) should be(Seq("dt" -> "STRING"))
        ddl.columnSeq(table).map(_._1) should not contain "dt"
        owner.columnSeq(table).map(_._1) should not contain "dt"
      }
    }
  }

  it should "A4 参考副本不得用 ALTER TABLE 旁路加列（否则「唯一所有者」断言可被绕过）" in {
    // 只扫**语句**：先剥 `--` 行注释（本文件头部就逐字写着 snapshot_id 漂移这类提示，
    // 不剥注释会把说明文字当真语句。S3-13 的同类夹具缺陷）。
    val statements = stripLineComments(readRepoFile(adsDdlRelative))
    withClue("04-ads.sql 里出现了 ALTER TABLE：") {
      statements.toUpperCase should not include "ALTER TABLE"
    }
  }

  // ── 2. 所有者内部结构（正式 ↔ 暂存）────────────────────────────────────

  it should "B1 所有者正式 ADS 表集 == AdsSql.TABLES（作业写的就是所有者建的，且无重复、无遗漏）" in {
    val owner = ownerAds
    val owned = Frozen.keySet
    AdsSql.TABLES.distinct.size should be(AdsSql.TABLES.size)
    AdsSql.TABLES.toSet should be(owned)
    owned.foreach { table =>
      withClue(s"$table 所有者建表列序与冻结快照不一致: ") {
        columnSeqOf(owner, table) should be(Frozen(table))
      }
    }
    AdsSql.TABLES.foreach { table =>
      withClue(s"$table 未按 insertTarget 写入（或未在本守卫登记）：") {
        write(table, None).toUpperCase should include("INSERT OVERWRITE TABLE")
      }
    }
  }

  it should "B2 暂存表数据列 == 正式表数据列；分区 正式=dt、暂存=snapshot_id+dt；分区列不混进数据列" in {
    val owner = ownerAds
    AdsSql.TABLES.foreach { table =>
      val staging = s"${table}__staging"
      withClue(s"$staging: ") {
        columnSeqOf(owner, staging) should be(Frozen(table))
        owner.partitions(staging) should be(Seq("snapshot_id" -> "STRING", "dt" -> "STRING"))
        owner.columnSeq(staging).map(_._1) should not contain "snapshot_id"
        owner.columnSeq(staging).map(_._1) should not contain "dt"
      }
    }
  }

  it should "B3 参考副本里没有 __staging 表：8 张暂存表是所有者独有的派生结构（白名单的另一半）" in {
    val ddl = adsRef
    val stagingOwned = AdsSql.TABLES.map(t => s"${t}__staging").toSet
    stagingOwned.size should be(8)
    ddl.columns.keySet.filter(_.endsWith("__staging")) should be(Set.empty[String])
    ownerAds.columns.keySet.filter(_.endsWith("__staging")) should be(stagingOwned)
  }

  // ── 3. 写入投影 ↔ 所有者 ───────────────────────────────────────────────

  it should "C1 8 张正式表的写入投影与所有者列序一致（静态分区 dt）" in {
    AdsSql.TABLES.foreach { table =>
      checkProjection(table, DmlWriteProjection.parse(write(table, None)))
    }
  }

  it should "C2 8 张暂存表的写入投影与所有者列序一致（静态分区 snapshot_id + dt）" in {
    AdsSql.TABLES.foreach { table =>
      checkProjection(s"${table}__staging", DmlWriteProjection.parse(write(table, Some(sid))))
    }
  }

  it should "C3 ADS 写入唯一入口：每张表恰好 1 处 insertTarget 调用，且无以 ads_ 表名直写的旁路 INSERT" in {
    val text = readRepoFile(adsSqlRelative)
    val calls = adsInsertCallRe.findAllMatchIn(text).map(_.group(1)).toList
    calls.distinct.size should be(8)
    // 注意：Scala 2.12 的 `view.mapValues` 返回 IterableView（2.13 才可直接 mapValues）⇒ 用显式 map
    calls.groupBy(identity).map { case (table, hits) => table -> hits.size } should be(
      AdsSql.TABLES.map(_ -> 1).toMap)
    // ADS 侧 `INSERT OVERWRITE TABLE` 只允许出现在 insertTarget 的两个分支里（正式 / 暂存）
    text.split("INSERT OVERWRITE TABLE", -1).length - 1 should be(2)
    val bypass = insertTargetCounts.keys.filter(_.contains("ads_")).toSet
    withClue(s"存在绕过 AdsSql.insertTarget 的 ADS 直写: $bypass: ") {
      bypass should be(Set.empty[String])
    }
  }

  it should "C4 守卫自检：投影列序对调、静态分区子句换错时，同一判定点必须红" in {
    val formal = AdsSql.formal(ns, "ads_active_trend")
    val swapped =
      s"""INSERT OVERWRITE TABLE $formal PARTITION(dt = '$dt')
         |SELECT behavior_count, dau FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt'""".stripMargin
    an[TestFailedException] should be thrownBy {
      checkProjection("ads_active_trend", DmlWriteProjection.parse(swapped))
    }
    val wrongPartition =
      s"""INSERT OVERWRITE TABLE $formal PARTITION(snapshot_id = '$sid', dt = '$dt')
         |SELECT dau, behavior_count FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '$dt'""".stripMargin
    an[TestFailedException] should be thrownBy {
      checkProjection("ads_active_trend", DmlWriteProjection.parse(wrongPartition))
    }
  }

  it should "C5 冻结快照 Frozen（第四份独立依据）与所有者逐列一致，且覆盖全部 8 张表" in {
    val owner = ownerAds
    Frozen.size should be(8)
    Frozen.keySet should be(AdsSql.TABLES.toSet)
    Frozen.foreach { case (table, cols) =>
      withClue(s"$table: ") {
        columnSeqOf(owner, table) should be(cols)
      }
    }
  }
}
