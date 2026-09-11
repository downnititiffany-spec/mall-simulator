import { createRouter, createWebHistory } from 'vue-router'

// 模拟商城前端路由：只含商城侧页面（商城演示 / 商品后台 / 登录）
// M1-7（三程序边界）：数据生成器页面随生成器整体移交 synthetic-data-generator（8092），本前端不再有该路由。
const routes = [
  { path: '/', redirect: '/mall' },
  { path: '/login', name: 'login', component: () => import('./views/Login.vue') },
  { path: '/mall', name: 'mall', component: () => import('./views/Mall.vue') },
  { path: '/admin-products', name: 'admin-products', component: () => import('./views/AdminProducts.vue') }
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
