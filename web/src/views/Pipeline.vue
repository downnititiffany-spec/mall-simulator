<template>
  <div>
    <div class="page-title">数据流水线</div>
    <div class="chart-box">
      <div class="chart-title">触发一次采集与流水线实例（分析平台侧，不调用模拟商城生成器）</div>
      <!-- S3-34（E5-c）：本页此前**不挂**上下文条（其余 8 个分析页都挂）⇒ 看不到结果所属数据源/发布方。
           上下文条只描述**本页响应整体**的口径；每个实例自己的业务时间/源数据版本/输入批次/目标快照见下表。 -->
      <AnalysisContext :context="exportContext" :state="state" :error="error" />
      <div class="window-note" style="font-size:12px;color:var(--gray-500);margin-bottom:8px">
        上下文条描述本页响应整体口径（/pipeline-runs/page 返回分页对象但无分析信封，故来源（发布方）/口径版本/质量状态显示「未知」）；
        实例级溯源请看下表「源数据版本 / 输入批次 / 目标快照」三列（输入批次 ＝ 本 run 消费的
        `ingestion_batch.id`，S3-36 起落库；老实例该列未记录、显示「—」）。
      </div>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <label style="font-size:13px">业务时间
          <input v-model="businessDate" type="date" :disabled="loading || busy" style="margin-left:6px;padding:4px">
        </label>
        <label style="font-size:13px">运行环境
          <input v-model.number="runtimeProfileId" type="number" min="1" :disabled="loading || busy" style="width:70px;margin-left:6px;padding:4px">
        </label>
        <button @click="runOnce" :disabled="loading || busy" style="padding:6px 16px">
          {{ busy ? '运行中…' : '触发采集并创建流水线实例' }}
        </button>
      </div>
      <div style="margin-top:8px;font-size:12px;color:var(--gray-500)">
        说明：事件由外部模拟商城按统一契约写入事件目录，分析平台只做采集与编排，不再内置“生成订单”入口（指导书 §18.4）。
      </div>
      <div v-if="runResult" style="margin-top:12px;font-size:13px">
        <template v-if="runResult.runId">流水线 run#{{ runResult.runId }}：{{ runResult.status }}</template>
        <template v-else>{{ runResult.status }}</template>
        <div v-if="runResult.runId" style="margin-top:4px;color:var(--gray-600)">
          当前阶段：{{ runResult.currentStage || 'PENDING' }}
          <span v-if="runResult.errorCode" style="margin-left:8px;color:#dc2626">失败码：{{ runResult.errorCode }}</span>
          <span v-if="runIsActive(runResult.status)" style="margin-left:8px">正在刷新进度…</span>
        </div>
        <div v-if="runResult.pollingError" role="status" style="margin-top:4px;color:#b45309">
          暂时无法刷新任务进度，将继续重试。
        </div>
        <table v-if="Array.isArray(runResult.stages) && runResult.stages.length" class="data-table" style="margin-top:8px;max-width:720px">
          <thead><tr style="text-align:left;color:var(--gray-500)"><th style="padding:6px">阶段</th><th>状态</th><th>记录数</th><th>失败码</th></tr></thead>
          <tbody><tr v-for="stage in runResult.stages" :key="stage.id" style="border-top:1px solid var(--gray-100)">
            <td style="padding:6px">{{ stage.stageCode }}</td><td>{{ stage.status }}</td>
            <td class="mono">{{ stage.records == null ? '—' : stage.records }}</td>
            <td class="mono" style="color:#dc2626">{{ stage.errorCode || '—' }}</td>
          </tr></tbody>
        </table>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        最近流水线实例
        <button class="btn btn-sm" style="float:right" @click="refresh" :disabled="loading || busy">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <div style="display:flex;gap:8px;align-items:center;margin-bottom:8px">
        <label style="font-size:13px">排序
          <select v-model="sort" :disabled="loading || busy" @change="changeSort" style="margin-left:6px;padding:4px">
            <option value="createdAt,desc">创建时间（新到旧）</option>
            <option value="businessTime,desc">业务时间（新到旧）</option>
            <option value="status,asc">状态</option>
          </select>
        </label>
        <label style="font-size:13px">每页
          <select v-model.number="pageSize" :disabled="loading || busy" @change="changePageSize" style="margin-left:6px;padding:4px">
            <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option>
          </select>
        </label>
        <span style="font-size:13px;color:var(--gray-500)">共 {{ total }} 条</span>
        <button class="btn btn-sm" :disabled="loading || busy || page <= 1" @click="changePage(page - 1)">上一页</button>
        <span style="font-size:13px">第 {{ page }} / {{ totalPages }} 页</span>
        <button class="btn btn-sm" :disabled="loading || busy || page >= totalPages" @click="changePage(page + 1)">下一页</button>
      </div>
      <div class="table-wrap">
      <table class="data-table">
        <thead><tr style="text-align:left;color:var(--gray-500)">
          <th style="padding:8px">ID</th><th>流水线</th><th>业务时间</th><th>源数据版本</th><th>输入批次</th><th>目标快照</th><th>状态</th><th>当前阶段</th><th>尝试</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in runRows" :key="r.id" style="border-top:1px solid var(--gray-100)">
            <td style="padding:8px">{{ r.id }}</td>
            <td>{{ r.pipelineCode }}</td>
            <td>{{ r.businessTime }}</td>
            <td class="mono">{{ r.sourceDataVersion }}</td>
            <td class="mono">{{ r.inputBatchId }}</td>
            <td class="mono">{{ r.targetSnapshotId }}</td>
            <td>
              <span class="badge"
                    :class="r.status === 'SUCCESS' ? 'badge-success' : (r.status === 'FAILED' ? 'badge-danger' : 'badge-warning')">
                {{ r.status }}
              </span>
            </td>
            <td class="mono">{{ r.currentStage || '—' }}<span v-if="r.status === 'FAILED' && r.errorCode" style="margin-left:6px;color:#dc2626">{{ r.errorCode }}</span></td>
            <td>{{ r.attemptNo }}</td>
            <td>
              <button class="btn btn-sm" v-if="r.status === 'FAILED'" @click="retry(r.id)" :disabled="loading || busy || recoveryBusy">重试</button>
              <template v-if="canOperatePipeline">
                <button class="btn btn-sm" v-if="r.status === 'FAILED'" @click="beginRecovery(r, 'resume')" :disabled="loading || busy || recoveryBusy">续跑</button>
                <button class="btn btn-sm" v-if="r.status === 'FAILED'" @click="beginRecovery(r, 'retry-stage')" :disabled="loading || busy || recoveryBusy">按阶段重试</button>
                <button class="btn btn-sm btn-danger" v-if="r.status === 'PENDING' || r.status === 'RUNNING'" @click="beginRecovery(r, 'mark-failed')" :disabled="loading || busy || recoveryBusy">标记失败…</button>
              </template>
            </td>
          </tr>
          <tr v-if="runRows.length === 0"><td colspan="10" class="el-empty">暂无运行记录</td></tr>
        </tbody>
      </table>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        启动恢复报告
        <button class="btn btn-sm" style="float:right" @click="refreshRecoveryReport" :disabled="recoveryReportLoading">
          {{ recoveryReportLoading ? '读取中…' : '刷新报告' }}
        </button>
      </div>
      <div v-if="recoveryReportError" class="banner banner-error">恢复报告读取失败：{{ recoveryReportError }}</div>
      <div v-else-if="recoveryReportLoading && !recoveryReport" class="state-line">读取本次平台启动后的恢复对账结果…</div>
      <div v-else-if="!recoveryReport" class="el-empty">本次启动尚无可展示的恢复报告</div>
      <template v-else>
        <div class="table-hint">操作者 {{ recoveryReport.operator || '—' }} · 时间 {{ recoveryReport.at || '—' }} ·
          重排待执行 {{ recoveryReport.requeuedPending?.length || 0 }} · 中断运行 {{ recoveryReport.interruptedRunning?.length || 0 }} ·
          孤立作业标记 UNKNOWN {{ recoveryReport.orphanJobsMarkedUnknown ?? '—' }}</div>
        <div v-if="recoveryReport.requeuedPending?.length" class="table-hint">已重排 run：{{ recoveryReport.requeuedPending.join(', ') }}</div>
        <div v-if="recoveryReport.interruptedRunning?.length" class="table-hint">判定中断 run：{{ recoveryReport.interruptedRunning.join(', ') }}</div>
        <div v-if="recoveryReport.errors?.length" class="banner banner-error">对账错误：{{ recoveryReport.errors.join('；') }}</div>
        <div v-else class="table-hint">报告仅记录本次启动时发现的 PENDING/RUNNING 对账；可对 FAILED run 执行有理由的人工恢复。</div>
      </template>
    </div>

    <div v-if="recoveryMessage" class="banner banner-stale" role="status">{{ recoveryMessage }}</div>
    <div v-if="recoveryForm" class="chart-box" @keyup.esc="closeRecoveryForm">
      <div class="chart-title">{{ recoveryActionTitle(recoveryForm.action) }}：run#{{ recoveryForm.runId }}</div>
      <div v-if="recoveryFormError" class="banner banner-error">操作失败：{{ recoveryFormError }}</div>
      <div v-if="recoveryForm.run" class="table-hint">
        当前状态 {{ recoveryForm.run.status }} · 当前阶段 {{ recoveryForm.run.currentStage || '—' }} · 失败码 {{ recoveryForm.run.errorCode || '—' }}
      </div>
      <div v-if="recoveryForm.stages?.length" class="table-hint">
        已记录阶段：{{ recoveryForm.stages.map(s => `${s.stageCode}:${s.status}`).join('，') }}
      </div>
      <div v-if="recoveryForm.action === 'mark-failed'" class="banner banner-warn">
        此操作只改写平台 run 状态，不会停止进程或从执行队列移除任务。PENDING 也可能已经入队，RUNNING 也可能仍在执行；只有核实平台队列、提交进程和外部 Spark 作业均无待执行/运行任务后才可继续。
      </div>
      <div v-if="recoveryForm.action === 'retry-stage'" class="banner banner-warn">
        按阶段重试会删除所选阶段及之后的阶段记录并重跑。请先核实所选阶段之前的产物可复用；本操作不会自动检查外部作业是否仍运行。
      </div>
      <label v-if="recoveryForm.action === 'retry-stage'" style="display:block;margin:8px 0">
        起始阶段
        <select v-model="recoveryForm.stage" :disabled="recoveryBusy" style="margin-left:6px;padding:4px">
          <option v-for="stage in PIPELINE_STAGES" :key="stage" :value="stage">{{ stage }}</option>
        </select>
      </label>
      <label style="display:block;margin:8px 0">恢复原因（必填）
        <textarea v-model="recoveryForm.reason" rows="3" maxlength="500" :disabled="recoveryBusy"
                  style="display:block;width:min(100%,720px);margin-top:4px;padding:8px"></textarea>
      </label>
      <label style="display:block;margin:8px 0">
        <input v-model="recoveryForm.confirmed" type="checkbox" :disabled="recoveryBusy" />
        {{ recoveryConfirmationText(recoveryForm.action) }}
      </label>
      <div style="display:flex;gap:8px;margin-top:10px">
        <button class="btn btn-primary" @click="executeRecovery" :disabled="recoveryBusy || !recoveryForm.run || !recoveryForm.reason.trim() || !recoveryForm.confirmed">
          {{ recoveryBusy ? '提交中…' : '确认并提交' }}
        </button>
        <button class="btn" @click="closeRecoveryForm" :disabled="recoveryBusy">取消</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import { useAnalysis } from '../composables/useAnalysis'
