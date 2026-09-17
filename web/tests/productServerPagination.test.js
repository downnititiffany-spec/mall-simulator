import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Products.vue'), 'utf8')

test('商品页请求显式携带 page/size/sort，不再把单页结果做本地分页', () => {
  assert.match(source, /page: page\.value/)
  assert.match(source, /size: pageSize\.value/)
  assert.match(source, /sort: `\$\{sortKey\.value\},\$\{sortOrder\.value\}`/)
  assert.doesNotMatch(source, /\bsortRows\b/)
  assert.doesNotMatch(source, /\bpaginate\b/)
})

test('分页按钮使用后端 page/hasMore 元数据驱动', () => {
  assert.match(source, /const responsePage = computed\(/)
  assert.match(source, /const hasMore = computed\(\(\) => data\.value\.hasMore === true\)/)
  assert.match(source, /@click="goPage\(responsePage - 1\)"/)
  assert.match(source, /@click="goPage\(responsePage \+ 1\)"/)
  assert.match(source, /:disabled="loading \|\| !hasMore"/)
})

test('只开放后端契约允许的商品排序字段，名称和转化率不伪造本地全量排序', () => {
  for (const key of ['rank', 'pv', 'fav', 'cart', 'buy', 'heat']) {
    assert.match(source, new RegExp(`sortKey: '${key}'`))
  }
  assert.match(source, /\{ key: 'productName', title: '商品' \}/)
  assert.match(source, /\{ key: 'conversionRate', title: '转化率' \}/)
})

test('切换排序会回到第 1 页并重新请求后端', () => {
  const start = source.indexOf('function toggleSort(col)')
  assert.notEqual(start, -1)
  const body = source.slice(start, source.indexOf('\nconst nameOfProduct', start))
  assert.match(body, /page\.value = 1/)
  assert.match(body, /return load\(\)/)
})

test('当前页导出资格要求整页 ready 且当前 hot 页确实非空，按钮和 handler 使用同一谓词', () => {
  assert.match(source, />导出当前页 CSV</)
  assert.match(source, /const pageExportable = computed\(\(\) => exportable\.value && rows\.value\.length > 0\)/)
  assert.match(source, /:disabled="!pageExportable"\s+@click="doExport"/)
  assert.match(source, /function doExport\(\) \{[\s\S]*if \(!pageExportable\.value\) return/)
  assert.match(source, /baseName: `product-analysis-page-\$\{responsePage\.value\}`/)
  assert.match(source, /rows: rows\.value\.map/)
})

test('分页大小在加载期间锁定，applyFilters 自身也拒绝 loading 重入', () => {
  assert.match(source, /v-model\.number="pageSize"[^>]*:disabled="loading"/)
  const start = source.indexOf('function applyFilters()')
  const end = source.indexOf('\nfunction goPage', start)
  assert.notEqual(start, -1)
  assert.ok(end > start)
  const body = source.slice(start, end)
  assert.match(body, /if \(loading\.value\) return/)
  assert.match(body, /page\.value = 1/)
  assert.match(body, /return load\(\)/)
})
