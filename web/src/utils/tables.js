// 运维 / AI / 决策三页的表格与导出数据拼装（指导书 §18.4 / §24.6）
// 纯逻辑：只做「后端字段 → 列头 + 行」的搬运与格式化，不重算任何指标。
// 缺失值统一显示为 '—' 或「接口未提供」，不填 0、不猜测。
import { EMPTY_TEXT, formatInteger, formatNumber } from './number.js'
import { formatDateTime } from './envelope.js'

const text = (v) => (v === null || v === undefined || v === '' ? EMPTY_TEXT : String(v))

/** 数值列：后端给数字就格式化，给不了就占位符 */
const num = (v, digits = 2) => formatNumber(v, digits)

/** 质量规则结果行 → 导出/展示列 */
export function qualityRows(list) {
  return (Array.isArray(list) ? list : []).map((q) => ({
    runId: q.runId === null || q.runId === undefined ? null : q.runId,
    ruleCode: text(q.ruleCode),
    passed: q.passed === 1 || q.passed === true,
    detail: text(q.detail),
    createdAt: formatDateTime(q.createdAt)
  }))
}

/** 快照列表行：状态与版本原样展示，不推断 */
export function snapshotRows(list) {
  return (Array.isArray(list) ? list : []).map((s) => ({
    snapshotId: text(s.snapshotId),
    businessTime: formatDateTime(s.businessTime),
    status: text(s.status),
    version: s.version === null || s.version === undefined ? EMPTY_TEXT : 'v' + s.version,
    dataUpdatedAt: formatDateTime(s.dataUpdatedAt)
  }))
}

/** 指标行 → 表格/导出行（数值原样来自后端） */
export function metricRows(list) {
  return (Array.isArray(list) ? list : []).map((m) => ({
    metricCode: text(m.metricCode),
    value: num(m.value),
    unit: text(m.unit),
    period: text(m.period),
    snapshotId: text(m.snapshotId),
    definitionVersion: text(m.definitionVersion)
  }))
}

/** AI 问答审计行 */
export function aiHistoryRows(list) {
  return (Array.isArray(list) ? list : []).map((h) => ({
    userId: text(h.userId),
    question: text(h.question),
    sqlText: text(h.sqlText),
    tables: text(h.tables),
    status: text(h.status),
    rowsReturned: formatInteger(h.rowsReturned),
    elapsedMs: h.elapsedMs === null || h.elapsedMs === undefined ? text(h.elapsedMs) : formatInteger(h.elapsedMs) + 'ms',
    errors: text(h.errors),
    createdAt: formatDateTime(h.createdAt)
  }))
}

/** AI 模型调用审计行 */
export function aiCallRows(list) {
  return (Array.isArray(list) ? list : []).map((c) => ({
    useCase: text(c.useCase),
    provider: text(c.provider),
    model: text(c.model),
    inputTokens: formatInteger(c.inputTokens),
    outputTokens: formatInteger(c.outputTokens),
    elapsedMs: c.elapsedMs === null || c.elapsedMs === undefined ? text(c.elapsedMs) : formatInteger(c.elapsedMs) + 'ms',
    promptVersion: text(c.promptVersion),
    status: text(c.status),
    error: text(c.error),
    createdAt: formatDateTime(c.createdAt)
  }))
}

/** 流水线实例行（溯源链路：业务时间 / 源数据版本 / 目标快照 / 当前阶段） */
export function pipelineRunRows(list) {
  return (Array.isArray(list) ? list : []).map((r) => ({
    id: text(r.id),
    pipelineCode: text(r.pipelineCode),
    businessTime: formatDateTime(r.businessTime),
    // S3-34：结果所属**数据源版本**（§PipelineService:169 逐次运行写入 'manual-<ms>' 等），
    // V2 既有列；后端列表接口返回实体整行，前端只搬运，不重算。
    sourceDataVersion: text(r.sourceDataVersion),
    // S3-37：本 run **消费了哪一批**（`pipeline_run.input_batch_id` → `ingestion_batch.id`）。
    // 该列由 V7 建好、S3-36 起写入侧真的落库（A 类加性），后端列表接口返回实体整行 ⇒
    // 前端此前只是**没搬运**。S3-36 不回填历史行 ⇒ 老实例为 NULL ⇒ 显示占位符（NULL 是
    // 「未知」，**不得**用 targetSnapshotId / sourceDataVersion 顶替，也不得显示 0）。
    inputBatchId: text(r.inputBatchId),
    targetSnapshotId: text(r.targetSnapshotId),
    attemptNo: formatInteger(r.attemptNo),
    status: text(r.status),
    currentStage: text(r.currentStage),
    errorCode: text(r.errorCode),
    createdAt: formatDateTime(r.createdAt)
  }))
}

