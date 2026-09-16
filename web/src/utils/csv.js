// CSV 导出（指导书 §18.4：导出必须携带当前 filters、snapshotId 与生成时间）
// 纯逻辑部分（文件名与 CSV 文本拼装）在此，可被 node:test 覆盖；
// 触发下载的浏览器动作单独放在 exportCsv.js，避免测试触碰 DOM。
import { formatDateTime } from './envelope.js'

const MISSING = '（缺失）'

const esc = (v) => {
  const s = v === null || v === undefined ? '' : String(v)
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

/** 筛选条件 → 稳定文本，便于写进文件名与 CSV 元信息 */
export function formatFilters(filters) {
  if (!filters || typeof filters !== 'object') return '（无筛选）'
  const keys = Object.keys(filters).sort()
  if (keys.length === 0) return '（无筛选）'
  return keys.map((k) => `${k}=${filters[k] === null || filters[k] === undefined ? '' : filters[k]}`).join(';')
}

/** 生成时间 → 文件名安全格式：2026-09-10 20:12:33 -> 20260910-201233 */
export function compactTimestamp(value) {
  const text = formatDateTime(value)
  if (text === '—') return 'unknown'
  return text.replace(/[-: ]/g, '').replace(/^(\d{8})(\d{6})$/, '$1-$2')
}

/**
 * 生成导出文件名（含页面标识、快照、生成时间）。
 * @param {{baseName: string, context: object, generatedAt: string}} input
 */
export function buildExportFilename({ baseName, context = {}, generatedAt }) {
  const snapshot = context.snapshotId || 'no-snapshot'
  return `${baseName}-${snapshot}-${compactTimestamp(generatedAt)}.csv`
}

/**
 * CSV 元信息行：快照、生成时间、来源（发布方）、口径版本、质量状态、filters。
 * 导出文件必须能自证“数据来自哪次快照、谁发布的、按什么筛选、什么时候导出”。
 */
export function buildContextRows(context = {}, generatedAt) {
  const rows = [
    ['# 快照ID', context.snapshotId || MISSING],
    ['# 业务时间', formatDateTime(context.businessTime)],
    ['# 数据更新时间', formatDateTime(context.dataUpdatedAt)],
    // S3-26：与上下文条同一字段（信封 source = 发布方，契约 v1.2）；取不到写「（缺失）」不写 spark-ads
    ['# 来源（发布方）', context.source || MISSING],
    ['# 口径版本', context.definitionVersion || MISSING],
    ['# 质量状态', context.qualityStatus || 'UNKNOWN'],
    ['# 生效筛选', formatFilters(context.filters)],
    ['# 生成时间', formatDateTime(generatedAt)],
    ['# 告警', Array.isArray(context.warnings) && context.warnings.length ? context.warnings.join('; ') : '无']
  ]
  // 上下文缺失说明也要写进文件，导出件离开页面后仍能自证哪些字段接口未提供
  if (context.missingNotice) rows.push(['# 上下文缺失', context.missingNotice])
  return rows
}

/**
 * 拼装完整 CSV 文本（不含 BOM，BOM 由下载层添加）。
 * @param {{context: object, generatedAt: string, headers: string[], rows: unknown[][]}} input
 */
export function buildCsvText({ context = {}, generatedAt, headers = [], rows = [] }) {
  const lines = buildContextRows(context, generatedAt).map((r) => r.map(esc).join(','))
  lines.push('')
  lines.push(headers.map(esc).join(','))
  for (const row of Array.isArray(rows) ? rows : []) {
    lines.push((Array.isArray(row) ? row : [row]).map(esc).join(','))
  }
  return lines.join('\n')
}

export { esc as escapeCsvCell }
