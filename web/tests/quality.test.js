// S3-28：`quality` 展示文案的统一属主测试（纯函数）＋ 两视图消费的结构守卫。
// 契约 docs/contracts/analysis-viewmodel-r7-4.md v1.6（字段与键集语义）/ v1.8（读侧消费）。
// 说明：本文件只读源码文本，不构成 SFC 编译或 DOM 渲染证据（web/node_modules 不存在）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { qualitySummaryText, ruleVersionText, RULE_VERSION_NOTE } from '../src/utils/quality.js'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const sales = read('../src/views/Sales.vue')
const overview = read('../src/views/Overview.vue')

test('qualitySummaryText：通过数与失败规则统一成一句，缺值走占位不臆造', () => {
  assert.equal(
    qualitySummaryText({ ruleCount: 4, passedCount: 3, failedRules: ['A_B', 'C_D'] }),
    '规则 3/4 通过，失败规则：A_B、C_D'
  )
  assert.equal(qualitySummaryText({ ruleCount: 4, passedCount: 4, failedRules: [] }), '规则 4/4 通过，失败规则：无')
  assert.equal(qualitySummaryText({}), '规则 —/— 通过，失败规则：无')
  assert.equal(qualitySummaryText(null), '规则 —/— 通过，失败规则：无')
})

test('ruleVersionText：按规则码升序、原样带 v 前缀', () => {
  assert.equal(ruleVersionText({ B_RULE: 2, A_RULE: 1 }), 'A_RULE=v1、B_RULE=v2')
  assert.equal(ruleVersionText({ A_RULE: 0 }), 'A_RULE=v0')
})

test('ruleVersionText：无任何版本记录 ⇒ 明确「无版本记录」，不显示 v1、不留空', () => {
  assert.equal(ruleVersionText({}), '无版本记录')
  assert.equal(ruleVersionText(null), '无版本记录')
  assert.equal(ruleVersionText(undefined), '无版本记录')
  assert.equal(ruleVersionText('x'), '无版本记录')
  assert.doesNotMatch(ruleVersionText({}), /v1/)
})

test('ruleVersionText：键在但值不可解析 ⇒ 标「未记录版本」，不臆造版本号', () => {
  assert.equal(ruleVersionText({ A_RULE: null }), 'A_RULE=未记录版本')
  assert.equal(ruleVersionText({ A_RULE: '' }), 'A_RULE=未记录版本')
  assert.equal(ruleVersionText({ A_RULE: 'abc' }), 'A_RULE=未记录版本')
})

test('RULE_VERSION_NOTE：写明键集是子集、不补 0、不冒充 v1', () => {
  assert.equal(typeof RULE_VERSION_NOTE, 'string')
  assert.match(RULE_VERSION_NOTE, /子集/)
  assert.match(RULE_VERSION_NOTE, /不补 0/)
  assert.match(RULE_VERSION_NOTE, /不冒充 v1/)
})

test('两个视图都消费 ruleVersions：import 统一属主并渲染，且不各写一份文案', () => {
  for (const src of [sales, overview]) {
    assert.match(src, /from '\.\.\/utils\/quality'/)
    assert.match(src, /qualitySummaryText/)
    assert.match(src, /ruleVersionText/)
    assert.match(src, /quality\.ruleVersions|ruleVersions/)
    // 反证：视图内不得自己拼失败规则/版本列表（唯一属主在 utils/quality.js）
    assert.doesNotMatch(src, /failedRules\.join/)
    assert.doesNotMatch(src, /Object\.keys\([^)]*ruleVersions/)
  }
  for (const src of [sales, overview]) {
    assert.doesNotMatch(src, /不补 0/)
  }
  assert.match(sales, /质量规则/)
  assert.match(overview, /数据质量/)
})
