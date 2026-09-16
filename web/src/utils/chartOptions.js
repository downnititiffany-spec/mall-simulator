// 分析响应 data → ECharts option 的纯函数构造（指导书 §18.3：后端只返回图表语义数据，
// 前端只做展示映射，不在这里重算任何指标公式）。
// 分页与排序也放在这里，便于 node:test 直接覆盖。

const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v))

/** 友好兜底标签（`||` 只影响图例展示，不参与数值计算） */
const labelOf = (v, fallback) => (typeof v === 'string' && v.trim() !== '' ? v : fallback)

/**
 * 逐日净销售额的限制说明（S3-27，单一常量，两个视图共用，禁止各写一份）。
 * 口径逐字取自 `docs/contracts/analysis-viewmodel-r7-4.md` v1.4 边界 1/2 与 v1.7 展示层口径：
 * 不重算、不把 `0` 当缺值、不把逐日值与快照汇总值互相代入。
 */
export const NET_SALE_NOTE =
  '净销售额为逐日口径（当日支付金额 − 当日成功退款），不是所选区间的净额汇总；退款归属期未冻结，' +
  '跨业务日到账的退款不会回改历史业务日，故不得读作"最终到账净收入"；历史快照的 0 可能是"未计算"占位，' +
  '与"当日零净额"在当前数据上不可区分，0 不得读作"无退款"；上方卡片「净销售额」是快照汇总指标，' +
  '与本图逐日净销售额不是一个东西，两者不得互相代入。'

/** 销售趋势：销售额（柱）+ 净销售额（柱，同轴，逐日口径）+ 订单数/买家数（折线，双轴） */
export function salesTrendOption(trend = []) {
  const rows = Array.isArray(trend) ? trend : []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['销售额', '净销售额', '订单数', '买家数'] },
    grid: { left: 60, right: 60, top: 34, bottom: 40 },
    xAxis: { type: 'category', data: rows.map((r) => r.date) },
    yAxis: [
      { type: 'value', name: '金额' },
      { type: 'value', name: '单数/人数' }
    ],
    series: [
      { name: '销售额', type: 'bar', data: rows.map((r) => num(r.saleAmount)) },
      // 契约 v1.4：值 = ADS 列 net_sale_amount 原样透传；缺失/畸形 = null（图表断点，不臆造 0）
      { name: '净销售额', type: 'bar', data: rows.map((r) => num(r.netSaleAmount)) },
      { name: '订单数', type: 'line', smooth: true, yAxisIndex: 1, data: rows.map((r) => num(r.orderCount)) },
      { name: '买家数', type: 'line', smooth: true, yAxisIndex: 1, data: rows.map((r) => num(r.buyerCount)) }
    ]
  }
}

/** 活跃趋势：DAU（折线）+ 行为量（柱） */
export function activeTrendOption(active = []) {
  const rows = Array.isArray(active) ? active : []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['活跃用户', '行为量'] },
    grid: { left: 60, right: 40, top: 34, bottom: 40 },
    xAxis: { type: 'category', data: rows.map((r) => r.date) },
    yAxis: [
      { type: 'value', name: '人数' },
      { type: 'value', name: '行为量' }
    ],
    series: [
      { name: '活跃用户', type: 'line', smooth: true, data: rows.map((r) => num(r.dau)) },
      { name: '行为量', type: 'bar', yAxisIndex: 1, data: rows.map((r) => num(r.behaviorCount)) }
    ]
  }
}

/** 转化漏斗：阶段人数（横向漏斗），阶段名与转化率由后端下发 */
export function funnelOption(stages = []) {
  const rows = Array.isArray(stages) ? stages : []
  return {
    tooltip: { trigger: 'item' },
    series: [
      {
        type: 'funnel',
        left: 60,
        top: 20,
        bottom: 20,
        width: '70%',
        minSize: '20%',
        label: { formatter: '{b}: {c} 人' },
        data: rows.map((s) => ({
          name: labelOf(s.label, s.stage),
          value: num(s.users)
        }))
      }
    ]
  }
}

