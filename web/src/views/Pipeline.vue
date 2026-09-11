<template>
  <div>
    <div class="page-title">数据流水线</div>
    <div class="chart-box">
      <div class="chart-title">触发一次采集与流水线实例（分析平台侧，不调用模拟商城生成器）</div>
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

const businessDate = ref(new Date().toISOString().slice(0, 10))
const runtimeProfileId = ref(1)
const busy = ref(false)
const runs = ref([])
const runResult = ref(null)

async function runOnce() {
  busy.value = true
  runResult.value = null
  try {
    // 采集一次事件目录（发布由定时器完成），再创建流水线实例
    await api.ingestionRun()
    await new Promise((r) => setTimeout(r, 3000))
    runResult.value = await api.createPipelineRun({
      runtimeProfileId: runtimeProfileId.value, pipelineCode: 'DAILY_CORE',
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
  try { runs.value = await api.pipelineRuns(10) } catch (e) { console.error(e) }
}

async function retry(id) {
  runResult.value = await api.retryPipelineRun(id)
  await loadRuns()
}

onMounted(loadRuns)
</script>