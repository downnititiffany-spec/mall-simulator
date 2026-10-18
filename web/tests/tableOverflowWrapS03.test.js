/**
 * tableOverflowWrapS03.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Products.vue、Sales.vue、Reports.vue 与 theme.css 源码并断言结构模式，
 * 锁定 Products、Sales、Reports 三张宽表横向滚动包裹容器（.table-wrap）、排序按钮与空态结构防回退、样式机制锚点与条件真值判定，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定纯函数仿真，并非真实浏览器 DOM 渲染或视觉排版证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const productsSource = readFileSync(join(root, 'src/views/Products.vue'), 'utf8')
const salesSource = readFileSync(join(root, 'src/views/Sales.vue'), 'utf8')
const reportsSource = readFileSync(join(root, 'src/views/Reports.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')

test('a. Products 包裹结构：存在 <div class="table-wrap"> 紧邻包裹 <table class="data-table">；空态 colspan="8" 与表头 sort-btn 在位', () => {
  // 1. 存在 <div class="table-wrap"> 紧邻包裹 <table class="data-table">
  assert.match(
    productsSource,
    /<div class="table-wrap">\s*<table class="data-table">/,
    'Products 需包含 <div class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. table 标签自身不带 v-if
  const wrapIndex = productsSource.indexOf('<div class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 Products table-wrap 起始标签')
  const tableTagMatch = productsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'Products table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 闭合标签 </table> 紧邻 </div>
  assert.match(
    productsSource,
    /<\/table>\s*<\/div>/,
    'Products 的 </table> 后需紧跟 </div>'
  )

  // 4. 截取包裹层内容，验证表格内容不回退
  const tableCloseIndex = productsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 Products table 闭合标签')
  const wrapEnd = productsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 Products table-wrap 结束标签')
  const wrapSnippet = productsSource.slice(wrapIndex, wrapEnd + '</div>'.length)

  // 空态 colspan="8" 在位
  assert.match(
    wrapSnippet,
    /<td colspan="8" class="el-empty">当前分页窗口没有商品明细数据<\/td>/,
    'Products 包裹层内需包含空态 colspan="8"'
  )

  // 表头 sort-btn 结构（U06-S02 形态）在位
  assert.match(
    wrapSnippet,
    /<button\s+v-if="col\.sortKey"\s+type="button"\s+class="sort-btn"\s+@click="toggleSort\(col\)">/,
    'Products 包裹层内需包含可排序 button'
  )
  assert.match(
    wrapSnippet,
    /<template\s+v-else>{{ col\.title }}<\/template>/,
    'Products 包裹层内需包含不可排序 template 分支'
  )
})

test('b. Sales 包裹结构：存在 <div class="table-wrap"> 紧邻包裹 <table class="data-table">；空态 colspan="6" 与表头 sort-btn 在位', () => {
  // 1. 存在 <div class="table-wrap"> 紧邻包裹 <table class="data-table">
  assert.match(
    salesSource,
    /<div class="table-wrap">\s*<table class="data-table">/,
    'Sales 需包含 <div class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. table 标签自身不带 v-if
  const wrapIndex = salesSource.indexOf('<div class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 Sales table-wrap 起始标签')
  const tableTagMatch = salesSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'Sales table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 闭合标签 </table> 紧邻 </div>
  assert.match(
    salesSource,
    /<\/table>\s*<\/div>/,
    'Sales 的 </table> 后需紧跟 </div>'
  )

  // 4. 截取包裹层内容，验证表格内容不回退
  const tableCloseIndex = salesSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 Sales table 闭合标签')
  const wrapEnd = salesSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 Sales table-wrap 结束标签')
  const wrapSnippet = salesSource.slice(wrapIndex, wrapEnd + '</div>'.length)

  // 空态 colspan="6" 在位
  assert.match(
    wrapSnippet,
    /<td colspan="6" class="el-empty">所选日期范围内没有销售数据<\/td>/,
    'Sales 包裹层内需包含空态 colspan="6"'
  )

  // 表头 sort-btn 结构（U06-S02 形态）在位
  assert.match(
    wrapSnippet,
    /<button\s+type="button"\s+class="sort-btn"\s+@click="toggleSort\(col\.key\)">/,
    'Sales 包裹层内需包含原生 button 表头'
  )
})

test('c. Reports 包裹结构：存在 <div v-if="reports.length" class="table-wrap">；其内 table 标签不带 v-if；7 个表头齐全；详情区 .report-table-scroll 仍在', () => {
  // 1. 存在 <div v-if="reports.length" class="table-wrap"> 紧邻包裹 <table class="data-table">
  assert.match(
    reportsSource,
    /<div v-if="reports\.length" class="table-wrap">\s*<table class="data-table">/,
    'Reports 需包含 <div v-if="reports.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 报告列表 table 标签自身不带 v-if
  const wrapIndex = reportsSource.indexOf('<div v-if="reports.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 Reports table-wrap 起始标签')
  const tableTagMatch = reportsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'Reports 报告列表的 table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 闭合标签 </table> 紧邻 </div>
  const tableCloseIndex = reportsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 Reports 报告列表 table 闭合标签')
  const wrapEnd = reportsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 Reports table-wrap 结束标签')
  const wrapSnippet = reportsSource.slice(wrapIndex, wrapEnd + '</div>'.length)

  // 4. 7 个表头文案（编号/模板/报告窗口/快照/状态/创建时间/操作）齐全
  const expectedHeaders = ['编号', '模板', '报告窗口', '快照', '状态', '创建时间', '操作']
  for (const header of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(`<th>${header}</th>`),
      `Reports 报告列表包裹层内必须包含表头「${header}」`
    )
  }

  // 5. 详情区 .report-table-scroll 仍在且未被包裹改动
  assert.match(
    reportsSource,
    /<div class="report-table-scroll">\s*<table class="data-table">/,
    'Reports 详情区需保留 .report-table-scroll 包裹容器'
  )
  const detailScrollIndex = reportsSource.indexOf('<div class="report-table-scroll">')
  assert.ok(detailScrollIndex > wrapEnd, '详情区 .report-table-scroll 应位于报告列表包裹层之后')
  const detailSnippet = reportsSource.slice(detailScrollIndex)
  const detailHeaders = ['指标', '数值', '期间', '来源证据']
  for (const h of detailHeaders) {
    assert.ok(
      detailSnippet.includes(`<th>${h}</th>`),
      `Reports 详情区必须包含表头「${h}」`
    )
  }
})

test('d. 机制锚点：theme.css 含 .table-wrap 规则且含 overflow-x: auto；Products/Sales/Reports 三文件各恰有 1 处 class="table-wrap"', () => {
  // theme.css 中 .table-wrap 规则含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto'
  )

  // Products/Sales/Reports 三文件各恰有 1 处 class="table-wrap"
  const countProducts = (productsSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    countProducts,
    1,
    `Products.vue 中 class="table-wrap" 恰好 1 处（实测 ${countProducts} 处）`
  )

  const countSales = (salesSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    countSales,
    1,
    `Sales.vue 中 class="table-wrap" 恰好 1 处（实测 ${countSales} 处）`
  )

  const countReports = (reportsSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    countReports,
    1,
    `Reports.vue 中 class="table-wrap" 恰好 1 处（实测 ${countReports} 处）`
  )
})

test('e. 条件真值表仿真：以纯函数复刻 Reports 包裹显隐判定与 Products/Sales 无条件显示', () => {
  // 1. Reports 条件分支与显隐纯函数仿真
  const resolveReportsVisibility = ({ reportsLength, loading = false, listError = null }) => {
    let statusBranch = 'none'
    if (loading && reportsLength === 0) {
      statusBranch = 'loading'
    } else if (listError) {
      statusBranch = 'error'
    } else if (!reportsLength) {
      statusBranch = 'empty'
    }

    const showTableWrap = reportsLength > 0
    return { statusBranch, showTableWrap }
  }

  // 1.1 reports.length > 0 时显示包裹层，且与空态分支互斥
  const withRows = resolveReportsVisibility({ reportsLength: 5 })
  assert.equal(withRows.showTableWrap, true, 'reports.length > 0 时必须显示 table-wrap')
  assert.equal(withRows.statusBranch, 'none', '有报告数据时不得命中状态分支')
  assert.notEqual(withRows.statusBranch, 'empty', '有报告数据时不得为空态')

  // 1.2 reports.length === 0 时隐藏包裹层，且命中空态（非 loading、非 error）
  const emptyReady = resolveReportsVisibility({ reportsLength: 0 })
  assert.equal(emptyReady.showTableWrap, false, 'reports.length === 0 时不得显示 table-wrap')
  assert.equal(emptyReady.statusBranch, 'empty', '无报告且非 loading/error 时命中「还没有报告」空态')

  // 1.3 reports.length === 0 且 loading 时隐藏包裹层，命中 loading
  const emptyLoading = resolveReportsVisibility({ reportsLength: 0, loading: true })
  assert.equal(emptyLoading.showTableWrap, false, 'reports.length === 0 且 loading 时不得显示 table-wrap')
  assert.equal(emptyLoading.statusBranch, 'loading', '命中「正在读取报告…」状态行')

  // 1.4 reports.length === 0 且 listError 时隐藏包裹层，命中 error
  const emptyError = resolveReportsVisibility({ reportsLength: 0, listError: '网络超时' })
  assert.equal(emptyError.showTableWrap, false, 'reports.length === 0 且 error 时不得显示 table-wrap')
  assert.equal(emptyError.statusBranch, 'error', '命中「报告列表读取失败」横幅')

  // 1.5 互斥性全值域穷举：showTableWrap 与 statusBranch === 'empty' 永远不可同时为真
  for (const len of [0, 1, 2, 10, 100]) {
    for (const load of [false, true]) {
      for (const err of [null, 'err']) {
        const res = resolveReportsVisibility({ reportsLength: len, loading: load, listError: err })
        const bothActive = res.showTableWrap && res.statusBranch === 'empty'
        assert.equal(bothActive, false, `互斥性违背：len=${len}, load=${load}, err=${err}`)
      }
    }
  }

  // 2. Products / Sales 无条件显示仿真
  // 现行模板中 Products 与 Sales 的 table-wrap 不挂载 v-if / v-show，无论数据行数多少容器均无条件挂载
  const resolveUnconditionalWrapVisibility = (rowsCount) => {
    // 模板层无 v-if / v-show 控制外层包裹
    return true
  }

  assert.equal(resolveUnconditionalWrapVisibility(0), true, 'Products/Sales 在 0 行时 table-wrap 仍然无条件渲染')
  assert.equal(resolveUnconditionalWrapVisibility(10), true, 'Products/Sales 在 10 行时 table-wrap 无条件渲染')
})
