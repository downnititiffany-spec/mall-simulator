import { createRouter, createWebHistory } from 'vue-router'

// 页面结构（§11.2）：固定看板为日常主入口，技术页面单独隔离；/login 为登录入口
const routes = [
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { title: '登录' } },
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: () => import('./views/Overview.vue'), meta: { title: '运营大盘', requiresAuth: true } },
  { path: '/behavior', name: 'behavior', component: () => import('./views/Behavior.vue'), meta: { title: '用户行为分析', requiresAuth: true } },
  { path: '/products', name: 'products', component: () => import('./views/Products.vue'), meta: { title: '商品分析', requiresAuth: true } },
  { path: '/rfm', name: 'rfm', component: () => import('./views/Rfm.vue'), meta: { title: '用户分层', requiresAuth: true } },
  { path: '/sales', name: 'sales', component: () => import('./views/Sales.vue'), meta: { title: '销售分析', requiresAuth: true } },
  { path: '/pipeline', name: 'pipeline', component: () => import('./views/Pipeline.vue'), meta: { title: '数据流水线', requiresAuth: true } },
  { path: '/ops', name: 'ops', component: () => import('./views/Ops.vue'), meta: { title: '运维中心', requiresAuth: true } },
  { path: '/decisions', name: 'decisions', component: () => import('./views/Decisions.vue'), meta: { title: '决策中心', requiresAuth: true } },
  { path: '/ai', name: 'ai', component: () => import('./views/AiAssistant.vue'), meta: { title: '智能分析助手', requiresAuth: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫：未登录访问受保护页面 → /login；已登录访问 /login → /
// 令牌键名与 api.js 一致（analytics_token）；分析平台不读写商城侧键名（§18.4 边界）
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('analytics_token')
  if (to.path === '/login' && token) {
    next('/')
    return
  }
  if (to.path !== '/login' && to.meta.requiresAuth !== false && !token) {
    next('/login')
    return
  }
  next()
})

export default router