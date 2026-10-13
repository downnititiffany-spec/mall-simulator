const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const isSafeInteger = (value) => Number.isSafeInteger(value)

const EMPTY_SNAPSHOT_WARNINGS = new Set(['NO_ACTIVE_SNAPSHOT', 'UNKNOWN_SNAPSHOT'])

/** Validate the paged Products view model without changing the shared analysis-envelope fallback contract. */
export function requireProductsPageResponse(value) {
  if (!isRecord(value) || !isRecord(value.data)) {
    throw new Error('商品分页响应格式错误，请刷新重试')
  }

  const { data } = value
  if (Object.keys(data).length === 0) {
    const hasKnownEmptySnapshot = value.snapshotId == null
      && Array.isArray(value.warnings)
      && value.warnings.some((warning) => EMPTY_SNAPSHOT_WARNINGS.has(warning))
    if (hasKnownEmptySnapshot) return value
    throw new Error('商品分页缺少分页数据，请刷新重试')
  }

  const { hot, conversion, topN, page, size, total, hasMore } = data
  if (!Array.isArray(hot) || !Array.isArray(conversion)
    || !isSafeInteger(topN) || topN < 1
    || !isSafeInteger(page) || page < 1
    || !isSafeInteger(size) || size < 1
    || !isSafeInteger(total) || total < 0
    || typeof hasMore !== 'boolean') {
    throw new Error('商品分页元数据无效，请刷新重试')
  }

  const expectedHasMore = BigInt(page) * BigInt(size) < BigInt(total)
  if (topN !== size || hot.length > size || hasMore !== expectedHasMore) {
    throw new Error('商品分页数据与元数据不一致，请刷新重试')
  }

  return value
}
