// S3-66：DRAFT → PENDING_REVIEW 时，后端允许 SubmitReq 补 owner；页面必须真正收集并提交，不能继续发空 {}。
// 本文件只守源码接线；真实浏览器 prompt 与后端 PARAM_INVALID / 状态流留后续 E2E。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Decisions.vue'), 'utf8')

function body(name, nextName) {
  const start = source.indexOf(`async function ${name}`)
  assert.ok(start >= 0, `必须存在 ${name}`)
  const end = source.indexOf(`async function ${nextName}`, start + 1)
  assert.ok(end > start, `必须能截取 ${name}`)
  return source.slice(start, end)
}

test('DRAFT 的提交审核按钮调用 submitDecision(d)，不得继续走 act(d, submit) 空请求', () => {
  assert.match(source, /v-if="d\.status === 'DRAFT'"[^>]*@click="submitDecision\(d\)"[^>]*>提交审核<\/button>/)
  assert.doesNotMatch(source, /@click="act\(d, 'submit'\)"/)
})

test('submitDecision 使用已有真实 owner 作为可编辑初始值，缺失时保持空而不制造默认身份', () => {
  const fn = body('submitDecision', 'approve')
  assert.match(fn, /d && d\.owner && d\.owner !== '—' \? String\(d\.owner\)\.trim\(\) : ''/)
  assert.match(fn, /requiredApprovalText\('负责人（提交审核前必填）', '负责人不能为空', currentOwner\)/)
  assert.doesNotMatch(fn, /运营-小李/)
  assert.doesNotMatch(fn, /admin|demo/i)
})

test('取消或空白负责人时 submitDecision 必须在进入 busy 和发请求之前返回', () => {
  const fn = body('submitDecision', 'approve')
  const requireAt = fn.indexOf("requiredApprovalText('负责人（提交审核前必填）'")
  const guardAt = fn.indexOf('if (!owner) return')
  const busyAt = fn.indexOf('busy.value = true')
  const apiAt = fn.indexOf("api.decisionAction(d.id, 'submit'")
  assert.ok(requireAt >= 0 && guardAt > requireAt)
  assert.ok(busyAt > guardAt)
  assert.ok(apiAt > busyAt)
})

test('submitDecision 只提交员工确认后的 { owner } 并在成功后刷新列表', () => {
  const fn = body('submitDecision', 'approve')
  assert.match(fn, /api\.decisionAction\(d\.id, 'submit', \{ owner \}\)/)
  assert.match(fn, /await flush\(\)/)
  assert.match(fn, /actionError\.value = .*'提交审核失败'/)
  assert.doesNotMatch(fn, /decisionAction\(d\.id, 'submit', \{\s*\}\)/)
})
