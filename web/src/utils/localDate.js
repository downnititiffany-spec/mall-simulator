// HTML date inputs represent a local calendar day, not a UTC instant.
// Never derive their default value through Date#toISOString(), because around
// local midnight that can select yesterday/tomorrow for users outside UTC.
export function localIsoDay(date = new Date()) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function localIsoDayOffset(offsetDays, now = new Date()) {
  const date = new Date(now.getTime())
  date.setDate(date.getDate() + Number(offsetDays || 0))
  return localIsoDay(date)
}

/**
 * 是否为 `<input type="date">` 给出的合法局部日历日（严格 yyyy-MM-dd）。
 * 形状不合（`2026-9-21`、数字、空值）与日历上不存在的日期（2026-02-30）都判 false：
 * 后续的区间比较按字典序，形状不严格时字典序不等于日期序，必须先卡住形状。
 */
export function isIsoDay(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  return (
    date.getFullYear() === year &&
    date.getMonth() === month - 1 &&
    date.getDate() === day
  )
}

/**
 * 区间是否倒置（起始晚于结束）。
 * 这是「日期筛选必须真正生效」的前端守卫（实测评价 §QA-01）：倒置区间后端会 400，
 * 前端不该先发一次注定失败的请求。任一端缺失或形状不合法时**不**报倒置——
 * 宁可让后端按自己的参数校验回答，也不前端猜一个结论。
 */
export function isRangeInverted(from, to) {
  if (!isIsoDay(from) || !isIsoDay(to)) return false
  return from > to
}

/** 倒置区间的页面提示文案（唯一属主，页面只引用不自拼） */
export function dateRangeErrorText(from, to) {
  return `起始日期 ${from || '未选'} 晚于结束日期 ${to || '未选'}，日期区间无效，已取消本次加载；请重新选择后再查询。`
}
