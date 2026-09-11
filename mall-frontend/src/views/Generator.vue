<template>
  <div>
    <div class="chart-box">
      <div class="box-title">数据生成器（模拟商城侧）</div>
      <p class="hint">
        生成器通过商城业务 Service 造数据并写入事件出口（outbox → landing），
        分析平台再按 ODS→DWD→DWS→ADS 链路加工。该页面属模拟商城，分析平台前端不再提供生成入口（§18.4）。
      </p>
      <div class="form-grid">
        <div class="field">
          <label for="scenario">经营场景</label>
          <select id="scenario" v-model="form.scenario">
            <option v-for="s in scenarios" :key="s.code" :value="s.code">{{ s.label }}（{{ s.code }}）</option>
          </select>
        </div>
        <div class="field">
          <label for="userCount">用户数</label>
          <input id="userCount" v-model.number="form.userCount" type="number" min="1" />
        </div>
        <div class="field">
          <label for="productCount">商品数</label>
          <input id="productCount" v-model.number="form.productCount" type="number" min="0" />
        </div>
        <div class="field">
          <label for="eps">事件速率（条/秒）</label>
          <input id="eps" v-model.number="form.eventsPerSecond" type="number" min="1" />
        </div>
        <div class="field">
          <label for="cvr">基础转化率（0~1）</label>
          <input id="cvr" v-model.number="form.baseConversionRate" type="number" step="0.01" min="0" max="1" />
        </div>
        <div class="field">
          <label for="dirty">脏数据比例（0~1）</label>
          <input id="dirty" v-model.number="form.dirtyDataRate" type="number" step="0.01" min="0" max="1" />
        </div>
        <div class="field">
          <label for="seed">随机种子</label>
          <input id="seed" v-model.number="form.randomSeed" type="number" />
        </div>
        <div class="field">
          <label for="start">开始时间</label>
          <input id="start" v-model="form.startTime" type="datetime-local" step="1" />
        </div>
        <div class="field">
          <label for="end">结束时间</label>
          <input id="end" v-model="form.endTime" type="datetime-local" step="1" />
        </div>
      </div>
      <div class="actions">
        <button class="btn primary" :disabled="running" @click="run">{{ running ? '生成中…' : '开始生成' }}</button>
        <span v-if="error" class="error-text">{{ error }}</span>
      </div>
      <div v-if="directions.length" class="hint">
        预期方向：<span v-for="d in directions" :key="d" class="tag">{{ d }}</span>
      </div>
    </div>

    <div v-if="result" class="chart-box">
      <div class="box-title">本次生成摘要（同种子应得到同摘要，§20.6）</div>
      <table class="data-table">
        <tbody>
          <tr><th>场景</th><td>{{ result.scenario }}</td><th>配置键</th><td class="mono">{{ result.configKey }}</td></tr>
          <tr><th>新建用户</th><td>{{ result.usersCreated }}</td><th>新建商品</th><td>{{ result.productsCreated }}</td></tr>
          <tr><th>事件总数</th><td>{{ result.totalEvents }}</td><th>行为构成</th><td>{{ behaviors }}</td></tr>
          <tr><th>创建订单</th><td>{{ result.ordersCreated }}</td><th>支付订单</th><td>{{ result.ordersPaid }}</td></tr>
          <tr><th>取消订单</th><td>{{ result.ordersCancelled }}</td><th>完成订单</th><td>{{ result.ordersCompleted }}</td></tr>
          <tr><th>退款申请</th><td>{{ result.refundsApplied }}</td><th>退款完成</th><td>{{ result.refundsCompleted }}</td></tr>
          <tr><th>GMV</th><td>{{ result.gmv }}</td><th>净销售额</th><td>{{ result.netSale }}</td></tr>
          <tr><th>客单价</th><td>{{ result.avgOrderValue }}</td><th>缺货命中</th><td>{{ result.stockShortageHits }}</td></tr>
          <tr><th>样例事件</th><td colspan="3" class="mono">{{ (result.sampleEventIds || []).join(', ') }}</td></tr>
        </tbody>
      </table>
      <p class="hint">生成完成不代表指标已更新：需在分析平台触发采集与流水线（ODS→DWD→DWS→ADS→发布），看板才会显示新快照。</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'

const now = new Date()
const pad = (n) => String(n).padStart(2, '0')
const toLocal = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
const start = new Date(now.getTime() - 3600 * 1000)

const form = reactive({
  scenario: 'normal_day',
  userCount: 20,
  productCount: 10,
  eventsPerSecond: 50,
  baseConversionRate: 0.3,
  dirtyDataRate: 0,
  randomSeed: 20260901,
  startTime: toLocal(start),
  endTime: toLocal(now)
})
const scenarios = ref([])
const running = ref(false)
const error = ref('')
const result = ref(null)

const behaviors = computed(() => {
  const m = (result.value && result.value.behaviorsByType) || {}
  return Object.entries(m).map(([k, v]) => `${k}=${v}`).join('，') || '—'
})
const directions = computed(() => {
  const s = scenarios.value.find((x) => x.code === form.scenario)
  return (s && s.expectedDirections && Object.values(s.expectedDirections)) || []
})

const run = async () => {
  if (running.value) return
  running.value = true
  error.value = ''
  try {
    result.value = await api.generatorRun({ ...form })
  } catch (e) {
    error.value = (e && e.message) || '生成失败'
  } finally {
    running.value = false
  }
}

onMounted(async () => {
  try {
    scenarios.value = (await api.scenarios()) || []
    if (scenarios.value.length && !scenarios.value.some((s) => s.code === form.scenario)) {
      form.scenario = scenarios.value[0].code
    }
  } catch (e) {
    error.value = '场景列表加载失败：' + ((e && e.message) || '')
  }
})
</script>
