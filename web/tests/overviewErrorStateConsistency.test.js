// Overview 大盘错误态旧数据呈现与横幅口径一致性守卫测试（U03-S01）
// 依据：《现有功能完善实施指导书 V1.0》§5 U03、问题 UP-20261018-11（P2）+ UP-20261018-20（P2/测试空白）
// 规约：mall-simulator-notes/U03-S01.md
//
// 说明：本测试为源码结构守卫（本仓无 SFC 编译器，以读源码结构断言与公式纯函数仿真锁定设计不回退，
// 如实注明此为源码守卫与公式仿真、不得冒充真实 DOM 渲染证据）。
// 断言作用域严格限定在关键模板与计算属性代码片段内，防止逻辑回退与五页口径漂移。

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

test('a. Overview.vue 不再包含 v-if="!empty && !failed"，且主体模板为 v-if="showBody"', () => {
  const overviewSource = read('../src/views/Overview.vue')
  assert.doesNotMatch(
    overviewSource,
    /v-if="!empty\s*&&\s*!failed"/,
    'Overview.vue 主体不得包含 !empty && !failed 顶层阻断形态（回退防护）'
  )
  assert.match(
    overviewSource,
    /<template\s+v-if="showBody">/,
    'Overview.vue 主体模板必须采用 v-if="showBody"'
  )
})

