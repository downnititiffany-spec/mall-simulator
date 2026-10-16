const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const EMPTY_SNAPSHOT_WARNINGS = new Set(['NO_ACTIVE_SNAPSHOT', 'UNKNOWN_SNAPSHOT'])

/** Validate a page's required analysis data fields while preserving the shared readEnvelope fallback contract. */
export function requireAnalysisDataResponse(value, { resourceLabel = '分析结果', arrayFields = [], objectFields = [] } = {}) {
  if (!isRecord(value) || !isRecord(value.data)) {
    throw new Error(`${resourceLabel}响应格式错误，请刷新重试`)
  }

  const { data } = value
  if (Object.keys(data).length === 0) {
    const isKnownEmptySnapshot = value.snapshotId == null
      && Array.isArray(value.warnings)
      && value.warnings.some((warning) => EMPTY_SNAPSHOT_WARNINGS.has(warning))
    if (isKnownEmptySnapshot) return value
    throw new Error(`${resourceLabel}缺少数据字段，请刷新重试`)
  }

  if (arrayFields.some((field) => !Array.isArray(data[field]))
    || objectFields.some((field) => !isRecord(data[field]))) {
    throw new Error(`${resourceLabel}数据结构无效，请刷新重试`)
  }

  return value
}
