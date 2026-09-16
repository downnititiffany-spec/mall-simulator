// 指标口径周期（`metric_value.period`）的解析与展示文案 —— **唯一属主**。
//
// 契约：`docs/contracts/analysis-viewmodel-r7-4.md` 文首 **v1.9** 与 §3.1 v1.9 条。
// 取值域两类：① `day:<yyyy-MM-dd>`（单日口径）；② `window:<start>..<end>`（窗口/观察期口径）。
//
// 纪律：本模块**只解析格式**，不重算数值、不给缺省观察期、不把畸形值"修好" ——
// 畸形/缺失一律 `unknown` + `null` 文案（与 `utils/number.js` 的「空值显示成占位符、
// 不显示成 0」同一条纪律）。视图**不得**各自解析 `window:` 前缀（防第二属主）。
//
// 纯逻辑、无外部依赖 ⇒ 可被 `node:test` 直接 import（见 `tests/metricPeriod.test.js`）。

/** ISO 单日（`yyyy-MM-dd`）；只认这一种形态，"2026-9-1" 之类不收 */
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/

/** 不可识别周期：不猜、不给默认窗口 */
const UNKNOWN = Object.freeze({ kind: 'unknown', start: null, end: null })

export const PERIOD_KIND_DAY = 'day'
export const PERIOD_KIND_WINDOW = 'window'

/**
 * 窗口口径指标的**限制说明**（唯一属主；视图只引用不自拼）。
 *
 * 口径边界：数值是**观察期**口径（与该行 `period` 同源），**不**与本页单日指标同期；
 * 数值来自快照 `metric_value` 的**原样透传**，页面与导出都不重算。
 */
export const WINDOW_METRIC_NOTE =
  '窗口型指标（如有效复购率）：数值是观察期口径，与页面单日指标不同期；观察期取自快照声明（metric_value.period），页面不重算。'

/** `yyyy-MM-dd` 形态校验 */
export function isIsoDay(value) {
  return typeof value === 'string' && ISO_DAY.test(value.trim())
}

/**
 * 解析 `metric_value.period`。
 * @param {unknown} period 后端原样透传值
 * @returns {{kind: 'day'|'window'|'unknown', start: string|null, end: string|null}}
 *   `kind='unknown'` 时 start/end 一律 null（畸形不猜）。
 */
export function parseMetricPeriod(period) {
  if (typeof period !== 'string') return UNKNOWN
  const raw = period.trim()
  const sep = raw.indexOf(':')
  if (sep <= 0) return UNKNOWN
  const kind = raw.slice(0, sep)
  const rest = raw.slice(sep + 1)

  if (kind === PERIOD_KIND_DAY) {
    const day = rest.trim()
    return isIsoDay(day) ? { kind: PERIOD_KIND_DAY, start: day, end: day } : UNKNOWN
  }

  if (kind === PERIOD_KIND_WINDOW) {
    const dots = rest.indexOf('..')
    if (dots < 0) return UNKNOWN
    const start = rest.slice(0, dots).trim()
    const end = rest.slice(dots + 2).trim()
    const hasStart = isIsoDay(start)
    const hasEnd = isIsoDay(end)
    // 两端都不可识别 ⇒ 不给"缺省观察期"，如实判 unknown
    if (!hasStart && !hasEnd) return UNKNOWN
    return { kind: PERIOD_KIND_WINDOW, start: hasStart ? start : null, end: hasEnd ? end : null }
  }

  return UNKNOWN
}

/** 是否为窗口/观察期口径（视图用来决定"要不要提示不是当日值"） */
export function isWindowPeriod(period) {
  return parseMetricPeriod(period).kind === PERIOD_KIND_WINDOW
}

/**
 * 观察期展示文案：**只有**窗口口径才有值（单日/畸形 ⇒ `null`，视图不渲染副标题）。
 * @returns {string|null}
 */
export function periodText(period) {
  const parsed = parseMetricPeriod(period)
  if (parsed.kind !== PERIOD_KIND_WINDOW) return null
  if (parsed.start && parsed.end) {
    return parsed.start === parsed.end ? `观察期 ${parsed.start}` : `观察期 ${parsed.start} ~ ${parsed.end}`
  }
  if (parsed.start) return `观察期 ${parsed.start} 起`
  return `观察期 至 ${parsed.end}`
}
