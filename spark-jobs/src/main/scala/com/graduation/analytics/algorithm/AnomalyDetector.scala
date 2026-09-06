package com.graduation.analytics.algorithm

/**
 * 异常检测（§21.9）：z-score 滚动窗口、IQR 边界、维度贡献拆解 —— 纯函数。
 */
object AnomalyDetector {

  private val Z_THRESHOLD = 2.5

  /** z = (x - mean)/std；窗口 <2 或无波动 → None（改用绝对阈值） */
  def zScore(value: Double, window: Seq[Double]): Option[Double] = {
    if (window.size < 2) return None
    val mean = window.sum / window.size
    val variance = window.map(v => math.pow(v - mean, 2)).sum / (window.size - 1)
    if (variance == 0) None
    else Some((value - mean) / math.sqrt(variance))
  }

  /** |z| >= 2.5 标记候选异常 */
  def isCandidateAnomaly(value: Double, window: Seq[Double]): Boolean =
    zScore(value, window).exists(_.abs >= Z_THRESHOLD)

  /** IQR 边界（偏态分布兜底） */
  def iqrBounds(values: Seq[Double]): Option[(Double, Double)] = {
    if (values.isEmpty) return None
    val sorted = values.sorted.toArray
    val q1 = QuartileStats.quantile(sorted, 0.25)
    val q3 = QuartileStats.quantile(sorted, 0.75)
    val iqr = q3 - q1
    Some((q1 - 1.5 * iqr, q3 + 1.5 * iqr))
  }

  /**
   * 维度贡献拆解（§21.9）：
   * 维度贡献 = 当前值 − 基准值；贡献占比 = 单维度变化 / 总变化。
   */
  def dimContribution(current: Double, baseline: Double, totalDelta: Double): Double =
    if (totalDelta == 0) 0.0 else (current - baseline) / totalDelta
}