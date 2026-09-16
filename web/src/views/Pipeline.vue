<template>
  <div>
    <div class="page-title">数据流水线</div>
    <div class="chart-box">
      <div class="chart-title">触发一次采集与流水线实例（分析平台侧，不调用模拟商城生成器）</div>
      <!-- S3-34（E5-c）：本页此前**不挂**上下文条（其余 8 个分析页都挂）⇒ 看不到结果所属数据源/发布方。
           上下文条只描述**本页响应整体**的口径；每个实例自己的业务时间/源数据版本/目标快照见下表。 -->
      <AnalysisContext :context="context" :state="state" :error="error" />
      <div class="window-note" style="font-size:12px;color:#6b7280;margin-bottom:8px">
        上下文条描述本页响应整体口径（/pipeline-runs 返回裸数组、无统一信封，故来源（发布方）/口径版本/质量状态显示「未知」）；
        实例级溯源请看下表「源数据版本 / 目标快照」两列。
      </div>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <label style="font-size:13px">业务时间
          <input v-model="businessDate" type="date" style="margin-left:6px;padding:4px">
        </label>
        <label style="font-size:13px">运行环境
          <input v-model.number="runtimeProfileId" type="number" min="1" style="width:70px;margin-left:6px;padding:4px">
        </label>
        <button @click="runOnce" :disabled="busy" style="padding:6px 16px">
          {{ busy ? '运行中…' : '触发采集并创建流水线实例' }}
        </button>
      </div>
      <div style="margin-top:8px;font-size:12px;color:#6b7280">
        说明：事件由外部模拟商城按统一契约写入事件目录，分析平台只做采集与编排，不再内置“生成订单”入口（指导书 §18.4）。
      </div>
      <div v-if="runResult" style="margin-top:12px;font-size:13px">
        流水线 run#{{ runResult.runId }}：{{ runResult.status }}
        <span v-if="runResult.stages">
          （{{ runResult.stages.filter(s => s.status === 'SUCCESS').length }}/{{ runResult.stages.length }} 阶段成功）
        </span>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">
        最近流水线实例
        <button style="float:right;font-size:12px;padding:3px 10px" @click="loadRuns">刷新</button>
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">ID</th><th>流水线</th><th>业务时间</th><th>源数据版本</th><th>目标快照</th><th>状态</th><th>尝试</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in runRows" :key="r.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ r.id }}</td>
            <td>{{ r.pipelineCode }}</td>
            <td>{{ r.businessTime }}</td>
            <td class="mono">{{ r.sourceDataVersion }}</td>
            <td class="mono">{{ r.targetSnapshotId }}</td>
            <td :style="{ color: r.status === 'SUCCESS' ? '#16a34a' : (r.status === 'FAILED' ? '#dc2626' : '#d97706') }">
              {{ r.status }}
            </td>
            <td>{{ r.attemptNo }}</td>
            <td><button v-if="r.status === 'FAILED'" @click="retry(r.id)" style="font-size:12px">重试</button></td>
          </tr>
          <tr v-if="runRows.length === 0"><td colspan="8" class="el-empty">暂无运行记录</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import AnalysisContext from '../components/AnalysisContext.vue'
import { buildFallbackContext } from '../utils/context.js'
import { pipelineRunRows } from '../utils/tables.js'

const businessDate = ref(new Date().toISOString().slice(0, 10))
const runtimeProfileId = ref(1)
const busy = ref(false)
const runs = ref([])
const runResult = ref(null)
// 上下文条状态：只描述「实例列表」这次取数，不把触发动作的失败算成数据加载失败
const state = ref('loading')
const error = ref('')

// 取数形状守卫：api.js 解包 `body.data` ⇒ 这里是 List<PipelineRun>；
// 形状意外时退化成空表（不抛错），与 Ops.vue 对同一实体的处理保持一致。
const runList = computed(() => (Array.isArray(runs.value) ? runs.value : []))

// 实例表经 tables.js 的 pipelineRunRows 单一映射所有者渲染（不再自渲染裸行字段）
const runRows = computed(() => pipelineRunRows(runList.value))

// 响应级上下文：/pipeline-runs 是**裸数组**接口（无统一信封）⇒ 显式登记 ENVELOPE_MISSING；
// 快照号取实例的目标快照（可能多个：由 buildFallbackContext 既有规则如实标注「未合并为单一快照」）；
// 业务时间/数据更新时间/口径版本/质量状态是**响应级**字段，裸数组接口不提供 ⇒ 交给缺失清单如实标注，
// 不从某一实例行挑一个值冒充整页口径。
const context = computed(() => buildFallbackContext({
  rows: runList.value,
  warnings: ['ENVELOPE_MISSING'],
  snapshotIds: runList.value.map((r) => r && r.targetSnapshotId).filter(Boolean)
}))

async function runOnce() {
  busy.value = true
  runResult.value = null
  try {
    // 采集一次事件目录（发布由定时器完成），再创建流水线实例
    await api.ingestionRun()
    await new Promise((r) => setTimeout(r, 3000))
    runResult.value = await api.createPipelineRun({
      // 流水线编码是全项目唯一的编排标识：脚本 / 验收记录 / 论文一律用 ODS_TO_ADS。
      // 早期页面写死过 DAILY_CORE（只在 V2 建表注释里出现过，平台不按它选阶段），
      // 会让面板里出现的编码与文档/脚本对不上，故对齐为 ODS_TO_ADS（M1-6 命名一致）。
      runtimeProfileId: runtimeProfileId.value, pipelineCode: 'ODS_TO_ADS',
      businessTime: businessDate.value + 'T00:00:00', sourceDataVersion: 'manual-' + Date.now()
    }, 'manual-' + Date.now())
    await loadRuns()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

async function loadRuns() {
  state.value = 'loading'
  error.value = ''
  try {
    runs.value = await api.pipelineRuns(10)
    state.value = 'ready'
  } catch (e) {
    error.value = e.message || String(e)
    state.value = 'error'
    console.error(e)
  }
}

async function retry(id) {
  runResult.value = await api.retryPipelineRun(id)
  await loadRuns()
}

onMounted(loadRuns)
</script>