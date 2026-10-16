import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { withMetricSnapshotIdentity } from '../src/utils/metricSnapshotEnvelope.js'

const here = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(here, '../src/views/Ops.vue'), 'utf8')
const fetchMetrics = source.match(/async function fetchMetrics\([\s\S]*?\n\}/)?.[0] || ''

test('运维页为选中快照请求统一分析信封', () => {
  assert.ok(fetchMetrics, '必须保留快照指标 fetcher')
  assert.match(fetchMetrics, /api\.overview\(\{\s*snapshotId\s*\},\s*\{\s*signal\s*\}\)/)
  assert.match(fetchMetrics, /withMetricSnapshotIdentity\(envelope\)/)
  assert.doesNotMatch(fetchMetrics, /metricsOverview|ENVELOPE_MISSING/)
})

test('指标行快照号来自同一分析信封，且保持原数据和不可变性', () => {
  const source = {
    snapshotId: 'S-20261009-24',
    source: 'spark-ads',
    qualityStatus: 'PASS',
    data: { metrics: [{ metricCode: 'gmv', value: 12 }], salesTrend: [] }
  }
  const adapted = withMetricSnapshotIdentity(source)
  assert.equal(adapted.snapshotId, source.snapshotId)
  assert.equal(adapted.data.metrics[0].snapshotId, source.snapshotId)
  assert.deepEqual(adapted.data.salesTrend, [])
  assert.equal(adapted.source, 'spark-ads')
  assert.equal(adapted.qualityStatus, 'PASS')
  assert.equal(source.data.metrics[0].snapshotId, undefined)
})

test('运维页显示真实信封里的发布方、业务来源、时间和质量状态', () => {
  const metricPanel = source.slice(source.indexOf('快照 {{ selected }} 指标'), source.indexOf('流水线实例'))
  for (const field of ['sourceId', 'metricExportContext.source', 'definitionVersion',
    'businessTime', 'dataUpdatedAt', 'qualityStatus']) {
    assert.ok(metricPanel.includes(field), `快照指标上下文应显示 ${field}`)
  }
  assert.ok(source.includes('warningTextAll(warning)'), '必须显示后端快照告警解释')
  assert.match(metricPanel, /上下文来自所选快照的 \/dashboards\/overview 信封/)
})
