// 目的：钉住「清单外指标」的量纲/展示名登记表（契约文首 v1.10「展示义务」）。
// 被治的缺陷（S3-41 开工前实测，真实模块 + 页面分支表达式）：
//   `cart_rate`=0.2531 ⇒ 显示 `0.25`（**比例被当两位小数**）；`fav_cnt`=12345 ⇒ 显示 `12,345.00`
//   （**计数带小数尾**）；字典缺行时标题回退成裸码 `cart_add_cnt`。
// 边界：本表**只**回答「怎么显示」（量纲/展示名），**不**回答「怎么算」——口径唯一 owner 仍是
// `analytics_meta.metric_definition`（页面「查看指标口径」面板）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
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

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..')
const publisherSource = readFileSync(join(
  repoRoot,
  'analytics-server', 'metric-analysis', 'src', 'main', 'java',
  'com', 'graduation', 'analytics', 'metric', 'publish', 'MetricPublisher.java'
), 'utf8')
const overviewSource = readFileSync(join(repoRoot, 'web', 'src', 'views', 'Overview.vue'), 'utf8')
const metaMigrationDir = join(repoRoot, 'analytics-server', 'platform-app', 'src', 'main', 'resources', 'db', 'meta')

function publishedCodesFromSource(source) {
  const codes = new Set()
  for (const match of source.matchAll(/OVERVIEW_TO_METRIC\.put\("[^"]+",\s*"([^"]+)"\);/g)) {
    codes.add(match[1])
  }
  // 概览映射之外，漏斗整体率直接由 valueOf(request, "...") 发布。
  for (const match of source.matchAll(/valueOf\(request,\s*"([a-z0-9_]+)"/g)) {
    codes.add(match[1])
  }
  return [...codes].sort()
}

function cardCodesFromSource(source) {
  const start = source.indexOf('const CARD_META = [')
  assert.notEqual(start, -1, 'Overview.vue 必须保留 CARD_META owner；找不到时不得静默返回空集合')
  const end = source.indexOf('\n]', start)
  assert.notEqual(end, -1, 'CARD_META 必须能定位到闭合 ]；解析器不得空跑')
  const block = source.slice(start, end)
  return [...block.matchAll(/\{\s*code:\s*'([^']+)'/g)].map((match) => match[1]).sort()
}

const PUBLISHED_CODES = publishedCodesFromSource(publisherSource)
const CARD_CODES = cardCodesFromSource(overviewSource)

function metricDefinitionCodesFromMigrations(dir) {
  const codes = new Set()
  const files = readdirSync(dir).filter((name) => /^V\d+__.*\.sql$/.test(name)).sort()
  assert.ok(files.length >= 10, `metric definition 迁移扫描面异常少：${files.length}`)
  for (const name of files) {
    const source = readFileSync(join(dir, name), 'utf8')
    if (!/metric_definition/.test(source)) continue

    // V2 形态：INSERT ... VALUES ('pv', ...), ('uv', ...)
    const valuesBlock = source.match(/INSERT\s+INTO\s+metric_definition[\s\S]*?VALUES([\s\S]*?);/i)
    if (valuesBlock) {
      for (const row of valuesBlock[1].matchAll(/\(\s*'([^']+)'\s*,/g)) codes.add(row[1])
    }

    // 后续加性迁移形态：INSERT ... SELECT 'full_refund_rate', ...
    for (const match of source.matchAll(/INSERT\s+INTO\s+metric_definition[\s\S]*?SELECT\s+'([^']+)'\s*,[\s\S]*?;/gi)) {
      codes.add(match[1])
    }
  }
  return [...codes].sort()
}

const MIGRATED_DICTIONARY_CODES = metricDefinitionCodesFromMigrations(metaMigrationDir)

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

test('跨树对账：真实 CARD_META 与清单外登记表不重叠，合起来＝真实 MetricPublisher 可产出码全集', () => {
  assert.ok(PUBLISHED_CODES.length >= 10, `MetricPublisher 解析结果异常少：${JSON.stringify(PUBLISHED_CODES)}`)
  assert.ok(CARD_CODES.length >= 5, `CARD_META 解析结果异常少：${JSON.stringify(CARD_CODES)}`)
  assert.equal(new Set(PUBLISHED_CODES).size, PUBLISHED_CODES.length, '发布侧指标码不得重复')
  assert.equal(new Set(CARD_CODES).size, CARD_CODES.length, 'CARD_META 指标码不得重复')
  const overlap = Object.keys(OUTSIDE_METRIC_KINDS).filter((c) => CARD_CODES.includes(c))
  assert.deepEqual(overlap, [], '清单外登记表不得与 CARD_META 的码重叠（两处量纲会打架）')
  const union = [...CARD_CODES, ...Object.keys(OUTSIDE_METRIC_KINDS)].sort()
  assert.deepEqual(union, PUBLISHED_CODES, 'CARD_META ∪ 清单外登记表 必须等于 MetricPublisher 真实可产出码全集')
})

test('跨树解析器有牙齿：Java 概览映射与直接发布两种形态都参与指标码集合', () => {
  const fixture = `
    OVERVIEW_TO_METRIC.put("sale_amount", "gmv");
    OVERVIEW_TO_METRIC.put("order_count", "paid_order_cnt");
    values.add(valueOf(request, "buy_rate", BigDecimal.ONE));
  `
  assert.deepEqual(publishedCodesFromSource(fixture), ['buy_rate', 'gmv', 'paid_order_cnt'])
})

test('跨树对账：MetricPublisher 每个可产出码都已在累计 metric_definition 迁移链登记', () => {
  const missing = PUBLISHED_CODES.filter((code) => !MIGRATED_DICTIONARY_CODES.includes(code))
  assert.deepEqual(missing, [], `发布侧存在未登记 metric_definition 迁移的指标码：${JSON.stringify(missing)}`)
  for (const code of ['full_refund_rate', 'fav_cnt', 'cart_add_cnt']) {
    assert.ok(MIGRATED_DICTIONARY_CODES.includes(code), `${code} 的后续加性迁移不得被漏扫`)
  }
})

test('metric_definition 迁移解析器有牙齿：同时识别 VALUES 批量种子与 SELECT 加性迁移', () => {
  const fixtureDir = metaMigrationDir
  // 真树中两种形态都必须存在，否则上面的累计扫描可能退化成只覆盖一种写法。
  const sources = readdirSync(fixtureDir)
    .filter((name) => /^V\d+__.*\.sql$/.test(name))
    .map((name) => readFileSync(join(fixtureDir, name), 'utf8'))
    .filter((source) => /metric_definition/.test(source))
  assert.ok(sources.some((source) => /INSERT\s+INTO\s+metric_definition[\s\S]*?VALUES/i.test(source)), '必须存在 VALUES 形态见证')
  assert.ok(sources.some((source) => /INSERT\s+INTO\s+metric_definition[\s\S]*?SELECT\s+'/i.test(source)), '必须存在 SELECT 加性迁移形态见证')
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
