<template>
  <div>
    <div class="page-title">决策中心</div>

    <!-- /decisions 不是统一信封：快照上下文由决策行的 suggestionSnapshotId 汇总，
         业务时间/数据更新时间/质量状态不在此接口返回内，逐项标注「接口未提供」。 -->
    <div class="chart-box">
      <div class="chart-title">
        决策数据上下文
        <button style="float:right;font-size:12px;padding:3px 10px" @click="load" :disabled="loading">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <AnalysisContext :context="exportContext" :state="state" :error="error" />
      <div class="table-hint">
        说明：批准决策时会锁定当次快照基线，该快照号记录在决策行的「建议快照」字段；
        多个决策可能来自不同快照，因此上方快照栏在存在多个值时逐个列出，不合并成单一快照。
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        决策列表（AI 草稿 → 人工审核 → 执行 → 效果评价）
        <button style="float:right;font-size:12px;padding:3px 10px"
                :disabled="!exportable" @click="exportDecisions">
          {{ exportable ? '导出决策 CSV' : '导出（' + statusText + '）' }}
        </button>
      </div>

      <div v-if="state === 'loading'" class="state-line">决策列表加载中…</div>
      <div v-else-if="state === 'error'" class="banner banner-error">决策列表加载失败：{{ error || '未知原因' }}</div>
      <div v-else-if="state === 'stale'" class="banner banner-stale">数据更新中：当前展示的仍是上一次结果，已禁止导出。</div>
      <div v-else-if="state === 'empty'" class="el-empty">暂无决策</div>

      <div v-if="actionError" class="banner banner-error">操作失败：{{ actionError }}</div>

      <table v-if="rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">编号</th><th>标题</th><th>来源</th><th>目标指标</th><th>基线</th>
          <th>建议快照</th><th>负责人</th><th>状态</th><th>效果</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="d in rows" :key="d.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px" class="mono">{{ d.decisionNo }}</td>
            <td>{{ d.title }}</td>
            <td>{{ d.source || '—' }}</td>
            <td>{{ d.targetMetricCode || '—' }} <span v-if="d.targetDirection">({{ d.targetDirection }})</span></td>
            <td class="mono">{{ formatNumber(d.baselineValue) }}</td>
            <td class="mono">{{ d.suggestionSnapshotId || '—' }}</td>
            <td>{{ d.owner || '—' }}</td>
            <td><span :style="{ color: statusColor(d.status), fontWeight: 600 }">{{ d.status }}</span></td>
            <td>
              <span v-if="evaluations[d.id]">
                {{ evaluations[d.id].result }} ({{ evaluations[d.id].improvementRate }})
                <div class="table-hint mono">
                  基线 {{ evaluations[d.id].baselineValue }} → 实际 {{ evaluations[d.id].actualValue }}
                  （窗口 {{ evaluations[d.id].evalWindowDays }} 天）
                </div>
              </span>
              <span v-else>—</span>
            </td>
            <td style="white-space:nowrap">
              <button v-if="d.status === 'DRAFT'" @click="act(d, 'submit')" :disabled="busy">提交审核</button>
              <button v-if="d.status === 'PENDING_REVIEW'" style="background:#16a34a" @click="approve(d)" :disabled="busy">批准</button>
              <button v-if="d.status === 'PENDING_REVIEW'" style="background:#dc2626" @click="rejectDecision(d)" :disabled="busy">驳回</button>
              <button v-if="d.status === 'APPROVED'" @click="act(d, 'start')" :disabled="busy">开始</button>
              <button v-if="d.status === 'IN_PROGRESS'" @click="act(d, 'complete')" :disabled="busy">完成</button>
              <button v-if="d.status === 'IN_PROGRESS'" style="background:#dc2626" @click="cancelDecision(d)" :disabled="busy">取消</button>
              <button v-if="d.status === 'COMPLETED'" style="background:#7c3aed" @click="evaluate(d)" :disabled="busy">评价</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="state !== 'loading' && state !== 'error'" class="el-empty">暂无决策</div>

      <div v-if="evaluationError" class="table-hint table-hint-error">部分决策的评价读取失败：{{ evaluationError }}</div>
    </div>

    <div class="chart-box" style="font-size:13px;color:#6b7280;line-height:1.8">
      <b>口径说明：</b>AI 只能创建 DRAFT（§22.6）；从 PENDING_REVIEW 到 APPROVED 必须人工；
      批准时锁定当前快照基线与目标指标；效果 = (实际−基线)/|基线|，"越低越好"指标（退款率等）取反；
      评价结果仅为前后对比，非因果推断（§21.10）。
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import { useAnalysis } from '../composables/useAnalysis'
import { buildFallbackContext, NON_ANALYSIS_ROW_KEYS } from '../utils/context'
import { formatNumber } from '../utils/number'
import { COLUMNS, decisionRows as mapDecisionRows, evaluationRows as mapEvaluationRows } from '../utils/tables'
import { exportAnalysisCsv } from '../utils/exportCsv'

const evaluations = ref({})
const evaluationError = ref('')
const actionError = ref('')
const busy = ref(false)

const isAbort = (e) => Boolean(e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError'))

// 决策列表 + 每个已评价决策的效果（都返回裸数组，非统一信封）
async function fetchDecisions(params, signal) {
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
  evaluations.value = evalMap
  evaluationError.value = failed.join('；')

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
  const map = { EFFECTIVE: '#16a34a', PARTIAL: '#d97706', INEFFECTIVE: '#dc2626',
    INSUFFICIENT_DATA: '#6b7280', REJECTED: '#dc2626', CANCELLED: '#6b7280' }
  return map[s] || '#111827'
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

async function approve(d) {
  const owner = prompt('负责人（如：运营-小李）', '运营-小李')
  if (!owner) return
  busy.value = true
  actionError.value = ''
  try {
    await api.decisionAction(d.id, 'approve', {
      owner,
      dueDate: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10)
    })
    await flush()
  } catch (e) {
    actionError.value = (e && (e.message || e.code)) || '批准失败'
  } finally {
    busy.value = false
  }
}

async function rejectDecision(d) {
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

onMounted(() => {
  load({})
})

onUnmounted(cancel)
</script>