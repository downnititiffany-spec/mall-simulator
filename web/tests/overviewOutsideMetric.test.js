// 目的：钉住**页面侧**对「清单外指标」的展示面（契约文首 v1.10「展示义务」）。
// `Overview.vue` 依赖 `vue`/SFC 编译，本仓无 `node_modules` ⇒ 只能做**源码文本守卫**
// （与 `tests/overviewWindowMetric.test.js` 同型）。
// 被治的缺陷（S3-41 开工前实测）：清单外分支 `formatNumber(m.value, 2)` ⇒ `cart_rate` 0.2531
// 显示 `0.25`（比例当小数）、`fav_cnt` 12345 显示 `12,345.00`（计数带小数尾）；
// 且字典缺该码时标题回退裸码 `cart_add_cnt`（实测该 3 码在已发布 V2 的 metric_definition 种子里无行）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const webSrc = join(here, '..', 'src')
const overviewSrc = readFileSync(join(webSrc, 'views', 'Overview.vue'), 'utf8')
const ownerSrc = readFileSync(join(webSrc, 'utils', 'metricDisplay.js'), 'utf8')

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else if (/\.(js|vue)$/.test(name)) out.push(p)
  }
  return out
}
const rel = (p) => p.slice(webSrc.length + 1).replace(/\\/g, '/')
const allFiles = walk(webSrc)
const filesWith = (needle) => allFiles.filter((p) => readFileSync(p, 'utf8').includes(needle)).map(rel).sort()

test('Overview 的清单外量纲/展示名只来自唯一属主 utils/metricDisplay.js', () => {
  assert.match(
    overviewSrc,
    /import\s*\{[^}]*formatOutsideMetricValue[^}]*\}\s*from\s*'\.\.\/utils\/metricDisplay'/,
    'Overview.vue 必须从 ../utils/metricDisplay 引入 formatOutsideMetricValue'
  )
  assert.match(overviewSrc, /import\s*\{[^}]*displayMetricName[^}]*\}\s*from\s*'\.\.\/utils\/metricDisplay'/)
  assert.match(overviewSrc, /import\s*\{[^}]*DICTIONARY_MISSING_NOTE[^}]*\}\s*from\s*'\.\.\/utils\/metricDisplay'/)
})

test('清单外分支按量纲格式化，不再一律两位小数（缺陷本体）', () => {
  assert.match(overviewSrc, /formatOutsideMetricValue\(m\.metricCode, m\.value\)/, '清单外分支必须按码取量纲')
  assert.doesNotMatch(overviewSrc, /formatNumber\(m\.value, 2\)/, '不得再对清单外指标一律 formatNumber(v, 2)')
  assert.match(overviewSrc, /displayMetricName\(m\.metricCode, m\.metricName\)/, '标题必须走三级回退属主')
})

test('视图不得自持第二份比例/量纲实现（防第二属主）', () => {
  assert.doesNotMatch(overviewSrc, /\*\s*100/, 'Overview.vue 不得自己做 decimal→百分比换算')
  assert.doesNotMatch(overviewSrc, /toFixed\(/, 'Overview.vue 不得自己格式化小数')
  assert.doesNotMatch(overviewSrc, /['"]%['"]/, 'Overview.vue 不得自己拼百分号')
  assert.doesNotMatch(ownerSrc, /\*\s*100/, 'metricDisplay.js 不得自己换算百分比（应用 number.js 的 formatPercent）')
  assert.doesNotMatch(ownerSrc, /toFixed\(/)
  assert.match(ownerSrc, /from\s*'\.\/number(\.js)?'/, 'metricDisplay.js 必须复用 number.js 的格式化函数')
  assert.doesNotMatch(ownerSrc, /from\s*'vue'/, '属主必须是纯逻辑，不依赖 vue')
})

test('登记表与展示名只有 utils/metricDisplay.js 一个属主', () => {
  assert.deepEqual(filesWith('OUTSIDE_METRIC_KINDS'), ['utils/metricDisplay.js'])
  assert.deepEqual(filesWith('METRIC_KIND_PERCENT'), ['utils/metricDisplay.js'])
  assert.deepEqual(filesWith('DICTIONARY_MISSING_NOTE'), ['utils/metricDisplay.js', 'views/Overview.vue'])
})

test('字典缺该码时给出「字典未登记口径」提示（不推断口径）；字典整体为空时不逐卡重复', () => {
  assert.match(overviewSrc, /m\.dictionaryMissing/, '卡片数据必须带 dictionaryMissing 标记')
  assert.match(overviewSrc, /DICTIONARY_MISSING_NOTE/, '模板必须渲染该提示文案')
  assert.match(overviewSrc, /dict\.length > 0 && !dictCodes\.has\(/, '字典整体为空时必须整卡豁免')
  // 两段 push（固定卡片 / 清单外）都必须真的带上标记——只留函数定义不算（M2 突变实测会漏）
  assert.match(
    overviewSrc,
    /dictionaryMissing:\s*dictionaryMissing\(m\.metricCode\)/,
    '清单外条目必须标记 dictionaryMissing'
  )
  assert.match(
    overviewSrc,
    /dictionaryMissing:\s*dictionaryMissing\(meta\.code\)/,
    '固定卡片条目必须标记 dictionaryMissing'
  )
})

test('固定卡片码与清单外登记表不重叠（两处量纲不得打架）', () => {
  const metaBlock = overviewSrc.slice(overviewSrc.indexOf('const CARD_META = ['), overviewSrc.indexOf(']', overviewSrc.indexOf('const CARD_META = [')))
  const cardCodes = [...metaBlock.matchAll(/code:\s*'([^']+)'/g)].map((m) => m[1])
  assert.equal(cardCodes.length, 9, `CARD_META 应含 9 个码，实测 ${cardCodes.length}`)
  const kindBlock = ownerSrc.slice(ownerSrc.indexOf('OUTSIDE_METRIC_KINDS = Object.freeze({'), ownerSrc.indexOf('})', ownerSrc.indexOf('OUTSIDE_METRIC_KINDS = Object.freeze({')))
  const outsideCodes = [...kindBlock.matchAll(/([a-z_]+):\s*METRIC_KIND_/g)].map((m) => m[1])
  assert.deepEqual(outsideCodes.filter((c) => cardCodes.includes(c)), [])
  assert.equal(cardCodes.length + outsideCodes.length, 14, '9 张固定卡 ＋ 5 个清单外码 ＝ 发布侧 14 码')
})
