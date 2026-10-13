import test from 'node:test'
import assert from 'node:assert/strict'
import { requireReportPageResponse } from '../src/utils/reportPageResponse.js'

test('accepts empty report pages with the service totalPages=0 convention', () => {
  const page = { items: [], page: 1, size: 10, total: 0, totalPages: 0, sort: 'createdAt,desc' }
  assert.equal(requireReportPageResponse(page), page)
})

test('accepts a valid nonempty page and rejects inconsistent pagination metadata', () => {
  const page = { items: [{ id: 1 }], page: 1, size: 10, total: 11, totalPages: 2, sort: 'createdAt,desc' }
  assert.equal(requireReportPageResponse(page), page)
  assert.throws(() => requireReportPageResponse({ ...page, totalPages: 1 }), /响应格式无效/)
  assert.throws(() => requireReportPageResponse({ ...page, page: 3 }), /响应格式无效/)
  assert.throws(() => requireReportPageResponse({ ...page, items: new Array(11).fill({ id: 2 }) }), /响应格式无效/)
})
