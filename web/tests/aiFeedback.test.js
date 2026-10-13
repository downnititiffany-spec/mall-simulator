import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api.js', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/AiAssistant.vue', import.meta.url), 'utf8')

test('AI feedback uses the approved owner-scoped history endpoint', () => {
  assert.match(api, /aiFeedback:\s*\(id,\s*body\)\s*=>\s*client\.post\(`\/ai\/history\/\$\{id\}\/feedback`,\s*body\)/)
  assert.match(view, /api\.aiFeedback\(historyRow\.id,\s*\{/)
})

test('feedback is offered only when executed history has a complete evidence anchor', () => {
  assert.match(view, /h\.status === 'EXECUTED' && h\.evidenceId && h\.snapshotId && h\.sourceId && h\.definitionVersion/)
  assert.match(view, /h\.feedbackSubmitted/)
  assert.match(view, /historyRow\.feedbackSubmitted = true/)
})

test('feedback reasons use the closed code list and send no free text', () => {
  for (const code of ['ANSWER_MATCHED_EVIDENCE', 'MISSING_EVIDENCE', 'FACT_OR_SCOPE_INCORRECT', 'EXPLANATION_UNCLEAR', 'OTHER']) {
    assert.ok(view.includes(`value="${code}"`), `missing reason code ${code}`)
  }
  assert.match(view, /reasonCode:\s*feedbackReasons\.value\[historyRow\.id\]/)
})
