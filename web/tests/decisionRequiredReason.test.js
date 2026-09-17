// S3-64：后端 reject/cancel 的 ReasonReq.reason 是必填业务事实；前端必须采集员工真实原因，
// 不能 reject 发空对象，也不能 cancel 写死一个看似真实的固定原因。这里只做源码接线守卫。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(here, '..', 'src', 'views', 'Decisions.vue'), 'utf8')

function bodyOf(startAnchor, endAnchor) {
  const start = source.indexOf(startAnchor)
  const end = source.indexOf(endAnchor, start + startAnchor.length)
  assert.ok(start >= 0, `缺少函数：${startAnchor}`)
  assert.ok(end > start, `缺少结束锚点：${endAnchor}`)
  return source.slice(start, end)
}

test('PENDING_REVIEW 的驳回按钮调用独立 rejectDecision，而不是 act(..., reject) 空请求', () => {
  assert.match(source, /d\.status === 'PENDING_REVIEW'[^>]*@click="rejectDecision\(d\)"[^>]*>驳回<\/button>/)
  assert.doesNotMatch(source, /@click="act\(d, 'reject'\)"/)
})

test('requiredReason 对取消 prompt 与纯空白输入 fail-closed，不制造默认原因', () => {
  const fn = bodyOf('function requiredReason(actionLabel)', 'async function approve(d)')
  assert.match(fn, /prompt\(`\$\{actionLabel\}原因（必填）`, ''\)/)
  assert.match(fn, /if \(raw === null\) return null/)
  assert.match(fn, /const reason = raw\.trim\(\)/)
  assert.match(fn, /if \(!reason\)/)
  assert.match(fn, /actionError\.value = `\$\{actionLabel\}原因不能为空`/)
  assert.doesNotMatch(fn, /策略调整|情况变化|默认原因/)
})

test('rejectDecision 只在取得非空真实原因后发送 { reason } 并刷新', () => {
  const fn = bodyOf('async function rejectDecision(d)', 'async function cancelDecision(d)')
  assert.match(fn, /const reason = requiredReason\('驳回'\)/)
  assert.match(fn, /if \(!reason\) return/)
  assert.match(fn, /api\.decisionAction\(d\.id, 'reject', \{ reason \}\)/)
  assert.match(fn, /await flush\(\)/)
  assert.match(fn, /'驳回失败'/)
})

test('cancelDecision 与 reject 共用同一原因采集纪律，不再硬编码策略调整', () => {
  const fn = bodyOf('async function cancelDecision(d)', 'async function evaluate(d)')
  assert.match(fn, /const reason = requiredReason\('取消'\)/)
  assert.match(fn, /api\.decisionAction\(d\.id, 'cancel', \{ reason \}\)/)
  assert.doesNotMatch(fn, /策略调整/)
  const owners = source.match(/function requiredReason\(/g) || []
  assert.equal(owners.length, 1, 'reason 采集/trim/空值判据必须只有一个前端 owner')
})
