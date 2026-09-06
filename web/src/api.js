import axios from 'axios'

// API 客户端：统一处理 traceId / 错误码（§24.2 约定）
const client = axios.create({ baseURL: '/api/v1', timeout: 30000 })

// 登录态存储（localStorage 键名与后端约定一致）
const TOKEN_KEY = 'mall_token'
const USER_KEY = 'mall_user'

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
  post: (url, body) => client.post(url, body),
  get: (url, params) => client.get(url, { params }),
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
  // 大盘（§25.1 首页信息层级）
  overview: () => client.get('/dashboards/overview'),
  // 专题
  sales: (from, to) => client.get('/analysis/sales', { params: { from, to } }),
  products: (topN, from, to) => client.get('/analysis/products', { params: { topN, from, to } }),
  funnel: (date) => client.get('/analysis/funnel', { params: { date } }),
  users: (from, to) => client.get('/analysis/users', { params: { from, to } }),
  // 流水线
  createPipelineRun: (body, idempotencyKey) =>
    client.post('/pipeline-runs', body, { headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {} }),
  pipelineRuns: (limit = 10) => client.get('/pipeline-runs', { params: { limit } }),
  pipelineRun: (id) => client.get(`/pipeline-runs/${id}`),
  retryPipelineRun: (id) => client.post(`/pipeline-runs/${id}/retry`),
  // 指标/快照
  metricsOverview: (snapshotId) => client.get('/metrics/overview', { params: { snapshotId } }),
  snapshots: (limit = 10) => client.get('/metrics/snapshots', { params: { limit } }),
  // 生成器（演示控制台）
  generatorRun: (body) => client.post('/generator/runs', body),
  scenarios: () => client.get('/generator/scenarios'),
  // 采集
  ingestionRun: () => client.post('/ingestion/runs'),
  ingestionStatus: () => client.get('/ingestion/status'),
  // 商城（演示下单链路）
  mallOrders: () => client.get('/mall/orders')
}