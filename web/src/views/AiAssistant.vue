<template>
  <div>
    <div class="page-title">智能分析助手</div>
    <div class="chart-box">
      <div class="chart-title">自然语言问数（语义层 → 受控 Text-to-SQL → 安全校验 → 证据解释）</div>
      <div style="display:flex;gap:10px">
        <input v-model="question" @keyup.enter="ask" placeholder="例如：最近 7 天销售额变化趋势如何？"
               style="flex:1;padding:8px" />
        <button @click="ask" :disabled="busy"
                style="padding:8px 18px;background:#7c3aed;color:#fff;border:none;border-radius:6px">
          {{ busy ? '分析中…' : '发送' }}
        </button>
      </div>
      <div v-if="busy" style="margin-top:10px;font-size:13px;color:#6b7280">
        执行状态：理解问题 → 生成 SQL → 安全校验 → 查询数据 → 生成解释…
      </div>
      <div v-if="error" style="margin-top:10px;font-size:13px;color:#dc2626">{{ error }}</div>
    </div>

    <template v-if="result">
      <div class="chart-box">
        <div class="chart-title">结论</div>
        <div style="font-size:14px;line-height:1.7">{{ result.explanation.summary || '（无结论）' }}</div>
        <div v-if="result.query.status" style="margin-top:8px;font-size:12px;color:#6b7280">
          管道状态：{{ result.query.status }}（模型：{{ result.query.providerUsed }}，行数：{{ result.query.rowsReturned }}，耗时：{{ result.query.elapsedMs }}ms）
        </div>
      </div>

      <div class="chart-box">
        <div class="chart-title">数据依据（真实查询结果）</div>
        <table v-if="result.query.rows && result.query.rows.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th v-for="k in Object.keys(result.query.rows[0])" :key="k" style="padding:6px">{{ k }}</th>
          </tr></thead>
          <tbody>
            <tr v-for="(row, i) in result.query.rows" :key="i" style="border-top:1px solid #f3f4f6">
              <td v-for="(v, k) in row" :key="k" style="padding:6px">{{ v }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">当前时间范围无数据，不编造结论</div>
      </div>

      <div class="chart-box" v-if="explanationSection.length">
        <div class="chart-title">建议与可能原因</div>
        <div v-for="(s, i) in explanationSection" :key="i" style="font-size:13px;line-height:1.8;padding:4px 0">
          • {{ s }}
        </div>
      </div>

      <div class="chart-box">
        <div class="chart-title">证据（SQL + 口径）</div>
        <pre style="background:#f9fafb;padding:10px;border-radius:6px;font-size:12px;overflow:auto">{{ result.query.sql }}</pre>
        <div style="font-size:12px;color:#6b7280;margin-top:6px">
          使用表：{{ (result.query.tables || []).join(', ') }}；快照：{{ result.explanation.evidence.snapshotId }}；
          口径：{{ result.explanation.evidence.timeRange }}
          <template v-if="result.explanation.limitations && result.explanation.limitations.length">
            <br />限制说明：{{ result.explanation.limitations.join('；') }}
          </template>
        </div>
      </div>
    </template>

    <div class="chart-box">
      <div class="chart-title">推荐问题</div>
      <div style="display:flex;flex-wrap:wrap;gap:8px">
        <button v-for="q in recommended" :key="q" @click="question = q; ask()"
                style="padding:6px 12px;border:1px solid #e5e7eb;background:#fff;border-radius:16px;font-size:13px;cursor:pointer">
          {{ q }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import api from '../api'

const question = ref('')
const busy = ref(false)
const result = ref(null)
const error = ref('')

const recommended = [
  '最近 7 天销售额变化趋势如何？',
  '最近一天的销售额是否明显偏离近 7 日平均水平？',
  '9月4日转化漏斗各阶段人数是多少？',
  '最新一期的 GMV 和退款率是多少？'
]

const explanationSection = computed(() => {
  if (!result.value || !result.value.explanation) return []
  const out = []
  ;(result.value.explanation.possibleCauses || []).forEach((p) => out.push('可能原因：' + p.statement))
  ;(result.value.explanation.suggestions || []).forEach((s) => out.push('建议：' + s.title + ' — ' + s.action))
  return out
})

async function ask() {
  if (!question.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await api.post('/ai/queries', { question: question.value.trim(), timeRange: '近30天' })
  } catch (e) {
    error.value = '请求失败：' + (e.message || e)
  } finally {
    busy.value = false
  }
}
</script>