import { buildFallbackContext, NON_ANALYSIS_ROW_KEYS } from '../utils/context.js'
import { localIsoDay } from '../utils/localDate.js'
import { pipelineRunRows } from '../utils/tables.js'
import { requirePageResponse } from '../utils/pageResponse.js'

const businessDate = ref(localIsoDay())
const runtimeProfileId = ref(1)
const busy = ref(false)
const runResult = ref(null)
const page = ref(1)
const total = ref(0)
const totalPages = ref(1)
const sort = ref('createdAt,desc')
const pageSize = ref(20)
const runPollTimer = ref(null)
let runPollGeneration = 0
const recoveryReport = ref(null)
const recoveryReportError = ref('')
const recoveryReportLoading = ref(false)
const recoveryForm = ref(null)
const recoveryFormError = ref('')
const recoveryBusy = ref(false)
const recoveryMessage = ref('')
const PIPELINE_STAGES = ['WAIT_LANDING', 'INIT_SCHEMA', 'LOAD_ODS', 'BUILD_DWD', 'BUILD_DWS', 'BUILD_ADS', 'QUALITY_CHECK', 'PUBLISH_METRIC']
const canOperatePipeline = computed(() => {
  try {
    const user = JSON.parse(localStorage.getItem('analytics_user') || 'null')
    return ['admin', 'data_dev', 'operator'].includes(user?.role)
  } catch (_) {
    return false
  }
})

