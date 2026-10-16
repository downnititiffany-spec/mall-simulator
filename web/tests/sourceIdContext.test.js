// source_registry.id（sourceId）与指标快照发布方（source）必须独立透传、展示。
import test, { after, before } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { readEnvelope, businessSourceIdText } from '../src/utils/envelope.js'
import { buildFallbackContext } from '../src/utils/context.js'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const COMPONENT = read('../src/components/AnalysisContext.vue')

let vite
let useAnalysis

before(async () => {
  // 通过 Vite 加载真实 composable，覆盖 readEnvelope → exportContext 的生产路径。
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

test('readEnvelope 将 sourceId 数字与 source 发布方分字段保留，拒绝不可信 ID 表示', () => {
  const ctx = readEnvelope({ sourceId: 27, source: 'spark-ads', data: {} })
  assert.equal(ctx.sourceId, 27)
  assert.equal(ctx.source, 'spark-ads')

  for (const invalid of [null, undefined, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, '27']) {
    assert.equal(readEnvelope({ sourceId: invalid }).sourceId, null, `sourceId=${String(invalid)} 应降级为 null`)
  }
})

test('sourceId 从真实信封经 useAnalysis 导出上下文；source 仍独立表示发布方', async () => {
  const analysis = useAnalysis({
    fetcher: async () => ({
      snapshotId: 'S-source-27',
      sourceId: 27,
      source: 'spark-ads',
      data: { rows: [{ orderCount: 3 }] }
    }),
    rowKeys: ['rows'],
    defaults: []
  })

  await analysis.load()
  assert.equal(analysis.context.value.sourceId, 27)
  assert.equal(analysis.context.value.source, 'spark-ads')
  assert.equal(analysis.exportContext.value.sourceId, 27)
  assert.equal(analysis.exportContext.value.source, 'spark-ads')
  assert.equal(analysis.exportContext.value.snapshotId, 'S-source-27')
})

test('历史 sourceId=null 不从显式当前来源参数或 spark-ads 发布方补值', async () => {
  const history = readEnvelope({ snapshotId: 'S-history', sourceId: null, source: 'spark-ads', data: {} })
  assert.equal(history.sourceId, null)
  assert.equal(history.source, 'spark-ads')

  const analysis = useAnalysis({
    fetcher: async () => ({ ...history, data: { rows: [{ orderCount: 1 }] } }),
    rowKeys: ['rows'],
    defaults: []
  })
  await analysis.load()
  assert.equal(analysis.exportContext.value.sourceId, null)
  assert.equal(analysis.exportContext.value.source, 'spark-ads')

  const fallback = buildFallbackContext({
    rows: [],
    sourceId: 99, // 模拟调用处当前选中的来源；不得覆盖历史信封的显式 null
    envelopeSource: { snapshotId: 'S-history', sourceId: null, source: 'spark-ads' }
  })
  assert.equal(fallback.sourceId, null)
  assert.equal(fallback.source, 'spark-ads')
  assert.equal(businessSourceIdText(fallback.sourceId), '未知 / 历史未记录')
})

test('非信封上下文只透传响应明确提供的 sourceId；页面用两个不同字段分别显示来源 ID 与发布方', () => {
  assert.equal(buildFallbackContext({ rows: [], envelopeSource: { sourceId: 8 } }).sourceId, 8)
  assert.equal(buildFallbackContext({ rows: [], sourceId: 8 }).sourceId, 8)
  assert.equal(buildFallbackContext({ rows: [], source: 'spark-ads' }).sourceId, null)

  assert.match(COMPONENT, /业务来源 ID[\s\S]*businessSourceIdText\(ctx\.sourceId\)/)
  assert.match(COMPONENT, /来源（发布方）[\s\S]*sourceText\(ctx\.source\)/)
  assert.equal(businessSourceIdText(8), '8')
  assert.equal(businessSourceIdText(null), '未知 / 历史未记录')
  assert.equal(businessSourceIdText('8'), '未知 / 历史未记录')
})
