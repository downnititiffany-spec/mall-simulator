<template>
  <div class="analysis-context">
    <div class="meta-row">
      <span class="meta-item">快照 <b class="mono">{{ ctx.snapshotId || '无' }}</b></span>
      <span class="meta-item">业务时间 <b class="mono">{{ formatDateTime(ctx.businessTime) }}</b></span>
      <span class="meta-item">数据更新 <b class="mono">{{ formatDateTime(ctx.dataUpdatedAt) }}</b></span>
      <span class="meta-item">口径版本 <b class="mono">{{ ctx.definitionVersion || '未知' }}</b></span>
      <span class="meta-item">
        质量
        <b class="mono" :class="ctx.qualityStatus === 'PASS' ? 'ok' : (ctx.qualityStatus === 'FAIL' ? 'bad' : '')">
          {{ ctx.qualityStatus || 'UNKNOWN' }}
        </b>
        <span class="meta-hint">{{ qualityText(ctx.qualityStatus) }}</span>
      </span>
    </div>
    <div v-if="ctx.missingNotice" class="banner banner-missing">
      {{ ctx.missingNotice }}
    </div>
    <div v-if="warnings.length" class="banner banner-warn">
      数据告警：{{ warnings.map(warningTextAll).join('；') }}
    </div>
    <div v-if="state === 'stale'" class="banner banner-stale">
      数据更新中：筛选条件已变化，正在等待新数据返回，当前展示的仍是上一次结果，已禁止导出。
    </div>
    <div v-if="state === 'error'" class="banner banner-error">
      数据加载失败：{{ error || '未知原因' }}。页面保留的数据为上一次成功结果。
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatDateTime, qualityText } from '../utils/envelope'
import { warningTextAll } from '../utils/context'

const props = defineProps({
  context: { type: Object, default: () => ({}) },
  state: { type: String, default: 'loading' },
  error: { type: String, default: '' }
})

// 上下文可能仍是 null（首次加载尚未返回）：统一兜底为空对象，避免模板取字段抛错
const ctx = computed(() => props.context || {})
const warnings = computed(() => (Array.isArray(ctx.value.warnings) ? ctx.value.warnings : []))
</script>
