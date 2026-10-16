<template>
  <div class="runtime-profiles-page">
    <div class="page-head">
      <div>
        <h1>运行环境</h1>
        <p class="page-desc">管理 Landing、Hive、Spark 与指标存储连接。只有通过连通性检查的环境才能激活。</p>
      </div>
      <div class="head-actions">
        <button class="btn btn-ghost" :disabled="loading || busy" @click="loadProfiles">刷新</button>
        <button class="btn btn-primary" :disabled="busy" @click="startCreate">新建环境</button>
      </div>
    </div>

    <div class="alert alert-warning safety-note">
      <div><strong>切换会改变后续任务使用的环境。</strong> 已运行任务保留启动时的环境快照；新环境必须先完成测试。凭据只填写安全凭据库中的引用，不要输入密码或 token。</div>
    </div>

    <div v-if="pageError" class="alert alert-danger" role="alert">{{ pageError }}</div>
    <div v-if="notice" class="alert alert-success" role="status">{{ notice }}</div>

    <section class="card">
      <div class="card-head">
        <div class="card-title">已登记环境 <span class="table-count">{{ profiles.length }} 条</span></div>
        <span v-if="loading" class="muted">正在读取…</span>
      </div>
      <div v-if="loading" class="empty-state">正在读取运行环境…</div>
      <div v-else-if="pageError && profiles.length === 0" class="empty-state">
        运行环境列表当前不可用，请修正错误后刷新。
      </div>
      <div v-else-if="profiles.length === 0" class="empty-state">
        尚无运行环境。新建草稿后先测试，再按需激活。
      </div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead><tr><th>环境</th><th>类型</th><th>绑定来源</th><th>状态</th><th>版本</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="profile in profiles" :key="profile.id" :class="{ 'active-row': profile.status === 'ACTIVE' }">
              <td><strong>{{ profile.profileName || profile.profileCode || `环境 ${profile.id}` }}</strong><span class="profile-code">{{ profile.profileCode }}</span></td>
              <td>{{ typeLabel(profile.type) }}</td>
              <td>{{ sourceLabel(profile.sourceId) }}</td>
              <td><span class="badge" :class="statusClass(profile.status)">{{ statusLabel(profile.status) }}</span></td>
              <td>{{ profile.version ?? '—' }}</td>
              <td>{{ formatDate(profile.updatedAt) }}</td>
              <td>
                <div class="row-actions">
                  <button class="btn btn-sm btn-ghost" :disabled="busy || profile.status === 'ACTIVE'" @click="startEdit(profile)">配置</button>
                  <button class="btn btn-sm btn-ghost" :disabled="busy || profile.status === 'ACTIVE'" @click="runTest(profile)">测试</button>
                  <button v-if="profile.status !== 'ACTIVE'" class="btn btn-sm btn-primary" :disabled="busy || !canActivate(profile)" @click="activate(profile)">激活</button>
                  <button v-else class="btn btn-sm btn-danger" :disabled="busy" @click="disable(profile)">停用</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="card-note">激活按钮要求本页面最近一次连通性测试通过；服务端激活时还会重新执行所有适用检查。</p>
    </section>

    <section v-if="formOpen" class="card editor-card">
      <div class="card-head">
        <div class="card-title">{{ editingId ? '编辑环境配置' : '新建环境草稿' }}</div>
        <button class="btn btn-sm btn-ghost" :disabled="saving" @click="closeForm">关闭</button>
      </div>
      <form class="profile-form" @submit.prevent="saveProfile">
        <div class="form-grid">
          <div class="field"><label for="profile-code">环境编码</label><input id="profile-code" v-model.trim="form.profileCode" class="input" required maxlength="64" :disabled="!!editingId" autocomplete="off"><span class="hint">创建后不可修改</span></div>
          <div class="field"><label for="profile-name">环境名称</label><input id="profile-name" v-model.trim="form.profileName" class="input" required maxlength="128"></div>
          <div class="field"><label for="profile-type">环境类型</label><select id="profile-type" v-model="form.type" class="select"><option value="LOCAL">本地</option><option value="SINGLE_NODE">单节点</option><option value="REMOTE_CLUSTER">远程集群</option></select></div>
          <div class="field"><label for="profile-source">绑定来源</label><select id="profile-source" v-model.number="form.sourceId" class="select"><option :value="null">请选择来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.sourceName || source.sourceCode }}（{{ source.sourceCode }}）</option></select><span class="hint">激活前需要绑定有效来源</span></div>
          <div class="field"><label for="profile-timezone">时区</label><input id="profile-timezone" v-model.trim="form.timezone" class="input" required placeholder="Asia/Shanghai"></div>
          <div class="field"><label for="profile-landing">Landing URI</label><input id="profile-landing" v-model.trim="form.landingUri" class="input" placeholder="file:///data/landing 或 hdfs://…"></div>
          <div class="field"><label for="profile-layout">Landing 文件布局</label><select id="profile-layout" v-model="form.landingLayout" class="select"><option value="ROLLING_LOG">滚动日志</option><option value="FLUME_RAW">Flume 原始目录</option></select></div>
          <div v-if="form.type !== 'LOCAL'" class="field"><label for="profile-hdfs">HDFS URI</label><input id="profile-hdfs" v-model.trim="form.hdfsUri" class="input" placeholder="hdfs://host:port"></div>
          <div v-if="form.type !== 'LOCAL'" class="field"><label for="profile-hive">HiveServer2 JDBC URL</label><input id="profile-hive" v-model.trim="form.hiveJdbcUrl" class="input" placeholder="jdbc:hive2://host:10000/default"></div>
          <div v-if="form.type !== 'LOCAL'" class="field"><label for="profile-credential">凭据引用</label><input id="profile-credential" v-model.trim="form.credentialRef" class="input" autocomplete="off" placeholder="凭据库中的引用键"><span class="hint">仅引用安全凭据，不保存或展示明文凭据</span></div>
          <div class="field"><label for="profile-spark-master">Spark Master</label><input id="profile-spark-master" v-model.trim="form.sparkMaster" class="input" placeholder="local[*] 或 yarn"></div>
          <div class="field"><label for="profile-spark-submit">spark-submit 路径</label><input id="profile-spark-submit" v-model.trim="form.sparkSubmitPath" class="input" placeholder="留空时不能通过 Spark 探测"></div>
          <div class="field"><label for="profile-deploy-mode">Deploy mode</label><input id="profile-deploy-mode" v-model.trim="form.deployMode" class="input" placeholder="client / cluster"></div>
          <div class="field"><label for="profile-yarn-queue">YARN 队列</label><input id="profile-yarn-queue" v-model.trim="form.yarnQueue" class="input"></div>
          <div class="field"><label for="profile-job-jar">Spark 作业 JAR URI</label><input id="profile-job-jar" v-model.trim="form.sparkJobJarUri" class="input"></div>
          <template v-if="form.type === 'REMOTE_CLUSTER'">
            <div class="field"><label for="profile-ssh-host">SSH 主机</label><input id="profile-ssh-host" v-model.trim="form.sshHost" class="input" autocomplete="off"></div>
            <div class="field"><label for="profile-ssh-port">SSH 端口</label><input id="profile-ssh-port" v-model.number="form.sshPort" class="input" type="number" min="1" max="65535"></div>
            <div class="field"><label for="profile-ssh-user">SSH 用户</label><input id="profile-ssh-user" v-model.trim="form.sshUser" class="input" autocomplete="off"></div>
          </template>
          <div class="field"><label for="profile-metric-type">指标存储类型</label><select id="profile-metric-type" v-model="form.metricStoreType" class="select"><option value="MYSQL">MySQL</option></select></div>
          <div class="field"><label for="profile-metric-ref">指标存储配置引用</label><input id="profile-metric-ref" v-model.trim="form.metricStoreConfigRef" class="input" autocomplete="off"></div>
        </div>
        <div class="form-actions">
          <span v-if="saveError" class="inline-error" role="alert">{{ saveError }}</span>
          <button type="button" class="btn btn-ghost" :disabled="saving" @click="closeForm">取消</button>
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '正在保存…' : '保存草稿配置' }}</button>
        </div>
      </form>
    </section>

    <section v-if="testResult" class="card test-card" aria-live="polite">
      <div class="card-head"><div class="card-title">连通性检查：{{ profileName(testResult.profileId) }}</div><span class="badge" :class="testResult.allPassed ? 'badge-success' : 'badge-danger'">{{ testResult.allPassed ? '全部适用项通过' : '存在未通过项' }}</span></div>
      <p class="test-summary">{{ testResult.summary }}</p>
      <ul class="check-list"><li v-for="(check, index) in testResult.checks || []" :key="`${check.name}-${index}`"><span>{{ check.name }}</span><span class="badge" :class="!check.applicable ? 'badge-neutral' : check.passed ? 'badge-success' : 'badge-danger'">{{ !check.applicable ? '不适用' : check.passed ? '通过' : '未通过' }}</span><span>{{ check.detail }}</span></li></ul>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { requireRuntimeProfileList } from '../utils/runtimeProfiles'

