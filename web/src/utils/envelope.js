// 分析响应信封解包（契约 analysis-viewmodel-r7-4 §2 / 指导书 §18.3）
// 纯逻辑，不依赖 vue/浏览器，可直接被 node:test 覆盖。
// 原则：字段缺失一律降级为 null / 空数组，绝不抛异常，也绝不编造数值。

// 非空字符串判定：空串按“缺字段”处理
const asText = (v) => (typeof v === 'string' && v.trim() !== '' ? v : null)

// 数组字段透传；缺失或类型不符时返回空数组
const asList = (v) => (Array.isArray(v) ? v : [])

// 普通对象字段透传；缺失或类型不符时返回空对象
const asRecord = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : {})

/**
 * 解包统一信封。
 * 兼容两种入参：完整信封 { snapshotId, data, ... } 与仅含业务数据的裸对象。
 * @param {unknown} envelope 后端 data 字段（已由 api.js 剥掉 ApiResponse 外壳）
 * @returns {{snapshotId: string|null, businessTime: string|null, dataUpdatedAt: string|null,
 *            source: string|null, definitionVersion: string|null, qualityStatus: string, filters: object,
 *            warnings: string[], data: object}}
 */
export function readEnvelope(envelope) {
  const src = envelope && typeof envelope === 'object' && !Array.isArray(envelope) ? envelope : {}
  return {
    snapshotId: asText(src.snapshotId),
    businessTime: asText(src.businessTime),
    dataUpdatedAt: asText(src.dataUpdatedAt),
    // S3-26：`source` = metric_snapshot.source，即该快照的**发布方/生产者**（契约 v1.2 §2 字段表；
    // §17.6 成功快照只接受 spark-ads）。**不是业务源身份**：业务源由 per-source namespace 与
    // ODS/DWD 的 source_system/source_instance_id 承载，页面不得把它当源身份展示或过滤。
    source: asText(src.source),
    definitionVersion: asText(src.definitionVersion),
    // 质量门结论取不到时按契约降级为 UNKNOWN
    qualityStatus: asText(src.qualityStatus) || 'UNKNOWN',
    filters: asRecord(src.filters),
    // 降级事实必须透传，不得吞掉
    warnings: asList(src.warnings).filter((w) => typeof w === 'string' && w.trim() !== ''),
    data: asRecord(src.data)
  }
}

/** 是否存在可见告警（非空数组） */
export function hasWarnings(ctx) {
  return Boolean(ctx && Array.isArray(ctx.warnings) && ctx.warnings.length > 0)
}

/** 告警文案（中文展示，未知编码原样展示以便排查） */
const WARNING_TEXT = {
  NO_ACTIVE_SNAPSHOT: '当前没有 ACTIVE 快照，指标库尚未发布可用数据。',
  UNKNOWN_DIMENSION_TABLE: '部分维度表本期不存在，对应维度为空。'
}
export const warningText = (code) => WARNING_TEXT[code] || String(code)

/** 质量状态展示语义（PASS / FAIL / UNKNOWN） */
export function qualityText(status) {
  if (status === 'PASS') return '质量通过'
  if (status === 'FAIL') return '质量未通过'
  return '质量未知'
}

/**
 * 来源（发布方）展示语义（S3-26）。
 *
 * 契约 v1.2 §2：`source` 取不到为 null、空串按缺字段处理；展示层同样**不臆造发布方**，
 * 取不到一律显示「未知」——把缺失写成 `spark-ads` 会让归档/历史快照看起来像正常发布。
 */
export function sourceText(source) {
  return asText(source) || '未知'
}

/** ISO 时间转本地可读文本：2026-09-10T20:12:33 -> 2026-09-10 20:12:33 */
export function formatDateTime(value) {
  const s = asText(value)
  if (!s) return '—'
  return s.replace('T', ' ').slice(0, 19)
}
