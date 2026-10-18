// Keep the client route boundary aligned with the server's runtime:manage matrix.
// Existing admin-only operations remain admin-only; data_dev may manage runtime profiles.
const RUNTIME_MANAGE_ROLES = new Set(['admin', 'data_dev'])

export function canAccessRoute(user, meta = {}) {
  if (meta.requiresAdmin && user?.role !== 'admin') return false
  if (meta.requiresRuntimeManage && !RUNTIME_MANAGE_ROLES.has(user?.role)) return false
  return true
}

const ALLOWED_REDIRECT_PATHS = new Set([
  '/',
  '/overview',
  '/behavior',
  '/products',
  '/rfm',
  '/sales',
  '/ai',
  '/decisions',
  '/reports',
  '/pipeline',
  '/ops',
  '/sources/wizard',
  '/runtime-profiles'
])

/**
 * 校验并解析登录成功后的重定向目标。
 * 纯函数：仅做开放重定向防护与已知白名单路由路径比对，无副作用、不读写存储与全局对象。
 * 注意：回跳后仍受路由守卫 canAccessRoute 二次拦截，无权目标会被守卫回退，本函数不做权限判定。
 *
 * @param {unknown} raw 原始 redirect 参数
 * @returns {string} 合法的站内跳转路径，非法/异常形态一律安全回落 '/'
 */
export function resolveLoginRedirect(raw) {
  if (typeof raw !== 'string') return '/'
  if (!raw.startsWith('/') || raw.startsWith('//')) return '/'
  if (raw.includes('\\') || raw.includes(':')) return '/'
  if (/[\s\x00-\x1f\x7f]/.test(raw)) return '/'

  const path = raw.split(/[?#]/, 1)[0]
  if (!ALLOWED_REDIRECT_PATHS.has(path)) return '/'

  return raw
}

