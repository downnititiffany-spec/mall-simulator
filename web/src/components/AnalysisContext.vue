<template>
  <div class="analysis-context">
    <div class="meta-row">
      <span class="meta-item">快照 <b class="mono">{{ ctx.snapshotId || '无' }}</b></span>
      <span v-if="queryWindowText" class="meta-item">生效查询范围 <b class="mono">{{ queryWindowText }}</b></span>
      <span class="meta-item">
        业务来源 ID
        <b class="mono">{{ businessSourceIdText(ctx.sourceId) }}</b>
      </span>
      <span class="meta-item">业务时间 <b class="mono">{{ formatDateTime(ctx.businessTime) }}</b></span>
      <span class="meta-item">数据更新 <b class="mono">{{ formatDateTime(ctx.dataUpdatedAt) }}</b></span>
      <span class="meta-item">
        来源（发布方）
        <b class="mono">{{ sourceText(ctx.source) }}</b>
        <span class="meta-hint">非业务源身份</span>
      </span>
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
import { businessSourceIdText, formatDateTime, qualityText, sourceText } from '../utils/envelope'
import { effectiveWindowText, warningTextAll } from '../utils/context'

const props = defineProps({
  context: { type: Object, default: () => ({}) },
  state: { type: String, default: 'loading' },
  error: { type: String, default: '' }
})

// 上下文可能仍是 null（首次加载尚未返回）：统一兜底为空对象，避免模板取字段抛错
const ctx = computed(() => props.context || {})
const warnings = computed(() => (Array.isArray(ctx.value.warnings) ? ctx.value.warnings : []))
const queryWindowText = computed(() => effectiveWindowText(ctx.value.filters))
</script>

<style scoped>
.analysis-context {
  margin-bottom: var(--sp-4, 18px);
}
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px var(--sp-5, 24px);
  align-items: center;
  background: var(--bg-surface, #ffffff);
  border: 1px solid var(--border, #E4E7EC);
  border-radius: var(--radius-lg, 14px);
  padding: 12px 18px;
  font-size: var(--fs-sm, 13px);
  color: var(--text-secondary, #475467);
  box-shadow: var(--shadow-sm);
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.meta-item b.mono {
  color: var(--text-primary, #101828);
  font-weight: 600;
  font-family: var(--font-mono);
}
.meta-item b.ok { color: var(--success, #12B76A); }
.meta-item b.bad { color: var(--danger, #F04438); }
.meta-hint {
  font-size: var(--fs-xs, 12px);
  color: var(--text-tertiary, #98A2B3);
  margin-left: 2px;
}
.banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 16px;
  border-radius: var(--radius-md, 10px);
  font-size: var(--fs-base, 14px);
  border: 1px solid transparent;
}
.banner-warn    { background: var(--warning-bg, #FFFAEB); border-color: var(--warning-bd, #FEDF89); color: #B54708; }
.banner-stale   { background: var(--info-bg, #F0F9FF);    border-color: var(--info-bd, #B9E6FE);    color: #026AA2; }
.banner-error   { background: var(--danger-bg, #FEF3F2);  border-color: var(--danger-bd, #FECDCA);  color: #B42318; }
.banner-missing { background: var(--bg-subtle, #F2F4F7);  border-color: var(--border, #E4E7EC);     color: var(--text-secondary, #475467); }
</style>
