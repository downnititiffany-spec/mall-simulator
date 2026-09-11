import axios from 'axios'

// 模拟商城 API 客户端：只访问 mall-simulator（8090）自身接口。
// 与分析平台无关的接口（指标库/ADS/流水线/AI）不在这里出现——边界见指导书 V2.0 §18.4。
const client = axios.create({ baseURL: '/api/v1', timeout: 120000 })

const TOKEN_KEY = 'mall_token'
const USER_KEY = 'mall_user'

const clearAuth = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

const redirectToLogin = () => {
  clearAuth()
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

client.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY) || ''
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
    if (body && (body.code === 'OK' || body.code === 'ACCEPTED')) {
      return body.data
    }
    if (body && body.code === 'UNAUTHORIZED') {
      redirectToLogin()
      return Promise.reject(new Error((body && body.message) || '未授权'))
    }
    return Promise.reject(new Error((body && body.message) || '请求失败'))
  },
  (err) => {
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
  // 认证（商城自有账号，与分析平台账号相互独立）
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
  currentUser: () => {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    } catch (e) {
      return null
    }
  },
  // 商城演示（§5.2.2：全部走商城业务 Service）
  products: (categoryId) => client.get('/mall/products', { params: { categoryId } }),
  createUser: (body) => client.post('/mall/users', body),
  cartItems: (userId) => client.get('/mall/cart/items', { params: { userId } }),
  addCartItem: (body) => client.post('/mall/cart/items', body),
  createOrder: (body) => client.post('/mall/orders', body),
  orders: (userId) => client.get('/mall/orders', { params: { userId } }),
  payOrder: (orderId, userId) => client.post(`/mall/orders/${orderId}/pay`, { userId }),
  cancelOrder: (orderId, userId, reason) => client.post(`/mall/orders/${orderId}/cancel`, { userId, reason }),
  requestRefund: (orderId, userId) => client.post(`/mall/orders/${orderId}/refunds`, { userId }),
  completeRefund: (refundId, userId) => client.post(`/mall/refunds/${refundId}/complete`, { userId }),
  // 商品后台
  adminProducts: () => client.get('/admin/products'),
  createProduct: (body) => client.post('/admin/products', body),
  changePrice: (productId, price) => client.post(`/admin/products/${productId}/price`, { price }),
  changeStock: (productId, quantity, changeType) =>
    client.post(`/admin/products/${productId}/stock`, { quantity, changeType }),
  changeStatus: (productId, status) => client.post(`/admin/products/${productId}/status`, { status }),
  // 数据生成器（事件生成属于模拟商城侧；已从分析平台前端迁出）
  scenarios: () => client.get('/generator/scenarios'),
  generatorRun: (body) => client.post('/generator/runs', body)
}
