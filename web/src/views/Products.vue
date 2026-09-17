<template>
  <div>
    <div class="page-title">商品分析</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <label style="font-size:13px;color:#374151">每页：</label>
      <input v-model.number="pageSize" type="number" min="1" max="100" :disabled="loading" style="width:80px;padding:4px" />
      <button style="font-size:12px" :disabled="loading" @click="applyFilters">{{ loading ? '加载中' : '加载' }}</button>
      <button style="font-size:12px" :disabled="!pageExportable" @click="doExport">导出当前页 CSV</button>
      <span style="font-size:12px;color:#9ca3af">
        热度与转化均取后端快照口径；热度榜分页/排序由后端执行，页面不对单页结果再次排序。
        当前第 {{ responsePage }} 页，每页 {{ responseSize }} 条，共 {{ totalText }} 条
      </span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="chart-box">
      <div class="chart-title">商品热度排行（当前页）</div>
      <ChartState :option="heatOpt" :state="state" :error="error" :height="Math.min(420, 100 + hotRows.length * 34)"
                  empty-text="当前分页窗口没有商品热度数据" />
    </div>

    <div class="chart-box">
      <div class="chart-title">商品转化（浏览用户 / 支付用户）</div>
      <ChartState :option="convOpt" :state="state" :error="error" :height="300"
                  empty-text="当前快照没有商品转化数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">
        商品明细（后端稳定排序）
        <span class="table-count">当前页 {{ rows.length }} 行</span>
      </div>
      <table>
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key"
                :style="{ cursor: col.sortKey ? 'pointer' : 'default' }"
                @click="toggleSort(col)">
              {{ col.title }}<span v-if="col.sortKey && sortKey === col.sortKey">{{ sortOrder === 'asc' ? ' ↑' : ' ↓' }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.productId">
            <td class="mono">{{ r.rank }}</td>
            <td>{{ r.productName || r.productId }}</td>
            <td class="mono">{{ formatInteger(r.pv) }}</td>
            <td class="mono">{{ formatInteger(r.fav) }}</td>
            <td class="mono">{{ formatInteger(r.cart) }}</td>
            <td class="mono">{{ formatInteger(r.buy) }}</td>
            <td class="mono">{{ formatNumber(r.heat, 2) }}</td>
            <td class="mono">{{ formatPercent(r.conversionRate) }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="8" class="el-empty">当前分页窗口没有商品明细数据</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button style="font-size:12px" :disabled="loading || responsePage <= 1" @click="goPage(responsePage - 1)">上一页</button>
        <span>第 {{ responsePage }} 页 / 共 {{ totalText }} 条</span>
        <button style="font-size:12px" :disabled="loading || !hasMore" @click="goPage(responsePage + 1)">下一页</button>
      </div>
      <div class="table-hint">
        排序字段只开放后端契约白名单：排名、热度、浏览、收藏、加购、支付；商品名称与转化率不在服务端排序白名单内，因此不伪造本地全量排序。
        库存覆盖天数未在本期分析接口发布（契约 §3.3 只含热度与转化），页面不展示该列以避免出现无来源数值。
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
import { productHeatOption, productConversionOption } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

const page = ref(1)
const pageSize = ref(10)
const sortKey = ref('rank')
const sortOrder = ref('asc')

const analysis = useAnalysis({
  fetcher: (params, signal) => api.products(params, { signal }),
  rowKeys: ENDPOINT_ROW_KEYS.products,
  defaults: { hot: [], conversion: [], topN: null, page: 1, size: 10, total: null, hasMore: false }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

const hotRows = computed(() => (Array.isArray(data.value.hot) ? data.value.hot : []))
const conversionRows = computed(() => (Array.isArray(data.value.conversion) ? data.value.conversion : []))
const responsePage = computed(() => (isNumeric(data.value.page) ? Number(data.value.page) : page.value))
const responseSize = computed(() => (isNumeric(data.value.size) ? Number(data.value.size) : pageSize.value))
const total = computed(() => (isNumeric(data.value.total) ? Number(data.value.total) : null))
const totalText = computed(() => (total.value === null ? '—' : String(total.value)))
const hasMore = computed(() => data.value.hasMore === true)

// 合并当前 hot 分页窗口与 conversion 全量（按 productId 对齐）；两段数据都来自后端，仅做展示拼装。
const rows = computed(() => {
  const conv = new Map()
  for (const c of conversionRows.value) if (c && c.productId !== null && c.productId !== undefined) conv.set(c.productId, c)
  return hotRows.value.map((h) => {
    const c = conv.get(h.productId)
    return {
      rank: h.rank,
      productId: h.productId,
      productName: h.productName,
      pv: h.pv,
      fav: h.fav,
      cart: h.cart,
      buy: h.buy,
      heat: h.heat,
      pvUsers: c ? c.pvUsers : null,
      buyUsers: c ? c.buyUsers : null,
      conversionRate: c ? c.conversionRate : null
    }
  })
})

// 整页 ready 只说明 hot / conversion 至少有一个区块有数据；“导出当前页”必须要求当前 hot 页确实有行。
const pageExportable = computed(() => exportable.value && rows.value.length > 0)

const columns = [
  { key: 'rank', title: '排名', sortKey: 'rank', defaultOrder: 'asc' },
  { key: 'productName', title: '商品' },
  { key: 'pv', title: '浏览量', sortKey: 'pv', defaultOrder: 'desc' },
  { key: 'fav', title: '收藏', sortKey: 'fav', defaultOrder: 'desc' },
  { key: 'cart', title: '加购', sortKey: 'cart', defaultOrder: 'desc' },
  { key: 'buy', title: '支付', sortKey: 'buy', defaultOrder: 'desc' },
  { key: 'heat', title: '热度', sortKey: 'heat', defaultOrder: 'desc' },
  { key: 'conversionRate', title: '转化率' }
]

function requestParams() {
  return {
    page: page.value,
    size: pageSize.value,
    sort: `${sortKey.value},${sortOrder.value}`
  }
}

const load = () => analysis.load(requestParams())

function applyFilters() {
  if (loading.value) return
  page.value = 1
  return load()
}

function goPage(targetPage) {
  if (loading.value || targetPage < 1) return
  page.value = targetPage
  return load()
}

function toggleSort(col) {
  if (!col || !col.sortKey || loading.value) return
  if (sortKey.value === col.sortKey) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = col.sortKey
    sortOrder.value = col.defaultOrder || 'desc'
  }
  page.value = 1
  return load()
}

const nameOfProduct = (productId) => {
  const hit = hotRows.value.find((h) => h.productId === productId)
  return hit ? hit.productName : String(productId)
}
const heatOpt = computed(() => productHeatOption(hotRows.value))
const convOpt = computed(() => productConversionOption(conversionRows.value, nameOfProduct))

function doExport() {
  if (!pageExportable.value) return
  exportAnalysisCsv({
    baseName: `product-analysis-page-${responsePage.value}`,
    context: exportContext.value,
    headers: ['排名', '商品ID', '商品名称', '浏览量', '收藏', '加购', '支付', '热度', '浏览用户', '支付用户', '转化率'],
    rows: rows.value.map((r) => [
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
