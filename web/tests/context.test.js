// 非统一信封接口的上下文拼装（运维 / AI / 决策三页共用）
// 重点验证「取不到就如实标注」，绝不凭空造快照号或质量结论。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildAiEvidenceContext,
  buildFallbackContext,
  collectSnapshotIds,
  mergeWarnings,
  NON_ANALYSIS_ROW_KEYS,
  warningTextAll
} from '../src/utils/context.js'
// S3-35：缺失告知要活过统一信封归一化才到得了屏幕/导出件 ⇒ 这条链必须一起验
import { readEnvelope } from '../src/utils/envelope.js'

test('collectSnapshotIds 兼容 snapshotId / snapshot_id / suggestionSnapshotId 三种字段并去重排序', () => {
  const rows = [
    { snapshotId: 'S20260901_24' },
    { snapshot_id: 'S20260831_23' },
    { suggestionSnapshotId: 'S20260901_24' },
    { title: '无快照字段的行' },
    null
  ]
  assert.deepEqual(collectSnapshotIds(rows), ['S20260831_23', 'S20260901_24'])
})

test('collectSnapshotIds 空输入返回空数组，不返回 undefined', () => {
  assert.deepEqual(collectSnapshotIds(null), [])
  assert.deepEqual(collectSnapshotIds([]), [])
})

test('collectSnapshotIds 接受直接给出的快照号字符串（按快照号请求指标的场景）', () => {
  assert.deepEqual(collectSnapshotIds(['S20260901_24']), ['S20260901_24'])
  assert.deepEqual(collectSnapshotIds('S20260901_24'), ['S20260901_24'])
})

test('collectSnapshotIds 过滤 AI 证据包的占位串 unknown，不把占位值当快照号', () => {
  assert.deepEqual(collectSnapshotIds([{ snapshotId: 'unknown' }]), [])
  assert.deepEqual(collectSnapshotIds(['', '  ']), [])
})

test('collectSnapshotIds 的字符串入参确实参与拼装：指标页上下文能显示所选快照号', () => {
  const rows = [{ metricCode: 'dau', value: 3, definitionVersion: 'v1' }]
  const ctx = buildFallbackContext({ rows, warnings: ['ENVELOPE_MISSING'], snapshotIds: ['S20260901_24'] })
  assert.equal(ctx.snapshotId, 'S20260901_24')
  // 快照号已提供，缺失清单里就不应再出现 snapshotId
  assert.doesNotMatch(ctx.missingNotice, /snapshotId/)
})

test('mergeWarnings 去重、丢空值、保持首次出现顺序', () => {
  assert.deepEqual(
    mergeWarnings(['ENVELOPE_MISSING'], ['QUALITY_RULE_FAILED', 'ENVELOPE_MISSING'], null, ['']),
    ['ENVELOPE_MISSING', 'QUALITY_RULE_FAILED']
  )
})

test('warningTextAll 已知码给中文解释，未知码原样透出（新后端告警不会被吞）', () => {
  assert.match(warningTextAll('ENVELOPE_MISSING'), /未返回统一信封/)
  assert.match(warningTextAll('QUALITY_RULE_FAILED'), /质量规则/)
  assert.equal(warningTextAll('SOME_NEW_CODE_2027'), 'SOME_NEW_CODE_2027')
})

// ── S3-33：告警文案的「信封内置 → 本模块补全」链 ────────────────────────────
// 实测缺陷（S3-33）：`warningTextAll` 的 KDoc 写着「先查信封内置映射，再查本模块补全」，
// 实现却只查 `WARNING_TEXT_EXTRA` ⇒ 信封侧降级码在页面上原样打出编码。页面**只有**这一个
// 渲染入口（`AnalysisContext.vue:25`、`AiAssistant.vue:96` 都调 `warningTextAll`），
// 而 `envelope.js` 的 `warningText` 当时零页面调用 ⇒ 只补 `envelope.js` 的表不会显示出来。

test('S3-33：warningTextAll 链式解析——信封内置码给中文，不再原样打出编码', () => {
  // 语义来源：AnalysisViewModel 常量 KDoc；NO_ACTIVE_SNAPSHOT/UNKNOWN_DIMENSION_TABLE 本就在信封表里
  assert.match(warningTextAll('NO_ACTIVE_SNAPSHOT'), /ACTIVE/)
  assert.match(warningTextAll('RFM_PERIOD_UNAVAILABLE'), /(不猜|留空|不一致)/)
  assert.match(warningTextAll('RFM_RAW_VALUES_UNAVAILABLE'), /回算/)
})

test('S3-33：本模块补全码与未知码行为不变（补全仍生效、未知仍原样透出）', () => {
  assert.match(warningTextAll('NO_SNAPSHOT_SELECTED'), /尚未选择快照/)
  assert.match(warningTextAll('AI_EVIDENCE_PARTIAL'), /证据包/)
  assert.equal(warningTextAll('SOME_NEW_CODE_2027'), 'SOME_NEW_CODE_2027')
})

