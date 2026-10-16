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
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: var(--fs-sm, 13px);
  color: var(--text-secondary, #475467);
  background: var(--bg-surface, #ffffff);
  border: 1px dashed var(--border, #E4E7EC);
  border-radius: var(--radius-lg, 14px);
}
.state-error {
  color: var(--danger, #F04438);
  border-color: var(--danger-bd, #FECDCA);
  background: var(--danger-bg, #FEF3F2);
}
.state-empty {
  color: var(--text-tertiary, #98A2B3);
}
.spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--brand-100, #DBE5FF);
  border-top-color: var(--brand-600, #2E5BFF);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
