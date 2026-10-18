/**
 * filterEnterSubmitS12.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Overview.vue、Behavior.vue、Sales.vue、AiAssistant.vue、Products.vue、Rfm.vue 源码并断言结构模式，
 * 锁定分析看板三页（Overview、Behavior、Sales）日期筛选输入框的 Enter 键提交绑定（@keyup.enter="load"）、
 * 「加载」按钮绑定防回退、全仓 keyup.enter 范围与计数规范、不引入 <form> 标签守护以及 Enter 触发判定纯函数仿真。
 * 特此注明：本测试属于静态源码结构守卫与键盘事件触发判定纯函数仿真，并非真实浏览器 DOM 渲染或真实浏览器键盘事件证据。
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
const aiAssistantSource = readFileSync(join(root, 'src/views/AiAssistant.vue'), 'utf8')
const productsSource = readFileSync(join(root, 'src/views/Products.vue'), 'utf8')
const rfmSource = readFileSync(join(root, 'src/views/Rfm.vue'), 'utf8')

test('a. Overview 锚点：两输入新形态逐字在位（正则逐字断言含 @keyup.enter="load"）+「加载」按钮 @click="load" 行逐字保留', () => {
  // 1. 起始日期 input 新形态逐字锚点（含 @keyup.enter="load"）
  assert.match(
    overviewSource,
    /<input id="overview-trend-from" class="input input-date" type="date" v-model="from" :disabled="loading" aria-label="趋势起始日期" @keyup\.enter="load" \/>/,
    'Overview 需包含起始日期 input 且带 @keyup.enter="load"'
  )

  // 2. 结束日期 input 新形态逐字锚点（含 @keyup.enter="load"）
  assert.match(
    overviewSource,
    /<input id="overview-trend-to" class="input input-date" type="date" v-model="to" :disabled="loading" aria-label="趋势结束日期" @keyup\.enter="load" \/>/,
    'Overview 需包含结束日期 input 且带 @keyup.enter="load"'
  )

  // 3. 「加载」按钮 @click="load" 行逐字保留
  assert.match(
    overviewSource,
    /<button class="btn btn-sm btn-primary" :disabled="loading" @click="load">\{\{ loading \? '加载中' : '加载' \}\}<\/button>/,
    'Overview 需保留「加载」按钮 @click="load" 行'
  )

  // 4. 筛选栏连贯结构断言（两输入与「加载」按钮顺序在位）
  assert.match(
    overviewSource,
    /<label class="row text-sm secondary" for="overview-trend-from">趋势日期范围<\/label>\s*<input id="overview-trend-from" class="input input-date" type="date" v-model="from" :disabled="loading" aria-label="趋势起始日期" @keyup\.enter="load" \/>\s*<span class="muted">至<\/span>\s*<input id="overview-trend-to" class="input input-date" type="date" v-model="to" :disabled="loading" aria-label="趋势结束日期" @keyup\.enter="load" \/>\s*<button class="btn btn-sm btn-primary" :disabled="loading" @click="load">/,
    'Overview 筛选栏两输入与「加载」按钮必须按契约顺序连续在位'
  )
})

test('b. Behavior 锚点：同上口径（两输入 + 加载按钮行）', () => {
  // 1. 活跃趋势起始日期 input 新形态逐字锚点
  assert.match(
    behaviorSource,
    /<input id="behavior-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="活跃趋势起始日期" @keyup\.enter="load" \/>/,
    'Behavior 需包含活跃趋势起始日期 input 且带 @keyup.enter="load"'
  )

  // 2. 活跃趋势结束日期 input 新形态逐字锚点
  assert.match(
    behaviorSource,
    /<input id="behavior-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="活跃趋势结束日期" @keyup\.enter="load" \/>/,
    'Behavior 需包含活跃趋势结束日期 input 且带 @keyup.enter="load"'
  )

  // 3. 「加载」按钮 @click="load" 行逐字保留
  assert.match(
    behaviorSource,
    /<button class="btn btn-sm" :disabled="loading" @click="load">\{\{ loading \? '加载中' : '加载' \}\}<\/button>/,
    'Behavior 需保留「加载」按钮 @click="load" 行'
  )

  // 4. 筛选区连贯结构断言
  assert.match(
    behaviorSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="behavior-trend-from">活跃趋势日期范围：<\/label>\s*<input id="behavior-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="活跃趋势起始日期" @keyup\.enter="load" \/>\s*<span style="color:var\(--gray-400\)">至<\/span>\s*<input id="behavior-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="活跃趋势结束日期" @keyup\.enter="load" \/>\s*<button class="btn btn-sm" :disabled="loading" @click="load">/,
    'Behavior 筛选区两输入与「加载」按钮必须按契约顺序连续在位'
  )
})

test('c. Sales 锚点：同上口径（两输入 + 加载按钮行，并含 rangeError 横幅行逐字保留抽锚）', () => {
  // 1. 销售趋势起始日期 input 新形态逐字锚点
  assert.match(
    salesSource,
    /<input id="sales-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="销售趋势起始日期" @keyup\.enter="load" \/>/,
    'Sales 需包含销售趋势起始日期 input 且带 @keyup.enter="load"'
  )

  // 2. 销售趋势结束日期 input 新形态逐字锚点
  assert.match(
    salesSource,
    /<input id="sales-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="销售趋势结束日期" @keyup\.enter="load" \/>/,
    'Sales 需包含销售趋势结束日期 input 且带 @keyup.enter="load"'
  )

  // 3. 「加载」按钮 @click="load" 行逐字保留
  assert.match(
    salesSource,
    /<button class="btn btn-sm" :disabled="loading" @click="load">\{\{ loading \? '加载中' : '加载' \}\}<\/button>/,
    'Sales 需保留「加载」按钮 @click="load" 行'
  )

  // 4. rangeError 横幅行逐字保留抽锚
  assert.match(
    salesSource,
    /<div v-if="rangeError" class="banner banner-error" style="margin-bottom:10px">\{\{ rangeError \}\}<\/div>/,
    'Sales 需保留 rangeError 错误横幅行'
  )

  // 5. 筛选区连贯结构断言
  assert.match(
    salesSource,
    /<label style="font-size:13px;color:var\(--gray-700\)" for="sales-trend-from">日期范围：<\/label>\s*<input id="sales-trend-from" type="date" v-model="from" :disabled="loading" style="padding:4px" aria-label="销售趋势起始日期" @keyup\.enter="load" \/>\s*<span style="color:var\(--gray-400\)">至<\/span>\s*<input id="sales-trend-to" type="date" v-model="to" :disabled="loading" style="padding:4px" aria-label="销售趋势结束日期" @keyup\.enter="load" \/>\s*<button class="btn btn-sm" :disabled="loading" @click="load">/,
    'Sales 筛选区两输入与「加载」按钮必须按契约顺序连续在位'
  )
})

test('d. 范围守护与计数：三页各 @keyup.enter 恰 2 处；AiAssistant @keyup.enter="ask" 恰 1 处逐字保留；Products.vue、Rfm.vue 源码不含 keyup.enter；三页源码不含 <form；三页 const load = () => 定义逐字保留', () => {
  // 1. 三页各 @keyup.enter 恰 2 处
  const overviewEnterMatches = overviewSource.match(/@keyup\.enter/g) || []
  assert.equal(
    overviewEnterMatches.length,
    2,
    `Overview.vue 内 @keyup.enter 出现次数必须恰为 2 次（实测 ${overviewEnterMatches.length} 次）`
  )

  const behaviorEnterMatches = behaviorSource.match(/@keyup\.enter/g) || []
  assert.equal(
    behaviorEnterMatches.length,
    2,
    `Behavior.vue 内 @keyup.enter 出现次数必须恰为 2 次（实测 ${behaviorEnterMatches.length} 次）`
  )

  const salesEnterMatches = salesSource.match(/@keyup\.enter/g) || []
  assert.equal(
    salesEnterMatches.length,
    2,
    `Sales.vue 内 @keyup.enter 出现次数必须恰为 2 次（实测 ${salesEnterMatches.length} 次）`
  )

  // 2. AiAssistant @keyup.enter="ask" 恰 1 处逐字保留
  const aiAssistantEnterMatches = aiAssistantSource.match(/@keyup\.enter/g) || []
  assert.equal(
    aiAssistantEnterMatches.length,
    1,
    `AiAssistant.vue 内 @keyup.enter 出现次数必须恰为 1 次（实测 ${aiAssistantEnterMatches.length} 次）`
  )
  assert.match(
    aiAssistantSource,
    /@keyup\.enter="ask"/,
    'AiAssistant.vue 必须保留 @keyup.enter="ask" 绑定'
  )

  // 3. Products.vue 与 Rfm.vue 源码不含 keyup.enter
  assert.doesNotMatch(
    productsSource,
    /keyup\.enter/,
    'Products.vue 源码不得包含 keyup.enter（每页条数按 applyFilters 语义由回车提交裁决排除）'
  )
  assert.doesNotMatch(
    rfmSource,
    /keyup\.enter/,
    'Rfm.vue 源码不得包含 keyup.enter（无筛选输入）'
  )

  // 4. 三页源码不含 <form（禁止引入 <form> 包裹）
  assert.doesNotMatch(overviewSource, /<form\b/i, 'Overview.vue 源码不得引入 <form> 标签')
  assert.doesNotMatch(behaviorSource, /<form\b/i, 'Behavior.vue 源码不得引入 <form> 标签')
  assert.doesNotMatch(salesSource, /<form\b/i, 'Sales.vue 源码不得引入 <form> 标签')

  // 5. 三页 const load = () => 定义逐字保留
  assert.match(overviewSource, /const load = \(\) =>/, 'Overview.vue 必须保留 const load = () => 定义')
  assert.match(behaviorSource, /const load = \(\) =>/, 'Behavior.vue 必须保留 const load = () => 定义')
  assert.match(salesSource, /const load = \(\) =>/, 'Sales.vue 必须保留 const load = () => 定义')
})

test('e. Enter 触发判定仿真：以纯函数复刻「日期输入 Enter → load」判定（按 id 定位六输入，断言各带 @keyup.enter="load" 且 handler 名与本页按钮 @click handler 名一致；并以反向仿真断言缺失监听、handler 名不符时会被检出）', () => {
  // 1. 纯函数：复刻「日期输入 Enter → load 与按钮 @click handler 一致性」判定
  const simulateEnterSubmitCheck = (sourceText, targetInputIds, buttonPattern = /<button[^>]*@click="([^"]+)"[^>]*>/) => {
    const btnMatch = sourceText.match(buttonPattern)
    const buttonHandler = btnMatch ? btnMatch[1] : null

    const inputMatches = [...sourceText.matchAll(/<input\b([^>]*)>/g)]
    const inputsById = new Map()
    for (const m of inputMatches) {
      const rawAttrs = m[1]
      const idMatch = rawAttrs.match(/\bid="([^"]+)"/)
      if (idMatch) {
        const enterMatch = rawAttrs.match(/@keyup\.enter="([^"]+)"/)
        inputsById.set(idMatch[1], {
          id: idMatch[1],
          enterHandler: enterMatch ? enterMatch[1] : null,
          rawAttrs
        })
      }
    }

    const missingInputs = []
    const missingEnterListeners = []
    const mismatchedHandlers = []
    const matched = []

    for (const id of targetInputIds) {
      const input = inputsById.get(id)
      if (!input) {
        missingInputs.push(id)
        continue
      }
      if (!input.enterHandler) {
        missingEnterListeners.push(id)
        continue
      }
      if (!buttonHandler || input.enterHandler !== buttonHandler) {
        mismatchedHandlers.push({
          id,
          enterHandler: input.enterHandler,
          buttonHandler
        })
        continue
      }
      matched.push({ id, enterHandler: input.enterHandler, buttonHandler })
    }

    return {
      buttonHandler,
      checkedIds: targetInputIds,
      matched,
      missingInputs,
      missingEnterListeners,
      mismatchedHandlers,
      passed: missingInputs.length === 0 && missingEnterListeners.length === 0 && mismatchedHandlers.length === 0
    }
  }

  // 2. 对 Overview.vue 仿真判定：两输入各带 @keyup.enter="load" 且与按钮 @click="load" 一致
  const overviewCheck = simulateEnterSubmitCheck(overviewSource, ['overview-trend-from', 'overview-trend-to'])
  assert.equal(overviewCheck.passed, true, 'Overview 两个日期输入 Enter 提交仿真判定必须通过')
  assert.equal(overviewCheck.buttonHandler, 'load', 'Overview 按钮 handler 必须为 load')
  assert.deepEqual(overviewCheck.missingInputs, [], 'Overview 不得缺失预期的输入框')
  assert.deepEqual(overviewCheck.missingEnterListeners, [], 'Overview 两输入框均需挂载 @keyup.enter')
  assert.deepEqual(overviewCheck.mismatchedHandlers, [], 'Overview 两输入框 enter handler 必须与按钮 load 一致')

  // 3. 对 Behavior.vue 仿真判定：两输入各带 @keyup.enter="load" 且与按钮 @click="load" 一致
  const behaviorCheck = simulateEnterSubmitCheck(behaviorSource, ['behavior-trend-from', 'behavior-trend-to'])
  assert.equal(behaviorCheck.passed, true, 'Behavior 两个日期输入 Enter 提交仿真判定必须通过')
  assert.equal(behaviorCheck.buttonHandler, 'load', 'Behavior 按钮 handler 必须为 load')
  assert.deepEqual(behaviorCheck.missingInputs, [], 'Behavior 不得缺失预期的输入框')
  assert.deepEqual(behaviorCheck.missingEnterListeners, [], 'Behavior 两输入框均需挂载 @keyup.enter')
  assert.deepEqual(behaviorCheck.mismatchedHandlers, [], 'Behavior 两输入框 enter handler 必须与按钮 load 一致')

  // 4. 对 Sales.vue 仿真判定：两输入各带 @keyup.enter="load" 且与按钮 @click="load" 一致
  const salesCheck = simulateEnterSubmitCheck(salesSource, ['sales-trend-from', 'sales-trend-to'])
  assert.equal(salesCheck.passed, true, 'Sales 两个日期输入 Enter 提交仿真判定必须通过')
  assert.equal(salesCheck.buttonHandler, 'load', 'Sales 按钮 handler 必须为 load')
  assert.deepEqual(salesCheck.missingInputs, [], 'Sales 不得缺失预期的输入框')
  assert.deepEqual(salesCheck.missingEnterListeners, [], 'Sales 两输入框均需挂载 @keyup.enter')
  assert.deepEqual(salesCheck.mismatchedHandlers, [], 'Sales 两输入框 enter handler 必须与按钮 load 一致')

  // 5. 纯函数判据真值逻辑反向仿真：异常形态必须被检出
  // 5.1 缺失 @keyup.enter 监听
  const badSourceMissingEnter = '<input id="mock-from" type="date" /><button @click="load">加载</button>'
  const badCheck1 = simulateEnterSubmitCheck(badSourceMissingEnter, ['mock-from'])
  assert.equal(badCheck1.passed, false, '缺失 @keyup.enter 监听时仿真判定必须失败')
  assert.deepEqual(badCheck1.missingEnterListeners, ['mock-from'])

  // 5.2 handler 名与按钮不一致
  const badSourceMismatchedHandler = '<input id="mock-from" type="date" @keyup.enter="otherAction" /><button @click="load">加载</button>'
  const badCheck2 = simulateEnterSubmitCheck(badSourceMismatchedHandler, ['mock-from'])
  assert.equal(badCheck2.passed, false, 'handler 名与按钮 @click 不符时仿真判定必须失败')
  assert.equal(badCheck2.mismatchedHandlers.length, 1)
  assert.equal(badCheck2.mismatchedHandlers[0].id, 'mock-from')
  assert.equal(badCheck2.mismatchedHandlers[0].enterHandler, 'otherAction')
  assert.equal(badCheck2.mismatchedHandlers[0].buttonHandler, 'load')

  // 5.3 目标输入控件不存在
  const badSourceMissingInput = '<button @click="load">加载</button>'
  const badCheck3 = simulateEnterSubmitCheck(badSourceMissingInput, ['mock-missing'])
  assert.equal(badCheck3.passed, false, '目标输入框不存在时仿真判定必须失败')
  assert.deepEqual(badCheck3.missingInputs, ['mock-missing'])
})
