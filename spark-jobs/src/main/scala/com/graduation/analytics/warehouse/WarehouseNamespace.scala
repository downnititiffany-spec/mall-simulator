package com.graduation.analytics.warehouse

import com.graduation.analytics.job.JobArgs

/**
 * 数仓库名空间的**唯一所有者**（P1-04）。
 *
 * 背景（改造前实测）：`dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads` 这五个库名以裸字面量散落在
 * INIT_SCHEMA、五张 SQL 模板、质量检查、ADS 发布、指标导出与测试里（共 ~190 处），
 * `runtime_profile.hive_database_prefix` 是**死配置**（写了不生效）。要做「一种平台适配多种商城」，
 * 就必须让每个源有自己的库名空间，且库名只能由一处派生。
 *
 * 规则（机器可读权威见 `contract-specs/specs/warehouse-namespace.v1.json`，两侧逐向量对账）：
 *   - 库名 = `<prefix>_<layer>`，layer ∈ {ods, dwd, dim, dws, ads}；
 *   - prefix 白名单：`^[a-z][a-z0-9_]{0,23}$`，且不得以 `_` 结尾、不得含 `__`、
 *     不得命中保留字、不得已带层后缀（避免 `dw_ods_ods` 这类二次后缀）；
 *   - `hive_database_prefix` 为 null/空串 → 缺省前缀 `dw`（与改造前库名逐字一致，零数据迁移）；
 *   - **不做任何归一化**：不 trim、不转小写、不自动纠正；非法取值原样失败并带错误码。
 *
 * 纪律：库名字面量只允许出现在本文件与规格文件里；其余源码（含 SQL 模板、脚本、测试）
 * 必须消费本对象。`WarehouseNamespaceSpec` 与 Java 侧契约测试共同守住这条线。
 */
final case class WarehouseNamespace private (prefix: String) {

  import WarehouseNamespace.{LayerSuffixes, Separator}

  /** 某层的库名（层名必须在 `LayerSuffixes` 内，否则抛错——调用方不得自造层名） */
  def layerDb(layer: String): String = {
    require(LayerSuffixes.contains(layer), s"未知数仓层: $layer（允许: ${LayerSuffixes.mkString(",")}）")
    s"$prefix$Separator$layer"
  }

  /** 贴源层 ODS */
  def ods: String = layerDb("ods")

  /** 明细层 DWD */
  def dwd: String = layerDb("dwd")

  /** 维度层 DIM */
  def dim: String = layerDb("dim")

  /** 汇总层 DWS */
  def dws: String = layerDb("dws")

  /** 应用层 ADS */
  def ads: String = layerDb("ads")

  /** 层 → 库名（按规格给定顺序，供页面/证据展示；不参与 SQL 拼接） */
  def layers: Map[String, String] = {
    val b = scala.collection.immutable.ListMap.newBuilder[String, String]
    LayerSuffixes.foreach(l => b += (l -> layerDb(l)))
    b.result()
  }

  /** `库.表` 限定名（表名由调用方给定，不得含 `.`——避免跨库注入） */
  def table(layer: String, table: String): String = {
    require(table != null && table.nonEmpty && !table.contains("."),
      s"表名非法: $table（不得为空或含 '.'）")
    s"${layerDb(layer)}.$table"
  }

  override def toString: String = s"WarehouseNamespace($prefix → ${layers.values.mkString("/")})"
}

object WarehouseNamespace {

  /** 缺省前缀：源 A 既有库名（`dw_ods` …），零数据迁移 */
  final val DefaultPrefix: String = "dw"

  final val Separator: String = "_"

  /** 前缀白名单形状（与规格 rule.prefixPattern 逐字一致） */
  final val PrefixPattern: String = "^[a-z][a-z0-9_]{0,23}$"

  /** 由前缀派生的层后缀；顺序即规格 order */
  final val LayerSuffixes: Seq[String] = Seq("ods", "dwd", "dim", "dws", "ads")

  /** 保留前缀（Hive/元数据库名） */
  final val Reserved: Set[String] =
    Set("default", "sys", "system", "information_schema", "hive_metastore")

  final val ErrPattern = "WAREHOUSE_PREFIX_PATTERN"
  final val ErrUnderscore = "WAREHOUSE_PREFIX_UNDERSCORE"
  final val ErrReserved = "WAREHOUSE_PREFIX_RESERVED"
  final val ErrLayerSuffix = "WAREHOUSE_PREFIX_LAYER_SUFFIX"

  /** Java 侧（`JobCommandBuilder`）与本侧共用的透传键名 */
  final val ArgKey: String = "hiveDatabasePrefix"

  private val Compiled = java.util.regex.Pattern.compile(PrefixPattern)

  /**
   * 白名单校验：`None` = 合法（含缺省）；`Some(错误码)` = 非法。
   * 检查顺序与规格 `rule.checkOrder` 一致（保证两侧同一输入得到同一错误码）。
   */
  def validate(raw: String): Option[String] = {
    if (raw == null || raw.isEmpty) None
    else if (!Compiled.matcher(raw).matches()) Some(ErrPattern)
    else if (raw.endsWith(Separator) || raw.contains(Separator + Separator)) Some(ErrUnderscore)
    else if (Reserved.contains(raw)) Some(ErrReserved)
    else if (LayerSuffixes.exists(s => raw.endsWith(Separator + s))) Some(ErrLayerSuffix)
    else None
  }

  /** 解析：`Right` = 命名空间（缺省也合法）；`Left(错误码)` = 非法 */
  def parse(raw: String): Either[String, WarehouseNamespace] =
    validate(raw) match {
      case None => Right(WarehouseNamespace(if (raw == null || raw.isEmpty) DefaultPrefix else raw))
      case Some(code) => Left(code)
    }

  /** 解析并返回值；非法 → `IllegalArgumentException("<错误码>: <原值>")` */
  def of(raw: String): WarehouseNamespace = parse(raw) match {
    case Right(ns) => ns
    case Left(code) => throw new IllegalArgumentException(s"$code: $raw")
  }

  /** 缺省命名空间（源 A，`dw_*`） */
  def defaultNamespace: WarehouseNamespace = WarehouseNamespace(DefaultPrefix)

  /**
   * 从作业参数解析（`--hiveDatabasePrefix=…`；缺省 → `dw`）。
   * 供 `JobRunner` 在创建 SparkSession **之前**调用做启动前校验（非法前缀不进入 Spark），
   * 各作业也可自行调用取得同一命名空间。
   */
  def fromArgs(args: JobArgs): WarehouseNamespace =
    of(args.extra.getOrElse(ArgKey, DefaultPrefix))
}
