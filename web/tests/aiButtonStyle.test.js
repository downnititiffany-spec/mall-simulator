import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/AiAssistant.vue', import.meta.url), 'utf8')

test('AI 推荐问题和历史按钮不声明内联背景，避免全局彩色按钮规则造成文字低对比', () => {
  const recommendation = source.match(/<button v-for="q in recommended"[\s\S]*?<\/button>/)?.[0]
  const history = source.match(/<button v-for="h in history"[\s\S]*?<\/button>/)?.[0]

  assert.ok(recommendation, '应找到推荐问题按钮')
  assert.ok(history, '应找到最近问答按钮')
  assert.doesNotMatch(recommendation, /\bbackground\s*:/)
  assert.doesNotMatch(history, /\bbackground\s*:/)
})
