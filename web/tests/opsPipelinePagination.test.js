import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Ops.vue'), 'utf8')

test('运维流水线表使用服务端分页元数据与白名单排序参数', () => {
  assert.match(source, /api\.pipelineRunsPage\(\{ page: runPage\.value, size: runPageSize\.value, sort: runSort\.value \}, \{ signal \}\)/)
  assert.match(source, /const pipelineResponse = requirePageResponse\(pipelinePage, '流水线运行列表'\)/)
  assert.match(source, /runTotal\.value = pipelineResponse\.total/)
  assert.match(source, /runTotalPages\.value = pipelineResponse\.totalPages/)
  assert.doesNotMatch(source, /api\.pipelineRuns\(10, \{ signal \}\)/)
})

test('运维流水线分页控件在翻页、改页大小与排序时请求新页面', () => {
  assert.match(source, /value="createdAt,desc">创建时间（新到旧）/)
  assert.match(source, /const runSort = ref\('createdAt,desc'\)/)
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

test('运维快照表使用分页 API，独立保留最近快照作为上下文', () => {
  assert.match(source, /api\.snapshotsPage\(\{ page: snapshotPage\.value, size: snapshotPageSize\.value, sort: snapshotSort\.value \}, \{ signal \}\)/)
  assert.match(source, /api\.snapshots\(15, \{ signal \}\)/)
  assert.match(source, /rows: snapshotContextList/)
  assert.match(source, /snapshots,\s*snapshotContextRows: snapshotContextList/)
  assert.match(source, /const snapshotResponse = requirePageResponse\(snapshotPageResult, '快照列表'\)/)
  assert.match(source, /snapshotTotalPages\.value = snapshotResponse\.totalPages/)
})

test('快照分页支持翻页、改页大小与排序', () => {
  assert.match(source, /value="createdAt,desc">创建时间（新到旧）/)
  assert.match(source, /value="publishedAt,desc">发布时间（新到旧）/)
  assert.match(source, /第 \{\{ snapshotPage \}\} \/ \{\{ snapshotTotalPages \}\} 页/)
  assert.match(source, /@click="changeSnapshotPage\(snapshotPage \+ 1\)"/)
  assert.match(source, /const snapshotSort = ref\('id,desc'\)/)
  for (const name of ['changeSnapshotPage', 'changeSnapshotPageSize', 'changeSnapshotSort']) {
    const start = source.indexOf(`function ${name}`)
    const end = source.indexOf('\n}', start) + 2
    assert.ok(start >= 0, `${name} should exist`)
    assert.match(source.slice(start, end), /return load\(\{\}\)/)
  }
})

test('AI问答与模型调用审计使用服务端分页及总数元数据', () => {
  assert.match(source, /api\.aiAuditHistoryPage\(\{ page: aiHistoryPage\.value, size: aiHistoryPageSize\.value, sort: aiHistorySort\.value \}, \{ signal \}\)/)
  assert.match(source, /api\.aiAuditCallsPage\(\{ page: aiCallPage\.value, size: aiCallPageSize\.value, sort: aiCallSort\.value \}, \{ signal \}\)/)
  assert.match(source, /aiHistoryTotalPages\.value = historyResponse\.totalPages/)
  assert.match(source, /aiCallTotalPages\.value = callResponse\.totalPages/)
  assert.match(source, /@click="changeAiHistoryPage\(aiHistoryPage \+ 1\)"/)
  assert.match(source, /@click="changeAiCallPage\(aiCallPage \+ 1\)"/)
  assert.match(source, /@change="changeAiHistorySort"/)
  assert.match(source, /@change="changeAiCallSort"/)
  assert.doesNotMatch(source, /api\.aiAuditHistory\(10, \{ signal \}\)/)
  assert.doesNotMatch(source, /api\.aiAuditCalls\(10, \{ signal \}\)/)
})

test('Ops 用户管理使用服务端分页并提供白名单排序控件', () => {
  assert.match(source, /api\.adminUsersPage\(\{ page: userPage\.value, size: userPageSize\.value, sort: userSort\.value \}\)/)
  assert.match(source, /const result = requirePageResponse\(/)
  assert.match(source, /userTotalPages\.value = result\.totalPages/)
  assert.match(source, /@click="changeUserPage\(userPage \+ 1\)"/)
  assert.match(source, /@change="changeUserSort"/)
  assert.doesNotMatch(source, /api\.adminUsers\(\)/)
})

test('运维数据质量结果使用服务端分页并保留最新质量上下文', () => {
  assert.match(source, /api\.qualityPage\(\{ page: qualityPage\.value, size: qualityPageSize\.value, sort: qualitySort\.value \}, \{ signal \}\)/)
  assert.match(source, /qualityTotalPages\.value = qualityResponse\.totalPages/)
  assert.match(source, /qualityResults: qualityRowsPage/)
  assert.match(source, /const qualityList = requireArrayResponse\(quality, '质量列表'\)/)
  assert.match(source, /value="ruleCode,asc">规则编码/)
  assert.match(source, /@click="changeQualityPage\(qualityPage \+ 1\)"/)
  for (const name of ['changeQualityPage', 'changeQualityPageSize', 'changeQualitySort']) {
    const start = source.indexOf(`function ${name}`)
    const end = source.indexOf('\n}', start) + 2
    assert.ok(start >= 0, `${name} should exist`)
    assert.match(source.slice(start, end), /return load\(\{\}\)/)
  }
})

test('运维页读取采集状态与最近批次，并按接口字段如实展示', () => {
  const apiSource = fs.readFileSync(path.resolve(here, '../src/api.js'), 'utf8')
  assert.match(apiSource, /ingestionBatchesPage: \(params = \{\}, options\) => client\.get\('\/ingestion\/batches\/page', \{ params, \.\.\.\(options \|\| \{\}\) \}\)/)
  assert.match(source, /api\.ingestionStatus\(\{ signal \}\)/)
  assert.match(source, /api\.ingestionBatchesPage\(\{ page: ingestionPage\.value, size: ingestionPageSize\.value, sort: ingestionSort\.value \}, \{ signal \}\)/)
  assert.match(source, /ingestionBatches: ingestionResponse\.items/)
  assert.match(source, /ingestionTotal: ingestionResponse\.total/)
  assert.match(source, /扫描目录文件数/)
  assert.match(source, /扫描目录字节数/)
  assert.match(source, /checkpoint 文件/)
  assert.match(source, /最近接收/)
  assert.match(source, /仍有可消费行的文件数/)
  assert.match(source, /batch\.sourceId \?\? '未知来源'/)
  assert.match(source, /batch\.errorCount \?\? '接口未提供'/)
  assert.match(source, /batch\.quarantineCount \?\? '接口未提供'/)
  assert.match(source, /共 {{ ingestionTotal }} 条/)
  assert.match(source, /visibleIngestionBatches/)
  assert.match(source, /value="startTime,desc">开始时间（新到旧）/)
  assert.match(source, /@click="changeIngestionPage\(ingestionPage \+ 1\)"/)
  assert.match(source, /批次表由服务端按页读取全量历史记录/)
  assert.match(source, /不表示上游持续在线或完整链路已验收/)
})

test('采集批次页码、页大小和排序都由服务端分页接口处理', () => {
  assert.doesNotMatch(source, /import \{ paginateIngestionBatches, sortIngestionBatches \} from '\.\.\/utils\/ingestionBatches'/)
  assert.match(source, /const ingestionTotalPages = computed\(\(\) => Math\.max\(1, Number\(data\.value\.ingestionTotalPages\) \|\| 1\)\)/)
  assert.match(source, /const visibleIngestionBatches = computed\(\(\) => ingestionBatches\.value\)/)
  assert.match(source, /const ingestionSort = ref\('id,desc'\)/)
  for (const name of ['changeIngestionPage', 'changeIngestionPageSize', 'changeIngestionSort']) {
    const start = source.indexOf(`function ${name}`)
    const end = source.indexOf('\n}', start) + 2
    assert.ok(start >= 0, `${name} should exist`)
    assert.match(source.slice(start, end), /ingestionPage\.value/)
    assert.match(source.slice(start, end), /return load\(\{\}\)/)
  }
})
