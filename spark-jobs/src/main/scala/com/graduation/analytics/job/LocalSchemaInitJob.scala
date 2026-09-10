package com.graduation.analytics.job

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
    val created = LocalSchemaInitJob.STATEMENTS.map { case (db, stmt) =>
      spark.sql(stmt)
      (db, 1)
    }.groupBy(_._1).map { case (db, rows) => db -> rows.size }
    // R6-13：结构对账（历史 ADS schema 漂移修正在此收敛，幂等）
    val reconciles = LocalSchemaInitJob.reconcile(spark)
    reconciles.foreach(m => System.err.println(s"[spark-jobs] sci 对账: $m"))
    spark.sql("SELECT 1").collect() // 触发执行
    val msg = if (reconciles.isEmpty) "ok" else s"ok; 对账: ${reconciles.mkString(" | ")}"
    JobResult(code, 0L, created.values.sum.toLong, 0L, None, args.attemptNo, "SUCCESS", msg,
      System.currentTimeMillis() - start)
  }
}

object LocalSchemaInitJob {

  /** 四层库 + 核心表（与 warehouse/ddl 对应；本地 warehouse 路径由 spark.sql.warehouse.dir 控制） */
  val STATEMENTS: List[(String, String)] = List(
    ("dw_ods", "CREATE DATABASE IF NOT EXISTS dw_ods"),
    ("dw_dwd", "CREATE DATABASE IF NOT EXISTS dw_dwd"),
    ("dw_dws", "CREATE DATABASE IF NOT EXISTS dw_dws"),
    ("dw_ads", "CREATE DATABASE IF NOT EXISTS dw_ads"),
    ("dw_dim", "CREATE DATABASE IF NOT EXISTS dw_dim"),

    ("dw_ods", """
        CREATE TABLE IF NOT EXISTS dw_ods.ods_user_event (
          event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
          source_system STRING, schema_version STRING, trace_id STRING,
          payload_user_id STRING, payload_age_group STRING, payload_city_level STRING,
          payload_member_level STRING, payload_register_time STRING,
          source_file STRING, ingest_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),

    ("dw_ods", """
        CREATE TABLE IF NOT EXISTS dw_ods.ods_product_event (
          event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
          source_system STRING, schema_version STRING, trace_id STRING,
          payload_product_id STRING, payload_product_name STRING, payload_category_id STRING,
          payload_category_name STRING, payload_parent_category_id STRING,
          payload_parent_category_name STRING,
          payload_brand_id STRING, payload_price DECIMAL(18,2), payload_cost DECIMAL(18,2),
          payload_status STRING,
          source_file STRING, ingest_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),

    ("dw_ods", """
        CREATE TABLE IF NOT EXISTS dw_ods.ods_behavior_event (
          event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
          source_system STRING, schema_version STRING, trace_id STRING,
          payload_user_id STRING, payload_product_id STRING, payload_session_id STRING,
          payload_behavior_type STRING, payload_channel STRING,
          source_file STRING, ingest_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),

    ("dw_ods", """
        CREATE TABLE IF NOT EXISTS dw_ods.ods_trade_event (
          event_id STRING, event_type STRING, event_time STRING, ingest_time STRING,
          source_system STRING, schema_version STRING, trace_id STRING,
          payload_order_id STRING, payload_user_id STRING, payload_payment_id STRING,
          payload_refund_id STRING, payload_product_id STRING,
          payload_amount DECIMAL(18,2), payload_total_amount DECIMAL(18,2),
          payload_status STRING, payload_reason STRING, payload_items STRING,
          source_file STRING, ingest_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING, hour STRING)"""),

    ("dw_dwd", """
        CREATE TABLE IF NOT EXISTS dw_dwd.dwd_user_behavior_detail (
          behavior_id STRING, user_id BIGINT, product_id BIGINT, category_id BIGINT,
          behavior_type STRING, event_time TIMESTAMP, event_date STRING,
          event_hour INT, city_level STRING, channel STRING, session_id STRING,
          source_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dwd", """
        CREATE TABLE IF NOT EXISTS dw_dwd.dwd_reject_record (
          reject_id STRING, source_table STRING, reject_reason STRING,
          raw_payload STRING, reject_time TIMESTAMP)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dwd", """
        CREATE TABLE IF NOT EXISTS dw_dwd.dwd_order_detail (
          order_id BIGINT, user_id BIGINT, product_id BIGINT, category_id BIGINT,
          quantity INT, unit_price DECIMAL(18,2), discount DECIMAL(18,2),
          amount DECIMAL(18,2), order_status STRING, order_time TIMESTAMP,
          order_date STRING, city_level STRING, paid_at TIMESTAMP,
          order_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
          refund_amount DECIMAL(18,2), net_paid_amount DECIMAL(18,2),
          final_paid_flag INT, final_refunded_flag INT)
        USING parquet PARTITIONED BY (dt STRING)"""),

    ("dw_dim", """
        CREATE TABLE IF NOT EXISTS dw_dim.dim_user (
          user_id BIGINT, age_group STRING, city_level STRING, member_level STRING,
          register_date STRING, register_time TIMESTAMP, source_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dim", """
        CREATE TABLE IF NOT EXISTS dw_dim.dim_product (
          product_id BIGINT, product_name STRING, category_id BIGINT, category_name STRING,
          parent_category_id BIGINT, parent_category_name STRING,
          brand_id BIGINT, price DECIMAL(18,2), cost DECIMAL(18,2), status STRING,
          source_batch_id BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),

    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_user_behavior_day (
          user_id BIGINT, pv BIGINT, fav BIGINT, cart BIGINT, search BIGINT,
          active_hours INT, buy BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_behavior_funnel_day (
          category_id BIGINT, channel STRING, view_users BIGINT, intent_users BIGINT,
          order_users BIGINT, pay_users BIGINT, intent_rate DECIMAL(8,4),
          order_rate DECIMAL(8,4), pay_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_product_behavior_day (
          product_id BIGINT, category_id BIGINT, pv BIGINT, uv BIGINT,
          fav BIGINT, cart BIGINT, buy BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_product_sale_day (
          product_id BIGINT, category_id BIGINT, sale_count BIGINT,
          sale_amount DECIMAL(18,2), buyer_count BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_trade_day (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          refund_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_user_trade_period (
          user_id BIGINT, last_buy_date STRING, order_count BIGINT,
          sale_amount DECIMAL(18,2), period_start STRING, period_end STRING)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_dws", """
        CREATE TABLE IF NOT EXISTS dw_dws.dws_region_sale_day (
          region STRING, buyer_count BIGINT, order_count BIGINT, sale_amount DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),

    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_operation_overview (
          pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
          sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_behavior_funnel (
          stage STRING, user_count BIGINT,
          conversion_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_active_trend (
          dau BIGINT, behavior_count BIGINT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_hot_product (
          product_id BIGINT, product_name STRING, heat_score DECIMAL(18,4),
          pv BIGINT, fav BIGINT, cart BIGINT, buy BIGINT, rank_no INT)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_product_conversion (
          product_id BIGINT, pv_users BIGINT, buy_users BIGINT,
          conversion_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_sale_trend (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2))
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_user_profile (
          user_id BIGINT, r INT, f INT, m INT, value_group STRING,
          active_level STRING, favorite_category BIGINT, last_active_date STRING,
          last_buy_date STRING, lifecycle_state STRING, rule_version STRING,
          calc_date STRING)
        USING parquet PARTITIONED BY (dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_data_quality (
          rule_code STRING, check_count BIGINT, error_count BIGINT,
          error_rate DECIMAL(8,6), passed INT, threshold STRING)
        USING parquet PARTITIONED BY (dt STRING)"""),

    // ---- R6-13 暂存分区（§14.4 分区幂等协议）----
    // fna 只写 {table}__staging/snapshot_id=S/dt=D；质量门（dqc）通过后由 pub 用
    // Hive 元数据指针把正式分区指向该路径。暂存表结构 = 正式表结构去掉 dt（分区列另加 snapshot_id）。
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_operation_overview__staging (
          pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
          sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_behavior_funnel__staging (
          stage STRING, user_count BIGINT,
          conversion_rate DECIMAL(8,4), overall_buy_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_active_trend__staging (
          dau BIGINT, behavior_count BIGINT)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_hot_product__staging (
          product_id BIGINT, product_name STRING, heat_score DECIMAL(18,4),
          pv BIGINT, fav BIGINT, cart BIGINT, buy BIGINT, rank_no INT)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_product_conversion__staging (
          product_id BIGINT, pv_users BIGINT, buy_users BIGINT,
          conversion_rate DECIMAL(8,4))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_sale_trend__staging (
          order_count BIGINT, buyer_count BIGINT, sale_amount DECIMAL(18,2),
          avg_order_value DECIMAL(18,2))
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_user_profile__staging (
          user_id BIGINT, r INT, f INT, m INT, value_group STRING,
          active_level STRING, favorite_category BIGINT, last_active_date STRING,
          last_buy_date STRING, lifecycle_state STRING, rule_version STRING,
          calc_date STRING)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)"""),
    ("dw_ads", """
        CREATE TABLE IF NOT EXISTS dw_ads.ads_data_quality__staging (
          rule_code STRING, check_count BIGINT, error_count BIGINT,
          error_rate DECIMAL(8,6), passed INT, threshold STRING)
        USING parquet PARTITIONED BY (snapshot_id STRING, dt STRING)""")
  )

  /** 正式 ads_operation_overview 的当前定义（对账重建时使用，须与上方 STATEMENTS 一致） */
  val ADS_OPERATION_OVERVIEW_DDL: String = """
      CREATE TABLE IF NOT EXISTS dw_ads.ads_operation_overview (
        pv BIGINT, uv BIGINT, dau BIGINT, order_count BIGINT,
        sale_amount DECIMAL(18,2), net_sale_amount DECIMAL(18,2),
        avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4))
      USING parquet PARTITIONED BY (dt STRING)"""

  /**
   * R6-13 结构对账（幂等，只在检测到漂移时动作）：
   * 历史 dw_ads.ads_operation_overview 有数据列 snapshot_id，其值恒为 dt（不是真正快照号），
   * 与「暂存分区 snapshot_id」语义冲突。Spark datasource 表不支持 DROP COLUMN（实测
   * UNSUPPORTED_FEATURE.TABLE_OPERATION），故检测到该列即重建该表。
   * 该表由 fna 的 INSERT OVERWRITE 全量重建，无持久数据损失；
   * 重建后正式分区元数据为空（Spark 不会自动发现遗留目录，实测 COUNT=0），
   * 首次成功的 pub 会用元数据指针把正式分区指到本次暂存路径。
   */
  def reconcile(spark: SparkSession): List[String] = {
    val actions = scala.collection.mutable.ListBuffer[String]()
    val table = "dw_ads.ads_operation_overview"
    if (spark.catalog.tableExists("dw_ads", "ads_operation_overview")) {
      val cols = spark.table(table).schema.fieldNames.toSet
      if (cols.contains("snapshot_id")) {
        spark.sql(s"DROP TABLE IF EXISTS $table")
        spark.sql(ADS_OPERATION_OVERVIEW_DDL)
        actions += s"$table 检测到遗留数据列 snapshot_id（值恒为 dt，语义错误）→ 已重建为无该列的当前结构"
      }
    }
    actions.toList
  }
}

object LocalSchemaInitJobInstance {
  val instance: LocalSchemaInitJob = new LocalSchemaInitJob()
}