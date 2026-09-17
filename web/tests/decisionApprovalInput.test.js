// S3-65：批准动作必须由员工显式提供负责人和截止日期，不能用前端硬编码身份或自动 +3 天。
// 本文件是源码接线守卫；真实浏览器 prompt / HTTP / 后端状态机留后续 E2E。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.resolve(here, '../src/views/Decisions.vue'), 'utf8')

function functionBody(name, nextName) {
  const start = source.indexOf(`function ${name}`)
  const asyncStart = source.indexOf(`async function ${name}`)
  const actualStart = start >= 0 ? start : asyncStart
  assert.ok(actualStart >= 0, `必须存在 ${name}`)
  const nextPlain = nextName ? source.indexOf(`function ${nextName}`, actualStart + 1) : -1
  const nextAsync = nextName ? source.indexOf(`async function ${nextName}`, actualStart + 1) : -1
  const candidates = [nextPlain, nextAsync].filter((v) => v > actualStart)
  const end = candidates.length ? Math.min(...candidates) : source.length
  return source.slice(actualStart, end)
}

test('批准流程不再硬编码负责人，也不再自动把截止日期设为当前时间加三天', () => {
  const approve = functionBody('approve', 'rejectDecision')
  assert.doesNotMatch(approve, /prompt\([^\n]*,\s*['"]运营-小李['"]\)/)
  assert.doesNotMatch(approve, /Date\.now\(\)\s*\+\s*3\s*\*\s*86400000/)
  assert.doesNotMatch(approve, /toISOString\(\)\.slice\(0,\s*10\)/)
})

test('requiredApprovalText 对取消和纯空白输入 fail-closed，不制造默认负责人', () => {
  const fn = functionBody('requiredApprovalText', 'requiredDueDate')
  assert.match(fn, /prompt\(promptText, ''\)/)
  assert.match(fn, /if \(raw === null\) return null/)
  assert.match(fn, /const value = raw\.trim\(\)/)
  assert.match(fn, /if \(!value\)/)
  assert.match(fn, /actionError\.value = emptyMessage/)
  assert.match(fn, /return null/)
})

test('截止日期必须是 YYYY-MM-DD 且是真实存在的日历日期', () => {
  const fn = functionBody('requiredDueDate', 'approve')
  assert.match(fn, /\^\(\\d\{4\}\)-\(\\d\{2\}\)-\(\\d\{2\}\)\$/)
  assert.match(fn, /Date\.UTC\(year, month - 1, day\)/)
  assert.match(fn, /getUTCFullYear\(\) !== year/)
  assert.match(fn, /getUTCMonth\(\) !== month - 1/)
  assert.match(fn, /getUTCDate\(\) !== day/)
  assert.match(fn, /截止日期不是有效日历日期/)
})

test('approve 在拿到负责人和截止日期之前不得进入 busy 或发请求', () => {
  const fn = functionBody('approve', 'rejectDecision')
  const ownerAt = fn.indexOf("requiredApprovalText('负责人（必填，例如：运营-小李）'")
  const dueAt = fn.indexOf('requiredDueDate()')
  const busyAt = fn.indexOf('busy.value = true')
  const apiAt = fn.indexOf("api.decisionAction(d.id, 'approve'")
  assert.ok(ownerAt >= 0 && dueAt > ownerAt)
  assert.ok(busyAt > dueAt, '未完成两项人工输入前不能进入 busy')
  assert.ok(apiAt > busyAt, '未完成两项人工输入前不能发 approve 请求')
  assert.match(fn, /if \(!owner\) return/)
  assert.match(fn, /if \(!dueDate\) return/)
})

test('批准请求只提交员工确认后的 owner/dueDate，成功后仍刷新列表', () => {
  const fn = functionBody('approve', 'rejectDecision')
  assert.match(fn, /api\.decisionAction\(d\.id, 'approve', \{ owner, dueDate \}\)/)
  assert.match(fn, /await flush\(\)/)
  assert.match(fn, /actionError\.value = .*'批准失败'/)
})
