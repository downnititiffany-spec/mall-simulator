// 数值与比例格式化（契约 §1.4：比例返回 decimal，前端统一格式化）
// 纯逻辑，无外部依赖，可被 node:test 覆盖。
// 约定：null / undefined / 非数字一律返回占位符 '—'，绝不把空值显示成 0。

export const EMPTY_TEXT = '—'

/** 是否可用数字（null/''/NaN 均视为不可用；0 与 '0' 可用） */
export function isNumeric(value) {
  if (value === null || value === undefined || value === '') return false
  return Number.isFinite(Number(value))
}

/**
 * 数值格式化：千分位 + 固定小数位。
 * @param {unknown} value 数值（来自后端，禁止前端重算）
 * @param {number} digits 小数位
 * @param {string} empty 不可用时的占位符
 */
export function formatNumber(value, digits = 2, empty = EMPTY_TEXT) {
  if (!isNumeric(value)) return empty
  const n = Number(value)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** 整数格式化（人数、订单数、PV 等计数指标） */
export function formatInteger(value, empty = EMPTY_TEXT) {
  if (!isNumeric(value)) return empty
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

/**
 * decimal 比例转百分比文本：0.6 -> '60.00%'。
 * 除数为 0 或缺失时返回占位符（对应指导书“不可计算”）。
 */
export function formatPercent(decimal, digits = 2, empty = EMPTY_TEXT) {
  if (!isNumeric(decimal)) return empty
  return (Number(decimal) * 100).toFixed(digits) + '%'
}

/** 后端已给百分数（如 60 表示 60%）时直接拼接百分号 */
export function formatPercentValue(value, digits = 2, empty = EMPTY_TEXT) {
  if (!isNumeric(value)) return empty
  return Number(value).toFixed(digits) + '%'
}
