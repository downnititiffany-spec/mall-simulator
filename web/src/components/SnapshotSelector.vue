<template>
  <section class="snapshot-toolbar" aria-label="分析数据选择">
    <label for="analysis-snapshot">分析来源与业务日期</label>
    <select
      id="analysis-snapshot"
      :value="snapshotSelection.selectedSnapshotId"
      :disabled="snapshotSelection.loading || !snapshotSelection.options.length"
      @change="onChange"
    >
      <option v-if="snapshotSelection.loading" value="">正在读取可用数据…</option>
      <option v-else-if="!snapshotSelection.options.length" value="">暂无可选快照</option>
      <option v-for="choice in snapshotSelection.options" :key="choice.snapshotId" :value="choice.snapshotId">
        {{ choice.label }}
      </option>
    </select>
    <span v-if="selected" class="snapshot-note">
      本页数据固定使用 {{ selected.snapshotId }}（{{ selected.definitionVersion || '口径版本未提供' }}）
    </span>
    <span v-if="snapshotSelection.sourceLabelWarning" class="snapshot-warning">
      {{ snapshotSelection.sourceLabelWarning }}
    </span>
    <span v-else-if="snapshotSelection.error" class="snapshot-warning" role="alert">
      {{ snapshotSelection.error }}
      <button class="retry" type="button" @click="retry">重试</button>
    </span>
  </section>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import {
  ensureSnapshotSelection,
  selectSnapshot,
  snapshotSelection
} from '../composables/useSnapshotSelection'

const selected = computed(() => snapshotSelection.options.find(
  (item) => item.snapshotId === snapshotSelection.selectedSnapshotId
) || null)

function onChange(event) {
  try {
    selectSnapshot(event.target.value)
  } catch (error) {
    snapshotSelection.error = error.message || '无法切换所选快照'
  }
}

function retry() {
  void ensureSnapshotSelection(true).catch(() => {})
}

onMounted(() => {
  void ensureSnapshotSelection().catch(() => {})
})
</script>

<style scoped>
.snapshot-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 10px 12px;
  margin-bottom: 14px;
  border: 1px solid var(--gray-200, #e5e7eb);
  border-radius: 8px;
  background: var(--white, #fff);
  color: var(--gray-700, #374151);
  font-size: 13px;
}
.snapshot-toolbar label { font-weight: 600; }
.snapshot-toolbar select {
  max-width: min(100%, 520px);
  min-height: 34px;
  padding: 4px 8px;
  border: 1px solid var(--gray-300, #d1d5db);
  border-radius: 6px;
  background: #fff;
  color: inherit;
}
.snapshot-note { color: var(--gray-500, #6b7280); }
.snapshot-warning { color: #92400e; }
.retry { margin-left: 6px; text-decoration: underline; }
</style>
