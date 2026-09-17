import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Rfm.vue'), 'utf8')

test('RFM 用户聚合请求固定到主 RFM 响应的 snapshotId', () => {
  assert.match(source, /api\.users\(\{\s*snapshotId:\s*rfm\.snapshotId\s*\},\s*\{\s*signal\s*\}\)/)
  assert.doesNotMatch(source, /api\.users\(\{\s*\},\s*\{\s*signal\s*\}\)/)
})

test('RFM 响应缺 snapshotId 时跳过第二个聚合请求而不是重新取 ACTIVE', () => {
  const missingGuard = source.indexOf('if (!rfm.snapshotId)')
  const usersCall = source.indexOf('api.users({ snapshotId: rfm.snapshotId }')
  assert.ok(missingGuard >= 0)
  assert.ok(usersCall > missingGuard)
  assert.match(source, /为避免混快照已跳过生命周期\/偏好聚合请求/)
})

test('页面响应上下文仍以 RFM 主响应快照为唯一 snapshotId', () => {
  assert.match(source, /return \{[\s\S]*snapshotId:\s*rfm\.snapshotId/)
  assert.doesNotMatch(source, /snapshotId:\s*users\.snapshotId/)
})

test('生命周期与偏好只消费被固定快照的 usersData，不参与 RFM 主口径重算', () => {
  assert.match(source, /lifecycle:\s*usersData\.lifecycle \|\| \[\]/)
  assert.match(source, /preference:\s*usersData\.preference \|\| \[\]/)
  assert.match(source, /rfmSegments:\s*rfm\.data\.rfmSegments \|\| \[\]/)
  assert.match(source, /rfmMatrix:\s*rfm\.data\.rfmMatrix \|\| \[\]/)
})
