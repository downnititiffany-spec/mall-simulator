<template>
  <div>
    <div class="page-title">运营大盘</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <label style="font-size:13px;color:#374151">趋势日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '加载' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <button style="font-size:12px" @click="showDictionary = !showDictionary">
        {{ showDictionary ? '收起指标口径' : '查看指标口径' }}
      </button>
      <span style="font-size:12px;color:#9ca3af">日期范围只作用于趋势，指标卡固定取本次快照</span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <template v-if="!empty && !failed">
      <div class="metric-cards">
        <div v-for="m in cards" :key="m.metricCode" class="metric-card">
          <div class="label">{{ m.metricName || m.metricCode }}</div>
          <div class="value">{{ m.text }}<span class="unit">{{ m.unit }}</span></div>
        </div>
      </div>

      <div class="chart-box">
        <div class="chart-title">销售趋势（销售额 / 净销售额 / 订单数 / 买家数）</div>
        <ChartState :option="salesOption" :state="state" :error="error" :height="280"
                    empty-text="所选日期范围内没有销售趋势数据" />
        <div style="font-size:12px;color:#94A3B8;margin-top:6px">{{ NET_SALE_NOTE }}</div>
      </div>

      <div class="chart-box">
        <div class="chart-title">活跃趋势（活跃用户 / 行为量）</div>
        <ChartState :option="activeOption" :state="state" :error="error" :height="280"
                    empty-text="所选日期范围内没有活跃趋势数据" />
      </div>

      <div class="chart-box">
        <div class="chart-title">数据质量（快照 run）</div>
        <div style="font-size:13px;color:#374151">质量规则：{{ qualityText }}</div>
        <div style="font-size:13px;color:#374151;margin-top:4px">规则版本：{{ ruleVersionsText }}</div>
        <div style="font-size:12px;color:#94A3B8;margin-top:6px">{{ RULE_VERSION_NOTE }}</div>
      </div>

      <div v-if="showDictionary" class="table-box">
        <div class="chart-title">指标口径（来自 analytics_meta.metric_definition）</div>
        <table>
          <thead>
            <tr><th>指标编码</th><th>指标名称</th><th>单位</th><th>口径公式</th></tr>
          </thead>
          <tbody>
            <tr v-for="d in dictionary" :key="d.metricCode">
              <td class="mono">{{ d.metricCode }}</td>
              <td>{{ d.metricName || '—' }}</td>
              <td>{{ d.unit || '—' }}</td>
              <td>{{ d.formula || '—' }}</td>
            </tr>
            <tr v-if="dictionary.length === 0"><td colspan="4" class="el-empty">当前快照未提供指标口径字典</td></tr>
          </tbody>
        </table>
      </div>
    </template>
    <div v-else-if="failed" class="el-empty">数据加载失败：{{ error }}（不使用任何本地推算值代替）</div>
    <div v-else class="el-empty">当前没有可用指标快照（告警：{{ warningSummary }}）。指标数值一律来自后端快照，页面不做推算。</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import { useAnalysis } from '../composables/useAnalysis'
import { ENDPOINT_ROW_KEYS } from '../utils/chartState'
import { formatInteger, formatNumber, formatPercent } from '../utils/number'
import { warningText } from '../utils/envelope'
// S3-28：质量文案（通过情况/规则版本）唯一属主在 utils/quality.js，视图只引用不自拼
import { qualitySummaryText, ruleVersionText, RULE_VERSION_NOTE } from '../utils/quality'
import { salesTrendOption, activeTrendOption, NET_SALE_NOTE } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

const isoDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10)
const from = ref(isoDay(-6))
const to = ref(isoDay(0))
const showDictionary = ref(false)

const analysis = useAnalysis({
  fetcher: (params, signal) => api.overview(params, { signal }),
  rowKeys: ENDPOINT_ROW_KEYS.overview,
  defaults: { metrics: [], salesTrend: [], activeTrend: [], quality: {}, metricDictionary: [] }
})
const { data, context, state, error, loading, empty, failed, exportable, exportContext } = analysis

// 指标卡顺序与展示名：后端未返回的指标不显示，不在前端造数
const CARD_META = [
  { code: 'gmv', name: '销售额(GMV)', unit: '元', digits: 2 },
  { code: 'net_sale', name: '净销售额', unit: '元', digits: 2 },
  { code: 'paid_order_cnt', name: '支付订单数', unit: '单', digits: 0 },
  { code: 'pv', name: '浏览量(PV)', unit: '', digits: 0 },
  { code: 'uv', name: '浏览用户(UV)', unit: '', digits: 0 },
  { code: 'dau', name: '活跃用户(DAU)', unit: '', digits: 0 },
  { code: 'avg_order_value', name: '客单价', unit: '元', digits: 2 },
  { code: 'refund_rate', name: '退款率', unit: '', percent: true }
]

const cards = computed(() => {
  const list = Array.isArray(data.value.metrics) ? data.value.metrics : []
  const byCode = {}
  for (const m of list) if (m && m.metricCode) byCode[m.metricCode] = m
  const out = []
  for (const meta of CARD_META) {
    const m = byCode[meta.code]
    if (!m) continue
    const text = meta.percent
      ? formatPercent(m.value)
      : (meta.digits === 0 ? formatInteger(m.value) : formatNumber(m.value, meta.digits))
    out.push({ metricCode: meta.code, metricName: meta.name || m.metricName, unit: m.unit || meta.unit, text })
  }
  // 后端返回但不在固定清单内的指标也照实展示
  for (const m of list) {
    if (!m || !m.metricCode || CARD_META.some((c) => c.code === m.metricCode)) continue
    out.push({ metricCode: m.metricCode, metricName: m.metricName || m.metricCode, unit: m.unit || '', text: formatNumber(m.value, 2) })
  }
  return out
})

// S3-28：质量信息展示（契约 v1.6 字段 + v1.8 口径）——通过情况与规则版本都只透传、不重算
const qualityText = computed(() => qualitySummaryText(data.value.quality))
const ruleVersionsText = computed(() => ruleVersionText((data.value.quality || {}).ruleVersions))

const dictionary = computed(() => (Array.isArray(data.value.metricDictionary) ? data.value.metricDictionary : []))
const salesOption = computed(() => salesTrendOption(data.value.salesTrend))
const activeOption = computed(() => activeTrendOption(data.value.activeTrend))
const warningSummary = computed(() =>
  (context.value && context.value.warnings && context.value.warnings.length
    ? context.value.warnings.map(warningText).join('；')
    : '无告警，但快照内无数据行'))

const load = () => analysis.load({ from: from.value, to: to.value })

function doExport() {
  const rows = cards.value.map((c) => [c.metricCode, c.metricName, c.text, c.unit])
  exportAnalysisCsv({
    baseName: 'overview-metrics',
    context: exportContext.value,
    headers: ['指标编码', '指标名称', '数值', '单位'],
    rows
  })
}

onMounted(load)
onBeforeUnmount(() => analysis.cancel())
</script>
