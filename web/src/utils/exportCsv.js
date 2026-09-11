// CSV 下载层：负责浏览器动作（BOM / Blob / 触发下载），拼装逻辑在 utils/csv.js
import { buildCsvText, buildExportFilename } from './csv'

/**
 * 导出 CSV（自动携带当前 filters、snapshotId 与生成时间）。
 * @param {{baseName: string, context: object, headers: string[], rows: unknown[][], generatedAt?: string}} input
 */
export function exportAnalysisCsv({ baseName, context, headers, rows, generatedAt }) {
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
