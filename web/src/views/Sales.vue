<template>
  <div>
    <div class="page-title">销售分析
      <button style="float:right;font-size:12px;padding:4px 12px" :disabled="!rows.length" @click="doExport">导出 CSV</button>
    </div>
    <div class="chart-box" style="display:flex;gap:10px;align-items:center;padding:10px 14px">
      <label style="font-size:13px;color:#374151">日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" @click="load">加载</button>
    </div>
    <div class="chart-box">
      <div class="chart-title">销售额与支付订单数趋势</div>
      <BaseChart :option="salesOption" :height="280" />
    </div>
    <div class="table-box">
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">日期</th><th>订单数</th><th>销售额(元)</th><th>买家数</th>
        </tr></thead>
        <tbody>
          <tr v-for="s in rows" :key="s.date" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ s.date }}</td><td>{{ s.orderCount }}</td>
            <td>{{ Number(s.saleAmount).toFixed(2) }}</td><td>{{ s.buyerCount }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="4" class="el-empty">暂无销售数据</td></tr>
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
const from = ref(new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10))
const to = ref(new Date().toISOString().slice(0, 10))

const doExport = () => {
  exportCSV(`sales-trend-${from.value}_${to.value}.csv`,
    ['日期', '订单数', '销售额(元)', '买家数'],
    rows.value.map((s) => [s.date, s.orderCount, Number(s.saleAmount).toFixed(2), s.buyerCount]))
}

async function load() {
  try {
    rows.value = await api.sales(from.value, to.value)
  } catch (e) { console.error(e) }
}
onMounted(load)

const salesOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['销售额', '订单数'] },
  grid: { left: 60, right: 40, top: 30 },
  xAxis: { type: 'category', data: rows.value.map((s) => s.date) },
  yAxis: [
    { type: 'value', name: '销售额' },
    { type: 'value', name: '订单数' }
  ],
  series: [
    { name: '销售额', type: 'bar', data: rows.value.map((s) => Number(s.saleAmount)) },
    { name: '订单数', type: 'line', yAxisIndex: 1, data: rows.value.map((s) => s.orderCount) }
  ]
}))
</script>