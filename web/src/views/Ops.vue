<template>
  <div>
    <div class="page-title">运维中心</div>

    <!-- 运维页数据来源为 /metrics/*、/ai/audit/*、/admin/users 等非统一信封接口：
         上下文由响应内可见字段拼装，取不到的字段逐项标注「接口未提供」，不编造。 -->
    <div class="chart-box">
      <div class="chart-title">
        运维数据上下文
        <button style="float:right;font-size:12px;padding:3px 10px" @click="loadAll" :disabled="loading">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <AnalysisContext :context="exportContext" :state="state" :error="error" />
    </div>

    <div class="chart-box">
      <div class="chart-title">系统用户（admin 专属：创建/启用禁用/重置密码；不随快照版本变化）</div>
      <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">
        <input v-model="newUser.username" placeholder="用户名" style="padding:4px" />
        <input v-model="newUser.realName" placeholder="姓名" style="padding:4px" />
        <select v-model="newUser.role" style="padding:4px">
          <option value="operator">运营专员</option><option value="analyst">数据分析师</option>
          <option value="admin">系统管理员</option>
        </select>
        <input v-model="newUser.password" placeholder="初始密码(≥6位)" type="password" style="padding:4px" />
        <button style="font-size:12px;background:#16a34a" @click="createUser" :disabled="busy">创建用户</button>
      </div>
      <div class="table-hint">
        说明：/admin/users 的用户列表接口不返回启用状态（UserView 只有 id/username/realName/role），
        因此「状态」列只显示本会话内的切换结果，初始一律标注「接口未提供」，不假装知道后端当前状态；
        若同一账号同时被他人改动，以刷新后后端行为为准。后端规则：不能停用当前登录账号。
      </div>
      <table v-if="users.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:6px">ID</th><th>用户名</th><th>姓名</th><th>角色</th><th>启用状态</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px">{{ u.id }}</td>
            <td>{{ u.username }}</td>
            <td>{{ u.realName || '—' }}</td>
            <td>{{ roleName(u.role) }}</td>
            <td>
              <span v-if="knownStatus(u.id) === null" style="color:#6b7280">接口未提供</span>
              <span v-else :style="{ color: knownStatus(u.id) ? '#16a34a' : '#dc2626' }">
                {{ knownStatus(u.id) ? '已启用（本会话确认）' : '已禁用（本会话确认）' }}
              </span>
            </td>
            <td style="white-space:nowrap">
              <template v-if="knownStatus(u.id) === null">
                <button style="font-size:12px;background:#dc2626" @click="toggle(u, false)" :disabled="busy">禁用</button>
                <button style="font-size:12px;background:#16a34a" @click="toggle(u, true)" :disabled="busy">启用</button>
              </template>
              <button v-else-if="knownStatus(u.id)" style="font-size:12px;background:#dc2626" @click="toggle(u, false)" :disabled="busy">禁用</button>
              <button v-else style="font-size:12px;background:#16a34a" @click="toggle(u, true)" :disabled="busy">启用</button>
              <button style="font-size:12px" @click="resetPwd(u)" :disabled="busy">重置密码</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无用户或该接口未返回数据</div>
      <div v-if="actionError" class="table-hint table-hint-error">用户操作失败：{{ actionError }}</div>
    </div>

    <div class="chart-box">
      <div class="chart-title">快照版本（BUILDING→VERIFYING→ACTIVE / ARCHIVED / FAILED）</div>
      <DataTable :table="snapshotTable" :is-active="isActiveSnapshotRow" action-label="操作"
                 :on-action="viewSnapshotRow" :cell-color="snapshotCellColor"
                 empty-text="暂无快照或该接口未返回数据" />
      <div class="table-hint">
        「查看指标」按该行的快照号加载指标行；当前选中行以浅蓝底标注。状态与版本字段原样来自 /metrics/snapshots。
      </div>

      <div v-if="selected" style="margin-top:12px">
        <div class="chart-title">
          快照 {{ selected }} 指标
          <button style="float:right;font-size:12px;padding:3px 10px"
                  :disabled="!metricExportable" @click="exportMetrics">
            {{ metricExportable ? '导出指标 CSV' : '导出（' + metricStateText + '）' }}
          </button>
        </div>
        <div class="meta-row" style="margin-bottom:8px">
          <span class="meta-item">指标快照 <b class="mono">{{ metricExportContext.snapshotId || '无' }}</b></span>
          <span class="meta-item">口径版本 <b class="mono">{{ metricExportContext.definitionVersion || '未知' }}</b></span>
          <span class="meta-item">业务时间 <b class="mono">{{ fmtDateTime(metricExportContext.businessTime) }}</b></span>
          <span class="meta-item">数据更新 <b class="mono">{{ fmtDateTime(metricExportContext.dataUpdatedAt) }}</b></span>
        </div>
        <div v-if="metricState === 'loading'" class="state-line">指标加载中…</div>
        <div v-else-if="metricState === 'error'" class="banner banner-error">指标加载失败：{{ metricError || '未知原因' }}</div>
        <div v-else-if="metricState === 'empty'" class="state-line">{{ metricEmptyText }}</div>
        <div v-else-if="metricState === 'stale'" class="banner banner-stale">数据更新中：正在重新拉取该快照指标，当前展示的仍是上一次结果，已禁止导出。</div>
        <table v-if="metricTable.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th v-for="h in metricTable.headers" :key="h" style="padding:6px">{{ h }}</th>
          </tr></thead>
          <tbody>
            <tr v-for="(row, i) in metricTable.rows" :key="i" style="border-top:1px solid #f3f4f6">
              <td v-for="(cell, j) in row" :key="j" style="padding:6px" class="mono">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
        <div class="table-hint">
          说明：/metrics/overview 返回指标数组（非统一信封），快照与口径版本取自指标行本身；
          业务时间、数据更新时间、质量状态、生效筛选不在此接口返回内，已在上方上下文如实标注为「接口未提供」。
        </div>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        流水线实例（幂等键 / 尝试次数 / 目标快照，溯源链路）
        <button style="float:right;font-size:12px;padding:3px 10px"
                :disabled="!exportable" @click="exportRuns">
          {{ exportable ? '导出流水线 CSV' : '导出（' + statusText + '）' }}
        </button>
      </div>
      <table v-if="pipelineTable.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th v-for="h in pipelineTable.headers" :key="h" style="padding:6px">{{ h }}</th>
        </tr></thead>
        <tbody>
          <tr v-for="(r, i) in pipelineTable.rows" :key="i" style="border-top:1px solid #f3f4f6">
            <td v-for="(cell, j) in r" :key="j" style="padding:6px" class="mono">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无流水线实例或该接口未返回数据</div>
    </div>

    <div class="chart-box">
      <div class="chart-title">数据质量规则结果（金额对账失败会阻断发布）</div>
      <table v-if="qualityTable.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th v-for="h in qualityTable.headers" :key="h" style="padding:6px">{{ h }}</th>
        </tr></thead>
        <tbody>
          <tr v-for="(q, i) in qualityTable.rows" :key="i" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px">{{ q[0] }}</td>
            <td>{{ q[1] }}</td>
            <td><span :style="{ color: q[2] === '通过' ? '#16a34a' : '#dc2626', fontWeight: 600 }">{{ q[2] }}</span></td>
            <td>{{ q[3] }}</td>
            <td class="mono">{{ q[4] }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无质量结果（跑一次流水线后出现）或该接口未返回数据</div>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start">
      <div class="chart-box">
        <div class="chart-title">
          AI 问答审计（ai_query_history）
          <button style="float:right;font-size:12px;padding:3px 10px" @click="exportAudit('ai')">导出 CSV</button>
        </div>
        <table v-if="aiHistoryTable.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用户</th><th>问题</th><th>状态</th><th>行数</th><th>耗时</th><th>时间</th>
          </tr></thead>
          <tbody>
            <tr v-for="h in aiHistoryRows" :key="h.createdAt + h.question" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ h.userId }}</td>
              <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="h.question">{{ h.question }}</td>
              <td>{{ h.status }}</td>
              <td class="mono">{{ h.rowsReturned }}</td>
              <td class="mono">{{ h.elapsedMs }}</td>
              <td class="mono">{{ h.createdAt }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无问答记录</div>
        <details v-if="aiHistoryRows.length" style="margin-top:8px">
          <summary style="font-size:12px;color:#6b7280;cursor:pointer">展开 SQL 与使用表（证据复核）</summary>
          <div v-for="(h, i) in aiHistoryRows" :key="i" style="margin-top:6px;font-size:12px">
            <div class="mono">{{ h.tables }}</div>
            <pre style="background:#f9fafb;padding:8px;border-radius:6px;overflow:auto">{{ h.sqlText }}</pre>
            <div v-if="h.errors && h.errors !== '—'" style="color:#dc2626">错误：{{ h.errors }}</div>
          </div>
        </details>
      </div>
      <div class="chart-box">
        <div class="chart-title">
          模型调用审计（ai_call_log）
          <button style="float:right;font-size:12px;padding:3px 10px" @click="exportAudit('calls')">导出 CSV</button>
        </div>
        <table v-if="aiCallTable.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用例</th><th>模型</th><th>tokens</th><th>耗时</th><th>状态</th>
          </tr></thead>
          <tbody>
            <tr v-for="(c, i) in aiCallRows" :key="i" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ c.useCase }}</td>
              <td>{{ c.provider }}/{{ c.model }}</td>
              <td class="mono">{{ c.inputTokens }}/{{ c.outputTokens }}</td>
              <td class="mono">{{ c.elapsedMs }}</td>
              <td>{{ c.status }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无调用记录</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, onServerPrefetch, onUnmounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import DataTable from '../components/DataTable.vue'
import { useAnalysis } from '../composables/useAnalysis'
import { formatDateTime } from '../utils/envelope'
import { buildFallbackContext, mergeWarnings, NON_ANALYSIS_ROW_KEYS } from '../utils/context'
import {
  aiCallRows as mapAiCallRows,
  aiHistoryRows as mapAiHistoryRows,
  COLUMNS,
  metricRows,
  pipelineRunRows as mapPipelineRunRows,
  qualityRows,
  snapshotRows,
  toTable
} from '../utils/tables'
import { exportAnalysisCsv } from '../utils/exportCsv'

const users = ref([])
// 用户启用状态：列表接口不返回该字段，只记录本会话内切换过的结果，其余为 null（=接口未提供）
const lastKnownStatus = ref({})
const busy = ref(false)
const actionError = ref('')
const newUser = ref({ username: '', realName: '', role: 'operator', password: '' })
const selected = ref('')

const roleName = (r) => ({ admin: '系统管理员', operator: '运营专员', analyst: '数据分析师' }[r] || r)
const fmtDateTime = formatDateTime
const knownStatus = (id) => {
  const v = lastKnownStatus.value[id]
  return typeof v === 'boolean' ? v : null
}
const snapColor = (s) => ({ ACTIVE: '#16a34a', BUILDING: '#d97706', VERIFYING: '#d97706', ARCHIVED: '#6b7280', FAILED: '#dc2626' }[s] || '#111827')

// 运维页多来源取数：统一信封接口（无）与非信封接口混用，
// 因此在这里拼上下文；任一子接口失败都要显式告知，不静默当作没数据。
async function fetchOps(params, signal) {
  const [snapshots, quality, history, calls, pipelineRuns] = await Promise.all([
    api.snapshots(15, { signal }),
    api.quality(20, { signal }),
    api.aiAuditHistory(10, { signal }),
    api.aiAuditCalls(10, { signal }),
    api.pipelineRuns(10, { signal })
  ])
  const warnings = []
  // 这些接口都不返回统一信封，逐项登记，页面与导出件都能看到“为什么上下文是拼的”
  warnings.push('ENVELOPE_MISSING')

  const snapshotList = Array.isArray(snapshots) ? snapshots : []
  const qualityList = Array.isArray(quality) ? quality : []
  const historyList = Array.isArray(history) ? history : []
  const callList = Array.isArray(calls) ? calls : []
  const runs = Array.isArray(pipelineRuns) ? (pipelineRuns.items || pipelineRuns) : []

  // 找 ACTIVE 快照作为上下文主快照（找不到就如实留空，由上下文标注缺失）
  const active = snapshotList.find((s) => s && s.status === 'ACTIVE') || null
  // 质量结论：有质量结果就按结果判定；一条都没有则传 null → 上下文如实标注「接口未提供」
  const qualityOk = qualityList.length ? qualityList.every((q) => q.passed === 1) : null
  const ctx = buildFallbackContext({
    rows: snapshotList,
    warnings,
    snapshotIds: active ? [active.snapshotId] : [],
    businessTime: active ? active.businessTime : null,
    dataUpdatedAt: active ? active.dataUpdatedAt : null,
    qualityStatus: qualityOk === null ? null : (qualityOk ? 'PASS' : 'FAIL')
  })

  return {
    snapshotId: ctx.snapshotId,
    businessTime: ctx.businessTime,
    dataUpdatedAt: ctx.dataUpdatedAt,
    definitionVersion: ctx.definitionVersion,
    qualityStatus: ctx.qualityStatus,
    filters: ctx.filters,
    warnings: mergeWarnings(ctx.warnings, qualityOk === false ? ['QUALITY_RULE_FAILED'] : []),
    missingNotice: ctx.missingNotice,
    data: {
      snapshots: snapshotList,
      qualityResults: qualityList,
      aiHistory: historyList,
      aiCalls: callList,
      pipelineRuns: runs
    }
  }
}

const analysis = useAnalysis({ fetcher: fetchOps, rowKeys: NON_ANALYSIS_ROW_KEYS.opsAudit })
const { data, state, loading, error, exportable, statusText, exportContext, load, cancel } = analysis

// 快照指标单独取（点「查看指标」时）：/metrics/overview 支持快照号，且返回裸数组
const metricAnalysis = useAnalysis({ fetcher: fetchMetrics, rowKeys: NON_ANALYSIS_ROW_KEYS.opsMetrics })
const {
  data: metricData,
  state: metricState,
  error: metricError,
  exportable: metricExportable,
  exportContext: metricExportContext,
  load: loadMetrics,
  cancel: cancelMetrics
} = metricAnalysis

async function fetchMetrics(params, signal) {
  const snapshotId = params && params.snapshotId ? params.snapshotId : selected.value
  // 没有快照号就不发请求：/metrics/overview 不带 snapshotId 会返回全部快照的指标，
  // 那样页面展示的就不是「某一期快照」的数据，与上下文标注不符。
  if (!snapshotId) {
    return {
      snapshotId: null,
      businessTime: null,
      dataUpdatedAt: null,
      definitionVersion: null,
      qualityStatus: 'UNKNOWN',
      filters: {},
      warnings: mergeWarnings(['ENVELOPE_MISSING'], ['NO_SNAPSHOT_SELECTED']),
      missingNotice: '接口未提供（尚未选择快照，未发起 /metrics/overview 请求）：snapshotId',
      data: { metrics: [] }
    }
  }
  const raw = await api.metricsOverview(snapshotId, { signal })
  const list = Array.isArray(raw) ? raw : []
  const ctx = buildFallbackContext({
    rows: list,
    warnings: ['ENVELOPE_MISSING'],
    snapshotIds: snapshotId ? [snapshotId] : []
  })
  return {
    snapshotId: ctx.snapshotId,
    businessTime: ctx.businessTime,
    dataUpdatedAt: ctx.dataUpdatedAt,
    definitionVersion: ctx.definitionVersion,
    qualityStatus: ctx.qualityStatus,
    filters: { snapshotId },
    warnings: ctx.warnings,
    missingNotice: ctx.missingNotice,
    data: { metrics: list }
  }
}

async function viewSnapshot(id) {
  selected.value = id
  await loadMetrics({ snapshotId: id })
}

// 首次取到快照列表后自动展示 ACTIVE 快照的指标（浏览器端点「查看指标」或此处自动选中；离线渲染校验走同一逻辑）
const snapshotList = computed(() => (Array.isArray(data.value.snapshots) ? data.value.snapshots : []))
function autoSelectActive() {
  if (selected.value) return
  const first = snapshotList.value.find((s) => s && s.status === 'ACTIVE') || snapshotList.value[0]
  if (first && first.snapshotId) return viewSnapshot(first.snapshotId)
  return null
}

// 表格数据全部来自后端字段，页面只做列映射
const snapshotTable = computed(() => toTable(snapshotRows(snapshotList.value), COLUMNS.opsSnapshots))
// toTable 的行是「按列定义排列的数组」，第一列即快照 ID（见 COLUMNS.opsSnapshots）
const snapshotIdOfRow = (row) => (Array.isArray(row) ? row[0] : null)
const isActiveSnapshotRow = (i) => snapshotIdOfRow(snapshotTable.value.rows[i]) === selected.value
const viewSnapshotRow = (row) => viewSnapshot(snapshotIdOfRow(row))
// 状态列（第 3 列）只上色，不改文字：文字永远是后端原样返回的状态码
const snapshotCellColor = (cell, i, j) => (j === 2 ? snapColor(cell) : null)
const pipelineTable = computed(() => toTable(mapPipelineRunRows(data.value.pipelineRuns), COLUMNS.opsPipelineRuns))
const qualityTable = computed(() => toTable(qualityRows(data.value.qualityResults), COLUMNS.opsQuality))
const metricTable = computed(() => toTable(metricRows(metricData.value.metrics), COLUMNS.opsMetrics))
const aiHistoryTable = computed(() => toTable(mapAiHistoryRows(data.value.aiHistory), COLUMNS.opsAiHistory))
const aiCallTable = computed(() => toTable(mapAiCallRows(data.value.aiCalls), COLUMNS.opsAiCalls))
const aiHistoryRows = computed(() => mapAiHistoryRows(data.value.aiHistory))
const aiCallRows = computed(() => mapAiCallRows(data.value.aiCalls))
const metricStateText = computed(() => (metricState.value === 'empty' ? '暂无数据' : metricState.value === 'stale' ? '数据更新中' : metricState.value === 'error' ? '失败' : '加载中'))
const metricEmptyText = computed(() =>
  selected.value ? `快照 ${selected.value} 没有指标数据，或该快照不属于当前 runtime profile` : '请先选择快照'
)

async function loadUsers() {
  try {
    users.value = (await api.adminUsers()) || []
  } catch (e) {
    // 非 admin 或无权限时如实提示，不假装没数据
    actionError.value = (e && (e.message || e.code)) || '用户列表加载失败'
  }
}

async function loadAll() {
  actionError.value = ''
  await Promise.all([load({}), loadUsers()])
}

function exportMetrics() {
  const generatedAt = new Date().toISOString()
  // 行数据取映射后的对象，列头与表格完全一致，避免导出与页面两套字段
  exportAnalysisCsv({
    baseName: `metrics-${selected.value || 'no-snapshot'}`,
    context: metricExportContext.value,
    generatedAt,
    headers: COLUMNS.opsMetrics.map((c) => c.label),
    rows: metricRows(metricData.value.metrics).map((r) => COLUMNS.opsMetrics.map((c) => r[c.key]))
  })
}

function exportRuns() {
  if (!exportable.value) return
  const generatedAt = new Date().toISOString()
  exportAnalysisCsv({
    baseName: 'pipeline-runs',
    context: exportContext.value,
    generatedAt,
    headers: COLUMNS.opsPipelineRuns.map((c) => c.label),
    rows: pipelineTable.value.rows
  })
}

function exportAudit(which) {
  const generatedAt = new Date().toISOString()
  const isAi = which === 'ai'
  const columns = isAi ? COLUMNS.opsAiHistory : COLUMNS.opsAiCalls
  const rows = isAi ? aiHistoryRows.value : aiCallRows.value
  exportAnalysisCsv({
    baseName: isAi ? 'ai-query-audit' : 'ai-call-audit',
    context: exportContext.value,
    generatedAt,
    headers: columns.map((c) => c.label),
    rows: rows.map((r) => columns.map((c) => r[c.key]))
  })
}

async function createUser() {
  busy.value = true
  actionError.value = ''
  try {
    await api.adminCreateUser(newUser.value)
    newUser.value = { username: '', realName: '', role: 'operator', password: '' }
    await loadUsers()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '创建失败'
  } finally {
    busy.value = false
  }
}

async function toggle(u, enable) {
  busy.value = true
  actionError.value = ''
  try {
    await api.adminUserAction(u.id, 'toggle', { enable })
    // 后端返回 Void，不回声状态：只能记录本次我们确实请求到的目标状态
    lastKnownStatus.value = { ...lastKnownStatus.value, [u.id]: enable }
    await loadUsers()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '操作失败'
  } finally {
    busy.value = false
  }
}

async function resetPwd(u) {
  const pwd = prompt(`重置 ${u.username} 的密码（至少 6 位）`, '')
  if (!pwd) return
  busy.value = true
  actionError.value = ''
  try {
    await api.adminUserAction(u.id, 'reset-password', { password: pwd })
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '重置失败'
  } finally {
    busy.value = false
  }
}

// 首次取数：浏览器端在 mounted 执行；离线渲染校验（无 window）时走 onServerPrefetch，
// 两者复用同一个 init 逻辑，避免“只有浏览器能看到数据”的验证盲区。
async function init() {
  await loadAll()
  // 取完快照列表后选中 ACTIVE 快照并加载其指标
  await autoSelectActive()
}

if (typeof window === 'undefined' && getCurrentInstance()) {
  onServerPrefetch(init)
} else {
  onMounted(init)
}

// 卸载时取消在途请求，避免卸载后写状态
onUnmounted(() => {
  cancel()
  cancelMetrics()
})
</script>
