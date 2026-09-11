<template>
  <router-view v-if="isLoginPage" />
  <div v-else class="layout">
    <aside class="sidebar">
      <div class="logo">电商用户行为分析</div>
      <nav>
        <router-link v-for="r in nav" :key="r.path" :to="r.path" class="nav-item">
          {{ r.meta.title }}
        </router-link>
      </nav>
      <div class="sidebar-foot">
        <div v-if="user" class="user-info">
          <span class="user-line">{{ userLine }}</span>
          <button class="logout-btn" @click="onLogout">退出登录</button>
        </div>
        <div v-else class="user-info">
          <span class="user-line">未登录</span>
        </div>
        <div class="version">V2.2 · 阶段7 看板</div>
      </div>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from './api'

const router = useRouter()
const route = useRoute()

const ROLE_NAMES = { admin: '系统管理员', operator: '运营专员', analyst: '数据分析师' }

const readUser = () => {
  try {
    // 键名与分析前端 api.js 一致（analytics_user）；不读商城侧键名（§18.4 边界）
    return JSON.parse(localStorage.getItem('analytics_user') || 'null')
  } catch (e) {
    return null
  }
}

// 登录/登出后路由变化时重新读取登录态（含刷新页面场景）
const user = ref(null)
watch(
  () => route.path,
  () => { user.value = readUser() },
  { immediate: true }
)

// 登录页为独立全屏布局，不展示侧边栏
const isLoginPage = computed(() => route.path === '/login')

const roleName = computed(() =>
  (user.value && (ROLE_NAMES[user.value.role] || user.value.role)) || ''
)
const userLine = computed(() => {
  if (!user.value) return ''
  const { username, realName } = user.value
  const name = realName || username
  return `${roleName.value} ${name}${realName ? '（' + username + '）' : ''}`
})

// 导航按角色过滤：admin 可见全部页面，其余角色隐藏「数据流水线/运维中心」
const allRoutes = router.options.routes.filter((r) => r.meta && r.meta.title && r.path !== '/login')
const ADMIN_ONLY_PATHS = ['/pipeline', '/ops']
const nav = computed(() => {
  const isAdmin = user.value && user.value.role === 'admin'
  return allRoutes.filter((r) => isAdmin || !ADMIN_ONLY_PATHS.includes(r.path))
})

const onLogout = async () => {
  try {
    await api.logout()
  } catch (e) {
    // 登出接口失败（如令牌已失效）不影响本地清理，照常退出
  }
  user.value = null
  router.push('/login')
}
</script>

<style>
/* 全局主题由 styles/theme.css 提供（设计令牌/壳/卡片/表格/控件）。 */
</style>