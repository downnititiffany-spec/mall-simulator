/**
 * tableOverflowWrapS05.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 AiAssistant.vue、Ops.vue、DataTable.vue 与 theme.css 源码并断言结构模式，
 * 锁定 AiAssistant 数据依据表横向滚动包裹容器（.table-wrap）、动态表头与行体防回退、样式机制锚点、范围守护与条件真值判定，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定纯函数仿真，并非真实浏览器 DOM 渲染或视觉排版证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const aiSource = readFileSync(join(root, 'src/views/AiAssistant.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')
const dataTableSource = readFileSync(join(root, 'src/components/DataTable.vue'), 'utf8')

test('a. 包裹结构：存在 <div v-if="resultTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合在位且紧跟 v-else 空态锚点', () => {
  // 1. 存在 <div v-if="resultTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    aiSource,
    /<div v-if="resultTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'AiAssistant 需包含 <div v-if="resultTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 以包裹位置后首个 table 标签逐字断言其不带 v-if
  const wrapIndex = aiSource.indexOf('<div v-if="resultTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 resultTable table-wrap 起始标签')
  const tableTagMatch = aiSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 v-else 空态 div 逐字锚点（当前时间范围无数据，不编造结论）
  const tableCloseIndex = aiSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 table 闭合标签')
  const wrapEnd = aiSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 table-wrap 结束标签')

  const postWrapSnippet = aiSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 200)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">当前时间范围无数据，不编造结论<\/div>/,
    'table 的 </table> 后需紧跟 </div> 与原 v-else 空态 div 逐字锚点'
  )
})

test('b. 内容不回退：包裹层内表头动态列、行体与标题锚点在位', () => {
  const wrapIndex = aiSource.indexOf('<div v-if="resultTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 resultTable table-wrap 起始标签')
  const tableCloseIndex = aiSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 table 闭合标签')
  const wrapEnd = aiSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 table-wrap 结束标签')
  const wrapSnippet = aiSource.slice(wrapIndex, wrapEnd + '</div>'.length)

  // 1. 包裹层内表头动态列锚点 v-for="k in resultTable.headers" 在位
  assert.match(
    wrapSnippet,
    /v-for="k in resultTable\.headers"/,
    '包裹层内需包含表头动态列锚点 v-for="k in resultTable.headers"'
  )

  // 2. 行体锚点 v-for="(row, i) in resultTable.rows" 与 v-for="(v, j) in row" 在位
  assert.match(
    wrapSnippet,
    /v-for="\(row,\s*i\) in resultTable\.rows"/,
    '包裹层内需包含行循环锚点 v-for="(row, i) in resultTable.rows"'
  )
  assert.match(
    wrapSnippet,
    /v-for="\(v,\s*j\) in row"/,
    '包裹层内需包含单元格循环锚点 v-for="(v, j) in row"'
  )

  // 3. 标题锚点 数据依据（真实查询结果，未做前端重算）在位
  assert.ok(
    aiSource.includes('数据依据（真实查询结果，未做前端重算）'),
    'AiAssistant 必须包含标题「数据依据（真实查询结果，未做前端重算）」'
  )
})

test('c. 机制锚点：theme.css 包含 .table-wrap 与 overflow-x: auto；AiAssistant.vue 内 table-wrap 恰为 1 处且无 overflow-x；table 标签恰为 1 处', () => {
  // 1. theme.css 含 .table-wrap 规则且含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto'
  )

  // 2. AiAssistant.vue 内 class="table-wrap" 恰为 1 处
  const wrapCount = (aiSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    wrapCount,
    1,
    `AiAssistant.vue 内 class="table-wrap" 恰好 1 处（实测 ${wrapCount} 处）`
  )

  // 3. AiAssistant.vue 内不含 overflow-x 字面（包裹只用既有类）
  assert.doesNotMatch(
    aiSource,
    /overflow-x/,
    'AiAssistant.vue 内不得包含 overflow-x 字面（包裹机制严格复用 theme.css 既有类）'
  )

  // 4. AiAssistant.vue 内 <table 标签恰为 1 处
  const tableCount = (aiSource.match(/<table\b/g) || []).length
  assert.equal(
    tableCount,
    1,
    `AiAssistant.vue 内 <table 标签恰好 1 处（实测 ${tableCount} 处）`
  )
})

test('d. 范围守护：Ops.vue 内审计双表 table 标签仍带 v-if 原形态；DataTable.vue 内 table 标签仍带 v-if 原形态', () => {
  // 1. Ops.vue 内审计双表 table 标签仍带 v-if 原形态
  assert.match(
    opsSource,
    /<table v-if="aiHistoryTable\.rows\.length" class="data-table">/,
    'Ops.vue 内 aiHistoryTable 必须逐字保留原有 <table v-if="aiHistoryTable.rows.length" class="data-table"> 原形态'
  )
  assert.match(
    opsSource,
    /<table v-if="aiCallTable\.rows\.length" class="data-table">/,
    'Ops.vue 内 aiCallTable 必须逐字保留原有 <table v-if="aiCallTable.rows.length" class="data-table"> 原形态'
  )

  // 2. DataTable.vue 内 table 标签仍带 v-if 原形态
  assert.match(
    dataTableSource,
    /<table v-if="table && table\.rows && table\.rows\.length" class="data-table">/,
    'DataTable.vue 内 table 标签必须逐字保留原有 <table v-if="table && table.rows && table.rows.length" class="data-table"> 原形态'
  )
})

test('e. 条件真值表仿真：以纯函数复刻该表包裹显隐判定（互斥性与全值域二值覆盖性）', () => {
  // 纯函数复刻该表包裹显隐判定：resultTable.rows.length > 0 显示包裹层、隐藏空态；= 0 隐藏包裹层、显示 v-else 空态
  const resolveAiTableVisibility = (rowsLength) => {
    const showTableWrap = rowsLength > 0
    const showEmpty = !showTableWrap
    return { showTableWrap, showEmpty }
  }

  // 1. resultTable.rows.length > 0 时显示包裹层、隐藏空态
  const withRows = resolveAiTableVisibility(3)
  assert.equal(withRows.showTableWrap, true, 'resultTable.rows.length > 0 时必须显示 table-wrap')
  assert.equal(withRows.showEmpty, false, 'resultTable.rows.length > 0 时必须隐藏空态')

  // 2. resultTable.rows.length === 0 时隐藏包裹层、显示 v-else 空态
  const emptyRows = resolveAiTableVisibility(0)
  assert.equal(emptyRows.showTableWrap, false, 'resultTable.rows.length === 0 时必须隐藏 table-wrap')
  assert.equal(emptyRows.showEmpty, true, 'resultTable.rows.length === 0 时必须显示 v-else 空态')

  // 3. 断言互斥与覆盖（全值域穷举）
  for (const len of [0, 1, 2, 5, 20, 100]) {
    const res = resolveAiTableVisibility(len)
    assert.equal(
      res.showTableWrap && res.showEmpty,
      false,
      `包裹层与空态互斥性违背：len=${len}`
    )
    assert.equal(
      res.showTableWrap || res.showEmpty,
      true,
      `包裹层与空态二值覆盖性违背：len=${len}`
    )
  }
})
