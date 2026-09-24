<template>
  <router-view v-if="isLoginPage" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">EB</div>
        <div class="brand-text">
          <div class="brand-name">电商用户行为分析</div>
          <div class="brand-sub">Analytics Platform</div>
        </div>
      </div>

      <nav class="sidebar-nav">
        <template v-for="g in groups" :key="g.title">
          <div class="nav-group-title">{{ g.title }}</div>
          <router-link v-for="r in g.items" :key="r.path" :to="r.path" class="nav-item">
            <span class="nav-ico" v-html="iconOf(r.path)"></span>
            {{ r.meta.title }}
          </router-link>
        </template>
      </nav>

      <div class="sidebar-foot">
        <div class="avatar">{{ avatarText }}</div>
        <div class="who">
          <b>{{ displayName }}</b>
          <span>{{ roleName || '未登录' }}</span>
        </div>
      </div>
    </aside>

    <div class="content">
      <header class="topbar">
        <div class="crumb">
          <span>{{ currentGroupTitle }}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 18l6-6-6-6" />
          </svg>
          <span class="cur">{{ currentTitle }}</span>
        </div>
        <div class="topbar-spacer"></div>
        <div class="topbar-meta">
          <span v-if="user" class="badge badge-brand">{{ roleName }}</span>
          <span class="muted">{{ versionText }}</span>
          <button class="btn btn-sm btn-ghost" @click="onLogout">退出登录</button>
        </div>
      </header>

      <main class="page-body">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from './api'

const router = useRouter()
const route = useRoute()

const ROLE_NAMES = { admin: '系统管理员', operator: '运营专员', analyst: '数据分析师' }
const VERSION_TEXT = 'V2.2 · 阶段7 看板'

// 导航图标：内联 SVG（24×24 / currentColor 描边），不引外网资源、不新增依赖
const ICON_PATHS = {
  '/overview': '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  '/behavior': '<path d="M3 17l5-6 4 3 4-7 5 4"/><path d="M3 21h18"/>',
  '/products': '<path d="M20 7l-8-4-8 4 8 4 8-4z"/><path d="M4 7v10l8 4 8-4V7"/><path d="M12 11v10"/>',
  '/rfm': '<circle cx="12" cy="12" r="9"/><path d="M12 3v9l6.5 4"/>',
  '/sales': '<path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/>',
  '/ai': '<path d="M12 3l1.9 4.6L18.5 9l-4.6 1.9L12 15.5l-1.9-4.6L5.5 9l4.6-1.4L12 3z"/><path d="M18 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8.8-2z"/>',
  '/decisions': '<path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/>',
  '/pipeline': '<rect x="3" y="4" width="6" height="6" rx="1.5"/><rect x="15" y="14" width="6" height="6" rx="1.5"/><path d="M9 7h4a2 2 0 012 2v7"/>',
  '/ops': '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-2.9 1.2V21a2 2 0 11-4 0v-.1A1.7 1.7 0 007 19.4a1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00-1.2-2.9H1a2 2 0 110-4h.1A1.7 1.7 0 002.6 7a1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H7a1.7 1.7 0 001-1.5V1a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9V7a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"/>',
  '/sources/wizard': '<path d="M12 3l2.2 5.3 5.8.5-4.4 3.8 1.3 5.7L12 15.2 7.1 18.3l1.3-5.7L4 8.8l5.8-.5L12 3z"/>'
}
const iconOf = (path) =>
  '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
  'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
  (ICON_PATHS[path] || '<circle cx="12" cy="12" r="3" fill="currentColor"/>') +
  '</svg>'

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
const displayName = computed(() => {
  if (!user.value) return '未登录'
  return user.value.realName || user.value.username || '—'
})
const avatarText = computed(() => (displayName.value || '—').slice(0, 1))
const versionText = VERSION_TEXT

// 导航按角色过滤：admin 可见全部页面，其余角色隐藏「数据流水线/运维中心/接入向导」
const allRoutes = router.options.routes.filter((r) => r.meta && r.meta.title && r.path !== '/login')
const ADMIN_ONLY_PATHS = ['/pipeline', '/ops', '/sources/wizard']
const visibleRoutes = computed(() => {
  const isAdmin = user.value && user.value.role === 'admin'
  return allRoutes.filter((r) => isAdmin || !ADMIN_ONLY_PATHS.includes(r.path))
})

// 分组：分析看板 / 智能与决策 / 系统治理（未列出的路由归入第一组）
const GROUP_DEFS = [
  { title: '分析看板', paths: ['/overview', '/behavior', '/products', '/rfm', '/sales'] },
  { title: '智能与决策', paths: ['/ai', '/decisions'] },
  { title: '系统治理', paths: ['/pipeline', '/ops', '/sources/wizard'] }
]
const groups = computed(() => {
  const listed = GROUP_DEFS.flatMap((g) => g.paths)
  return GROUP_DEFS
    .map((g) => ({
      title: g.title,
      items: g.paths
        .map((p) => visibleRoutes.value.find((r) => r.path === p))
        .filter(Boolean)
    }))
    .concat([{
      title: '其他',
      items: visibleRoutes.value.filter((r) => !listed.includes(r.path))
    }])
    .filter((g) => g.items.length > 0)
})

const currentTitle = computed(() => (route.meta && route.meta.title) || '')
const currentGroupTitle = computed(() => {
  const hit = groups.value.find((g) => g.items.some((i) => i.path === route.path))
  return hit ? hit.title : '分析看板'
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

<style scoped>
/* 图标占位：与文字基线对齐，避免 v-html 插入前后布局抖动 */
.nav-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  opacity: .85;
}
.nav-item:hover .nav-ico,
.nav-item.router-link-active .nav-ico { opacity: 1; }
</style>
