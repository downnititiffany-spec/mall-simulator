<template>
  <div>
    <div class="page-title">运营大盘</div>
    <div class="chart-box" style="display:flex;gap:10px;align-items:center;padding:10px 14px">
      <label style="font-size:13px;color:#374151">趋势日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" @click="load">加载</button>
      <span style="font-size:12px;color:#9ca3af">指标卡来自最新 ACTIVE 快照</span>
    </div>
    <div v-if="!loading && Object.keys(metrics).length === 0" class="el-empty">
      暂无已发布指标快照。请先在「数据流水线」页运行一次流水线，或执行一键生成分析。
    </div>
    <template v-else>
      <div class="metric-cards">
        <div v-for="m in cardList" :key="m.code" class="metric-card">
          <div class="label">{{ m.name }}</div>
          <div class="value">{{ Number(m.val).toLocaleString() }}<span class="unit">{{ m.unit }}</span></div>
        </div>
      </div>
      <div class="chart-box">
        <div class="chart-title">近 7 日销售趋势（GMV）</div>
        <BaseChart :option="salesOption" :height="260" />
      </div>
      <div class="chart-box">
        <div class="chart-title">近 7 日活跃用户与行为量</div>
        <BaseChart :option="activeOption" :height="260" />
      </div>
      <div class="el-empty" v-if="snapshotId">数据快照：{{ snapshotId }}（数据更新时间见快照详情）</div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import BaseChart from '../components/BaseChart.vue'

const loading = ref(true)
const metrics = ref({})
const sales = ref([])
const active = ref([])
const snapshotId = ref(null)
const from = ref(new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10))
const to = ref(new Date().toISOString().slice(0, 10))

const METRIC_NAMES = { gmv: '销售额(GMV)', net_sale: '净销售额', avg_order_value: '客单价',
  paid_order_cnt: '支付订单数', pv: '浏览量', uv: '浏览用户', dau: '活跃用户', refund_rate: '退款率' }
const cardOrder = ['gmv', 'net_sale', 'avg_order_value', 'paid_order_cnt', 'pv', 'uv', 'dau', 'refund_rate']

const cardList = computed(() =>
  cardOrder.filter((c) => metrics.value[c]).map((c) => ({
    code: c, name: METRIC_NAMES[c] || c,
    val: metrics.value[c].value,
    unit: metrics.value[c].unit
  }))
)

const salesOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: sales.value.map((s) => s.date) },
  yAxis: { type: 'value' },
  series: [{ name: '销售额', type: 'line', smooth: true, areaStyle: {},
    data: sales.value.map((s) => Number(s.saleAmount)) }]
}))

const activeOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['DAU', '行为量'] },
  xAxis: { type: 'category', data: active.value.map((a) => a.date) },
  yAxis: { type: 'value' },
  series: [
    { name: 'DAU', type: 'line', data: active.value.map((a) => a.dau) },
    { name: '行为量', type: 'bar', data: active.value.map((a) => a.behaviorCount) }
  ]
}))

async function load() {
  loading.value = true
  try {
    const data = await api.get('/dashboards/overview', { from: from.value, to: to.value })
    metrics.value = data.snapshotMetrics || {}
    sales.value = data.salesTrend || []
    active.value = data.activeTrend || []
    snapshotId.value = data.snapshotId
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>