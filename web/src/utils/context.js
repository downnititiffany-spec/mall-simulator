// 统一上下文拼装（契约 analysis-viewmodel-r7-4 §2 / 指导书 §18.3）
// 分析端点返回完整信封；运维、AI、决策等接口不是统一信封（返回裸数组或各自结构），
// 这里把它们的公共字段拼成一个与信封同形的上下文，并显式记录哪些上下文信息「接口未提供」。
// 纯逻辑，不依赖 vue/浏览器，可被 node:test 覆盖。
// 原则：拼装出来的每个字段都必须来自后端真实响应，取不到就写「接口未提供」，绝不编造。
import { readEnvelope, WARNING_TEXT } from './envelope.js'

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
 *
 * S3-33 实测缺陷：此前实现只查 `WARNING_TEXT_EXTRA`，与本 KDoc 声明的链式顺序不符 ⇒
 * 信封侧降级码（`NO_ACTIVE_SNAPSHOT`、三个 `RFM_*` 等）在页面上原样打出编码。页面只有
 * 本函数一个渲染入口（`AnalysisContext.vue`、`AiAssistant.vue`），故必须在此完成链式解析。
 */
export function warningTextAll(code) {
  return WARNING_TEXT[code] || WARNING_TEXT_EXTRA[code] || String(code)
}

const isRecord = (v) => v && typeof v === 'object' && !Array.isArray(v)

/**
 * 是否为真实快照号。
 * AI 证据包在规则回退分支会返回占位串 'unknown'，那不是快照号，页面必须按「未提供」展示。
 * 后端标识属于精确值：前后空白不做静默 trim，出现即按无效标识处理（fail-closed）。
 */
export function isRealSnapshotId(value) {
  if (typeof value !== 'string') return false
  const text = value.trim()
  return text !== '' && value === text && text.toLowerCase() !== 'unknown'
}

/** 从任意对象中取第一个非空字符串/有限数字字段；仅用于展示型普通字段，不用于 ID。 */
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
 * Return the date window echoed by the backend, if one exists.
 * This intentionally does not read page form state: the context bar describes
 * the query that produced the visible result, not a possibly newer user input.
 */
export function effectiveWindowText(filters) {
  if (!isRecord(filters)) return null
  const from = pickText(filters, ['from'])
  const to = pickText(filters, ['to'])
  if (from && to) return `${from} ~ ${to}`
  if (from) return `自 ${from} 起`
  if (to) return `截至 ${to}`
  const date = pickText(filters, ['date'])
  if (date) return date
  const aiWindow = pickText(filters, ['时间范围'])
  return aiWindow || null
}

/**
 * 从后端对象中读取标识字段。
 * ID 不接受数字自动转字符串，也不做 trim；真实性统一交给 isRealSnapshotId 判定。
 */
function pickIdentifier(source, keys) {
  if (!isRecord(source)) return null
  for (const key of keys) {
    const v = source[key]
    if (typeof v === 'string') return v
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
    const id = pickIdentifier(v, ['snapshotId', 'snapshot_id', 'suggestionSnapshotId'])
    if (isRealSnapshotId(id)) set.add(id)
  }
  return [...set].sort()
}

