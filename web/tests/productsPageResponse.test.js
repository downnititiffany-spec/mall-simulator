import test from 'node:test'
import assert from 'node:assert/strict'
import { requireProductsPageResponse } from '../src/utils/productsPageResponse.js'

const response = (data, extra = {}) => ({ snapshotId: 'snap-1', warnings: [], data, ...extra })

test('商品分页接受元数据一致的普通页和末页', () => {
    const first = response({
      hot: [{ productId: 1 }], conversion: [], topN: 2, page: 1, size: 2, total: 3, hasMore: true
    })
    assert.equal(requireProductsPageResponse(first), first)
    assert.ok(requireProductsPageResponse(response({
      hot: [{ productId: 3 }], conversion: [], topN: 2, page: 2, size: 2, total: 3, hasMore: false
    })))
})

test('商品分页接受无可用快照时约定的空数据告警', () => {
    assert.deepEqual(requireProductsPageResponse(response({}, {
      snapshotId: null,
      warnings: ['NO_ACTIVE_SNAPSHOT']
    })).data, {})
})

test('商品分页拒绝缺少行/元数据和不一致页状态', () => {
    assert.throws(() => requireProductsPageResponse(response({})), /缺少分页数据/)
    assert.throws(() => requireProductsPageResponse(response({
      hot: [], conversion: [], topN: 10, page: 1, size: 10, total: 11, hasMore: false
    })), /不一致/)
    assert.throws(() => requireProductsPageResponse(response({
      hot: [{}, {}], conversion: [], topN: 1, page: 1, size: 1, total: 2, hasMore: true
    })), /不一致/)
})

test('商品分页拒绝不安全数字、非数组行及无已知告警的空信封', () => {
    assert.throws(() => requireProductsPageResponse(response({
      hot: {}, conversion: [], topN: 10, page: 1, size: 10, total: 1, hasMore: false
    })), /元数据无效/)
    assert.throws(() => requireProductsPageResponse(response({
      hot: [], conversion: [], topN: 10, page: Number.MAX_SAFE_INTEGER + 1,
      size: 10, total: 0, hasMore: false
    })), /元数据无效/)
    assert.throws(() => requireProductsPageResponse(response({}, { warnings: [] })), /缺少分页数据/)
})
