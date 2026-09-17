// J-R1 之后的低风险 Web 一致性加固统一回归守卫。
// 这些断言只钉前端接线/归属，不替代真实 HTTP、状态机或浏览器 E2E。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const readView = (name) => fs.readFileSync(path.resolve(here, `../src/views/${name}.vue`), 'utf8')

const behavior = readView('Behavior')
const rfm = readView('Rfm')
const sales = readView('Sales')
const overview = readView('Overview')
const decisions = readView('Decisions')

test('Behavior 先锁定 funnel snapshot，再用同一 snapshot 请求 overview，并拒绝回声错快照', () => {
  assert.match(behavior, /snapshotId:\s*funnel\.snapshotId/)
  assert.match(behavior, /if \(overview\.snapshotId && overview\.snapshotId !== funnel\.snapshotId\)/)
  assert.match(behavior, /跨接口快照不一致/)
})

test('RFM 保留主响应 publisher source，并拒绝 users 聚合回声成另一快照', () => {
  assert.match(rfm, /source:\s*rfm\.source/)
  assert.match(rfm, /if \(users\.snapshotId !== rfm\.snapshotId\)/)
  assert.match(rfm, /用户聚合快照不一致/)
})

test('Sales 与 Overview 的导出 handler 自身也执行 exportable fail-closed', () => {
  for (const [name, source] of [['Sales', sales], ['Overview', overview]]) {
    const exportAt = source.indexOf('function doExport()')
    assert.ok(exportAt >= 0, `${name} 必须存在 doExport`)
    const body = source.slice(exportAt, exportAt + 500)
    assert.match(body, /if \(!exportable\.value\) return/, `${name} handler 不能只依赖按钮 disabled`)
  }
})

test('Decision 所有写动作在 prompt/API 之前先拒绝 busy 重入', () => {
  for (const name of ['act', 'submitDecision', 'approve', 'rejectDecision', 'cancelDecision', 'evaluate']) {
    assert.match(
      decisions,
      new RegExp(`async function ${name}\\([^)]*\\) \\{\\s*if \\(busy\\.value\\) return`),
      `${name} 必须在函数入口 fail-closed`
    )
  }
})
