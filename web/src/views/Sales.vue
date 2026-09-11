<template>
  <div>
    <div class="page-title">销售分析</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <label style="font-size:13px;color:#374151">日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '加载' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <span style="font-size:12px;color:#9ca3af">汇总值取快照指标库，趋势取快照明细，页面不重算</span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="metric-cards">
      <div v-for="c in cards" :key="c.code" class="metric-card">
        <div class="label">{{ c.label }}</div>
        <div class="value">{{ c.text }}<span class="unit">{{ c.unit }}</span></div>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">销售趋势（销售额 / 订单数 / 买家数）</div>
      <ChartState :option="trendOpt" :state="state" :error="error" :height="280"
                  empty-text="所选日期范围内没有销售趋势数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">
        销售明细
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
          <tr v-for="r in paged.items" :key="r.date">
            <td class="mono">{{ r.date }}</td>
            <td class="mono">{{ formatInteger(r.orderCount) }}</td>
            <td class="mono">{{ formatNumber(r.saleAmount, 2) }}</td>
            <td class="mono">{{ formatInteger(r.buyerCount) }}</td>
            <td class="mono">{{ formatNumber(r.avgOrderValue, 2) }}</td>
          </tr>
          <tr v-if="rows.length === 0"><td colspan="5" class="el-empty">所选日期范围内没有销售数据</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button style="font-size:12px" :disabled="paged.page <= 1" @click="page = paged.page - 1">上一页</button>
        <span>第 {{ paged.page }} / {{ paged.totalPages }} 页</span>
        <button style="font-size:12px" :disabled="paged.page >= paged.totalPages" @click="page = paged.page + 1">下一页</button>
      </div>
      <div class="table-hint">
        质量规则（快照 run）：{{ qualityText }}。
        说明：分类结构、地区结构本期未在分析接口发布（契约 §3.2 中 ads_category_sale_m / ads_region_sale_m 不存在），
        页面不展示无数据来源的维度图。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import { useAnalysis } from '../composables/useAnalysis'
import { ENDPOINT_ROW_KEYS } from '../utils/chartState'
import { formatInteger, formatNumber, formatPercent } from '../utils/number'
import { salesTrendOption, sortRows, paginate } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

const isoDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10)
const from = ref(isoDay(-6))
const to = ref(isoDay(0))
const sortKey = ref('')
const sortOrder = ref('asc')
const page = ref(1)
const pageSize = 10

const analysis = useAnalysis({
  fetcher: (params, signal) => api.sales(params, { signal }),
  rowKeys: ENDPOINT_ROW_KEYS.sales,
  defaults: { trend: [], gmv: null, netSale: null, refundRate: null, fullRefundRate: null, quality: {} }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

// GMV 与净销售必须并列展示（指导书 §18.2 销售分析）
const cards = computed(() => [
  { code: 'gmv', label: '销售额(GMV)', text: formatNumber(data.value.gmv, 2), unit: '元' },
  { code: 'netSale', label: '净销售额', text: formatNumber(data.value.netSale, 2), unit: '元' },
  { code: 'refundRate', label: '退款率', text: formatPercent(data.value.refundRate), unit: '' },
  { code: 'fullRefundRate', label: '全额退款率', text: formatPercent(data.value.fullRefundRate), unit: '' }
])

const rows = computed(() => (Array.isArray(data.value.trend) ? data.value.trend : []))
const columns = [
  { key: 'date', title: '日期' },
  { key: 'orderCount', title: '订单数' },
  { key: 'saleAmount', title: '销售额(元)' },
  { key: 'buyerCount', title: '买家数' },
  { key: 'avgOrderValue', title: '客单价(元)' }
]
const sortedRows = computed(() => (sortKey.value ? sortRows(rows.value, sortKey.value, sortOrder.value) : rows.value))
const paged = computed(() => paginate(sortedRows.value, page.value, pageSize))

const qualityText = computed(() => {
  const q = data.value.quality || {}
  const ruleCount = formatInteger(q.ruleCount, '—')
  const passedCount = formatInteger(q.passedCount, '—')
  const failed = Array.isArray(q.failedRules) && q.failedRules.length ? q.failedRules.join('、') : '无'
  return `规则 ${passedCount}/${ruleCount} 通过，失败规则：${failed}`
})

const trendOpt = computed(() => salesTrendOption(rows.value))

function toggleSort(key) {
  if (sortKey.value === key) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortOrder.value = 'asc'
  }
  page.value = 1
}

const load = () => {
  page.value = 1
  return analysis.load({ from: from.value, to: to.value })
}

function doExport() {
  exportAnalysisCsv({
    baseName: 'sales-analysis',
    context: exportContext.value,
    headers: ['日期', '订单数', '销售额(元)', '买家数', '客单价(元)'],
    rows: sortedRows.value.map((r) => [r.date, r.orderCount, formatNumber(r.saleAmount, 2, ''), r.buyerCount, formatNumber(r.avgOrderValue, 2, '')])
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
