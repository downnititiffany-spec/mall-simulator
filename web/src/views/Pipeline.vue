<template>
  <div>
    <div class="page-title">数据流水线</div>
    <div class="chart-box">
      <div class="chart-title">触发一次采集与流水线实例（分析平台侧，不调用模拟商城生成器）</div>
      <!-- S3-34（E5-c）：本页此前**不挂**上下文条（其余 8 个分析页都挂）⇒ 看不到结果所属数据源/发布方。
           上下文条只描述**本页响应整体**的口径；每个实例自己的业务时间/源数据版本/输入批次/目标快照见下表。 -->
      <AnalysisContext :context="exportContext" :state="state" :error="error" />
      <div class="window-note" style="font-size:12px;color:#6b7280;margin-bottom:8px">
        上下文条描述本页响应整体口径（/pipeline-runs 返回裸数组、无统一信封，故来源（发布方）/口径版本/质量状态显示「未知」）；
        实例级溯源请看下表「源数据版本 / 输入批次 / 目标快照」三列（输入批次 ＝ 本 run 消费的
        `ingestion_batch.id`，S3-36 起落库；老实例该列未记录、显示「—」）。
      </div>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <label style="font-size:13px">业务时间
          <input v-model="businessDate" type="date" :disabled="busy" style="margin-left:6px;padding:4px">
        </label>
        <label style="font-size:13px">运行环境
          <input v-model.number="runtimeProfileId" type="number" min="1" :disabled="busy" style="width:70px;margin-left:6px;padding:4px">
        </label>
        <button @click="runOnce" :disabled="busy" style="padding:6px 16px">
          {{ busy ? '运行中…' : '触发采集并创建流水线实例' }}
        </button>
      </div>
      <div style="margin-top:8px;font-size:12px;color:#6b7280">
        说明：事件由外部模拟商城按统一契约写入事件目录，分析平台只做采集与编排，不再内置“生成订单”入口（指导书 §18.4）。
      </div>
      <div v-if="runResult" style="margin-top:12px;font-size:13px">
        <template v-if="runResult.runId">流水线 run#{{ runResult.runId }}：{{ runResult.status }}</template>
        <template v-else>{{ runResult.status }}</template>
        <span v-if="runResult.stages">
          （{{ runResult.stages.filter(s => s.status === 'SUCCESS').length }}/{{ runResult.stages.length }} 阶段成功）
        </span>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        最近流水线实例
        <button style="float:right;font-size:12px;padding:3px 10px" @click="load" :disabled="loading || busy">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">ID</th><th>流水线</th><th>业务时间</th><th>源数据版本</th><th>输入批次</th><th>目标快照</th><th>状态</th><th>尝试</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in runRows" :key="r.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ r.id }}</td>
            <td>{{ r.pipelineCode }}</td>
            <td>{{ r.businessTime }}</td>
            <td class="mono">{{ r.sourceDataVersion }}</td>
            <td class="mono">{{ r.inputBatchId }}</td>
            <td class="mono">{{ r.targetSnapshotId }}</td>
            <td :style="{ color: r.status === 'SUCCESS' ? '#16a34a' : (r.status === 'FAILED' ? '#dc2626' : '#d97706') }">
              {{ r.status }}
            </td>
            <td>{{ r.attemptNo }}</td>
            <td><button v-if="r.status === 'FAILED'" @click="retry(r.id)" :disabled="busy" style="font-size:12px">重试</button></td>
          </tr>
          <tr v-if="runRows.length === 0"><td colspan="9" class="el-empty">暂无运行记录</td></tr>
        </tbody>
      </table>
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

const businessDate = ref(localIsoDay())
const runtimeProfileId = ref(1)
const busy = ref(false)
const runResult = ref(null)

// 取数 fetcher：/pipeline-runs 是**裸数组**接口（无统一信封）⇒ 在这里用 buildFallbackContext
// 拼响应级上下文（与 Decisions.vue / Ops.vue 同形）；快照号只取实例的 targetSnapshotId
// （可能多个：由 buildFallbackContext 既有规则如实标注「未合并为单一快照」）。
// 业务时间/数据更新时间/口径版本/质量状态是**响应级**字段、裸数组接口并不提供 ⇒ 交缺失清单如实标注，
// 不从某一实例行挑一个值冒充整页口径。加载状态**一律**交给唯一属主 useAnalysis，页内不自造状态机。
async function fetchRuns(_params, signal) {
  const raw = await api.pipelineRuns(10, { signal })
  // 形状守卫：api.js 解包 `body.data` ⇒ 这里是 List<PipelineRun>；形状意外时退化成空表（不抛错）
  const list = Array.isArray(raw) ? raw : []
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

async function runOnce() {
  if (busy.value) return
  // 本次人工触发的输入必须在任何 await 之前冻结；否则 ingestionRun/3 秒等待期间修改表单，
  // createPipelineRun 会读到另一组业务时间/运行环境，导致一次操作前后上下文漂移。
  const requestedBusinessDate = businessDate.value
  const requestedRuntimeProfileId = runtimeProfileId.value
  busy.value = true
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
    await load()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

async function retry(id) {
  if (busy.value) return
  busy.value = true
  runResult.value = null
  try {
    runResult.value = await api.retryPipelineRun(id)
    await load()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
onBeforeUnmount(() => analysis.cancel())
</script>