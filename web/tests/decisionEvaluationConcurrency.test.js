import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { forEachConcurrent } from '../src/utils/forEachConcurrent.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const decisions = fs.readFileSync(path.resolve(here, '../src/views/Decisions.vue'), 'utf8')

test('有界 worker 保留每个决策评价读取且并发不超过配置上限', async () => {
  const rows = Array.from({ length: 17 }, (_, id) => id)
  const visited = []
  let active = 0
  let peak = 0
  await forEachConcurrent(rows, 5, async (row) => {
    active += 1
    peak = Math.max(peak, active)
    await new Promise((resolve) => setTimeout(resolve, row % 3))
    visited.push(row)
    active -= 1
  })
  assert.deepEqual(visited.sort((left, right) => left - right), rows)
  assert.equal(peak, 5)
})

test('决策列表先完成主分页读取，再以有界并发旁路加载效果评价', () => {
  assert.match(decisions, /const DECISION_EVALUATION_READ_CONCURRENCY = 5/)
  const body = decisions.slice(decisions.indexOf('async function fetchDecisions(params, signal)'), decisions.indexOf('const analysis = useAnalysis'))
  assert.match(body, /void loadDecisionEvaluations\(decided, signal, mySeq\)/)
  assert.ok(body.indexOf('void loadDecisionEvaluations') < body.indexOf('return {'))
  assert.match(decisions, /forEachConcurrent\(decisions, DECISION_EVALUATION_READ_CONCURRENCY/)
  assert.match(decisions, /评价加载中…/)
})

test('旁路评价只允许最新且未取消的列表请求更新状态', () => {
  const start = decisions.indexOf('async function loadDecisionEvaluations(')
  const end = decisions.indexOf('\nasync function fetchDecisions', start)
  assert.ok(start >= 0 && end > start)
  const body = decisions.slice(start, end)
  assert.match(body, /signal && signal\.aborted/)
  assert.match(body, /mySeq === decisionFetchSeq/)
  assert.match(body, /evaluationPending\.value = nextPending/)
  assert.match(body, /evaluations\.value = \{ \.\.\.evalMap, \.\.\.evaluations\.value \}/)
})