/** 合并告警：去重、丢空、保持首次出现顺序 */
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
 *          sourceId?: number|null, source?: string|null, filters?: object|null, envelopeSource?: object|null,
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
    sourceId = null,
    source = null,
    filters = null,
    envelopeSource = null,
    extraMissing = []
  } = input

  // 若响应中夹带了统一信封字段（例如行内含 snapshotId/definitionVersion），一并提取
  const env = readEnvelope(envelopeSource || {})
  // 若信封明确包含 sourceId（包括 null），它是该快照的唯一事实来源；显式 null 不回退到
  // 调用参数，防止历史快照被当前选择的业务来源补值。无信封字段时才接受接口提供的 sourceId。
  const envelopeHasSourceId = isRecord(envelopeSource)
    && Object.prototype.hasOwnProperty.call(envelopeSource, 'sourceId')
  const businessSourceId = envelopeHasSourceId ? env.sourceId : readEnvelope({ sourceId }).sourceId
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
    sourceId: businessSourceId,
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
 * 快照身份的**唯一**归一化（实测评价 §QA-03）。
 *
 * 为什么必须只有一处：此前 `snapshotId` 只放在本模块返回值的顶层，嵌套 `evidence` 没有它，
 * 页面读的却是嵌套字段 ⇒ 真实快照号 S20260921_20 被显示成「未提供」，提示还编出
 * 「SQL 用 MAX(snapshot_id)」（SQL 里没有 MAX）。展示值与决策草稿锚点必须读同一结果。
 *
 * 判据仍是 {@link isRealSnapshotId}（fail-closed）：不 trim、不把数字转字符串、
 * `unknown` 任意大小写一律无效；无效值**不**回落成占位串，只如实说「未提供」。
 *
 * @param {unknown} value 后端给出的原始快照号
 * @returns {{snapshotId: string|null, text: string, hint: string}}
 */
export function snapshotIdentity(value) {
  const ok = isRealSnapshotId(value)
  return {
    snapshotId: ok ? value : null,
    text: ok ? value : '未提供',
    // 提示只陈述「证据包没给出可用快照号」，不推断 SQL 怎么锁快照、也不回显占位串
    hint: ok ? '' : '（证据包未返回可用的快照号）'
  }
}

/**
 * 从「证据 → query → 结果行」按既有优先序解析快照身份。
 * `source` 只用于缺失说明，不改变身份本身。
 */
export function aiSnapshotIdentity({ evidence, query, rows } = {}) {
  const candidates = [
    ['evidence', pickIdentifier(evidence, ['snapshotId'])],
    ['query', pickIdentifier(query, ['snapshotId'])],
    ['row', pickIdentifier(Array.isArray(rows) ? rows[0] : null, ['snapshot_id', 'snapshotId'])]
  ]
  for (const [source, raw] of candidates) {
    if (isRealSnapshotId(raw)) return { ...snapshotIdentity(raw), source }
  }
  return { ...snapshotIdentity(null), source: 'none' }
}

/**
 * 归一化 `/ai/queries` 返回的结构化查询窗口 `query.window`（实测评价 §QA-04）。
 *
 * 有效查询期、参考业务日、实际覆盖天数以后端结论为唯一事实来源——请求标签由模型自己解析，
 * 不能拿来当解释口径。字段缺失一律 `null`（`days` 按契约取 0），绝不前端推算日期差。
 *
 * @param {unknown} raw 后端 `query.window`
 * @returns {object|null} 无结构化窗口时 null（旧后端/错误响应），页面据此诚实降级
 */
export function normalizeAiWindow(raw) {
  if (!isRecord(raw)) return null
  return {
    requested: pickText(raw, ['requested']),
    requestedDays: Number.isFinite(raw.requestedDays) ? raw.requestedDays : null,
    from: pickText(raw, ['from']),
    to: pickText(raw, ['to']),
    days: Number.isFinite(raw.days) ? raw.days : 0,
    referenceBusinessDate: pickText(raw, ['referenceBusinessDate']),
    coveredDays: Number.isFinite(raw.coveredDays) ? raw.coveredDays : null,
    notice: pickText(raw, ['notice'])
  }
}

/**
 * 结构化窗口里的有效查询期文本：`2026-09-15 ~ 2026-09-21（共 7 天）`。
 * 区间不可得时返回 null（不编造单日、不编造天数）。
 */
export function windowRangeText(window) {
  if (!isRecord(window) || !window.from || !window.to) return null
  const days = typeof window.days === 'number' && window.days > 0 ? `（共 ${window.days} 天）` : ''
  return `${window.from} ~ ${window.to}${days}`
}

