const RECENT_RUNS_KEY = 'generator-console:recent-runs:v1'
const ACTIVE_STATES = new Set(['PENDING', 'RUNNING'])
const STATUS_LABELS = {
  PENDING: '等待启动', RUNNING: '运行中', SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消'
}

const byId = (id) => document.getElementById(id)
let currentRunId = ''
let pollTimer = 0
let toastTimer = 0

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers }
  })
  const text = await response.text()
  let body = null
  if (text) {
    try { body = JSON.parse(text) } catch { body = text }
  }
  if (!response.ok) {
    const detail = typeof body === 'string' ? body : body?.message || body?.detail || body?.error || `HTTP ${response.status}`
    throw new Error(String(detail).slice(0, 500))
  }
  return body
}

function escapeText(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}

function showNotice(message, kind = 'info') {
  const node = byId('page-message')
  node.textContent = message
  node.dataset.kind = kind
  node.classList.remove('hidden')
}

function showToast(message) {
  const node = byId('toast')
  node.textContent = message
  node.classList.remove('hidden')
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => node.classList.add('hidden'), 3600)
}

function readRecentRuns() {
  try {
    const parsed = JSON.parse(localStorage.getItem(RECENT_RUNS_KEY) || '[]')
    return Array.isArray(parsed) ? parsed.filter((id) => typeof id === 'string').slice(0, 8) : []
  } catch { return [] }
}

function saveRecentRun(runId) {
  try {
    const runs = [runId, ...readRecentRuns().filter((id) => id !== runId)].slice(0, 8)
    localStorage.setItem(RECENT_RUNS_KEY, JSON.stringify(runs))
  } catch { /* Storage can be disabled; the current page remains usable. */ }
  renderRecentRuns()
}

function renderRecentRuns() {
  const ids = readRecentRuns()
  const wrapper = byId('recent-runs')
  const list = byId('recent-list')
  list.replaceChildren()
  wrapper.classList.toggle('hidden', ids.length === 0)
  for (const id of ids) {
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'run-chip'
    button.textContent = id
    button.title = `查询运行 ${id}`
    button.addEventListener('click', () => { byId('run-id').value = id; loadRun(id) })
    list.append(button)
  }
}

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function loadTargets() {
  const list = byId('target-list')
  list.innerHTML = '<div class="skeleton" aria-label="正在加载目标商城"></div>'
  try {
    const targets = await api('/api/v1/targets')
    if (!Array.isArray(targets) || targets.length === 0) {
      list.innerHTML = '<p class="muted">暂无已配置的目标商城。配置管理暂由受控维护流程完成。</p>'
      return
    }
    list.innerHTML = targets.map((target) => {
      const id = escapeText(target.id)
      const status = String(target.status || 'UNKNOWN').toUpperCase()
      const active = status === 'ACTIVE'
      let origin = target.base_url || '未填写服务地址'
      try { if (target.base_url) origin = new URL(target.base_url).origin } catch { /* Show the configured value as text. */ }
      return `<article class="target-card" data-target-id="${id}">
        <div class="target-title-row"><strong>${escapeText(target.name || `目标 ${id}`)}</strong><span class="target-status" data-active="${active}">${escapeText(status)}</span></div>
        <div class="target-subtitle">${escapeText(target.adapter_type || 'ADAPTER 未声明')} · ID ${id}</div>
        <div class="target-meta"><span>地址：${escapeText(origin)}</span><span>测试环境：${target.test_environment === true ? '是' : target.test_environment === false ? '否' : '未声明'}</span></div>
        <button class="button button-quiet target-test" type="button" data-target-id="${id}" ${active ? '' : 'disabled'}>测试连接</button>
        <div class="target-test-slot" aria-live="polite"></div>
      </article>`
    }).join('')
    list.querySelectorAll('.target-test').forEach((button) => button.addEventListener('click', () => testTarget(button)))
  } catch (error) {
    list.innerHTML = `<p class="muted">目标列表读取失败：${escapeText(error.message)}。请确认生成器服务和元数据库状态。</p>`
  }
}

async function testTarget(button) {
  const targetId = button.dataset.targetId
  const slot = button.closest('.target-card')?.querySelector('.target-test-slot')
  button.disabled = true
  button.textContent = '正在测试…'
  if (slot) slot.replaceChildren()
  try {
    const result = await api(`/api/v1/targets/${encodeURIComponent(targetId)}/test`, { method: 'POST' })
    const node = document.createElement('div')
    node.className = 'target-test-result'
    node.dataset.ok = String(result?.reachable === true)
    const capabilities = result?.capabilities && typeof result.capabilities === 'object'
      ? Object.entries(result.capabilities).map(([name, verdict]) => `${name}: ${verdict}`).join(' · ')
      : '本次未返回能力信息'
    node.textContent = `${result?.reachable === true ? '连接可达' : '连接未确认'}：${result?.detail || '服务未提供说明'} · ${capabilities}`
    slot?.append(node)
  } catch (error) {
    const node = document.createElement('div')
    node.className = 'target-test-result'
    node.dataset.ok = 'false'
    node.textContent = `测试失败：${error.message}`
    slot?.append(node)
  } finally {
    button.disabled = false
    button.textContent = '重新测试'
  }
}

