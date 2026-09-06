package com.graduation.analytics.algorithm

/**
 * 商品热度（§21.7）：对数缩放加权，避免 PV 数量级压制购买。
 * heat = 1×ln(1+PV) + 2×ln(1+收藏) + 3×ln(1+加购) + 5×ln(1+支付件数)
 */
object ProductHeat {

  def heat(pv: Long, fav: Long, cart: Long, buy: Long): Double =
    1.0 * math.log1p(pv.toDouble) +
      2.0 * math.log1p(fav.toDouble) +
      3.0 * math.log1p(cart.toDouble) +
      5.0 * math.log1p(buy.toDouble)

  /** 按销售额贡献度（§21.7 长尾）：达到目标累计比例的头部商品占比 */
  def contributionShare(sortedAmounts: Array[Double], targetRatio: Double): Double = {
    val total = sortedAmounts.sum
    if (total <= 0) return 0.0
    var acc = 0.0
    var i = 0
    while (i < sortedAmounts.length && acc / total < targetRatio) {
      acc += sortedAmounts(i)
      i += 1
    }
    if (sortedAmounts.isEmpty) 0.0 else math.min(1.0, i.toDouble / sortedAmounts.length)
  }
}