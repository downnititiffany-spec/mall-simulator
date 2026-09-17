import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { COLUMNS, decisionRows } from '../src/utils/tables.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const viewSource = fs.readFileSync(path.join(here, '../src/views/Decisions.vue'), 'utf8')

test('decisionRows 搬运截止日期、基线快照与口径版本，缺失值保持显式占位', () => {
  const [row] = decisionRows([{
    id: 9,
    decisionNo: 'D-009',
    baselineSnapshotId: 'S-BASE-9',
    definitionVersion: 'metric-v3',
    dueDate: '2026-09-30'
  }])

  assert.equal(row.id, 9)
  assert.equal(row.baselineSnapshotId, 'S-BASE-9')
  assert.equal(row.definitionVersion, 'metric-v3')
  assert.equal(row.dueDate, '2026-09-30')

  const [missing] = decisionRows([{}])
  assert.equal(missing.baselineSnapshotId, '—')
  assert.equal(missing.definitionVersion, '—')
  assert.equal(missing.dueDate, '—')
})

test('决策 CSV 列定义包含建议快照、真实基线快照、口径版本和截止日期', () => {
  const keys = COLUMNS.decisions.map((c) => c.key)
  const labels = COLUMNS.decisions.map((c) => c.label)

  assert.ok(keys.includes('suggestionSnapshotId'))
  assert.ok(keys.includes('baselineSnapshotId'))
  assert.ok(keys.includes('definitionVersion'))
  assert.ok(keys.includes('dueDate'))
  assert.ok(labels.includes('建议快照'))
  assert.ok(labels.includes('基线快照'))
  assert.ok(labels.includes('口径版本'))
  assert.ok(labels.includes('截止日期'))
})

test('决策页面同时展示建议快照与批准后锁定的基线快照，不能把二者混为一谈', () => {
  assert.match(viewSource, /<th>建议快照<\/th><th>基线快照<\/th><th>口径版本<\/th>/)
  assert.match(viewSource, /\{\{ d\.suggestionSnapshotId \|\| '—' \}\}/)
  assert.match(viewSource, /\{\{ d\.baselineSnapshotId \|\| '—' \}\}/)
  assert.match(viewSource, /\{\{ d\.definitionVersion \|\| '—' \}\}/)
  assert.match(viewSource, /「建议快照」是 AI 建议来源/)
  assert.match(viewSource, /真正锁定的评价基线来自「基线快照」/)
  assert.doesNotMatch(viewSource, /锁定当次快照基线，该快照号记录在决策行的「建议快照」字段/)
})

test('决策页面展示批准时采集的截止日期，并继续通过统一列定义导出', () => {
  assert.match(viewSource, /<th>负责人<\/th><th>截止日期<\/th>/)
  assert.match(viewSource, /\{\{ d\.dueDate \|\| '—' \}\}/)
  assert.match(viewSource, /headers: COLUMNS\.decisions\.map\(\(c\) => c\.label\)/)
  assert.match(viewSource, /rows: rows\.value\.map\(\(r\) => COLUMNS\.decisions\.map\(\(c\) => r\[c\.key\]\)\)/)
})
