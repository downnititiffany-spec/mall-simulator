import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { buildAiEvidenceContext } from '../src/utils/context.js'

const here = dirname(fileURLToPath(import.meta.url))
const aiAssistantSrc = readFileSync(join(here, '..', 'src', 'views', 'AiAssistant.vue'), 'utf8')

test('S3-54：AI 结论只搬运 explanation.summary，不从 query/evidence 推导', () => {
  const ctx = buildAiEvidenceContext({
    query: { status: 'EXECUTED', rows: [{ gmv: '123.45' }] },
    explanation: {
      summary: '近 7 天 GMV 保持稳定。',
      evidence: { question: '最近 7 天 GMV 如何？', snapshotId: 'S20260901_24' }
    }
  })
  assert.equal(ctx.summary, '近 7 天 GMV 保持稳定。')
})

test('S3-54：summary 缺失或空白时返回 null，页面走明确缺失文案', () => {
  assert.equal(buildAiEvidenceContext({ query: {}, explanation: {} }).summary, null)
  assert.equal(buildAiEvidenceContext({ query: {}, explanation: { summary: '   ' } }).summary, null)
})

test('S3-54：query 中同名 summary 不得覆盖 ExplanationResult.summary', () => {
  const ctx = buildAiEvidenceContext({
    query: { summary: '不应使用 query.summary' },
    explanation: { summary: '应展示 ExplanationResult.summary', evidence: {} }
  })
  assert.equal(ctx.summary, '应展示 ExplanationResult.summary')
})

test('S3-54：AiAssistant 的结论展示消费 evidenceContext.summary，缺失时显式降级', () => {
  assert.match(aiAssistantSrc, /evidenceContext\.summary\s*\|\|\s*'（后端未给出结论文本）'/)
  assert.doesNotMatch(aiAssistantSrc, /query\.summary\s*\|\|/)
})