test('buildFallbackContext 无任何可见字段时，快照/版本/质量都标注缺失，绝不编造', () => {
  const ctx = buildFallbackContext({ rows: [], warnings: ['ENVELOPE_MISSING'] })
  assert.equal(ctx.snapshotId, null)
  assert.equal(ctx.definitionVersion, null)
  assert.equal(ctx.qualityStatus, 'UNKNOWN')
  assert.deepEqual(ctx.warnings, ['ENVELOPE_MISSING'])
  assert.match(ctx.missingNotice, /snapshotId/)
  assert.match(ctx.missingNotice, /定义版本|口径版本/)
  assert.match(ctx.missingNotice, /质量状态/)
})

test('buildFallbackContext 只从行里真实存在的字段提取，缺失项逐条登记', () => {
  const ctx = buildFallbackContext({
    rows: [{ snapshotId: 'S20260901_24', definitionVersion: 'metric_v1' }],
    warnings: [],
    businessTime: '2026-09-01T00:00:00Z'
  })
  assert.equal(ctx.snapshotId, 'S20260901_24')
  assert.equal(ctx.definitionVersion, 'metric_v1')
  assert.equal(ctx.businessTime, '2026-09-01T00:00:00Z')
  // 质量状态没有数据来源 → 必须出现在缺失说明里
  assert.match(ctx.missingNotice, /质量状态/)
  // 已提供的字段不应被列为缺失
  assert.doesNotMatch(ctx.missingNotice, /snapshotId/)
})

test('buildFallbackContext 多个快照号时不擅自选一个，逐个列出并提示', () => {
  const ctx = buildFallbackContext({
    rows: [{ suggestionSnapshotId: 'S20260831_23' }, { suggestionSnapshotId: 'S20260901_24' }],
    warnings: ['ENVELOPE_MISSING']
  })
  assert.match(ctx.missingNotice, /S20260831_23/)
  assert.match(ctx.missingNotice, /S20260901_24/)
})

test('buildFallbackContext 显式传入 qualityStatus 时按传入值展示', () => {
  const ctx = buildFallbackContext({ rows: [], warnings: [], qualityStatus: 'FAIL' })
  assert.equal(ctx.qualityStatus, 'FAIL')
})

test('buildAiEvidenceContext 证据字段缺失时快照号为 null 且提示未提供', () => {
  const ctx = buildAiEvidenceContext({ query: { status: 'OK', rows: [] }, explanation: { evidence: {} } })
  assert.equal(ctx.snapshotId, null)
  assert.equal(ctx.businessTime, null)
  assert.equal(ctx.dataUpdatedAt, null)
  assert.equal(ctx.definitionVersion, null)
  assert.equal(ctx.qualityStatus, 'UNKNOWN')
  assert.match(ctx.missingNotice, /snapshotId/)
  assert.match(ctx.missingNotice, /业务时间/)
  // 证据包本身只提供提示词版本，不能冒充指标口径版本
  assert.match(ctx.missingNotice, /指标口径版本/)
  assert.ok(ctx.warnings.includes('AI_EVIDENCE_PARTIAL'))
})

test('buildAiEvidenceContext 回退取 query.rows[0].snapshot_id 时说明回退来源', () => {
  const ctx = buildAiEvidenceContext({
    query: { rows: [{ snapshot_id: 'S20260901_24', sales: 2042 }] },
    explanation: { evidence: {} }
  })
  assert.equal(ctx.snapshotId, 'S20260901_24')
  assert.match(ctx.missingNotice, /回退/)
})

test('buildAiEvidenceContext 遇到真实后端的占位快照号 unknown：按未提供处理并说明占位值', () => {
  // 2026-09-11 实测 /ai/queries（rule-based 回退分支）返回 explanation.evidence.snapshotId = 'unknown'，
  // SQL 实际用 MAX(snapshot_id) 锁定快照但未回传具体快照号 —— 页面必须如实标注，不能显示成快照号。
  const ctx = buildAiEvidenceContext({
    query: {
      status: 'EXECUTED',
      sql: 'SELECT dt FROM ads_operation_overview_m WHERE snapshot_id = (SELECT MAX(snapshot_id) FROM ads_operation_overview_m)',
      rows: [{ dt: '20260901', sale_amount: '2042.00' }]
    },
    explanation: { evidence: { snapshotId: 'unknown', returnedRows: 1 } }
  })
  assert.equal(ctx.snapshotId, null)
  assert.match(ctx.missingNotice, /占位值 unknown/)
  assert.doesNotMatch(ctx.missingNotice, /已回退取/)
})

