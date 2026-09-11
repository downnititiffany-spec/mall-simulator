// 图表四态判定（契约 §4 / 指导书 §18.4）
// 纯逻辑：给定“请求状态 + 是否已有可用旧数据”，返回四态之一。
// 状态取值：
//   loading 首次加载且屏上无数据
//   stale   切换筛选后旧数据仍在屏、新请求未回来（必须显示“数据更新中”并禁止导出）
//   error   请求失败（失败优先于旧数据展示，页面同时保留旧数据并显示失败原因）
//   empty   请求成功但没有数据行（非错误，不得当成失败）
//   ready   请求成功且有数据行
// 说明：stale 只在“请求进行中且屏上已有旧数据”时成立；请求失败属于确定事实，
// 不能让 stale 把它盖成“更新中”，因此 error 优先。

/** 请求状态常量（与分析页保持一致） */
export const REQUEST = {
  IDLE: 'idle',
  LOADING: 'loading',
  READY: 'ready',
  ERROR: 'error'
}

/**
 * @param {string} requestStatus REQUEST 之一
 * @param {number} pointCount 当前屏上可渲染的数据点数（可为旧数据）
 * @returns {'loading'|'stale'|'error'|'empty'|'ready'}
 */
export function chartState(requestStatus, pointCount) {
  const hasData = Number.isFinite(pointCount) && pointCount > 0
  if (requestStatus === REQUEST.LOADING) {
    // 有旧数据 → stale（旧数据仍在屏）；无旧数据 → loading
    return hasData ? 'stale' : 'loading'
  }
  if (requestStatus === REQUEST.ERROR) {
    // 失败优先：页面保留旧数据，但四态如实汇报 error，避免把失败静默成正常
    return 'error'
  }
  return hasData ? 'ready' : 'empty'
}

/** 是否允许导出：加载中/失败/无数据/旧数据在屏时一律禁止，避免导出错版数据 */
export function canExport(state) {
  return state === 'ready'
}

/** 四态中文文案 */
export function stateText(state) {
  if (state === 'loading') return '数据加载中'
  if (state === 'stale') return '数据更新中'
  if (state === 'error') return '数据加载失败'
  if (state === 'empty') return '暂无数据'
  return ''
}

/** 数据行估算：按端点字段名取行数，供四态判定使用（取不到按 0 计） */
export function rowCount(data, keys) {
  if (!data || typeof data !== 'object') return 0
  for (const key of keys) {
    const v = data[key]
    if (Array.isArray(v) && v.length > 0) return v.length
  }
  return 0
}

// 各端点用于“是否有数据”判定的业务字段（契约 §3）
export const ENDPOINT_ROW_KEYS = {
  overview: ['salesTrend', 'activeTrend', 'metrics'],
  sales: ['trend'],
  products: ['hot', 'conversion'],
  funnel: ['stages'],
  users: ['rfmSegments', 'lifecycle', 'preference'],
  rfm: ['rfmSegments', 'rfmMatrix', 'distribution']
}