const profiles = ref([])
const sources = ref([])
const loading = ref(false)
const busy = ref(false)
const saving = ref(false)
const formOpen = ref(false)
const editingId = ref(null)
const pageError = ref('')
const saveError = ref('')
const notice = ref('')
const testResult = ref(null)
const latestPassedProfileId = ref(null)

const blankProfile = () => ({
  profileCode: '', profileName: '', type: 'LOCAL', sourceId: null, timezone: 'Asia/Shanghai',
  landingUri: '', landingLayout: 'ROLLING_LOG', hdfsUri: '', hiveJdbcUrl: '', credentialRef: '',
  sparkMaster: 'local[*]', sparkSubmitPath: '', deployMode: 'client', yarnQueue: '',
  sparkJobJarUri: '', sshHost: '', sshPort: 22, sshUser: '', metricStoreType: 'MYSQL',
  metricStoreConfigRef: ''
})
const form = reactive(blankProfile())
const sourceById = computed(() => new Map(sources.value.map((source) => [Number(source.id), source])))

const loadProfiles = async () => {
  loading.value = true
  pageError.value = ''
  profiles.value = []
  sources.value = []
  try {
    const [profileResult, sourceResult] = await Promise.allSettled([api.runtimeProfiles(), api.sources()])
    if (profileResult.status === 'rejected') throw profileResult.reason
    profiles.value = requireRuntimeProfileList(profileResult.value, '运行环境')
    if (sourceResult.status === 'fulfilled') {
      try {
        sources.value = requireRuntimeProfileList(sourceResult.value, '来源')
      } catch {
        pageError.value = '来源列表响应格式无效；绑定来源前请刷新重试。'
      }
    } else {
      pageError.value = '运行环境已读取，但来源列表不可用；绑定来源前请刷新重试。'
    }
  } catch (error) {
    pageError.value = error?.message || '无法读取运行环境或来源列表'
  } finally {
    loading.value = false
  }
}

