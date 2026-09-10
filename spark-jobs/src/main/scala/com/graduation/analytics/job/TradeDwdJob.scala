package com.graduation.analytics.job

import com.graduation.analytics.algorithm.{OrderTradeCompiler, TradeEvent, TradeOrderDetail}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.JavaConverters._

/**
 * Job04 订单交易明细（§11.3 TradeDwdJob）：
 * ODS 交易事件 → dwd_order_detail。订单状态由 OrderTradeCompiler 纯状态机合并；
 * 商品项由 order_created.items JSON 展开，一行一个 order_id+product_id；
 * 迟到支付/退款按订单归属业务日（orderDate）重算历史分区（§11.4 动态分区覆盖）。
 *
 * 依赖：dim_product / dim_user（维度快照已由 dim 作业产出）补充 category_id / city_level；
 * 缺失维度使用明确 unknown key（-1 / 'unknown'，§11.1）。
 */
class TradeDwdJob extends WarehouseJob {
  override val code: String = "tdw"
  override val description: String = "ODS 交易 → DWD 订单明细（状态合并+商品展开+迟到重算）"

  override def run(spark: SparkSession, args: JobArgs): JobResult = {
    val start = System.currentTimeMillis()
    spark.conf.set("spark.sql.sources.partitionOverwriteMode", "dynamic") // §11.4 迟到重算只覆盖归属分区

    spark.sparkContext.setJobDescription(s"$code read ods_trade_event")
    val ods: DataFrame = spark.sql(
      "SELECT event_id, event_type, event_time, " +
        "payload_order_id, payload_user_id, payload_amount, payload_total_amount, " +
        "payload_refund_id, payload_items " +
        "FROM dw_ods.ods_trade_event").filter("payload_order_id IS NOT NULL")

    val inputCount = ods.count()
    spark.sparkContext.setJobDescription(s"$code dedup by event_id")
    val dedup = ods.dropDuplicates("event_id") // §11.1 at-least-once 重复投递兜底

    spark.sparkContext.setJobDescription(s"$code compile orders")
    val orderEvents: Map[String, Seq[TradeEvent]] =
      dedup.collect().groupBy(r => r.getAs[String]("payload_order_id")).map { case (orderId, rows) =>
        orderId -> rows.toSeq.map(toTradeEvent).sortBy(_.eventTime)
      }

    val compiled: Seq[TradeOrderDetail] =
      orderEvents.values.flatMap(OrderTradeCompiler.compile).toSeq

    // 商品项展开：一行一个 order_id+product_id（§11.3）；无 items 的行 product_id='' 保序
    val daily = compiled.flatMap { d =>
      val items = if (d.items.isEmpty) Seq.empty else d.items
      if (items.isEmpty) {
        Seq((d, "", 0L, BigDecimal(0), BigDecimal(0), BigDecimal(0)))
      } else {
        items.map(it => (d, it.productId, it.quantity, it.unitPrice, it.discount, it.amount))
      }
    }

    spark.sparkContext.setJobDescription(s"$code assign dt partition")
    val rows: Seq[Row] = daily.map { case (d, pid, qty, unitPrice, discount, amount) =>
      Row(
        d.orderId, d.userId, pid, qty, unitPrice.bigDecimal, discount.bigDecimal, amount.bigDecimal,
        d.status, d.orderTime, d.orderDate, d.paidAt.orNull,
        d.orderAmount.bigDecimal, d.paidAmount.bigDecimal, d.refundAmount.bigDecimal,
        d.netPaidAmount.bigDecimal, d.finalPaidFlag, d.finalRefundedFlag,
        OrderTradeCompiler.partitionDt(d.orderDate))
    }

    spark.sparkContext.setJobDescription(s"$code write dwd_order_detail")
    if (rows.nonEmpty) {
      val out = spark.createDataFrame(rows.asJava, TradeDwdJob.OUT_SCHEMA)
      out.createOrReplaceTempView("tdw_tmp")
      // 维度补充（缺失 → unknown key，§11.1）：category_id 关联商品维、city_level 关联用户维
      spark.sql(
        """INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt)
          |SELECT
          |  CAST(t.order_id AS BIGINT),
          |  CAST(t.user_id AS BIGINT),
          |  CASE WHEN t.product_id = '' THEN -1 ELSE CAST(t.product_id AS BIGINT) END,
          |  COALESCE(p.category_id, -1) AS category_id,
          |  CAST(t.quantity AS INT),
          |  t.unit_price, t.discount, t.amount,
          |  t.status AS order_status,
          |  TO_TIMESTAMP(t.order_time) AS order_time,
          |  t.order_date,
          |  COALESCE(u.city_level, 'unknown') AS city_level,
          |  CASE WHEN t.paid_at IS NULL THEN NULL ELSE TO_TIMESTAMP(t.paid_at) END AS paid_at,
          |  t.order_amount, t.paid_amount, t.refund_amount, t.net_paid_amount,
          |  t.final_paid_flag, t.final_refunded_flag,
          |  t.dt
          |FROM tdw_tmp t
          |LEFT JOIN dw_dim.dim_product p ON p.product_id = CASE WHEN t.product_id = ''
          |     THEN -1 ELSE CAST(t.product_id AS BIGINT) END
          |LEFT JOIN dw_dim.dim_user u ON u.user_id = CAST(t.user_id AS BIGINT)""".stripMargin)
    }

    val outputCount = spark.sql("SELECT COUNT(*) c FROM dw_dwd.dwd_order_detail").collect()(0).getLong(0)
    // 数据驱动分区（迟到支付/退款会重算历史归属日），故采集该表全部存续分区
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, TradeDwdJob.OUTPUT_TABLES, args.outputSnapshotId))
  }

  /** ODS 行 → TradeEvent（字符串归一，避免 NULL 装箱） */
  private def toTradeEvent(r: Row): TradeEvent = {
    def str(col: String): Option[String] =
      Option(r.getAs[Any](col)).map(_.toString).filter(_.nonEmpty)
    TradeEvent(
      eventId = r.getAs[String]("event_id"),
      orderId = r.getAs[String]("payload_order_id"),
      eventType = r.getAs[String]("event_type"),
      eventTime = r.getAs[String]("event_time"),
      amount = str("payload_amount"),
      totalAmount = str("payload_total_amount"),
      refundId = str("payload_refund_id"),
      itemsJson = str("payload_items"),
      userId = str("payload_user_id"))
  }
}

