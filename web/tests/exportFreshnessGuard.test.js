// Web 导出最终防线：不能只依赖按钮 disabled。
// useAnalysis 把当前四态写入 exportContext；下载层对 stale/loading/error/empty 以及空数据集统一 fail-closed。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { canDownloadCsv } from '../src/utils/exportCsv.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const useAnalysisSource = fs.readFileSync(path.resolve(here, '../src/composables/useAnalysis.js'), 'utf8')
const exportSource = fs.readFileSync(path.resolve(here, '../src/utils/exportCsv.js'), 'utf8')

test('useAnalysis 导出上下文携带当前 viewState，下载层能判断 freshness', () => {
  assert.match(useAnalysisSource, /viewState:\s*state\.value/)
})

test('ready 且有真实行时允许下载', () => {
  assert.equal(canDownloadCsv({ context: { viewState: 'ready' }, rows: [[1]] }), true)
})

test('loading/stale/error/empty 即使屏上仍有旧行也禁止下载', () => {
  for (const viewState of ['loading', 'stale', 'error', 'empty']) {
    assert.equal(
      canDownloadCsv({ context: { viewState }, rows: [['old-row']] }),
      false,
      `${viewState} 不得导出`
    )
  }
})

test('整页 ready 但当前导出子集为空时禁止生成空 CSV', () => {
  assert.equal(canDownloadCsv({ context: { viewState: 'ready' }, rows: [] }), false)
  assert.equal(canDownloadCsv({ context: { viewState: 'ready' }, rows: null }), false)
})

test('未携带 viewState 的旧调用方保持兼容，但仍要求非空数据行', () => {
  assert.equal(canDownloadCsv({ context: { snapshotId: 's1' }, rows: [['x']] }), true)
  assert.equal(canDownloadCsv({ context: { snapshotId: 's1' }, rows: [] }), false)
})

test('exportAnalysisCsv 在创建 Blob 前执行最终 canDownloadCsv 守卫', () => {
  const guardAt = exportSource.indexOf('if (!canDownloadCsv({ context, rows })) return null')
  const blobAt = exportSource.indexOf('new Blob(')
  assert.ok(guardAt >= 0, '必须存在最终下载守卫')
  assert.ok(blobAt > guardAt, '必须先判定 freshness/空数据，再创建 Blob')
})