test('buildAiEvidenceContext 优先使用 evidence.snapshotId，并搬运 SQL/表/行数/耗时/提示词版本', () => {
  const ctx = buildAiEvidenceContext({
    query: { rows: [{ snapshot_id: 'S20260831_23' }], tables: ['x'] },
    explanation: {
      evidence: {
        snapshotId: 'S20260901_24',
        question: '最近 7 天销售额趋势',
        sql: 'SELECT ... FROM dws_sales_daily WHERE snapshot_id = ...',
        tables: ['dws_sales_daily'],
        returnedRows: 7,
        queryElapsedMs: 42,
        timeRange: '近30天',
        definitions: 'explain_v1'
      },
      limitations: ['仅覆盖已支付订单']
    }
  })
  assert.equal(ctx.snapshotId, 'S20260901_24')
  assert.equal(ctx.evidence.sql.includes('dws_sales_daily'), true)
  assert.deepEqual(ctx.evidence.tables, ['dws_sales_daily'])
  assert.equal(ctx.evidence.returnedRows, 7)
  assert.equal(ctx.evidence.queryElapsedMs, 42)
  assert.equal(ctx.evidence.promptVersion, 'explain_v1')
  assert.equal(ctx.filters['时间范围'], '近30天')
  assert.ok(ctx.warnings.includes('仅覆盖已支付订单'))
  // 证据齐全时不应再提 snapshotId 缺失
  assert.doesNotMatch(ctx.missingNotice, /snapshotId/)
})

test('NON_ANALYSIS_ROW_KEYS 与三页实际返回结构一致', () => {
  assert.deepEqual(NON_ANALYSIS_ROW_KEYS.opsMetrics, ['metrics'])
  assert.deepEqual(NON_ANALYSIS_ROW_KEYS.decisions, ['decisions'])
  assert.deepEqual(NON_ANALYSIS_ROW_KEYS.aiQuery, ['queryRows'])
  assert.deepEqual(NON_ANALYSIS_ROW_KEYS.opsAudit, ['snapshots', 'qualityResults', 'aiHistory', 'aiCalls'])
})

// ── S3-26：来源（发布方）在非信封路径的处理 ────────────────────────────────

test('buildFallbackContext 透传信封 source；信封与入参都没有时为 null（页面显示「未知」）', () => {
  const withEnvelope = buildFallbackContext({
    rows: [],
    envelopeSource: { snapshotId: 'S20260901_24', source: 'spark-ads' }
  })
  assert.equal(withEnvelope.source, 'spark-ads')
  // 显式入参在信封缺失时生效（运维页各行自带来源的场景）
  assert.equal(buildFallbackContext({ rows: [], source: 'spark-ads' }).source, 'spark-ads')
  assert.equal(buildFallbackContext({ rows: [] }).source, null)
  // 来源不是业务源身份，也不进缺失清单：非信封接口已由 ENVELOPE_MISSING 标注
  const ctx = buildFallbackContext({ rows: [], warnings: ['ENVELOPE_MISSING'] })
  assert.doesNotMatch(ctx.missingNotice, /来源/)
})

test('AI 证据上下文不含发布方：source 显式为 null，不借当前快照冒充 AI 结果来源', () => {
  const ctx = buildAiEvidenceContext({
    explanation: { evidence: { snapshotId: 'S20260901_24', question: '复购率', sql: 'SELECT 1', tables: ['dws_user_trade_period'] } },
    query: {},
    rows: []
  })
  assert.equal(ctx.source, null)
})

// ── S3-35：/pipeline 页取数状态收敛到 useAnalysis 后，其行键也登记在同一属主 ──────────

test('NON_ANALYSIS_ROW_KEYS 是「非信封接口页面的行键」唯一属主：/pipeline 行键已登记且各值非空', () => {
  // /pipeline 的 data 形状＝{ pipelineRuns: [...] }（fetcher 内已构造），行键必须与 data 键同名
  assert.deepEqual(NON_ANALYSIS_ROW_KEYS.pipelineRuns, ['pipelineRuns'])
  // 既有键一个都不能少（加性登记，不得改名/删除既有页面的行键）
  assert.deepEqual(Object.keys(NON_ANALYSIS_ROW_KEYS).sort(), ['aiQuery', 'decisions', 'opsAudit', 'opsMetrics', 'pipelineRuns'])
  for (const [k, v] of Object.entries(NON_ANALYSIS_ROW_KEYS)) {
    assert.ok(Array.isArray(v) && v.length > 0 && v.every((s) => typeof s === 'string' && s), `${k} 的行键必须是非空字符串数组`)
  }
})

// ── S3-35：缺失告知（missingNotice）必须活过统一信封归一化，否则页面横幅与导出件都看不到 ──────

test('buildFallbackContext 的 missingNotice 经 readEnvelope 归一化后仍在；真信封无该字段时为空', () => {
  const ctx = buildFallbackContext({ rows: [] })
  assert.match(ctx.missingNotice, /接口未提供/)
  const normalized = readEnvelope(ctx)
  // fetchRuns 这类 fetcher 返回的对象要经 readEnvelope 才进 useAnalysis：
  // 若归一化丢掉 missingNotice，横幅（AnalysisContext）与导出元信息（csv.js）都拿不到，
  // 「取不到就如实标注」就只停留在单元层、到不了屏幕。
  assert.equal(normalized.missingNotice, ctx.missingNotice)
  // 后端统一信封没有该字段：按本归一化器既有约定为 null（其余字段同为 asText ⇒ null），
  // 页面 `v-if` 为假不显示、导出不写该行，不得凭空造缺失说明
  assert.equal(readEnvelope({ snapshotId: 'S20260901_47', data: {} }).missingNotice, null)
})
