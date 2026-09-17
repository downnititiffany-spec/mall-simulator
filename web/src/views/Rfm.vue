<template>
  <div>
    <div class="page-title">用户分层（RFM）</div>

    <div class="chart-box" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:10px 14px">
      <button style="font-size:12px" :disabled="loading" @click="load">{{ loading ? '加载中' : '刷新' }}</button>
      <button style="font-size:12px" :disabled="!exportable" @click="doExport">导出 CSV</button>
      <span style="font-size:12px;color:#9ca3af">
        分层口径版本：{{ ruleVersion || '未提供' }}；观察期：{{ observationWindow }}；只展示聚合结果，不展示个人敏感明细
      </span>
    </div>

    <AnalysisContext :context="context || {}" :state="state" :error="error" />

    <div class="chart-box">
      <div class="chart-title">RFM 八类用户分布（优先使用后端 rfmMatrix，缺失类目补 0 由后端口径负责）</div>
      <ChartState :option="matrixOpt" :state="state" :error="error" :height="300"
                  empty-text="当前快照没有 RFM 分层数据" />
    </div>

    <div class="table-box">
      <div class="chart-title">分层明细</div>
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
      <div class="table-hint">
        rfmMatrix 是后端维护的八类全量矩阵；页面不再自行追加另一套固定类目。若后端未提供 matrix，则仅展示 rfmSegments 的真实返回行，不伪造 0 人分组。
        平均最近购买天数在聚合列缺失时按“—”展示，后端不造数、前端也不补零。
      </div>
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
      <div v-if="usersError" class="table-hint">用户聚合接口（/analysis/users）本次请求未纳入：{{ usersError }}</div>
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

const COLORS = {
  重要价值: '#059669', 重要发展: '#10B981', 重要保持: '#84CC16', 重要挽留: '#D97706',
  一般价值: '#1E40AF', 一般发展: '#3B82F6', 一般保持: '#7C3AED', 一般挽留: '#94A3B8'
}
const colorOf = (name) => COLORS[name] || '#1E40AF'

// 一次请求取 RFM 分层 + 用户聚合（生命周期/偏好）；用户聚合失败不影响分层展示。
// 两个接口必须固定到同一 snapshotId：先以 /analysis/rfm 响应选定快照，再用该快照请求 /analysis/users，
// 避免两次“取当前 ACTIVE”之间发生发布切换时把不同快照的数据拼到同一页。
const usersError = ref('')
const isAbort = (e) => Boolean(e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError'))

async function fetchRfm(params, signal) {
  const rfmRaw = await api.rfm({}, { signal })
  const rfm = readEnvelope(rfmRaw)
  let usersData = {}
  let usersWarnings = []

  if (!rfm.snapshotId) {
    usersError.value = 'RFM 响应未提供 snapshotId，为避免混快照已跳过生命周期/偏好聚合请求'
  } else {
    try {
      const users = readEnvelope(await api.users({ snapshotId: rfm.snapshotId }, { signal }))
      usersData = users.data
      usersWarnings = users.warnings
    } catch (e) {
      // 主动取消（切换刷新）不算失败，不写错误提示
      if (!isAbort(e)) usersError.value = (e && (e.message || e.code)) || '请求失败'
    }
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
      periodStart: rfm.data.periodStart || null,
      periodEnd: rfm.data.periodEnd || null,
      lifecycle: usersData.lifecycle || [],
      preference: usersData.preference || []
    }
  }
}

const analysis = useAnalysis({
  fetcher: fetchRfm,
  rowKeys: [...ENDPOINT_ROW_KEYS.rfm, 'lifecycle', 'preference'],
  defaults: {
    rfmSegments: [], rfmMatrix: [], ruleVersion: null,
    periodStart: null, periodEnd: null,
    lifecycle: [], preference: []
  }
})
const { data, context, state, error, loading, exportable, exportContext } = analysis

const ruleVersion = computed(() => data.value.ruleVersion || (context.value && context.value.definitionVersion) || null)
const observationWindow = computed(() => {
  const start = data.value.periodStart
  const end = data.value.periodEnd
  return start && end ? `${start} 至 ${end}` : '未提供'
})

// §3.6 的八类补 0 口径由后端 RfmService.rfmMatrix 唯一维护；前端只搬运。
// 若旧响应缺少 rfmMatrix，则降级为 rfmSegments 的真实行，不再把另一套固定类目追加进去制造额外 0 行。
const segmentRows = computed(() => {
  const matrix = Array.isArray(data.value.rfmMatrix) ? data.value.rfmMatrix.filter((s) => s && s.valueGroup) : []
  const source = matrix.length
    ? matrix
    : (Array.isArray(data.value.rfmSegments) ? data.value.rfmSegments.filter((s) => s && s.valueGroup) : [])
  return source.map((s) => ({
    valueGroup: s.valueGroup,
    users: s.users === undefined || s.users === null ? 0 : s.users,
    amount: s.amount === undefined ? null : s.amount,
    avgRecencyDays: s.avgRecencyDays === undefined ? null : s.avgRecencyDays
  }))
})

const lifecycle = computed(() => (Array.isArray(data.value.lifecycle) ? data.value.lifecycle : []))
const preference = computed(() => (Array.isArray(data.value.preference) ? data.value.preference : []))
const matrixOpt = computed(() =>
  rfmMatrixOption(segmentRows.value, segmentRows.value.map((s) => s.valueGroup), COLORS)
)

const load = () => {
  usersError.value = ''
  return analysis.load({})
}

function doExport() {
  if (!exportable.value) return
  exportAnalysisCsv({
    baseName: 'rfm-segments',
    context: exportContext.value,
    headers: ['分层', '用户数', '消费额(元)', '平均最近购买(天)', '观察期开始', '观察期结束'],
    rows: segmentRows.value.map((s) => [
      s.valueGroup, s.users, formatNumber(s.amount, 2, ''),
      s.avgRecencyDays === null ? '' : s.avgRecencyDays,
      data.value.periodStart || '', data.value.periodEnd || ''
    ])
  })
}

onMounted(load)
onBeforeUnmount(() => analysis.cancel())
</script>

<style scoped>
.table-hint { font-size: 12px; color: #94A3B8; margin-top: 8px; }
</style>
