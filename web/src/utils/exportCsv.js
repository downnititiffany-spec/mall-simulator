// CSV 下载层：负责浏览器动作（BOM / Blob / 触发下载），拼装逻辑在 utils/csv.js
import { buildCsvText, buildExportFilename } from './csv'

/**
 * 下载层的最终 fail-closed 判定。
 * - useAnalysis 调用方会把 viewState 放进 context；只允许 ready。
 * - 老的/独立调用方没有 viewState 时保持兼容，但仍要求存在至少一行真实导出数据。
 * @param {{context?: object, rows?: unknown[][]}} input
 */
export function canDownloadCsv({ context = {}, rows = [] } = {}) {
  if (!Array.isArray(rows) || rows.length === 0) return false
  if (context && context.viewState !== undefined && context.viewState !== null) {
    return context.viewState === 'ready'
  }
  return true
}

/**
 * 导出 CSV（自动携带当前 filters、snapshotId 与生成时间）。
 * @param {{baseName: string, context: object, headers: string[], rows: unknown[][], generatedAt?: string}} input
 */
export function exportAnalysisCsv({ baseName, context, headers, rows, generatedAt }) {
  // UI disabled 只是一层提示；handler 被程序化调用、页面处于 stale/error/loading，
  // 或整页 ready 但当前导出子集为空时，都必须在真正创建 Blob 前 fail-closed。
  if (!canDownloadCsv({ context, rows })) return null

  const at = generatedAt || new Date().toISOString()
  const filename = buildExportFilename({ baseName, context, generatedAt: at })
  const text = buildCsvText({ context, generatedAt: at, headers, rows })
  const blob = new Blob(['\ufeff' + text], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  return filename
}
