/** Fail visibly when an API that promises a list returns another JSON shape. */
export function requireArrayResponse(value, resourceLabel = '列表') {
  if (!Array.isArray(value)) {
    throw new TypeError(`${resourceLabel}响应格式无效，无法显示列表`)
  }
  return value
}
