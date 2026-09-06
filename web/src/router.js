import { createRouter, createWebHistory } from 'vue-router'

// 页面结构（§11.2）：固定看板为日常主入口，技术页面单独隔离
const routes = [
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: () => import('./views/Overview.vue'), meta: { title: '运营大盘' } },
  { path: '/behavior', name: 'behavior', component: () => import('./views/Behavior.vue'), meta: { title: '用户行为分析' } },
  { path: '/products', name: 'products', component: () => import('./views/Products.vue'), meta: { title: '商品分析' } },
  { path: '/sales', name: 'sales', component: () => import('./views/Sales.vue'), meta: { title: '销售分析' } },
  { path: '/pipeline', name: 'pipeline', component: () => import('./views/Pipeline.vue'), meta: { title: '数据流水线' } },
  { path: '/decisions', name: 'decisions', component: () => import('./views/Decisions.vue'), meta: { title: '决策中心' } },
  { path: '/ai', name: 'ai', component: () => import('./views/AiAssistant.vue'), meta: { title: '智能分析助手' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})