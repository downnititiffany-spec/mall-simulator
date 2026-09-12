package com.graduation.analytics.job

import com.graduation.analytics.algorithm.{OrderTradeCompiler, TradeEvent, TradeOrderDetail}
import com.graduation.analytics.sql.{IdCodec, OdsLoadSql, SurrogateKey}
import com.graduation.analytics.warehouse.WarehouseNamespace
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
    val ns = WarehouseNamespace.fromArgs(args)
    spark.conf.set("spark.sql.sources.partitionOverwriteMode", "dynamic") // §11.4 迟到重算只覆盖归属分区

    spark.sparkContext.setJobDescription(s"$code read ods_trade_event")
    val ods: DataFrame = spark.sql(
      "SELECT event_id, event_type, event_time, " +
        "payload_order_id, payload_user_id, payload_amount, payload_total_amount, " +
        "payload_refund_id, payload_items " +
        s"FROM ${ns.ods}.ods_trade_event").filter("payload_order_id IS NOT NULL")

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
      // R9 修正（D-R9-2）：维度快照必须按**生效日期分区**过滤（与 DwdSql/AdsSql 同口径）。
      //   原实现漏掉 dt 谓词 → dim_user/dim_product 每日快照逐日累积出多分区，JOIN 形成笛卡尔放大：
      //   实测 dt=20260901 订单明细 7 行被放大为 28 行（无维度分区 2 个 × 用户分区 2 个），
      //   GMV 2042.00 → 8168.00、net_sale 1493.00 → 5972.00。
      val dimDt = args.businessDate
      // P2-03：代理键需要本运行源编码。取值点与 ODS 载入**同一个所有者**
      // （`OdsLoadSql.ArgSourceSystem` + `OdsLoadSql.sourceSystemLiteral` 的校验/转义），不另建解析链。
      val sourceSystem = args.extra.getOrElse(OdsLoadSql.ArgSourceSystem, "")
      spark.sql(TradeDwdJob.orderDetailInsertSql(ns, dimDt, sourceSystem))
    }

    val outputCount = spark.sql(s"SELECT COUNT(*) c FROM ${ns.dwd}.dwd_order_detail").collect()(0).getLong(0)
    // 数据驱动分区（迟到支付/退款会重算历史归属日），故采集该表全部存续分区
    JobResult.success(code, inputCount, outputCount, 0L, args.outputSnapshotId, args.attemptNo,
      System.currentTimeMillis() - start,
      PartitionEvidence.collect(spark, TradeDwdJob.outputTables(ns), args.outputSnapshotId))
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

  /**
   * `dwd_order_detail` 写入 SQL（从 `tdw_tmp` 临时视图落表 + 维度补充）。
   *
   * - **id 归一化**：契约字符串 id（`O00000001`/`U000065`/`P00030`）在 ODS→DWD 边界一次性转 `BIGINT`，
   *   规则只在 `IdCodec` 定义（DEF-05 / 决策 B-07 候选 ② / D-023），此处不得再手写 `CAST(... AS BIGINT)`；
   * - **维度分区谓词**：R9 修正（D-R9-2）——`dim_*` 必须按生效日期分区过滤，否则 JOIN 笛卡尔放大（GMV 2042.00→8168.00）。
   * - **P2-03 代理键（只加不改）**：列尾追加 `user_key` / `product_key` / `category_key`
   *   （契约 `SurrogateKey` 派生，源编码取 `tdw_tmp` 未携带 → 见下方 `srcSys` 说明）。
   *   **`order_key` 刻意不存在**：契约实体枚举为 `{user, product, category, brand, coupon}`，**无 `order`**，
   *   按裁决 **D-093** 订单级身份一律不得套用代理键算法（否则物料 `<entity>` 段是自造的），
   *   登记为**契约缺口**，须走契约升版新增 `order` 实体后方可实现。原 `IdCodec` 的 `order_id` 列保持原样。
   *
   * @param ns 数仓命名空间（库名由唯一所有者派生）
   * @param dimDt 维度快照生效日期分区（= 业务日 `yyyyMMdd`）
   * @param sourceSystem 本运行源编码（= `--sourceSystem` 注入的 `source_registry.source_code`，D-056）。
   *                     `tdw_tmp` 由 `TradeDwdJob.run` 在内存里拼出（`OUT_SCHEMA` 不含 `source_system`），
   *                     故源身份只能经**参数通道**进入本 SQL，而不是从临时视图读列。
   *
   * **实测缺陷（P2-03-m2，run 44/45/46 连续失败的真根因）**：本 SQL 的 INSERT **不写列名**，全靠与 DDL
   * 的**位置**对齐，而 `INSERT OVERWRITE TABLE … PARTITION (dt)` 未给分区值（动态分区）时，Spark
   * **仍把分区列当作一个需要由 SELECT 提供的列**——它必须落在 **SELECT 列表的最末位**。
   * 错误的写法是把 `t.dt` 放在三个代理键**之前**（此处前面已有 19 个普通字段，`t.dt` 是**第 20 项**），
   * 而 `ccc1dff`(P2-03) 新增的三个 `BIGINT` 代理键被追加在 `t.dt` **之后** ⇒ 整体右移一位：
   * **STRING** 的 `t.dt` 落到第 20 个普通列 `user_key`(BIGINT)：
   * 报错 `[INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_SAFELY_CAST] … Cannot safely cast user_key "STRING" to "BIGINT"`。
   *
   * 正确顺序：19 个数据字段 → `user_key` → `product_key` → `category_key` → `t.dt`（分区列在最末位）。
   *
   * **口径订正（2026-09-12，人裁定）**：旧表是「19 个普通列 ＋ 动态分区列 `dt`」、旧 SELECT 是
   * 「19 个普通值 ＋ `t.dt`」，`t.dt` 正好是**第 20 项**、**位置正确** ⇒ **不存在**「旧版静默错位／
   * run 43 静默写错值」，也**不得**用 run 43 与 run 47 的行数或分区数差异去论证数据损坏
   * （两者输入批次不同）。此前本文件与调查报告中的「该错位在 P2-03 之前就已存在」「第 19 项」等
   * 表述已按该裁定**撤回**。
   *
   * 一次性实验钉死（`raw/post/align-experiment-1-*.sql` / `-2-*.sql`，实验表 `dw_exp.t_align_bad`
   * 建后即删，未碰 `dw_dwd.dwd_order_detail`）：
   *  - 实验 1：23 列同构表，`dt` 放第 19 项（STRING）⇒ 复现**逐字相同**的报错；
   *  - 实验 2：同一 SELECT 仅把 `dt` 移到末尾 ⇒ 写入成功，读回 `user_key=111/product_key=222/
   *    category_key=333/dt=20260901` 逐列落位正确。
   */
  def orderDetailInsertSql(ns: WarehouseNamespace, dimDt: String, sourceSystem: String): String = {
    val orderKey = IdCodec.toBIGINT("t.order_id")
    val userKey = IdCodec.toBIGINT("t.user_id")
    val productKey = IdCodec.toBIGINT("t.product_id")
    // 源编码字面量走唯一所有者（校验 + `'` 转义）；空值在此抛参数错误，与本项目既有口径一致
    val src = OdsLoadSql.sourceSystemLiteral(sourceSystem)
    val userSurrogate = SurrogateKey.toBIGINT(src, "user", "t.user_id")
    val productSurrogate = SurrogateKey.toBIGINT(src, "product", "t.product_id")
    s"""INSERT OVERWRITE TABLE ${ns.dwd}.dwd_order_detail PARTITION (dt)
       |SELECT
       |  $orderKey,
       |  $userKey,
       |  CASE WHEN t.product_id = '' THEN -1 ELSE $productKey END,
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
       |  $userSurrogate AS user_key,
       |  $productSurrogate AS product_key,
       |  p.category_key AS category_key,
       |  t.dt                                  -- 静态分区列**必须在最后一个位置**（见下）
       |FROM tdw_tmp t
       |LEFT JOIN ${ns.dim}.dim_product p ON p.product_id = CASE WHEN t.product_id = ''
       |     THEN -1 ELSE $productKey END AND p.dt = '$dimDt'
       |LEFT JOIN ${ns.dim}.dim_user u ON u.user_id = $userKey AND u.dt = '$dimDt'""".stripMargin
  }

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

  /** 本作业写出的目标表（R6-12 分区证据采集范围）；库名由唯一所有者派生 */
  def outputTables(ns: WarehouseNamespace): Seq[String] = Seq(ns.table("dwd", "dwd_order_detail"))
}