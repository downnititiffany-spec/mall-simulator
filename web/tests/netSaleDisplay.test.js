// S3-27 源码结构守卫（无 SFC 编译器：`web/node_modules` 不存在、`vite build` 不可运行）
// 目的：钉住「逐日净销售额 netSaleAmount 确实被阶段5 页面消费」与「限制说明来自单一常量」。
// 只读源码文本做断言，不代表任何渲染结果 —— 不构成 DOM/浏览器走查证据。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const chartOptions = read('../src/utils/chartOptions.js')
const sales = read('../src/views/Sales.vue')
const overview = read('../src/views/Overview.vue')

test('chartOptions 定义单一限制常量 NET_SALE_NOTE 并导出', () => {
  assert.match(chartOptions, /export const NET_SALE_NOTE\s*=/)
})

test('销售趋势图含净销售额柱序列（净销售额＋逐日口径标注）', () => {
  assert.match(chartOptions, /净销售额/)
  assert.match(chartOptions, /netSaleAmount/)
  assert.match(chartOptions, /逐日口径/)
})

test('Sales.vue：趋势图标题、明细列、导出表头都包含净销售额', () => {
  assert.match(sales, /netSaleAmount/)
  assert.match(sales, /净销售额\(元\)/)
  // 表格列定义
  assert.match(sales, /key: 'netSaleAmount'/)
  // 导出表头与行数据
  assert.match(sales, /'净销售额\(元\)'/)
  assert.match(sales, /formatNumber\(r\.netSaleAmount/)
})

test('Sales.vue 与 Overview.vue 都渲染 NET_SALE_NOTE（限制说明不得各写一份）', () => {
  for (const src of [sales, overview]) {
    assert.match(src, /NET_SALE_NOTE/)
    assert.match(src, /import\s*\{[^}]*NET_SALE_NOTE[^}]*\}\s*from\s*'\.\.\/utils\/chartOptions'/)
  }
  // 文案本体只能存在于 chartOptions（两视图不得内联复写关键限制语句）
  for (const src of [sales, overview]) {
    assert.doesNotMatch(src, /退款归属期未冻结/)
  }
})

test('页面不做净销售额换算：不得出现 GMV 与净销售的启发式纠正', () => {
  for (const src of [chartOptions, sales, overview]) {
    assert.doesNotMatch(src, /netSaleAmount\s*[-+*/]\s*/)
    assert.doesNotMatch(src, /Math\.max\([^)]*netSaleAmount/)
  }
})
