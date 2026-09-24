<template>
  <div>
    <div class="page-title">智能分析助手</div>

    <!-- AI 接口不是统一信封：证据包只提供快照/SQL/表/时间范围/提示词版本，
         其余上下文一律标注「接口未提供」，不编造、不 Mock。 -->
    <div class="chart-box">
      <div class="chart-title">问答数据上下文（取当期 ACTIVE 快照作对照）</div>
      <AnalysisContext :context="baseContext" :state="state" :error="error" />
      <div class="table-hint">
        说明：/dashboards/overview 提供当期快照、业务时间、口径版本与质量状态，用作对照基线；
        AI 问答结果自带证据包（快照、SQL、使用表、时间范围、提示词版本），二者的快照号应一致，
        若不一致说明问答把快照 pin 在了另一个版本上，需复核。
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">自然语言问数（语义层 → 受控 Text-to-SQL → 安全校验 → 证据解释）</div>
      <div class="chat-input-bar">
        <input class="input" v-model="question" @keyup.enter="ask" placeholder="例如：最近 7 天销售额变化趋势如何？"
               :disabled="busy || draftBusy" />
        <button class="btn btn-ai" @click="handleAskAction" :disabled="draftBusy || (!busy && !question.trim())">
          {{ busy ? '放弃本次分析' : (draftBusy ? '草稿创建中…' : '发送') }}
        </button>
      </div>
      <div v-if="busy" class="state-line">
        执行状态：理解问题 → 生成 SQL → 安全校验 → 查询数据 → 生成解释…
      </div>
      <div v-if="queryError" class="alert alert-danger" style="margin-top:10px">请求失败：{{ queryError }}</div>
      <div v-if="abortedNotice" class="alert alert-info" style="margin-top:10px">{{ abortedNotice }}</div>
    </div>

    <template v-if="queryResult">
      <div class="chart-box">
        <div class="chart-title">
          结论
          <button class="btn btn-sm" style="float:right"
                  :disabled="!canExportResult" @click="exportResult">
            {{ exportButtonText }}
          </button>
        </div>
        <div class="answer-card">{{ evidenceContext.summary || '（后端未给出结论文本）' }}</div>
        <div v-if="query.status" class="table-hint">
          管道状态：{{ query.status }}（模型：{{ query.providerUsed || '未提供' }}，行数：{{ query.rowsReturned ?? 0 }}，
          耗时：{{ query.elapsedMs ?? '未提供' }}ms）
        </div>
        <div v-if="query.assumptions && query.assumptions.length" class="table-hint">
          模型假设：{{ query.assumptions.join('；') }}
        </div>
        <div v-if="query.error" class="banner banner-error">后端返回错误：{{ query.error }}</div>
      </div>

      <div class="chart-box">
        <div class="chart-title">数据依据（真实查询结果，未做前端重算）</div>
        <table v-if="resultTable.rows.length" class="data-table">
          <thead><tr style="text-align:left;color:var(--gray-500)">
            <th v-for="k in resultTable.headers" :key="k" style="padding:6px">{{ k }}</th>
          </tr></thead>
          <tbody>
            <tr v-for="(row, i) in resultTable.rows" :key="i" style="border-top:1px solid var(--gray-100)">
              <td v-for="(v, j) in row" :key="j" style="padding:6px" class="mono">{{ v }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">当前时间范围无数据，不编造结论</div>
      </div>

      <div class="chart-box" v-if="explanationSection.length">
        <div class="chart-title">建议与可能原因（模型推测，非因果结论）</div>
        <div v-for="(s, i) in explanationSection" :key="i" style="font-size:13px;line-height:1.8;padding:4px 0">
          • {{ s }}
        </div>
      </div>

      <!-- S3-42：AI 建议 → 决策草稿。字段口径的唯一属主是 utils/decisionDraft.js：
           页面只搬运后端已给的字段（不猜方向/指标），锚点缺失就不让创建，
           提交审批的齐全性由服务端提交校验判定，页面只转述错误原文。 -->
      <div class="chart-box" v-if="explanation.suggestions && explanation.suggestions.length">
        <div class="chart-title">核查建议 → 决策草稿（先草稿后审批，AI 不执行商业动作）</div>
        <div class="table-hint">
          证据锚点：<b class="mono">{{ anchorLabel }}</b>
        </div>
        <div v-for="s in draftSuggestions(explanation.suggestions)" :key="s.index"
             style="padding:6px 0;font-size:13px;border-top:1px solid var(--gray-100)">
          <div style="line-height:1.7">{{ s.action }}</div>
          <div style="display:flex;gap:10px;align-items:center;margin-top:4px">
            <button :disabled="!canCreateDraft || draftBusy" @click="openDraft(s)"
                    style="padding:3px 10px;font-size:12px">转决策草稿</button>
            <span v-if="!canCreateDraft" class="meta-hint">{{ blockedText(DRAFT_BLOCK.NO_EVIDENCE_ANCHOR) }}</span>
            <span v-else-if="!s.targetMetricCode" class="meta-hint">目标指标：接口未提供（留空，不猜）</span>
          </div>
        </div>
      </div>

      <div class="chart-box" v-if="draftForm">
        <div class="chart-title">新建决策草稿（内容取自本条建议，可修改；创建后到决策中心提交审批）</div>
        <div v-if="draftError" class="banner banner-error">{{ draftError }}</div>
        <div v-if="draftCreated" class="banner banner-stale">
          草稿已创建：<b class="mono">{{ draftCreated.decisionNo || draftCreated.id || '未提供' }}</b>
          （状态 {{ draftCreated.status || '未提供' }}）。请到
          <router-link to="/decisions">决策中心</router-link> 提交审批；AI 侧不执行商业动作。
        </div>
        <template v-else>
          <div style="display:flex;flex-direction:column;gap:8px;max-width:760px;font-size:13px">
            <label>标题<input v-model="draftForm.title" :disabled="draftBusy" style="width:100%;padding:6px" /></label>
            <label>动作<textarea v-model="draftForm.action" rows="2" :disabled="draftBusy" style="width:100%;padding:6px"></textarea></label>
            <label>目标指标<input v-model="draftForm.metricCode" placeholder="接口未提供时留空，不猜"
                              :disabled="draftBusy" style="width:100%;padding:6px" /></label>
            <label>目标方向（必选，页面不代选）
              <select v-model="draftForm.direction" :disabled="draftBusy" style="padding:6px">
                <option v-for="c in DIRECTION_CHOICES" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
            </label>
            <label>负责人（可留空，提交审批前必填）<input v-model="draftForm.owner" :disabled="draftBusy" style="width:100%;padding:6px" /></label>
          </div>
          <div class="table-hint">{{ SUBMIT_REQUIREMENT_TEXT }}</div>
          <div class="table-hint">将提交：<span class="mono">{{ draftPreview }}</span></div>
          <div style="margin-top:10px;display:flex;gap:8px">
            <button :disabled="draftBusy" @click="createDraft"
                    style="padding:6px 16px;background:var(--ai);color:#fff;border:none;border-radius:6px">
              {{ draftBusy ? '创建中…' : '创建草稿' }}
            </button>
            <button :disabled="draftBusy" @click="closeDraft" style="padding:6px 16px">取消</button>
          </div>
        </template>
      </div>

      <div class="chart-box">
        <div class="chart-title">证据（SQL + 口径）</div>
        <pre class="evidence-box">{{ evidence.sql || '（后端未返回 SQL）' }}</pre>
        <div class="meta-row" style="margin-top:8px">
          <span class="meta-item">
            证据快照
            <b class="mono">{{ evidence.snapshotText }}</b>
          </span>
          <span class="meta-item meta-hint" v-if="evidence.snapshotHint">{{ evidence.snapshotHint }}</span>
          <span class="meta-item">证据包 ID <b class="mono">{{ evidence.evidenceId || '未提供' }}</b></span>
          <span class="meta-item">时间范围 <b class="mono">{{ evidenceWindowText }}</b></span>
          <span class="meta-item" v-if="evidence.window && evidence.window.referenceBusinessDate">
            参考业务日 <b class="mono">{{ evidence.window.referenceBusinessDate }}</b>
          </span>
          <span class="meta-item">提示词版本 <b class="mono">{{ evidence.promptVersion || '未提供' }}</b></span>
          <span class="meta-item">返回行数 <b class="mono">{{ evidence.returnedRows }}</b></span>
          <span class="meta-item">查询耗时 <b class="mono">{{ evidence.queryElapsedMs === null ? '未提供' : evidence.queryElapsedMs + 'ms' }}</b></span>
          <span class="meta-item">
            对照大盘快照 <b class="mono">{{ baseContext ? (baseContext.snapshotId || '无') : '加载中' }}</b>
          </span>
        </div>
        <div class="table-hint">使用表：{{ evidence.tables.length ? evidence.tables.join(', ') : '未提供' }}</div>
        <div v-if="evidenceContext.missingNotice" class="banner banner-missing">{{ evidenceContext.missingNotice }}</div>
        <div v-if="evidenceContext.warnings.length" class="banner banner-warn">
          证据包告警：{{ evidenceContext.warnings.map(warningTextAll).join('；') }}
        </div>
        <div v-if="explanation.limitations && explanation.limitations.length" class="table-hint">
          限制说明：{{ explanation.limitations.join('；') }}
        </div>
      </div>
    </template>

    <div class="chart-box">
      <div class="chart-title">推荐问题</div>
      <div style="display:flex;flex-wrap:wrap;gap:8px">
        <button v-for="q in recommended" :key="q" @click="askPreset(q)" :disabled="busy || draftBusy"
                style="padding:6px 12px;border:1px solid #e5e7eb;background:#fff;border-radius:16px;font-size:13px;cursor:pointer">
          {{ q }}
        </button>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">我的最近问答（点击回填；来自 /ai/history/my，非统一信封）</div>
      <div v-if="historyError" class="banner banner-error">历史加载失败：{{ historyError }}</div>
      <div v-if="history.length" style="display:flex;flex-direction:column;gap:6px">
        <button v-for="h in history" :key="h.id" @click="question = h.question" :disabled="busy || draftBusy"
                style="text-align:left;padding:6px 10px;border:1px solid var(--gray-100);background:#fafafa;border-radius:6px;font-size:13px;cursor:pointer">
          <span style="color:var(--gray-800)">{{ h.question }}</span>
          <span style="float:right;color:var(--gray-400);font-size:12px">
            {{ h.status }} · {{ h.rowsReturned ?? 0 }} 行 · {{ formatDateTime(h.createdAt) }}
          </span>
        </button>
      </div>
      <div v-else class="el-empty">暂无历史记录</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import { useAnalysis } from '../composables/useAnalysis'
import { ENDPOINT_ROW_KEYS } from '../utils/chartState'
import { buildAiEvidenceContext, warningTextAll, windowDisplayText } from '../utils/context'
import {
  ANCHOR_KIND,
  DIRECTION_CHOICES,
  DRAFT_BLOCK,
  SUBMIT_REQUIREMENT_TEXT,
  anchorText,
  buildDraftBody,
  draftAnchor,
  draftSuggestions
} from '../utils/decisionDraft'
import { formatDateTime } from '../utils/envelope'
import { aiResultCsvHeaders, aiResultTable } from '../utils/tables'
import { exportAnalysisCsv } from '../utils/exportCsv'

const question = ref('')
const busy = ref(false)
const queryError = ref('')
const abortedNotice = ref('')
const queryResult = ref(null)

// 对照基线：当期 ACTIVE 快照的分析信封（供上下文条展示快照/业务时间/口径版本/质量状态与告警）
const base = useAnalysis({ fetcher: (params, signal) => api.overview({}, { signal }), rowKeys: ENDPOINT_ROW_KEYS.overview })
const { context: baseContext, state, error, load: loadBase, cancel: cancelBase } = base

// 历史记录：/ai/history/my 返回裸数组，失败必须显式提示（不再静默）
const history = ref([])
const historyError = ref('')
let historySeq = 0
let historyController = null

// 提问请求序号守卫：显式取消时递增序号，任何旧请求随后完成都必须被丢弃
let askSeq = 0
let askController = null
const isAbort = (e) => Boolean(e && (e.code === 'ERR_CANCELED' || e.name === 'CanceledError' || e.name === 'AbortError'))

const recommended = [
  '最近 7 天销售额变化趋势如何？',
  '最近一天的销售额是否明显偏离近 7 日平均水平？',
  '9月4日转化漏斗各阶段人数是多少？',
  '最新一期的 GMV 和退款率是多少？'
]

const query = computed(() => (queryResult.value && queryResult.value.query) || {})
const explanation = computed(() => (queryResult.value && queryResult.value.explanation) || {})
const evidenceContext = computed(() => buildAiEvidenceContext(queryResult.value))
const evidence = computed(() => evidenceContext.value.evidence || {})

// QA-03：快照身份的唯一归一化在 utils/context.js。页面**只**渲染它给出的文本与提示，
// 不再自己判断 isRealSnapshotId，也不推断裂回退分支的 SQL 怎么锁快照（曾错误宣称 MAX(snapshot_id)）。
// QA-04：有效查询期以响应里的结构化 query.window 为唯一来源（经 context.js 搬运与降级），
// 无结构化窗口时才回落到证据自带文本，仍取不到就是「接口未提供」——绝不打印前端请求标签。
const evidenceWindowText = computed(() => windowDisplayText(evidence.value.window, evidence.value.timeRange))
const resultTable = computed(() => aiResultTable(query.value.rows))

// ── S3-42：AI 建议 → 决策草稿（字段口径的唯一属主是 utils/decisionDraft.js）──
const draftForm = ref(null)
const draftBusy = ref(false)
const draftError = ref('')
const draftCreated = ref(null)

// 证据锚点：优先顶层证据包 ID，其次归一化后的证据快照号（QA-03 起二者同源）；
// 两者皆无 ⇒ canCreateDraft=false，入口禁用
const evidenceAnchor = computed(() => draftAnchor({
  evidenceId: evidence.value.evidenceId,
  snapshotId: evidence.value.snapshotId
}))
const canCreateDraft = computed(() => evidenceAnchor.value.kind !== ANCHOR_KIND.NONE)
const anchorLabel = computed(() => anchorText(evidenceAnchor.value))

const DRAFT_BLOCKED_TEXT = {
  [DRAFT_BLOCK.SUGGESTION_INCOMPLETE]: '这条建议缺标题或动作，AI 侧没有给出可执行文案，无法创建草稿（不替模型补文案）。',
  [DRAFT_BLOCK.NO_EVIDENCE_ANCHOR]: '本次问答没有可用的证据锚点（既无证据包 ID，快照号也是占位值），不能创建草稿。'
}
const blockedText = (code) => DRAFT_BLOCKED_TEXT[code] || '无法创建草稿'

// 请求体由属主构造：页面只把「已展示的字段 ＋ 员工填的方向/负责人」交给它
const draftPayload = computed(() => buildDraftBody({
  suggestion: draftForm.value,
  evidenceId: evidence.value.evidenceId,
  snapshotId: evidence.value.snapshotId,
  direction: draftForm.value ? draftForm.value.direction : '',
  owner: draftForm.value ? draftForm.value.owner : ''
}))
const draftPreview = computed(() => (draftPayload.value.ok
  ? JSON.stringify(draftPayload.value.body)
  : blockedText(draftPayload.value.blocked)))

function openDraft(s) {
  if (!canCreateDraft.value || draftBusy.value) return
  draftError.value = ''
  draftCreated.value = null
  // 后端模板分支的 targetMetricCode 恒为 null ⇒ 页面留空并标注「接口未提供」，不猜指标编码
  draftForm.value = { title: s.title, action: s.action, metricCode: s.targetMetricCode || '', direction: '', owner: '' }
}

function closeDraft() {
  if (draftBusy.value) return
  draftForm.value = null
  draftCreated.value = null
  draftError.value = ''
}

async function createDraft() {
  if (draftBusy.value) return
  const payload = draftPayload.value
  draftError.value = ''
  if (!payload.ok) {
    draftError.value = blockedText(payload.blocked)
    return
  }
  draftBusy.value = true
  try {
    // 服务端固定 source=ai / 状态 DRAFT；成功只回显服务端返回的编号，前端不改状态
    draftCreated.value = (await api.decisionCreate(payload.body)) || {}
  } catch (e) {
    draftError.value = (e && (e.message || e.code)) || '创建草稿失败'
  } finally {
    draftBusy.value = false
  }
}
const explanationSection = computed(() => {
  const out = []
  ;(explanation.value.possibleCauses || []).forEach((p) => out.push('可能原因：' + p.statement))
  ;(explanation.value.suggestions || []).forEach((s) => out.push('建议：' + s.title + ' — ' + s.action))
  return out
})

// 导出条件：本次问答已成功返回且结果表非空；失败/无结果时禁止导出
const canExportResult = computed(() => Boolean(queryResult.value) && resultTable.value.rows.length > 0)
const exportButtonText = computed(() => (canExportResult.value ? '导出本次查询 CSV' : '导出（暂无可导出结果）'))

async function loadHistory() {
  const mySeq = ++historySeq
  if (historyController) historyController.abort()
  historyController = typeof AbortController === 'function' ? new AbortController() : null
  historyError.value = ''
  try {
    const rows = await api.aiHistoryMine(8, historyController ? { signal: historyController.signal } : undefined)
    if (mySeq !== historySeq) return
    history.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    if (mySeq !== historySeq) return
    if (!isAbort(e)) historyError.value = (e && (e.message || e.code)) || '请求失败'
  } finally {
    if (mySeq === historySeq) historyController = null
  }
}

function cancelAsk() {
  if (!busy.value) return
  askSeq += 1
  const controller = askController
  askController = null
  if (controller) controller.abort()
  busy.value = false
  queryError.value = ''
  queryResult.value = null
  closeDraft()
  abortedNotice.value = '本次提问已取消，结果不会展示。'
}

function handleAskAction() {
  if (draftBusy.value) return
  if (busy.value) {
    cancelAsk()
    return
  }
  ask()
}

async function ask() {
  const text = question.value.trim()
  if (!text || busy.value || draftBusy.value) return
  const mySeq = ++askSeq
  if (askController) askController.abort()
  askController = typeof AbortController === 'function' ? new AbortController() : null
  busy.value = true
  queryError.value = ''
  abortedNotice.value = ''
  queryResult.value = null
  closeDraft()
  try {
    // QA-04：不再下发任何时间范围标签——问题原文由后端解析成结构化 window，
    // 页面只展示响应里的 query.window，避免「问题/SQL/解释」三个口径互相打架。
    const ctl = askController
    const resp = await api.aiQuery(text, undefined, ctl ? { signal: ctl.signal } : undefined)
    if (mySeq !== askSeq) return // 已被显式取消或已有更新序号，丢弃过期响应
    queryResult.value = resp
    await loadHistory()
  } catch (e) {
    if (mySeq !== askSeq) return
    if (isAbort(e)) {
      abortedNotice.value = '本次提问已取消，结果不会展示。'
      return
    }
    queryError.value = (e && (e.message || e.code)) || '请求失败'
  } finally {
    if (mySeq === askSeq) {
      busy.value = false
      askController = null
    }
  }
}

function askPreset(q) {
  if (busy.value || draftBusy.value) return
  question.value = q
  ask()
}

function exportResult() {
  if (!canExportResult.value) return
  const generatedAt = new Date().toISOString()
  const rows = query.value.rows
  exportAnalysisCsv({
    baseName: 'ai-query-result',
    context: evidenceContext.value,
    generatedAt,
    // 导出表头带原始字段名，方便与证据里的 SQL 列一一对照
    headers: aiResultCsvHeaders(rows),
    rows: resultTable.value.rows
  })
}

onMounted(() => {
  loadBase({})
  loadHistory()
})

onUnmounted(() => {
  cancelBase()
  askSeq += 1
  if (askController) askController.abort()
  historySeq += 1
  if (historyController) historyController.abort()
  historyController = null
})
</script>