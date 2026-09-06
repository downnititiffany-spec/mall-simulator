package com.graduation.analytics.algorithm

/**
 * 用户级转化漏斗（§21.4 宽松口径）：各阶段人数与阶段转化率。
 * 分母为 0 → None（页面显示"无可计算数据"）。
 */
object FunnelComputer {

  case class Stage(stage: String, users: Long, conversionRate: Option[Double])

  /** view/intent/order/pay 四阶段宽口径漏斗 */
  def compute(viewUsers: Long, intentUsers: Long, orderUsers: Long, payUsers: Long): Seq[Stage] = {
    def rate(next: Long, prev: Long): Option[Double] =
      if (prev == 0) None else Some(BigDecimal(next.toDouble / prev).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble)

    Seq(
      Stage("view", viewUsers, None),
      Stage("intent", intentUsers, rate(intentUsers, viewUsers)),
      Stage("order", orderUsers, rate(orderUsers, intentUsers)),
      Stage("pay", payUsers, rate(payUsers, orderUsers))
    )
  }

  /** 整体购买转化率 = 支付用户/浏览用户 */
  def overallBuyRate(payUsers: Long, viewUsers: Long): Option[Double] =
    if (viewUsers == 0) None
    else Some(BigDecimal(payUsers.toDouble / viewUsers).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble)
}