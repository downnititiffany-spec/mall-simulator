import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Ops.vue'), 'utf8')

test('运维流水线表使用服务端分页元数据与白名单排序参数', () => {
  assert.match(source, /api\.pipelineRunsPage\(\{ page: runPage\.value, size: runPageSize\.value, sort: runSort\.value \}, \{ signal \}\)/)
  assert.match(source, /runTotal\.value = Number\(pipelinePage && pipelinePage\.total\) \|\| 0/)
  assert.match(source, /runTotalPages\.value = Math\.max\(1, Number\(pipelinePage && pipelinePage\.totalPages\) \|\| 1\)/)
  assert.doesNotMatch(source, /api\.pipelineRuns\(10, \{ signal \}\)/)
})

test('运维流水线分页控件在翻页、改页大小与排序时请求新页面', () => {
  assert.match(source, /第 \{\{ runPage \}\} \/ \{\{ runTotalPages \}\} 页/)
  assert.match(source, /:value="100"/)
  assert.match(source, /@click="changeRunPage\(runPage - 1\)"/)
  assert.match(source, /@click="changeRunPage\(runPage \+ 1\)"/)
  for (const [name, nextName] of [['changeRunPage', 'changeRunPageSize'], ['changeRunPageSize', 'changeRunSort']]) {
    const start = source.indexOf(`function ${name}`)
    const end = source.indexOf(`function ${nextName}`, start + 1)
    const body = source.slice(start, end)
    assert.match(body, /return load\(\{\}\)/)
  }
  assert.match(source, /导出本页流水线 CSV/)
})
