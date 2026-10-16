// S3-61：阶段5 只消费 metrics[*].definitionVersion；页面不得猜版本、补默认 v1，
// 也不得只给 repeat_rate 特判。这里用源码结构守卫钉住 Overview 的卡片与 CSV 接线；
// 真 Vue/browser 渲染仍留给后续 E2E，不把文本守卫冒充运行时证据。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const overviewPath = path.resolve(here, '../src/views/Overview.vue')
const source = fs.readFileSync(overviewPath, 'utf8')

test('Overview 指标卡显式展示口径版本，缺失时仍有可见占位而不是静默丢字段', () => {
  assert.match(source, /口径版本：\{\{\s*m\.definitionVersionText\s*\}\}/)
  assert.match(source, /const definitionVersionText = \(value\) =>/)
  assert.match(source, /value === null \|\| value === undefined \|\| value === '' \? '—' : String\(value\)/)
})

test('固定清单指标与清单外指标都从后端 definitionVersion 搬运，不只给 repeat_rate 特判', () => {
  const matches = source.match(/definitionVersionText:\s*definitionVersionText\(m\.definitionVersion\)/g) || []
  assert.equal(matches.length, 2, '固定清单与清单外两条 cards 路径都必须搬运 definitionVersion')
})

test('Overview CSV 同步导出口径版本，与页面卡片使用同一已格式化字段', () => {
  assert.match(source, /headers:\s*\['指标编码', '指标名称', '数值', '单位', '口径周期', '口径版本'\]/)
  assert.match(source, /c\.periodText \|\| '—',\s*\n\s*c\.definitionVersionText/)
})

test('页面不硬编码任何默认 definition version，也不擅自给版本拼 v 前缀', () => {
  const fnStart = source.indexOf('const definitionVersionText =')
  const cardsStart = source.indexOf('const cards = computed', fnStart)
  assert.ok(fnStart >= 0 && cardsStart > fnStart, '必须能定位 definitionVersionText 实现')
  const implementation = source.slice(fnStart, cardsStart)
  assert.doesNotMatch(implementation, /['"]v\d+['"]/i)
  assert.doesNotMatch(implementation, /['"]unknown['"]/i)
  assert.doesNotMatch(implementation, /['"]v['"]\s*\+/)
})
