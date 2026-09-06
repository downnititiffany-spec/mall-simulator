import axios from 'axios'

// API 客户端：统一处理 traceId / 错误码（§24.2 约定）
const client = axios.create({ baseURL: '/api/v1', timeout: 30000 })

client.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 'OK') {
      return body.data
    }
    if (body && body.code === 'ACCEPTED') {
      return body.data
    }
    return Promise.reject(new Error((body && body.message) || '请求失败'))
  },
  (err) => Promise.reject(err)
)

export default {
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