object TradeDwdJob {

  /** 临时视图 Schema（不含维度列；与 LocalSchemaInitJob.dwd_order_detail 列序对齐） */
  val OUT_SCHEMA: org.apache.spark.sql.types.StructType =
    new org.apache.spark.sql.types.StructType(Array(
      org.apache.spark.sql.types.StructField("order_id", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("user_id", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("product_id", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("quantity", org.apache.spark.sql.types.LongType),
      org.apache.spark.sql.types.StructField("unit_price", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("discount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("amount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("status", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("order_time", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("order_date", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("paid_at", org.apache.spark.sql.types.StringType),
      org.apache.spark.sql.types.StructField("order_amount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("paid_amount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("refund_amount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("net_paid_amount", org.apache.spark.sql.types.DecimalType(18, 2)),
      org.apache.spark.sql.types.StructField("final_paid_flag", org.apache.spark.sql.types.IntegerType),
      org.apache.spark.sql.types.StructField("final_refunded_flag", org.apache.spark.sql.types.IntegerType),
      org.apache.spark.sql.types.StructField("dt", org.apache.spark.sql.types.StringType)
    ))

  val instance: TradeDwdJob = new TradeDwdJob()

  /** 本作业写出的目标表（R6-12 分区证据采集范围） */
  val OUTPUT_TABLES: Seq[String] = Seq("dw_dwd.dwd_order_detail")
}