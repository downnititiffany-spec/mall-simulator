// 02.6 平台独立性：指标时效提示（唯一属主）。
//
// 验收锚点（指导书 V3.1 02.6）：关闭商城/生成器后历史指标仍可读；来源停机要显示
// 时效警告，不假报最新。派生口径：页面所显示指标 cell（/metrics/overview 的
// metrics 数组，后端原样透传的 period 字段）中的最大业务日期 —— day 取该日、
// window 取观察期两端 —— 与本地今日比较。
//
// 角色无关：只消费人人可读的指标载荷本身，不调用 /sources（分析师无
// RUNTIME_MANAGE 权限，向导面才需要）。畸形/未知 period 一律忽略不猜
// （与 utils/metricPeriod.js 同一条纪律）。
//
// 时效语义：滞后 ≥1 天才提示；滞后 0 天返回 null —— 既不渲染横幅，也绝不主动宣称「最新」
// （前端不为来源是否停更背书，只在不新鲜时如实陈述事实）。
// 纯逻辑、无外部依赖 ⇒ 可被 node:test 直接 import（见 tests/overviewStaleness.test.js）。

import { isIsoDay, parseMetricPeriod } from './metricPeriod.js'

/** 指标 cell 列表中的最大业务日期（ISO yyyy-MM-dd）；没有可识别周期 ⇒ null */
export function latestBusinessDate(metrics) {
  let max = null
  const list = Array.isArray(metrics) ? metrics : []
  for (const m of list) {
    if (!m || typeof m.period !== 'string') continue
    const parsed = parseMetricPeriod(m.period)
    for (const day of [parsed.start, parsed.end]) {
      if (day && (!max || day > max)) max = day
    }
  }
  return max
}

/** 自然日差 today − businessDate（同日 0；非 ISO 形态返回 null，不猜） */
export function stalenessLagDays(businessDate, todayIso) {
  if (!isIsoDay(businessDate) || !isIsoDay(todayIso)) return null
  const a = Date.parse(businessDate.trim() + 'T12:00:00Z')
  const b = Date.parse(todayIso.trim() + 'T12:00:00Z')
  if (Number.isNaN(a) || Number.isNaN(b)) return null
  return Math.round((b - a) / 86400000)
}

/**
 * 时效提示：滞后 ≥1 天 ⇒ {businessDate, lagDays, text}；否则 null。
 * text 只陈述事实（业务时点 + 滞后天数 + 历史仍可读），不含「最新/实时」断言。
 */
export function stalenessNotice(metrics, todayIso) {
  const date = latestBusinessDate(metrics)
  if (!date) return null
  const lagDays = stalenessLagDays(date, todayIso)
  if (lagDays === null || lagDays < 1) return null
  return {
    businessDate: date,
    lagDays,
    text: '数据时点提示：页面指标的业务时点为 ' + date + '（滞后 ' + lagDays +
      ' 天）。数据来源可能已停更或采集未运行；历史指标仍可读，但以下数字不代表最新业务日。'
  }
}
