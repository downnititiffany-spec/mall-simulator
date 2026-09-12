package com.graduation.analytics.sql

/**
 * ODS 列集合的**唯一所有者**（P2-01 / D-052）。
 *
 * 存在理由：ODS 的列集合今天同时存在于三处——`warehouse/ddl/00-ods.sql`（静态 SQL）、
 * `LocalSchemaInitJob.statements`（本地自举 DDL）、`OdsLoadSql` 的四个 INSERT 模板。
 * 三处各自演化必然漂移。本对象把「ODS v2 公共列 + 各表业务列」收成**一份机器可读定义**，
 * 另外两处只做「派生 / 对账」：
 *  - `LocalSchemaInitJob` 从本对象**派生** ODS 建表列（不再手抄）；
 *  - `warehouse/ddl/00-ods.sql` 保持静态 SQL（交付物要求），由 `OdsV2ReconciliationSpec`
 *    逐列对账钉住——静态文件的列名/类型/顺序必须与本对象完全一致，否则测试红。
 *
 * v2 语义（D-052/D-054/D-057）：在 v1 九业务列 + `trace_id` + 全部 `payload_*` **一个不动**的
 * 前提下**加法扩列**，新增 `raw_event_type` / `raw_source_system` / `landing_file` /
 * `payload_json` / `payload_hash`。`event_time` 保持 STRING（不做类型收敛——那是 P2 后续任务）。
 *
 * **列序口径（D-052(c)「对既有列零破坏」的保守解）**：v1 的全部列在物理列序里
 * **位置与序号一格不动**，v2 新增列**追加在 `ingest_batch_id` 之后**。
 * 这样「加法扩列」在**名字**和**序号**两个维度同时成立，按列位置读 Parquet 的既有消费方零感知。
 * （备选解是把 12 公共列排成连续一块，但那会让每个 v1 列的序号后移；本对象是列序的**唯一所有者**，
 *  改用备选解只需改本文件。）
 */
object OdsV2Columns {

  /** 一列的机器可读定义：名字、SQL 类型（与 DDL 逐字一致）、可选注释 */
  final case class Column(name: String, sqlType: String, comment: Option[String] = None) {
    /** `name TYPE [COMMENT '...']` 形态（生成 DDL 用） */
    def ddlFragment: String =
      comment.map(c => s"$name $sqlType COMMENT '$c'").getOrElse(s"$name $sqlType")
  }

  /**
   * ODS 四表共同拥有的 12 列（D-052 裁决口径）。
   *
   * 注意：这 **12 列不构成一段连续的物理列**——`ingest_batch_id` 是第 12 列但物理上排在
   * 各表业务列之后。要物理列序用 {@link dataColumns}。
   */
  val CommonColumns: Seq[Column] = Seq(
    Column("event_id", "STRING"),
    Column("event_type", "STRING"),
    Column("event_time", "STRING"),
    Column("ingest_time", "STRING"),
    Column("source_system", "STRING"),
    Column("schema_version", "STRING"),
    Column("trace_id", "STRING"),
    Column("raw_event_type", "STRING"),
    Column("raw_source_system", "STRING"),
    Column("landing_file", "STRING"),
    Column("payload_json", "STRING"),
    Column("payload_hash", "STRING"),
    Column("ingest_batch_id", "BIGINT")
  )

  /** 四表共享的 v1 信封列（物理列序的第一段，序号 0-6） */
  private val V1Shared: Seq[Column] = Seq(
    Column("event_id", "STRING"),
    Column("event_type", "STRING"),
    Column("event_time", "STRING"),
    Column("ingest_time", "STRING"),
    Column("source_system", "STRING"),
    Column("schema_version", "STRING"),
    Column("trace_id", "STRING")
  )

  /** v2 新增列（D-054）：追加在 v1 列之后，物理列序的最后一段（审计列之前） */
  val V2NewColumns: Seq[Column] = Seq(
    Column("raw_event_type", "STRING"),
    Column("raw_source_system", "STRING"),
    Column("landing_file", "STRING"),
    Column("payload_json", "STRING"),
    Column("payload_hash", "STRING")
  )

  /** 各表业务列（payload_*）；顺序即物理列顺序，v1 一个不动 */
  val UserPayloadColumns: Seq[Column] = Seq(
    Column("payload_user_id", "STRING"),
    Column("payload_age_group", "STRING"),
    Column("payload_city_level", "STRING"),
    Column("payload_member_level", "STRING"),
    Column("payload_register_time", "STRING")
  )

