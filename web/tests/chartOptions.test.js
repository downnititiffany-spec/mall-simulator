// 图表 option 构造与表格分页/排序的纯函数测试（指导书 §18.4）
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  salesTrendOption,
  activeTrendOption,
  funnelOption,
  productHeatOption,
  productConversionOption,
  rfmMatrixOption,
  sortRows,
  paginate
} from '../src/utils/chartOptions.js'

test('销售趋势 option 的数值原样来自后端，不做换算', () => {
  const opt = salesTrendOption([{ date: '2026-09-01', orderCount: 5, saleAmount: 2042, buyerCount: 3 }])
  assert.deepEqual(opt.xAxis.data, ['2026-09-01'])
  assert.deepEqual(opt.series[0].data, [2042])
  assert.deepEqual(opt.series[1].data, [5])
  assert.deepEqual(opt.series[2].data, [3])
})

test('趋势数组缺失或为空时系列仍存在，长度一致（避免 ECharts 报错）', () => {
  for (const bad of [undefined, null, 'x', []]) {
    const opt = salesTrendOption(bad)
    assert.equal(opt.xAxis.data.length, 0)
    assert.equal(opt.series.length, 3)
    opt.series.forEach((s) => assert.deepEqual(s.data, []))
  }
})

test('缺失字段映射为 null，不伪造 0', () => {
  const opt = activeTrendOption([{ date: '2026-09-01' }])
  assert.deepEqual(opt.series[0].data, [null])
  assert.deepEqual(opt.series[1].data, [null])
})

test('漏斗阶段名优先用后端 label，回退 stage', () => {
  const opt = funnelOption([{ stage: 'view', label: '浏览', users: 3 }])
  assert.equal(opt.series[0].data[0].name, '浏览')
  assert.equal(opt.series[0].data[0].value, 3)
  const fallback = funnelOption([{ stage: 'pay', users: 1 }])
  assert.equal(fallback.series[0].data[0].name, 'pay')
})

test('商品热度排行按后端顺序展示（不前端重排）', () => {
  const opt = productHeatOption([{ productName: '甲', heat: 9.5 }, { productName: '乙', heat: 8.1 }])
  assert.deepEqual(opt.yAxis.data, ['甲', '乙'])
  assert.deepEqual(opt.series[0].data, [9.5, 8.1])
  // 商品名为空时回退商品 ID，不出现空标签
  assert.deepEqual(productHeatOption([{ productId: 11 }]).yAxis.data, [11])
})

test('RFM 八类固定输出，缺失类目补 0', () => {
  const labels = ['重要价值', '重要发展', '重要保持', '重要挽留', '一般价值', '一般发展', '一般保持', '一般挽留']
  const opt = rfmMatrixOption([{ valueGroup: '重要价值', users: 1 }], labels, {})
  assert.deepEqual(opt.xAxis.data, labels)
  assert.equal(opt.series[0].data[0], 1)
  assert.deepEqual(opt.series[0].data.slice(1), [0, 0, 0, 0, 0, 0, 0])
})

test('RFM 类目以后端下发名为准（本期实际为「高价值」等），不得索引成 0', () => {
  // 页面把后端类目名与兜底八类名合并后传入；后端类目必须拿到真实人数
  const backend = [{ valueGroup: '高价值', users: 1, amount: 1496 }]
  const merged = [...new Set([...backend.map((s) => s.valueGroup), '重要价值', '一般发展'])]
  const opt = rfmMatrixOption(backend, merged, {})
  assert.deepEqual(opt.xAxis.data, ['高价值', '重要价值', '一般发展'])
  assert.deepEqual(opt.series[0].data, [1, 0, 0])
})

test('排序：数值列升/降序，空值恒排末尾', () => {
  const rows = [{ v: 3 }, { v: null }, { v: 1 }, { v: 2 }]
  assert.deepEqual(sortRows(rows, 'v', 'asc').map((r) => r.v), [1, 2, 3, null])
  assert.deepEqual(sortRows(rows, 'v', 'desc').map((r) => r.v), [3, 2, 1, null])
  // 原数组不被修改
  assert.deepEqual(rows.map((r) => r.v), [3, null, 1, 2])
})

test('排序：文本列按中文比较', () => {
  const rows = [{ n: '丙' }, { n: '甲' }, { n: '乙' }]
  assert.equal(sortRows(rows, 'n', 'asc').length, 3)
  assert.equal(sortRows(null, 'n', 'asc').length, 0)
})

test('分页：页码越界夹回有效范围', () => {
  const rows = Array.from({ length: 25 }, (_, i) => ({ i }))
  const p1 = paginate(rows, 1, 10)
  assert.equal(p1.items.length, 10)
  assert.equal(p1.totalPages, 3)
  assert.equal(p1.total, 25)
  const p3 = paginate(rows, 3, 10)
  assert.equal(p3.items.length, 5)
  assert.equal(paginate(rows, 99, 10).page, 3)
  assert.equal(paginate(rows, 0, 10).page, 1)
  assert.equal(paginate([], 1, 10).totalPages, 1)
  assert.equal(paginate(rows, 1, 0).pageSize, 10)
})
