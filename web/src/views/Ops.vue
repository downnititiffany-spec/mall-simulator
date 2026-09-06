<template>
  <div>
    <div class="page-title">运维中心</div>

    <div class="chart-box">
      <div class="chart-title">系统用户（admin 专属：创建/启用禁用/重置密码）</div>
      <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">
        <input v-model="newUser.username" placeholder="用户名" style="padding:4px" />
        <input v-model="newUser.realName" placeholder="姓名" style="padding:4px" />
        <select v-model="newUser.role" style="padding:4px">
          <option value="operator">运营专员</option><option value="analyst">数据分析师</option>
          <option value="admin">系统管理员</option>
        </select>
        <input v-model="newUser.password" placeholder="初始密码(≥6位)" type="password" style="padding:4px" />
        <button style="font-size:12px;background:#16a34a" @click="createUser" :disabled="busy">创建用户</button>
      </div>
      <table v-if="users.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:6px">ID</th><th>用户名</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px">{{ u.id }}</td>
            <td>{{ u.username }}</td>
            <td>{{ u.realName || '—' }}</td>
            <td>{{ roleName(u.role) }}</td>
            <td><span :style="{ color: u.status === 1 ? '#16a34a' : '#dc2626' }">{{ u.status === 1 ? '启用' : '禁用' }}</span></td>
            <td style="white-space:nowrap">
              <button v-if="u.status === 1" style="font-size:12px;background:#dc2626" @click="toggle(u, false)" :disabled="busy">禁用</button>
              <button v-else style="font-size:12px;background:#16a34a" @click="toggle(u, true)" :disabled="busy">启用</button>
              <button style="font-size:12px" @click="resetPwd(u)" :disabled="busy">重置密码</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无用户</div>
    </div>

    <div class="chart-box">
      <div class="chart-title">快照版本（BUILDING→VERIFYING→ACTIVE / ARCHIVED / FAILED）
        <button style="float:right;font-size:12px;padding:3px 10px" @click="loadAll">刷新</button>
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:8px">快照 ID</th><th>业务时间</th><th>状态</th><th>版本</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="s in snapshots" :key="s.snapshotId" style="border-top:1px solid #f3f4f6"
              :style="{ background: selected === s.snapshotId ? '#eff6ff' : '' }">
            <td style="padding:8px">{{ s.snapshotId }}</td>
            <td>{{ s.businessTime || '—' }}</td>
            <td><span :style="{ color: snapColor(s.status), fontWeight: 600 }">{{ s.status }}</span></td>
            <td>v{{ s.version }}</td>
            <td><button style="font-size:12px" @click="viewSnapshot(s.snapshotId)">查看指标</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="snapshotValues.length" style="margin-top:10px;font-size:12px;color:#374151">
        <b>快照 {{ selected }} 指标：</b>
        <span v-for="v in snapshotValues" :key="v.metricCode" style="margin-right:12px">
          {{ v.metricCode }} = <b>{{ v.value }}</b>
        </span>
      </div>
    </div>

    <div class="chart-box">
      <div class="chart-title">数据质量规则结果（金额对账失败会阻断发布）</div>
      <table v-if="quality.length" style="width:100%;border-collapse:collapse;font-size:13px">
        <thead><tr style="text-align:left;color:#6b7280">
          <th style="padding:6px">流水线</th><th>指标</th><th>结果</th><th>信息</th><th>时间</th>
        </tr></thead>
        <tbody>
          <tr v-for="q in quality" :key="q.id" style="border-top:1px solid #f3f4f6">
            <td style="padding:6px">{{ q.runId || '—' }}</td>
            <td>{{ q.ruleCode }}</td>
            <td><span :style="{ color: q.passed === 1 ? '#16a34a' : '#dc2626', fontWeight: 600 }">{{ q.passed === 1 ? '通过' : '失败' }}</span></td>
            <td>{{ q.detail || '' }}</td>
            <td>{{ (q.createdAt || '').toString().slice(0, 16) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="el-empty">暂无质量结果（跑一次流水线后出现）</div>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start">
      <div class="chart-box">
        <div class="chart-title">AI 问答审计（ai_query_history）</div>
        <table v-if="history.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用户</th><th>问题</th><th>状态</th><th>行数</th><th>时间</th>
          </tr></thead>
          <tbody>
            <tr v-for="h in history" :key="h.id" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ h.userId }}</td>
              <td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ h.question }}</td>
              <td>{{ h.status }}</td>
              <td>{{ h.rowsReturned }}</td>
              <td>{{ (h.createdAt || '').toString().slice(0, 16) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无问答记录</div>
      </div>
      <div class="chart-box">
        <div class="chart-title">模型调用审计（ai_call_log）</div>
        <table v-if="calls.length" style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr style="text-align:left;color:#6b7280">
            <th style="padding:6px">用例</th><th>模型</th><th>tokens</th><th>耗时</th><th>状态</th>
          </tr></thead>
          <tbody>
            <tr v-for="c in calls" :key="c.id" style="border-top:1px solid #f3f4f6">
              <td style="padding:6px">{{ c.useCase }}</td>
              <td>{{ c.provider }}/{{ c.model }}</td>
              <td>{{ c.inputTokens ?? 0 }}/{{ c.outputTokens ?? 0 }}</td>
              <td>{{ c.elapsedMs }}ms</td>
              <td>{{ c.status }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="el-empty">暂无调用记录</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'

const snapshots = ref([])
const quality = ref([])
const history = ref([])
const calls = ref([])
const selected = ref('')
const snapshotValues = ref([])
const users = ref([])
const busy = ref(false)
const newUser = ref({ username: '', realName: '', role: 'operator', password: '' })

const roleName = (r) => ({ admin: '系统管理员', operator: '运营专员', analyst: '数据分析师' }[r] || r)

const snapColor = (s) => ({ ACTIVE: '#16a34a', BUILDING: '#d97706', VERIFYING: '#d97706', ARCHIVED: '#6b7280', FAILED: '#dc2626' }[s] || '#111827')

async function loadAll() {
  snapshots.value = await api.get('/metrics/snapshots', { limit: 15 }) || []
  quality.value = await api.get('/metrics/quality', { limit: 20 }) || []
  history.value = await api.get('/ai/audit/history', { limit: 10 }) || []
  calls.value = await api.get('/ai/audit/calls', { limit: 10 }) || []
  users.value = await api.get('/admin/users') || []
}
async function viewSnapshot(id) {
  selected.value = id
  snapshotValues.value = await api.get('/metrics/overview', { snapshotId: id }) || []
}
async function createUser() {
  busy.value = true
  try {
    await api.post('/admin/users', newUser.value)
    newUser.value = { username: '', realName: '', role: 'operator', password: '' }
    await loadAll()
  } catch (e) { alert('创建失败：' + (e.message || '')) } finally { busy.value = false }
}
async function toggle(u, enable) {
  busy.value = true
  try {
    await api.post(`/admin/users/${u.id}/toggle`, { enable })
    await loadAll()
  } catch (e) { alert('操作失败：' + (e.message || '')) } finally { busy.value = false }
}
async function resetPwd(u) {
  const pwd = prompt(`重置 ${u.username} 的密码（至少 6 位）`, '')
  if (!pwd) return
  busy.value = true
  try {
    await api.post(`/admin/users/${u.id}/reset-password`, { password: pwd })
    alert('已重置')
  } catch (e) { alert('操作失败：' + (e.message || '')) } finally { busy.value = false }
}
onMounted(loadAll)
</script>