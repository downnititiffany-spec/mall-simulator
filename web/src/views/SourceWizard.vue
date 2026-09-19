<template>
  <div>
    <div class="page-title">接入向导（选源 → 受控样本 → 预览 → 确认激活）</div>

    <div class="chart-box">
      <div class="chart-title">说明</div>
      <div class="table-hint">
        本向导只复用既有管理端点（源登记 / 映射 dry-run / 映射激活 / 源激活），
        <b>不要求编辑服务器文件</b>：候选画像以「粘贴原文」提交（服务端对提交字节做 sha256 作为权威
        profileChecksum），受控样本用 sample-root 下的仓库相对引用（服务端 fail-close 校验，
        不接受绝对路径 / .. / 协议前缀）。映射与源激活都会写服务端审计行。
      </div>
    </div>

    <!-- 第 1 步：选源 -->
    <div class="chart-box">
      <div class="chart-title">第 1 步 · 选择数据源（GET /sources；current=当前 ACTIVE 运行环境绑定的源）</div>
      <div class="table-hint" v-if="sourcesLoading">源列表加载中…</div>
      <div class="table-hint" v-else-if="!sources.length">接口未返回任何源（请先以管理员身份登记源）。</div>
      <table v-else style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:6px">选择</th><th>ID</th><th>sourceCode</th><th>名称</th>
          <th>模式</th><th>状态</th><th>画像版本</th><th>当前激活</th>
        </tr></thead>
        <tbody>
          <tr v-for="s in sources" :key="s.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px"><input type="radio" name="wizard-source" :value="s.id" v-model.number="sourceId" /></td>
            <td>{{ s.id }}</td>
            <td>{{ s.sourceCode }}</td>
            <td>{{ s.displayName }}</td>
            <td>{{ s.ingestMode }}</td>
            <td>{{ s.status }}</td>
            <td>{{ s.profileVersion || '—' }}</td>
            <td>
              <span v-if="s.current" style="color:#16a34a">是</span>
              <span v-else style="color:#6b7280">否</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 第 2 步：受控样本 + 画像原文 -->
    <div class="chart-box">
      <div class="chart-title">第 2 步 · 受控样本与候选画像（POST /sources/{id}/mappings/dry-run 的入参）</div>
      <div style="display:flex;gap:10px;align-items:flex-start;margin-bottom:10px;flex-wrap:wrap">
        <label style="font-size:13px">预置受控样本：
          <select v-model="presetRef" style="padding:4px" data-testid="wizard-preset-select">
            <option value="">— 选择 —</option>
            <option v-for="p in presets" :key="p.ref" :value="p.ref">{{ p.label }}</option>
          </select>
        </label>
        <label style="font-size:13px">或输入受控样本引用：
          <input v-model="sampleRefInput" placeholder="sample-root 下的仓库相对路径" style="padding:4px;width:280px" data-testid="wizard-sample-input" />
        </label>
        <label style="font-size:13px">行数上限(1..100)：
          <input v-model.number="limit" type="number" min="1" max="100" style="padding:4px;width:80px" />
        </label>
      </div>
      <div class="table-hint" style="margin-bottom:6px">
        预置引用来自当前运行环境的 sample-root 目录；手动输入只接受仓库相对路径，
        任意服务器路径由服务端拒绝（本页不做也不应做路径放行）。
      </div>
      <textarea v-model="profileText" rows="10" style="width:100%;font-family:monospace;font-size:12px;padding:6px"
                :placeholder="profilePlaceholder" data-testid="wizard-profile-text"></textarea>
      <div style="margin-top:6px;font-size:12px" :style="{ color: profileCheck.ok ? '#16a34a' : '#dc2626' }">
        {{ profileCheck.ok ? '画像原文 JSON 形状初检通过（fieldMappings 对象存在）；最终以服务端 Loader 为准。'
           : (profileText.trim() ? profileCheck.error : '') }}
      </div>
      <div style="margin-top:8px">
        <button style="font-size:12px" @click="runPreview" :disabled="previewDisabled || previewBusy" data-testid="wizard-preview-btn">
          {{ previewBusy ? '预览中…' : '生成预览报告' }}
        </button>
        <span v-if="previewGate.reason && !previewBusy" style="font-size:12px;color:#6b7280;margin-left:8px">{{ previewGate.reason }}</span>
      </div>
    </div>

    <!-- 第 3 步：预览报告 -->
    <div class="chart-box" v-if="report">
      <div class="chart-title">
        第 3 步 · 预览报告（reportId {{ report.reportId }}）
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <tbody>
          <tr v-for="r in summary.rows" :key="r.label" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px;color:#6b7280;width:160px">{{ r.label }}</td>
            <td style="padding:6px">{{ r.value }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="reasonRows.length" style="margin-top:8px">
        <div style="font-size:13px;font-weight:600;margin-bottom:4px">违规计数（按违例条数，非隔离行数）</div>
        <table style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280"><th style="padding:6px">原因</th><th>条数</th></tr></thead>
          <tbody>
            <tr v-for="r in reasonRows" :key="r.reason" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ r.reason }}</td><td>{{ r.count }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="summary.profileIssues.length" style="margin-top:8px;font-size:12px;color:#dc2626">
        画像装载失败原因：<span v-for="(i, idx) in summary.profileIssues" :key="idx">{{ issueText(i) }}；</span>
      </div>
      <div style="margin-top:10px;font-size:13px" data-testid="wizard-eligible">
        <span v-if="summary.eligible" style="color:#16a34a">activationEligible = true（预览判据全绿）</span>
        <template v-else>
          <span style="color:#dc2626">activationEligible = false</span>
          <div style="font-size:12px;color:#6b7280;margin-top:4px">
            原因清单：<template v-if="summary.ineligibleReasons.length">{{ summary.ineligibleReasons.join('；') }}</template><template v-else>接口未提供逐条原因。</template>
            <template v-if="summary.blocks.length"> 激活阻断项：{{ summary.blocks.join('；') }}。</template>
            <template v-if="summary.gaps.length"> 能力缺口：{{ summary.gaps.join('；') }}。</template>
          </div>
        </template>
      </div>
      <div class="table-hint" style="margin-top:6px">
        口径说明（D-030）：dry-run 预览严于生产装载器（Loader），是 advisory preflight——
        activationEligible=false 不必然等于激活被拒，激活门槛以激活接口的实际结果为准；
        反之预览全绿也不豁免激活时的漂移校验（409 MAPPING_PROFILE_CHANGED）。
      </div>
    </div>

    <!-- 第 4 步：确认激活 -->
    <div class="chart-box" v-if="report">
      <div class="chart-title">第 4 步 · 确认激活（先映射后源；两步都写服务端审计）</div>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <button style="font-size:12px;background:#16a34a" @click="activateMapping" :disabled="!report || mappingBusy || sourceBusy" data-testid="wizard-activate-mapping-btn">
          {{ mappingBusy ? '映射激活中…' : '① 激活映射（按报告 ' + (report ? report.reportId : '') + '）' }}
        </button>
        <button style="font-size:12px;background:#2563eb" @click="activateSource" :disabled="!mappingDone || sourceBusy || mappingBusy" data-testid="wizard-activate-source-btn">
          {{ sourceBusy ? '源激活中…' : '② 激活源（绑定到当前运行环境）' }}
        </button>
      </div>
      <div class="table-hint" style="margin-top:6px">
        ② 依赖 ①：源激活前必须先有映射激活成功（本会话内记录 mappingDone）。
      </div>
      <div v-if="mappingError" style="margin-top:8px;font-size:13px;color:#dc2626" data-testid="wizard-activation-error">{{ mappingError }}</div>
      <div v-if="activationRows" style="margin-top:8px">
        <table style="width:100%;border-collapse:collapse;font-size:13px">
          <tbody>
            <tr v-for="r in activationRows" :key="r.label" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px;color:#6b7280;width:160px">{{ r.label }}</td>
              <td style="padding:6px">{{ r.value }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="sourceResult" style="margin-top:8px;font-size:13px" data-testid="wizard-source-result">
        源激活结果：sourceId {{ sourceResult.id }} / {{ sourceResult.sourceCode }} 状态
        <b :style="{ color: sourceResult.status === 'ACTIVE' ? '#16a34a' : '#6b7280' }">{{ sourceResult.status }}</b>
        <template v-if="sourceResult.current && wasCurrentAtActivate">（已是当前运行环境绑定的源，幂等）</template>
        <template v-else-if="sourceResult.current">（已切换为当前运行环境绑定的源）</template>
      </div>
      <div v-if="sourceError" style="margin-top:8px;font-size:13px;color:#dc2626">{{ sourceError }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import api from '../api'
import {
  PRESET_SAMPLE_REFS, PROFILE_PLACEHOLDER, canPreview, validateProfileText,
  summarizeReport, reasonCountRows, activationErrorMessage, summarizeActivation
} from '../utils/sourceWizard'

// —— 第 1 步：源列表 ——
const sources = ref([])
const sourcesLoading = ref(true)
const sourceId = ref(null)
const loadSources = async () => {
  sourcesLoading.value = true
  try {
    sources.value = (await api.sources()) || []
  } catch (e) {
    sources.value = []
  } finally {
    sourcesLoading.value = false
  }
}
loadSources()

// —— 第 2 步：样本 + 画像 ——
const presets = PRESET_SAMPLE_REFS
const profilePlaceholder = PROFILE_PLACEHOLDER
const presetRef = ref('')
const sampleRefInput = ref('')
const limit = ref(20)
const profileText = ref('')

// 预置与手动输入互斥：最后操作的一个生效。选预置即把引用回填输入框（覆盖之前手输的旧引用，
// 输入框始终显示实际将提交的 ref，仍可再编辑）；手输值经 || 优先级覆盖预置选择。
const effectiveSampleRef = computed(() => (sampleRefInput.value || '').trim() || presetRef.value)
watch(presetRef, (v) => { if (v) sampleRefInput.value = v })

const profileCheck = computed(() => validateProfileText(profileText.value))
const previewGate = computed(() => canPreview({
  sourceId: sourceId.value, sampleRef: effectiveSampleRef.value,
  profileText: profileText.value, limit: limit.value
}))
const previewDisabled = computed(() => !previewGate.value.ok)
const previewBusy = ref(false)

// —— 第 3 步：报告 ——
const report = ref(null)
const summary = ref(null)
const reasonRows = ref([])

const runPreview = async () => {
  previewBusy.value = true
  try {
    const r = await api.mappingDryRun(sourceId.value, {
      profileText: profileText.value,
      sampleRef: effectiveSampleRef.value,
      limit: limit.value
    })
    applyReport(r)
  } catch (e) {
    report.value = null
    summary.value = null
    reasonRows.value = []
    mappingError.value = '预览失败：' + activationErrorMessage(e)
  } finally {
    previewBusy.value = false
  }
}

const applyReport = (r) => {
  report.value = r
  summary.value = summarizeReport(r)
  reasonRows.value = reasonCountRows(r)
  // 新报告使上一次激活状态失效：激活授权绑定 reportId
  mappingDone.value = false
  activationRows.value = null
  sourceResult.value = null
  mappingError.value = ''
  sourceError.value = ''
}

const issueText = (i) => {
  if (i && typeof i === 'object') {
    return i.message || i.reason || i.code || JSON.stringify(i)
  }
  return String(i)
}

// —— 第 4 步：激活 ——
const mappingBusy = ref(false)
const sourceBusy = ref(false)
const mappingDone = ref(false)
const mappingError = ref('')
const sourceError = ref('')
const activationRows = ref(null)
const sourceResult = ref(null)

const activateMapping = async () => {
  mappingBusy.value = true
  mappingError.value = ''
  try {
    const outcome = await api.mappingActivate(sourceId.value, {
      reportId: report.value.reportId,
      expectedProfileChecksum: report.value.profileChecksum
    })
    activationRows.value = summarizeActivation(outcome)
    mappingDone.value = true
  } catch (e) {
    mappingDone.value = false
    mappingError.value = activationErrorMessage(e)
  } finally {
    mappingBusy.value = false
  }
}

const wasCurrentAtActivate = ref(false)
const activateSource = async () => {
  sourceBusy.value = true
  sourceError.value = ''
  // 记录调用前的 current，区分「本来就当前（幂等）」与「本次真正切换」——激活响应里的
  // current 是切换后状态，恒为 true，不能用来区分这两种情形
  wasCurrentAtActivate.value = !!(sources.value || []).find((s) => s.id === sourceId.value)?.current
  try {
    sourceResult.value = await api.sourceActivate(sourceId.value)
  } catch (e) {
    sourceError.value = activationErrorMessage(e)
  } finally {
    sourceBusy.value = false
  }
}
</script>
