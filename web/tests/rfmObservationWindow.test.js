import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Rfm.vue'), 'utf8')

test('RFM 只从 rfm 响应搬运 periodStart/periodEnd，不从 users 聚合接口猜观察期', () => {
  assert.match(source, /periodStart:\s*rfm\.data\.periodStart \|\| null/)
  assert.match(source, /periodEnd:\s*rfm\.data\.periodEnd \|\| null/)
  assert.doesNotMatch(source, /periodStart:\s*usersData\./)
  assert.doesNotMatch(source, /periodEnd:\s*usersData\./)
})

test('观察期必须起止都存在才展示完整窗口，任一缺失时显示未提供', () => {
  assert.match(source, /const periodText = computed\(\(\) => \([\s\S]*periodStart\.value && periodEnd\.value[\s\S]*'未提供'/)
  assert.match(source, /观察期：\{\{ periodText \}\}/)
})

test('RFM defaults 显式包含 periodStart/periodEnd null，不能制造默认窗口', () => {
  assert.match(source, /periodStart:\s*null,\s*periodEnd:\s*null/)
  assert.doesNotMatch(source, /periodStart:\s*localIsoDay/)
  assert.doesNotMatch(source, /periodEnd:\s*localIsoDay/)
})

test('RFM CSV 同步导出后端观察期起止，不重算日期', () => {
  assert.match(source, /headers:\s*\['分层', '用户数', '消费额\(元\)', '平均最近购买\(天\)', '观察期开始', '观察期结束'\]/)
  assert.match(source, /periodStart\.value \|\| ''/)
  assert.match(source, /periodEnd\.value \|\| ''/)
  assert.doesNotMatch(source, /new Date\(/)
})
