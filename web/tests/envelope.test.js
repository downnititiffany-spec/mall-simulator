// 信封解包测试（契约 analysis-viewmodel-r7-4 §2 / 指导书 §18.3）
import test from 'node:test'
import assert from 'node:assert/strict'
import { readEnvelope, hasWarnings, qualityText, formatDateTime, warningText, sourceText } from '../src/utils/envelope.js'

const FULL = {
  snapshotId: 'S20260901_24',
  businessTime: '2026-09-01T00:00:00',
  dataUpdatedAt: '2026-09-10T20:12:33',
  source: 'spark-ads',
  definitionVersion: 'v2',
  qualityStatus: 'PASS',
  filters: { from: '2026-09-01', to: '2026-09-01', snapshotId: 'S20260901_24' },
  warnings: [],
  data: { metrics: [{ metricCode: 'gmv', value: 2042.0, unit: '元' }] }
}

test('完整信封逐字段解包，数值与筛选原样透传', () => {
  const ctx = readEnvelope(FULL)
  assert.equal(ctx.snapshotId, 'S20260901_24')
  assert.equal(ctx.businessTime, '2026-09-01T00:00:00')
  assert.equal(ctx.dataUpdatedAt, '2026-09-10T20:12:33')
  assert.equal(ctx.source, 'spark-ads')
  assert.equal(ctx.definitionVersion, 'v2')
  assert.equal(ctx.qualityStatus, 'PASS')
  assert.deepEqual(ctx.filters, FULL.filters)
  assert.deepEqual(ctx.warnings, [])
  // 数值必须原样透传，不允许前端改写
  assert.equal(ctx.data.metrics[0].value, 2042.0)
})

test('warnings 非空必须透传，不被吞掉', () => {
  const ctx = readEnvelope({ ...FULL, warnings: ['NO_ACTIVE_SNAPSHOT', 'UNKNOWN_DIMENSION_TABLE'] })
  assert.deepEqual(ctx.warnings, ['NO_ACTIVE_SNAPSHOT', 'UNKNOWN_DIMENSION_TABLE'])
  assert.equal(hasWarnings(ctx), true)
  assert.equal(warningText('NO_ACTIVE_SNAPSHOT').includes('ACTIVE'), true)
  // 未知告警码原样展示，避免静默
  assert.equal(warningText('SOME_NEW_CODE'), 'SOME_NEW_CODE')
})

test('warnings 不是数组时降级为空数组', () => {
  assert.deepEqual(readEnvelope({ ...FULL, warnings: 'NO_ACTIVE_SNAPSHOT' }).warnings, [])
  assert.deepEqual(readEnvelope({ ...FULL, warnings: null }).warnings, [])
  assert.deepEqual(readEnvelope({ ...FULL, warnings: [1, '', '  ', 'X'] }).warnings, ['X'])
})

test('缺字段不抛异常：全部降级为 null / UNKNOWN / 空对象', () => {
  const ctx = readEnvelope({ data: { trend: [] } })
  assert.equal(ctx.snapshotId, null)
  assert.equal(ctx.businessTime, null)
  assert.equal(ctx.dataUpdatedAt, null)
  assert.equal(ctx.source, null)
  assert.equal(ctx.definitionVersion, null)
  assert.equal(ctx.qualityStatus, 'UNKNOWN')
  assert.deepEqual(ctx.filters, {})
  assert.deepEqual(ctx.warnings, [])
  assert.deepEqual(ctx.data, { trend: [] })
})

test('空串、空白串按缺字段处理', () => {
  const ctx = readEnvelope({ snapshotId: '', businessTime: '   ', definitionVersion: '', data: {} })
  assert.equal(ctx.snapshotId, null)
  assert.equal(ctx.businessTime, null)
  assert.equal(ctx.definitionVersion, null)
})

test('传入 null / 数组 / 字符串也不抛异常', () => {
  for (const bad of [null, undefined, 'oops', 42, [], [1, 2]]) {
    const ctx = readEnvelope(bad)
    assert.equal(ctx.snapshotId, null)
    assert.equal(ctx.qualityStatus, 'UNKNOWN')
    // 顶层裸数组不是统一信封（/metrics/overview 这类接口的包装由页面 fetcher 负责），
    // 这里降级为空对象，页面不会因为 data 是数组而误取字段
    assert.deepEqual(ctx.data, {})
    assert.deepEqual(ctx.warnings, [])
  }
})

