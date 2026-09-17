// S3-62/S3-64：决策中心“取消”按钮必须调用业务取消动作，而不是 useAnalysis 的请求取消函数；
// 取消原因由员工显式输入，不能用前端固定文案冒充真实原因。
// 这是源码接线守卫；真实 HTTP/浏览器行为留给后续 E2E。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Decisions.vue'), 'utf8')

test('IN_PROGRESS 的“取消”按钮调用 cancelDecision(d)，不得误调用 useAnalysis.cancel', () => {
  assert.match(source, /v-if="d\.status === 'IN_PROGRESS'"[^>]*@click="cancelDecision\(d\)"[^>]*>取消<\/button>/)
  assert.doesNotMatch(source, /@click="cancel\(d\)"[^>]*>取消<\/button>/)
})

test('cancelDecision 要求员工输入取消原因，再通过 decisionAction 发业务请求并刷新列表', () => {
  const start = source.indexOf('async function cancelDecision(d)')
  const end = source.indexOf('async function evaluate(d)', start)
  assert.ok(start >= 0 && end > start, '必须存在独立的 cancelDecision 实现')
  const fn = source.slice(start, end)
  assert.match(fn, /const reason = requiredReason\('取消'\)/)
  assert.match(fn, /if \(!reason\) return/)
  assert.match(fn, /api\.decisionAction\(d\.id, 'cancel', \{ reason \}\)/)
  assert.match(fn, /await flush\(\)/)
  assert.match(fn, /actionError\.value = .*'取消失败'/)
  assert.doesNotMatch(fn, /reason:\s*['"][^'"]+['"]/, '不得用固定原因冒充员工实际输入')
})

test('useAnalysis.cancel 仍只用于组件卸载时中止取数，不承担业务取消', () => {
  assert.match(source, /const \{[^}]*\bcancel\b[^}]*\} = analysis/s)
  assert.match(source, /onUnmounted\(cancel\)/)
  const businessCalls = source.match(/decisionAction\(d\.id, 'cancel'/g) || []
  assert.equal(businessCalls.length, 1, '业务 cancel 请求必须只有一个显式 owner')
})