async function loadScenarios() {
  const list = byId('scenario-list')
  list.innerHTML = '<div class="skeleton" aria-label="正在加载场景"></div>'
  try {
    const scenarios = await api('/api/v1/scenarios')
    if (!Array.isArray(scenarios) || scenarios.length === 0) {
      list.innerHTML = '<p class="muted">生成器服务暂未返回场景定义。</p>'
      return
    }
    list.innerHTML = scenarios.map((scenario) => {
      const directions = scenario.expected_directions && typeof scenario.expected_directions === 'object'
        ? Object.entries(scenario.expected_directions).map(([key, value]) => `<li><span>${escapeText(key)}</span><b>${escapeText(value)}</b></li>`).join('')
        : ''
      return `<article class="scenario-card"><div class="scenario-title-row"><strong>${escapeText(scenario.label || scenario.code || '未命名场景')}</strong></div><div class="scenario-code">${escapeText(scenario.code || 'CODE 未声明')}</div>${directions ? `<ul class="scenario-directions">${directions}</ul>` : ''}</article>`
    }).join('')
  } catch (error) {
    list.innerHTML = `<p class="muted">场景读取失败：${escapeText(error.message)}</p>`
  }
}

function artifactName(uri) {
  const value = String(uri || '')
  return value.split(/[\\/]/).filter(Boolean).pop() || value || '未命名制品'
}

async function loadArtifacts(runId) {
  const list = byId('artifact-list')
  byId('artifact-count').textContent = '加载中…'
  list.innerHTML = '<p class="muted">正在读取制品清单…</p>'
  try {
    const artifacts = await api(`/api/v1/generation-runs/${encodeURIComponent(runId)}/artifacts`)
    if (!Array.isArray(artifacts) || artifacts.length === 0) {
      byId('artifact-count').textContent = '0 个制品'
      list.innerHTML = '<p class="muted">当前运行没有可读制品，或任务仍在执行。</p>'
      byId('journal-content-note').classList.add('hidden')
      return
    }
    byId('artifact-count').textContent = `${artifacts.length} 个制品`
    byId('journal-content-note').classList.toggle('hidden', !artifacts.some((artifact) => /operation-journal\.jsonl/i.test(String(artifact.uri || ''))))
    list.innerHTML = artifacts.map((artifact) => `<article class="artifact-card">
      <span class="artifact-name">${escapeText(artifactName(artifact.uri))}</span>
      <div class="artifact-meta"><span>记录 ${escapeText(artifact.record_count ?? '—')}</span><span>契约 ${escapeText(artifact.schema_version || '—')}</span><span>${escapeText(artifact.bytes ?? '—')} bytes</span></div>
      <div class="artifact-meta"><span>SHA-256</span><code>${escapeText(artifact.checksum || '—')}</code></div>
      <div class="artifact-meta"><span>位置</span><code>${escapeText(artifact.uri || '—')}</code></div>
    </article>`).join('')
  } catch (error) {
    byId('artifact-count').textContent = '读取失败'
    list.innerHTML = `<p class="muted">制品读取失败：${escapeText(error.message)}</p>`
  }
}

function setStatus(status) {
  const node = byId('run-status')
  node.textContent = STATUS_LABELS[status] || status || '状态未知'
  node.dataset.status = status || ''
}

function stopPolling() {
  window.clearTimeout(pollTimer)
  pollTimer = 0
  byId('poll-hint').textContent = ''
}

function syncStartButton() {
  const sideEffectConfirmed = byId('side-effect-confirm').checked
  const resultNeedsReconciliation = !byId('run-result-warning').classList.contains('hidden')
  const reconciled = byId('outcome-reconciled').checked
  byId('start-button').disabled = !sideEffectConfirmed || (resultNeedsReconciliation && !reconciled)
}

function schedulePoll(runId) {
  stopPolling()
  byId('poll-hint').textContent = '运行中 · 每 3 秒自动刷新'
  pollTimer = window.setTimeout(() => loadRun(runId, true), 3000)
}