/**
 * 有效查询期的展示文本（唯一来源）：结构化窗口优先，其次证据自带的 timeRange，
 * 都没有才是「接口未提供」。**不**回落到请求标签——那正是 QA-04 的矛盾来源。
 * 窗口存在但区间不可得时（如空窗口），仍原样转达后端 notice，不假装没有任何口径信息。
 */
export function windowDisplayText(window, fallbackTimeRange) {
  const range = windowRangeText(window)
  const hasWindow = isRecord(window)
  const notice = hasWindow ? window.notice : null
  if (!range) {
    // 后端明确给了窗口却说不出区间（空窗口）：以结构化结论为准，绝不拿请求侧的旧文本凑数
    if (hasWindow) return notice ? `（${notice}）` : MISSING_TEXT
    // 完全无结构化窗口（旧后端）：只如实展示后端**自己返回**的文本，空白一律当没有
    return typeof fallbackTimeRange === 'string' && fallbackTimeRange.trim() !== '' ? fallbackTimeRange : MISSING_TEXT
  }
  // 后端给出的口径提示原样转达；后端没给但声明的覆盖天数确实不足时，只补一句事实
  const coverage = !notice && Number.isFinite(window.coveredDays) && window.days > 0 && window.coveredDays < window.days
    ? `（结果只覆盖 ${window.coveredDays} / ${window.days} 天，其余业务日无数据）`
    : ''
  return `${range}${notice ? `（${notice}）` : coverage}`
}

/**
 * AI 问答结果的证据上下文。
 * 后端 /ai/queries 不是统一信封：证据在 explanation.evidence（snapshotId/timeRange/definitions 等），
 * 因此这里只搬运证据里真实存在的字段；业务时间、数据更新时间、质量状态、指标口径版本
 * 都不在该接口返回里，一律标为「接口未提供」。
 * @param {{query?: object, explanation?: object, evidenceId?: string|null}} result
 */
