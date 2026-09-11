<template>
  <div>
    <div v-if="state === 'loading'" class="state-overlay">
      <span class="spinner"></span>数据加载中
    </div>
    <div v-else-if="state === 'error'" class="state-overlay state-error">
      数据加载失败：{{ error || '未知原因' }}
    </div>
    <div v-else-if="state === 'empty'" class="state-overlay state-empty">
      {{ emptyText }}
    </div>
    <template v-else>
      <BaseChart :option="option" :height="height" />
    </template>
  </div>
</template>

<script setup>
import BaseChart from './BaseChart.vue'

// 四态图表壳：loading / empty / error 时用占位替换图表；stale 时照旧渲染旧数据，
// 由页面上方的上下文条显示“数据更新中”（契约 §4）。
defineProps({
  option: { type: Object, required: true },
  state: { type: String, default: 'ready' },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '暂无数据' },
  height: { type: Number, default: 300 }
})
</script>

<style scoped>
.state-overlay {
  height: 100%;
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-muted-foreground);
  background: var(--color-card);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius);
}
.state-error { color: var(--color-destructive); border-color: #FECACA; }
.state-empty { color: #94A3B8; }
.spinner {
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid var(--color-border); border-top-color: var(--color-primary);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
