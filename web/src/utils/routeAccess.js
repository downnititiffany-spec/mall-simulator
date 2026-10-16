// Keep the client route boundary aligned with the server's runtime:manage matrix.
// Existing admin-only operations remain admin-only; data_dev may manage runtime profiles.
const RUNTIME_MANAGE_ROLES = new Set(['admin', 'data_dev'])

export function canAccessRoute(user, meta = {}) {
  if (meta.requiresAdmin && user?.role !== 'admin') return false
  if (meta.requiresRuntimeManage && !RUNTIME_MANAGE_ROLES.has(user?.role)) return false
  return true
}
