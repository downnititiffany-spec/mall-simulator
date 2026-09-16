// 目的：钉住**页面侧**对窗口口径指标的展示面（契约 v1.9「展示义务」）。
// `Overview.vue` 依赖 `vue`/SFC 编译，本仓无 `node_modules` ⇒ 只能做**源码文本守卫**
// （与 `tests/useAnalysisFilters.test.js` / `tests/pipelinePage.test.js` 同型）。
//
// 被治的缺陷（S3-40 开工前实测）：`repeat_rate` **已经**由后端经 `metric_value` →
// `/dashboards/overview` 的 `data.metrics[]` 返回（`AnalysisService.metrics()` 遍历全部
// `metric_value` 行，`MetricItem` 自带 `period`），而 `Overview.vue` 对清单外指标走
// "照实展示"分支：`formatNumber(m.value, 2)` ⇒ 复购率 0.3333 显示成 `0.33`（**不是百分比**），
// 且**不显示观察期**、与单日指标同墙 ⇒ 用户会把 30 天窗口值读成"当日值"（混口径）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const webSrc = join(here, '..', 'src')
const overviewSrc = readFileSync(join(webSrc, 'views', 'Overview.vue'), 'utf8')
const ownerSrc = readFileSync(join(webSrc, 'utils', 'metricPeriod.js'), 'utf8')

test('Overview 的周期文案与解析只来自唯一属主 utils/metricPeriod.js', () => {
  assert.match(
    overviewSrc,
    /import\s*\{[^}]*periodText[^}]*\}\s*from\s*'\.\.\/utils\/metricPeriod'/,
    'Overview.vue 必须从 ../utils/metricPeriod 引入 periodText'
  )
  assert.match(
    overviewSrc,
    /import\s*\{[^}]*WINDOW_METRIC_NOTE[^}]*\}\s*from\s*'\.\.\/utils\/metricPeriod'/,
    'Overview.vue 必须从 ../utils/metricPeriod 引入 WINDOW_METRIC_NOTE'
  )
})

test('Overview 不得自己解析 period（防第二属主）', () => {
  assert.doesNotMatch(overviewSrc, /window:/, 'Overview.vue 不得出现 `window:` 字面量')
  assert.doesNotMatch(overviewSrc, /day:/, 'Overview.vue 不得出现 `day:` 字面量')
  assert.doesNotMatch(
    overviewSrc,
    /startsWith\(\s*['"]window/,
    'Overview.vue 不得自行按前缀解析 period'
  )
})

test('`web/src` 下 `window:` 口径字面量只允许出现在唯一属主里', () => {
  const hits = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name)
      if (statSync(full).isDirectory()) walk(full)
      else if (/\.(js|vue)$/.test(name) && readFileSync(full, 'utf8').includes('window:')) {
        hits.push(full.slice(webSrc.length + 1).split('\\').join('/'))
      }
    }
  }
  walk(webSrc)
  assert.deepEqual(
    hits,
    ['utils/metricPeriod.js'],
    `窗口口径解析属主必须唯一，实际命中：${JSON.stringify(hits)}`
  )
})

test('repeat_rate 按比例展示（百分比），不再走"清单外指标"的两位小数分支', () => {
  const line = overviewSrc.split('\n').find((l) => l.includes("code: 'repeat_rate'"))
  assert.ok(line, "CARD_META 必须显式登记 repeat_rate（否则它落到 formatNumber(...,2) 分支）")
  assert.match(line, /percent:\s*true/, 'repeat_rate 是 decimal 比例 ⇒ 必须 percent: true')
})

test('指标卡展示观察期副标题（仅窗口口径有值时才渲染）', () => {
  assert.match(overviewSrc, /v-if="m\.periodText"/, '卡片必须有 periodText 的条件渲染')
  assert.match(overviewSrc, /\{\{\s*m\.periodText\s*\}\}/, '卡片必须真的把 periodText 渲染出来')
})

test('窗口口径限制说明渲染在卡片墙上（v-if 为假不显示）', () => {
  assert.match(overviewSrc, /WINDOW_METRIC_NOTE/, '必须渲染窗口口径限制说明')
  assert.match(
    overviewSrc,
    /v-if="windowNote"/,
    '限制说明必须按需渲染（快照内没有窗口型指标时不显示）'
  )
})

test('CSV 导出携带观察期列（页面看得到的东西导出件也要看得到）', () => {
  assert.match(overviewSrc, /'口径周期'/, '导出表头必须含「口径周期」列')
  assert.match(overviewSrc, /c\.periodText\s*\|\|/, '导出行必须带 periodText（缺失按占位符，不臆造）')
})

test('属主自身不得反向依赖视图（单向依赖：view → utils）', () => {
  assert.doesNotMatch(ownerSrc, /from\s*'[^']*views\//, 'utils/metricPeriod.js 不得依赖 views/**')
  assert.doesNotMatch(ownerSrc, /from\s*'vue'/, 'utils/metricPeriod.js 必须保持纯逻辑（可被 node:test 直接 import）')
})
