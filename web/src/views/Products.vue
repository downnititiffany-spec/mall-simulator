<template>
  <div>
    <div class="page-title">商品分析
      <button style="float:right;font-size:12px;padding:4px 12px" :disabled="!rows.length" @click="doExport">导出 CSV</button>
    </div>
    <div class="chart-box">
      <div class="chart-title">商品热度 Top {{ topN }}（对数权重：浏览1/收藏2/加购3/支付5）</div>
      <BaseChart :option="rankOption" :height="Math.min(420, 60 + rows.length * 36)" />
    </div>
    <div class="table-box">
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">排名</th><th>商品ID</th><th>浏览量</th><th>收藏</th><th>加购</th><th>热度</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.productId" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ r.rank }}</td><td>{{ r.productName }}</td>
            <td>{{ r.pv }}</td><td>{{ r.fav }}</td><td>{{ r.cart }}</td><td>{{ Number(r.heat).toFixed(2) }}</td>
          </tr>
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
const topN = 10

const doExport = () => {
  exportCSV('product-heat.csv',
    ['排名', '商品', '浏览量', '收藏', '加购', '热度'],
    rows.value.map((r) => [r.rank, r.productName, r.pv, r.fav, r.cart, Number(r.heat).toFixed(2)]))
}

const rankOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 60, right: 30, top: 10, bottom: 40 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', inverse: true, data: rows.value.map((r) => r.productName) },
  series: [{ name: '热度', type: 'bar', data: rows.value.map((r) => Number(r.heat)), itemStyle: { color: '#f59e0b' } }]
}))

onMounted(async () => {
  const to = new Date().toISOString().slice(0, 10)
  const from = new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10)
  try {
    rows.value = await api.products(topN, from, to)
  } catch (e) {
    console.error(e)
  }
})
</script>