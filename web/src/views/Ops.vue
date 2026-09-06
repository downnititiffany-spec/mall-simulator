<template>
  <div>
    <div class="page-title">运维中心</div>

    <div class="chart-box">
      <div class="chart-title">快照版本（BUILDING→VERIFYING→ACTIVE / ARCHIVED / FAILED）
        <button style="float:right;font-size:12px;padding:3px 10px" @click="loadAll">刷新</button>
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">快照 ID</th><th>业务时间</th><th>状态</th><th>版本</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="s in snapshots" :key="s.snapshotId" style="border-top:1px solid #f3f4f6"
              :style="{ background: selected === s.snapshotId ? '#eff6ff' : '' }">
            <td style="padding:8px">{{ s.snapshotId }}</td>
            <td>{{ s.businessTime || '—' }}</td>
            <td><span :style="{ color: snapColor(s.status), fontWeight: 600 }">{{ s.status }}</span></td>
            <td>v{{ s.version }}</td>
            <td><button style="font-size:12px" @click="viewSnapshot(s.snapshotId)">查看指标</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="snapshotValues.length" style="margin-top:10px;font-size:12px;color:#374151">
        <b>快照 {{ selected }} 指标：</b>
        <span v-for="v in snapshotValues" :key="v.metricCode" style="margin-right:12px">
          {{ v.metricCode }} = <b>{{ v.value }}</b>
        </span>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">数据质量规则结果（金额对账失败会阻断发布）</div>
      <table v-if="quality.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:6px">流水线</th><th>指标</th><th>结果</th><th>信息</th><th>时间</th>
        </tr></thead>
        <tbody>
          <tr v-for="q in quality" :key="q.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px">{{ q.runId || '—' }}</td>
            <td>{{ q.ruleCode }}</td>
            <td><span :style="{ color: q.passed === 1 ? '#16a34a' : '#dc2626', fontWeight: 600 }">{{ q.passed === 1 ? '通过' : '失败' }}</span></td>
            <td>{{ q.detail || '' }}</td>
            <td>{{ (q.createdAt || '').toString().slice(0, 16) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无质量结果（跑一次流水线后出现）</div>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start">
      <div class="chart-box">
        <div class="chart-title">AI 问答审计（ai_query_history）</div>
        <table v-if="history.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用户</th><th>问题</th><th>状态</th><th>行数</th><th>时间</th>
          </tr></thead>
          <tbody>
            <tr v-for="h in history" :key="h.id" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ h.userId }}</td>
              <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ h.question }}</td>
              <td>{{ h.status }}</td>
              <td>{{ h.rowsReturned }}</td>
              <td>{{ (h.createdAt || '').toString().slice(0, 16) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无问答记录</div>
      </div>
      <div class="chart-box">
        <div class="chart-title">模型调用审计（ai_call_log）</div>
        <table v-if="calls.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用例</th><th>模型</th><th>tokens</th><th>耗时</th><th>状态</th>
          </tr></thead>
          <tbody>
            <tr v-for="c in calls" :key="c.id" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ c.useCase }}</td>
              <td>{{ c.provider }}/{{ c.model }}</td>
              <td>{{ c.inputTokens ?? 0 }}/{{ c.outputTokens ?? 0 }}</td>
              <td>{{ c.elapsedMs }}ms</td>
              <td>{{ c.status }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无调用记录</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const snapshots = ref([])
const quality = ref([])
const history = ref([])
const calls = ref([])
const selected = ref('')
const snapshotValues = ref([])

const snapColor = (s) => ({ ACTIVE: '#16a34a', BUILDING: '#d97706', VERIFYING: '#d97706', ARCHIVED: '#6b7280', FAILED: '#dc2626' }[s] || '#111827')

async function loadAll() {
  snapshots.value = await api.get('/metrics/snapshots', { limit: 15 }) || []
  quality.value = await api.get('/metrics/quality', { limit: 20 }) || []
  history.value = await api.get('/ai/audit/history', { limit: 10 }) || []
  calls.value = await api.get('/ai/audit/calls', { limit: 10 }) || []
}
async function viewSnapshot(id) {
  selected.value = id
  snapshotValues.value = await api.get('/metrics/overview', { snapshotId: id }) || []
}
onMounted(loadAll)
</script>