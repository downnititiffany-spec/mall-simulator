package com.graduation.analytics.algorithm

/**
 * RFM 规则分层（§21.6）：R/F/M 五分位评分与中位数比较 → 八类标签。
 * R=最近购买间隔(天)，F=观察期有效支付订单数，M=净支付金额。
 */
object RfmScorer {

  /** r：间隔天数（小=好）；f/m：越大越好 */
  def octant(rScore: Int, fScore: Int, mScore: Int): String = {
    if (mScore >= 4) {
      if (rScore >= 4) {
        if (fScore >= 4) "重要价值" else "重要发展"
      } else {
        if (fScore >= 4) "重要保持" else "重要挽留"
      }
    } else {
      if (rScore >= 4) {
        if (fScore >= 4) "一般价值" else "一般发展"
      } else {
        if (fScore >= 4) "一般保持" else "一般挽留"
      }
    }
  }

  /**
   * 生命周期状态（§5.7.1）：
   * 新用户=观察期内首次购买；沉默=有购买但近 30 天无有效行为；
   * 流失风险=间隔超 60 天；其余=活跃。
   */
  def lifecycle(rDays: Int, isNew: Boolean, inactiveDays: Int): String = {
    if (isNew) "新用户"
    else if (inactiveDays > 60) "流失风险"
    else if (inactiveDays > 30) "沉默"
    else "活跃"
  }
}