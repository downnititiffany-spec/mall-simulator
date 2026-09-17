// S3-68：decisionRows 已把基线格式化为展示文本；Decisions.vue 不得再次 formatNumber，
// 否则千分位文本（如 2,042.00）会被 Number(...) 判成 NaN 并退化成「—」。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { decisionRows } from '../src/utils/tables.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const view = fs.readFileSync(path.resolve(here, '../src/views/Decisions.vue'), 'utf8')

test('decisionRows 对千位以上基线只格式化一次，得到可直接展示的千分位文本', () => {
  const [row] = decisionRows([{ id: 1, baselineValue: 2042, targetValue: 3000 }])
  assert.equal(row.baselineValue, '2,042.00')
  assert.equal(row.targetValue, '3,000.00')
})

test('Decisions 页面直接展示映射后的 baselineValue，不再次调用 formatNumber', () => {
  assert.match(view, /<td class="mono">\{\{ d\.baselineValue \}\}<\/td>/)
  assert.doesNotMatch(view, /formatNumber\(d\.baselineValue\)/)
  assert.doesNotMatch(view, /import\s*\{\s*formatNumber\s*\}\s*from\s*['"]\.\.\/utils\/number['"]/)
})
