// 02.6 平台独立性单测：时效提示纯逻辑 + Overview 接线锚点（node --test 范式，见 sourceWizard.test.js）。
// 验收锚点（指导书 V3.1 02.6）：关闭商城/生成器后历史指标可读；来源停机显示时效警告，不假报最新。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { latestBusinessDate, stalenessLagDays, stalenessNotice } from '../src/utils/staleness.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const overviewVue = fs.readFileSync(path.resolve(here, '../src/views/Overview.vue'), 'utf8')
const stalenessSource = fs.readFileSync(path.resolve(here, '../src/utils/staleness.js'), 'utf8')

const day = (code, date) => ({ metricCode: code, period: `day:${date}` })
const window = (code, start, end) => ({ metricCode: code, period: `window:${start}..${end}` })

// ---------- latestBusinessDate ----------

test('最新业务时点：day 取该日、window 取观察期两端、畸形/未知周期忽略、空列表 null', () => {
  assert.equal(latestBusinessDate([]), null)
  assert.equal(latestBusinessDate(undefined), null)
  assert.equal(latestBusinessDate([day('gmv', '2026-09-18')]), '2026-09-18')
  // window 的观察期截至日也可成为最新时点（覆盖日 > 单日时取大者）
  assert.equal(
    latestBusinessDate([day('gmv', '2026-09-16'), window('repeat_rate', '2026-09-12', '2026-09-18')]),
    '2026-09-18'
  )
  assert.equal(
    latestBusinessDate([window('repeat_rate', '2026-09-12', '2026-09-17'), day('gmv', '2026-09-16')]),
    '2026-09-17'
  )
  // 畸形 period（hour: 前缀 / 非 ISO / 非字符串 cell）一律忽略，不猜
  assert.equal(latestBusinessDate([{ metricCode: 'x', period: 'hour:2026-09-18' }]), null)
  assert.equal(latestBusinessDate([{ metricCode: 'x', period: 'day:2026-9-1' }]), null)
  assert.equal(latestBusinessDate([{ metricCode: 'x' }, null, 'junk']), null)
})

// ---------- stalenessLagDays ----------

test('自然日差：同日 0、滞后 2 天为 2、跨月正确、畸形返回 null', () => {
  assert.equal(stalenessLagDays('2026-09-20', '2026-09-20'), 0)
  assert.equal(stalenessLagDays('2026-09-18', '2026-09-20'), 2)
  assert.equal(stalenessLagDays('2026-08-31', '2026-09-01'), 1)
  // 业务时点在未来（时钟偏差）：负值，由 stalenessNotice 归入"不提示"
  assert.equal(stalenessLagDays('2026-09-21', '2026-09-20'), -1)
  assert.equal(stalenessLagDays('2026-9-18', '2026-09-20'), null)
  assert.equal(stalenessLagDays('', '2026-09-20'), null)
  assert.equal(stalenessLagDays('2026-09-18', undefined), null)
})

// ---------- stalenessNotice ----------

test('时效提示：滞后 ≥1 天才出现，文案只陈述事实并明示「不代表最新」', () => {
  // 02.6 现场景：最新业务时点 2026-09-18，今日 2026-09-20 → 滞后 2 天
  const notice = stalenessNotice([day('gmv', '2026-09-18'), day('uv', '2026-09-18')], '2026-09-20')
  assert.ok(notice)
  assert.equal(notice.businessDate, '2026-09-18')
  assert.equal(notice.lagDays, 2)
  assert.match(notice.text, /业务时点为 2026-09-18/)
  assert.match(notice.text, /滞后 2 天/)
  assert.match(notice.text, /历史指标仍可读/)
  assert.match(notice.text, /不代表最新/)

  // 同日（滞后 0）：null —— 不渲染横幅，也绝不宣称「最新」
  assert.equal(stalenessNotice([day('gmv', '2026-09-20')], '2026-09-20'), null)
  // 未来时点：null
  assert.equal(stalenessNotice([day('gmv', '2026-09-21')], '2026-09-20'), null)
  // 无可识别周期：null
  assert.equal(stalenessNotice([{ metricCode: 'x', period: 'hour:2026-09-18' }], '2026-09-20'), null)
  // window 口径取观察期截至日
  const wn = stalenessNotice([window('repeat_rate', '2026-09-10', '2026-09-17')], '2026-09-20')
  assert.equal(wn.businessDate, '2026-09-17')
  assert.equal(wn.lagDays, 3)
})

test('不假报最新：用户可见文案里「最新」只允许出现在否定式「不代表最新」中', () => {
  const notice = stalenessNotice([day('gmv', '2026-09-18')], '2026-09-20')
  assert.match(notice.text, /不代表最新/)
  const withoutNegation = notice.text.replace(/不代表最新/g, '')
  assert.doesNotMatch(withoutNegation, /最新|实时/)
})

// ---------- Overview 接线 ----------

test('Overview 接线时效横幅且角色无关：不调 /sources、用本地今日比较', () => {
  assert.match(overviewVue, /import \{ stalenessNotice \} from '\.\.\/utils\/staleness'/)
  assert.match(overviewVue, /stalenessNotice\(data\.value\.metrics, localIsoDayOffset\(0\)\)/)
  assert.match(overviewVue, /data-test="staleness-note"/)
  assert.match(overviewVue, /v-if="staleness"/)
  // 角色无关：Overview 代码不得引用 /sources 端点封装（分析师无 RUNTIME_MANAGE）；
  // 注释行剔除后再断言（注释里的「不调 /sources」是约束陈述，不是调用）。
  const overviewCode = overviewVue.split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
  assert.doesNotMatch(overviewCode, /api\.sources|\/sources/)
  // 派生属主唯一：视图不自拼日期比较逻辑（不 import Date 工具之外的解析器）
  assert.doesNotMatch(overviewVue, /parseMetricPeriod/)
})
