package com.graduation.analytics.algorithm

/**
 * 分位数统计（§21.6）：线性插值分位点 + 五分位打分。
 * R 反向（间隔越小分越高），F/M 正向。
 */
object QuartileStats {

  /** 线性插值分位点（sorted 升序数组，q ∈ (0,1)） */
  def quantile(sorted: Array[Double], q: Double): Double = {
    require(sorted.nonEmpty && q > 0 && q < 1, "需要非空序列且 0<q<1")
    val n = sorted.length
    val pos = q * (n - 1)
    val lo = math.floor(pos).toInt
    val hi = math.ceil(pos).toInt
    if (lo == hi) sorted(lo)
    else sorted(lo) + (sorted(hi) - sorted(lo)) * (pos - lo)
  }

  /** 五等分边界：返回 4 个切点（Q1..Q4），供分箱 */
  def quintileCutPoints(sorted: Array[Double]): Array[Double] =
    Array(quantile(sorted, 0.2), quantile(sorted, 0.4), quantile(sorted, 0.6), quantile(sorted, 0.8))

  /**
   * 按五分位打分 1..5。
   * @param descending true=值越小分越高（R 维度）
   */
  def score(value: Double, sorted: Array[Double], descending: Boolean): Int = {
    if (sorted.isEmpty) return 3
    val cuts = quintileCutPoints(sorted)
    // 落在该切点以下的数量决定档位：正向 = count(<value)+1；
    // 反向 = 5 - count(<value)（最小的值落在第 1 档得 5 分）
    val below = cuts.count(_ < value)
    val rank = if (descending) 5 - below else below + 1
    math.max(1, math.min(5, rank))
  }
}