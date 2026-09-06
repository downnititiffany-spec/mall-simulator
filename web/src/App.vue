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
    return JSON.parse(localStorage.getItem('mall_user') || 'null')
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
const ADMIN_ONLY_PATHS = ['/pipeline', '/ops', '/admin-products']
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
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f5f7fa; }
.layout { display: flex; min-height: 100vh; }
.sidebar {
  width: 220px; background: #1f2937; color: #e5e7eb; display: flex; flex-direction: column; flex-shrink: 0;
}
.logo { padding: 20px 16px; font-weight: 700; font-size: 15px; color: #fff; letter-spacing: 1px; }
nav { flex: 1; padding: 8px 0; }
.nav-item {
  display: block; padding: 10px 20px; color: #cbd5e1; text-decoration: none; font-size: 14px;
  border-left: 3px solid transparent;
}
.nav-item:hover { background: #374151; color: #fff; }
.nav-item.router-link-active { background: #374151; color: #fff; border-left-color: #3b82f6; }
.sidebar-foot { padding: 14px 16px; font-size: 12px; color: #6b7280; }
.user-info {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  border-top: 1px solid #374151; padding-top: 12px; margin-bottom: 10px;
}
.user-line { color: #cbd5e1; line-height: 1.4; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.logout-btn {
  flex-shrink: 0; border: 1px solid #4b5563; background: transparent; color: #cbd5e1;
  font-size: 12px; padding: 4px 10px; border-radius: 4px; cursor: pointer;
}
.logout-btn:hover { background: #374151; color: #fff; border-color: #6b7280; }
.version { color: #6b7280; }
.content { flex: 1; padding: 24px; min-width: 0; }
.page-title { font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #111827; }
.metric-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 18px; }
.metric-card { background: #fff; border-radius: 8px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
.metric-card .label { font-size: 12px; color: #6b7280; }
.metric-card .value { font-size: 22px; font-weight: 700; color: #111827; margin-top: 4px; }
.metric-card .unit { font-size: 12px; color: #9ca3af; margin-left: 2px; }
.chart-box { background: #fff; border-radius: 8px; padding: 14px; box-shadow: 0 1px 3px rgba(0,0,0,.08); margin-bottom: 16px; }
.chart-title { font-size: 14px; font-weight: 600; margin-bottom: 8px; color: #374151; }
.table-box { background: #fff; border-radius: 8px; padding: 14px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
.el-empty { color: #9ca3af; font-size: 13px; padding: 20px; text-align: center; }
</style>