/** 决策任务行：基线/目标/快照号来自后端，不重算 */export function decisionRows(list) {
  return (Array.isArray(list) ? list : []).map((d) => ({
    decisionNo: text(d.decisionNo),
    title: text(d.title),
    source: text(d.source),
    targetMetricCode: text(d.targetMetricCode),
    targetDirection: text(d.targetDirection),
    baselineValue: num(d.baselineValue),
    targetValue: num(d.targetValue),
    suggestionSnapshotId: text(d.suggestionSnapshotId),
    risk: text(d.risk),
    owner: text(d.owner),
    status: text(d.status),
    createdAt: formatDateTime(d.createdAt)
  }))
}

/** 决策效果评价行：改善率按 decimal → 百分比展示（后端已算好） */
export function evaluationRows(list) {
  return (Array.isArray(list) ? list : []).map((e) => ({
    result: text(e.result),
    improvementRate: e.improvementRate === null || e.improvementRate === undefined
      ? EMPTY_TEXT
      : formatNumber(Number(e.improvementRate) * 100, 2) + '%',
    baselineValue: num(e.baselineValue),
    actualValue: num(e.actualValue),
    evalWindowDays: formatInteger(e.evalWindowDays),
    createdAt: formatDateTime(e.createdAt)
  }))
}

/** 通用：把 [{...}] 按给定列定义转成 [表头, 行数组] */
export function toTable(rowObjects, columns) {
  const list = Array.isArray(rowObjects) ? rowObjects : []
  return {
    headers: columns.map((c) => c.label || c.key),
    rows: list.map((item) => columns.map((c) => (c.format ? c.format(item[c.key], item) : item[c.key])))
  }
}

