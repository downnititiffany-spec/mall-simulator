<template>
  <div>
    <div class="page-title">决策中心</div>

    <!-- /decisions 不是统一信封：快照上下文由决策行的 suggestionSnapshotId 汇总，
         业务时间/数据更新时间/质量状态不在此接口返回内，逐项标注「接口未提供」。 -->
    <div class="chart-box">
      <div class="chart-title">
        决策数据上下文
        <button class="btn btn-sm" style="float:right" @click="refresh" :disabled="loading || busy">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <AnalysisContext :context="exportContext" :state="state" :error="error" />
      <div class="table-hint">
        说明：「建议快照」是 AI 建议来源；批准时真正锁定的评价基线来自「基线快照」，并同时记录口径版本。
        多个决策可能来自不同建议快照，因此上方快照栏在存在多个值时逐个列出，不合并成单一快照。
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        决策列表（AI 草稿 → 人工审核 → 执行 → 效果评价）
        <button class="btn btn-sm" style="float:right"
                :disabled="!exportable" @click="exportDecisions">
          {{ exportable ? '导出决策 CSV' : '导出（' + statusText + '）' }}
        </button>
      </div>

      <div v-if="state === 'loading'" class="state-line">决策列表加载中…</div>
      <div v-else-if="state === 'error'" class="banner banner-error">决策列表加载失败：{{ error || '未知原因' }}</div>
      <div v-else-if="state === 'stale'" class="banner banner-stale">数据更新中：当前展示的仍是上一次结果，已禁止导出。</div>
      <div v-else-if="state === 'empty'" class="el-empty">暂无决策</div>

      <div v-if="actionError" class="banner banner-error">操作失败：{{ actionError }}</div>

      <table v-if="rows.length" class="data-table">
        <thead><tr style="text-align:left;color:var(--gray-500)">
          <th style="padding:8px">编号</th><th>标题</th><th>来源</th><th>目标指标</th><th>基线</th>
          <th>建议快照</th><th>基线快照</th><th>口径版本</th><th>负责人</th><th>截止日期</th>
          <th>状态</th><th>效果</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="d in rows" :key="d.id" style="border-top:1px solid var(--gray-100)">
            <td style="padding:8px" class="mono">{{ d.decisionNo }}</td>
            <td>{{ d.title }}</td>
            <td>{{ d.source || '—' }}</td>
            <td>{{ d.targetMetricCode || '—' }} <span v-if="d.targetDirection">({{ d.targetDirection }})</span></td>
            <td class="mono">{{ d.baselineValue }}</td>
            <td class="mono">{{ d.suggestionSnapshotId || '—' }}</td>
            <td class="mono">{{ d.baselineSnapshotId || '—' }}</td>
            <td class="mono">{{ d.definitionVersion || '—' }}</td>
            <td>{{ d.owner || '—' }}</td>
            <td class="mono">{{ d.dueDate || '—' }}</td>
            <td><span class="badge" :class="statusBadge(d.status)">{{ d.status }}</span></td>
            <td>
              <span v-if="evaluations[d.id]">
                {{ evaluations[d.id].result }} ({{ evaluations[d.id].improvementRate }})
                <div class="table-hint mono">
                  基线 {{ evaluations[d.id].baselineValue }} → 实际 {{ evaluations[d.id].actualValue }}
                  （{{ evaluations[d.id].evalWindowDays }} 天；基线 {{ evaluations[d.id].baselineWindow }} /
                  实际 {{ evaluations[d.id].actualWindow }}；逐日样本
                  {{ evaluations[d.id].baselineSamples }} + {{ evaluations[d.id].actualSamples }}）
                </div>
                <div class="table-hint">
                  来源 {{ evaluations[d.id].sourceId }} / 指标口径 {{ evaluations[d.id].metricDefinitionVersion }}；
                  {{ evaluations[d.id].note }}
                </div>
              </span>
              <span v-else>—</span>
            </td>
            <td class="nowrap">
              <button class="btn btn-sm btn-primary" v-if="d.status === 'DRAFT'" @click="submitDecision(d)" :disabled="loading || busy">提交审核</button>
              <button class="btn btn-sm btn-success" v-if="d.status === 'PENDING_REVIEW'" @click="approve(d)" :disabled="loading || busy">批准</button>
              <button class="btn btn-sm btn-danger" v-if="d.status === 'PENDING_REVIEW'" @click="rejectDecision(d)" :disabled="loading || busy">驳回</button>
              <button class="btn btn-sm" v-if="d.status === 'APPROVED'" @click="act(d, 'start')" :disabled="loading || busy">开始</button>
              <button class="btn btn-sm" v-if="d.status === 'IN_PROGRESS'" @click="act(d, 'complete')" :disabled="loading || busy">完成</button>
              <button class="btn btn-sm btn-danger" v-if="d.status === 'IN_PROGRESS'" @click="cancelDecision(d)" :disabled="loading || busy">取消</button>
              <button class="btn btn-sm btn-ai" v-if="d.status === 'COMPLETED'" @click="evaluate(d)" :disabled="loading || busy">评价</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="state !== 'loading' && state !== 'error'" class="el-empty">暂无决策</div>

      <div v-if="evaluationError" class="table-hint table-hint-error">部分决策的评价读取失败：{{ evaluationError }}</div>
    </div>

    <div class="chart-box" style="font-size:13px;color:var(--gray-500);line-height:1.8">
      <b>口径说明：</b>AI 只能创建 DRAFT（§22.6）；从 PENDING_REVIEW 到 APPROVED 必须人工；
      批准时冻结完整基线窗口的来源、口径及逐日快照；目前只对已验证的可加总日指标评价。
      比率、UV/DAU、复购率、客单价等缺少窗口公式时显示数据不足，不直接按日值平均或求和；
      评价结果仅为前后变化，非因果推断（§21.10）。
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import { useAnalysis } from '../composables/useAnalysis'
import { buildFallbackContext, NON_ANALYSIS_ROW_KEYS } from '../utils/context'
import { COLUMNS, decisionRows as mapDecisionRows, evaluationRows as mapEvaluationRows } from '../utils/tables'
import { exportAnalysisCsv } from '../utils/exportCsv'

