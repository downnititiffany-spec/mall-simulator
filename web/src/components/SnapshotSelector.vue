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
    <span v-if="selected && !snapshotSelection.explicitSnapshotId" class="snapshot-note">
      本页数据固定使用 {{ selected.snapshotId }}（{{ selected.definitionVersion || '口径版本未提供' }}）
    </span>
    <form class="explicit-snapshot" @submit.prevent="onExplicitSubmit">
      <label for="explicit-snapshot-id">按 ID 查看归档快照</label>
      <input id="explicit-snapshot-id" v-model="explicitSnapshotInput" type="text" maxlength="256"
             autocomplete="off" spellcheck="false" placeholder="输入精确 snapshotId"
             :disabled="snapshotSelection.loading" />
      <button class="retry" type="submit" :disabled="snapshotSelection.loading">查看</button>
      <button v-if="snapshotSelection.explicitSnapshotId" class="retry" type="button" @click="clearExplicit">
        返回当前档案快照
      </button>
    </form>
    <span v-if="snapshotSelection.explicitSnapshotId" class="snapshot-note" role="status">
      本页正按指定 ID {{ snapshotSelection.explicitSnapshotId }} 读取；服务端将校验权限和快照状态，失败时不会回退。
    </span>
    <span v-if="explicitSnapshotError" class="snapshot-warning" role="alert">{{ explicitSnapshotError }}</span>
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
import { computed, onMounted, ref, watch } from 'vue'
import {
  clearExplicitSnapshot,
  ensureSnapshotSelection,
  selectExplicitSnapshot,
  selectSnapshot,
  snapshotSelection
} from '../composables/useSnapshotSelection'

const selected = computed(() => snapshotSelection.options.find(
  (item) => item.snapshotId === snapshotSelection.selectedSnapshotId
) || null)
const explicitSnapshotInput = ref(snapshotSelection.explicitSnapshotId)
const explicitSnapshotError = ref('')

watch(() => snapshotSelection.explicitSnapshotId, (value) => {
  explicitSnapshotInput.value = value
})

function onChange(event) {
  try {
    selectSnapshot(event.target.value)
  } catch (error) {
    snapshotSelection.error = error.message || '无法切换所选快照'
  }
}

function onExplicitSubmit() {
  try {
    selectExplicitSnapshot(explicitSnapshotInput.value)
    explicitSnapshotError.value = ''
  } catch (error) {
    explicitSnapshotError.value = error.message || '无法选择指定快照'
  }
}

function clearExplicit() {
  clearExplicitSnapshot()
  explicitSnapshotInput.value = ''
  explicitSnapshotError.value = ''
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
  gap: 10px 14px;
  padding: 12px 18px;
  margin-bottom: 18px;
  border: 1px solid var(--border, #E4E7EC);
  border-radius: var(--radius-lg, 14px);
  background: var(--bg-surface, #ffffff);
  color: var(--text-secondary, #475467);
  font-size: var(--fs-sm, 13px);
  box-shadow: var(--shadow-sm);
}
.snapshot-toolbar label {
  font-weight: 600;
  color: var(--text-primary, #101828);
}
.snapshot-toolbar select {
  max-width: min(100%, 520px);
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border, #E4E7EC);
  border-radius: var(--radius, 8px);
  background: var(--bg-surface, #ffffff);
  color: var(--text-primary, #101828);
  font-size: var(--fs-base, 14px);
  transition: border-color var(--dur-fast, .12s) var(--ease, ease), box-shadow var(--dur-fast, .12s) var(--ease, ease);
}
.snapshot-toolbar select:focus {
  outline: none;
  border-color: var(--brand-500, #3B71F7);
  box-shadow: 0 0 0 3px rgba(46, 91, 255, .15);
}
.explicit-snapshot { display: contents; }
.explicit-snapshot input {
  width: min(100%, 250px);
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border, #E4E7EC);
  border-radius: var(--radius, 8px);
  background: var(--bg-surface, #ffffff);
  color: var(--text-primary, #101828);
  font-size: var(--fs-base, 14px);
  transition: border-color var(--dur-fast, .12s) var(--ease, ease), box-shadow var(--dur-fast, .12s) var(--ease, ease);
}
.explicit-snapshot input:focus {
  outline: none;
  border-color: var(--brand-500, #3B71F7);
  box-shadow: 0 0 0 3px rgba(46, 91, 255, .15);
}
.explicit-snapshot button.retry {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--border, #E4E7EC);
  border-radius: var(--radius, 8px);
  background: var(--bg-surface, #ffffff);
  color: var(--text-secondary, #475467);
  font-size: var(--fs-sm, 13px);
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: all var(--dur-fast, .12s) var(--ease, ease);
}
.explicit-snapshot button.retry:hover:not(:disabled) {
  border-color: var(--brand-300, #93B4FD);
  color: var(--brand-600, #2E5BFF);
  background: var(--brand-50, #EFF4FF);
}
.snapshot-note { color: var(--text-tertiary, #98A2B3); }
.snapshot-warning { color: var(--warning, #F79009); font-weight: 500; }
.retry {
  margin-left: 6px;
  color: var(--brand-600, #2E5BFF);
  cursor: pointer;
}
</style>