export const COLUMNS = {
  opsMetrics: [
    { key: 'metricCode', label: '指标编码' },
    { key: 'value', label: '数值' },
    { key: 'unit', label: '单位' },
    { key: 'period', label: '周期' },
    { key: 'snapshotId', label: '快照' },
    { key: 'definitionVersion', label: '口径版本' }
  ],
  opsQuality: [
    { key: 'runId', label: '流水线' },
    { key: 'ruleCode', label: '规则' },
    { key: 'passed', label: '结果', format: (v) => (v === true ? '通过' : '未通过') },
    { key: 'detail', label: '信息' },
    { key: 'createdAt', label: '时间' }
  ],
  opsSnapshots: [
    { key: 'snapshotId', label: '快照 ID' },
    { key: 'businessTime', label: '业务时间' },
    { key: 'status', label: '状态' },
    { key: 'version', label: '版本' }
  ],
  opsPipelineRuns: [
    { key: 'id', label: '实例' },
    { key: 'pipelineCode', label: '流水线' },
    { key: 'businessTime', label: '业务时间' },
    { key: 'sourceDataVersion', label: '源数据版本' },
    { key: 'inputBatchId', label: '输入批次' },
    { key: 'targetSnapshotId', label: '目标快照' },
    { key: 'attemptNo', label: '尝试次数' },
    { key: 'status', label: '状态' },
    { key: 'currentStage', label: '当前阶段' },
    { key: 'errorCode', label: '错误码' },
    { key: 'createdAt', label: '创建时间' }
  ],
  opsAiHistory: [
    { key: 'userId', label: '用户' },
    { key: 'question', label: '问题' },
    { key: 'sqlText', label: 'SQL' },
    { key: 'tables', label: '使用表' },
    { key: 'status', label: '状态' },
    { key: 'rowsReturned', label: '行数' },
    { key: 'elapsedMs', label: '耗时' },
    { key: 'errors', label: '错误' },
    { key: 'createdAt', label: '时间' }
  ],
  opsAiCalls: [
    { key: 'useCase', label: '用例' },
    { key: 'provider', label: '提供方' },
    { key: 'model', label: '模型' },
    { key: 'inputTokens', label: '输入 tokens' },
    { key: 'outputTokens', label: '输出 tokens' },
    { key: 'elapsedMs', label: '耗时' },
    { key: 'promptVersion', label: '提示词版本' },
    { key: 'status', label: '状态' },
    { key: 'error', label: '错误' },
    { key: 'createdAt', label: '时间' }
  ],
  decisions: [
    { key: 'decisionNo', label: '编号' },
    { key: 'title', label: '标题' },
    { key: 'source', label: '来源' },
    { key: 'targetMetricCode', label: '目标指标' },
    { key: 'targetDirection', label: '方向' },
    { key: 'baselineValue', label: '基线' },
    { key: 'targetValue', label: '目标' },
    { key: 'suggestionSnapshotId', label: '建议快照' },
    { key: 'risk', label: '风险' },
    { key: 'owner', label: '负责人' },
    { key: 'status', label: '状态' },
    { key: 'createdAt', label: '创建时间' }
  ],
  evaluations: [
    { key: 'result', label: '评价结果' },
    { key: 'baselineValue', label: '基线' },
    { key: 'actualValue', label: '实际' },
    { key: 'improvementRate', label: '改善率' },
    { key: 'evalWindowDays', label: '窗口(天)' },
    { key: 'createdAt', label: '评价时间' }
  ]
}

/**
 * AI 结果表列名中文化（指导书 §18.4 展示要求）。
 * 只做“列名 → 中文标签”的映射，键名与数值原样保留；未登记的键名原样显示，
 * 保证新增指标列不会被隐藏，也不会被错误翻译。
 */
export const RESULT_COLUMN_LABELS = {
  dt: '业务日期',
  stat_date: '业务日期',
  pv: '浏览量',
  uv: '访客数',
  dau: '日活用户数',
  order_count: '订单数',
  sale_amount: '销售额',
  net_sale_amount: '净销售额',
  avg_order_value: '客单价',
  refund_rate: '退款率',
  full_refund_rate: '全额退款率',
  refund_amount: '退款金额',
  buy_rate: '购买转化率',
  roi: '投产比',
  snapshot_id: '快照'
}

export const resultColumnLabel = (key) => RESULT_COLUMN_LABELS[key] || key

/**
 * AI 问答结果 → 导出列（动态列：列名取结果行的键，原样搬运）
 * 行内键即证据字段名，便于复核 SQL 结果与快照。
 */
export function aiResultTable(rows) {
  const list = Array.isArray(rows) ? rows.filter((r) => r && typeof r === 'object') : []
  const keys = []
  for (const row of list) {
    for (const k of Object.keys(row)) if (!keys.includes(k)) keys.push(k)
  }
  return {
    headers: keys.map(resultColumnLabel),
    // 导出文件的列名同时带中文标签与原始字段名，便于与 SQL 对照
    rows: list.map((row) => keys.map((k) => (row[k] === null || row[k] === undefined ? '' : String(row[k]))))
  }
}

/** 结果表导出表头：中文标签（原始字段名），未登记字段只显示原字段名 */
export function aiResultCsvHeaders(rows) {
  const list = Array.isArray(rows) ? rows.filter((r) => r && typeof r === 'object') : []
  const keys = []
  for (const row of list) {
    for (const k of Object.keys(row)) if (!keys.includes(k)) keys.push(k)
  }
  return keys.map((k) => (RESULT_COLUMN_LABELS[k] ? `${RESULT_COLUMN_LABELS[k]}（${k}）` : k))
}
