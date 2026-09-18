import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Pipeline.vue'), 'utf8')

const functionBody = (name) => {
  const start = source.indexOf(`async function ${name}`)
  assert.notEqual(start, -1, `${name} must exist`)
  const next = source.indexOf('\nasync function ', start + 1)
  return source.slice(start, next === -1 ? source.length : next)
}

test('FAILED run 的重试按钮受 loading/busy 双向保护，避免与刷新或重复 retry 并发', () => {
  assert.match(source, /v-if="r\.status === 'FAILED'"[^>]*@click="retry\(r\.id\)"[^>]*:disabled="loading \|\| busy"/)
})

test('retry 在 loading/busy 时 fail-closed，进入 busy 后清理旧结果，并在 finally 中恢复 busy', () => {
  const body = functionBody('retry')
  assert.match(body, /if \(busy\.value \|\| loading\.value\) return/)
  assert.match(body, /busy\.value = true/)
  assert.match(body, /runResult\.value = null/)
  assert.match(body, /finally \{[\s\S]*busy\.value = false[\s\S]*\}/)
})

test('retry 成功才刷新列表，失败时转成可见 FAILED 结果而不是未处理 Promise', () => {
  const body = functionBody('retry')
  assert.match(body, /runResult\.value = await api\.retryPipelineRun\(id\)[\s\S]*await load\(\)/)
  assert.match(body, /catch \(e\) \{[\s\S]*runResult\.value = \{ status: 'FAILED: ' \+ \(e\.message \|\| e\) \}/)
})

test('失败结果没有 runId 时不渲染 run#undefined', () => {
  assert.match(source, /<template v-if="runResult\.runId">流水线 run#\{\{ runResult\.runId \}\}：\{\{ runResult\.status \}\}<\/template>/)
  assert.match(source, /<template v-else>\{\{ runResult\.status \}\}<\/template>/)
  assert.doesNotMatch(source, />\s*流水线 run#\{\{ runResult\.runId \}\}：\{\{ runResult\.status \}\}\s*<span/)
})
