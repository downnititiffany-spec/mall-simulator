<template>
  <div class="mall-shell">
    <header v-if="showNav" class="mall-header">
      <div class="mall-brand">模拟商城（mall-simulator）</div>
      <nav class="mall-nav">
        <RouterLink to="/mall">商城演示</RouterLink>
        <RouterLink to="/admin-products">商品后台</RouterLink>
      </nav>
      <div class="mall-user">
        <span v-if="userName">{{ userName }}</span>
        <button class="btn" @click="logout">退出登录</button>
      </div>
    </header>
    <div class="mall-boundary">
      本页属<strong>模拟商城</strong>（事件来源系统，仅提供可重复测试数据）；
      分析平台看板在 <code>http://127.0.0.1:8091</code>，两者数据库、账号与前端完全独立。
    </div>
    <main class="mall-main">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import api from './api'

const router = useRouter()
const route = useRoute()
const showNav = computed(() => route.path !== '/login')
const userName = computed(() => {
  if (!showNav.value) return ''
  const u = api.currentUser()
  return (u && (u.displayName || u.username)) || ''
})

const logout = async () => {
  try {
    await api.logout()
  } finally {
    router.push('/login')
  }
}
</script>

<style scoped>
.mall-shell {
  min-height: 100vh;
}
.mall-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 24px;
  background: #1f2d3d;
  color: #fff;
}
.mall-brand {
  font-weight: 600;
}
.mall-nav {
  display: flex;
  gap: 16px;
  flex: 1;
}
.mall-nav a {
  color: #cbd5e1;
  text-decoration: none;
}
.mall-nav a.router-link-active {
  color: #fff;
  font-weight: 600;
}
.mall-user {
  display: flex;
  align-items: center;
  gap: 12px;
}
.mall-boundary {
  padding: 8px 24px;
  background: #fff7e6;
  border-bottom: 1px solid #ffe0a3;
  color: #7a4d00;
  font-size: 13px;
}
.mall-main {
  padding: 20px 24px 40px;
}
</style>
