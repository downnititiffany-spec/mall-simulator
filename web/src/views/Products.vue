<template>
  <div>
    <div class="page-title">商品分析
      <button style="float:right;font-size:12px;padding:4px 12px" :disabled="!rows.length" @click="doExport">导出 CSV</button>
    </div>
    <div class="chart-box" style="display:flex;gap:10px;align-items:center;padding:10px 14px">
      <label style="font-size:13px;color:#374151">日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" @click="load">加载</button>
      <span style="font-size:12px;color:#9ca3af">热度 = 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付)（§21.7）</span>
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
const from = ref(new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10))
const to = ref(new Date().toISOString().slice(0, 10))

const doExport = () => {
  exportCSV(`product-heat-${from.value}_${to.value}.csv`,
    ['排名', '商品', '浏览量', '收藏', '加购', '热度'],
    rows.value.map((r) => [r.rank, r.productName, r.pv, r.fav, r.cart, Number(r.heat).toFixed(2)]))
}

async function load() {
  try {
    rows.value = await api.products(topN, from.value, to.value)
  } catch (e) {
    console.error(e)
  }
}
onMounted(load)

const rankOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 60, right: 30, top: 10, bottom: 40 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', inverse: true, data: rows.value.map((r) => r.productName) },
  series: [{ name: '热度', type: 'bar', data: rows.value.map((r) => Number(r.heat)), itemStyle: { color: '#f59e0b' } }]
}))
</script>