const evaluations = ref({})
const evaluationError = ref('')
const actionError = ref('')
const busy = ref(false)
let decisionFetchSeq = 0

const isAbort = (e) => Boolean(e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError'))

// 决策列表 + 每个已评价决策的效果（都返回裸数组，非统一信封）
async function fetchDecisions(params, signal) {
  const mySeq = ++decisionFetchSeq
  const raw = await api.decisions(20, { signal })
  const list = Array.isArray(raw) ? raw : []
  const evalMap = {}
  const failed = []
  const decided = list.filter((d) => d && ['EFFECTIVE', 'PARTIAL', 'INEFFECTIVE', 'INSUFFICIENT_DATA'].includes(d.status))
  // 逐个取评价：单个失败只影响该行，但要显式记录失败原因，不静默
  for (const d of decided) {
    try {
      const evals = await api.decisionEvaluations(d.id, { signal })
      const first = Array.isArray(evals) ? evals[0] : null
      if (first) evalMap[d.id] = mapEvaluationRows([first])[0]
    } catch (e) {
      if (!isAbort(e)) failed.push(`${d.decisionNo || d.id}: ${(e && (e.message || e.code)) || '读取失败'}`)
    }
  }
  if (mySeq === decisionFetchSeq) {
    evaluations.value = evalMap
    evaluationError.value = failed.join('；')
  }

  const ctx = buildFallbackContext({ rows: list, warnings: ['ENVELOPE_MISSING'] })
  return {
    snapshotId: ctx.snapshotId,
    businessTime: ctx.businessTime,
    dataUpdatedAt: ctx.dataUpdatedAt,
    source: ctx.source,
    definitionVersion: ctx.definitionVersion,
    qualityStatus: ctx.qualityStatus,
    filters: ctx.filters,
    warnings: ctx.warnings,
    missingNotice: ctx.missingNotice,
    data: { decisions: list }
  }
}

const analysis = useAnalysis({ fetcher: fetchDecisions, rowKeys: NON_ANALYSIS_ROW_KEYS.decisions })
const { data, state, loading, error, exportable, statusText, exportContext, load, cancel } = analysis

const rows = computed(() => mapDecisionRows(data.value.decisions))

const statusColor = (s) => {
  const map = { EFFECTIVE: 'var(--success)', PARTIAL: '#d97706', INEFFECTIVE: '#dc2626',
    INSUFFICIENT_DATA: 'var(--gray-500)', REJECTED: '#dc2626', CANCELLED: 'var(--gray-500)' }
  return map[s] || 'var(--gray-800)'
}

// 状态徽标（纯展示映射，不改变任何状态判定）
const statusBadge = (s) => {
  const map = {
    EFFECTIVE: 'badge-success', PARTIAL: 'badge-warning', INEFFECTIVE: 'badge-danger',
    INSUFFICIENT_DATA: 'badge-neutral', REJECTED: 'badge-danger', CANCELLED: 'badge-neutral',
    DRAFT: 'badge-neutral', PENDING_REVIEW: 'badge-warning', APPROVED: 'badge-brand',
    IN_PROGRESS: 'badge-info', COMPLETED: 'badge-ai'
  }
  return map[s] || 'badge-neutral'
}

function refresh() {
  if (loading.value || busy.value) return
  return load({})
}

async function flush() {
  await load({})
}

function exportDecisions() {
  if (!exportable.value) return
  const generatedAt = new Date().toISOString()
  exportAnalysisCsv({
    baseName: 'decisions',
    context: exportContext.value,
    generatedAt,
    headers: COLUMNS.decisions.map((c) => c.label),
    rows: rows.value.map((r) => COLUMNS.decisions.map((c) => r[c.key]))
  })
}

async function act(d, action) {
  if (busy.value || loading.value) return
  busy.value = true
  actionError.value = ''
  try {
    await api.decisionAction(d.id, action, {})
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '操作失败'
  } finally {
    busy.value = false
  }
}

function requiredReason(actionLabel) {
  const raw = prompt(`${actionLabel}原因（必填）`, '')
  if (raw === null) return null
  const reason = raw.trim()
  if (!reason) {
    actionError.value = `${actionLabel}原因不能为空`
    return null
  }
  return reason
}

function requiredApprovalText(promptText, emptyMessage, initialValue = '') {
  const raw = prompt(promptText, initialValue)
  if (raw === null) return null
  const value = raw.trim()
  if (!value) {
    actionError.value = emptyMessage
    return null
  }
  return value
}

function requiredDueDate() {
  const dueDate = requiredApprovalText('截止日期（必填，格式 YYYY-MM-DD）', '截止日期不能为空')
  if (!dueDate) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dueDate)
  if (!match) {
    actionError.value = '截止日期格式必须为 YYYY-MM-DD'
    return null
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) {
    actionError.value = '截止日期不是有效日历日期'
    return null
  }
  return dueDate
}

