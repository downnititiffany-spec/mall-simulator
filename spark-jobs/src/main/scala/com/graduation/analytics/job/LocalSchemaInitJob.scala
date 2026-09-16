package com.graduation.analytics.job

import com.graduation.analytics.sql.{OdsV2Columns, SurrogateKey}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession

/**
 * LocalSchemaInitJob（code=sci）—— LOCAL/SINGLE_NODE 本地验证用：
 * 在 embedded Derby/Hive（spark.sql.warehouse.dir）下创建四层核心表（本地路径），
 * 使 odl/bdw/usw/fna 作业链可以在无 Hadoop/Hive 服务的本机完整运行。
 * 生产集群使用 warehouse/ddl 目录下的建表 SQL；本作业仅用于实验环境自举。
 */
class LocalSchemaInitJob extends WarehouseJob {
  override val code: String = "sci"
  override val description: String = "本地 Derby-Hive 四层核心表初始化（验证用）"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    val ns = WarehouseNamespace.fromArgs(args)
    val created = LocalSchemaInitJob.statements(ns).map { case (db, stmt) =>
      spark.sql(stmt)
      (db, 1)
    }.groupBy(_._1).map { case (db, rows) => db -> rows.size }
    // R6-13：结构对账（历史 ADS schema 漂移修正在此收敛，幂等）
    val reconciles = LocalSchemaInitJob.reconcile(spark, ns)
    reconciles.foreach(m => System.err.println(s"[spark-jobs] sci 对账: $m"))
    spark.sql("SELECT 1").collect() // 触发执行
    val msg = if (reconciles.isEmpty) "ok" else s"ok; 对账: ${reconciles.mkString(" | ")}"
    JobResult(code, 0L, created.values.sum.toLong, 0L, None, args.attemptNo, "SUCCESS", msg,
      System.currentTimeMillis() - start)
  }
}

object LocalSchemaInitJob {

  /** 四层库 + 核心表（与 warehouse/ddl 对应；本地 warehouse 路径由 spark.sql.warehouse.dir 控制） */
  def statements(ns: WarehouseNamespace): List[(String, String)] = List(
    (ns.ods, s"CREATE DATABASE IF NOT EXISTS ${ns.ods}"),
    (ns.dwd, s"CREATE DATABASE IF NOT EXISTS ${ns.dwd}"),
    (ns.dws, s"CREATE DATABASE IF NOT EXISTS ${ns.dws}"),
    (ns.ads, s"CREATE DATABASE IF NOT EXISTS ${ns.ads}"),
    (ns.dim, s"CREATE DATABASE IF NOT EXISTS ${ns.dim}"),

    (ns.ods, odsCreateTable(ns, "ods_user_event")),
    (ns.ods, odsCreateTable(ns, "ods_product_event")),
    (ns.ods, odsCreateTable(ns, "ods_behavior_event")),
    (ns.ods, odsCreateTable(ns, "ods_trade_event")),

    (ns.dwd, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dwd}.dwd_user_behavior_detail (
          behavior_id STRING, user_id BIGINT, product_id BIGINT, category_id BIGINT,
          behavior_type STRING, event_time TIMESTAMP, event_date STRING,
          event_hour INT, city_level STRING, channel STRING, session_id STRING,
          source_batch_id BIGINT,
          user_key BIGINT, product_key BIGINT, category_key BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dwd, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dwd}.dwd_reject_record (
          reject_id STRING, source_table STRING, reject_reason STRING,
          raw_payload STRING, reject_time TIMESTAMP)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dwd, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dwd}.dwd_order_detail (
          order_id BIGINT, user_id BIGINT, product_id BIGINT, category_id BIGINT,
          quantity INT, unit_price DECIMAL(18,2), discount DECIMAL(18,2),
          amount DECIMAL(18,2), order_status STRING, order_time TIMESTAMP,
          order_date STRING, city_level STRING, paid_at TIMESTAMP,
          order_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
          refund_amount DECIMAL(18,2), net_paid_amount DECIMAL(18,2),
          final_paid_flag INT, final_refunded_flag INT,
          user_key BIGINT, product_key BIGINT, category_key BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),

