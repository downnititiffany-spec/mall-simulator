import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { requireAnalysisDataResponse } from '../src/utils/analysisDataResponse.js'

const response = (data, extra = {}) => ({ snapshotId: 'snap-1', warnings: [], data, ...extra })

test('分析响应接受所需数据字段齐全的快照和明确无快照空态', () => {
  const valid = response({ rows: [], quality: {} })
  assert.equal(requireAnalysisDataResponse(valid, { arrayFields: ['rows'], objectFields: ['quality'] }), valid)

  const empty = response({}, { snapshotId: null, warnings: ['NO_ACTIVE_SNAPSHOT'] })
  assert.equal(requireAnalysisDataResponse(empty, { arrayFields: ['rows'] }), empty)
})

test('分析响应拒绝缺失信封、伪空快照和错误数据类型', () => {
  assert.throws(() => requireAnalysisDataResponse({}), /响应格式错误/)
  assert.throws(() => requireAnalysisDataResponse(response({})), /缺少数据字段/)
  assert.throws(() => requireAnalysisDataResponse(response({ rows: null }), { arrayFields: ['rows'] }), /数据结构无效/)
  assert.throws(() => requireAnalysisDataResponse(response({ quality: [] }), { objectFields: ['quality'] }), /数据结构无效/)
})

test('概览、销售、行为、RFM 与 AI 基线在共享解包前校验端点数据形状', () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/views')
  const expectations = {
    'Overview.vue': ['metrics', 'salesTrend', 'activeTrend', 'metricDictionary'],
    'Sales.vue': ['trend', 'byCategory', 'byRegion'],
    'Behavior.vue': ['stages', 'activeTrend'],
    'Rfm.vue': ['rfmSegments', 'rfmMatrix', 'lifecycle', 'preference'],
    'AiAssistant.vue': ['metrics', 'salesTrend', 'activeTrend', 'metricDictionary']
  }
  for (const [file, fields] of Object.entries(expectations)) {
    const source = fs.readFileSync(path.join(root, file), 'utf8')
    assert.match(source, /requireAnalysisDataResponse/, `${file} must validate its analysis response`)
    for (const field of fields) assert.match(source, new RegExp(`['"]${field}['"]`), `${file} must declare ${field}`)
  }
})
