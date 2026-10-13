/** Validate the shared one-based pagination envelope used by Decisions and Pipeline. */
export function requirePageResponse(value, resourceLabel = '分页结果') {
  const invalid = () => new TypeError(`${resourceLabel}响应格式无效，无法显示列表`)
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.items)) {
    throw invalid()
  }

  const { page, size, total, totalPages } = value
  if (![page, size, total, totalPages].every(Number.isSafeInteger)
    || page < 1 || size < 1 || total < 0 || totalPages < 1) {
    throw invalid()
  }

  const expectedTotalPages = total === 0 ? 1 : Math.ceil(total / size)
  if (totalPages !== expectedTotalPages || page > totalPages || value.items.length > size) {
    throw invalid()
  }
  return value
}
