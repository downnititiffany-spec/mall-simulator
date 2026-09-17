// S3-67：决策列表映射必须保留后端实体 id；页面操作、评价索引与 Vue key 都依赖这个身份。
import test from 'node:test'
import assert from 'node:assert/strict'
import { decisionRows } from '../src/utils/tables.js'

test('decisionRows 保留后端数值 id 的原始类型，不转字符串也不生成展示占位符', () => {
  const [row] = decisionRows([{ id: 42, decisionNo: 'D-42', baselineValue: 1, targetValue: 2 }])
  assert.equal(row.id, 42)
  assert.equal(typeof row.id, 'number')
})

test('decisionRows 缺失或 null id 时保持 null，不能制造伪造身份', () => {
  assert.equal(decisionRows([{ id: null }])[0].id, null)
  assert.equal(decisionRows([{}])[0].id, null)
})

test('保留 id 不改变既有展示字段的搬运与格式化规则', () => {
  const [row] = decisionRows([{
    id: 7,
    decisionNo: 'D-007',
    title: '提升复购',
    baselineValue: 0.12,
    targetValue: 0.15,
    suggestionSnapshotId: 'S20260901_24',
    owner: '运营-A',
    status: 'PENDING_REVIEW'
  }])
  assert.equal(row.id, 7)
  assert.equal(row.decisionNo, 'D-007')
  assert.equal(row.baselineValue, '0.12')
  assert.equal(row.targetValue, '0.15')
  assert.equal(row.suggestionSnapshotId, 'S20260901_24')
  assert.equal(row.owner, '运营-A')
  assert.equal(row.status, 'PENDING_REVIEW')
})
