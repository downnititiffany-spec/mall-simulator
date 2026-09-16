// 统一上下文拼装（契约 analysis-viewmodel-r7-4 §2 / 指导书 §18.3）
// 分析端点返回完整信封；运维、AI、决策等接口不是统一信封（返回裸数组或各自结构），
// 这里把它们的公共字段拼成一个与信封同形的上下文，并显式记录哪些上下文信息「接口未提供」。
// 纯逻辑，不依赖 vue/浏览器，可被 node:test 覆盖。
// 原则：拼装出来的每个字段都必须来自后端真实响应，取不到就写「接口未提供」，绝不编造。
import { readEnvelope } from './envelope.js'

/** 接口未提供该字段时的统一文案 */
export const MISSING_TEXT = '接口未提供'

/** 告警编码补全：让非信封接口的降级事实也能被页面显式展示 */
const WARNING_TEXT_EXTRA = {
  ENVELOPE_MISSING: '该接口未返回统一信封（无 snapshotId/口径版本/质量状态），上下文以响应内可见字段拼装，另有缺失项已逐项标注。',
  AI_EVIDENCE_PARTIAL: 'AI 证据包只提供快照、SQL、表、时间范围与提示词版本，业务时间/质量状态/指标口径版本不在此接口返回内。',
  QUALITY_RULE_FAILED: '数据质量规则存在未通过项（金额对账失败会阻断指标发布），请在下方的质量规则结果中核对。',
  NO_SNAPSHOT_SELECTED: '尚未选择快照，未请求该快照的指标数据，因此没有可展示的指标行。'
}

/**
 * 告警文案：先查信封内置映射，再查本模块补全，未知编码原样展示（不静默）。
 */
export function warningTextAll(code) {
  return WARNING_TEXT_EXTRA[code] || String(code)
}

const isRecord = (v) => v && typeof v === 'object' && !Array.isArray(v)

/**
 * 是否为真实快照号。
 * AI 证据包在规则回退分支会返回占位串 'unknown'，那不是快照号，页面必须按「未提供」展示。
 */
export function isRealSnapshotId(value) {
  return typeof value === 'string' && value.trim() !== '' && value !== 'unknown' && value !== 'UNKNOWN'
}

/** 从任意对象中取第一个非空字符串字段 */
function pickText(source, keys) {
  if (!isRecord(source)) return null
  for (const key of keys) {
    const v = source[key]
    if (typeof v === 'string' && v.trim() !== '') return v
    if (typeof v === 'number' && Number.isFinite(v)) return String(v)
  }
  return null
}

/**
 * 收集响应中出现的多个快照号（去重后排序）。
 * 入参既可以是对象数组（决策列表的 suggestionSnapshotId、指标行的 snapshot_id），
 * 也可以是直接给出的快照号字符串（调用方已知快照号时，如按快照号请求指标）。
 */
export function collectSnapshotIds(values) {
  const set = new Set()
  for (const v of Array.isArray(values) ? values : [values]) {
    if (isRealSnapshotId(v)) {
      set.add(v)
      continue
    }
    if (!isRecord(v)) continue
    const id = pickText(v, ['snapshotId', 'snapshot_id', 'suggestionSnapshotId'])
    if (isRealSnapshotId(id)) set.add(id)
  }
  return [...set].sort()
}

/** 合并告警：去重、去空、保持出现顺序 */
export function mergeWarnings(...lists) {
  const out = []
  for (const list of lists) {
    for (const w of Array.isArray(list) ? list : [list]) {
      if (typeof w === 'string' && w.trim() !== '' && !out.includes(w)) out.push(w)
    }
  }
  return out
}

/** 缺失字段 → 「接口未提供」清单（顺序即展示顺序） */
function missingNotice(missing) {
  if (!missing.length) return ''
  return '接口未提供（已如实标注，不代替后端编造）：' + missing.join('、')
}

/**
 * 拼装非信封接口的上下文。
 * @param {{rows?: unknown, warnings?: string[], snapshotIds?: string[],
 *          businessTime?: string|null, dataUpdatedAt?: string|null,
 *          definitionVersion?: string|null, qualityStatus?: string|null,
 *          source?: string|null, filters?: object|null, envelopeSource?: object|null,
 *          extraMissing?: string[]}} input
 */
export function buildFallbackContext(input = {}) {
  const {
    rows,
    warnings = [],
    snapshotIds,
    businessTime = null,
    dataUpdatedAt = null,
    definitionVersion = null,
    qualityStatus = null,
    source = null,
    filters = null,
    envelopeSource = null,
    extraMissing = []
  } = input

  // 若响应中夹带了统一信封字段（例如行内含 snapshotId/definitionVersion），一并提取
  const env = readEnvelope(envelopeSource || {})
  const sns = collectSnapshotIds(
    snapshotIds && snapshotIds.length ? snapshotIds : (Array.isArray(rows) ? rows : rows ? [rows] : [])
  )

  const snapshotId = env.snapshotId || (sns.length ? sns.join(' / ') : null)
  const business = env.businessTime || businessTime
  const updated = env.dataUpdatedAt || dataUpdatedAt
  // 口径版本优先取信封；个别接口（如 /metrics/overview）把 definitionVersion 放在指标行里，
  // 此时按行内真实字段回退，取不到才标缺失。
  const rowVersion = Array.isArray(rows) && rows.length ? pickText(rows[0], ['definitionVersion', 'definition_version']) : null
  const version = env.definitionVersion || definitionVersion || rowVersion
  const quality = env.qualityStatus !== 'UNKNOWN' ? env.qualityStatus : qualityStatus
  const filterObj = env.filters && Object.keys(env.filters).length ? env.filters : filters
  // S3-26：来源（发布方）优先取信封，其次取调用方显式给的入参；两者都没有就是 null，
  // 页面显示「未知」。非信封接口本来就没有 source（已由 ENVELOPE_MISSING 标注），
  // 因此这里**不**把它并入 missing 清单，避免把"接口非信封"重复报成"字段缺失"。
  const sourceValue = env.source || source

  const missing = [...extraMissing]
  // 快照号是上下文的第一要素：取不到必须显式标注，不允许页面留空又不说明
  if (!snapshotId) missing.push('snapshotId')
  if (!business) missing.push('业务时间')
  if (!updated) missing.push('数据更新时间')
  if (!version) missing.push('口径版本')
  if (!quality) missing.push('质量状态')
  if (!filterObj || Object.keys(filterObj).length === 0) missing.push('生效筛选')
  if (sns.length > 1) missing.push(`该响应含多个快照号（${sns.join('、')}），未合并为单一快照`)

  return {
    snapshotId,
    businessTime: business,
    dataUpdatedAt: updated,
    // 来源（发布方）：取不到就是 null，由展示层统一显示「未知」（契约 v1.2，非业务源身份）
    source: sourceValue,
    definitionVersion: version,
    // 质量状态取不到按契约降级为 UNKNOWN（不是编造 PASS）
    qualityStatus: quality || 'UNKNOWN',
    filters: filterObj || {},
    warnings: mergeWarnings(warnings, env.warnings),
    missingNotice: missingNotice(missing)
  }
}

