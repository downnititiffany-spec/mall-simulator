package com.graduation.analytics.job

import scala.collection.mutable

/**
 * 作业注册表（§5.3.2 依赖限定）：code 唯一。
 * 依赖顺序用于流水线编排（阶段 6 JobSubmitter 引用）。
 * R4：odl 全主题 ODS → bdw 行为 DWD / dim 维度 / tdw 交易 DWD。
 * R5：usw 聚合 7 张 DWS（行为+订单），fna 产出 8 张核心 ADS。
 * R6-13：fna 只写暂存分区 → dqc 质量门 → pub 发布正式分区（元数据指针）。
 * R7-3：pub 之后 mxp 把已发布正式 ADS 导出为发布文件（指标库发布读取侧）。
 */
object JobRegistry {

  val jobs: Map[String, WarehouseJob] = Map(
    EventOdsLoadJob.instance.code -> EventOdsLoadJob.instance,
    BehaviorDwdJob.instance.code -> BehaviorDwdJob.instance,
    DimensionBuildJob.instance.code -> DimensionBuildJob.instance,
    TradeDwdJob.instance.code -> TradeDwdJob.instance,
    UserProductDwsJob.instance.code -> UserProductDwsJob.instance,
    FunnelAdsJob.instance.code -> FunnelAdsJob.instance,
    AdsQualityJob.instance.code -> AdsQualityJob.instance,
    AdsPublishJob.instance.code -> AdsPublishJob.instance,
    MetricExportJob.instance.code -> MetricExportJob.instance,
    LocalJsonParquetJob.instance.code -> LocalJsonParquetJob.instance,
    LocalSchemaInitJobInstance.instance.code -> LocalSchemaInitJobInstance.instance
  )

  /** 依赖：jobCode -> 其前置作业 code 列表（§5.3.2，供阶段 6 流水线使用） */
  val dependencies: Map[String, List[String]] = Map(
    "odl" -> List.empty,
    "bdw" -> List("odl", "dim"), // P2-04-a：bdw 的 SQL LEFT JOIN dim_user/dim_product，dim 必须先于 bdw
    "dim" -> List("odl"),
    "tdw" -> List("odl", "dim"),
    "usw" -> List("bdw", "tdw"),
    "fna" -> List("usw"),
    "dqc" -> List("fna"),
    "pub" -> List("dqc"),
    "mxp" -> List("pub"),
    "ljp" -> List.empty,  // 本地 JSON→Parquet 验证
    "sci" -> List.empty   // 本地表初始化自举
  )

  def lookup(code: String): Option[WarehouseJob] = jobs.get(code)

  def allCodes: List[String] = jobs.keys.toList.sorted

  /**
   * Kahn 拓扑序（**前置在前**）；`None` ⇔ 依赖图存在环（含自环）。
   *
   * 设计 §10.1 L375 逐字要求：**扩展 DAG 时必须用 Kahn 拓扑排序或 DFS 检测真实环**，
   * 不得沿用「首批无环」的假定 —— 本方法取代原先的 `hasCycle = false` 恒假断言。
   *
   * 传入 `deps` 中的**未知前置**（不在 `deps` 键集内）被忽略：它既构不成环，也不应把图误判成
   * 有环。但这类前置属编排缺陷（会静默不跑），由 `JobArgsRegistrySpec` 单独钉住
   * 「注册表里每个前置都必须是已注册作业」。
   */
  def topologicalOrder(deps: Map[String, List[String]] = dependencies): Option[List[String]] = {
    val nodes = deps.keySet
    val pres = deps.map { case (n, ps) => n -> ps.filter(nodes.contains).distinct }
    val dependents = mutable.Map.empty[String, List[String]]
    nodes.foreach(n => dependents(n) = Nil)
    pres.foreach { case (n, ps) => ps.foreach(p => dependents(p) = dependents(p) :+ n) }
    val pending = mutable.Map.empty[String, Int]
    nodes.foreach(n => pending(n) = pres(n).size)
    val ready = mutable.Queue.empty[String]
    nodes.toSeq.sorted.filter(pending(_) == 0).foreach(ready.enqueue(_))
    val out = mutable.ListBuffer.empty[String]
    while (ready.nonEmpty) {
      val n = ready.dequeue()
      out += n
      dependents(n).sorted.foreach { m =>
        pending(m) -= 1
        if (pending(m) == 0) ready.enqueue(m)
      }
    }
    // 就位数 < 节点数 ⇔ 剩余节点仍有未就位前置 ⇒ 它们必然处在某个环上
    if (out.size == nodes.size) Some(out.toList) else None
  }

  def hasCycle: Boolean = topologicalOrder().isEmpty
}