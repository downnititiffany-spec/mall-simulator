package com.graduation.analytics.algorithm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QuartileStatsRfmSpec extends AnyFlatSpec with Matchers {

  "QuartileStats" should "线性插值分位点正确" in {
    val sorted = Array(1.0, 2.0, 3.0, 4.0, 5.0)
    QuartileStats.quantile(sorted, 0.5) should be(3.0)
    QuartileStats.quantile(sorted, 0.25) should be(2.0)
    QuartileStats.quantile(sorted, 0.75) should be(4.0)
  }

  it should "正向五分位打分：越大分越高" in {
    val values = Array(1.0, 2.0, 3.0, 4.0, 5.0)
    QuartileStats.score(5.0, values, descending = false) should be(5)
    QuartileStats.score(1.0, values, descending = false) should be(1)
    QuartileStats.score(3.0, values, descending = false) should be(3)
  }

  it should "反向五分位打分：间隔越小分越高（R 维度）" in {
    val values = Array(5.0, 10.0, 20.0, 50.0, 100.0)
    QuartileStats.score(6.0, values, descending = true) should be(5)
    QuartileStats.score(90.0, values, descending = true) should be(1)
  }

  "RfmScorer" should "八类标签映射正确" in {
    RfmScorer.octant(5, 5, 5) should be("重要价值")
    RfmScorer.octant(5, 2, 5) should be("重要发展")
    RfmScorer.octant(2, 5, 5) should be("重要保持")
    RfmScorer.octant(1, 1, 4) should be("重要挽留")
    RfmScorer.octant(5, 5, 2) should be("一般价值")
    RfmScorer.octant(5, 1, 1) should be("一般发展")
    RfmScorer.octant(1, 5, 1) should be("一般保持")
    RfmScorer.octant(1, 1, 1) should be("一般挽留")
  }

  it should "生命周期状态判定正确" in {
    RfmScorer.lifecycle(3, isNew = true, inactiveDays = 0) should be("新用户")
    RfmScorer.lifecycle(5, isNew = false, inactiveDays = 10) should be("活跃")
    RfmScorer.lifecycle(60, isNew = false, inactiveDays = 40) should be("沉默")
    RfmScorer.lifecycle(200, isNew = false, inactiveDays = 120) should be("流失风险")
  }
}