test('data 缺失或类型不符时返回空对象，页面可安全取字段', () => {
  assert.deepEqual(readEnvelope({ snapshotId: 'S1', data: null }).data, {})
  assert.deepEqual(readEnvelope({ snapshotId: 'S1', data: [] }).data, {})
  assert.deepEqual(readEnvelope({ data: { a: 1 } }).data, { a: 1 })
})

test('质量状态与时间展示语义', () => {
  assert.equal(qualityText('PASS'), '质量通过')
  assert.equal(qualityText('FAIL'), '质量未通过')
  assert.equal(qualityText('UNKNOWN'), '质量未知')
  assert.equal(qualityText(null), '质量未知')
  assert.equal(formatDateTime('2026-09-10T20:12:33'), '2026-09-10 20:12:33')
  assert.equal(formatDateTime(null), '—')
})

// ── S3-26：统一信封 source（发布方）消费侧 ────────────────────────────────
// 契约 v1.2 §2 字段表：`source` = `metric_snapshot.source`（发布方/生产者，§17.6 只接受
// `spark-ads`），**不是业务源身份**；取不到为 null、空串按缺字段处理，均不臆造值。

test('信封 source 原样透传，不被吞掉也不改写', () => {
  assert.equal(readEnvelope({ ...FULL, source: 'spark-ads' }).source, 'spark-ads')
  // 非 spark-ads 的取值也照实透传：本层只做搬运，不代后端做白名单判定
  assert.equal(readEnvelope({ ...FULL, source: 'other-publisher' }).source, 'other-publisher')
})

test('source 缺失/空串/类型不符一律降级为 null，不冒充发布方', () => {
  assert.equal(readEnvelope({ ...FULL, source: undefined }).source, null)
  assert.equal(readEnvelope({ ...FULL, source: '' }).source, null)
  assert.equal(readEnvelope({ ...FULL, source: '   ' }).source, null)
  assert.equal(readEnvelope({ ...FULL, source: 42 }).source, null)
  assert.equal(readEnvelope({ ...FULL, source: ['spark-ads'] }).source, null)
})

test('来源展示文案：取不到给「未知」而不是编造 spark-ads', () => {
  assert.equal(sourceText('spark-ads'), 'spark-ads')
  assert.equal(sourceText('other-publisher'), 'other-publisher')
  assert.equal(sourceText(null), '未知')
  assert.equal(sourceText(undefined), '未知')
  assert.equal(sourceText(''), '未知')
  assert.equal(sourceText('  '), '未知')
  assert.equal(sourceText(42), '未知')
})

// ── S3-33：信封内置降级码的中文文案（消费侧展示链） ─────────────────────────
// 权威 owner ＝ 后端 `AnalysisViewModel`（契约 §16.4 L732「所有新错误码统一 owner」）；
// 本表是**展示侧**文案，缺一个码页面上就原样打出编码。S3-33 实测：改前这 8 个信封码
// **全部**没有中文文案（只有 2 个码在表里，且该表当时无渲染入口，见 context.test.js）。
// 文案口径逐条对应 `AnalysisViewModel` 各常量上方的 KDoc，不得自行发明语义。
const ENVELOPE_WARNING_CODES = [
  'NO_ACTIVE_SNAPSHOT',
  'UNKNOWN_SNAPSHOT',
  'UNKNOWN_DIMENSION_TABLE',
  'RFM_AMOUNT_UNAVAILABLE',
  'RFM_RAW_VALUES_UNAVAILABLE',
  'RFM_PERIOD_UNAVAILABLE',
  'MULTIPLE_RULE_VERSIONS',
  'QUALITY_STATUS_UNAVAILABLE'
]

test('S3-33：信封内置降级码全部有中文文案，不再原样打出编码', () => {
  for (const code of ENVELOPE_WARNING_CODES) {
    const text = warningText(code)
    assert.notEqual(text, code, `${code} 没有中文文案（页面会原样显示编码）`)
    assert.ok(text.length > 4 && /[\u4e00-\u9fa5]/.test(text), `${code} 的文案必须是中文且非空`)
  }
})

test('S3-33：RFM 三个降级码的文案与后端冻结语义一致（回算 / 不猜窗口 / 不冒充金额）', () => {
  // 语义来源：AnalysisViewModel L54-68 的 KDoc ＋ 契约 analysis-viewmodel-r7-4 §L313/L315
  assert.match(warningText('RFM_RAW_VALUES_UNAVAILABLE'), /回算/, 'R 原值缺失时是按统计日回算，文案必须说明')
  assert.match(warningText('RFM_PERIOD_UNAVAILABLE'), /(不猜|留空|不一致)/)
  assert.match(warningText('RFM_AMOUNT_UNAVAILABLE'), /(消费额|金额)/)
})
