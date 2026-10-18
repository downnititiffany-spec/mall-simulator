import test, { beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import api, { handleApiResponse, handleApiError, redirectToLogin } from '../src/api.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_DIR = join(HERE, '..')
const API_SRC = readFileSync(join(WEB_DIR, 'src', 'api.js'), 'utf8')

const TOKEN_KEY = 'analytics_token'
const USER_KEY = 'analytics_user'

const initialLocalStorage = globalThis.localStorage
const initialWindow = globalThis.window
const hadLocalStorage = 'localStorage' in globalThis
const hadWindow = 'window' in globalThis

/**
 * 创建基于 Map 的内存存储桩，模拟浏览器 localStorage 接口
 */
function createMemoryStorage(initial = {}) {
  const store = new Map(Object.entries(initial))
  return {
    getItem(key) {
      return store.has(key) ? store.get(key) : null
    },
    setItem(key, value) {
      store.set(key, String(value))
    },
    removeItem(key) {
      store.delete(key)
    },
    clear() {
      store.clear()
    },
    key(index) {
      return Array.from(store.keys())[index] ?? null
    },
    get length() {
      return store.size
    }
  }
}

/**
 * 创建模拟浏览器 window.location 全局对象桩
 */
function createMemoryWindow({ pathname = '/', href = pathname } = {}) {
  return {
    location: {
      pathname,
      href
    }
  }
}

/**
 * 为用例安装独立的全局内存桩
 */
function setupEnv({ pathname = '/', href = pathname, storage = {} } = {}) {
  const memStorage = createMemoryStorage(storage)
  const memWindow = createMemoryWindow({ pathname, href })
  globalThis.localStorage = memStorage
  globalThis.window = memWindow
  return { storage: memStorage, window: memWindow }
}

/**
 * 严格恢复测试前的全局对象，杜绝测试间全局状态污染
 */
function restoreGlobals() {
  if (hadLocalStorage) {
    globalThis.localStorage = initialLocalStorage
  } else {
    try {
      delete globalThis.localStorage
    } catch {
      globalThis.localStorage = undefined
    }
  }
  if (hadWindow) {
    globalThis.window = initialWindow
  } else {
    try {
      delete globalThis.window
    } catch {
      globalThis.window = undefined
    }
  }
}

beforeEach(() => {
  restoreGlobals()
})

afterEach(() => {
  restoreGlobals()
})

// ── 必测场景 a: 成功回调 OK 信封 ──────────────────────────────────────────────
test('场景 a: 成功回调 OK 信封 → 返回 data 原值，不清 storage、不改 href', () => {
  const { storage, window: win } = setupEnv({
    pathname: '/overview',
    href: '/overview',
    storage: { [TOKEN_KEY]: 'token-ok', [USER_KEY]: '{"username":"analyst"}' }
  })
  try {
    const payload = { id: 100, name: '运营大盘数据' }
    const resp = {
      data: {
        code: 'OK',
        message: '成功',
        data: payload
      }
    }
    const result = handleApiResponse(resp)
    assert.deepEqual(result, payload, 'OK 信封必须解包并返回 body.data 原值')
    assert.equal(storage.getItem(TOKEN_KEY), 'token-ok', 'OK 信封不得清理 token')
    assert.equal(storage.getItem(USER_KEY), '{"username":"analyst"}', 'OK 信封不得清理 user')
    assert.equal(win.location.href, '/overview', 'OK 信封不得修改 href')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 b: 成功回调 ACCEPTED 信封 ────────────────────────────────────────
test('场景 b: 成功回调 ACCEPTED 信封 → 返回 data 原值，不清 storage、不改 href', () => {
  const { storage, window: win } = setupEnv({
    pathname: '/pipeline',
    href: '/pipeline',
    storage: { [TOKEN_KEY]: 'token-accepted', [USER_KEY]: '{"username":"engineer"}' }
  })
  try {
    const payload = { runId: 'run-pipe-2026', status: 'SUBMITTED' }
    const resp = {
      data: {
        code: 'ACCEPTED',
        message: '流水线任务已受理',
        data: payload
      }
    }
    const result = handleApiResponse(resp)
    assert.deepEqual(result, payload, 'ACCEPTED 信封必须解包并返回 body.data 原值')
    assert.equal(storage.getItem(TOKEN_KEY), 'token-accepted', 'ACCEPTED 信封不得清理 token')
    assert.equal(storage.getItem(USER_KEY), '{"username":"engineer"}', 'ACCEPTED 信封不得清理 user')
    assert.equal(win.location.href, '/pipeline', 'ACCEPTED 信封不得修改 href')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 c: 成功回调 UNAUTHORIZED 信封 + 非 /login 路径 ───────────────────
test('场景 c: 成功回调 UNAUTHORIZED 信封 + 当前路径 /overview → reject 错误，两键被清，href 变为 /login', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/overview',
    href: '/overview',
    storage: { [TOKEN_KEY]: 'stale-token', [USER_KEY]: '{"username":"expired"}' }
  })
  try {
    const resp = {
      data: {
        code: 'UNAUTHORIZED',
        message: '登录状态已失效，请重新登录'
      }
    }
    await assert.rejects(
      async () => handleApiResponse(resp),
      (err) => {
        assert.ok(err instanceof Error, '必须 reject 一个 Error 实例')
        assert.equal(err.message, '登录状态已失效，请重新登录', 'reject 的 Error message 必须取 body.message')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null, 'analytics_token 必须被清除')
    assert.equal(storage.getItem(USER_KEY), null, 'analytics_user 必须被清除')
    assert.equal(win.location.href, '/login', '当前处于非 /login 路径时必须跳转至 /login')
  } finally {
    restoreGlobals()
  }
})

test('场景 c 补充: 成功回调 UNAUTHORIZED 信封无 message 时兜底文案为「未授权」', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/overview',
    href: '/overview',
    storage: { [TOKEN_KEY]: 'stale-token-2', [USER_KEY]: '{"id":2}' }
  })
  try {
    const resp = {
      data: {
        code: 'UNAUTHORIZED'
      }
    }
    await assert.rejects(
      async () => handleApiResponse(resp),
      (err) => {
        assert.ok(err instanceof Error)
        assert.equal(err.message, '未授权', 'body.message 为空时必须兜底为「未授权」')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null)
    assert.equal(storage.getItem(USER_KEY), null)
    assert.equal(win.location.href, '/login')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 d: 成功回调 UNAUTHORIZED 信封 + /login 路径（防循环） ─────────────
test('场景 d: 成功回调 UNAUTHORIZED 信封 + 当前路径 /login → reject 错误，两键被清，href 不变（防循环）', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/login',
    href: '/login',
    storage: { [TOKEN_KEY]: 'token-login-page', [USER_KEY]: '{"username":"login_user"}' }
  })
  try {
    const resp = {
      data: {
        code: 'UNAUTHORIZED',
        message: '凭证无效'
      }
    }
    await assert.rejects(
      async () => handleApiResponse(resp),
      (err) => {
        assert.ok(err instanceof Error)
        assert.equal(err.message, '凭证无效')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null, 'analytics_token 必须被清除')
    assert.equal(storage.getItem(USER_KEY), null, 'analytics_user 必须被清除')
    assert.equal(win.location.href, '/login', '处于 /login 路径时不重复跳转（防循环）')
  } finally {
    restoreGlobals()
  }
})

test('场景 d 补充: 成功回调 UNAUTHORIZED 信封 + 当前路径以 /login 开头（如带 query）→ href 不被重写', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/login',
    href: '/login?redirect=%2Foverview',
    storage: { [TOKEN_KEY]: 'token-with-query', [USER_KEY]: '{"id":3}' }
  })
  try {
    const resp = {
      data: {
        code: 'UNAUTHORIZED',
        message: '凭证无效'
      }
    }
    await assert.rejects(async () => handleApiResponse(resp))
    assert.equal(storage.getItem(TOKEN_KEY), null)
    assert.equal(storage.getItem(USER_KEY), null)
    assert.equal(win.location.href, '/login?redirect=%2Foverview', '带有 redirect 参数的 /login 路径不被覆盖重置')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 e: 成功回调其他 code ─────────────────────────────────────────────
test('场景 e: 成功回调其他 code（如 BUSINESS_ERROR）→ reject 且 message 透传，不清 storage、不改 href', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/sales',
    href: '/sales',
    storage: { [TOKEN_KEY]: 'valid-sales-token', [USER_KEY]: '{"role":"analyst"}' }
  })
  try {
    const resp = {
      data: {
        code: 'BUSINESS_ERROR',
        message: '销售指标聚合计算异常'
      }
    }
    await assert.rejects(
      async () => handleApiResponse(resp),
      (err) => {
        assert.ok(err instanceof Error)
        assert.equal(err.message, '销售指标聚合计算异常', '其他 code 必须透传 body.message')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), 'valid-sales-token', '非 UNAUTHORIZED 状态码不得清除 token')
    assert.equal(storage.getItem(USER_KEY), '{"role":"analyst"}', '非 UNAUTHORIZED 状态码不得清除 user')
    assert.equal(win.location.href, '/sales', '非 UNAUTHORIZED 状态码不得修改 href')
  } finally {
    restoreGlobals()
  }
})

test('场景 e 补充: 成功回调其他未知 code 且无 message 时兜底文案为「请求失败」', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/sales',
    href: '/sales',
    storage: { [TOKEN_KEY]: 'sales-tok', [USER_KEY]: '{"u":4}' }
  })
  try {
    const resp = {
      data: {
        code: 'UNKNOWN_CODE'
      }
    }
    await assert.rejects(
      async () => handleApiResponse(resp),
      (err) => {
        assert.ok(err instanceof Error)
        assert.equal(err.message, '请求失败', '其他 code 无 message 时必须兜底为「请求失败」')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), 'sales-tok')
    assert.equal(storage.getItem(USER_KEY), '{"u":4}')
    assert.equal(win.location.href, '/sales')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 f: 失败回调 err.response.status=401 + 当前路径 /sales ─────────────
test('场景 f: 失败回调 err.response.status=401 + 当前路径 /sales → reject 原 err，两键被清，href 变为 /login', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/sales',
    href: '/sales',
    storage: { [TOKEN_KEY]: 'active-token', [USER_KEY]: '{"username":"sales_mgr"}' }
  })
  try {
    const originalErr = new Error('Request failed with status code 401')
    originalErr.response = {
      status: 401,
      data: { code: 'UNAUTHORIZED', message: '未授权' }
    }
    await assert.rejects(
      async () => handleApiError(originalErr),
      (err) => {
        assert.equal(err, originalErr, '失败回调必须 reject 原 err 对象（引用一致）')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null, 'analytics_token 必须被清除')
    assert.equal(storage.getItem(USER_KEY), null, 'analytics_user 必须被清除')
    assert.equal(win.location.href, '/login', '非 /login 路径必须重定向至 /login')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 g: 失败回调 err.response.status=200 但 data.code='UNAUTHORIZED' ──
test('场景 g: 失败回调 err.response.status=200 但 data.code=\'UNAUTHORIZED\' + 当前路径 /ops → 清键并跳 /login（body 口径触发）', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/ops',
    href: '/ops',
    storage: { [TOKEN_KEY]: 'ops-token', [USER_KEY]: '{"username":"ops_admin"}' }
  })
  try {
    const originalErr = new Error('Custom Business Rejection')
    originalErr.response = {
      status: 200,
      data: { code: 'UNAUTHORIZED', message: '当前凭据已被吊销' }
    }
    await assert.rejects(
      async () => handleApiError(originalErr),
      (err) => {
        assert.equal(err, originalErr, '失败回调必须 reject 原 err 对象')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null, 'body 口径触发时 analytics_token 必须被清除')
    assert.equal(storage.getItem(USER_KEY), null, 'body 口径触发时 analytics_user 必须被清除')
    assert.equal(win.location.href, '/login', 'body 口径触发时必须跳转至 /login')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 h: 失败回调 err.response.status=500 ──────────────────────────────
test('场景 h: 失败回调 err.response.status=500 → reject 原 err，不清 storage、不改 href', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/ops',
    href: '/ops',
    storage: { [TOKEN_KEY]: 'ops-token-keep', [USER_KEY]: '{"username":"ops_admin"}' }
  })
  try {
    const originalErr = new Error('Internal Server Error')
    originalErr.response = {
      status: 500,
      data: { code: 'INTERNAL_ERROR', message: '数据库连接超时' }
    }
    await assert.rejects(
      async () => handleApiError(originalErr),
      (err) => {
        assert.equal(err, originalErr, '失败回调必须 reject 原 err 对象')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), 'ops-token-keep', '非 401/UNAUTHORIZED 错误不得清除 token')
    assert.equal(storage.getItem(USER_KEY), '{"username":"ops_admin"}', '非 401/UNAUTHORIZED 错误不得清除 user')
    assert.equal(win.location.href, '/ops', '非 401/UNAUTHORIZED 错误不得更改 href')
  } finally {
    restoreGlobals()
  }
})

