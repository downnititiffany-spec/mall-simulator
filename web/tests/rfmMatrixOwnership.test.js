import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Rfm.vue'), 'utf8')

test('RFM 页面优先使用后端 rfmMatrix，而不是把前端固定类目追加到真实结果', () => {
  assert.match(source, /const matrix = Array\.isArray\(data\.value\.rfmMatrix\)/)
  assert.match(source, /const source = matrix\.length[\s\S]*\? matrix[\s\S]*: \(Array\.isArray\(data\.value\.rfmSegments\)/)
  assert.doesNotMatch(source, /const SEGMENTS =/)
  assert.doesNotMatch(source, /\.\.\.SEGMENTS/)
})

test('旧响应缺少 rfmMatrix 时只展示真实 rfmSegments，不制造额外 0 人类目', () => {
  assert.match(source, /Array\.isArray\(data\.value\.rfmSegments\) \? data\.value\.rfmSegments\.filter/)
  assert.doesNotMatch(source, /new Set\(\[\.\.\.list\.map[\s\S]*重要价值/)
})

test('矩阵行仅做字段搬运，缺失数值按既有空值语义处理', () => {
  assert.match(source, /valueGroup: s\.valueGroup/)
  assert.match(source, /users: s\.users === undefined \|\| s\.users === null \? 0 : s\.users/)
  assert.match(source, /amount: s\.amount === undefined \? null : s\.amount/)
  assert.match(source, /avgRecencyDays: s\.avgRecencyDays === undefined \? null : s\.avgRecencyDays/)
})

test('图表与 CSV 都消费同一个 segmentRows，不再产生第二套类别所有者', () => {
  assert.match(source, /rfmMatrixOption\(segmentRows\.value, segmentRows\.value\.map/)
  assert.match(source, /rows: segmentRows\.value\.map/)
  assert.match(source, /function doExport\(\) \{[\s\S]*if \(!exportable\.value\) return/)
})