const sourceLabel = (sourceId) => {
  if (sourceId == null) return '未绑定'
  const source = sourceById.value.get(Number(sourceId))
  return source ? (source.sourceName || source.sourceCode || `来源 ${sourceId}`) : `来源 ${sourceId}（未在列表中）`
}
const typeLabel = (type) => ({ LOCAL: '本地', SINGLE_NODE: '单节点', REMOTE_CLUSTER: '远程集群' })[type] || type || '未知'
const statusLabel = (status) => ({ ACTIVE: '生效中', DRAFT: '草稿', TESTING: '已测试', DISABLED: '已停用' })[status] || status || '未知'
const statusClass = (status) => ({ ACTIVE: 'badge-success', DRAFT: 'badge-neutral', TESTING: 'badge-info', DISABLED: 'badge-warning' })[status] || 'badge-neutral'
const formatDate = (value) => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
const profileName = (id) => profiles.value.find((profile) => Number(profile.id) === Number(id))?.profileName || `环境 ${id}`
const canActivate = (profile) => Number(latestPassedProfileId.value) === Number(profile.id)

const resetForm = (profile = null) => {
  Object.assign(form, blankProfile(), profile || {})
  form.sourceId = profile?.sourceId == null ? null : Number(profile.sourceId)
  form.landingLayout = profile?.landingLayout || 'ROLLING_LOG'
  form.sshPort = profile?.sshPort == null ? 22 : Number(profile.sshPort)
  editingId.value = profile?.id ?? null
  saveError.value = ''
  latestPassedProfileId.value = null
  testResult.value = null
  formOpen.value = true
}
const startCreate = () => { notice.value = ''; resetForm() }
const startEdit = async (profile) => {
  if (profile.status === 'ACTIVE') return
  notice.value = ''
  pageError.value = ''
  busy.value = true
  try {
    const latest = await api.runtimeProfile(profile.id)
    resetForm(latest)
  } catch (error) {
    pageError.value = error?.message || '无法读取环境配置'
  } finally {
    busy.value = false
  }
}
const closeForm = () => { formOpen.value = false; saveError.value = '' }

const profilePayload = () => {
  const payload = {}
  for (const key of Object.keys(blankProfile())) {
    const value = form[key]
    if (key === 'sourceId') payload[key] = value == null || value === '' ? null : Number(value)
    else if (key === 'sshPort') payload[key] = form.type === 'REMOTE_CLUSTER' && value ? Number(value) : null
    else payload[key] = typeof value === 'string' && value.trim() === ''
      ? (editingId.value ? '' : null)
      : value
  }
  if (form.type !== 'REMOTE_CLUSTER') {
    payload.sshHost = null
    payload.sshPort = null
    payload.sshUser = null
  }
  return payload
}

