// 四态判定边界测试（契约 §4 / 指导书 §18.4）
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  REQUEST,
  chartState,
  canExport,
  stateText,
  rowCount,
  ENDPOINT_ROW_KEYS
} from '../src/utils/chartState.js'

test('无数据但非错误 -> empty（不得当成失败）', () => {
  assert.equal(chartState(REQUEST.READY, 0), 'empty')
  assert.equal(chartState(REQUEST.IDLE, 0), 'empty')
})

test('有旧数据 + 请求中 -> stale（必须提示数据更新中）', () => {
  assert.equal(chartState(REQUEST.LOADING, 7), 'stale')
  assert.equal(stateText('stale'), '数据更新中')
})

test('首次加载且屏上无数据 -> loading', () => {
  assert.equal(chartState(REQUEST.LOADING, 0), 'loading')
  assert.equal(stateText('loading'), '数据加载中')
})

test('请求失败 -> error（失败优先，不被 stale 掩盖）', () => {
  assert.equal(chartState(REQUEST.ERROR, 0), 'error')
  assert.equal(chartState(REQUEST.ERROR, 7), 'error')
  assert.equal(stateText('error'), '数据加载失败')
})

test('请求成功且有数据 -> ready', () => {
  assert.equal(chartState(REQUEST.READY, 1), 'ready')
  assert.equal(chartState(REQUEST.READY, 100), 'ready')
  assert.equal(stateText('ready'), '')
})

test('只有 ready 允许导出：loading/empty/error/stale 一律禁止', () => {
  assert.equal(canExport('ready'), true)
  assert.equal(canExport('loading'), false)
  assert.equal(canExport('stale'), false)
  assert.equal(canExport('empty'), false)
  assert.equal(canExport('error'), false)
})

test('行数非法值按 0 处理，不误判为有数据', () => {
  assert.equal(chartState(REQUEST.READY, NaN), 'empty')
  assert.equal(chartState(REQUEST.READY, undefined), 'empty')
  assert.equal(chartState(REQUEST.LOADING, -3), 'loading')
})

test('rowCount 按端点字段取行数', () => {
  assert.equal(rowCount({ stages: [{}, {}] }, ENDPOINT_ROW_KEYS.funnel), 2)
  assert.equal(rowCount({ trend: [] }, ENDPOINT_ROW_KEYS.sales), 0)
  assert.equal(rowCount({ trend: 'bad' }, ENDPOINT_ROW_KEYS.sales), 0)
  assert.equal(rowCount(null, ENDPOINT_ROW_KEYS.sales), 0)
  // overview 可用多字段兜底：趋势为空但有指标时仍算有数据
  assert.equal(rowCount({ salesTrend: [], metrics: [{}] }, ENDPOINT_ROW_KEYS.overview), 1)
})
