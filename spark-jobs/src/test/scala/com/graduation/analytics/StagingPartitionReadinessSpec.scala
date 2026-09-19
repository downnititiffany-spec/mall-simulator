package com.graduation.analytics

import com.graduation.analytics.job.{OutputPartition, PartitionEvidence}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Stage 7 T-R1 回归：业务专题“当天 0 行”与“分区缺失”必须严格区分。
 *
 * REFERENCE_MALL_HTTP 当前没有通用 behavior endpoint（B-04），因此合法 MALL_API 交易日会出现
 * ads_hot_product / ads_product_conversion 等 0 行专题。只要本次 snapshot+dt 分区真实存在且
 * Hive 元数据能给出 Location，它就是可发布的空态；缺分区或缺 Location 才是发布准备失败。
 */
class StagingPartitionReadinessSpec extends AnyFlatSpec with Matchers {

  private val A = "dw_ads.ads_hot_product__staging"
  private val B = "dw_ads.ads_sale_trend__staging"
  private val Sid = "S_EMPTY_OK"
  private val Dt = "20260918"

  "PartitionEvidence.missingLocatedTables" should "接受存在且有 Location 的 0 行分区" in {
    val parts = Seq(
      OutputPartition(A, Dt, Some(Sid), 0L, Some("file:/warehouse/ads_hot_product")),
      OutputPartition(B, Dt, Some(Sid), 3L, Some("file:/warehouse/ads_sale_trend")))

    PartitionEvidence.missingLocatedTables(Seq(A, B), parts) should be(empty)
  }

  it should "把完全缺失的目标分区判为 missing" in {
    val parts = Seq(OutputPartition(B, Dt, Some(Sid), 3L, Some("file:/warehouse/ads_sale_trend")))

    PartitionEvidence.missingLocatedTables(Seq(A, B), parts) should be(Seq(A))
  }

  it should "把存在但拿不到 Location 的分区判为 missing，而不看 rowCount 猜就绪" in {
    val parts = Seq(
      OutputPartition(A, Dt, Some(Sid), 99L, None),
      OutputPartition(B, Dt, Some(Sid), 0L, Some("file:/warehouse/ads_sale_trend")))

    PartitionEvidence.missingLocatedTables(Seq(A, B), parts) should be(Seq(A))
  }
}
