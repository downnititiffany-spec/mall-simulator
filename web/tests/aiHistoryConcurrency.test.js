import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/AiAssistant.vue'), 'utf8')

const between = (start, end) => {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.ok(from >= 0, `未找到起始锚点：${start}`)
  assert.ok(to > from, `未找到结束锚点：${end}`)
  return source.slice(from, to)
}

test('AI 历史刷新拥有独立序号和 AbortController，不复用主问答控制器', () => {
  assert.match(source, /let historySeq = 0/)
  assert.match(source, /let historyController = null/)
  assert.match(source, /let askSeq = 0/)
  assert.match(source, /let askController = null/)
})

test('loadHistory 启动新请求前使旧请求失效并尝试 abort', () => {
  const body = between('async function loadHistory()', 'function cancelAsk()')
  const seqAt = body.indexOf('const mySeq = ++historySeq')
  const abortAt = body.indexOf('if (historyController) historyController.abort()')
  const controllerAt = body.indexOf("historyController = typeof AbortController === 'function' ? new AbortController() : null")
  const requestAt = body.indexOf('api.aiHistoryMine(8, historyController ? { signal: historyController.signal } : undefined)')
  assert.ok(seqAt >= 0)
  assert.ok(abortAt > seqAt)
  assert.ok(controllerAt > abortAt)
  assert.ok(requestAt > controllerAt)
})

test('只有最新 history 请求可以写列表，异常路径也先做序号守卫', () => {
  const body = between('async function loadHistory()', 'function cancelAsk()')
  const guards = body.match(/if \(mySeq !== historySeq\) return/g) || []
  assert.ok(guards.length >= 2, `成功/异常路径至少各需一条过期响应守卫，实际 ${guards.length}`)
  const successGuard = body.indexOf('if (mySeq !== historySeq) return')
  const assign = body.indexOf('history.value = Array.isArray(rows) ? rows : []')
  assert.ok(assign > successGuard, '历史列表写入必须发生在最新序号确认之后')
})

test('历史请求主动取消不写错误，最新请求结束后才释放 controller', () => {
  const body = between('async function loadHistory()', 'function cancelAsk()')
  assert.match(body, /if \(!isAbort\(e\)\) historyError\.value =/)
  assert.match(body, /finally \{\s*if \(mySeq === historySeq\) historyController = null\s*\}/)
})

test('组件卸载时同时失效并取消主问答和历史请求', () => {
  const body = source.slice(source.indexOf('onUnmounted(() => {'))
  assert.match(body, /askSeq \+= 1/)
  assert.match(body, /if \(askController\) askController\.abort\(\)/)
  assert.match(body, /historySeq \+= 1/)
  assert.match(body, /if \(historyController\) historyController\.abort\(\)/)
  assert.match(body, /historyController = null/)
})
