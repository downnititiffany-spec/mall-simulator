import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const readView = (name) => fs.readFileSync(path.resolve(here, `../src/views/${name}.vue`), 'utf8')
const rfm = readView('Rfm')
const decisions = readView('Decisions')

function between(source, start, end) {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.ok(from >= 0, `未找到起始锚点：${start}`)
  assert.ok(to > from, `未找到结束锚点：${end}`)
  return source.slice(from, to)
}

test('RFM secondary read 每次 fetch 都取得独立序号', () => {
  assert.match(rfm, /let rfmFetchSeq = 0/)
  const body = between(rfm, 'async function fetchRfm(params, signal)', 'const analysis = useAnalysis')
  assert.match(body, /const mySeq = \+\+rfmFetchSeq/)
  assert.ok(body.indexOf('const mySeq = ++rfmFetchSeq') < body.indexOf('await api.rfm('))
})

test('RFM 缺 snapshotId 的用户聚合提示只允许最新 fetch 写入', () => {
  const body = between(rfm, 'async function fetchRfm(params, signal)', 'const analysis = useAnalysis')
  assert.match(
    body,
    /if \(!rfm\.snapshotId\) \{[\s\S]*if \(mySeq === rfmFetchSeq\) \{[\s\S]*usersError\.value = 'RFM 响应未提供 snapshotId/
  )
})

test('RFM 用户聚合异常不能由旧请求晚到覆盖新状态，卸载也使旧序号失效', () => {
  const body = between(rfm, 'async function fetchRfm(params, signal)', 'const analysis = useAnalysis')
  assert.match(body, /if \(!isAbort\(e\) && mySeq === rfmFetchSeq\) usersError\.value =/)
  assert.match(rfm, /onBeforeUnmount\(\(\) => \{\s*rfmFetchSeq \+= 1\s*analysis\.cancel\(\)\s*\}\)/)
})

test('Decision 组合读取每次 fetch 都取得独立序号', () => {
  assert.match(decisions, /let decisionFetchSeq = 0/)
  const body = between(decisions, 'async function fetchDecisions(params, signal)', 'const analysis = useAnalysis')
  assert.match(body, /const mySeq = \+\+decisionFetchSeq/)
  assert.ok(body.indexOf('const mySeq = ++decisionFetchSeq') < body.indexOf('await api.decisions('))
})

test('Decision evaluations 与 evaluationError 作为同一旁路状态只允许最新 fetch 提交', () => {
  const body = between(decisions, 'async function fetchDecisions(params, signal)', 'const analysis = useAnalysis')
  assert.match(
    body,
    /if \(mySeq === decisionFetchSeq\) \{\s*evaluations\.value = evalMap\s*evaluationError\.value = failed\.join\('；'\)\s*\}/
  )
})

test('Decision 卸载先使旁路 fetch 序号失效，再取消 useAnalysis 请求', () => {
  assert.match(decisions, /onUnmounted\(\(\) => \{\s*decisionFetchSeq \+= 1\s*cancel\(\)\s*\}\)/)
})
