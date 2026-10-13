export const INGESTION_BATCH_PAGE_SIZES = Object.freeze([10, 20, 50])

const SORTS = Object.freeze({
  'id,desc': { field: 'id', direction: -1 },
  'startTime,desc': { field: 'startTime', direction: -1 },
  'status,asc': { field: 'status', direction: 1 }
})

function comparePresentValues(left, right) {
  if (typeof left === 'number' && typeof right === 'number') return left - right
  return String(left).localeCompare(String(right), 'zh-CN', { numeric: true })
}

export function sortIngestionBatches(rows, requestedSort = 'id,desc') {
  const source = Array.isArray(rows) ? rows : []
  const sort = SORTS[requestedSort] ? requestedSort : 'id,desc'
  const { field, direction } = SORTS[sort]
  return [...source].sort((left = {}, right = {}) => {
    const a = left[field]
    const b = right[field]
    if (a === null || a === undefined) return b === null || b === undefined ? 0 : 1
    if (b === null || b === undefined) return -1
    const primary = comparePresentValues(a, b) * direction
    if (primary !== 0) return primary
    return Number(right.id || 0) - Number(left.id || 0)
  })
}

export function paginateIngestionBatches(rows, requestedPage = 1, requestedSize = 20) {
  const source = Array.isArray(rows) ? rows : []
  const size = INGESTION_BATCH_PAGE_SIZES.includes(Number(requestedSize)) ? Number(requestedSize) : 20
  const totalPages = Math.max(1, Math.ceil(source.length / size))
  const numericPage = Number(requestedPage)
  const normalizedPage = Number.isFinite(numericPage) ? Math.trunc(numericPage) : 1
  const page = Math.min(totalPages, Math.max(1, normalizedPage))
  const start = (page - 1) * size
  return {
    items: source.slice(start, start + size),
    page,
    size,
    total: source.length,
    totalPages
  }
}
