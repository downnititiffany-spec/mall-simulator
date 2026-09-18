import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/AiAssistant.vue'), 'utf8')

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

test('草稿创建 handler 自身拒绝 draftBusy 重入', () => {
  const fn = functionBody('createDraft', 'loadHistory')
  const guardAt = fn.indexOf('if (draftBusy.value) return')
  const apiAt = fn.indexOf('api.decisionCreate(')
  assert.ok(guardAt >= 0)
  assert.ok(apiAt > guardAt)
})

test('草稿创建在途时新问答入口与 ask 本体都 fail-closed', () => {
  const action = functionBody('handleAskAction', 'ask')
  assert.match(action, /if \(draftBusy\.value\) return/)
  const ask = functionBody('ask', 'askPreset')
  assert.match(ask, /if \(!text \|\| busy\.value \|\| draftBusy\.value\) return/)
})

test('推荐问题入口也拒绝 busy/draftBusy 重入', () => {
  const fn = functionBody('askPreset', 'exportResult')
  assert.match(fn, /if \(busy\.value \|\| draftBusy\.value\) return/)
  assert.ok(fn.indexOf('if (busy.value || draftBusy.value) return') < fn.indexOf('question.value = q'))
})

test('问答输入、发送、推荐问题和历史回填在 draftBusy 期间都被 UI 禁用', () => {
  assert.match(source, /:disabled="busy \|\| draftBusy"/)
  assert.match(source, /:disabled="draftBusy \|\| \(!busy && !question\.trim\(\)\)"/)
  const disabledMatches = source.match(/:disabled="busy \|\| draftBusy"/g) || []
  assert.ok(disabledMatches.length >= 3, '输入框、推荐问题、历史回填至少三处都应受 draftBusy 约束')
})

test('打开/关闭草稿不会在草稿创建请求在途时改写表单状态', () => {
  const open = functionBody('openDraft', 'closeDraft')
  assert.match(open, /if \(!canCreateDraft\.value \|\| draftBusy\.value\) return/)
  const close = functionBody('closeDraft', 'createDraft')
  assert.match(close, /if \(draftBusy\.value\) return/)
})

test('草稿创建在途时表单五个可编辑字段全部锁定，页面显示不会继续偏离已提交 payload', () => {
  assert.match(source, /v-model="draftForm\.title" :disabled="draftBusy"/)
  assert.match(source, /v-model="draftForm\.action"[^>]*:disabled="draftBusy"/)
  assert.match(source, /v-model="draftForm\.metricCode"[^>]*:disabled="draftBusy"/)
  assert.match(source, /v-model="draftForm\.direction" :disabled="draftBusy"/)
  assert.match(source, /v-model="draftForm\.owner" :disabled="draftBusy"/)
})

