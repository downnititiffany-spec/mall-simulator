<template>
  <div>
    <div class="page-title">用户行为分析</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <label style="font-size:13px;color:#374151">活跃趋势日期范围：</label>
      <input type="date" v-model="from" style="padding:4px" />
      <span style="color:#9ca3af">至</span>
      <input type="date" v-model="to" style="padding:4px" />
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '加载' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <span style="font-size:12px;color:#9ca3af">漏斗按快照整体口径返回，不受日期范围影响</span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="chart-box">
      <div class="chart-title">转化漏斗（去重用户）</div>
      <div class="window-note">
        观察窗口：{{ windowNote }}
        <template v-if="overallBuyRate !== null">；整体支付转化率（后端快照口径）：{{ formatPercent(overallBuyRate) }}</template>
      </div>
      <ChartState :option="funnelOpt" :state="state" :error="error" :height="300"
                  empty-text="当前快照没有漏斗阶段数据" />
    </div>

    <div class="chart-box">
      <div class="chart-title">活跃趋势（活跃用户 / 行为量）</div>
      <ChartState :option="trendOpt" :state="state" :error="error" :height="280"
                  empty-text="所选日期范围内没有活跃趋势数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">漏斗阶段明细（转化率取后端 conversion_rate，前端不重算）</div>
      <table>
        <thead>
          <tr><th>阶段</th><th>用户数</th><th>转化率</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in stages" :key="s.stage">
            <td>{{ s.label || s.stage }}</td>
            <td class="mono">{{ formatInteger(s.users) }}</td>
            <td class="mono">{{ formatPercent(s.rate) }}</td>
          </tr>
          <tr v-if="stages.length === 0"><td colspan="3" class="el-empty">当前快照没有漏斗阶段数据</td></tr>
        </tbody>
      </table>
      <div class="table-hint">
        说明：行为类型构成、渠道/分类维度字段本期未在分析接口发布（契约 §3.4），页面不自行拆分行为类型。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import { useAnalysis } from '../composables/useAnalysis'
import { formatInteger, formatPercent } from '../utils/number'
import { readEnvelope } from '../utils/envelope'
import { localIsoDayOffset } from '../utils/localDate.js'
import { activeTrendOption, funnelOption } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

const from = ref(localIsoDayOffset(-6))
const to = ref(localIsoDayOffset(0))

// 先由漏斗接口固定主快照，再用同一 snapshotId 请求 Overview 的活跃趋势。
// 不能两个接口各自解析“当前 ACTIVE”，否则发布切换窗口可能把不同快照拼进同一页。
async function fetchBehavior(params, signal) {
  const funnel = readEnvelope(await api.funnel({}, { signal }))
  let overview = readEnvelope({})

  if (funnel.snapshotId) {
    overview = readEnvelope(await api.overview({
      from: params.from,
      to: params.to,
      snapshotId: funnel.snapshotId
    }, { signal }))
    if (overview.snapshotId && overview.snapshotId !== funnel.snapshotId) {
      throw new Error(`跨接口快照不一致：funnel=${funnel.snapshotId}, overview=${overview.snapshotId}`)
    }
  }

  const warnings = [...funnel.warnings, ...overview.warnings]
  return {
    snapshotId: funnel.snapshotId,
    businessTime: funnel.businessTime || overview.businessTime,
    dataUpdatedAt: funnel.dataUpdatedAt || overview.dataUpdatedAt,
    source: funnel.source || overview.source,
    definitionVersion: funnel.definitionVersion || overview.definitionVersion,
    qualityStatus:
      funnel.qualityStatus === 'UNKNOWN' && overview.qualityStatus !== 'UNKNOWN'
        ? overview.qualityStatus
        : funnel.qualityStatus,
    filters: { ...overview.filters, ...funnel.filters },
    warnings: [...new Set(warnings)],
    data: {
      stages: funnel.data.stages || [],
      overallBuyRate: funnel.data.overallBuyRate ?? null,
      windowNote: funnel.data.windowNote || '未提供观察窗口说明',
      activeTrend: overview.data.activeTrend || []
    }
  }
}

const analysis = useAnalysis({
  fetcher: fetchBehavior,
  rowKeys: ['stages', 'activeTrend'],
  defaults: { stages: [], activeTrend: [], overallBuyRate: null, windowNote: '' }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

const stages = computed(() => (Array.isArray(data.value.stages) ? data.value.stages : []))
const overallBuyRate = computed(() => (data.value.overallBuyRate === null || data.value.overallBuyRate === undefined ? null : data.value.overallBuyRate))
const windowNote = computed(() => data.value.windowNote || '未提供观察窗口说明')
const funnelOpt = computed(() => funnelOption(stages.value))
const trendOpt = computed(() => activeTrendOption(data.value.activeTrend))

const load = () => analysis.load({ from: from.value, to: to.value })

function doExport() {
  if (!exportable.value) return
  exportAnalysisCsv({
    baseName: 'behavior-funnel',
    context: exportContext.value,
    headers: ['阶段', '阶段编码', '用户数', '转化率'],
    rows: stages.value.map((s) => [s.label || s.stage, s.stage, s.users, formatPercent(s.rate, 2, '')])
  })
}

onMounted(load)
onBeforeUnmount(() => analysis.cancel())
</script>

<style scoped>
.window-note { font-size: 12px; color: var(--color-muted-foreground); margin-bottom: 8px; }
.table-hint { font-size: 12px; color: #94A3B8; margin-top: 8px; }
</style>