const saveProfile = async () => {
  saving.value = true
  saveError.value = ''
  pageError.value = ''
  try {
    const payload = profilePayload()
    if (editingId.value) await api.updateRuntimeProfile(editingId.value, payload)
    else await api.createRuntimeProfile(payload)
    closeForm()
    notice.value = editingId.value ? '环境配置已保存。请重新运行连通性检查。' : '环境草稿已创建。请先配置并运行连通性检查。'
    await loadProfiles()
    latestPassedProfileId.value = null
    testResult.value = null
  } catch (error) {
    saveError.value = error?.message || '保存环境配置失败'
  } finally {
    saving.value = false
  }
}

const runTest = async (profile) => {
  busy.value = true
  pageError.value = ''
  notice.value = ''
  testResult.value = null
  latestPassedProfileId.value = null
  try {
    const result = await api.testRuntimeProfile(profile.id)
    testResult.value = result
    if (result?.allPassed) latestPassedProfileId.value = profile.id
    await loadProfiles()
  } catch (error) {
    pageError.value = error?.message || '连通性检查请求失败'
  } finally {
    busy.value = false
  }
}

const activate = async (profile) => {
  if (!canActivate(profile)) return
  const confirmed = window.confirm(`激活“${profile.profileName || profile.profileCode}”会切换后续任务使用的运行环境。服务端将再次执行适用项检查，是否继续？`)
  if (!confirmed) return
  busy.value = true
  pageError.value = ''
  notice.value = ''
  try {
    await api.activateRuntimeProfile(profile.id)
    notice.value = `“${profile.profileName || profile.profileCode}”已激活。`
    latestPassedProfileId.value = null
    testResult.value = null
    await loadProfiles()
  } catch (error) {
    pageError.value = error?.message || '激活失败；当前环境未切换'
  } finally {
    busy.value = false
  }
}

const disable = async (profile) => {
  const confirmed = window.confirm(`停用“${profile.profileName || profile.profileCode}”后将没有该环境作为 ACTIVE。已有任务快照不变，但后续数据操作可能因无 ACTIVE 环境而失败。是否继续？`)
  if (!confirmed) return
  busy.value = true
  pageError.value = ''
  notice.value = ''
  try {
    await api.disableRuntimeProfile(profile.id)
    notice.value = `“${profile.profileName || profile.profileCode}”已停用。`
    latestPassedProfileId.value = null
    testResult.value = null
    await loadProfiles()
  } catch (error) {
    pageError.value = error?.message || '停用失败'
  } finally {
    busy.value = false
  }
}

onMounted(loadProfiles)
</script>

<style scoped>
.runtime-profiles-page { display: flex; flex-direction: column; gap: var(--sp-4); }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-4); }
.head-actions, .row-actions, .form-actions { display: flex; align-items: center; gap: var(--sp-2); }
.safety-note { margin: 0; line-height: 1.65; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); }
.data-table { width: 100%; border-collapse: collapse; min-width: 920px; }
.data-table th, .data-table td { padding: 12px 14px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: middle; }
.data-table th { color: var(--text-secondary); font-size: var(--fs-xs); font-weight: 600; white-space: nowrap; }
.data-table td { font-size: var(--fs-sm); }
.profile-code { display: block; margin-top: 3px; color: var(--text-tertiary); font-family: var(--font-mono); }
.data-table tr:last-child td { border-bottom: 0; }
.active-row { background: var(--success-bg); }
.empty-state { padding: 26px 10px; color: var(--text-secondary); text-align: center; }
.card-note { margin-top: var(--sp-3); }
.editor-card { border-color: var(--brand-200); }
.profile-form { margin-top: var(--sp-4); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-4); }
.form-actions { justify-content: flex-end; flex-wrap: wrap; margin-top: var(--sp-5); padding-top: var(--sp-4); border-top: 1px solid var(--border); }
.inline-error { margin-right: auto; color: var(--danger); font-size: var(--fs-sm); }
.test-summary { margin: var(--sp-3) 0; color: var(--text-secondary); }
.check-list { display: grid; gap: var(--sp-2); margin: 0; padding: 0; list-style: none; }
.check-list li { display: grid; grid-template-columns: minmax(100px, .35fr) auto minmax(200px, 1fr); align-items: center; gap: var(--sp-3); padding: 10px 12px; border-radius: var(--radius); background: var(--bg-subtle); font-size: var(--fs-sm); }
@media (max-width: 760px) {
  .page-head { flex-direction: column; }
  .head-actions { width: 100%; }
  .form-grid { grid-template-columns: 1fr; }
  .check-list li { grid-template-columns: 1fr auto; }
  .check-list li > :last-child { grid-column: 1 / -1; }
}
</style>
