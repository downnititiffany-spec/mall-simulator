<template>
  <div>
    <div class="page-title">数据流水线（演示控制台）</div>
    <div class="chart-box">
      <div class="chart-title">一键演示：生成并分析</div>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <label style="font-size:13px">场景
          <select v-model="scenario" style="margin-left:6px;padding:4px">
            <option v-for="s in scenarioList" :key="s.code" :value="s.code">{{ s.label }}</option>
          </select>
        </label>
        <label style="font-size:13px">用户数
          <input v-model.number="userCount" type="number" min="10" style="width:70px;margin-left:6px;padding:4px">
        </label>
        <label style="font-size:13px">窗口
          <input v-model="startDate" type="date" style="margin-left:6px;padding:4px"> ~
          <input v-model="endDate" type="date" style="padding:4px">
        </label>
        <button @click="runDemo" :disabled="busy" style="padding:6px 16px;background:#3b82f6;color:#fff;border:none;border-radius:6px">
          {{ busy ? '运行中…' : '生成并分析（一键）' }}
        </button>
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
          <th style="padding:8px">ID</th><th>流水线</th><th>业务时间</th><th>状态</th><th>尝试</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in runs" :key="r.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:8px">{{ r.id }}</td>
            <td>{{ r.pipelineCode }}</td>
            <td>{{ r.businessTime }}</td>
            <td :style="{ color: r.status === 'SUCCESS' ? '#16a34a' : (r.status === 'FAILED' ? '#dc2626' : '#d97706') }">
              {{ r.status }}
            </td>
            <td>{{ r.attemptNo }}</td>
            <td><button v-if="r.status === 'FAILED'" @click="retry(r.id)" style="font-size:12px">重试</button></td>
          </tr>
          <tr v-if="runs.length === 0"><td colspan="6" class="el-empty">暂无运行记录</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const scenario = ref('normal')
const scenarioList = ref([])
const userCount = ref(50)
const startDate = ref(new Date(Date.now() - 86400000).toISOString().slice(0, 10))
const endDate = ref(new Date().toISOString().slice(0, 10))
const busy = ref(false)
const runs = ref([])
const runResult = ref(null)

async function runDemo() {
  busy.value = true
  runResult.value = null
  try {
    // 一键演示（§3.5.2）：生成 → 采集（发布由定时器完成）→ 流水线 → 快照
    await api.generatorRun({
      userCount: userCount.value, productCount: 0, eventsPerSecond: 2,
      baseConversionRate: 0.04,
      startTime: startDate.value + 'T09:00:00', endTime: endDate.value + 'T18:00:00',
      randomSeed: 20260906, dirtyDataRate: 0, scenario: scenario.value
    })
    // 等待 outbox 发布（10s 轮询批次），手动补一轮
    await new Promise((r) => setTimeout(r, 3000))
    await api.ingestionRun()
    runResult.value = await api.createPipelineRun({
      runtimeProfileId: 1, pipelineCode: 'DAILY_CORE',
      businessTime: startDate.value + 'T00:00:00', sourceDataVersion: 'demo-' + Date.now()
    }, 'demo-' + Date.now())
    await loadRuns()
  } catch (e) {
    runResult.value = { status: 'FAILED: ' + (e.message || e) }
  } finally {
    busy.value = false
  }
}

async function loadRuns() {
  try { runs.value = await api.pipelineRuns(10) } catch (e) { console.error(e) }
}

async function retry(id) {
  runResult.value = await api.retryPipelineRun(id)
  await loadRuns()
}

onMounted(async () => {
  await loadRuns()
  try { scenarioList.value = await api.scenarios() } catch (e) { /* 无场景表时忽略 */ }
})
</script>