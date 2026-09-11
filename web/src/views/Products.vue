<template>
  <div>
    <div class="page-title">商品分析</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <label style="font-size:13px;color:#374151">Top N：</label>
      <input v-model.number="topN" type="number" min="1" max="200" style="width:80px;padding:4px" />
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '加载' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <span style="font-size:12px;color:#9ca3af">
        热度与转化均取后端快照口径（热度为对数加权合成值），页面不重算；本次返回 topN={{ returnedTopN ?? '—' }}
      </span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="chart-box">
      <div class="chart-title">商品热度排行</div>
      <ChartState :option="heatOpt" :state="state" :error="error" :height="Math.min(420, 100 + hotRows.length * 34)"
                  empty-text="当前快照没有商品热度数据" />
    </div>

    <div class="chart-box">
      <div class="chart-title">商品转化（浏览用户 / 支付用户）</div>
      <ChartState :option="convOpt" :state="state" :error="error" :height="300"
                  empty-text="当前快照没有商品转化数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">
        商品明细（点击表头排序）
        <span class="table-count">共 {{ rows.length }} 行</span>
      </div>
      <table>
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key" style="cursor:pointer" @click="toggleSort(col.key)">
              {{ col.title }}<span v-if="sortKey === col.key">{{ sortOrder === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in paged.items" :key="r.productId">
            <td class="mono">{{ r.rank }}</td>
            <td>{{ r.productName || r.productId }}</td>
            <td class="mono">{{ formatInteger(r.pv) }}</td>
            <td class="mono">{{ formatInteger(r.fav) }}</td>
            <td class="mono">{{ formatInteger(r.cart) }}</td>
            <td class="mono">{{ formatInteger(r.buy) }}</td>
            <td class="mono">{{ formatNumber(r.heat, 2) }}</td>
            <td class="mono">{{ formatPercent(r.conversionRate) }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="8" class="el-empty">当前快照没有商品明细数据</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button style="font-size:12px" :disabled="paged.page <= 1" @click="page = paged.page - 1">上一页</button>
        <span>第 {{ paged.page }} / {{ paged.totalPages }} 页</span>
        <button style="font-size:12px" :disabled="paged.page >= paged.totalPages" @click="page = paged.page + 1">下一页</button>
      </div>
      <div class="table-hint">
        说明：库存覆盖天数未在本期分析接口发布（契约 §3.3 只含热度与转化），页面不展示该列以避免出现无来源数值。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import { useAnalysis } from '../composables/useAnalysis'
import { ENDPOINT_ROW_KEYS } from '../utils/chartState'
import { formatInteger, formatNumber, formatPercent, isNumeric } from '../utils/number'
import { productHeatOption, productConversionOption, sortRows, paginate } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

const topN = ref(10)
const sortKey = ref('') // 空表示沿用后端 heat 排序
const sortOrder = ref('asc')
const page = ref(1)
const pageSize = 10

const analysis = useAnalysis({
  fetcher: (params, signal) => api.products(params, { signal }),
  rowKeys: ENDPOINT_ROW_KEYS.products,
  defaults: { hot: [], conversion: [], topN: null }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

const hotRows = computed(() => (Array.isArray(data.value.hot) ? data.value.hot : []))
const conversionRows = computed(() => (Array.isArray(data.value.conversion) ? data.value.conversion : []))
const returnedTopN = computed(() => (isNumeric(data.value.topN) ? Number(data.value.topN) : null))

// 合并热度与转化（按 productId 对齐），两段数据都来自后端，仅做展示拼装
const rows = computed(() => {
  const conv = {}
  for (const c of conversionRows.value) conv[c.productId] = c
  return hotRows.value.map((h) => ({
    rank: h.rank,
    productId: h.productId,
    productName: h.productName,
    pv: h.pv,
    fav: h.fav,
    cart: h.cart,
    buy: h.buy,
    heat: h.heat,
    pvUsers: conv[h.productId] ? conv[h.productId].pvUsers : null,
    buyUsers: conv[h.productId] ? conv[h.productId].buyUsers : null,
    conversionRate: conv[h.productId] ? conv[h.productId].conversionRate : null
  }))
})

const columns = [
  { key: 'rank', title: '排名' },
  { key: 'productName', title: '商品' },
  { key: 'pv', title: '浏览量' },
  { key: 'fav', title: '收藏' },
  { key: 'cart', title: '加购' },
  { key: 'buy', title: '支付' },
  { key: 'heat', title: '热度' },
  { key: 'conversionRate', title: '转化率' }
]

const sortedRows = computed(() => (sortKey.value ? sortRows(rows.value, sortKey.value, sortOrder.value) : rows.value))
const paged = computed(() => paginate(sortedRows.value, page.value, pageSize))

function toggleSort(key) {
  if (sortKey.value === key) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortOrder.value = 'desc'
  }
  page.value = 1
}

const nameOfProduct = (productId) => {
  const hit = hotRows.value.find((h) => h.productId === productId)
  return hit ? hit.productName : String(productId)
}
const heatOpt = computed(() => productHeatOption(hotRows.value))
const convOpt = computed(() => productConversionOption(conversionRows.value, nameOfProduct))

const load = () => {
  page.value = 1
  return analysis.load({ topN: topN.value })
}

function doExport() {
  exportAnalysisCsv({
    baseName: 'product-analysis',
    context: exportContext.value,
    headers: ['排名', '商品ID', '商品名称', '浏览量', '收藏', '加购', '支付', '热度', '浏览用户', '支付用户', '转化率'],
    rows: sortedRows.value.map((r) => [
      r.rank, r.productId, r.productName, r.pv, r.fav, r.cart, r.buy,
      formatNumber(r.heat, 2, ''), r.pvUsers, r.buyUsers, formatPercent(r.conversionRate, 2, '')
    ])
  })
}

onMounted(load)
onBeforeUnmount(() => analysis.cancel())
</script>

<style scoped>
.table-count { float: right; font-weight: 400; color: #94A3B8; }
.pager { display: flex; align-items: center; gap: 10px; margin-top: 10px; font-size: 12px; color: var(--color-muted-foreground); }
.table-hint { font-size: 12px; color: #94A3B8; margin-top: 8px; }
</style>
