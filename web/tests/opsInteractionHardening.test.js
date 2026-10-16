import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Ops.vue'), 'utf8')

function functionBody(name, nextName) {
  const start = source.indexOf(`function ${name}`)
  const asyncStart = source.indexOf(`async function ${name}`)
  const actualStart = start >= 0 ? start : asyncStart
  assert.ok(actualStart >= 0, `必须存在 ${name}`)
  const candidates = nextName
    ? [source.indexOf(`function ${nextName}`, actualStart + 1), source.indexOf(`async function ${nextName}`, actualStart + 1)].filter((v) => v > actualStart)
    : []
  const end = candidates.length ? Math.min(...candidates) : source.length
  return source.slice(actualStart, end)
}

test('admin 三类写动作在 prompt/API 前先拒绝 busy 或整页 loading 重入', () => {
  for (const [name, nextName, actionNeedle] of [
    ['createUser', 'toggle', 'api.adminCreateUser'],
    ['toggle', 'resetPwd', 'api.adminUserAction'],
    ['resetPwd', 'init', 'prompt(']
  ]) {
    const fn = functionBody(name, nextName)
    const guardAt = fn.indexOf('if (busy.value || loading.value) return')
    const actionAt = fn.indexOf(actionNeedle)
    assert.ok(guardAt >= 0, `${name} 必须有 busy/loading 入口守卫`)
    assert.ok(actionAt > guardAt, `${name} 的 prompt/API 必须位于 busy/loading 守卫之后`)
  }
})

test('运维整页刷新 handler 在 loading 或 admin busy 时 fail-closed', () => {
  const fn = functionBody('loadAll', 'exportMetrics')
  const guardAt = fn.indexOf('if (loading.value || busy.value) return')
  const clearAt = fn.indexOf("actionError.value = ''")
  const loadAt = fn.indexOf('Promise.all([load({}), loadUsers()])')
  assert.ok(guardAt >= 0)
  assert.ok(clearAt > guardAt, '刷新守卫必须早于清理错误状态')
  assert.ok(loadAt > clearAt, '请求必须发生在守卫之后')
})

test('刷新与 admin 写按钮共享 loading/busy UI 互斥', () => {
  assert.match(source, /@click="loadAll" :disabled="loading \|\| busy"/)
  assert.match(source, /loading \? '刷新中…' : \(busy \? '用户操作中…' : '刷新'\)/)
  assert.match(source, /@click="createUser" :disabled="loading \|\| busy"/)
  assert.match(source, /@click="resetPwd\(u\)" :disabled="loading \|\| busy"/)
  assert.ok((source.match(/@click="toggle\(u, (?:false|true)\)" :disabled="loading \|\| busy"/g) || []).length >= 4)
})

test('快照指标导出 handler 自身检查 metricExportable', () => {
  const fn = functionBody('exportMetrics', 'exportRuns')
  assert.match(fn, /if \(!metricExportable\.value\) return/)
  assert.ok(fn.indexOf('if (!metricExportable.value) return') < fn.indexOf('exportAnalysisCsv('))
})

test('流水线导出资格同时要求页面 ready 且当前子集非空', () => {
  assert.match(source, /const pipelineExportable = computed\(\(\) => exportable\.value && pipelineTable\.value\.rows\.length > 0\)/)
  assert.match(source, /:disabled="!pipelineExportable" @click="exportRuns"/)
  const fn = functionBody('exportRuns', 'exportAudit')
  assert.match(fn, /if \(!pipelineExportable\.value\) return/)
})

test('AI 两类审计各自按自己的非空子集决定导出资格', () => {
  assert.match(source, /const aiHistoryExportable = computed\(\(\) => exportable\.value && aiHistoryRows\.value\.length > 0\)/)
  assert.match(source, /const aiCallExportable = computed\(\(\) => exportable\.value && aiCallRows\.value\.length > 0\)/)
  assert.match(source, /:disabled="!aiHistoryExportable" @click="exportAudit\('ai'\)"/)
  assert.match(source, /:disabled="!aiCallExportable" @click="exportAudit\('calls'\)"/)
})

test('exportAudit 在真正下载前按所选审计子集二次 fail-closed', () => {
  const fn = functionBody('exportAudit', 'createUser')
  const guardAt = fn.indexOf("if (isAi ? !aiHistoryExportable.value : !aiCallExportable.value) return")
  const exportAt = fn.indexOf('exportAnalysisCsv(')
  assert.ok(guardAt >= 0)
  assert.ok(exportAt > guardAt)
})
