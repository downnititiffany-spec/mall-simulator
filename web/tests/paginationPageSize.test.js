import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const pipeline = readFileSync(join(ROOT, 'src/views/Pipeline.vue'), 'utf8')
const decisions = readFileSync(join(ROOT, 'src/views/Decisions.vue'), 'utf8')

test('pipeline page size selection is bounded and requests page one with selected size', () => {
  assert.match(pipeline, /v-model\.number="pageSize"[^>]*@change="changePageSize"/)
  for (const size of [10, 20, 50, 100]) assert.ok(pipeline.includes(`<option :value="${size}">${size}</option>`))
  assert.match(pipeline, /function changePageSize\(\) \{\s*if \(loading\.value \|\| busy\.value\) return\s*page\.value = 1\s*return load\(\{ page: 1, size: pageSize\.value, sort: sort\.value \}\)/)
  assert.match(pipeline, /function changeSort\(\) \{\s*if \(loading\.value \|\| busy\.value\) return\s*page\.value = 1\s*return load\(\{ page: 1, size: pageSize\.value, sort: sort\.value \}\)/)
})

test('decision page size selection is bounded, sent to API, and changing it resets page', () => {
  assert.match(decisions, /v-model\.number="pageSize"[^>]*@change="changePageSize"/)
  for (const size of [10, 20, 50, 100]) assert.ok(decisions.includes(`<option :value="${size}">${size}</option>`))
  assert.match(decisions, /decisionPage\(\{ page: params\.page \|\| 1, size: params\.size \|\| pageSize\.value/)
  assert.match(decisions, /function changePageSize\(\) \{\s*if \(loading\.value \|\| busy\.value\) return\s*page\.value = 1\s*return load\(\{ page: 1, size: pageSize\.value, sort: sort\.value \}\)/)
})
