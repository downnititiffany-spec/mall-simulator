// 运维 / AI / 决策三页的表格映射与导出列：只做字段搬运与格式化，不重算指标
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  aiCallRows,
  aiHistoryRows,
  aiResultCsvHeaders,
  aiResultTable,
  decisionRows,
  evaluationRows,
  metricRows,
  pipelineRunRows,
  qualityRows,
  resultColumnLabel,
  snapshotRows,
  toTable,
  COLUMNS
} from '../src/utils/tables.js'

test('resultColumnLabel 把实测 SQL 列名译成中文，未登记列名原样保留（不隐藏新列）', () => {
  // 2026-09-11 实测 /ai/queries 返回列：dt/pv/uv/dau/order_count/sale_amount/net_sale_amount/avg_order_value/refund_rate
  assert.equal(resultColumnLabel('sale_amount'), '销售额')
  assert.equal(resultColumnLabel('refund_rate'), '退款率')
  assert.equal(resultColumnLabel('avg_order_value'), '客单价')
  // 后端新增指标列时不能被吞掉，也不能乱翻译
  assert.equal(resultColumnLabel('brand_new_metric'), 'brand_new_metric')
})

test('aiResultTable 与 aiResultCsvHeaders 列数一致：页面表头与导出表头同源', () => {
  const rows = [{ dt: '20260901', sale_amount: '2042.00', refund_rate: '0.6000' }]
  const table = aiResultTable(rows)
  assert.deepEqual(table.headers, ['业务日期', '销售额', '退款率'])
  const csvHeaders = aiResultCsvHeaders(rows)
  assert.equal(csvHeaders.length, table.headers.length)
  // 导出表头带原始字段名，便于与证据里的 SQL 列逐列对照
  assert.equal(csvHeaders[1], '销售额（sale_amount）')
})

test('metricRows 保留后端数值与快照/口径版本，缺失值给占位符而非 0', () => {
  const rows = metricRows([
    { metricCode: 'GMV', value: 2042, unit: '元', period: 'DAY', snapshotId: 'S20260901_24', definitionVersion: 'metric_v1' },
    { metricCode: 'REFUND_RATE', value: null, unit: null, period: null, snapshotId: null, definitionVersion: null }
  ])
  assert.equal(rows[0].value, '2,042.00')
  assert.equal(rows[0].snapshotId, 'S20260901_24')
  assert.equal(rows[0].definitionVersion, 'metric_v1')
  assert.equal(rows[1].value, '—')
  assert.equal(rows[1].unit, '—')
})

test('qualityRows 把 passed=1 映射为「通过」，其他为「未通过」，时间走统一格式化', () => {
  const rows = qualityRows([{ runId: 7, ruleCode: 'AMOUNT_RECON', passed: 1, detail: 'ok', createdAt: '2026-09-01T02:03:04Z' }])
  assert.equal(rows[0].passed, true)
  assert.notEqual(rows[0].createdAt, '—')
  const table = toTable(rows, COLUMNS.opsQuality)
  assert.equal(table.rows[0][2], '通过')
  assert.equal(toTable(qualityRows([{ passed: 0 }]), COLUMNS.opsQuality).rows[0][2], '未通过')
})

test('snapshotRows 版本加 v 前缀，状态原样展示不推断', () => {
  const rows = snapshotRows([{ snapshotId: 'S1', businessTime: '2026-09-01T00:00:00Z', status: 'ACTIVE', version: 3, dataUpdatedAt: null }])
  assert.equal(rows[0].version, 'v3')
  assert.equal(rows[0].status, 'ACTIVE')
  assert.equal(rows[0].dataUpdatedAt, '—')
})

test('aiHistoryRows 耗时加 ms 单位，SQL 与使用表原样保留', () => {
  const rows = aiHistoryRows([{
    userId: 2, question: '最近7天销售额', sqlText: 'SELECT 1', tables: 'dws_sales_daily',
    status: 'OK', rowsReturned: 7, elapsedMs: 42, errors: null, createdAt: '2026-09-01T00:00:00Z'
  }])
  assert.equal(rows[0].elapsedMs, '42ms')
  assert.equal(rows[0].errors, '—')
  assert.equal(rows[0].sqlText, 'SELECT 1')
})