export function buildAiEvidenceContext(result) {
  const query = isRecord(result && result.query) ? result.query : {}
  const explanation = isRecord(result && result.explanation) ? result.explanation : {}
  const evidence = isRecord(explanation.evidence) ? explanation.evidence : {}
  const rows = Array.isArray(query.rows) ? query.rows : []

  const evidenceSnapshotId = pickIdentifier(evidence, ['snapshotId'])
  // 快照身份只归一化一次：顶层 snapshotId 与嵌套 evidence.* 都取这一份结果
  const snapshot = aiSnapshotIdentity({ evidence, query, rows })
  const snapshotId = snapshot.snapshotId
  const tables = Array.isArray(evidence.tables) ? evidence.tables : (Array.isArray(query.tables) ? query.tables : [])
  const definitions = pickText(evidence, ['definitions'])
  const rawTimeRange = pickText(evidence, ['timeRange'])
  // QA-04：结构化 window 是有效查询期/参考业务日/覆盖情况的唯一来源；timeRange 优先它的区间。
  // 窗口存在但区间为空时不补日期，退回证据原文（window.notice 由 windowDisplayText 负责转达，
  // 不重复并入 timeRange，否则展示层会叠加两遍同一句后端提示）。
  const window = normalizeAiWindow(query.window)
  const timeRange = window
    ? (windowRangeText(window) || rawTimeRange || null)
    : (rawTimeRange || null)
  // S3-54：结论文本只来自后端 ExplanationResult.summary。页面已经读取 evidenceContext.summary，
  // 这里必须显式搬运；缺失/空白就返回 null，让页面显示“后端未给出结论文本”，绝不前端拼结论。
  const summary = pickText(explanation, ['summary'])

  // S3-53/S3-55：证据包 ID 的值属主仍是后端。ID 必须是精确字符串，禁止数字自动转串、
  // 禁止前后空白静默 trim；顶层真实值优先，顶层无效时才回退嵌套 EvidencePackage。
  const topLevelEvidenceId = pickIdentifier(result, ['evidenceId'])
  const nestedEvidenceId = pickIdentifier(evidence, ['evidenceId'])
  const evidenceId = isRealSnapshotId(topLevelEvidenceId)
    ? topLevelEvidenceId
    : (isRealSnapshotId(nestedEvidenceId) ? nestedEvidenceId : null)

  const missing = []
  if (!snapshotId) {
    // 说明回退来源/占位情况，避免读者以为快照号就是证据包自带的
    if (evidenceSnapshotId && !isRealSnapshotId(evidenceSnapshotId)) {
      missing.push(`证据字段 snapshotId（后端返回占位值 ${evidenceSnapshotId}，未给出真实快照号）`)
    } else if (snapshot.source === 'query') missing.push('证据字段 snapshotId（已回退取 query.snapshotId）')
    else if (snapshot.source === 'row') missing.push('证据字段 snapshotId（已回退取结果行的 snapshot_id）')
    else missing.push('证据字段 snapshotId')
  } else if (snapshot.source === 'query') {
    // 拿到了快照号但来源不是证据包，仍要说明出处，读者才知道该字段是回退取来的
    missing.push('证据字段 snapshotId（已回退取 query.snapshotId）')
  } else if (snapshot.source === 'row') {
    missing.push('证据字段 snapshotId（已回退取结果行的 snapshot_id）')
  }
  missing.push('业务时间', '数据更新时间', '质量状态', '指标口径版本')

  return {
    summary,
    snapshotId,
    // AI 证据接口未提供业务来源登记 ID；不借用当前选源或发布方填充。
    sourceId: null,
    businessTime: null,
    dataUpdatedAt: null,
    // S3-26：AI 证据包不含发布方字段（不是统一信封），显式给 null ⇒ 页面来源一栏显示「未知」，
    // 不用"当前快照的发布方"代替 AI 结果来源
    source: null,
    definitionVersion: null,
    qualityStatus: 'UNKNOWN',
    filters: { 问题: pickText(evidence, ['question']) || '', 时间范围: windowDisplayText(window, rawTimeRange) },
    warnings: mergeWarnings(explanation.limitations, ['AI_EVIDENCE_PARTIAL']),
    missingNotice: missingNotice(missing),
    evidence: {
      question: pickText(evidence, ['question']),
      sql: pickText(evidence, ['sql']) || pickText(query, ['sql']),
      tables,
      returnedRows: Number.isFinite(evidence.returnedRows) ? evidence.returnedRows : rows.length,
      queryElapsedMs: Number.isFinite(evidence.queryElapsedMs) ? evidence.queryElapsedMs : null,
      timeRange,
      // QA-04：结构化查询窗口原样搬运（无则 null），页面据它展示有效查询期/参考业务日/覆盖提示
      window,
      // QA-03：展示值与决策草稿锚点共用的同一份快照身份，页面不再自建判据
      snapshotId,
      snapshotText: snapshot.text,
      snapshotHint: snapshot.hint,
      // AI 证据里的 definitions 是解释提示词版本（explain_v1），不是指标口径版本，分开命名避免混淆
      promptVersion: definitions,
      // 证据包 ID：决策草稿的 evidence_package_id 锚点来源。优先顶层，其次嵌套 EvidencePackage；
      // 无效/占位值归一为 null，因此页面展示与 draftAnchor 共用同一结果。
      evidenceId
    }
  }
}

export { WARNING_TEXT_EXTRA }

/**
 * 各页面行数判定用的字段（与 chartState.ENDPOINT_ROW_KEYS 同构，供 useAnalysis 使用）
 * 运维、AI、决策接口不是分析端点，因此单独在这里声明，避免把非契约字段混入 ENDPOINT_ROW_KEYS。
 */
export const NON_ANALYSIS_ROW_KEYS = {
  opsAudit: ['snapshots', 'qualityResults', 'aiHistory', 'aiCalls'],
  opsMetrics: ['metrics'],
  aiQuery: ['queryRows'],
  decisions: ['decisions'],
  // S3-35：/pipeline 页取数状态收敛到 useAnalysis 后，其 data 形状＝{ pipelineRuns: [...] }
  pipelineRuns: ['pipelineRuns']
}
