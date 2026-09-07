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
    spark.sql("SELECT 1").collect() // 触发执行
    JobResult.success(code, 0L, created.values.sum.toLong, 0L, None, args.attemptNo,
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
          active_hours INT)
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
          avg_order_value DECIMAL(18,2), refund_rate DECIMAL(8,4), snapshot_id STRING)
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
        USING parquet PARTITIONED BY (dt STRING)""")
  )
}

object LocalSchemaInitJobInstance {
  val instance: LocalSchemaInitJob = new LocalSchemaInitJob()
}