// 取数 fetcher：/pipeline-runs/page 是分页对象而非分析信封 ⇒ 在这里用 buildFallbackContext
// 拼响应级上下文（与 Decisions.vue / Ops.vue 同形）；快照号只取实例的 targetSnapshotId
// （可能多个：由 buildFallbackContext 既有规则如实标注「未合并为单一快照」）。
// 业务时间/数据更新时间/口径版本/质量状态是**响应级**字段、运行列表接口并不提供 ⇒ 交缺失清单如实标注，
// 不从某一实例行挑一个值冒充整页口径。加载状态**一律**交给唯一属主 useAnalysis，页内不自造状态机。
async function fetchRuns(params, signal) {
  const raw = requirePageResponse(await api.pipelineRunsPage(params, { signal }), '流水线运行列表')
  const list = raw.items
  total.value = raw.total
  totalPages.value = raw.totalPages
  page.value = raw.page
  const ctx = buildFallbackContext({
    rows: list,
    warnings: ['ENVELOPE_MISSING'],
    snapshotIds: list.map((r) => r && r.targetSnapshotId).filter(Boolean)
  })
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
    data: { pipelineRuns: list }
  }
}

const analysis = useAnalysis({ fetcher: fetchRuns, rowKeys: NON_ANALYSIS_ROW_KEYS.pipelineRuns })
const { data, state, loading, error, exportContext, load } = analysis

