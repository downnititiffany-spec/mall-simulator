import { createRouter, createWebHistory } from 'vue-router'

// 模拟商城前端路由：只含商城侧页面（商城演示 / 商品后台 / 数据生成器 / 登录）
const routes = [
  { path: '/', redirect: '/mall' },
  { path: '/login', name: 'login', component: () => import('./views/Login.vue') },
  { path: '/mall', name: 'mall', component: () => import('./views/Mall.vue') },
  { path: '/admin-products', name: 'admin-products', component: () => import('./views/AdminProducts.vue') },
  { path: '/generator', name: 'generator', component: () => import('./views/Generator.vue') }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('mall_token')
  if (!token && to.path !== '/login') {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