/**
 * AI 问答结果的证据上下文。
 * 后端 /ai/queries 不是统一信封：证据在 explanation.evidence（snapshotId/timeRange/definitions 等），
 * 因此这里只搬运证据里真实存在的字段；业务时间、数据更新时间、质量状态、指标口径版本
 * 都不在该接口返回里，一律标为「接口未提供」。
 * @param {{query?: object, explanation?: object}} result
 */
export function buildAiEvidenceContext(result) {
  const query = isRecord(result && result.query) ? result.query : {}
  const explanation = isRecord(result && result.explanation) ? result.explanation : {}
  const evidence = isRecord(explanation.evidence) ? explanation.evidence : {}
  const rows = Array.isArray(query.rows) ? query.rows : []

  const evidenceSnapshotId = pickText(evidence, ['snapshotId'])
  const querySnapshotId = pickText(query, ['snapshotId'])
  const rowSnapshotId = rows.length ? pickText(rows[0], ['snapshot_id', 'snapshotId']) : null
  // 后端证据里的 snapshotId 在 AI 分支上是占位串 'unknown'（真实 SQL 用 MAX(snapshot_id) 锁定），
  // 这种占位值不能当快照号展示，必须按「未提供」处理并说明原因。
  const evidenceSnapshot = isRealSnapshotId(evidenceSnapshotId) ? evidenceSnapshotId : null
  const querySnapshot = isRealSnapshotId(querySnapshotId) ? querySnapshotId : null
  const rowSnapshot = isRealSnapshotId(rowSnapshotId) ? rowSnapshotId : null
  const snapshotId = evidenceSnapshot || querySnapshot || rowSnapshot
  const tables = Array.isArray(evidence.tables) ? evidence.tables : (Array.isArray(query.tables) ? query.tables : [])
  const definitions = pickText(evidence, ['definitions'])
  const timeRange = pickText(evidence, ['timeRange'])

  const missing = []
  if (!evidenceSnapshot) {
    // 说明回退来源，避免读者以为快照号就是证据包自带的
    if (evidenceSnapshotId) missing.push(`证据字段 snapshotId（后端返回占位值 ${evidenceSnapshotId}，未给出真实快照号）`)
    else if (querySnapshot) missing.push('证据字段 snapshotId（已回退取 query.snapshotId）')
    else if (rowSnapshot) missing.push('证据字段 snapshotId（已回退取结果行的 snapshot_id）')
    else missing.push('证据字段 snapshotId')
  }
  missing.push('业务时间', '数据更新时间', '质量状态', '指标口径版本')

  return {
    snapshotId,
    businessTime: null,
    dataUpdatedAt: null,
    // S3-26：AI 证据包不含发布方字段（不是统一信封），显式给 null ⇒ 页面来源一栏显示「未知」，
    // 不用"当前快照的发布方"代替 AI 结果来源
    source: null,
    definitionVersion: null,
    qualityStatus: 'UNKNOWN',
    filters: { 问题: pickText(evidence, ['question']) || '', 时间范围: timeRange || '未指定' },
    warnings: mergeWarnings(explanation.limitations, ['AI_EVIDENCE_PARTIAL']),
    missingNotice: missingNotice(missing),
    evidence: {
      question: pickText(evidence, ['question']),
      sql: pickText(evidence, ['sql']) || pickText(query, ['sql']),
      tables,
      returnedRows: Number.isFinite(evidence.returnedRows) ? evidence.returnedRows : rows.length,
      queryElapsedMs: Number.isFinite(evidence.queryElapsedMs) ? evidence.queryElapsedMs : null,
      timeRange,
      // AI 证据里的 definitions 是解释提示词版本（explain_v1），不是指标口径版本，分开命名避免混淆
      promptVersion: definitions
    }
  }
}

export { WARNING_TEXT_EXTRA }

/**
 * 各页面行数判定用的字段（与 chartState.ENDPOINT_ROW_KEYS 同构，供 useAnalysis 使用）
 * 运维/AI/决策接口不是分析端点，因此单独在这里声明，避免把非契约字段混入 ENDPOINT_ROW_KEYS。
 */
export const NON_ANALYSIS_ROW_KEYS = {
  opsAudit: ['snapshots', 'qualityResults', 'aiHistory', 'aiCalls'],
  opsMetrics: ['metrics'],
  aiQuery: ['queryRows'],
  decisions: ['decisions']
}