// 实例表经 tables.js 的 pipelineRunRows 单一映射所有者渲染（不再自渲染裸行字段）
const runRows = computed(() => pipelineRunRows(data.value.pipelineRuns))

function refresh() {
  if (loading.value || busy.value) return
  return load({ page: page.value, size: pageSize.value, sort: sort.value })
}

function changePage(next) {
  if (loading.value || busy.value) return
  page.value = next
  return load({ page: next, size: pageSize.value, sort: sort.value })
}

function changePageSize() {
  if (loading.value || busy.value) return
  page.value = 1
  return load({ page: 1, size: pageSize.value, sort: sort.value })
}

function changeSort() {
  if (loading.value || busy.value) return
  page.value = 1
  return load({ page: 1, size: pageSize.value, sort: sort.value })
}

async function refreshRecoveryReport() {
  if (recoveryReportLoading.value) return
  recoveryReportLoading.value = true
  recoveryReportError.value = ''
  try {
    recoveryReport.value = await api.pipelineRecoveryReport()
  } catch (e) {
    recoveryReportError.value = (e && (e.message || e.code)) || '读取失败'
  } finally {
    recoveryReportLoading.value = false
  }
}

function recoveryActionTitle(action) {
  return ({ resume: '续跑失败实例', 'mark-failed': '人工标记失败', 'retry-stage': '从指定阶段重跑' })[action] || '恢复流水线'
}