/** 商品热度排行：横向条形（按后端 heat 排序结果原样展示） */
export function productHeatOption(hot = []) {
  const rows = Array.isArray(hot) ? hot : []
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 140, right: 40, top: 16, bottom: 40 },
    xAxis: { type: 'value', name: '热度' },
    yAxis: { type: 'category', inverse: true, data: rows.map((r) => labelOf(r.productName, r.productId)) },
    series: [{ name: '热度', type: 'bar', data: rows.map((r) => num(r.heat)) }]
  }
}

/** 商品转化：浏览量用户 vs 支付用户（同轴柱状，避免比例被尺度过大的指标压平） */
export function productConversionOption(conversion = [], nameOfProduct = () => '') {
  const rows = Array.isArray(conversion) ? conversion : []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['浏览用户', '支付用户'] },
    grid: { left: 60, right: 30, top: 34, bottom: 60 },
    xAxis: {
      type: 'category',
      axisLabel: { interval: 0, rotate: 30, width: 90, overflow: 'truncate' },
      data: rows.map((r) => nameOfProduct(r.productId) || String(r.productId))
    },
    yAxis: { type: 'value', name: '用户数' },
    series: [
      { name: '浏览用户', type: 'bar', data: rows.map((r) => num(r.pvUsers)) },
      { name: '支付用户', type: 'bar', data: rows.map((r) => num(r.buyUsers)) }
    ]
  }
}

/** RFM 八类人数分布：固定八类，缺失类目补 0（契约 §3.6），颜色按类别固定 */
export function rfmMatrixOption(segments = [], labels = [], colors = {}) {
  const rows = Array.isArray(segments) ? segments : []
  const byName = {}
  for (const r of rows) {
    const key = labelOf(r.valueGroup, '')
    if (key) byName[key] = num(r.users) || 0
  }
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 30, top: 20, bottom: 60 },
    xAxis: {
      type: 'category',
      axisLabel: { interval: 0, rotate: 20 },
      data: labels
    },
    yAxis: { type: 'value', name: '用户数' },
    series: [
      {
        name: '用户数',
        type: 'bar',
        data: labels.map((l) => byName[l] || 0),
        itemStyle: { color: (p) => colors[labels[p.dataIndex]] || '#3B82F6' }
      }
    ]
  }
}

// ── 表格分页 / 排序（§18.4 要求表格支持分页与排序） ──

/** 排序：空值恒排在末尾，不参与比较 */
export function sortRows(rows = [], key, order = 'asc') {
  const list = Array.isArray(rows) ? rows.slice() : []
  const dir = order === 'desc' ? -1 : 1
  return list.sort((a, b) => {
    const av = a ? a[key] : null
    const bv = b ? b[key] : null
    const aEmpty = av === null || av === undefined || av === ''
    const bEmpty = bv === null || bv === undefined || bv === ''
    if (aEmpty && bEmpty) return 0
    if (aEmpty) return 1
    if (bEmpty) return -1
    const an = Number(av)
    const bn = Number(bv)
    if (Number.isFinite(an) && Number.isFinite(bn)) return (an - bn) * dir
    return String(av).localeCompare(String(bv), 'zh-CN') * dir
  })
}

/** 分页：页码从 1 开始，越界时夹回有效范围 */
export function paginate(rows = [], page = 1, pageSize = 10) {
  const list = Array.isArray(rows) ? rows : []
  const size = Number(pageSize) > 0 ? Number(pageSize) : 10
  const totalPages = Math.max(1, Math.ceil(list.length / size))
  const current = Math.min(Math.max(1, Number(page) || 1), totalPages)
  const start = (current - 1) * size
  return { items: list.slice(start, start + size), page: current, pageSize: size, total: list.length, totalPages }
}
