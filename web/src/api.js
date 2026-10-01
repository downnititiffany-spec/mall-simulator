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
  // S3-35：options 可带 AbortSignal（与 snapshots/quality 同形），供 useAnalysis 的
  // 「切换/卸载取消在途请求」使用；不传 options 时请求与改前完全一致。
  pipelineRuns: (limit = 10, options) => client.get('/pipeline-runs', { params: { limit }, ...(options || {}) }),
  pipelineRunsPage: (params = {}, options) => client.get('/pipeline-runs/page', { params, ...(options || {}) }),
  pipelineRun: (id) => client.get(`/pipeline-runs/${id}`),
  retryPipelineRun: (id) => client.post(`/pipeline-runs/${id}/retry`),
  // 指标/快照（非统一信封接口：/metrics/overview 返回指标数组，/metrics/snapshots 返回快照数组）
  metricsOverview: (snapshotId, options) =>
    client.get('/metrics/overview', { params: snapshotId ? { snapshotId } : {}, ...(options || {}) }),
  snapshots: (limit = 10, options) => client.get('/metrics/snapshots', { params: { limit }, ...(options || {}) }),
  analyticsSourceOptions: (options) => client.get('/analytics/sources', { ...(options || {}) }),
  quality: (limit = 20, options) => client.get('/metrics/quality', { params: { limit }, ...(options || {}) }),
  // AI（问答 + 审计）：返回证据包结构，非统一信封。
  // QA-04：查询时间范围是**真正可选**——不传就不发送该字段。此前默认写死一个「最近 30 天」标签，
  // 等于前端替模型编一个时间口径，与 SQL 生成路径自行选择的真实日期互相矛盾。
  // 有效查询期一律以后端响应里的结构化 query.window 为准。
  aiQuery: (question, timeRange, options) => {
    const body = { question }
    const hasTimeRange = typeof timeRange === 'string' && timeRange.trim() !== ''
    if (hasTimeRange) body.timeRange = timeRange
    const requestOptions = { ...(options || {}) }
    if (typeof requestOptions.snapshotId === 'string' && requestOptions.snapshotId.trim()) {
      body.snapshotId = requestOptions.snapshotId.trim()
    }
    delete requestOptions.snapshotId
    return client.post('/ai/queries', body, requestOptions)
  },
  aiHistoryMine: (limit = 8, options) => client.get('/ai/history/my', { params: { limit }, ...(options || {}) }),
  aiAuditHistory: (limit = 20, options) => client.get('/ai/audit/history', { params: { limit }, ...(options || {}) }),
  aiAuditCalls: (limit = 20, options) => client.get('/ai/audit/calls', { params: { limit }, ...(options || {}) }),
  // 决策中心：返回决策任务/评价数组，非统一信封
  decisions: (limit = 20, options) => client.get('/decisions', { params: { limit }, ...(options || {}) }),
  decisionEvaluations: (id, options) => client.get(`/decisions/${id}/evaluations`, { ...(options || {}) }),
  decisionAction: (id, action, body = {}) => client.post(`/decisions/${id}/${action}`, body),
  // 决策创建（POST /decisions）：服务端把 source 固定为 ai、初始状态固定 DRAFT（r8 契约 §3.3/§5）
  decisionCreate: (body) => client.post('/decisions', body),
  // 用户管理（运维页，admin 专属）
  adminUsers: (options) => client.get('/admin/users', { ...(options || {}) }),
  adminCreateUser: (body) => client.post('/admin/users', body),
  adminUserAction: (id, action, body) => client.post(`/admin/users/${id}/${action}`, body),
  // 采集（分析平台侧采集触发；模拟商城生成器接口已迁出分析前端）
  ingestionRun: () => client.post('/ingestion/runs'),
  ingestionStatus: () => client.get('/ingestion/status'),
  // 接入向导（02.5）：源登记读取 + 受控激活面（RUNTIME_MANAGE，服务端审计）。
  // 路径占位符统一写 ${id}：跨树对账守卫按 `${x}` → `{x}` 原名归一后与 Java 冻结表逐字对账，
  // 而冻结表（ControllerPermissionCoverageTest.normalize）把一切占位符折叠成 {id}。
  // dry-run 报告回查 GET /sources/{id}/mappings/dry-runs/{reportId} 前端不接线（向导直接消费 dry-run 响应）。
  sources: (options) => client.get('/sources', { ...(options || {}) }),
  sourceActivate: (id) => client.post(`/sources/${id}/activate`),
  mappingDryRun: (id, body) => client.post(`/sources/${id}/mappings/dry-run`, body),
  mappingActivate: (id, body) => client.post(`/sources/${id}/mappings/activate`, body)
}