function recoveryConfirmationText(action) {
  if (action === 'mark-failed') return '我已核实该 run 不在执行队列中，平台提交进程和外部 Spark 作业均无待执行/运行任务；此操作不会停止它们。'
  if (action === 'retry-stage') return '我已核对起始阶段之前的产物可复用，并确认没有相关任务仍在队列或运行。'
  return '我已核实该实例为失败/中断状态，且没有相关任务仍在队列或运行。'
}

async function beginRecovery(row, action) {
  if (recoveryBusy.value || busy.value || loading.value || !canOperatePipeline.value) return
  recoveryFormError.value = ''
  recoveryMessage.value = ''
  recoveryBusy.value = true
  try {
    const run = await api.pipelineRun(row.id)
    const stages = Array.isArray(run && run.stages) ? run.stages : []
    const incomplete = stages.find((stage) => stage && stage.status !== 'SUCCESS' && PIPELINE_STAGES.includes(stage.stageCode))
    const currentStage = run && PIPELINE_STAGES.includes(run.currentStage) ? run.currentStage : null
    recoveryForm.value = {
      action,
      runId: row.id,
      run,
      stages,
      stage: (incomplete && incomplete.stageCode) || currentStage || PIPELINE_STAGES[0],
      reason: '',
      confirmed: false
    }
  } catch (e) {
    recoveryForm.value = { action, runId: row.id, run: null, stages: [], stage: PIPELINE_STAGES[0], reason: '', confirmed: false }
    recoveryFormError.value = (e && (e.message || e.code)) || '无法读取实例详情'
  } finally {
    recoveryBusy.value = false
  }
}

function closeRecoveryForm() {
  if (recoveryBusy.value) return
  recoveryForm.value = null
  recoveryFormError.value = ''
}

async function executeRecovery() {
  const form = recoveryForm.value
  if (!form || !form.run || recoveryBusy.value || !form.reason.trim() || !form.confirmed) return
  recoveryBusy.value = true
  recoveryFormError.value = ''
  recoveryMessage.value = ''
  try {
    const fresh = await api.pipelineRun(form.runId)
    if (!fresh || fresh.status !== form.run.status || fresh.currentStage !== form.run.currentStage
        || fresh.updatedAt !== form.run.updatedAt) {
      throw new Error('运行状态已变化。请刷新列表并重新打开恢复操作，避免使用过期详情。')
    }
    if ((form.action === 'resume' || form.action === 'retry-stage') && fresh.status !== 'FAILED') {
      throw new Error('只有 FAILED 实例可续跑或按阶段重跑。')
    }
    if (form.action === 'mark-failed' && !['PENDING', 'RUNNING'].includes(fresh.status)) {
      throw new Error('只有 PENDING/RUNNING 实例可由管理员判定失败。')
    }
    let result
    if (form.action === 'resume') result = await api.resumePipelineRun(form.runId, form.reason.trim())
    else if (form.action === 'mark-failed') result = await api.markPipelineRunFailed(form.runId, form.reason.trim())
    else if (form.action === 'retry-stage') result = await api.retryPipelineRunFromStage(form.runId, form.stage, form.reason.trim())
    else throw new Error('未知的恢复操作')

    runResult.value = result
    recoveryMessage.value = `run#${form.runId} 操作已被服务端接受，当前状态：${(result && result.status) || '未提供'}`
    recoveryForm.value = null
    if (result && result.runId && runIsActive(result.status)) startRunPolling(result.runId)
    await refreshRunsAfterSubmission()
  } catch (e) {
    recoveryFormError.value = (e && (e.message || e.code)) || '操作失败'
  } finally {
    recoveryBusy.value = false
  }
}

