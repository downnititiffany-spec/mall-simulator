// G31-03 03.3：证明业务来源、快照、口径、有效窗口与指标值一起到达 CSV 导出件。
import test, { after, before } from 'node:test'
import assert from 'node:assert/strict'
import { buildCsvText } from '../src/utils/csv.js'

let vite
let useAnalysis

before(async () => {
  vite = await (await import('vite')).createServer({
    configFile: false,
    root: process.cwd(),
    server: { middlewareMode: true },
    appType: 'custom'
  })
  ;({ useAnalysis } = await vite.ssrLoadModule('/src/composables/useAnalysis.js'))
})

after(async () => {
  if (vite) await vite.close()
})

test('来源/快照/口径/窗口与指标值从真实分析 composable 一致进入 CSV', async () => {
  const analysis = useAnalysis({
    fetcher: async () => ({
      sourceId: 27,
      snapshotId: 'S-g3103-five-point',
      definitionVersion: 'v2',
      filters: { from: '2026-09-17', to: '2026-09-18' },
      data: { metrics: [{ code: 'gmv', value: 1234.5 }] }
    }),
    rowKeys: ['metrics'],
    defaults: []
  })

  await analysis.load()
  const metric = analysis.data.value.metrics[0]
  const csv = buildCsvText({
    context: analysis.exportContext.value,
    generatedAt: '2026-09-23T12:00:00',
    headers: ['指标编码', '指标值'],
    rows: [[metric.code, metric.value]]
  })

  assert.equal(analysis.context.value.sourceId, 27)
  assert.equal(analysis.exportContext.value.snapshotId, 'S-g3103-five-point')
  assert.equal(analysis.exportContext.value.definitionVersion, 'v2')
  assert.deepEqual(analysis.exportContext.value.filters, { from: '2026-09-17', to: '2026-09-18' })
  assert.equal(metric.value, 1234.5)
  assert.match(csv, /# 业务来源 ID,27/)
  assert.match(csv, /# 快照ID,S-g3103-five-point/)
  assert.match(csv, /# 口径版本,v2/)
  assert.match(csv, /# 生效筛选,from=2026-09-17;to=2026-09-18/)
  assert.match(csv, /gmv,1234\.5$/)
})