    (ns.dim, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dim}.dim_user (
          user_id BIGINT, age_group STRING, city_level STRING, member_level STRING,
          register_date STRING, register_time TIMESTAMP, source_batch_id BIGINT,
          user_key BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dim, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dim}.dim_product (
          product_id BIGINT, product_name STRING, category_id BIGINT, category_name STRING,
          parent_category_id BIGINT, parent_category_name STRING,
          brand_id BIGINT, price DECIMAL(18,2), cost DECIMAL(18,2), status STRING,
          source_batch_id BIGINT,
          product_key BIGINT, category_key BIGINT, parent_category_key BIGINT, brand_key BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),

    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_user_behavior_day (
          user_id BIGINT, pv BIGINT, fav BIGINT, cart BIGINT, search BIGINT,
          active_hours INT, buy BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_behavior_funnel_day (
          category_id BIGINT, channel STRING, view_users BIGINT, intent_users BIGINT,
          order_users BIGINT, pay_users BIGINT, intent_rate DECIMAL(8,4),
          order_rate DECIMAL(8,4), pay_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4),
          cart_users BIGINT, cart_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_product_behavior_day (
          product_id BIGINT, category_id BIGINT, pv BIGINT, uv BIGINT,
          fav BIGINT, cart BIGINT, buy BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_product_sale_day (
          product_id BIGINT, category_id BIGINT, sale_count BIGINT,
          sale_amount DECIMAL(18,2), buyer_count BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_trade_day (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          refund_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_user_trade_period (
          user_id BIGINT, last_buy_date STRING, order_count BIGINT,
          sale_amount DECIMAL(18,2), period_start STRING, period_end STRING,
          valid_order_count BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.dws, s"""
        CREATE TABLE IF NOT EXISTS ${ns.dws}.dws_region_sale_day (
          region STRING, buyer_count BIGINT, order_count BIGINT, sale_amount DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),

    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_operation_overview (
          pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
          sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4),
          full_refund_rate DECIMAL(8,4), repeat_rate DECIMAL(8,4),
          repeat_period_start STRING, repeat_period_end STRING)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_behavior_funnel (
          stage STRING, user_count BIGINT,
          conversion_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4),
          overall_cart_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_active_trend (
          dau BIGINT, behavior_count BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_hot_product (
          product_id BIGINT, product_name STRING, heat_score DECIMAL(18,4),
          pv BIGINT, fav BIGINT, cart BIGINT, buy BIGINT, rank_no INT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_product_conversion (
          product_id BIGINT, pv_users BIGINT, buy_users BIGINT,
          conversion_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_sale_trend (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), net_sale_amount DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),
    // S3-01：画像表末尾追加 R/F/M 原值 + 观察窗口（§11.4 L447）。注意 `IF NOT EXISTS` 对**已存在**的表
    // 不生效 —— 已在产 Hive 建过该表的库需要显式 `ALTER TABLE … ADD COLUMNS` 才会长出新列
    // （本地/内存目录每次新建，能直接看到新结构；生产 Hive 迁移不在本任务范围，见 F-34 未实测边界）。
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_user_profile (
          user_id BIGINT, r INT, f INT, m INT, value_group STRING,
          active_level STRING, favorite_category BIGINT, last_active_date STRING,
          last_buy_date STRING, lifecycle_state STRING, rule_version STRING,
          calc_date STRING, r_days INT, f_count BIGINT, m_amount DECIMAL(18,2),
          period_start STRING, period_end STRING)
        USING parquet PARTITIONED BY (dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_data_quality (
          rule_code STRING, check_count BIGINT, error_count BIGINT,
          error_rate DECIMAL(8,6), passed INT, threshold STRING, rule_version INT)
        USING parquet PARTITIONED BY (dt STRING)"""),

    // ---- R6-13 暂存分区（§14.4 分区幂等协议）----
    // fna 只写 {table}__staging/snapshot_id=S/dt=D；质量门（dqc）通过后由 pub 用
    // Hive 元数据指针把正式分区指向该路径。暂存表结构 = 正式表结构去掉 dt（分区列另加 snapshot_id）。
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_operation_overview__staging (
          pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
          sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4),
          full_refund_rate DECIMAL(8,4), repeat_rate DECIMAL(8,4),
          repeat_period_start STRING, repeat_period_end STRING)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_behavior_funnel__staging (
          stage STRING, user_count BIGINT,
          conversion_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4),
          overall_cart_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_active_trend__staging (
          dau BIGINT, behavior_count BIGINT)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_hot_product__staging (
          product_id BIGINT, product_name STRING, heat_score DECIMAL(18,4),
          pv BIGINT, fav BIGINT, cart BIGINT, buy BIGINT, rank_no INT)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_product_conversion__staging (
          product_id BIGINT, pv_users BIGINT, buy_users BIGINT,
          conversion_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_sale_trend__staging (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), net_sale_amount DECIMAL(18,2))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_user_profile__staging (
          user_id BIGINT, r INT, f INT, m INT, value_group STRING,
          active_level STRING, favorite_category BIGINT, last_active_date STRING,
          last_buy_date STRING, lifecycle_state STRING, rule_version STRING,
          calc_date STRING, r_days INT, f_count BIGINT, m_amount DECIMAL(18,2),
          period_start STRING, period_end STRING)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    (ns.ads, s"""
        CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_data_quality__staging (
          rule_code STRING, check_count BIGINT, error_count BIGINT,
          error_rate DECIMAL(8,6), passed INT, threshold STRING, rule_version INT)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)""")
  )

  /**
   * ODS 建表语句：列清单**由唯一所有者派生**（`OdsV2Columns`），本文件不再手抄。
   *
   * 派生而非生成静态文件的原因见 `OdsV2Columns` 类注释：`warehouse/ddl/00-ods.sql` 必须是
   * 可被 beeline 直接执行的静态 SQL，所以它保留为静态文本，由 `OdsV2SchemaOwnerSpec` 逐列对账钉住。
   */
  def odsCreateTable(ns: WarehouseNamespace, table: String): String = {
    val partitions = OdsV2Columns.PartitionColumns.map(c => c.ddlFragment).mkString(", ")
    // 子句顺序是**文法硬约束**，不是风格问题：Spark 的 createTableHeader 里
    // `USING <provider>` 只能紧跟列体 `)`，排在 COMMENT / PARTITIONED BY 等 createTableClauses **之前**。
    // 写成 `) COMMENT '…'` + `USING parquet` 会 PARSE_SYNTAX_ERROR at or near 'USING'
    // （E2 实测：docs/acceptance/p2-01-ods-v2-20260912/evidence/e2-full-20260912.log，
    //  两种形态的真实 spark-sql 对照：…/evidence/e3-probe-ddl-clause-order-20260912-134550.log，
    //  负向对照退出码 1、正向对照退出码 0 且 SHOW CREATE TABLE 回显出 COMMENT 与分区列）。
    // 注：曾把该报错误判为「`COMMENT` 前不能有空行」——错因已由上述对照排除。
    val comment = OdsV2Columns.TableComments.get(table).map(c => s" COMMENT '$c'").getOrElse("")
    s"""
        CREATE TABLE IF NOT EXISTS ${ns.ods}.$table (
          ${OdsV2Columns.columnsClause(table)})
        USING parquet$comment PARTITIONED BY ($partitions)"""
  }

  /** 正式 ads_operation_overview 的当前定义（对账重建时使用，须与上方 statements(ns) 一致） */
  def adsOperationOverviewDdl(ns: WarehouseNamespace): String = s"""
      CREATE TABLE IF NOT EXISTS ${ns.ads}.ads_operation_overview (
        pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
        sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
        avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4),
        full_refund_rate DECIMAL(8,4), repeat_rate DECIMAL(8,4),
        repeat_period_start STRING, repeat_period_end STRING)
      USING parquet PARTITIONED BY (dt STRING)"""

  /**
   * 存量 ADS 表的**加性补列**清单（正式表 + 暂存表都补）：
   * R7-0 起为口径统一新增 `full_refund_rate`；S3-03 追加复购率与其观察期声明三列。
   *
   * 只允许追加列（`ALTER TABLE … ADD COLUMNS`）：历史 parquet 文件缺该列时读出 null，
   * 下一次成功发布即被新快照覆盖；新建表则由上方 `statements(ns)` 的 DDL 直接长齐。
   * **列序必须与 DDL 末尾一致**（`MetricAdsSpec` 与写入都是按位置对齐）。
   */
  val R7_ADDED_COLUMNS: Map[(String, String), Seq[String]] = Map(
    ("ads", "ads_operation_overview") -> Seq("full_refund_rate DECIMAL(8,4)",
      "repeat_rate DECIMAL(8,4)", "repeat_period_start STRING", "repeat_period_end STRING"),
    ("ads", "ads_operation_overview__staging") -> Seq("full_refund_rate DECIMAL(8,4)",
      "repeat_rate DECIMAL(8,4)", "repeat_period_start STRING", "repeat_period_end STRING"))

  /**
   * R6-13 结构对账（幂等，只在检测到漂移时动作）：
   * 历史 ADS 正式表 ads_operation_overview 有数据列 snapshot_id，其值恒为 dt（不是真正快照号），
   * 与「暂存分区 snapshot_id」语义冲突。Spark datasource 表不支持 DROP COLUMN（实测
   * UNSUPPORTED_FEATURE.TABLE_OPERATION），故检测到该列即重建该表。
   * 该表由 fna 的 INSERT OVERWRITE 全量重建，无持久数据损失；
   * 重建后正式分区元数据为空（Spark 不会自动发现遗留目录，实测 COUNT=0），
   * 首次成功的 pub 会用元数据指针把正式分区指到本次暂存路径。
   *
   * R7-0 追加：缺列（full_refund_rate）用 ALTER TABLE ADD COLUMNS 补齐（非破坏性，
   * 历史 parquet 文件缺该列时读出 null，下一次成功发布即被新快照覆盖）。
   */
  def reconcile(spark: SparkSession, ns: WarehouseNamespace): List[String] = {
    val actions = scala.collection.mutable.ListBuffer[String]()
    val table = ns.table("ads", "ads_operation_overview")
    if (spark.catalog.tableExists(ns.ads, "ads_operation_overview")) {
      val cols = spark.table(table).schema.fieldNames.toSet
      if (cols.contains("snapshot_id")) {
        spark.sql(s"DROP TABLE IF EXISTS $table")
        spark.sql(adsOperationOverviewDdl(ns))
        actions += s"$table 检测到遗留数据列 snapshot_id（值恒为 dt，语义错误）→ 已重建为无该列的当前结构"
      }
    }
    // R7-0 缺列补齐（正式表 + 暂存表）
    R7_ADDED_COLUMNS.foreach { case ((layer, name), columns) =>
      val tbl = ns.table(layer, name)
      if (spark.catalog.tableExists(ns.layerDb(layer), name)) {
        val existing = spark.table(tbl).schema.fieldNames.toSet
        val missing = columns.filterNot(c => existing.contains(c.trim.split("\\s+")(0)))
        if (missing.nonEmpty) {
          spark.sql(s"ALTER TABLE $tbl ADD COLUMNS (${missing.mkString(", ")})")
          actions += s"$tbl 补齐 R7-0 缺失列: ${missing.mkString(", ")}"
        }
      }
    }
    actions ++= reconcileOdsV2(spark, ns)
    actions ++= reconcileSurrogateKeys(spark, ns)
    actions.toList
  }

  /**
   * P2-03 代理键列对账（**只用 `ALTER TABLE ADD COLUMNS`，绝不 DROP/重建**）。
   *
   * 与 `reconcileOdsV2` 第 1 类同口径：缺列 → 非破坏性补齐。历史 parquet 文件缺该列时读出 `null`，
   * 下一次 `INSERT OVERWRITE` 即被真实值覆盖；**不重建表**（真数仓 `dw_*` 有真实数据，D-071 零迁移前提）。
   *
   * 列清单的唯一所有者是 `SurrogateKey.KeyColumns`（算法所有者）——DDL（本文件 `statements`）
   * 与对账（本方法）都必须与它一致，避免「建表一处、补列一处」两份清单漂移。
   *
   * @return 动作行（`RECONCILE_SURROGATE_KEY ...` 前缀；无漂移时为空）
   */
  def reconcileSurrogateKeys(spark: SparkSession, ns: WarehouseNamespace): List[String] = {
    val actions = scala.collection.mutable.ListBuffer[String]()
    SurrogateKey.keyColumnPlan.foreach { case (layer, name, cols) =>
      val full = ns.table(layer, name)
      if (spark.catalog.tableExists(ns.layerDb(layer), name)) {
        val existing = spark.table(full).schema.fieldNames.toSet
        val missing = cols.map(_._1).filterNot(existing.contains)
        if (missing.nonEmpty) {
          spark.sql(s"ALTER TABLE $full ADD COLUMNS (${missing.map(c => s"$c BIGINT").mkString(", ")})")
          actions += s"RECONCILE_SURROGATE_KEY $full 补齐代理键列: ${missing.mkString(", ")}"
        }
      }
    }
    actions.toList
  }

  /**
   * P2-01 / ODS v2 结构对账（**只在检测到漂移时动作**，幂等）。
   *
   * 分两类，口径刻意不同（D-052(c) + 规格草案 §5.1 第 7 条）：
   *  1. **缺列** → `ALTER TABLE ADD COLUMNS` 补齐。这是**非破坏性**动作，且语义正确：
   *     历史 parquet 文件缺该列时读出 null，下一次 `INSERT OVERWRITE` 即被真实值覆盖。
   *  2. **类型不符 / 多出未知列** → **只产出「需要重建」计划文本，绝不执行 DROP**。
   *     重建属破坏性操作，必须由显式 `INIT_SCHEMA` 审计步骤执行（本作业的 `statements` 路径），
   *     且 Spark datasource 表**不支持** `DROP COLUMN`（R6-13 已实测 `UNSUPPORTED_FEATURE.TABLE_OPERATION`），
   *     所以「改类型」在当前能力下根本无低成本路径——静默重建会丢数据，禁止。
   *
   * @return 动作/计划行（`RECONCILE_ODS_V2 ...` 前缀；无漂移时为空）
   */
  def reconcileOdsV2(spark: SparkSession, ns: WarehouseNamespace): List[String] = {
    val actions = scala.collection.mutable.ListBuffer[String]()
    OdsV2Columns.OdsTables.foreach { table =>
      if (spark.catalog.tableExists(ns.ods, table)) {
        val full = ns.table("ods", table)
        val actual = spark.table(full).schema.fields.map(f => f.name -> f.dataType.catalogString).toMap
        val expected = OdsV2Columns.dataColumns(table).map(c => c.name -> c.sqlType)

        // 1) 缺列 → 非破坏性补齐（v2 的加法扩列就靠这条在既有表上落地）
        val missing = OdsV2Columns.dataColumns(table)
          .filterNot(c => actual.contains(c.name))
        if (missing.nonEmpty) {
          spark.sql(s"ALTER TABLE $full ADD COLUMNS (${missing.map(_.ddlFragment).mkString(", ")})")
          actions += s"RECONCILE_ODS_V2 $full 补齐缺失列: ${missing.map(_.name).mkString(", ")}"
        }

        // 2) 类型不符 / 多出未知列 → 只出计划，不动手
        val typeMismatch = expected.filter { case (n, t) =>
          actual.get(n).exists(a => !normalizeType(a).equals(normalizeType(t)))
        }
        if (typeMismatch.nonEmpty) {
          actions += s"RECONCILE_ODS_V2 $full NEEDS_REBUILD 类型不符（不执行 DROP，" +
            s"请走显式 INIT_SCHEMA 审计步骤）: " +
            typeMismatch.map { case (n, t) => s"$n 期望=$t 实际=${actual(n)}" }.mkString("; ")
        }
        val unknown = actual.keySet.diff(expected.map(_._1).toSet)
          .diff(OdsV2Columns.PartitionColumns.map(_.name).toSet)
        if (unknown.nonEmpty) {
          actions += s"RECONCILE_ODS_V2 $full NEEDS_REVIEW 存在未登记列（不删，" +
            s"请确认是否需要重建）: ${unknown.toSeq.sorted.mkString(", ")}"
        }
      }
    }
    actions.toList
  }

  /** 类型比较前归一化（`decimal(18,2)` vs `DECIMAL(18,2)`、空白差异） */
  private def normalizeType(t: String): String = t.replaceAll("\\s+", "").toLowerCase
}

object LocalSchemaInitJobInstance {
  val instance: LocalSchemaInitJob = new LocalSchemaInitJob()
}