async function refreshRunsAfterSubmission() {
  try {
    await load({ page: page.value, size: pageSize.value, sort: sort.value })
  } catch (_) {
    // useAnalysis owns the list error state; a list read failure must not rewrite a created run as failed.
  }
}

async function runOnce() {
  if (busy.value || loading.value) return
  // 本次人工触发的输入必须在任何 await 之前冻结；否则 ingestionRun/3 秒等待期间修改表单，
  // createPipelineRun 会读到另一组业务时间/运行环境，导致一次操作前后上下文漂移。
  const requestedBusinessDate = businessDate.value
  const requestedRuntimeProfileId = runtimeProfileId.value
  busy.value = true
  stopRunPolling()
  runResult.value = null
  try {
    // 一次人工触发使用同一个 operationId 同时作为 sourceDataVersion 与 Idempotency-Key：
    // 两者描述的是同一逻辑操作，不能各自 Date.now() 导致毫秒级不一致，破坏排障/证据关联。
    const operationId = 'manual-' + Date.now()
    // 采集一次事件目录（发布由定时器完成），再创建流水线实例
    await api.ingestionRun()
    await new Promise((r) => setTimeout(r, 3000))
    runResult.value = await api.createPipelineRun({
      // 流水线编码是全项目唯一的编排标识：脚本 / 验收记录 / 论文一律用 ODS_TO_ADS。
      // 早期页面写死过 DAILY_CORE（只在 V2 建表注释里出现过，平台不按它选阶段），
      // 会让面板里出现的编码与文档/脚本对不上，故对齐为 ODS_TO_ADS（M1-6 命名一致）。
      runtimeProfileId: requestedRuntimeProfileId, pipelineCode: 'ODS_TO_ADS',
      businessTime: requestedBusinessDate + 'T00:00:00', sourceDataVersion: operationId
    }, operationId)
    if (runResult.value && runResult.value.runId) startRunPolling(runResult.value.runId)
    page.value = 1
    await refreshRunsAfterSubmission()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

async function retry(id) {
  if (busy.value || loading.value) return
  busy.value = true
  stopRunPolling()
  runResult.value = null
  try {
    runResult.value = await api.retryPipelineRun(id)
    if (runResult.value && runResult.value.runId) startRunPolling(runResult.value.runId)
    await refreshRunsAfterSubmission()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

const runIsActive = (status) => status === 'PENDING' || status === 'RUNNING'

function stopRunPolling() {
  runPollGeneration += 1
  if (runPollTimer.value !== null) {
    clearTimeout(runPollTimer.value)
    runPollTimer.value = null
  }
}

function startRunPolling(runId) {
  stopRunPolling()
  const generation = runPollGeneration
  const poll = async () => {
    if (generation !== runPollGeneration) return
    try {
      const latest = await api.pipelineRun(runId)
      if (generation !== runPollGeneration) return
      runResult.value = latest
      if (!runIsActive(latest && latest.status)) {
        runPollTimer.value = null
        try {
          await load({ page: page.value, size: pageSize.value, sort: sort.value })
        } catch (_) {
          // A list refresh failure must not restart polling for an already terminal run.
        }
        return
      }
    } catch (_) {
      if (generation !== runPollGeneration) return
      runResult.value = { ...runResult.value, pollingError: true }
    }
    runPollTimer.value = setTimeout(poll, 1500)
  }
  runPollTimer.value = setTimeout(poll, 800)
}

onMounted(() => {
  refresh()
  refreshRecoveryReport()
})
onBeforeUnmount(() => {
  stopRunPolling()
  analysis.cancel()
})
</script>
