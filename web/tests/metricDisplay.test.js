// 目的：钉住「清单外指标」的量纲/展示名登记表（契约文首 v1.10「展示义务」）。
// 被治的缺陷（S3-41 开工前实测，真实模块 + 页面分支表达式）：
//   `cart_rate`=0.2531 ⇒ 显示 `0.25`（**比例被当两位小数**）；`fav_cnt`=12345 ⇒ 显示 `12,345.00`
//   （**计数带小数尾**）；字典缺行时标题回退成裸码 `cart_add_cnt`。
// 边界：本表**只**回答「怎么显示」（量纲/展示名），**不**回答「怎么算」——口径唯一 owner 仍是
// `analytics_meta.metric_definition`（页面「查看指标口径」面板）。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  OUTSIDE_METRIC_KINDS,
  OUTSIDE_METRIC_NAMES,
  METRIC_KIND_PERCENT,
  METRIC_KIND_INTEGER,
  DICTIONARY_MISSING_NOTE,
  outsideMetricKind,
  displayMetricName,
  formatOutsideMetricValue
} from '../src/utils/metricDisplay.js'

// 发布侧可产出码集合（`MetricPublisher.OVERVIEW_TO_METRIC` 的 12 个值 ＋ 漏斗整体率 buy_rate/cart_rate）。
// 注：这是**镜像清单**；「发布侧 Java 码集合 ↔ 本登记表」的**跨树自动守卫**尚未实现
// （契约 v1.10「仍未实现」已登记为候选），本轮只保证 web 侧两处（登记表 vs CARD_META）不漂移。
const PUBLISHED_CODES = [
  'pv', 'uv', 'dau', 'paid_order_cnt', 'gmv', 'net_sale', 'avg_order_value',
  'refund_rate', 'full_refund_rate', 'repeat_rate', 'fav_cnt', 'cart_add_cnt',
  'buy_rate', 'cart_rate'
]
// 页面固定卡片（`Overview.vue` 的 CARD_META，顺序与量纲由它决定）
const CARD_CODES = [
  'gmv', 'net_sale', 'paid_order_cnt', 'pv', 'uv', 'dau', 'avg_order_value', 'refund_rate', 'repeat_rate'
]

test('登记表只覆盖「清单外」的 5 个码：比例 3 ＋ 计数 2', () => {
  assert.deepEqual(Object.keys(OUTSIDE_METRIC_KINDS).sort(), [
    'buy_rate', 'cart_add_cnt', 'cart_rate', 'fav_cnt', 'full_refund_rate'
  ])
  assert.equal(outsideMetricKind('cart_rate'), METRIC_KIND_PERCENT)
  assert.equal(outsideMetricKind('buy_rate'), METRIC_KIND_PERCENT)
  assert.equal(outsideMetricKind('full_refund_rate'), METRIC_KIND_PERCENT)
  assert.equal(outsideMetricKind('fav_cnt'), METRIC_KIND_INTEGER)
  assert.equal(outsideMetricKind('cart_add_cnt'), METRIC_KIND_INTEGER)
})

test('登记表与固定卡片清单不重叠、合起来＝发布侧可产出码全集（web 侧不漂移）', () => {
  const overlap = Object.keys(OUTSIDE_METRIC_KINDS).filter((c) => CARD_CODES.includes(c))
  assert.deepEqual(overlap, [], '清单外登记表不得与 CARD_META 的码重叠（两处量纲会打架）')
  const union = [...CARD_CODES, ...Object.keys(OUTSIDE_METRIC_KINDS)].sort()
  assert.deepEqual(union, [...PUBLISHED_CODES].sort(), 'CARD_META ∪ 清单外登记表 必须等于发布侧可产出码全集')
})

test('未登记的码不猜量纲：返回 null，值照实按两位小数展示', () => {
  assert.equal(outsideMetricKind('roi'), null)
  assert.equal(outsideMetricKind(''), null)
  assert.equal(outsideMetricKind(null), null)
  assert.equal(outsideMetricKind(undefined), null)
  assert.equal(formatOutsideMetricValue('roi', 0.5), '0.50', '未登记码不得擅自乘 100')
  assert.doesNotMatch(formatOutsideMetricValue('roi', 0.5), /%/)
})

test('比例型按百分比展示（契约 §1 总原则 4：比值给 decimal 与百分号语义）', () => {
  assert.equal(formatOutsideMetricValue('cart_rate', 0.2531), '25.31%')
  assert.equal(formatOutsideMetricValue('buy_rate', 0.0812), '8.12%')
  assert.equal(formatOutsideMetricValue('full_refund_rate', 0.0123), '1.23%')
})

test('计数型按整数展示（与 PV/UV/DAU 卡片同型）', () => {
  assert.equal(formatOutsideMetricValue('fav_cnt', 12345), '12,345')
  assert.equal(formatOutsideMetricValue('cart_add_cnt', 6789), '6,789')
  assert.doesNotMatch(formatOutsideMetricValue('fav_cnt', 12345), /\./)
})

test('空值一律占位符、0 仍显示为 0（不得显示成 0、也不得把 0 抹掉）', () => {
  for (const code of ['cart_rate', 'buy_rate', 'full_refund_rate', 'fav_cnt', 'cart_add_cnt', 'roi']) {
    assert.equal(formatOutsideMetricValue(code, null), '—', `${code} null`)
    assert.equal(formatOutsideMetricValue(code, undefined), '—', `${code} undefined`)
    assert.equal(formatOutsideMetricValue(code, ''), '—', `${code} 空串`)
  }
  assert.equal(formatOutsideMetricValue('cart_rate', 0), '0.00%')
  assert.equal(formatOutsideMetricValue('fav_cnt', 0), '0')
})

test('展示名三级回退：字典名 → 登记表 → 指标码；字典有名字时登记表不得覆盖', () => {
  assert.equal(displayMetricName('buy_rate', '购买转化率'), '购买转化率', '字典名优先')
  assert.equal(displayMetricName('buy_rate', ''), 'buy_rate', '登记表无该名 ⇒ 回退指标码')
  assert.equal(displayMetricName('full_refund_rate', ''), '全额退款率')
  assert.equal(displayMetricName('fav_cnt', ''), '收藏次数')
  assert.equal(displayMetricName('cart_add_cnt', ''), '加购次数')
  assert.equal(displayMetricName('mystery_metric', ''), 'mystery_metric', '未知码不得臆造名称')
  assert.equal(displayMetricName('mystery_metric', null), 'mystery_metric')
})

test('展示名登记表不含固定卡片码（防与 CARD_META 名称打架）', () => {
  const overlap = Object.keys(OUTSIDE_METRIC_NAMES).filter((c) => CARD_CODES.includes(c))
  assert.deepEqual(overlap, [])
  assert.equal(DICTIONARY_MISSING_NOTE, '字典未登记口径')
})
