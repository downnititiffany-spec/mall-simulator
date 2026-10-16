// 目的：把「指标口径周期」（`metric_value.period`）的**解析与展示文案**钉在唯一属主
// `web/src/utils/metricPeriod.js` 上（契约 `docs/contracts/analysis-viewmodel-r7-4.md`
// 文首 **v1.9** 与 §3.1 v1.9 条）。
//
// 契约口径（逐字边界）：`metrics[*].period` 取值域含两类 ——
//   ① `day:<yyyy-MM-dd>`   单日口径
//   ② `window:<start>..<end>` 窗口/观察期口径（在产码 `repeat_rate`，发布侧
//      `MetricPublisher.WINDOWED_REPEAT_RATE` 声明）
// 本模块**只解析格式**：不重算数值、不给缺省观察期、不把畸形值"修好" ——
// 畸形/缺失一律 `unknown` + `null` 文案（与 §1.4「空值不显示成 0」同一纪律）。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseMetricPeriod,
  isWindowPeriod,
  periodText,
  WINDOW_METRIC_NOTE
} from '../src/utils/metricPeriod.js'

test('day 口径：识别为单日，不产生需要额外提示的周期文案', () => {
  const p = parseMetricPeriod('day:2026-09-01')
  assert.equal(p.kind, 'day')
  assert.equal(p.start, '2026-09-01')
  assert.equal(p.end, '2026-09-01')
  assert.equal(isWindowPeriod('day:2026-09-01'), false)
  // 卡片墙上单日指标不需要额外副标题（页面已统一声明"指标卡固定取本次快照"）
  assert.equal(periodText('day:2026-09-01'), null)
})

test('window 口径：两端齐全 ⇒ 完整观察期文案', () => {
  const raw = 'window:2026-08-07..2026-09-05'
  const p = parseMetricPeriod(raw)
  assert.equal(p.kind, 'window')
  assert.equal(p.start, '2026-08-07')
  assert.equal(p.end, '2026-09-05')
  assert.equal(isWindowPeriod(raw), true)
  assert.equal(periodText(raw), '观察期 2026-08-07 ~ 2026-09-05')
})

test('window 口径：两端同日 ⇒ 不假装是区间', () => {
  assert.equal(periodText('window:2026-08-07..2026-08-07'), '观察期 2026-08-07')
})

test('window 口径：只声明一端 ⇒ 如实说明"起/至"，不补另一端', () => {
  assert.equal(periodText('window:2026-08-07..'), '观察期 2026-08-07 起')
  assert.equal(periodText('window:..2026-09-05'), '观察期 至 2026-09-05')
  const onlyStart = parseMetricPeriod('window:2026-08-07..')
  assert.equal(onlyStart.end, null, '缺的一端必须是 null，不得回填成 start')
})

test('window 口径：两端都缺 ⇒ unknown（不猜、不给缺省观察期）', () => {
  for (const raw of ['window:..', 'window:', 'window: .. ']) {
    assert.equal(parseMetricPeriod(raw).kind, 'unknown', `${raw} 应判 unknown`)
    assert.equal(periodText(raw), null, `${raw} 不得产出文案`)
  }
})

test('畸形/非法输入 ⇒ unknown 且无文案（不臆造）', () => {
  const bad = [
    'window:abc..def',
    'window:2026-09-01',
    'day:2026-9-1',
    'day:',
    'day:20260901',
    'hour:2026-09-01',
    '2026-09-01',
    'window',
    '',
    '   ',
    null,
    undefined,
    123,
    {},
    []
  ]
  for (const raw of bad) {
    assert.equal(parseMetricPeriod(raw).kind, 'unknown', `${JSON.stringify(raw)} 应判 unknown`)
    assert.equal(periodText(raw), null, `${JSON.stringify(raw)} 不得产出文案`)
    assert.equal(isWindowPeriod(raw), false, `${JSON.stringify(raw)} 不得被判为窗口口径`)
  }
})

test('两端空白与整体空白：按去空白解析，不把空白当有效值', () => {
  assert.equal(parseMetricPeriod(' day:2026-09-01 ').kind, 'day')
  const p = parseMetricPeriod('window: 2026-08-07 .. 2026-09-05 ')
  assert.equal(p.kind, 'window')
  assert.equal(p.start, '2026-08-07')
  assert.equal(p.end, '2026-09-05')
  assert.equal(periodText('window: 2026-08-07 .. '), '观察期 2026-08-07 起')
})

test('文案常量：说明"观察期口径 + 不重算"，且不得把它说成当日值', () => {
  assert.equal(typeof WINDOW_METRIC_NOTE, 'string')
  assert.ok(WINDOW_METRIC_NOTE.length > 0, '限制说明不得为空')
  assert.match(WINDOW_METRIC_NOTE, /观察期/)
  assert.match(WINDOW_METRIC_NOTE, /不重算/)
  // 反面：不得声称与单日指标同期（那正是本轮要消除的误导）
  assert.doesNotMatch(WINDOW_METRIC_NOTE, /即当日|等于当日|与单日指标同期/)
})