test('aiCallRows 缺失耗时给占位符，token 数量整数格式化', () => {
  const rows = aiCallRows([{ useCase: 'ai_query', provider: 'deepseek', model: 'x', inputTokens: 1200, outputTokens: 300, elapsedMs: null, promptVersion: 'explain_v1', status: 'OK' }])
  assert.equal(rows[0].inputTokens, '1,200')
  assert.equal(rows[0].elapsedMs, '—')
})

test('decisionRows 快照号与基线来自后端行，缺失给占位符', () => {
  const rows = decisionRows([{
    decisionNo: 'D20260901-001', title: '提升复购', source: 'AI', targetMetricCode: 'REPURCHASE_RATE',
    targetDirection: 'UP', baselineValue: 0.12, targetValue: 0.15, suggestionSnapshotId: 'S20260901_24',
    risk: 'LOW', owner: '运营-小李', status: 'PENDING_REVIEW', createdAt: '2026-09-01T00:00:00Z'
  }])
  assert.equal(rows[0].suggestionSnapshotId, 'S20260901_24')
  assert.equal(rows[0].baselineValue, '0.12')
  assert.equal(rows[0].targetValue, '0.15')
})

test('evaluationRows 改善率 decimal → 百分比，缺失不写成 0%', () => {
  const rows = evaluationRows([
    { result: 'EFFECTIVE', improvementRate: 0.6, baselineValue: 1, actualValue: 1.6, evalWindowDays: 3, createdAt: '2026-09-04T00:00:00Z' },
    { result: 'INSUFFICIENT_DATA', improvementRate: null }
  ])
  assert.equal(rows[0].improvementRate, '60.00%')
  assert.equal(rows[1].improvementRate, '—')
})

test('aiResultTable 列名来自结果行键的并集（未登记键原样保留），值原样字符串化，null 转空串', () => {
  const table = aiResultTable([
    { stat_date: '2026-09-01', sales: 2042.5, snapshot_id: 'S20260901_24' },
    { stat_date: '2026-09-02', sales: null, orders: 5 }
  ])
  assert.deepEqual(table.headers, ['业务日期', 'sales', '快照', 'orders'])
  assert.deepEqual(table.rows[0], ['2026-09-01', '2042.5', 'S20260901_24', ''])
  assert.deepEqual(table.rows[1], ['2026-09-02', '', '', '5'])
})

test('aiResultTable 非对象/空输入返回空表而不是抛错', () => {
  assert.deepEqual(aiResultTable(null), { headers: [], rows: [] })
  assert.deepEqual(aiResultTable([null, 1, 'x']), { headers: [], rows: [] })
})

// ── S3-34（E5-c /pipeline 与 /ops「结果所属数据源」）：流水线实例的溯源字段 ──
test('pipelineRunRows 映射「源数据版本」（结果所属数据源版本），缺失给占位符', () => {
  // 实测 §PipelineService:169 `run.setSourceDataVersion(sourceDataVersion)`（页面创建实例时传
  // 'manual-<ms>'）、:420 `run.setTargetSnapshotId(snapshotId)`；两者都是 V2/V7 既有列，
  // 后端列表接口本就把实体整行返回 ⇒ 前端此前只是**没搬运**，不得在页面重算或编造。
  const rows = pipelineRunRows([
    {
      id: 47, pipelineCode: 'ODS_TO_ADS', businessTime: '2026-09-01T00:00:00',
      sourceDataVersion: 'manual-1726000000000', targetSnapshotId: 'S20260901_47',
      attemptNo: 1, status: 'SUCCESS'
    },
    { id: 48, pipelineCode: 'ODS_TO_ADS' }
  ])
  assert.equal(rows[0].sourceDataVersion, 'manual-1726000000000')
  assert.equal(rows[0].targetSnapshotId, 'S20260901_47')
  assert.equal(rows[1].sourceDataVersion, '—')
  assert.equal(rows[1].targetSnapshotId, '—')
})

test('COLUMNS.opsPipelineRuns 含「源数据版本」列：/ops 流水线实例表可见结果所属数据源', () => {
  const col = COLUMNS.opsPipelineRuns.find((c) => c.key === 'sourceDataVersion')
  assert.ok(col, '运维页流水线实例表缺少 sourceDataVersion 列')
  assert.equal(col.label, '源数据版本')
})

test('toTable 对空列表返回列头齐全、行为空', () => {
  const table = toTable([], COLUMNS.decisions)
  assert.equal(table.headers.length, COLUMNS.decisions.length)
  assert.deepEqual(table.rows, [])
})
