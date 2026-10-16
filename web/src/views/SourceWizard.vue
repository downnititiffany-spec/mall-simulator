<template>
  <main class="source-wizard">
    <h1 class="page-title">数据源接入向导</h1>
    <p class="wizard-intro">按顺序选择数据源、准备受控样本、预览映射结果，再确认激活。预览不会写入正式数据。</p>

    <!-- 步骤条：仅按既有状态渲染进度，不参与任何校验判定 -->
    <div class="steps" role="list" aria-label="接入流程进度">
      <div class="step" role="listitem" :class="report ? 'is-done' : 'is-active'" :aria-current="!report ? 'step' : undefined">
        <span class="step-no">1</span>选择数据源
      </div>
      <span class="step-line" aria-hidden="true"></span>
      <div class="step" role="listitem" :class="report ? 'is-done' : ''">
        <span class="step-no">2</span>受控样本与画像
      </div>
      <span class="step-line" aria-hidden="true"></span>
      <div class="step" role="listitem" :class="report ? (mappingDone ? 'is-done' : 'is-active') : ''" :aria-current="report && !mappingDone ? 'step' : undefined">
        <span class="step-no">3</span>预览报告
      </div>
      <span class="step-line" aria-hidden="true"></span>
      <div class="step" role="listitem" :class="activationRows ? 'is-done' : (mappingDone ? 'is-active' : '')" :aria-current="mappingDone && !activationRows ? 'step' : undefined">
        <span class="step-no">4</span>确认激活
      </div>
    </div>

    <div class="chart-box">
      <h2 class="chart-title">操作说明</h2>
      <div class="table-hint">
        本向导只复用既有管理端点（源登记 / 映射 dry-run / 映射激活 / 源激活），
        <b>不要求编辑服务器文件</b>：候选画像以「粘贴原文」提交（服务端对提交字节做 sha256 作为权威
        profileChecksum），受控样本用 sample-root 下的仓库相对引用（服务端 fail-close 校验，
        不接受绝对路径 / .. / 协议前缀）。映射与源激活都会写服务端审计行。
      </div>
    </div>

    <!-- 第 1 步：选源 -->
    <div class="chart-box">
      <div class="section-heading">
        <h2 class="chart-title">第 1 步 · 选择数据源</h2>
        <button class="btn btn-sm" type="button" @click="loadSources" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy" aria-label="重新加载数据源列表">
          {{ sourcesLoading ? '加载中…' : '刷新列表' }}
        </button>
      </div>
      <p class="table-hint">“当前激活”表示当前运行环境绑定的数据源；切换选择不会立即改变系统配置。</p>
      <div v-if="sourcesLoading" class="table-hint" role="status" aria-live="polite">正在加载数据源列表…</div>
      <div v-else-if="sourcesError" class="wizard-alert" role="alert" data-testid="wizard-source-error">
        <p>数据源列表加载失败：{{ sourcesError }}</p>
        <button class="btn btn-sm" type="button" @click="loadSources" :disabled="sourcesLoading || mappingBusy || sourceBusy">重试</button>
      </div>
      <p v-else-if="!sources.length" class="table-hint" role="status">
        当前没有已登记的数据源。请由管理员先完成数据源登记，再返回此处刷新列表。
      </p>
      <fieldset v-else class="source-list">
        <legend class="visually-hidden">选择要配置的数据源</legend>
        <label v-for="s in sources" :key="s.id" class="source-card" :class="{ 'is-selected': sourceId === s.id }">
          <input type="radio" name="wizard-source" :value="s.id" v-model.number="sourceId"
                 :aria-describedby="`source-meta-${s.id}`" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy" />
          <span class="source-card-content">
            <span class="source-title-row">
              <strong>{{ s.displayName || s.sourceCode || `数据源 ${s.id}` }}</strong>
              <span class="status-chip" :data-active="s.status === 'ACTIVE'">{{ s.status || '状态未知' }}</span>
            </span>
            <span class="source-meta" :id="`source-meta-${s.id}`">
              <span>来源编码：{{ s.sourceCode || '未提供' }}</span>
              <span>接入模式：{{ s.ingestMode || '未提供' }}</span>
              <span>画像版本：{{ s.profileVersion || '尚无' }}</span>
              <span>当前运行环境：{{ s.current ? '已绑定' : '未绑定' }}</span>
            </span>
          </span>
        </label>
      </fieldset>
    </div>

    <!-- 第 2 步：受控样本 + 画像原文 -->
    <div class="chart-box">
      <h2 class="chart-title">第 2 步 · 受控样本与候选画像</h2>
      <div class="form-grid">
        <label for="wizard-preset-select">预置受控样本
          <select id="wizard-preset-select" v-model="presetRef" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy" data-testid="wizard-preset-select">
            <option value="">— 选择 —</option>
            <option v-for="p in presets" :key="p.ref" :value="p.ref">{{ p.label }}</option>
          </select>
        </label>
        <label for="wizard-sample-input">受控样本引用
          <input id="wizard-sample-input" v-model="sampleRefInput" placeholder="sample-root 下的仓库相对路径" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy" data-testid="wizard-sample-input" />
        </label>
        <label for="wizard-limit">预览行数上限（1–100）
          <input id="wizard-limit" v-model.number="limit" type="number" min="1" max="100" inputmode="numeric" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy" />
        </label>
      </div>
      <p class="table-hint">
        预置引用来自当前运行环境的 sample-root 目录；手动输入只接受仓库相对路径，
        任意服务器路径由服务端拒绝（本页不做也不应做路径放行）。
      </p>
      <label class="profile-label" for="wizard-profile-text">候选映射画像 JSON 原文</label>
      <textarea id="wizard-profile-text" v-model="profileText" rows="10" :placeholder="profilePlaceholder"
                :aria-describedby="'profile-check profile-hint'" :disabled="sourcesLoading || previewBusy || mappingBusy || sourceBusy"
                data-testid="wizard-profile-text"></textarea>
      <p id="profile-hint" class="table-hint">请粘贴管理员准备的画像 JSON。页面只做格式初检；服务端 Loader 的校验结果才是最终依据。</p>
      <div id="profile-check" class="field-feedback" :data-ok="profileCheck.ok" role="status" aria-live="polite">
        {{ profileCheck.ok ? '画像原文 JSON 形状初检通过（识别到 V1 fieldMapping 或 V2 fieldMappings）；最终以服务端 Loader 为准。'
           : (profileText.trim() ? profileCheck.error : '') }}
      </div>
      <div class="preview-action">
        <button class="btn btn-sm" type="button" @click="runPreview" :disabled="previewDisabled || sourcesLoading || previewBusy || mappingBusy || sourceBusy" data-testid="wizard-preview-btn">
          {{ previewBusy ? '预览中…' : '生成预览报告' }}
        </button>
        <span v-if="previewGate.reason && !previewBusy" class="field-feedback" id="preview-gate">{{ previewGate.reason }}</span>
      </div>
      <div v-if="previewBusy" class="wizard-loading" role="status" aria-live="polite">正在读取受控样本并生成预览，请稍候…</div>
      <div v-if="previewError" class="wizard-alert" role="alert" data-testid="wizard-preview-error">{{ previewError }}</div>
    </div>

    <!-- 第 3 步：预览报告 -->
    <div class="chart-box" v-if="report">
      <h2 class="chart-title">
        第 3 步 · 预览报告（reportId {{ report.reportId }}）
      </h2>
      <table class="data-table">
        <tbody>
          <tr v-for="r in summary.rows" :key="r.label" style="border-top:1px solid var(--gray-100)">
            <td style="padding:6px;color:var(--gray-500);width:160px">{{ r.label }}</td>
            <td style="padding:6px">{{ r.value }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="reasonRows.length" style="margin-top:8px">
        <div style="font-size:13px;font-weight:600;margin-bottom:4px">违规计数（按违例条数，非隔离行数）</div>
        <table class="data-table">
          <thead><tr style="text-align:left;color:var(--gray-500)"><th style="padding:6px">原因</th><th>条数</th></tr></thead>
          <tbody>
            <tr v-for="r in reasonRows" :key="r.reason" style="border-top:1px solid var(--gray-100)">
              <td style="padding:6px">{{ r.reason }}</td><td>{{ r.count }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="summary.profileIssues.length" style="margin-top:8px;font-size:12px;color:#dc2626">
        画像装载失败原因：<span v-for="(i, idx) in summary.profileIssues" :key="idx">{{ issueText(i) }}；</span>
      </div>
      <div style="margin-top:10px;font-size:13px" data-testid="wizard-eligible">
        <span v-if="summary.eligible" style="color:var(--success)">activationEligible = true（预览判据全绿）</span>
        <template v-else>
          <span style="color:#dc2626">activationEligible = false</span>
          <div style="font-size:12px;color:var(--gray-500);margin-top:4px">
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
      <h2 class="chart-title">第 4 步 · 确认激活（先映射后源；两步都写服务端审计）</h2>
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
        <button class="btn btn-sm btn-success" @click="activateMapping" :disabled="!report || !sourceId || sourcesLoading || mappingBusy || sourceBusy" data-testid="wizard-activate-mapping-btn">
          {{ mappingBusy ? '映射激活中…' : '① 激活映射（按报告 ' + (report ? report.reportId : '') + '）' }}
        </button>
        <button class="btn btn-sm btn-primary" @click="activateSource" :disabled="!mappingDone || !sourceId || sourcesLoading || sourceBusy || mappingBusy" data-testid="wizard-activate-source-btn">
          {{ sourceBusy ? '源激活中…' : '② 激活源（绑定到当前运行环境）' }}
        </button>
      </div>
      <div class="table-hint" style="margin-top:6px">
        ② 依赖 ①：源激活前必须先有映射激活成功（本会话内记录 mappingDone）。
      </div>
      <div v-if="mappingError" class="wizard-alert" role="alert" data-testid="wizard-activation-error">{{ mappingError }}</div>
      <div v-if="activationRows" style="margin-top:8px">
        <table class="data-table">
          <tbody>
            <tr v-for="r in activationRows" :key="r.label" style="border-top:1px solid var(--gray-100)">
              <td style="padding:6px;color:var(--gray-500);width:160px">{{ r.label }}</td>
              <td style="padding:6px">{{ r.value }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="sourceResult" style="margin-top:8px;font-size:13px" data-testid="wizard-source-result">
        源激活结果：sourceId {{ sourceResult.id }} / {{ sourceResult.sourceCode }} 状态
        <b :style="{ color: sourceResult.status === 'ACTIVE' ? 'var(--success)' : 'var(--gray-500)' }">{{ sourceResult.status }}</b>
        <template v-if="sourceResult.current && wasCurrentAtActivate">（已是当前运行环境绑定的源，幂等）</template>
        <template v-else-if="sourceResult.current">（已切换为当前运行环境绑定的源）</template>
      </div>
      <div v-if="sourceError" class="wizard-alert" role="alert">{{ sourceError }}</div>
    </div>
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import api from '../api'
import {
  PRESET_SAMPLE_REFS, PROFILE_PLACEHOLDER, canPreview, validateProfileText,
  summarizeReport, reasonCountRows, activationErrorMessage, summarizeActivation
} from '../utils/sourceWizard'
import { requireArrayResponse } from '../utils/arrayResponse.js'

// —— 第 1 步：源列表 ——
const sources = ref([])
const sourcesLoading = ref(true)
const sourcesError = ref('')
const sourceId = ref(null)
const loadSources = async () => {
  if (mappingBusy.value || sourceBusy.value) return
  sourcesLoading.value = true
  sourcesError.value = ''
  // 来源配置可能已在别处变化；刷新开始后旧报告不能继续用于写操作。
  previewRequestSequence += 1
  previewBusy.value = false
  clearPreview()
  try {
    sources.value = requireArrayResponse(await api.sources(), '数据来源')
    if (sourceId.value !== null && !sources.value.some((source) => source.id === sourceId.value)) {
      sourceId.value = null
    }
  } catch (e) {
    sources.value = []
    sourceId.value = null
    sourcesError.value = activationErrorMessage(e)
  } finally {
    sourcesLoading.value = false
  }
}

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
const previewError = ref('')
let previewRequestSequence = 0

const clearPreview = () => {
  report.value = null
  summary.value = null
  reasonRows.value = []
  mappingDone.value = false
  activationRows.value = null
  sourceResult.value = null
  mappingError.value = ''
  sourceError.value = ''
  previewError.value = ''
}

// 预览报告只对生成它时的源、画像、样本和行数有效；任一输入改变都必须重新预览。
watch([sourceId, presetRef, sampleRefInput, limit, profileText], () => {
  previewRequestSequence += 1
  previewBusy.value = false
  if (report.value) clearPreview()
  else previewError.value = ''
}, { flush: 'sync' })

const runPreview = async () => {
  if (sourcesLoading.value || mappingBusy.value || sourceBusy.value) return
  const requestSequence = ++previewRequestSequence
  const requestSourceId = sourceId.value
  const requestBody = {
    profileText: profileText.value,
    sampleRef: effectiveSampleRef.value,
    limit: limit.value
  }
  clearPreview()
  previewBusy.value = true
  try {
    const r = await api.mappingDryRun(requestSourceId, requestBody)
    if (requestSequence !== previewRequestSequence) return
    applyReport(r)
  } catch (e) {
    if (requestSequence !== previewRequestSequence) return
    previewError.value = '预览失败：' + activationErrorMessage(e)
  } finally {
    if (requestSequence === previewRequestSequence) previewBusy.value = false
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
  previewError.value = ''
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

// 初始请求放在状态初始化之后，使其与手动刷新走同一失效路径。
loadSources()

const activateMapping = async () => {
  if (!report.value || !sourceId.value || previewBusy.value || mappingBusy.value || sourceBusy.value) return
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
  if (!mappingDone.value || !sourceId.value || previewBusy.value || mappingBusy.value || sourceBusy.value) return
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

<style scoped>
.source-wizard { min-width: 0; }
.wizard-intro { margin: -8px 0 18px; color: var(--gray-600, #475569); line-height: 1.55; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.source-list { border: 0; margin: 12px 0 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 10px; }
.source-card { display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 10px; min-height: 64px; padding: 12px; border: 1px solid var(--gray-200, #e2e8f0); border-radius: 8px; cursor: pointer; }
.source-card:hover { border-color: var(--primary, #2563eb); }
.source-card:focus-within { outline: 3px solid rgba(37, 99, 235, .35); outline-offset: 2px; }
.source-card.is-selected { border-color: var(--primary, #2563eb); background: rgba(37, 99, 235, .06); }
.source-card input { width: 20px; height: 20px; margin: 2px; }
.source-card-content { display: grid; gap: 8px; min-width: 0; }
.source-title-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.source-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 12px; color: var(--gray-600, #475569); font-size: 14px; overflow-wrap: anywhere; }
.status-chip { display: inline-flex; align-items: center; min-height: 28px; padding: 2px 8px; border-radius: 999px; background: var(--gray-100, #f1f5f9); color: var(--gray-700, #334155); font-size: 13px; }
.status-chip[data-active="true"] { background: #dcfce7; color: #166534; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr)); gap: 12px; margin-bottom: 12px; }
.form-grid label, .profile-label { display: grid; gap: 6px; font-weight: 600; }
.form-grid input, .form-grid select, .source-wizard textarea { box-sizing: border-box; width: 100%; min-height: 44px; padding: 10px; border: 1px solid var(--gray-300, #cbd5e1); border-radius: 6px; font: inherit; color: inherit; background: var(--surface, #fff); }
.source-wizard textarea { min-height: 180px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 14px; }
.source-wizard input:focus-visible, .source-wizard select:focus-visible, .source-wizard textarea:focus-visible, .source-wizard button:focus-visible { outline: 3px solid rgba(37, 99, 235, .75); outline-offset: 2px; }
.source-wizard .btn { min-height: 44px; }
.field-feedback { margin-top: 6px; line-height: 1.5; color: var(--gray-700, #334155); }
.field-feedback[data-ok="false"] { color: #b91c1c; }
.field-feedback[data-ok="true"] { color: #166534; }
.preview-action { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.wizard-alert { margin-top: 10px; padding: 12px; border: 1px solid #fecaca; border-radius: 6px; background: #fef2f2; color: #991b1b; line-height: 1.5; }
.wizard-alert p { margin: 0 0 8px; }
.wizard-loading { margin-top: 10px; padding: 12px; border-radius: 6px; background: var(--gray-50, #f8fafc); color: var(--gray-700, #334155); line-height: 1.5; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 640px) {
  .source-wizard { font-size: 16px; }
  .source-meta { grid-template-columns: minmax(0, 1fr); }
  .section-heading { align-items: flex-start; }
}
</style>
