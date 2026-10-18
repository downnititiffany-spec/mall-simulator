/**
 * filterLabelAssociationS08.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Overview.vue、Behavior.vue、Sales.vue、Products.vue 源码并断言结构模式，
 * 锁定分析看板四页筛选区可见标签与输入控件的无障碍关联（label for 与 input id/aria-label 配对）、属性防回退、全仓 id 唯一性与纯函数关联完整性仿真，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与关联逻辑纯函数仿真，并非真实浏览器 DOM 渲染或屏幕阅读器可访问性实测证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const overviewSource = readFileSync(join(root, 'src/views/Overview.vue'), 'utf8')
const behaviorSource = readFileSync(join(root, 'src/views/Behavior.vue'), 'utf8')
const salesSource = readFileSync(join(root, 'src/views/Sales.vue'), 'utf8')
const productsSource = readFileSync(join(root, 'src/views/Products.vue'), 'utf8')

test('a. Overview 关联：label 的 for="overview-trend-from" 与两输入 id/aria-label 逐字锚点在位（正则逐字断言三行新形态）', () => {
  // 1. label 行新形态逐字锚点
  assert.match(
    overviewSource,
    /<label class="row text-sm secondary" for="overview-trend-from">趋势日期范围<\/label>/,
    'Overview 需包含 <label class="row text-sm secondary" for="overview-trend-from">趋势日期范围</label>'
  )

  // 2. 起始日期 input 新形态逐字锚点
  assert.match(
    overviewSource,
    /<input id="overview-trend-from" class="input input-date" type="date" v-model="from" :disabled="loading" aria-label="趋势起始日期" @keyup\.enter="load" \/>/,
    'Overview 需包含起始日期 input 且带 id="overview-trend-from" 与 aria-label="趋势起始日期"'
  )

  // 3. 结束日期 input 新形态逐字锚点
  assert.match(
    overviewSource,
    /<input id="overview-trend-to" class="input input-date" type="date" v-model="to" :disabled="loading" aria-label="趋势结束日期" @keyup\.enter="load" \/>/,
    'Overview 需包含结束日期 input 且带 id="overview-trend-to" 与 aria-label="趋势结束日期"'
  )

  // 4. 三行筛选控件连贯区块正则断言
  assert.match(
    overviewSource,
    /<label class="row text-sm secondary" for="overview-trend-from">趋势日期范围<\/label>\s*<input id="overview-trend-from" class="input input-date" type="date" v-model="from" :disabled="loading" aria-label="趋势起始日期" @keyup\.enter="load" \/>\s*<span class="muted">至<\/span>\s*<input id="overview-trend-to" class="input input-date" type="date" v-model="to" :disabled="loading" aria-label="趋势结束日期" @keyup\.enter="load" \/>/,
    'Overview 筛选栏三行新形态必须按契约顺序连续在位'
  )
})

test('b. Behavior 关联：label 的 for="behavior-trend-from" 与两输入 id/aria-label 逐字锚点在位', () => {
  // 1. label 行新形态逐字锚点
  assert.match(
    behaviorSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="behavior-trend-from">活跃趋势日期范围：<\/label>/,
    'Behavior 需包含 <label style="font-size:13px;color:var(--gray-700)" for="behavior-trend-from">活跃趋势日期范围：</label>'
  )

  // 2. 起始日期 input 新形态逐字锚点
  assert.match(
    behaviorSource,
    /<input id="behavior-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="活跃趋势起始日期" @keyup\.enter="load" \/>/,
    'Behavior 需包含起始日期 input 且带 id="behavior-trend-from" 与 aria-label="活跃趋势起始日期"'
  )

  // 3. 结束日期 input 新形态逐字锚点
  assert.match(
    behaviorSource,
    /<input id="behavior-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="活跃趋势结束日期" @keyup\.enter="load" \/>/,
    'Behavior 需包含结束日期 input 且带 id="behavior-trend-to" 与 aria-label="活跃趋势结束日期"'
  )

  // 4. 三行筛选控件连贯区块正则断言
  assert.match(
    behaviorSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="behavior-trend-from">活跃趋势日期范围：<\/label>\s*<input id="behavior-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="活跃趋势起始日期" @keyup\.enter="load" \/>\s*<span style="color:var\(--gray-400\)">至<\/span>\s*<input id="behavior-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="活跃趋势结束日期" @keyup\.enter="load" \/>/,
    'Behavior 筛选区三行新形态必须按契约顺序连续在位'
  )
})

test('c. Sales 关联：label 的 for="sales-trend-from" 与两输入 id/aria-label 逐字锚点在位', () => {
  // 1. label 行新形态逐字锚点
  assert.match(
    salesSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="sales-trend-from">日期范围：<\/label>/,
    'Sales 需包含 <label style="font-size:13px;color:var(--gray-700)" for="sales-trend-from">日期范围：</label>'
  )

  // 2. 起始日期 input 新形态逐字锚点
  assert.match(
    salesSource,
    /<input id="sales-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="销售趋势起始日期" @keyup\.enter="load" \/>/,
    'Sales 需包含起始日期 input 且带 id="sales-trend-from" 与 aria-label="销售趋势起始日期"'
  )

  // 3. 结束日期 input 新形态逐字锚点
  assert.match(
    salesSource,
    /<input id="sales-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="销售趋势结束日期" @keyup\.enter="load" \/>/,
    'Sales 需包含结束日期 input 且带 id="sales-trend-to" 与 aria-label="销售趋势结束日期"'
  )

  // 4. 三行筛选控件连贯区块正则断言
  assert.match(
    salesSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="sales-trend-from">日期范围：<\/label>\s*<input id="sales-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="销售趋势起始日期" @keyup\.enter="load" \/>\s*<span style="color:var\(--gray-400\)">至<\/span>\s*<input id="sales-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="销售趋势结束日期" @keyup\.enter="load" \/>/,
    'Sales 筛选区三行新形态必须按契约顺序连续在位'
  )
})

test('d. Products 关联与全仓 id 唯一性：label 的 for="products-page-size" 与输入 id/aria-label 逐字锚点在位；七个新 id 在各自页面内各恰出现 1 次且无 tabindex', () => {
  // 1. Products label 行与 input 逐字锚点
  assert.match(
    productsSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="products-page-size">每页：<\/label>/,
    'Products 需包含 <label style="font-size:13px;color:var(--gray-700)" for="products-page-size">每页：</label>'
  )
  assert.match(
    productsSource,
    /<input id="products-page-size" v-model\.number="pageSize" type="number" min="1" max="100" :disabled="loading" style="width:80px;padding:4px" aria-label="每页条数" \/>/,
    'Products 需包含 input 且带 id="products-page-size" 与 aria-label="每页条数"'
  )
  assert.match(
    productsSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="products-page-size">每页：<\/label>\s*<input id="products-page-size" v-model\.number="pageSize" type="number" min="1" max="100" :disabled="loading" style="width:80px;padding:4px" aria-label="每页条数" \/>/,
    'Products 筛选区两行新形态必须连续在位'
  )

  // 2. 七个新 id 在各自宿主页面内以 id="..." 形态统计各恰好出现 1 次（for 引用不计入 id 计数）
  const pages = [
    { name: 'Overview.vue', source: overviewSource },
    { name: 'Behavior.vue', source: behaviorSource },
    { name: 'Sales.vue', source: salesSource },
    { name: 'Products.vue', source: productsSource }
  ]

  const newIdDefinitions = [
    { id: 'overview-trend-from', hostPage: 'Overview.vue', source: overviewSource },
    { id: 'overview-trend-to', hostPage: 'Overview.vue', source: overviewSource },
    { id: 'behavior-trend-from', hostPage: 'Behavior.vue', source: behaviorSource },
    { id: 'behavior-trend-to', hostPage: 'Behavior.vue', source: behaviorSource },
    { id: 'sales-trend-from', hostPage: 'Sales.vue', source: salesSource },
    { id: 'sales-trend-to', hostPage: 'Sales.vue', source: salesSource },
    { id: 'products-page-size', hostPage: 'Products.vue', source: productsSource }
  ]

  for (const item of newIdDefinitions) {
    const hostIdRegex = new RegExp(`id="${item.id}"`, 'g')
    const hostMatches = item.source.match(hostIdRegex) || []
    assert.equal(
      hostMatches.length,
      1,
      `${item.hostPage} 内 id="${item.id}" 出现次数必须恰为 1 次（实测 ${hostMatches.length} 次，for 引用不计入 id 口径）`
    )

    // 跨页唯一性守护：非宿主页面不得包含该 id
    for (const p of pages) {
      if (p.name !== item.hostPage) {
        const otherMatches = p.source.match(hostIdRegex) || []
        assert.equal(
          otherMatches.length,
          0,
          `${p.name} 内不得出现冲突的 id="${item.id}"（跨页唯一）`
        )
      }
    }
  }

  // 3. 四页源码均不含 tabindex（符合 UP-49～52 规范口径）
  for (const p of pages) {
    assert.doesNotMatch(
      p.source,
      /tabindex/i,
      `${p.name} 源码不得包含 tabindex 属性（沿用 UP-49～52 复核符合口径）`
    )
  }
})

test('e. 行为不回退与关联完整性仿真：四页的 v-model 绑定各在位；以纯函数复刻「for→id 配对」与无障碍命名判定', () => {
  // 1. 行为不回退：v-model 与 v-model.number 绑定逐字在位
  assert.match(overviewSource, /v-model="from"/, 'Overview 需保留 v-model="from"')
  assert.match(overviewSource, /v-model="to"/, 'Overview 需保留 v-model="to"')
  assert.match(behaviorSource, /v-model="from"/, 'Behavior 需保留 v-model="from"')
  assert.match(behaviorSource, /v-model="to"/, 'Behavior 需保留 v-model="to"')
  assert.match(salesSource, /v-model="from"/, 'Sales 需保留 v-model="from"')
  assert.match(salesSource, /v-model="to"/, 'Sales 需保留 v-model="to"')
  assert.match(productsSource, /v-model\.number="pageSize"/, 'Products 需保留 v-model.number="pageSize"')

  // 2. 纯函数：复刻「for→id 配对与无障碍命名完整性」判定逻辑
  const simulateAssociationCheck = (sourceText, expectedNewIds = []) => {
    const labelForMatches = [...sourceText.matchAll(/<label[^>]*\bfor="([^"]+)"[^>]*>/g)]
    const labelFors = labelForMatches.map((m) => m[1])

    const inputMatches = [...sourceText.matchAll(/<input\b([^>]*)>/g)]
    const inputs = inputMatches.map((m) => {
      const attrString = m[1]
      const idMatch = attrString.match(/\bid="([^"]+)"/)
      const ariaLabelMatch = attrString.match(/\baria-label="([^"]+)"/)
      return {
        id: idMatch ? idMatch[1] : null,
        ariaLabel: ariaLabelMatch ? ariaLabelMatch[1] : null,
        rawAttrs: attrString
      }
    })

    const inputIdSet = new Set(inputs.map((inp) => inp.id).filter(Boolean))
    const inputsById = new Map(inputs.filter((inp) => inp.id).map((inp) => [inp.id, inp]))

    // 断言每个 label 的 for 都在同页有 input id 命中
    const unmatchedFors = labelFors.filter((forVal) => !inputIdSet.has(forVal))

    // 断言每个预期的 new id 都在同页存在且具有非空 aria-label
    const missingIds = []
    const missingAriaLabels = []
    for (const expectedId of expectedNewIds) {
      const inputObj = inputsById.get(expectedId)
      if (!inputObj) {
        missingIds.push(expectedId)
      } else if (!inputObj.ariaLabel || inputObj.ariaLabel.trim() === '') {
        missingAriaLabels.push(expectedId)
      }
    }

    return {
      labelFors,
      inputIds: [...inputIdSet],
      unmatchedFors,
      missingIds,
      missingAriaLabels,
      passed: unmatchedFors.length === 0 && missingIds.length === 0 && missingAriaLabels.length === 0
    }
  }

  // 3. 对四页源码执行纯函数判定
  const overviewCheck = simulateAssociationCheck(overviewSource, ['overview-trend-from', 'overview-trend-to'])
  assert.equal(overviewCheck.passed, true, 'Overview 仿真判定必须通过')
  assert.deepEqual(overviewCheck.unmatchedFors, [], 'Overview 所有 for 均需在同页命中 input id')
  assert.deepEqual(overviewCheck.missingIds, [], 'Overview 预期的两个新 id 必须均存在')
  assert.deepEqual(overviewCheck.missingAriaLabels, [], 'Overview 预期的两个新 id 必须均有非空 aria-label')
  assert.ok(overviewCheck.labelFors.includes('overview-trend-from'), 'Overview 的 label for 需包含 overview-trend-from')

  const behaviorCheck = simulateAssociationCheck(behaviorSource, ['behavior-trend-from', 'behavior-trend-to'])
  assert.equal(behaviorCheck.passed, true, 'Behavior 仿真判定必须通过')
  assert.deepEqual(behaviorCheck.unmatchedFors, [], 'Behavior 所有 for 均需在同页命中 input id')
  assert.deepEqual(behaviorCheck.missingIds, [], 'Behavior 预期的两个新 id 必须均存在')
  assert.deepEqual(behaviorCheck.missingAriaLabels, [], 'Behavior 预期的两个新 id 必须均有非空 aria-label')
  assert.ok(behaviorCheck.labelFors.includes('behavior-trend-from'), 'Behavior 的 label for 需包含 behavior-trend-from')

  const salesCheck = simulateAssociationCheck(salesSource, ['sales-trend-from', 'sales-trend-to'])
  assert.equal(salesCheck.passed, true, 'Sales 仿真判定必须通过')
  assert.deepEqual(salesCheck.unmatchedFors, [], 'Sales 所有 for 均需在同页命中 input id')
  assert.deepEqual(salesCheck.missingIds, [], 'Sales 预期的两个新 id 必须均存在')
  assert.deepEqual(salesCheck.missingAriaLabels, [], 'Sales 预期的两个新 id 必须均有非空 aria-label')
  assert.ok(salesCheck.labelFors.includes('sales-trend-from'), 'Sales 的 label for 需包含 sales-trend-from')

  const productsCheck = simulateAssociationCheck(productsSource, ['products-page-size'])
  assert.equal(productsCheck.passed, true, 'Products 仿真判定必须通过')
  assert.deepEqual(productsCheck.unmatchedFors, [], 'Products 所有 for 均需在同页命中 input id')
  assert.deepEqual(productsCheck.missingIds, [], 'Products 预期的新 id 必须存在')
  assert.deepEqual(productsCheck.missingAriaLabels, [], 'Products 预期的新 id 必须具有非空 aria-label')
  assert.ok(productsCheck.labelFors.includes('products-page-size'), 'Products 的 label for 需包含 products-page-size')

  // 4. 纯函数判据真值逻辑反向仿真：异常形态必须被检出
  const badSourceMissingTarget = '<label for="ghost-id">孤立标签</label><input id="other-id" aria-label="提示" />'
  const badCheck1 = simulateAssociationCheck(badSourceMissingTarget, ['other-id'])
  assert.equal(badCheck1.passed, false, 'for 无对应 id 时仿真必须检出失败')
  assert.deepEqual(badCheck1.unmatchedFors, ['ghost-id'])

  const badSourceMissingAria = '<label for="test-id">测试</label><input id="test-id" />'
  const badCheck2 = simulateAssociationCheck(badSourceMissingAria, ['test-id'])
  assert.equal(badCheck2.passed, false, '缺失 aria-label 时仿真必须检出失败')
  assert.deepEqual(badCheck2.missingAriaLabels, ['test-id'])
})
