package com.graduation.analytics.algorithm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FunnelHeatAnomalySpec extends AnyFlatSpec with Matchers {

  "FunnelComputer" should "四阶段转化率与整体购买转化率正确" in {
    // 100 浏览、50 意向、20 下单、10 支付
    val funnel = FunnelComputer.compute(100, 50, 20, 10)
    funnel.map(_.stage) should be(Seq("view", "intent", "order", "pay"))
    funnel(0).conversionRate should be(None)
    funnel(1).conversionRate.get should be(0.5)
    funnel(2).conversionRate.get should be(0.4)
    funnel(3).conversionRate.get should be(0.5)
    FunnelComputer.overallBuyRate(10, 100).get should be(0.1)
  }

  it should "分母为 0 时返回 None" in {
    FunnelComputer.compute(0, 0, 0, 0).map(_.conversionRate) should be(
      Seq(None, None, None, None))
    FunnelComputer.overallBuyRate(0, 0) should be(None)
  }

  "ProductHeat" should "对数权重热度：购买>加购>收藏>浏览" in {
    val base = ProductHeat.heat(100, 0, 0, 0)
    ProductHeat.heat(100, 1, 0, 0) should be > base
    ProductHeat.heat(100, 0, 1, 0) should be > ProductHeat.heat(100, 1, 0, 0)
    ProductHeat.heat(100, 0, 0, 1) should be > ProductHeat.heat(100, 0, 1, 0)
  }

  it should "头部贡献度计算（长尾）" in {
    // 4 商品销售额 [40,30,20,10]，累计 80% 需要前 3 个（75%→90% 区间）
    val share = ProductHeat.contributionShare(Array(40.0, 30.0, 20.0, 10.0), 0.9)
    // 前 3 个达 90%→100%（80 之前差一点：70/100=0.7，90/100=0.9 → 需第 3 个到 0.9）
    share should be(0.75)
  }

  "AnomalyDetector" should "z-score 阈值判定" in {
    val window = Seq(10.0, 12.0, 11.0, 13.0, 10.5, 11.5, 12.5)
    AnomalyDetector.zScore(11.0, window).get.abs should be < 2.5
    AnomalyDetector.isCandidateAnomaly(50.0, window) should be(true)
    AnomalyDetector.isCandidateAnomaly(11.0, window) should be(false)
    AnomalyDetector.zScore(1.0, Seq(1.0)) should be(None)
  }

  it should "IQR 边界与维度贡献" in {
    val bounds = AnomalyDetector.iqrBounds(Seq(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
    bounds.get._1 should be < 3.0
    bounds.get._2 should be > 6.0
    AnomalyDetector.dimContribution(30.0, 10.0, 40.0) should be(0.5)
    AnomalyDetector.dimContribution(30.0, 10.0, 0.0) should be(0.0)
  }

  "Cleaners" should "时间标准化与校验谓词" in {
    Cleaners.normalizeTime("2026-09-01T10:15:31+08:00") should be(Some("2026-09-01 10:15:31"))
    Cleaners.normalizeTime("2026-09-01 10:15:31") should be(Some("2026-09-01 10:15:31"))
    Cleaners.normalizeTime("bad") should be(None)
    Cleaners.validAmount("123.45") should be(true)
    Cleaners.validAmount("1.234") should be(false)
    Cleaners.validBehaviorType("view") should be(true)
    Cleaners.validBehaviorType("fly") should be(false)
  }
}