async function submitDecision(d) {
  if (busy.value || loading.value) return
  actionError.value = ''
  const currentOwner = d && d.owner && d.owner !== '—' ? String(d.owner).trim() : ''
  const owner = requiredApprovalText('负责人（提交审核前必填）', '负责人不能为空', currentOwner)
  if (!owner) return
  busy.value = true
  try {
    await api.decisionAction(d.id, 'submit', { owner })
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '提交审核失败'
  } finally {
    busy.value = false
  }
}

async function approve(d) {
  if (busy.value || loading.value) return
  actionError.value = ''
  const owner = requiredApprovalText('负责人（必填，例如：运营-小李）', '负责人不能为空')
  if (!owner) return
  const dueDate = requiredDueDate()
  if (!dueDate) return
  busy.value = true
  try {
    await api.decisionAction(d.id, 'approve', { owner, dueDate })
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '批准失败'
  } finally {
    busy.value = false
  }
}

async function rejectDecision(d) {
  if (busy.value || loading.value) return
  const reason = requiredReason('驳回')
  if (!reason) return
  busy.value = true
  actionError.value = ''
  try {
    await api.decisionAction(d.id, 'reject', { reason })
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '驳回失败'
  } finally {
    busy.value = false
  }
}

async function cancelDecision(d) {
  if (busy.value || loading.value) return
  const reason = requiredReason('取消')
  if (!reason) return
  busy.value = true
  actionError.value = ''
  try {
    await api.decisionAction(d.id, 'cancel', { reason })
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '取消失败'
  } finally {
    busy.value = false
  }
}

async function evaluate(d) {
  if (busy.value || loading.value) return
  busy.value = true
  actionError.value = ''
  try {
    const result = await api.decisionAction(d.id, 'evaluate', {})
    await flush()
    actionError.value = ''
    // 评价结果直接落表格，不再弹窗阻断
    if (result && result.result) evaluations.value = { ...evaluations.value, [d.id]: mapEvaluationRows([result])[0] }
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '评价失败'
  } finally {
    busy.value = false
  }
}

onMounted(refresh)

onUnmounted(() => {
  decisionFetchSeq += 1
  cancel()
})
</script>
