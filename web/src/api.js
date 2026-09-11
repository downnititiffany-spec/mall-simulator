import axios from 'axios'

// API 客户端：统一处理 traceId / 错误码（§24.2 约定）
const client = axios.create({ baseURL: '/api/v1', timeout: 30000 })

// 登录态存储（分析平台自有键名；商城前端用 mall_token/mall_user，两侧互不读取——
// 越界即由 web/tests/boundary.test.js 拦截，见指导书 V2.0 §18.4）
const TOKEN_KEY = 'analytics_token'
const USER_KEY = 'analytics_user'

const readToken = () => localStorage.getItem(TOKEN_KEY) || ''

const clearAuth = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

const redirectToLogin = () => {
  clearAuth()
  // 登录页自身的 401（如密码错误）不重复跳转，由页面展示错误提示
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

// 请求拦截器：携带令牌访问受保护接口
client.interceptors.request.use(
  (config) => {
    const token = readToken()
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (err) => Promise.reject(err)
)

client.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 'OK') {
      return body.data
    }
    if (body && body.code === 'ACCEPTED') {
      return body.data
    }
    if (body && body.code === 'UNAUTHORIZED') {
      redirectToLogin()
      return Promise.reject(new Error((body && body.message) || '未授权'))
    }
    return Promise.reject(new Error((body && body.message) || '请求失败'))
  },
  (err) => {
    // 令牌缺失/过期/无效：清理登录态并回到登录页
    const body = err && err.response && err.response.data
    if ((err.response && err.response.status === 401) || (body && body.code === 'UNAUTHORIZED')) {
      redirectToLogin()
    }
    return Promise.reject(err)
  }
)

export default {
  // 第三个参数可传 { signal }（AbortController）：新提问发起时取消上一次在途请求（§18.4）
  post: (url, body, options) => client.post(url, body, { ...(options || {}) }),
  get: (url, params, options) => client.get(url, { params, ...(options || {}) }),
  // 认证（登录 / 登出 / 当前用户）
  login: async (username, password) => {
    const data = await client.post('/auth/login', { username, password })
    localStorage.setItem(TOKEN_KEY, data.token)
    localStorage.setItem(USER_KEY, JSON.stringify(data.user))
    return data
  },
  logout: async () => {
    try {
      return await client.post('/auth/logout')
    } finally {
      clearAuth()
    }
  },
  me: () => client.get('/auth/me'),
  // 大盘（§25.1 首页信息层级）：响应 data 为统一信封，见契约 analysis-viewmodel-r7-4 §2
  overview: (params = {}, options) => client.get('/dashboards/overview', { params, ...(options || {}) }),
  // 专题分析（响应 data 均为统一信封）
  sales: (params = {}, options) => client.get('/analysis/sales', { params, ...(options || {}) }),
  products: (params = {}, options) => client.get('/analysis/products', { params, ...(options || {}) }),
  funnel: (params = {}, options) => client.get('/analysis/funnel', { params, ...(options || {}) }),
  users: (params = {}, options) => client.get('/analysis/users', { params, ...(options || {}) }),
  rfm: (params = {}, options) => client.get('/analysis/rfm', { params, ...(options || {}) }),
  // 流水线
  createPipelineRun: (body, idempotencyKey) =>
    client.post('/pipeline-runs', body, { headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {} }),
  pipelineRuns: (limit = 10) => client.get('/pipeline-runs', { params: { limit } }),
  pipelineRun: (id) => client.get(`/pipeline-runs/${id}`),
  retryPipelineRun: (id) => client.post(`/pipeline-runs/${id}/retry`),
  // 指标/快照（非统一信封接口：/metrics/overview 返回指标数组，/metrics/snapshots 返回快照数组）
  metricsOverview: (snapshotId, options) =>
    client.get('/metrics/overview', { params: snapshotId ? { snapshotId } : {}, ...(options || {}) }),
  snapshots: (limit = 10, options) => client.get('/metrics/snapshots', { params: { limit }, ...(options || {}) }),
  quality: (limit = 20, options) => client.get('/metrics/quality', { params: { limit }, ...(options || {}) }),
  // AI（问答 + 审计）：返回证据包结构，非统一信封
  aiQuery: (question, timeRange = '近30天', options) =>
    client.post('/ai/queries', { question, timeRange }, { ...(options || {}) }),
  aiHistoryMine: (limit = 8, options) => client.get('/ai/history/my', { params: { limit }, ...(options || {}) }),
  aiAuditHistory: (limit = 20, options) => client.get('/ai/audit/history', { params: { limit }, ...(options || {}) }),
  aiAuditCalls: (limit = 20, options) => client.get('/ai/audit/calls', { params: { limit }, ...(options || {}) }),
  // 决策中心：返回决策任务/评价数组，非统一信封
  decisions: (limit = 20, options) => client.get('/decisions', { params: { limit }, ...(options || {}) }),
  decisionEvaluations: (id, options) => client.get(`/decisions/${id}/evaluations`, { ...(options || {}) }),
  decisionAction: (id, action, body = {}) => client.post(`/decisions/${id}/${action}`, body),
  // 用户管理（运维页，admin 专属）
  adminUsers: (options) => client.get('/admin/users', { ...(options || {}) }),
  adminCreateUser: (body) => client.post('/admin/users', body),
  adminUserAction: (id, action, body) => client.post(`/admin/users/${id}/${action}`, body),
  // 采集（分析平台侧采集触发；模拟商城生成器接口已迁出分析前端）
  ingestionRun: () => client.post('/ingestion/runs'),
  ingestionStatus: () => client.get('/ingestion/status')
}