test('b. hasData 计算属性存在且覆盖 metrics/salesTrend/activeTrend 三数组的非空判定', () => {
  const overviewSource = read('../src/views/Overview.vue')
  const start = overviewSource.indexOf('const hasData = computed(')
  assert.ok(start !== -1, 'Overview.vue 必须声明 const hasData = computed')
  const end = overviewSource.indexOf('const showBody = computed(', start)
  assert.ok(end !== -1, 'hasData 之后必须紧随 showBody computed 声明')
  const snippet = overviewSource.slice(start, end)

  // 源码断言三字段名与 Array.isArray 形态
  assert.match(snippet, /d\.metrics/, 'hasData 计算属性必须涵盖 d.metrics')
  assert.match(snippet, /d\.salesTrend/, 'hasData 计算属性必须涵盖 d.salesTrend')
  assert.match(snippet, /d\.activeTrend/, 'hasData 计算属性必须涵盖 d.activeTrend')
  assert.match(snippet, /\[d\.metrics,\s*d\.salesTrend,\s*d\.activeTrend\]\.some\(/, 'hasData 必须以 some 遍历三数组')
  assert.match(snippet, /Array\.isArray\(a\)\s*&&\s*a\.length\s*>\s*0/, 'hasData 必须使用 Array.isArray(a) && a.length > 0 判定非空')
})

test('c. showBody 计算属性形态为 failed.value ? hasData.value : !empty.value', () => {
  const overviewSource = read('../src/views/Overview.vue')
  assert.match(
    overviewSource,
    /const\s+showBody\s*=\s*computed\(\(\)\s*=>\s*\(?failed\.value\s*\?\s*hasData\.value\s*:\s*!empty\.value\)?\)/,
    'showBody 计算属性形态必须为 failed.value ? hasData.value : !empty.value'
  )
})

test('d. 行 85 全页失败分支与行 86 空快照分支仍在，未被删除或改动条件', () => {
  const overviewSource = read('../src/views/Overview.vue')
  assert.match(
    overviewSource,
    /<div\s+v-else-if="failed"\s+class="el-empty">\s*数据加载失败：\{\{\s*error\s*\}\}（不使用任何本地推算值代替）\s*<\/div>/,
    '行 85 全页失败分支必须为 v-else-if="failed" 且保留「不使用任何本地推算值代替」文案'
  )
  assert.match(
    overviewSource,
    /<div\s+v-else\s+class="el-empty">\s*当前没有可用指标快照（告警：\{\{\s*warningSummary\s*\}\}）。指标数值一律来自后端快照，页面不做推算。\s*<\/div>/,
    '行 86 空快照分支必须为 v-else 且保留「当前没有可用指标快照」文案'
  )
})

test('e. 其余四页 Sales/Behavior/Products/Rfm 的源码均不包含 && !failed 顶层阻断形态（五页同口径守护）', () => {
  const otherPages = [
    { name: 'Sales.vue', path: '../src/views/Sales.vue' },
    { name: 'Behavior.vue', path: '../src/views/Behavior.vue' },
    { name: 'Products.vue', path: '../src/views/Products.vue' },
    { name: 'Rfm.vue', path: '../src/views/Rfm.vue' }
  ]
  for (const page of otherPages) {
    const src = read(page.path)
    assert.doesNotMatch(
      src,
      /&&\s*!failed/,
      `${page.name} 不得包含 && !failed 顶层阻断形态（五分析页同口径守护）`
    )
  }
})

test('f. AnalysisContext.vue 的 error 横幅文案逐字未动（含「页面保留的数据为上一次成功结果」）', () => {
  const contextSource = read('../src/components/AnalysisContext.vue')
  assert.match(
    contextSource,
    /<div\s+v-if="state === 'error'"\s+class="banner banner-error">\s*数据加载失败：\{\{\s*error \|\| '未知原因'\s*\}\}。页面保留的数据为上一次成功结果。\s*<\/div>/,
    'AnalysisContext.vue 必须包含完整 error 横幅且文案含「页面保留的数据为上一次成功结果」'
  )
})

test('g. showBody 真值表仿真：以同形纯函数复刻并断言六行真值表中的四行可区分情形', () => {
  // 注释注明：此为公式纯函数仿真，非 SFC 真实 DOM 渲染证据。
  // 公式形态：failed ? hasData : !empty
  // 状态真值表对应（见 U03-S01 规约 §契约 目标行为）：
  // 1. loading（首次，无数据）：failed=false, empty=false, hasData=false -> showBody=true（卡片/图表盒显示，图表呈 loading 遮罩）
  // 2. stale（刷新中，有旧数据）：failed=false, empty=false, hasData=true -> showBody=true（旧数据保留）
  // 3. ready（有数据）：failed=false, empty=false, hasData=true -> showBody=true
  // 4. empty（成功无数据）：failed=false, empty=true, hasData=false -> showBody=false（落入行 86 空快照分支）
  // 5. error（有旧数据）：failed=true, empty=false, hasData=true -> showBody=true（卡片/质量保留，图表呈 error 遮罩，本切片修复点）
  // 6. error（首次失败，无旧数据）：failed=true, empty=false, hasData=false -> showBody=false（落入行 85 全页失败分支）

  const computeShowBody = (failed, empty, hasData) => (failed ? hasData : !empty)

  // (failed=false, empty=false, hasData=任意) → true
  assert.equal(computeShowBody(false, false, false), true, '首次 loading 无旧数据时 showBody 为 true，主体渲染挂载 ChartState')
  assert.equal(computeShowBody(false, false, true), true, 'stale 或 ready 有数据时 showBody 为 true，主体正常展示')

  // (failed=false, empty=true) → false
  assert.equal(computeShowBody(false, true, false), false, 'empty 态无数据时 showBody 为 false，落入空快照分支')
  assert.equal(computeShowBody(false, true, true), false, 'empty 态即使 hasData 为真仍为 false（以 empty 为准）')

  // (failed=true, hasData=true) → true（本切片核心修复点）
  assert.equal(computeShowBody(true, false, true), true, 'error 态有旧数据时 showBody 为 true，保留主体旧数据与局部 ChartState 遮罩')

  // (failed=true, hasData=false) → false
  assert.equal(computeShowBody(true, false, false), false, 'error 态首次失败无旧数据时 showBody 为 false，落入全页失败分支')
})

test('hasData 行为语义仿真：metrics/salesTrend/activeTrend 任一非空即判定为有数据', () => {
  const evalHasData = (data) => {
    const d = data || {}
    return [d.metrics, d.salesTrend, d.activeTrend].some((a) => Array.isArray(a) && a.length > 0)
  }

  assert.equal(evalHasData(null), false, 'null 数据为 false')
  assert.equal(evalHasData({}), false, '空对象为 false')
  assert.equal(evalHasData({ metrics: [], salesTrend: [], activeTrend: [] }), false, '三数组均空为 false')
  assert.equal(evalHasData({ metrics: [{ metricCode: 'gmv', value: 100 }], salesTrend: [], activeTrend: [] }), true, '仅 metrics 有数据为 true')
  assert.equal(evalHasData({ metrics: [], salesTrend: [{ dt: '2026-10-18' }], activeTrend: [] }), true, '仅 salesTrend 有数据为 true')
  assert.equal(evalHasData({ metrics: [], salesTrend: [], activeTrend: [{ dt: '2026-10-18' }] }), true, '仅 activeTrend 有数据为 true')
  assert.equal(evalHasData({ metrics: 'not an array', salesTrend: null, activeTrend: undefined }), false, '非数组类型安全防护为 false')
})
