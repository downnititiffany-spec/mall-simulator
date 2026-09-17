import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Pipeline.vue'), 'utf8')

const functionBody = (name, nextName) => {
  const start = source.indexOf(`async function ${name}(`)
  const end = source.indexOf(`async function ${nextName}(`, start)
  assert.notEqual(start, -1, `${name} must exist`)
  assert.notEqual(end, -1, `${nextName} must exist after ${name}`)
  return source.slice(start, end)
}

const executableText = (text) => text
  .split('\n')
  .map((line) => line.replace(/\/\/.*$/, ''))
  .join('\n')

test('runOnce 自身受 busy fail-closed 保护，不能只依赖按钮 disabled', () => {
  const body = functionBody('runOnce', 'retry')
  const guardAt = body.indexOf('if (busy.value) return')
  const busyAt = body.indexOf('busy.value = true')
  assert.ok(guardAt >= 0)
  assert.ok(busyAt > guardAt)
})

test('一次人工流水线触发只生成一个 operationId', () => {
  const body = executableText(functionBody('runOnce', 'retry'))
  assert.match(body, /const operationId = 'manual-' \+ Date\.now\(\)/)
  assert.equal((body.match(/Date\.now\(\)/g) || []).length, 1)
})

test('sourceDataVersion 与 Idempotency-Key 复用同一 operationId', () => {
  const body = functionBody('runOnce', 'retry')
  assert.match(body, /sourceDataVersion:\s*operationId/)
  assert.match(body, /\},\s*operationId\)/)
  assert.doesNotMatch(body, /sourceDataVersion:\s*'manual-' \+ Date\.now\(\)/)
})

test('runOnce 仍保持成功刷新、失败可见和 finally 释放 busy', () => {
  const body = functionBody('runOnce', 'retry')
  assert.match(body, /runResult\.value = await api\.createPipelineRun/)
  assert.match(body, /await load\(\)/)
  assert.match(body, /catch \(e\) \{[\s\S]*runResult\.value = \{ status: 'FAILED: ' \+ \(e\.message \|\| e\) \}/)
  assert.match(body, /finally \{\s*busy\.value = false\s*\}/)
})
