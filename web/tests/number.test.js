// 数值/比例格式化测试（契约 §1：比例返回 decimal，前端统一格式化）
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatNumber,
  formatInteger,
  formatPercent,
  formatPercentValue,
  isNumeric,
  EMPTY_TEXT
} from '../src/utils/number.js'

test('小数格式化保留固定位数并千分位分组', () => {
  assert.equal(formatNumber(2042, 2), '2,042.00')
  assert.equal(formatNumber(2042.005, 2), '2,042.01')
  assert.equal(formatNumber(1493, 0), '1,493')
  assert.equal(formatNumber(0.5, 2), '0.50')
})

test('数字字符串按数值处理（后端 JSON 可能给字符串）', () => {
  assert.equal(formatNumber('408.4', 2), '408.40')
  assert.equal(formatInteger('5'), '5')
})

test('零是有效值，必须显示为 0 而不是占位符', () => {
  assert.equal(formatNumber(0, 2), '0.00')
  assert.equal(formatInteger(0), '0')
  assert.equal(formatPercent(0), '0.00%')
})

test('null / undefined / 空串 / NaN 一律返回占位符，不伪造 0', () => {
  assert.equal(formatNumber(null), EMPTY_TEXT)
  assert.equal(formatNumber(undefined), EMPTY_TEXT)
  assert.equal(formatNumber(''), EMPTY_TEXT)
  assert.equal(formatNumber('abc'), EMPTY_TEXT)
  assert.equal(formatNumber(NaN), EMPTY_TEXT)
  assert.equal(formatInteger(null), EMPTY_TEXT)
  assert.equal(formatPercent(null), EMPTY_TEXT)
})

test('decimal 比例转百分比：0.6 -> 60.00%', () => {
  assert.equal(formatPercent(0.6), '60.00%')
  assert.equal(formatPercent(0.6, 1), '60.0%')
  assert.equal(formatPercent(0.2), '20.00%')
  assert.equal(formatPercent(1), '100.00%')
  assert.equal(formatPercent(0.3333), '33.33%')
})

test('已是百分数时直接拼百分号，不重复乘 100', () => {
  assert.equal(formatPercentValue(60), '60.00%')
  assert.equal(formatPercentValue('12.5', 1), '12.5%')
})

test('isNumeric 的边界', () => {
  assert.equal(isNumeric(0), true)
  assert.equal(isNumeric('0'), true)
  assert.equal(isNumeric(-1), true)
  assert.equal(isNumeric(null), false)
  assert.equal(isNumeric(undefined), false)
  assert.equal(isNumeric(''), false)
  assert.equal(isNumeric(Infinity), false)
})
