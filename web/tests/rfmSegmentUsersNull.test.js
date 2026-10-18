// RFM 分层用户数 null 保留（不强转 0）守卫测试（U02-S02）
// 依据：《现有功能完善实施指导书 V1.0》§5 U02、问题 UP-20261018-12（P2）
// 规约：mall-simulator-notes/U02-S02.md
//
// 说明：本测试为源码结构守卫（web 未装 SFC 编译器，segmentRows 为 SFC 内未导出 computed）。
// 断言作用域严格限定在 segmentRows 映射及分层明细表格片段内，杜绝无锚点宽泛匹配。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { formatInteger } from '../src/utils/number.js'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const rfmSource = read('../src/views/Rfm.vue')

function extractSegmentRowsSnippet(source) {
  const start = source.indexOf('const segmentRows = computed(')
  assert.ok(start !== -1, 'Rfm.vue 必须在源码中以 const segmentRows = computed 形式声明')
  const end = source.indexOf('const lifecycle = computed(', start)
  assert.ok(end !== -1, 'segmentRows 之后必须紧随 lifecycle computed 声明')
  return source.slice(start, end)
}

function extractTableRowSnippet(source) {
  const start = source.indexOf('<tr v-for="s in segmentRows"')
  assert.ok(start !== -1, 'Rfm.vue 必须包含 segmentRows 表格循环渲染行')
  const end = source.indexOf('</tr>', start)
  assert.ok(end !== -1, 'segmentRows 表格渲染行必须闭合')
  return source.slice(start, end + 5)
}

test('segmentRows 映射片段内 users 表达式保留 null，缺失时不强转 0', () => {
  const snippet = extractSegmentRowsSnippet(rfmSource)
  // 必须采用三元保留 null 形态：s.users === undefined || s.users === null ? null : s.users
  assert.match(
    snippet,
    /users:\s*s\.users === undefined \|\| s\.users === null \? null : s\.users,/,
    'segmentRows 内 users 映射必须为 undefined/null 时保留 null'
  )
})

test('segmentRows 映射片段内不存在 ? 0 : s.users 强转形态', () => {
  const snippet = extractSegmentRowsSnippet(rfmSource)
  assert.doesNotMatch(
    snippet,
    /\? 0 : s\.users/,
    'segmentRows 映射中禁止把 users 的缺失强转为 0'
  )
  assert.doesNotMatch(
    snippet,
    /users:\s*.*?\? 0 :/,
    'users 映射禁止以任何三元条件强转为 0'
  )
})

test('segmentRows 映射片段内 amount 与 avgRecencyDays 遇 undefined 保留 null，三字段空值口径一致', () => {
  const snippet = extractSegmentRowsSnippet(rfmSource)
  assert.match(
    snippet,
    /amount:\s*s\.amount === undefined \? null : s\.amount,/,
    'amount 字段遇 undefined 必须保留 null'
  )
  assert.match(
    snippet,
    /avgRecencyDays:\s*s\.avgRecencyDays === undefined \? null : s\.avgRecencyDays/,
    'avgRecencyDays 字段遇 undefined 必须保留 null'
  )
})

test('Rfm.vue 表格仍以 formatInteger(s.users) 渲染用户数列，保证 null 显示「—」且真实 0 显示「0」', () => {
  const tableSnippet = extractTableRowSnippet(rfmSource)
  assert.match(
    tableSnippet,
    /<td class="mono">\{\{\s*formatInteger\(s\.users\)\s*\}\}<\/td>/,
    '表格用户数列必须通过 formatInteger(s.users) 渲染'
  )
  // 下游 formatInteger 渲染行为链验证
  assert.equal(formatInteger(null), '—', 'formatInteger(null) 必须输出占位符 —')
  assert.equal(formatInteger(undefined), '—', 'formatInteger(undefined) 必须输出占位符 —')
  assert.equal(formatInteger(0), '0', 'formatInteger(0) 必须输出真实 0')
  assert.equal(formatInteger(1234), '1,234', 'formatInteger 正整数正常千分位格式化')
})

test('segmentRows 映射行为语义仿真验证：缺失保留 null，真实 0 与正值原样透传', () => {
  // 仿真 segmentRows 内 map 函数的行为契约
  const mapRow = (s) => ({
    valueGroup: s.valueGroup,
    users: s.users === undefined || s.users === null ? null : s.users,
    amount: s.amount === undefined ? null : s.amount,
    avgRecencyDays: s.avgRecencyDays === undefined ? null : s.avgRecencyDays
  })

  // 1. null 与 undefined 统一保留为 null（显示为 —，导出为空）
  const missingRow = mapRow({ valueGroup: '重要价值', users: null, amount: undefined, avgRecencyDays: null })
  assert.equal(missingRow.users, null)
  assert.equal(missingRow.amount, null)
  assert.equal(missingRow.avgRecencyDays, null)

  const undefinedRow = mapRow({ valueGroup: '重要保持', users: undefined, amount: undefined, avgRecencyDays: undefined })
  assert.equal(undefinedRow.users, null)
  assert.equal(undefinedRow.amount, null)
  assert.equal(undefinedRow.avgRecencyDays, null)

  // 2. 真实 0 必须原样透传为 0（显示为 0，导出为 0）
  const zeroRow = mapRow({ valueGroup: '一般挽留', users: 0, amount: 0, avgRecencyDays: 0 })
  assert.strictEqual(zeroRow.users, 0)
  assert.strictEqual(zeroRow.amount, 0)
  assert.strictEqual(zeroRow.avgRecencyDays, 0)

  // 3. 正常业务数值原样透传
  const normalRow = mapRow({ valueGroup: '重要价值', users: 1500, amount: 98765.43, avgRecencyDays: 3 })
  assert.strictEqual(normalRow.users, 1500)
  assert.strictEqual(normalRow.amount, 98765.43)
  assert.strictEqual(normalRow.avgRecencyDays, 3)
})
