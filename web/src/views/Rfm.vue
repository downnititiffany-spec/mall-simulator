<template>
  <div>
    <div class="page-title">用户分层（RFM）</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '刷新' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <span style="font-size:12px;color:#9ca3af">
        分层口径版本：{{ ruleVersion || '未提供' }}；只展示聚合结果，不展示个人敏感明细
      </span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="chart-box">
      <div class="chart-title">RFM 八类用户分布（缺失类目按 0 人展示，契约 §3.6）</div>
      <ChartState :option="matrixOpt" :state="state" :error="error" :height="300"
                  empty-text="当前快照没有 RFM 分层数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">八类分层明细</div>
      <table>
        <thead>
          <tr><th>分层</th><th>用户数</th><th>消费额(元)</th><th>平均最近购买(天)</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in segmentRows" :key="s.valueGroup">
            <td><b :style="{ color: colorOf(s.valueGroup) }">{{ s.valueGroup }}</b></td>
            <td class="mono">{{ formatInteger(s.users) }}</td>
            <td class="mono">{{ formatNumber(s.amount, 2) }}</td>
            <td class="mono">{{ formatInteger(s.avgRecencyDays) }}</td>
          </tr>
          <tr v-if="segmentRows.length === 0"><td colspan="4" class="el-empty">当前快照没有 RFM 分层数据</td></tr>
        </tbody>
      </table>
      <div class="table-hint">平均最近购买天数在聚合列缺失时按“—”展示，后端不造数、前端也不补零。</div>
    </div>

    <div class="chart-box">
      <div class="chart-title">生命周期分布（aggregate，来自 ads_user_profile_m）</div>
      <table>
        <thead><tr><th>生命周期</th><th>用户数</th></tr></thead>
        <tbody>
          <tr v-for="l in lifecycle" :key="l.state">
            <td>{{ l.state }}</td><td class="mono">{{ formatInteger(l.users) }}</td>
          </tr>
          <tr v-if="lifecycle.length === 0"><td colspan="2" class="el-empty">未取到生命周期聚合数据</td></tr>
        </tbody>
      </table>
    </div>

    <div class="chart-box">
      <div class="chart-title">偏好分类分布（聚合）</div>
      <table>
        <thead><tr><th>分类ID</th><th>用户数</th></tr></thead>
        <tbody>
          <tr v-for="p in preference" :key="p.categoryId">
            <td class="mono">{{ p.categoryId }}</td><td class="mono">{{ formatInteger(p.users) }}</td>
          </tr>
          <tr v-if="preference.length === 0"><td colspan="2" class="el-empty">未取到偏好分类聚合数据</td></tr>
        </tbody>
      </table>
      <div v-if="usersError" class="table-hint">用户聚合接口（/analysis/users）本次请求失败：{{ usersError }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import { useAnalysis } from '../composables/useAnalysis'
import { ENDPOINT_ROW_KEYS } from '../utils/chartState'
import { formatInteger, formatNumber } from '../utils/number'
import { readEnvelope } from '../utils/envelope'
import { rfmMatrixOption } from '../utils/chartOptions'
import { exportAnalysisCsv } from '../utils/exportCsv'
import AnalysisContext from '../components/AnalysisContext.vue'
import ChartState from '../components/ChartState.vue'

// RFM 八类固定类目（缺失补 0，契约 §3.6）
const SEGMENTS = ['重要价值', '重要发展', '重要保持', '重要挽留', '一般价值', '一般发展', '一般保持', '一般挽留']
const COLORS = {
  重要价值: '#059669', 重要发展: '#10B981', 重要保持: '#84CC16', 重要挽留: '#D97706',
  一般价值: '#1E40AF', 一般发展: '#3B82F6', 一般保持: '#7C3AED', 一般挽留: '#94A3B8'
}
const colorOf = (name) => COLORS[name] || '#1E40AF'

// 一次请求取 RFM 分层 + 用户聚合（生命周期/偏好）；用户聚合失败不影响分层展示
const usersError = ref('')
const isAbort = (e) => Boolean(e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError'))

async function fetchRfm(params, signal) {
  const rfmRaw = await api.rfm({}, { signal })
  const rfm = readEnvelope(rfmRaw)
  let usersData = {}
  let usersWarnings = []
  try {
    const users = readEnvelope(await api.users({}, { signal }))
    usersData = users.data
    usersWarnings = users.warnings
  } catch (e) {
    // 主动取消（切换刷新）不算失败，不写错误提示
    if (!isAbort(e)) usersError.value = (e && (e.message || e.code)) || '请求失败'
  }
  return {
    snapshotId: rfm.snapshotId,
    businessTime: rfm.businessTime,
    dataUpdatedAt: rfm.dataUpdatedAt,
    definitionVersion: rfm.definitionVersion,
    qualityStatus: rfm.qualityStatus,
    filters: rfm.filters,
    warnings: [...new Set([...rfm.warnings, ...usersWarnings])],
    data: {
      rfmSegments: rfm.data.rfmSegments || [],
      rfmMatrix: rfm.data.rfmMatrix || [],
      ruleVersion: rfm.data.ruleVersion || null,
      lifecycle: usersData.lifecycle || [],
      preference: usersData.preference || []
    }
  }
}

const analysis = useAnalysis({
  fetcher: fetchRfm,
  rowKeys: [...ENDPOINT_ROW_KEYS.rfm, 'lifecycle', 'preference'],
  defaults: { rfmSegments: [], rfmMatrix: [], ruleVersion: null, lifecycle: [], preference: [] }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

const ruleVersion = computed(() => data.value.ruleVersion || (context.value && context.value.definitionVersion) || null)

// 后端返回的分层明细；空类目补 0 人数，保证类目齐全且不丢类
// 注意：类目名以后端下发为准（本期实际值为「高价值」等），契约八类名仅作空数据的兜底展示，
// 不能反过来用固定八类名去索引后端数据，否则会把真实人数全部显示成 0。
const segmentRows = computed(() => {
  const list = Array.isArray(data.value.rfmSegments) && data.value.rfmSegments.length
    ? data.value.rfmSegments
    : (Array.isArray(data.value.rfmMatrix) ? data.value.rfmMatrix : [])
  if (!list.length) return []
  const byName = {}
  for (const s of list) if (s && s.valueGroup) byName[s.valueGroup] = s
  const names = [...new Set([...list.map((s) => s.valueGroup).filter(Boolean), ...SEGMENTS])]
  return names.map((name) => {
    const s = byName[name] || {}
    return {
      valueGroup: name,
      users: s.users === undefined ? 0 : s.users,
      amount: s.amount === undefined ? null : s.amount,
      avgRecencyDays: s.avgRecencyDays === undefined ? null : s.avgRecencyDays
    }
  })
})

const lifecycle = computed(() => (Array.isArray(data.value.lifecycle) ? data.value.lifecycle : []))
const preference = computed(() => (Array.isArray(data.value.preference) ? data.value.preference : []))
// 图表类目与人数都取 segmentRows（后端类目优先），不再用固定八类名做索引
const matrixOpt = computed(() =>
  rfmMatrixOption(segmentRows.value, segmentRows.value.map((s) => s.valueGroup), COLORS)
)

const load = () => {
  usersError.value = ''
  return analysis.load({})
}

function doExport() {
  exportAnalysisCsv({
    baseName: 'rfm-segments',
    context: exportContext.value,
    headers: ['分层', '用户数', '消费额(元)', '平均最近购买(天)'],
    rows: segmentRows.value.map((s) => [s.valueGroup, s.users, formatNumber(s.amount, 2, ''), s.avgRecencyDays === null ? '' : s.avgRecencyDays])
  })
}

onMounted(load)
onBeforeUnmount(() => analysis.cancel())
</script>

<style scoped>
.table-hint { font-size: 12px; color: #94A3B8; margin-top: 8px; }
</style>
