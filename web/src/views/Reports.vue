<template>
  <div class="reports-page">
    <div class="page-title">业务报告</div>

    <section class="chart-box">
      <div class="chart-title">生成业务报告</div>
      <p class="table-hint">
        报告只读取固定快照或已保存决策评价；决策效果仅复述前后窗口，不能证明行动造成变化。周报覆盖快照业务日及此前六日。
      </p>
      <form class="report-form" @submit.prevent="createReport">
        <label for="report-template">报告模板</label>
        <select id="report-template" v-model="selectedTemplateCode" :disabled="creating">
          <option v-for="item in templates.filter((entry) => entry.ready)" :key="item.code" :value="item.code">
            {{ item.name }}
          </option>
        </select>
        <template v-if="selectedTemplateCode === 'DECISION_EFFECT'">
          <label for="report-decision">已评价决策</label>
          <select id="report-decision" v-model="selectedDecisionId" :disabled="decisionLoading || creating">
            <option value="">请选择已完成并评价的决策</option>
            <option v-for="item in decisionOptions" :key="item.id" :value="String(item.id)">
              {{ item.decisionNo || `#${item.id}` }} · {{ item.title }} · {{ item.status }}
            </option>
          </select>
          <button v-if="decisionPage < decisionTotalPages" class="btn btn-sm" type="button" :disabled="decisionLoading" @click="loadDecisionOptions(decisionPage + 1, true)">
            {{ decisionLoading ? '读取中…' : '加载更多决策' }}
          </button>
        </template>
        <label v-if="selectedTemplateCode !== 'DECISION_EFFECT'" for="report-snapshot">数据快照</label>
        <select v-if="selectedTemplateCode !== 'DECISION_EFFECT'" id="report-snapshot" v-model="selectedSnapshotId" :disabled="snapshotsLoading || creating">
          <option value="">请选择已发布快照</option>
          <option v-for="item in snapshotSelection.options" :key="item.snapshotId" :value="item.snapshotId">
            {{ item.label }}
          </option>
        </select>
        <template v-if="selectedTemplateCode !== 'DECISION_EFFECT'">
          <label for="report-business-date">业务日</label>
          <input id="report-business-date" :value="selectedChoice?.businessDate || ''" type="date" readonly />
        </template>
        <template v-if="selectedTemplateCode === 'OPS_WEEKLY'">
          <label for="report-week-from">周报起始日</label>
          <input id="report-week-from" :value="weeklyFromDate" type="date" readonly />
          <label for="report-week-to">周报结束日</label>
          <input id="report-week-to" :value="selectedChoice?.businessDate || ''" type="date" readonly />
        </template>
        <button class="btn btn-primary" type="submit" :disabled="(selectedTemplateCode === 'DECISION_EFFECT' ? !selectedDecisionId : !selectedChoice) || creating">
          {{ creating ? '生成中…' : `生成${templateName(selectedTemplateCode)}` }}
        </button>
      </form>
      <div v-if="snapshotError" class="banner banner-error" role="alert">读取快照失败：{{ snapshotError }}</div>
      <div v-if="decisionError" class="banner banner-error" role="alert">读取决策失败：{{ decisionError }}</div>
      <div v-if="selectedTemplateCode === 'DECISION_EFFECT' && !decisionLoading && !decisionError && !decisionOptions.length" class="banner banner-stale" role="status">
        当前已读取范围内没有已完成并评价的任务；可加载更多历史决策。
      </div>
      <div v-else-if="selectedTemplateCode !== 'DECISION_EFFECT' && !snapshotsLoading && !snapshotSelection.options.length" class="banner banner-stale" role="status">
        暂无可用的已发布快照，当前不能生成报告。
      </div>
      <div v-if="createError" class="banner banner-error" role="alert">报告生成失败：{{ createError }}</div>
      <div v-if="createMessage" class="banner banner-success" role="status">{{ createMessage }}</div>
    </section>

    <section class="chart-box">
      <div class="chart-title">可用模板</div>
      <div class="template-grid">
        <article v-for="item in templates" :key="item.code" class="template-card" :class="{ ready: item.ready }">
          <b>{{ item.name }}</b>
          <code>{{ item.code }}</code>
          <span>{{ item.ready ? '可生成' : '证据口径尚未完成' }}</span>
        </article>
      </div>
      <p class="table-hint">AI 摘要未启用；真实 Provider 与数据外发条件仍受 D-039 控制。</p>
    </section>

    <section class="chart-box">
      <div class="chart-title">我的报告</div>
      <div class="report-toolbar">
        <span>共 {{ total }} 条 · 第 {{ total ? page : 0 }} / {{ totalPages }} 页</span>
        <label>排序
          <select v-model="sort" :disabled="loading" @change="changeSort">
            <option value="createdAt,desc">创建时间（新到旧）</option>
            <option value="createdAt,asc">创建时间（旧到新）</option>
            <option value="templateCode,asc">模板名称</option>
          </select>
        </label>
        <button class="btn btn-sm" :disabled="loading || page <= 1" @click="loadPage(page - 1)">上一页</button>
        <button class="btn btn-sm" :disabled="loading || page >= Math.max(totalPages, 1)" @click="loadPage(page + 1)">下一页</button>
        <button class="btn btn-sm" :disabled="loading" @click="loadPage(page)">刷新</button>
      </div>
      <div v-if="loading && reports.length === 0" class="state-line">正在读取报告…</div>
      <div v-else-if="listError" class="banner banner-error" role="alert">报告列表读取失败：{{ listError }}</div>
      <div v-else-if="!reports.length" class="el-empty">还没有报告</div>
      <div v-if="detailError && !selectedReport" class="banner banner-error" role="alert">报告详情读取失败：{{ detailError }}。可以重试或选择其他报告。</div>
      <table v-if="reports.length" class="data-table">
        <thead><tr><th>编号</th><th>模板</th><th>报告窗口</th><th>快照</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="report in reports" :key="report.id">
            <td class="mono">{{ report.id }}</td>
            <td>{{ templateName(report.templateCode) }} <code>{{ report.templateVersion }}</code></td>
            <td class="mono">{{ report.fromDate ? `${report.fromDate}${report.toDate && report.toDate !== report.fromDate ? ` — ${report.toDate}` : ''}` : '—' }}</td>
            <td class="mono">{{ report.snapshotId || '—' }}</td>
            <td><span class="badge badge-success">{{ report.status }}</span></td>
            <td class="mono">{{ report.createdAt || '—' }}</td>
            <td><button class="btn btn-sm" :disabled="detailLoading" @click="openReport(report)">查看证据</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="selectedReport" class="chart-box" aria-label="报告证据详情">
      <div class="chart-title">
        报告 #{{ selectedReport.report?.id }} · {{ templateName(selectedReport.report?.templateCode) }}
        <button class="btn btn-sm" style="float:right" :disabled="detailLoading" @click="loadArtifact">读取完整制品</button>
      </div>
      <div v-if="detailError" class="banner banner-error" role="alert">{{ detailError }}</div>
      <div v-else class="report-readable">
        <dl v-if="selectedReport.summary" class="report-facts">
          <div><dt>数据快照</dt><dd class="mono">{{ selectedReport.summary.snapshotId || '—' }}</dd></div>
          <div><dt>报告窗口</dt><dd>{{ windowLabel(selectedReport.report) }}</dd></div>
          <div><dt>质量门</dt><dd>{{ qualityLabel(selectedReport.summary.dataQuality?.gateStatus) }}</dd></div>
          <div><dt>已发布指标</dt><dd>{{ selectedReport.summary.factCount ?? '—' }}</dd></div>
          <div><dt>对比指标</dt><dd>{{ selectedReport.summary.comparisonCount ?? '—' }}</dd></div>
          <div><dt>警告</dt><dd>{{ selectedReport.summary.warningCount ?? 0 }}</dd></div>
        </dl>
        <section v-if="summaryEntries.length" class="report-section">
          <h3>报告摘要</h3>
          <dl class="report-summary-grid">
            <div v-for="entry in summaryEntries" :key="entry.key">
              <dt>{{ entry.label }}</dt><dd>{{ entry.value }}</dd>
            </div>
          </dl>
        </section>
        <section v-if="artifactFacts.length" class="report-section">
          <h3>指标证据</h3>
          <div class="report-table-scroll">
            <table class="data-table">
              <thead><tr><th>指标</th><th>数值</th><th>期间</th><th>来源证据</th></tr></thead>
              <tbody>
                <tr v-for="(fact, index) in artifactFacts" :key="`${fact.metricCode}-${index}`">
                  <td>{{ fact.metricName || fact.metricCode || '—' }} <code>{{ fact.unit || '' }}</code></td>
                  <td class="mono">{{ fact.value ?? '未提供' }}</td>
                  <td>{{ fact.period || '—' }}</td>
                  <td class="mono">{{ fact.evidenceRef || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section v-if="warningItems.length" class="report-section">
          <h3>数据提示</h3>
          <ul class="report-notes"><li v-for="warning in warningItems" :key="warning">{{ warning }}</li></ul>
        </section>
        <section v-if="limitationItems.length" class="report-section">
          <h3>解释边界</h3>
          <ul class="report-notes"><li v-for="limitation in limitationItems" :key="limitation">{{ limitation }}</li></ul>
        </section>
        <details v-if="artifact" class="report-raw">
          <summary>查看完整 JSON 制品</summary>
          <pre class="report-artifact">{{ JSON.stringify(artifact, null, 2) }}</pre>
        </details>
        <p v-else class="table-hint">完整指标事实和明细尚未读取。读取完整制品会请求该报告的结构化 JSON 证据。</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'
import { snapshotSelection, ensureSnapshotSelection } from '../composables/useSnapshotSelection'
import { forEachConcurrent } from '../utils/forEachConcurrent'
import { requirePageResponse } from '../utils/pageResponse'
import { requireReportPageResponse } from '../utils/reportPageResponse'
import { buildReportRequest } from '../utils/reportRequest'

const templates = [
  { code: 'OPS_DAILY', name: '经营日报', ready: true },
  { code: 'OPS_WEEKLY', name: '经营周报', ready: true },
  { code: 'ORDER_ANALYSIS', name: '订单专题', ready: true },
  { code: 'PRODUCT_ANOMALY', name: '商品异常', ready: true },
  { code: 'USER_OPERATIONS', name: '用户运营', ready: true },
  { code: 'DECISION_EFFECT', name: '决策效果', ready: true }
]
const selectedTemplateCode = ref('OPS_DAILY')
const snapshotsLoading = ref(false)
const snapshotError = ref('')
const selectedSnapshotId = ref('')
const decisionOptions = ref([])
const selectedDecisionId = ref('')
const decisionLoading = ref(false)
const decisionError = ref('')
const decisionPage = ref(0)
const decisionTotalPages = ref(0)
const creating = ref(false)
const createError = ref('')
const createMessage = ref('')
const loading = ref(false)
const listError = ref('')
const reports = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const totalPages = ref(0)
const sort = ref('createdAt,desc')
const selectedReport = ref(null)
const artifact = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
let requestSequence = 0

const selectedChoice = computed(() => snapshotSelection.options.find(
  (item) => item.snapshotId === selectedSnapshotId.value
) || null)
const summaryLabels = {
  fromDate: '开始日期', toDate: '结束日期', totalOrders: '订单数', totalSaleAmount: '销售额',
  totalNetSaleAmount: '净销售额', averageOrderValue: '周客单价', totalBehaviorCount: '行为次数',
  availableMetricCount: '可用指标数', unavailableMetrics: '未发布指标', totalProducts: '商品数',
  topHeatProductCount: '热度商品数', existingCandidateCount: '既有异常候选数',
  periodStart: '统计开始日', periodEnd: '统计结束日', ruleVersion: '分群规则版本',
  rfmSegmentCount: 'RFM 分组数', lifecycleGroupCount: '生命周期分组数',
  categoryPreferenceCount: '类目偏好分组数', decisionId: '决策编号', decisionNo: '决策单号',
  result: '评价结果', baselineValue: '基线值', actualValue: '实际值', improvementRate: '变化率',
  baselineWindowStart: '基线开始日', baselineWindowEnd: '基线结束日',
  actualWindowStart: '实际开始日', actualWindowEnd: '实际结束日'
}
const summaryEntries = computed(() => Object.entries(selectedReport.value?.summary?.templateSummary || {})
  .map(([key, value]) => ({ key, label: summaryLabels[key] || key, value: displayValue(value) })))
const artifactFacts = computed(() => artifact.value?.evidence?.facts || [])
const warningItems = computed(() => [...new Set([
  ...(artifact.value?.evidence?.warnings || []),
  ...(artifact.value?.templateContent?.warnings || [])
])])
const limitationItems = computed(() => artifact.value?.templateContent?.limitations || [])
const weeklyFromDate = computed(() => {
  const businessDate = selectedChoice.value?.businessDate
  if (!businessDate) return ''
  const date = new Date(`${businessDate}T00:00:00`)
  date.setDate(date.getDate() - 6)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
})

function templateName(code) {
  return templates.find((item) => item.code === code)?.name || code || '—'
}

function displayValue(value) {
  if (Array.isArray(value)) return value.length ? value.join('、') : '无'
  if (value === null || value === undefined || value === '') return '未提供'
  return String(value)
}

function windowLabel(report) {
  if (!report?.fromDate) return '—'
  return report.toDate && report.toDate !== report.fromDate
    ? `${report.fromDate} 至 ${report.toDate}` : report.fromDate
}

function qualityLabel(status) {
  return ({ PASS: '通过', FAIL: '未通过', UNKNOWN: '未知' })[status] || '未知'
}

async function loadPage(targetPage = 1) {
  const sequence = ++requestSequence
  loading.value = true
  listError.value = ''
  try {
    const result = requireReportPageResponse(await api.reportsPage({ page: targetPage, size: size.value, sort: sort.value }))
    if (sequence !== requestSequence) return
    reports.value = result.items
    page.value = result.page
    total.value = result.total
    totalPages.value = result.totalPages
  } catch (error) {
    if (sequence !== requestSequence) return
    listError.value = error?.message || '未知错误'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function changeSort() {
  void loadPage(1)
}

async function loadDecisionOptions(targetPage = 1, append = false) {
  decisionLoading.value = true
  decisionError.value = ''
  try {
    const decisionPageResult = requirePageResponse(
      await api.decisionPage({ page: targetPage, size: 20, sort: 'id,desc' }),
      '决策列表'
    )
    const eligible = decisionPageResult.items.filter((task) =>
      task?.completedAt && ['EFFECTIVE', 'PARTIAL', 'INEFFECTIVE', 'INSUFFICIENT_DATA'].includes(task.status)
    )
    const evaluatedTasks = new Set()
    let evaluationReadFailures = 0
    await forEachConcurrent(eligible, 5, async (task) => {
      try {
        const evaluations = await api.decisionEvaluations(task.id)
        if (!Array.isArray(evaluations)) throw new TypeError('评价列表格式无效')
        if (evaluations[0]?.result === task.status) evaluatedTasks.add(task)
      } catch {
        evaluationReadFailures += 1
      }
    })
    const evaluated = eligible.filter((task) => evaluatedTasks.has(task))
    decisionOptions.value = append
      ? [...decisionOptions.value, ...evaluated.filter((task) => !decisionOptions.value.some((existing) => existing.id === task.id))]
      : evaluated
    decisionPage.value = decisionPageResult.page
    decisionTotalPages.value = decisionPageResult.totalPages
    if (evaluationReadFailures) {
      decisionError.value = `${evaluationReadFailures} 条决策的评价读取失败，已暂不列入报告候选。`
    }
  } catch (error) {
    decisionError.value = error?.message || '未知错误'
  } finally {
    decisionLoading.value = false
  }
}

async function createReport() {
  const decisionMode = selectedTemplateCode.value === 'DECISION_EFFECT'
  if ((decisionMode && !selectedDecisionId.value) || (!decisionMode && !selectedChoice.value) || creating.value) return
  creating.value = true
  createError.value = ''
  createMessage.value = ''
  try {
    const request = buildReportRequest({
      templateCode: selectedTemplateCode.value,
      snapshot: selectedChoice.value,
      decisionId: selectedDecisionId.value,
      fromDate: weeklyFromDate.value
    })
    const report = await api.createReport(request)
    createMessage.value = decisionMode
      ? `${templateName(report.templateCode)} #${report.id} 已生成并绑定决策 ${selectedDecisionId.value}。`
      : `${templateName(report.templateCode)} #${report.id} 已生成并绑定快照 ${report.snapshotId}。`
    await loadPage(1)
    const row = { id: report.id, templateCode: report.templateCode }
    await openReport(row)
  } catch (error) {
    createError.value = error?.message || '未知错误'
  } finally {
    creating.value = false
  }
}

async function openReport(report) {
  detailLoading.value = true
  detailError.value = ''
  artifact.value = null
  try {
    selectedReport.value = await api.reportDetail(report.id)
  } catch (error) {
    selectedReport.value = null
    detailError.value = error?.message || '读取报告详情失败'
  } finally {
    detailLoading.value = false
  }
}

async function loadArtifact() {
  if (!selectedReport.value?.report?.id) return
  detailLoading.value = true
  detailError.value = ''
  try {
    artifact.value = await api.reportArtifact(selectedReport.value.report.id)
  } catch (error) {
    detailError.value = error?.message || '读取报告制品失败'
  } finally {
    detailLoading.value = false
  }
}

watch(selectedTemplateCode, (templateCode) => {
  if (templateCode === 'DECISION_EFFECT' && decisionPage.value === 0 && !decisionLoading.value) {
    void loadDecisionOptions(1)
  }
})

onMounted(async () => {
  snapshotsLoading.value = true
  try {
    await ensureSnapshotSelection()
    selectedSnapshotId.value = snapshotSelection.selectedSnapshotId
  } catch (error) {
    snapshotError.value = error?.message || '未知错误'
  } finally {
    snapshotsLoading.value = false
  }
  await loadPage(1)
})
</script>

<style scoped>
.report-form, .report-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 14px; }
.report-form { margin-top: 14px; }
.report-form select, .report-form input, .report-toolbar select {
  min-height: 34px; padding: 5px 8px; border: 1px solid var(--gray-300, #d1d5db);
  border-radius: 6px; background: #fff; color: var(--gray-700, #374151);
}
.report-form select { min-width: min(100%, 420px); }
.report-form input { width: 150px; }
.report-toolbar { margin: 10px 0 14px; color: var(--gray-600, #4b5563); font-size: 13px; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; }
.template-card { display: grid; gap: 5px; padding: 12px; border: 1px solid var(--gray-200, #e5e7eb); border-radius: 8px; color: var(--gray-500, #6b7280); }
.template-card.ready { border-color: #86efac; color: var(--gray-800, #1f2937); }
.template-card code { color: var(--gray-500, #6b7280); font-size: 11px; }
.template-card span { font-size: 12px; }
.banner-success { background: var(--success-bg); border-color: var(--success-bd); color: #046C4E; }
.report-readable { display: grid; gap: 18px; }
.report-facts, .report-summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin: 0; }
.report-facts > div, .report-summary-grid > div { min-width: 0; padding: 12px; border: 1px solid var(--gray-200, #e5e7eb); border-radius: 8px; background: var(--white, #fff); }
.report-facts dt, .report-summary-grid dt { color: var(--gray-500, #6b7280); font-size: 12px; }
.report-facts dd, .report-summary-grid dd { margin: 6px 0 0; color: var(--gray-900, #111827); font-weight: 600; overflow-wrap: anywhere; }
.report-section { min-width: 0; }
.report-section h3 { margin: 0 0 10px; font-size: 15px; }
.report-table-scroll { overflow-x: auto; }
.report-notes { display: grid; gap: 6px; margin: 0; padding-left: 20px; color: var(--gray-700, #374151); }
.report-raw summary { cursor: pointer; color: var(--gray-600, #4b5563); font-size: 13px; }
.report-raw .report-artifact { margin-top: 10px; }
.report-artifact { max-height: 560px; overflow: auto; padding: 14px; border-radius: 8px; background: #111827; color: #e5e7eb; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 720px) { .report-form { align-items: stretch; } .report-form label { width: 100%; } .report-form select { width: 100%; } }
</style>
