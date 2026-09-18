import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const readView = (name) => fs.readFileSync(path.join(here, `../src/views/${name}.vue`), 'utf8')
const behavior = readView('Behavior')
const sales = readView('Sales')
const overview = readView('Overview')
const rfm = readView('Rfm')

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

test('Sales loading 期间拒绝本地排序与翻页，避免新筛选结果落在中途改过的页码', () => {
  assert.match(sales, /function toggleSort\(key\) \{\s*if \(loading\.value\) return/)
  assert.match(sales, /:disabled="loading \|\| paged\.page <= 1" @click="page = paged\.page - 1"/)
  assert.match(sales, /:disabled="loading \|\| paged\.page >= paged\.totalPages" @click="page = paged\.page \+ 1"/)
})

test('Overview 日期输入与 load handler 在 loading 期间 fail-closed', () => {
  assert.equal((overview.match(/v-model="(?:from|to)" :disabled="loading"/g) || []).length, 2)
  assert.match(overview, /const load = \(\) => \{\s*if \(loading\.value\) return\s*return analysis\.load/)
})

test('Overview 指标 CSV 只在 cards 子集非空时允许导出', () => {
  assert.match(overview, /const metricExportable = computed\(\(\) => exportable\.value && cards\.value\.length > 0\)/)
  assert.match(overview, /:disabled="!metricExportable" @click="doExport"/)
  assert.match(overview, /function doExport\(\) \{\s*if \(!metricExportable\.value\) return/)
  assert.match(overview, /const rows = cards\.value\.map/)
})

test('RFM load handler 在清空降级错误与请求前拒绝 loading 重入', () => {
  const block = rfm.match(/const load = \(\) => \{[\s\S]*?\n\}/)
  assert.ok(block, '未找到 RFM load handler')
  const guard = block[0].indexOf('if (loading.value) return')
  const clearError = block[0].indexOf("usersError.value = ''")
  const request = block[0].indexOf('analysis.load')
  assert.ok(guard >= 0, '缺少 loading handler guard')
  assert.ok(clearError > guard, 'loading guard 必须早于 usersError 清理')
  assert.ok(request > clearError, '请求必须发生在错误清理之后')
})

test('RFM CSV 只在真实 segmentRows 子集非空时允许导出', () => {
  assert.match(rfm, /const segmentExportable = computed\(\(\) => exportable\.value && segmentRows\.value\.length > 0\)/)
  assert.match(rfm, /:disabled="!segmentExportable" @click="doExport"/)
  assert.match(rfm, /function doExport\(\) \{\s*if \(!segmentExportable\.value\) return/)
  assert.match(rfm, /rows: segmentRows\.value\.map/)
})