test('场景 h 补充: 失败回调无 response（网络故障/断网/请求超时）→ reject 原 err，不清 storage、不改 href', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/reports',
    href: '/reports',
    storage: { [TOKEN_KEY]: 'rep-token-keep', [USER_KEY]: '{"username":"reporter"}' }
  })
  try {
    const networkErr = new Error('Network Error')
    // 无 response 属性
    await assert.rejects(
      async () => handleApiError(networkErr),
      (err) => {
        assert.equal(err, networkErr, '必须 reject 原 networkErr')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), 'rep-token-keep', '网络异常不得清理 token')
    assert.equal(storage.getItem(USER_KEY), '{"username":"reporter"}', '网络异常不得清理 user')
    assert.equal(win.location.href, '/reports', '网络异常不得修改 href')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 i: 失败回调 401 + 当前路径 /login ────────────────────────────────
test('场景 i: 失败回调 401 + 当前路径 /login → 两键被清，href 不变（防循环）', async () => {
  const { storage, window: win } = setupEnv({
    pathname: '/login',
    href: '/login',
    storage: { [TOKEN_KEY]: 'login-prev-token', [USER_KEY]: '{"username":"old_user"}' }
  })
  try {
    const originalErr = new Error('Unauthorized')
    originalErr.response = {
      status: 401,
      data: { code: 'UNAUTHORIZED', message: '密码错误' }
    }
    await assert.rejects(
      async () => handleApiError(originalErr),
      (err) => {
        assert.equal(err, originalErr, '必须 reject 原 err 对象')
        return true
      }
    )
    assert.equal(storage.getItem(TOKEN_KEY), null, '登录页 401 必须清理残留 token')
    assert.equal(storage.getItem(USER_KEY), null, '登录页 401 必须清理残留 user')
    assert.equal(win.location.href, '/login', '处于 /login 路径时 href 保持不变，防循环重定向')
  } finally {
    restoreGlobals()
  }
})

// ── 必测场景 j: redirectToLogin 直测 ──────────────────────────────────────────
test('场景 j: redirectToLogin 直测：非 /login 路径清键+跳转；/login 路径仅清键', () => {
  // j1: 非 /login 路径（如 /decisions）
  const env1 = setupEnv({
    pathname: '/decisions',
    href: '/decisions',
    storage: { [TOKEN_KEY]: 'token-dec', [USER_KEY]: '{"u":"dec"}', other_app_key: 'preserve' }
  })
  try {
    redirectToLogin()
    assert.equal(env1.storage.getItem(TOKEN_KEY), null, '非 /login 路径必须清除 token')
    assert.equal(env1.storage.getItem(USER_KEY), null, '非 /login 路径必须清除 user')
    assert.equal(env1.storage.getItem('other_app_key'), 'preserve', 'clearAuth 仅清理自身两键，其余键不受影响')
    assert.equal(env1.window.location.href, '/login', '非 /login 路径必须跳转至 /login')
  } finally {
    restoreGlobals()
  }

  // j2: /login 路径
  const env2 = setupEnv({
    pathname: '/login',
    href: '/login',
    storage: { [TOKEN_KEY]: 'token-login', [USER_KEY]: '{"u":"login"}' }
  })
  try {
    redirectToLogin()
    assert.equal(env2.storage.getItem(TOKEN_KEY), null, '/login 路径必须清除 token')
    assert.equal(env2.storage.getItem(USER_KEY), null, '/login 路径必须清除 user')
    assert.equal(env2.window.location.href, '/login', '/login 路径 href 不变（防循环）')
  } finally {
    restoreGlobals()
  }

  // j3: /login 带 query 路径（如 /login?redirect=%2Foverview）
  const env3 = setupEnv({
    pathname: '/login',
    href: '/login?redirect=%2Foverview',
    storage: { [TOKEN_KEY]: 'token-query', [USER_KEY]: '{"u":"query"}' }
  })
  try {
    redirectToLogin()
    assert.equal(env3.storage.getItem(TOKEN_KEY), null, '带 query 的 /login 路径必须清除 token')
    assert.equal(env3.storage.getItem(USER_KEY), null, '带 query 的 /login 路径必须清除 user')
    assert.equal(env3.window.location.href, '/login?redirect=%2Foverview', 'href 保持不变，不被重写')
  } finally {
    restoreGlobals()
  }
})

// ── 接线守护：源码断言与导出形态动态断言 ────────────────────────────────────────
test('接线守护：api.js 注册具名拦截器回调且默认导出保留既有方法', () => {
  // 源码断言：注册行使用具名 export 回调
  assert.match(
    API_SRC,
    /client\.interceptors\.response\.use\(\s*handleApiResponse\s*,\s*handleApiError\s*\)/,
    'api.js 必须使用 client.interceptors.response.use(handleApiResponse, handleApiError) 注册'
  )

  // 源码断言：默认导出包含 login、logout、me 等核心方法
  assert.match(API_SRC, /login\s*:\s*async\s*\(/, 'api.js 默认导出必须包含 login 方法')
  assert.match(API_SRC, /logout\s*:\s*async\s*\(/, 'api.js 默认导出必须包含 logout 方法')
  assert.match(API_SRC, /me\s*:\s*\(\)\s*=>/, 'api.js 默认导出必须包含 me 方法')

  // 动态对象断言：默认导出 api 对象的方法存在性及类型检查（确保未被意外破坏）
  assert.equal(typeof api.login, 'function', 'api.login 必须是函数')
  assert.equal(typeof api.logout, 'function', 'api.logout 必须是函数')
  assert.equal(typeof api.me, 'function', 'api.me 必须是函数')
  assert.equal(typeof api.post, 'function', 'api.post 必须是函数')
  assert.equal(typeof api.get, 'function', 'api.get 必须是函数')
  assert.equal(typeof api.overview, 'function', 'api.overview 必须是函数')
  assert.equal(typeof api.sales, 'function', 'api.sales 必须是函数')
  assert.equal(typeof api.products, 'function', 'api.products 必须是函数')
  assert.equal(typeof api.pipelineRuns, 'function', 'api.pipelineRuns 必须是函数')
  assert.equal(typeof api.runtimeProfiles, 'function', 'api.runtimeProfiles 必须是函数')
})
