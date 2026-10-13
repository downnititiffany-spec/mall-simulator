export function requireRuntimeProfileList(value, resourceLabel) {
  if (!Array.isArray(value)) {
    throw new TypeError(`${resourceLabel}列表响应格式无效`)
  }
  return value
}
