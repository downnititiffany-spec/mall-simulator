/**
 * tableSortKeyboard.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Products.vue、Sales.vue 与 theme.css 源码并断言结构模式，
 * 锁定 Products 与 Sales 排序表头内嵌原生 button（.sort-btn）结构、不可排序列纯文本分支、样式收敛规则与键盘语义逻辑，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支/排序语义纯函数仿真，并非真实浏览器 DOM 渲染或真实键盘 Tab/Enter/Space 交互走查证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const productsSource = readFileSync(join(root, 'src/views/Products.vue'), 'utf8')
const salesSource = readFileSync(join(root, 'src/views/Sales.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')

test('a. Products 结构：th 标签自身不带 @click 与 cursor :style；存在 button 排序入口与不可排序 template 分支', () => {
  // 提取 thead 内 tr 片段
  const theadMatch = productsSource.match(/<thead>([\s\S]*?)<\/thead>/)
  assert.ok(theadMatch, 'Products.vue 需包含 <thead> 结构')
  const theadSnippet = theadMatch[1]

  // th 标签自身不带 @click
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*@click/,
    'Products thead 的 th 标签自身不得带有 @click 属性（点击入口已下沉至 button）'
  )

  // th 标签自身不带 cursor :style
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*cursor/,
    'Products thead 的 th 标签自身不得带有 cursor 相关 style 绑定'
  )
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*:style/,
    'Products thead 的 th 标签自身不得带有 :style 属性'
  )

  // th 标签自身为 <th v-for="col in columns" :key="col.key">
  assert.match(
    theadSnippet,
    /<th\s+v-for="col in columns"\s+:key="col\.key">/,
    'Products thead 需包含 <th v-for="col in columns" :key="col.key">'
  )

  // 存在可排序按钮分支：<button v-if="col.sortKey" type="button" class="sort-btn" @click="toggleSort(col)">
  assert.match(
    theadSnippet,
    /<button\s+v-if="col\.sortKey"\s+type="button"\s+class="sort-btn"\s+@click="toggleSort\(col\)">/,
    'Products thead 需包含 <button v-if="col.sortKey" type="button" class="sort-btn" @click="toggleSort(col)">'
  )

  // 不可排序分支：<template v-else>{{ col.title }}</template> 在位
  assert.match(
    theadSnippet,
    /<template\s+v-else>{{ col\.title }}<\/template>/,
    'Products thead 需包含不可排序分支 <template v-else>{{ col.title }}</template>'
  )
})

test('b. Products 排序面不回退：columns 定义中带 sortKey 的键恰为 6 个、2 列不带；箭头 span 条件锁定', () => {
  // 截取 columns 定义片段
  const colStart = productsSource.indexOf('const columns = [')
  assert.ok(colStart >= 0, 'Products.vue 需包含 const columns = [')
  const colEnd = productsSource.indexOf(']', colStart)
  assert.ok(colEnd >= 0, 'Products.vue 需包含 columns 数组闭合 ]')
  const columnsSnippet = productsSource.slice(colStart, colEnd + 1)

  // columns 定义中带 sortKey 的键恰为 rank/pv/fav/cart/buy/heat 六个
  const expectedSortableKeys = ['rank', 'pv', 'fav', 'cart', 'buy', 'heat']
  for (const k of expectedSortableKeys) {
    const keyPattern = new RegExp(`key:\\s*'${k}'[\\s\\S]*?sortKey:\\s*'${k}'`)
    assert.match(
      columnsSnippet,
      keyPattern,
      `columns 中列 '${k}' 必须定义对应的 sortKey: '${k}'`
    )
  }

  // 统计 columns 定义中 sortKey 出现次数，断言恰好 6 处
  const sortKeyMatches = columnsSnippet.match(/sortKey:\s*'\w+'/g) || []
  assert.equal(
    sortKeyMatches.length,
    6,
    `columns 中带 sortKey 的列恰好为 6 个（实测 ${sortKeyMatches.length} 个）`
  )

  // productName 与 conversionRate 绝对不带 sortKey（服务端白名单边界）
  assert.match(
    columnsSnippet,
    /{\s*key:\s*'productName',\s*title:\s*'商品'\s*}/,
    'productName 列定义不得带有 sortKey'
  )
  assert.match(
    columnsSnippet,
    /{\s*key:\s*'conversionRate',\s*title:\s*'转化率'\s*}/,
    'conversionRate 列定义不得带有 sortKey'
  )

  // 箭头 span 条件为 sortKey === col.sortKey
  assert.match(
    productsSource,
    /<span\s+v-if="sortKey === col\.sortKey">\{\{\s*sortOrder === 'asc'\s*\?\s*' ↑'\s*:\s*' ↓'\s*\}\}<\/span>/,
    'Products 箭头 span 条件需严格为 sortKey === col.sortKey'
  )
})

test('c. Sales 结构：th 标签自身不带 @click 与 cursor 样式；存在原生 button 与箭头判定', () => {
  // 提取 thead 内 tr 片段
  const theadMatch = salesSource.match(/<thead>([\s\S]*?)<\/thead>/)
  assert.ok(theadMatch, 'Sales.vue 需包含 <thead> 结构')
  const theadSnippet = theadMatch[1]

  // th 标签自身不带 @click
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*@click/,
    'Sales thead 的 th 标签自身不得带有 @click 属性（点击入口已下沉至 button）'
  )

  // th 标签自身不带 cursor style
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*cursor/,
    'Sales thead 的 th 标签自身不得带有 cursor 样式'
  )
  assert.doesNotMatch(
    theadSnippet,
    /<th[^>]*style/,
    'Sales thead 的 th 标签自身不得带有 style 属性'
  )

  // th 标签自身为 <th v-for="col in columns" :key="col.key">
  assert.match(
    theadSnippet,
    /<th\s+v-for="col in columns"\s+:key="col\.key">/,
    'Sales thead 需包含 <th v-for="col in columns" :key="col.key">'
  )

  // 存在可排序按钮：<button type="button" class="sort-btn" @click="toggleSort(col.key)">
  assert.match(
    theadSnippet,
    /<button\s+type="button"\s+class="sort-btn"\s+@click="toggleSort\(col\.key\)">/,
    'Sales thead 需包含 <button type="button" class="sort-btn" @click="toggleSort(col.key)">'
  )

  // 箭头 span 条件为 sortKey === col.key
  assert.match(
    theadSnippet,
    /<span\s+v-if="sortKey === col\.key">\{\{\s*sortOrder === 'asc'\s*\?\s*' ↑'\s*:\s*' ↓'\s*\}\}<\/span>/,
    'Sales 箭头 span 条件需严格为 sortKey === col.key'
  )
})

test('d. 机制锚点：theme.css 含裁决 2 的 .sort-btn 规则逐字；两页源码均不含 tabindex', () => {
  // 裁决 2 的逐字样式规则
  const expectedSortBtnRule = '.data-table th .sort-btn { background: none; border: none; padding: 0; margin: 0; font: inherit; color: inherit; cursor: pointer; }'

  assert.ok(
    themeCssSource.includes(expectedSortBtnRule),
    'theme.css 必须逐字包含裁决 2 的 .sort-btn 规则'
  )

  // 断言该规则紧邻 .data-table th .sort-caret 规则之后
  assert.match(
    themeCssSource,
    /\.data-table th \.sort-caret\s*\{[^}]*\}\s*\.data-table th \.sort-btn\s*\{\s*background:\s*none;\s*border:\s*none;\s*padding:\s*0;\s*margin:\s*0;\s*font:\s*inherit;\s*color:\s*inherit;\s*cursor:\s*pointer;\s*\}/,
    '.sort-btn 规则需紧随 .sort-caret 规则之后定义'
  )

  // Products.vue 与 Sales.vue 均不含 tabindex（原生 button 自带键盘焦点，不走自维护语义路线）
  assert.doesNotMatch(
    productsSource,
    /tabindex/i,
    'Products.vue 不得包含 tabindex 属性'
  )
  assert.doesNotMatch(
    salesSource,
    /tabindex/i,
    'Sales.vue 不得包含 tabindex 属性'
  )
})

test('e. 键盘语义仿真：纯函数复刻 button 触发 click → toggleSort 切换判定', () => {
  // --- Products toggleSort(col) 纯函数仿真 ---
  const simulateProductsToggleSort = (state, col) => {
    if (!col || !col.sortKey || state.loading) {
      return { ...state, loadCalled: false }
    }
    const next = { ...state }
    if (next.sortKey === col.sortKey) {
      next.sortOrder = next.sortOrder === 'asc' ? 'desc' : 'asc'
    } else {
      next.sortKey = col.sortKey
      next.sortOrder = col.defaultOrder || 'desc'
    }
    next.page = 1
    next.loadCalled = true
    return next
  }

  // 情形 1：Products 键切换（以 defaultOrder 生效，重置 page 为 1 并触发 load）
  const pState0 = { sortKey: 'rank', sortOrder: 'asc', page: 3, loading: false }
  const pSwitched = simulateProductsToggleSort(pState0, { key: 'pv', sortKey: 'pv', defaultOrder: 'desc' })
  assert.equal(pSwitched.sortKey, 'pv', 'Products 键切换后 sortKey 切换至目标列 sortKey')
  assert.equal(pSwitched.sortOrder, 'desc', 'Products 键切换采用目标列 defaultOrder ("desc")')
  assert.equal(pSwitched.page, 1, 'Products 键切换重置 page 为 1')
  assert.equal(pSwitched.loadCalled, true, 'Products 键切换触发 load()')

  // 再次切换回 rank (defaultOrder: 'asc')
  const pSwitchedRank = simulateProductsToggleSort(pSwitched, { key: 'rank', sortKey: 'rank', defaultOrder: 'asc' })
  assert.equal(pSwitchedRank.sortKey, 'rank')
  assert.equal(pSwitchedRank.sortOrder, 'asc')
  assert.equal(pSwitchedRank.page, 1)
  assert.equal(pSwitchedRank.loadCalled, true)

  // 情形 2：Products 同键翻序（asc <-> desc 互切，重置 page 为 1 并触发 load）
  const pFlippedDesc = simulateProductsToggleSort(
    { sortKey: 'rank', sortOrder: 'asc', page: 2, loading: false },
    { key: 'rank', sortKey: 'rank', defaultOrder: 'asc' }
  )
  assert.equal(pFlippedDesc.sortKey, 'rank')
  assert.equal(pFlippedDesc.sortOrder, 'desc', '同键翻序：asc 翻转为 desc')
  assert.equal(pFlippedDesc.page, 1)
  assert.equal(pFlippedDesc.loadCalled, true)

  const pFlippedAsc = simulateProductsToggleSort(
    pFlippedDesc,
    { key: 'rank', sortKey: 'rank', defaultOrder: 'asc' }
  )
  assert.equal(pFlippedAsc.sortKey, 'rank')
  assert.equal(pFlippedAsc.sortOrder, 'asc', '同键翻序：desc 翻转为 asc')
  assert.equal(pFlippedAsc.page, 1)
  assert.equal(pFlippedAsc.loadCalled, true)

  // 情形 3：Products 无 sortKey 拒绝（无 sortKey 列点击不改变排序状态且不触发 load）
  const pUnchangedCol = simulateProductsToggleSort(
    pState0,
    { key: 'productName', title: '商品' }
  )
  assert.deepEqual(
    { sortKey: pUnchangedCol.sortKey, sortOrder: pUnchangedCol.sortOrder, page: pUnchangedCol.page },
    { sortKey: pState0.sortKey, sortOrder: pState0.sortOrder, page: pState0.page },
    '不可排序列 productName 点击后排序状态保持不变'
  )
  assert.equal(pUnchangedCol.loadCalled, false, '不可排序列 productName 点击不得触发 load()')

  const pUnchangedConv = simulateProductsToggleSort(
    pState0,
    { key: 'conversionRate', title: '转化率' }
  )
  assert.equal(pUnchangedConv.loadCalled, false, '不可排序列 conversionRate 点击不得触发 load()')

  // loading 防抖测试
  const pLoadingBlocked = simulateProductsToggleSort(
    { sortKey: 'rank', sortOrder: 'asc', page: 1, loading: true },
    { key: 'pv', sortKey: 'pv', defaultOrder: 'desc' }
  )
  assert.equal(pLoadingBlocked.loadCalled, false, 'Products loading 期间拦截排序切换')


  // --- Sales toggleSort(key) 纯函数仿真 ---
  const simulateSalesToggleSort = (state, key) => {
    if (state.loading) {
      return { ...state, changed: false }
    }
    const next = { ...state }
    if (next.sortKey === key) {
      next.sortOrder = next.sortOrder === 'asc' ? 'desc' : 'asc'
    } else {
      next.sortKey = key
      next.sortOrder = 'asc'
    }
    next.page = 1
    next.changed = true
    return next
  }

  // 情形 1：Sales 键切换（切新键时默认 asc，page 重置为 1）
  const sState0 = { sortKey: 'date', sortOrder: 'asc', page: 4, loading: false }
  const sSwitched = simulateSalesToggleSort(sState0, 'orderCount')
  assert.equal(sSwitched.sortKey, 'orderCount', 'Sales 键切换后 sortKey 切换至 orderCount')
  assert.equal(sSwitched.sortOrder, 'asc', 'Sales 切新键固定初始排序为 asc')
  assert.equal(sSwitched.page, 1, 'Sales 键切换重置 page 为 1')
  assert.equal(sSwitched.changed, true)

  // 情形 2：Sales 同键翻序（asc <-> desc 互切）
  const sFlippedDesc = simulateSalesToggleSort(sState0, 'date')
  assert.equal(sFlippedDesc.sortKey, 'date')
  assert.equal(sFlippedDesc.sortOrder, 'desc', 'Sales 同键翻序：asc 翻转为 desc')
  assert.equal(sFlippedDesc.page, 1)

  const sFlippedAsc = simulateSalesToggleSort(sFlippedDesc, 'date')
  assert.equal(sFlippedAsc.sortKey, 'date')
  assert.equal(sFlippedAsc.sortOrder, 'asc', 'Sales 同键翻序：desc 翻转为 asc')
  assert.equal(sFlippedAsc.page, 1)

  // loading 拦截测试
  const sLoadingBlocked = simulateSalesToggleSort(
    { sortKey: 'date', sortOrder: 'asc', page: 1, loading: true },
    'saleAmount'
  )
  assert.equal(sLoadingBlocked.sortKey, 'date', 'Sales loading 期间保持当前排序键')
  assert.equal(sLoadingBlocked.changed, false, 'Sales loading 期间不发生状态变更')
})
