<template>
  <div>
    <div class="page-title">用户分层（RFM）
      <button style="float:right;font-size:12px;padding:4px 12px" :disabled="!rows.length" @click="doExport">导出 CSV</button>
    </div>
    <div class="chart-box">
      <div class="chart-title">八类用户分布（R 最近购买 / F 频次 / M 金额，三分位五档评分 §21.6）</div>
      <BaseChart :option="barOption" :height="300" />
    </div>
    <div class="table-box">
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">用户ID</th><th>最近购买(天)</th><th>订单数</th><th>金额(元)</th>
          <th>R/F/M 分</th><th>八类标签</th><th>生命周期</th>
        </tr></thead>
        <tbody>
          <tr v-for="u in rows" :key="u.userId" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ u.userId }}</td>
            <td>{{ u.recency }}</td>
            <td>{{ u.frequency }}</td>
            <td>{{ Number(u.monetary).toFixed(2) }}</td>
            <td>{{ u.rScore }}/{{ u.fScore }}/{{ u.mScore }}</td>
            <td><b :style="{ color: labelColor(u.label) }">{{ u.label }}</b></td>
            <td>{{ u.lifecycle }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="7" class="el-empty">暂无分层数据（有支付订单后出现）</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import BaseChart from '../components/BaseChart.vue'
import { exportCSV } from '../utils/exportCsv'

const rows = ref([])
const distribution = ref({})

const LABELS = ['重要价值', '重要发展', '重要保持', '重要挽留', '一般价值', '一般发展', '一般保持', '一般挽留']
const COLORS = { '重要价值': '#16a34a', '重要发展': '#22c55e', '重要保持': '#84cc16', '重要挽留': '#d97706', '一般价值': '#3b82f6', '一般发展': '#60a5fa', '一般保持': '#a78bfa', '一般挽留': '#9ca3af' }
const labelColor = (l) => COLORS[l] || '#111827'

const barOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 60, right: 30, top: 20 },
  xAxis: { type: 'category', data: LABELS },
  yAxis: { type: 'value', name: '用户数' },
  series: [{
    name: '用户数', type: 'bar',
    data: LABELS.map((l) => distribution.value[l] || 0),
    itemStyle: { color: (p) => COLORS[LABELS[p.dataIndex]] || '#3b82f6' }
  }]
}))

onMounted(async () => {
  try {
    const r = await api.get('/analysis/rfm', { limit: 50 })
    rows.value = r.users || []
    distribution.value = r.distribution || {}
  } catch (e) { console.error(e) }
})

const doExport = () => {
  exportCSV('rfm-users.csv',
    ['用户ID', '最近购买(天)', '订单数', '金额(元)', 'R', 'F', 'M', '八类标签', '生命周期'],
    rows.value.map((u) => [u.userId, u.recency, u.frequency, Number(u.monetary).toFixed(2), u.rScore, u.fScore, u.mScore, u.label, u.lifecycle]))
}
</script>