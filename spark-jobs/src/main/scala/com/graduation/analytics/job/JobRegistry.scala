package com.graduation.analytics.job

/**
 * 作业注册表（§5.3.2 依赖限定）：首批 4 个作业，code 唯一。
 * 依赖顺序用于流水线编排（阶段 6 JobSubmitter 引用）。
 */
object JobRegistry {

  val jobs: Map[String, WarehouseJob] = Map(
    EventOdsLoadJob.instance.code -> EventOdsLoadJob.instance,
    BehaviorDwdJob.instance.code -> BehaviorDwdJob.instance,
    UserProductDwsJob.instance.code -> UserProductDwsJob.instance,
    FunnelAdsJob.instance.code -> FunnelAdsJob.instance,
    LocalJsonParquetJob.instance.code -> LocalJsonParquetJob.instance
  )

  /** 依赖：jobCode -> 其前置作业 code 列表（§5.3.2，供阶段 6 流水线使用） */
  val dependencies: Map[String, List[String]] = Map(
    "odl" -> List.empty,
    "bdw" -> List("odl"),
    "usw" -> List("bdw"),
    "fna" -> List("usw"),
    "ljp" -> List.empty // 本地验证作业，独立运行
  )

  def lookup(code: String): Option[WarehouseJob] = jobs.get(code)

  def allCodes: List[String] = jobs.keys.toList.sorted

  def hasCycle: Boolean = false // 首批无环；扩充时用拓扑校验（阶段 6）
}