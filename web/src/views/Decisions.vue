<template>
  <div>
    <div class="page-title">决策中心</div>
    <div class="chart-box">
      <div class="chart-title">
        决策列表（AI 草稿 → 人工审核 → 执行 → 效果评价）
        <button style="float:right;font-size:12px;padding:3px 10px" @click="load">刷新</button>
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">编号</th><th>标题</th><th>目标指标</th><th>基线</th>
          <th>负责人</th><th>状态</th><th>效果</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="d in rows" :key="d.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ d.decisionNo }}</td>
            <td>{{ d.title }}</td>
            <td>{{ d.targetMetricCode || '—' }} <span v-if="d.targetDirection">({{ d.targetDirection }})</span></td>
            <td>{{ d.baselineValue ?? '—' }}</td>
            <td>{{ d.owner || '—' }}</td>
            <td>
              <span :style="{ color: statusColor(d.status), fontWeight: 600 }">{{ d.status }}</span>
            </td>
            <td>
              <span v-if="evaluations[d.id]">{{ evaluations[d.id].result }} ({{ rate(evaluations[d.id]) }})</span>
              <span v-else>—</span>
            </td>
            <td style="white-space:nowrap">
              <button v-if="d.status === 'DRAFT'" @click="act(d, 'submit')">提交审核</button>
              <button v-if="d.status === 'PENDING_REVIEW'" style="background:#16a34a" @click="approve(d)">批准</button>
              <button v-if="d.status === 'PENDING_REVIEW'" style="background:#dc2626" @click="act(d, 'reject')">驳回</button>
              <button v-if="d.status === 'APPROVED'" @click="act(d, 'start')">开始</button>
              <button v-if="d.status === 'IN_PROGRESS'" @click="act(d, 'complete')">完成</button>
              <button v-if="d.status === 'IN_PROGRESS'" style="background:#dc2626" @click="cancel(d)">取消</button>
              <button v-if="d.status === 'COMPLETED'" style="background:#7c3aed" @click="evaluate(d)">评价</button>
            </td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="8" class="el-empty">暂无决策</td></tr>
        </tbody>
      </table>
    </div>
    <div class="chart-box" style="font-size:13px;color:#6b7280;line-height:1.8">
      <b>口径说明：</b>AI 只能创建 DRAFT（§22.6）；从 PENDING_REVIEW 到 APPROVED 必须人工；
      批准时锁定当前快照基线与目标指标；效果 = (实际−基线)/|基线|，"越低越好"指标（退款率等）取反；
      评价结果仅为前后对比，非因果推断（§21.10）。
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const evaluations = ref({})

const statusColor = (s) => {
  const map = { EFFECTIVE: '#16a34a', PARTIAL: '#d97706', INEFFECTIVE: '#dc2626',
    INSUFFICIENT_DATA: '#6b7280', REJECTED: '#dc2626', CANCELLED: '#6b7280' }
  return map[s] || '#111827'
}
const rate = (e) => (e.improvementRate ? (Number(e.improvementRate) * 100).toFixed(1) + '%' : '')

async function load() {
  rows.value = await api.get('/decisions', { limit: 20 })
  for (const d of rows.value) {
    if (['EFFECTIVE', 'PARTIAL', 'INEFFECTIVE', 'INSUFFICIENT_DATA'].includes(d.status)) {
      const evals = await api.get(`/decisions/${d.id}/evaluations`)
      if (evals && evals.length) evaluations.value[d.id] = evals[0]
    }
  }
}
async function act(d, action) {
  await api.post(`/decisions/${d.id}/${action}`, {})
  await load()
}
async function approve(d) {
  const owner = prompt('负责人（如：运营-小李）', '运营-小李')
  if (!owner) return
  await api.post(`/decisions/${d.id}/approve`, { owner, dueDate: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10) })
  await load()
}
async function cancel(d) {
  const reason = prompt('取消原因', '策略调整')
  await api.post(`/decisions/${d.id}/cancel`, { reason: reason || '策略调整' })
  await load()
}
async function evaluate(d) {
  const evalResult = await api.post(`/decisions/${d.id}/evaluate`, {})
  alert(`评价结果：${evalResult.result}（改善率 ${rate(evalResult)}）`)
  await load()
}

onMounted(load)
</script>