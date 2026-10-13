/** Validate the owner-scoped report page, including the backend's empty totalPages=0 shape. */
export function requireReportPageResponse(value) {
  const invalid = () => new TypeError('报告分页响应格式无效，无法显示列表')
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.items)) throw invalid()
  const { page, size, total, totalPages } = value
  if (![page, size, total, totalPages].every(Number.isSafeInteger)
      || page < 1 || size < 1 || total < 0 || totalPages < 0
      || totalPages !== (total === 0 ? 0 : Math.ceil(total / size))
      || (total > 0 && page > totalPages) || value.items.length > size) throw invalid()
  return value
}
