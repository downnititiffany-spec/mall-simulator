import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const readView = (name) => fs.readFileSync(path.join(here, `../src/views/${name}.vue`), 'utf8')
const behavior = readView('Behavior')
const sales = readView('Sales')

test('Behavior 日期输入在 loading 期间锁定，load handler 自身也拒绝重入', () => {
  assert.equal((behavior.match(/v-model="(?:from|to)" :disabled="loading"/g) || []).length, 2)
  assert.match(behavior, /const load = \(\) => \{\s*if \(loading\.value\) return\s*return analysis\.load/)
})

test('Behavior 漏斗 CSV 资格要求整页 ready 且 stages 子集非空', () => {
  assert.match(behavior, /const funnelExportable = computed\(\(\) => exportable\.value && stages\.value\.length > 0\)/)
  assert.match(behavior, /:disabled="!funnelExportable" @click="doExport"/)
  assert.match(behavior, /function doExport\(\) \{\s*if \(!funnelExportable\.value\) return/)
  assert.match(behavior, /rows: stages\.value\.map/)
})

test('Sales 日期输入在 loading 期间锁定', () => {
  assert.equal((sales.match(/v-model="(?:from|to)" :disabled="loading"/g) || []).length, 2)
  assert.match(sales, /:disabled="loading" @click="load"/)
})

test('Sales load handler 在重置页码和请求前拒绝 loading 重入', () => {
  const block = sales.match(/const load = \(\) => \{[\s\S]*?\n\}/)
  assert.ok(block, '未找到 Sales load handler')
  const guard = block[0].indexOf('if (loading.value) return')
  const reset = block[0].indexOf('page.value = 1')
  const request = block[0].indexOf('analysis.load')
  assert.ok(guard >= 0, '缺少 loading handler guard')
  assert.ok(reset > guard, 'loading guard 必须早于页码重置')
  assert.ok(request > reset, '请求必须发生在页码重置之后')
})