  val ProductPayloadColumns: Seq[Column] = Seq(
    Column("payload_product_id", "STRING"),
    Column("payload_product_name", "STRING"),
    Column("payload_category_id", "STRING"),
    Column("payload_category_name", "STRING"),
    Column("payload_parent_category_id", "STRING"),
    Column("payload_parent_category_name", "STRING"),
    Column("payload_brand_id", "STRING"),
    Column("payload_price", "DECIMAL(18,2)"),
    Column("payload_cost", "DECIMAL(18,2)"),
    Column("payload_status", "STRING")
  )

  val BehaviorPayloadColumns: Seq[Column] = Seq(
    Column("payload_user_id", "STRING"),
    Column("payload_product_id", "STRING"),
    Column("payload_session_id", "STRING"),
    Column("payload_behavior_type", "STRING"),
    Column("payload_channel", "STRING")
  )

  val TradePayloadColumns: Seq[Column] = Seq(
    Column("payload_order_id", "STRING"),
    Column("payload_user_id", "STRING"),
    Column("payload_payment_id", "STRING"),
    Column("payload_refund_id", "STRING"),
    Column("payload_product_id", "STRING"),
    Column("payload_amount", "DECIMAL(18,2)"),
    Column("payload_total_amount", "DECIMAL(18,2)"),
    Column("payload_status", "STRING"),
    Column("payload_reason", "STRING"),
    Column("payload_items", "STRING")
  )

  /** 采集审计列（v1 已有，位置固定在业务列之后、新增列之前） */
  val AuditColumns: Seq[Column] = Seq(
    Column("source_file", "STRING"),
    Column("ingest_batch_id", "BIGINT")
  )

  /** 分区列（不属于数据列，`PARTITIONED BY` 中声明） */
  val PartitionColumns: Seq[Column] = Seq(
    Column("dt", "STRING"),
    Column("hour", "STRING")
  )

  /** 四张 ODS 表 → 其业务列；表名即唯一所有者登记的物理表名 */
  val PayloadColumnsByTable: Map[String, Seq[Column]] = Map(
    "ods_user_event" -> UserPayloadColumns,
    "ods_product_event" -> ProductPayloadColumns,
    "ods_behavior_event" -> BehaviorPayloadColumns,
    "ods_trade_event" -> TradePayloadColumns
  )

  /** 表名顺序固定（DDL 生成与对账都按此顺序） */
  val OdsTables: Seq[String] = Seq(
    "ods_user_event", "ods_product_event", "ods_behavior_event", "ods_trade_event")

  /** 表注释（与 warehouse/ddl/00-ods.sql 逐字一致） */
  val TableComments: Map[String, String] = Map(
    "ods_user_event" -> "用户事件原始层",
    "ods_product_event" -> "商品事件原始层",
    "ods_behavior_event" -> "用户行为事件原始层",
    "ods_trade_event" -> "交易事件原始层"
  )

  /**
   * 全表数据列（不含分区列），**物理列序的唯一来源**：
   * `v1 信封列 + 业务列 + 审计列`（v1 原样）`++ v2 新增列`（追加）。
   */
  def dataColumns(table: String): Seq[Column] = v1Columns(table) ++ V2NewColumns

  /** v1 数据列（原样保真的那一段）：信封 7 列 + 业务列 + 审计 2 列 */
  def v1Columns(table: String): Seq[Column] = {
    val payload = PayloadColumnsByTable.getOrElse(table,
      throw new IllegalArgumentException(s"未登记的 ODS 表：$table"))
    V1Shared ++ payload ++ AuditColumns
  }

  /** v2 新增列的名字（供模板/对账复用，避免各处再抄一份字面量） */
  def v2NewColumnNames: Seq[String] = V2NewColumns.map(_.name)

  /** 全表列（数据列 + 分区列） */
  def allColumns(table: String): Seq[Column] = dataColumns(table) ++ PartitionColumns

  /** 列名序列（对账/测试用） */
  def columnNames(table: String): Seq[String] = allColumns(table).map(_.name)

  /** 某列的声明类型；列不存在时抛异常（对账失败要响） */
  def columnType(table: String, column: String): String =
    allColumns(table).find(_.name == column)
      .map(_.sqlType)
      .getOrElse(throw new IllegalArgumentException(s"$table 未声明列 $column"))

  // ── 派生：本地自举 DDL（LocalSchemaInitJob 不再手抄列清单）────────────────

  /** `CREATE TABLE IF NOT EXISTS` 语句（columns 段由本对象派生，其余部分由调用侧给） */
  def columnsClause(table: String): String =
    dataColumns(table).map(_.ddlFragment).mkString(",\n          ")
}