async function loadRun(runId, quiet = false) {
  const cleanId = String(runId || '').trim()
  if (!cleanId) return
  const previousRunId = currentRunId
  const warningWasVisible = !byId('run-result-warning').classList.contains('hidden')
  currentRunId = cleanId
  byId('run-id').value = cleanId
  byId('run-empty').classList.add('hidden')
  byId('run-content').classList.remove('hidden')
  byId('run-value-id').textContent = cleanId
  byId('refresh-run').disabled = true
  if (!quiet) byId('run-status').textContent = '读取中…'
  try {
    const run = await api(`/api/v1/generation-runs/${encodeURIComponent(cleanId)}`)
    setStatus(run.status)
    byId('run-success').textContent = run.success_count ?? '—'
    byId('run-failed').textContent = run.failed_count ?? '—'
    byId('run-version').textContent = run.plan_version ?? '—'
    byId('target-version').textContent = run.target_version ?? '—'
    byId('run-started').textContent = run.started ? `开始：${formatDate(run.started)}` : ''
    byId('run-finished').textContent = run.finished ? `结束：${formatDate(run.finished)}` : ''
    const errorNode = byId('run-error')
    errorNode.textContent = run.error || ''
    errorNode.classList.toggle('hidden', !run.error)
    const unknownOutcome = /MALL_OUTCOME_UNKNOWN|结果不明|禁止自动重试/.test(String(run.error || ''))
    byId('run-result-warning').classList.toggle('hidden', !unknownOutcome)
    if (unknownOutcome && (!warningWasVisible || previousRunId !== cleanId)) byId('outcome-reconciled').checked = false
    syncStartButton()
    byId('cancel-run').classList.toggle('hidden', !ACTIVE_STATES.has(run.status) || run.cancel_requested === true)
    byId('refresh-run').disabled = false
    saveRecentRun(cleanId)
    await loadArtifacts(cleanId)
    if (ACTIVE_STATES.has(run.status)) schedulePoll(cleanId)
    else stopPolling()
  } catch (error) {
    stopPolling()
    setStatus('')
    byId('run-error').textContent = `运行查询失败：${error.message}`
    byId('run-error').classList.remove('hidden')
    const unknownOutcome = /MALL_OUTCOME_UNKNOWN|结果不明|禁止自动重试/.test(error.message)
    byId('run-result-warning').classList.toggle('hidden', !unknownOutcome)
    if (unknownOutcome && (!warningWasVisible || previousRunId !== cleanId)) byId('outcome-reconciled').checked = false
    syncStartButton()
    byId('refresh-run').disabled = false
    if (!quiet) showNotice(`无法读取运行 ${cleanId}。请核对 runId，并确认生成器元数据库可用。`, 'error')
  }
}

byId('start-form').addEventListener('submit', async (event) => {
  event.preventDefault()
  const planId = byId('plan-id').value.trim()
  const version = Number(byId('plan-version').value)
  if (!planId || !Number.isInteger(version) || version < 1) {
    showNotice('请填写计划编号及大于零的整数版本。', 'error')
    return
  }
  if (!byId('side-effect-confirm').checked) {
    showNotice('请先确认目标商城和数据写入风险。', 'error')
    byId('side-effect-confirm').focus()
    return
  }
  if (!byId('run-result-warning').classList.contains('hidden') && !byId('outcome-reconciled').checked) {
    showNotice('请先在目标商城核实上一次结果不明的写操作，再启动新任务。', 'error')
    byId('outcome-reconciled').focus()
    return
  }
  if (!window.confirm(`即将启动计划“${planId}”第 ${version} 版。若模式为 MALL_API，可能向目标商城写入真实数据。是否继续？`)) return
  const button = byId('start-button')
  button.disabled = true
  button.querySelector('.button-label').textContent = '正在提交…'
  try {
    const result = await api('/api/v1/generation-runs', { method: 'POST', body: JSON.stringify({ plan_id: planId, version }) })
    if (!result?.runId) throw new Error('服务响应缺少 runId，未能确认任务已建立。')
    byId('run-id').value = result.runId
    showNotice(`任务已提交。运行编号：${result.runId}`, 'success')
    await loadRun(result.runId)
  } catch (error) {
    showNotice(`计划启动未确认：${error.message}。不要在确认服务端状态前盲目再次提交。`, 'error')
  } finally {
    button.querySelector('.button-label').textContent = '启动计划'
    syncStartButton()
  }
})

byId('side-effect-confirm').addEventListener('change', syncStartButton)
byId('outcome-reconciled').addEventListener('change', syncStartButton)

byId('lookup-form').addEventListener('submit', (event) => {
  event.preventDefault()
  loadRun(byId('run-id').value)
})
byId('refresh-run').addEventListener('click', () => currentRunId && loadRun(currentRunId))
byId('refresh-targets').addEventListener('click', loadTargets)
byId('cancel-run').addEventListener('click', async () => {
  if (!currentRunId || !window.confirm('请求取消该生成运行？商城已完成的操作不会自动回滚。')) return
  const button = byId('cancel-run')
  button.disabled = true
  try {
    await api(`/api/v1/generation-runs/${encodeURIComponent(currentRunId)}/cancel`, { method: 'POST', body: '{}' })
    showToast('取消请求已发送；已完成的商城操作不会回滚。')
    await loadRun(currentRunId)
  } catch (error) {
    showNotice(`取消请求失败：${error.message}`, 'error')
  } finally { button.disabled = false }
})

renderRecentRuns()
loadTargets()
loadScenarios()
