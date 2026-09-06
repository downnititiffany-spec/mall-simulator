<template>
  <div>
    <div class="page-title">用户行为分析
      <button style="float:right;font-size:12px;padding:4px 12px" :disabled="!stages.length" @click="doExport">导出 CSV</button>
    </div>
    <div class="chart-box">
      <div class="chart-title">转化漏斗（宽松用户口径，去重用户）</div>
      <BaseChart :option="funnelOption" :height="300" />
    </div>
    <div class="chart-box">
      <div class="chart-title">行为类型分布（当日）</div>
      <BaseChart :option="typeOption" :height="260" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import BaseChart from '../components/BaseChart.vue'
import { exportCSV } from '../utils/exportCsv'

const stages = ref([])
const behaviorCounts = ref({ view: 0, favorite: 0, cart_add: 0, cart_remove: 0, search: 0 })

const STAGE_NAMES = { view: '浏览', intent: '意向(收藏/加购)', order: '创建订单', pay: '支付成功' }
const doExport = () => {
  exportCSV('behavior-funnel.csv',
    ['阶段', '用户数', '转化率'],
    stages.value.map((s) => [STAGE_NAMES[s.stage] || s.stage, s.users, s.rate === null || s.rate === undefined ? '' : (Number(s.rate) * 100).toFixed(1) + '%']))
}

const funnelOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'funnel', left: 60, top: 20, bottom: 20, width: '70%', minSize: '20%',
    label: { formatter: '{b}: {c} 人' },
    data: stages.value.map((s) => ({
      name: { view: '浏览', intent: '意向(收藏/加购)', order: '创建订单', pay: '支付成功' }[s.stage] || s.stage,
      value: s.users
    }))
  }]
}))

const typeOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: Object.keys(behaviorCounts.value) },
  yAxis: { type: 'value' },
  series: [{ name: '次数', type: 'bar', data: Object.values(behaviorCounts.value), itemStyle: { color: '#3b82f6' } }]
}))

onMounted(async () => {
  const today = new Date().toISOString().slice(0, 10)
  try {
    stages.value = await api.funnel(today)
  } catch (e) {
    // 无数据时多个空状态即可
  }
})
</script>