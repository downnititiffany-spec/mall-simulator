// S3-63：源码接线守卫。这里不冒充浏览器/E2E；真 Vue 点击与真实 HTTP abort 留阶段6/7联调。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(here, '..', 'src', 'views', 'AiAssistant.vue'), 'utf8')

function between(start, end) {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.ok(from >= 0, `未找到起始锚点：${start}`)
  assert.ok(to > from, `未找到结束锚点：${end}`)
  return source.slice(from, to)
}

test('主按钮在 ask busy 时仍可点击取消，但 draftBusy 时禁止启动另一条问答', () => {
  assert.match(source, /@click="handleAskAction"\s+:disabled="draftBusy \|\| \(!busy && !question\.trim\(\)\)"/)
  assert.match(source, /busy \? '放弃本次分析' : \(draftBusy \? '草稿创建中…' : '发送'\)/)
  assert.doesNotMatch(source, /@click="ask"\s+:disabled="busy \|\| !question\.trim\(\)"/)
})

test('cancelAsk 使旧响应失效、真正 abort，并立即清理 busy/旧结果', () => {
  const body = between('function cancelAsk()', 'function handleAskAction()')
  assert.match(body, /askSeq \+= 1/)
  assert.match(body, /const controller = askController/)
  assert.match(body, /askController = null/)
  assert.match(body, /if \(controller\) controller\.abort\(\)/)
  assert.match(body, /busy\.value = false/)
  assert.match(body, /queryResult\.value = null/)
  assert.match(body, /abortedNotice\.value = '本次提问已取消，结果不会展示。'/)
})

test('同一个主按钮在 draftBusy 时先拒绝，在 ask busy 时取消，否则正常 ask', () => {
  const body = between('function handleAskAction()', 'async function ask()')
  assert.match(body, /if \(draftBusy\.value\) return/)
  assert.match(body, /if \(busy\.value\) \{[\s\S]*cancelAsk\(\)[\s\S]*return[\s\S]*\}/)
  assert.match(body, /ask\(\)/)
})

test('ask 保留序号守卫，取消后的旧响应不得重新写回页面状态', () => {
  const body = between('async function ask()', 'function askPreset(q)')
  const guards = body.match(/if \(mySeq !== askSeq\) return/g) || []
  assert.ok(guards.length >= 2, `至少需要成功/异常两条过期响应守卫，实际 ${guards.length}`)
  assert.match(body, /if \(mySeq === askSeq\) \{[\s\S]*busy\.value = false[\s\S]*askController = null[\s\S]*\}/)
})

test('在途分析或草稿创建期间推荐问题和历史回填均禁用，避免跨操作状态错位', () => {
  assert.match(source, /v-for="q in recommended"[^>]*@click="askPreset\(q\)"[^>]*:disabled="busy \|\| draftBusy"/)
  assert.match(source, /v-for="h in history"[^>]*@click="question = h\.question"[^>]*:disabled="busy \|\| draftBusy"/)
})
