// ChartState 错误遮罩就地重试与五分析页统一接线守卫测试（U03-S02）
// 依据：《现有功能完善实施指导书 V1.0》§5 U03、问题 UP-20261018-19（P2）
// 规约：mall-simulator-notes/U03-S02.md
//
// 说明：本测试为源码结构守卫（本仓无 SFC 编译器，以读源码结构与正则断言锁定设计不回退，
// 如实注明此为源码结构守卫、不得冒充真实 DOM 渲染/点击证据）。
// 断言作用域严格限定在 ChartState 组件实现与五分析页接线标签，防止回退与漏接。

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

test('a. ChartState.vue 的 error 分支内含重试按钮形态', () => {
  const src = read('../src/components/ChartState.vue')
  const errorBranchStart = src.indexOf("state === 'error'")
  assert.ok(errorBranchStart !== -1, "ChartState.vue 必须包含 state === 'error' 分支")
  const errorBranchEnd = src.indexOf("state === 'empty'", errorBranchStart)
  assert.ok(errorBranchEnd !== -1, 'error 分支后必须紧随 empty 分支')
  const errorSnippet = src.slice(errorBranchStart, errorBranchEnd)

  assert.match(
    errorSnippet,
    /<button\s+v-if="retryable"\s+type="button"\s+class="btn btn-sm state-retry"\s+@click="emit\('retry'\)">\s*重试\s*<\/button>/,
    'error 分支内必须包含符合规范的重试按钮（v-if="retryable", @click="emit(\'retry\')", 文案「重试」）'
  )
})

test('b. ChartState.vue 声明了 retryable prop 与 defineEmits([\'retry\'])', () => {
  const src = read('../src/components/ChartState.vue')
  assert.match(
    src,
    /retryable:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/,
    'ChartState.vue 必须声明 retryable prop（Boolean 类型且 default false）'
  )
  assert.match(
    src,
    /const\s+emit\s*=\s*defineEmits\(\[\s*['"]retry['"]\s*\]\)/,
    "ChartState.vue 必须声明 const emit = defineEmits(['retry'])"
  )
})

test('c. 重试按钮仅在 error 分支内：loading 分支与 empty 分支源码片段不含 emit(\'retry\')', () => {
  const src = read('../src/components/ChartState.vue')
  const loadingStart = src.indexOf("state === 'loading'")
  const errorStart = src.indexOf("state === 'error'")
  const emptyStart = src.indexOf("state === 'empty'")
  const elseStart = src.indexOf('<template v-else>', emptyStart)

  assert.ok(loadingStart !== -1 && errorStart !== -1 && emptyStart !== -1 && elseStart !== -1)

  const loadingSnippet = src.slice(loadingStart, errorStart)
  const emptySnippet = src.slice(emptyStart, elseStart)

  assert.doesNotMatch(loadingSnippet, /emit\(['"]retry['"]\)/, "loading 分支源码不得包含 emit('retry')")
  assert.doesNotMatch(loadingSnippet, /retryable/, 'loading 分支源码不得包含 retryable')

  assert.doesNotMatch(emptySnippet, /emit\(['"]retry['"]\)/, "empty 分支源码不得包含 emit('retry')")
  assert.doesNotMatch(emptySnippet, /retryable/, 'empty 分支源码不得包含 retryable')
})

test('d. 五页接线守护：逐页统计 <ChartState 用法数与 @retry="load" + retryable 绑定数相等，合计 10 处', () => {
  const pages = [
    { name: 'Overview.vue', path: '../src/views/Overview.vue', expected: 2 },
    { name: 'Sales.vue', path: '../src/views/Sales.vue', expected: 3 },
    { name: 'Behavior.vue', path: '../src/views/Behavior.vue', expected: 2 },
    { name: 'Products.vue', path: '../src/views/Products.vue', expected: 2 },
    { name: 'Rfm.vue', path: '../src/views/Rfm.vue', expected: 1 }
  ]

  let totalUsage = 0

  for (const page of pages) {
    const src = read(page.path)
    const chartStateMatches = src.match(/<ChartState\b[\s\S]*?\/>/g) || []
    assert.equal(
      chartStateMatches.length,
      page.expected,
      `${page.name} 中的 <ChartState /> 数量必须为 ${page.expected}`
    )

    for (const tag of chartStateMatches) {
      assert.match(
        tag,
        /\bretryable\b/,
        `${page.name} 中的每个 <ChartState /> 标签必须包含 retryable 属性: ${tag}`
      )
      assert.match(
        tag,
        /@retry=["']load["']/,
        `${page.name} 中的每个 <ChartState /> 标签必须绑定 @retry="load": ${tag}`
      )
    }

    totalUsage += chartStateMatches.length
  }

  assert.equal(totalUsage, 10, '五分析页共计必须有 10 处 <ChartState /> 接线')
})

test('e. 五页既有 load 处理器仍在（Products 另断言其 load 为 analysis.load(requestParams())）', () => {
  const pages = [
    { name: 'Overview.vue', path: '../src/views/Overview.vue' },
    { name: 'Sales.vue', path: '../src/views/Sales.vue' },
    { name: 'Behavior.vue', path: '../src/views/Behavior.vue' },
    { name: 'Products.vue', path: '../src/views/Products.vue' },
    { name: 'Rfm.vue', path: '../src/views/Rfm.vue' }
  ]

  for (const page of pages) {
    const src = read(page.path)
    assert.match(
      src,
      /const\s+load\s*=/,
      `${page.name} 必须保留既有的 const load = 处理器`
    )
  }

  const productsSrc = read('../src/views/Products.vue')
  assert.match(
    productsSrc,
    /const\s+load\s*=\s*\(\)\s*=>\s*analysis\.load\(requestParams\(\)\)/,
    'Products.vue 既有 load 必须为 analysis.load(requestParams()) 形态'
  )
})

test('f. ChartState.vue 既有三态文案逐字未动', () => {
  const src = read('../src/components/ChartState.vue')

  // loading 文案
  assert.match(
    src,
    /<span\s+class="spinner"><\/span>\s*数据加载中/,
    'ChartState.vue 必须保留「数据加载中」文案'
  )

  // error 文案与兜底
  assert.match(
    src,
    /数据加载失败：\{\{\s*error\s*\|\|\s*'未知原因'\s*\}\}/,
    "ChartState.vue 必须保留「数据加载失败：{{ error || '未知原因' }}」文案与兜底"
  )

  // emptyText 默认值与模板插值
  assert.match(
    src,
    /emptyText:\s*\{\s*type:\s*String,\s*default:\s*'暂无数据'\s*\}/,
    'ChartState.vue 必须保留 emptyText 默认值「暂无数据」'
  )
  assert.match(
    src,
    /\{\{\s*emptyText\s*\}\}/,
    'ChartState.vue 必须保留 {{ emptyText }} 模板插值'
  )
})
