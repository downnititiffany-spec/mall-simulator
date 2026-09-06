<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">电商用户行为分析</div>
      <nav>
        <router-link v-for="r in nav" :key="r.path" :to="r.path" class="nav-item">
          {{ r.meta.title }}
        </router-link>
      </nav>
      <div class="sidebar-foot">V2.2 · 阶段7 看板</div>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
const router = useRouter()
const nav = router.options.routes.filter((r) => r.meta && r.meta.title)
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