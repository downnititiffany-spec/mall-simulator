import test from 'node:test'
import assert from 'node:assert/strict'
import { paginateIngestionBatches, sortIngestionBatches } from '../src/utils/ingestionBatches.js'

test('采集批次排序默认按 ID 降序且不修改接口数组', () => {
  const rows = [{ id: 2 }, { id: 5 }, { id: 1 }]
  const sorted = sortIngestionBatches(rows)
  assert.deepEqual(sorted.map((row) => row.id), [5, 2, 1])
  assert.deepEqual(rows.map((row) => row.id), [2, 5, 1])
})

test('采集批次开始时间倒序并将缺失字段放在末尾', () => {
  const rows = [{ id: 1, startTime: null }, { id: 2, startTime: '2026-01-01T00:00:00' }, { id: 3, startTime: '2026-02-01T00:00:00' }]
  assert.deepEqual(sortIngestionBatches(rows, 'startTime,desc').map((row) => row.id), [3, 2, 1])
})

test('采集批次状态升序时使用 ID 降序稳定并列', () => {
  const rows = [{ id: 3, status: 'SUCCESS' }, { id: 1, status: 'FAILED' }, { id: 5, status: 'SUCCESS' }]
  assert.deepEqual(sortIngestionBatches(rows, 'status,asc').map((row) => row.id), [1, 5, 3])
})

test('不支持的采集批次排序回退至 ID 降序', () => {
  assert.deepEqual(sortIngestionBatches([{ id: 1 }, { id: 4 }], 'landingDir,asc').map((row) => row.id), [4, 1])
})

test('采集批次分页支持三个白名单页大小并返回页元数据', () => {
  const rows = Array.from({ length: 23 }, (_, index) => ({ id: index + 1 }))
  const result = paginateIngestionBatches(rows, 2, 10)
  assert.deepEqual({ page: result.page, size: result.size, total: result.total, totalPages: result.totalPages }, { page: 2, size: 10, total: 23, totalPages: 3 })
  assert.deepEqual(result.items.map((row) => row.id), Array.from({ length: 10 }, (_, index) => index + 11))
})

test('采集批次分页对越界页、非法页和不支持的页大小安全收敛', () => {
  const rows = [{ id: 1 }, { id: 2 }, { id: 3 }]
  assert.equal(paginateIngestionBatches(rows, 99, 50).page, 1)
  const invalid = paginateIngestionBatches(rows, -4, 500)
  assert.equal(invalid.page, 1)
  assert.equal(invalid.size, 20)
  assert.equal(invalid.totalPages, 1)
  const fractional = paginateIngestionBatches(rows, 1.7, 10)
  assert.equal(fractional.page, 1)
  assert.deepEqual(fractional.items, rows)
  assert.equal(paginateIngestionBatches(rows, Number.POSITIVE_INFINITY, 10).page, 1)
  assert.equal(paginateIngestionBatches([], 5, 10).totalPages, 1)
})
