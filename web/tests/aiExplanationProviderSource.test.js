import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/AiAssistant.vue', import.meta.url), 'utf8')

test('AI解释显示后端确认的最终来源，包括只有来源无解释文本的情况', () => {
  assert.match(view, /v-if="explanationSection\.length \|\| explanation\.providerUsed"/)
  assert.match(view, /解释最终来源：\{\{ explanation\.providerUsed \|\| '接口未提供' \}\}/)
  assert.match(view, /template 表示采用模板\/规则回退/)
})
