/**
 * tableOverflowWrapS07.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 DataTable.vue、Ops.vue 与 theme.css 源码并断言结构模式，
 * 锁定 DataTable 通用表组件横向滚动包裹容器（.table-wrap）、表头表体与操作按钮防回退、样式机制锚点、范围守护与复合条件真值判定，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定纯函数仿真，并非真实浏览器 DOM 渲染或视觉排版证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const dataTableSource = readFileSync(join(root, 'src/components/DataTable.vue'), 'utf8')
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')

test('a. 包裹结构：存在 <div v-if="table && table.rows && table.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合在位且紧跟 v-else 空态锚点', () => {
  // 1. 存在 <div v-if="table && table.rows && table.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    dataTableSource,
    /<div v-if="table && table\.rows && table\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'DataTable 需包含 <div v-if="table && table.rows && table.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 以包裹位置后首个 table 标签逐字断言其不带 v-if
  const wrapIndex = dataTableSource.indexOf('<div v-if="table && table.rows && table.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 DataTable table-wrap 起始标签')
  const tableTagMatch = dataTableSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 v-else 空态锚点（{{ emptyText }}）
  const tableCloseIndex = dataTableSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 table 闭合标签')
  const wrapEnd = dataTableSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 table-wrap 结束标签')

  const postWrapSnippet = dataTableSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 200)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">\{\{\s*emptyText\s*\}\}<\/div>/,
    'table 的 </table> 后需紧跟 </div> 与原 v-else 空态锚点 {{ emptyText }}'
  )
})

test('b. 内容不回退：表头循环锚点、操作列表头、行循环与高亮绑定、单元格循环与着色绑定与空值渲染、操作按钮在位', () => {
  // 1. 表头循环锚点 v-for="(h, i) in table.headers"
  assert.match(
    dataTableSource,
    /v-for="\(h,\s*i\)\s*in\s*table\.headers"/,
    '需包含表头循环锚点 v-for="(h, i) in table.headers"'
  )

  // 2. 操作列表头 <th v-if="actionLabel">
  assert.match(
    dataTableSource,
    /<th v-if="actionLabel">\{\{\s*actionLabel\s*\}\}<\/th>/,
    '需包含操作列表头 <th v-if="actionLabel">{{ actionLabel }}</th>'
  )

  // 3. 行循环锚点 v-for="(row, i) in table.rows" 与 row-active 高亮绑定
  assert.match(
    dataTableSource,
    /v-for="\(row,\s*i\)\s*in\s*table\.rows"/,
    '需包含行循环锚点 v-for="(row, i) in table.rows"'
  )
  assert.match(
    dataTableSource,
    /:class="\{\s*'row-active':\s*isActive\s*\?\s*isActive\(i\)\s*:\s*false\s*\}"/,
    '需包含 row-active 高亮绑定'
  )

  // 4. 单元格循环与 cellColor 着色绑定与空值 — 渲染
  assert.match(
    dataTableSource,
    /v-for="\(cell,\s*j\)\s*in\s*row"/,
    '需包含单元格循环锚点 v-for="(cell, j) in row"'
  )
  assert.match(
    dataTableSource,
    /:style="cellColor\s*\?\s*\{\s*color:\s*cellColor\(cell,\s*i,\s*j\)\s*\}\s*:\s*null"/,
    '需包含 cellColor 着色绑定'
  )
  assert.ok(
    dataTableSource.includes("cell === null || cell === undefined ? '—' : cell"),
    '需包含空值「—」渲染锚点'
  )

  // 5. 操作按钮 查看指标 锚点在位
  assert.ok(
    dataTableSource.includes('查看指标'),
    '需包含操作按钮文案「查看指标」'
  )
  assert.match(
    dataTableSource,
    /<td v-if="actionLabel">\s*<button class="btn btn-sm" @click="props\.onAction && props\.onAction\(row,\s*i\)">查看指标<\/button>\s*<\/td>/,
    '需包含操作单元格及查看指标按钮完整结构'
  )
})

test('c. 机制锚点：theme.css 含 .table-wrap 与 overflow-x: auto；DataTable.vue 内 table-wrap 恰为 1 处且无 overflow-x；table 标签恰为 1 处', () => {
  // 1. theme.css 含 .table-wrap 规则且含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto'
  )

  // 2. DataTable.vue 内 class="table-wrap" 恰为 1 处
  const wrapCount = (dataTableSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    wrapCount,
    1,
    `DataTable.vue 内 class="table-wrap" 恰好 1 处（实测 ${wrapCount} 处）`
  )

  // 3. DataTable.vue 内不含 overflow-x 字面（包裹机制严格复用 theme.css 既有类）
  assert.doesNotMatch(
    dataTableSource,
    /overflow-x/,
    'DataTable.vue 内不得包含 overflow-x 字面（包裹机制严格复用 theme.css 既有类）'
  )

  // 4. DataTable.vue 内 <table 标签恰为 1 处
  const tableCount = (dataTableSource.match(/<table\b/g) || []).length
  assert.equal(
    tableCount,
    1,
    `DataTable.vue 内 <table 标签恰好 1 处（实测 ${tableCount} 处）`
  )
})

test('d. 范围守护：Ops.vue 内 <DataTable :table="snapshotTable" 使用行逐字在位且 Ops.vue 内 class="table-wrap" 仍恰为 7 处', () => {
  // 1. Ops.vue 内 <DataTable :table="snapshotTable" 使用行逐字在位
  assert.match(
    opsSource,
    /<DataTable\s+:table="snapshotTable"/,
    'Ops.vue 内必须逐字保留 <DataTable :table="snapshotTable" 使用行'
  )

  // 2. Ops.vue 内 class="table-wrap" 仍恰为 7 处（本切片不改 Ops.vue）
  const opsWrapCount = (opsSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    opsWrapCount,
    7,
    `Ops.vue 内 class="table-wrap" 仍恰好 7 处（实测 ${opsWrapCount} 处）`
  )
})

test('e. 条件真值表仿真：以纯函数复刻复合条件显隐判定（互斥性与全值域二值覆盖性）', () => {
  // 纯函数复刻复合条件显隐判定：table && table.rows && table.rows.length
  const resolveDataTableVisibility = (table) => {
    const isVisible = Boolean(table && table.rows && table.rows.length)
    const showTableWrap = isVisible
    const showEmpty = !isVisible
    return { showTableWrap, showEmpty }
  }

  // 1. table 为 null 或 undefined 时：隐藏包裹层、显示空态
  const nullCase = resolveDataTableVisibility(null)
  assert.equal(nullCase.showTableWrap, false, 'table 为 null 时必须隐藏包裹层')
  assert.equal(nullCase.showEmpty, true, 'table 为 null 时必须显示空态')

  const undefCase = resolveDataTableVisibility(undefined)
  assert.equal(undefCase.showTableWrap, false, 'table 为 undefined 时必须隐藏包裹层')
  assert.equal(undefCase.showEmpty, true, 'table 为 undefined 时必须显示空态')

  // 2. rows 缺失时（如 table={} 或 table={ rows: null }）：隐藏包裹层、显示空态
  const missingRowsCase = resolveDataTableVisibility({})
  assert.equal(missingRowsCase.showTableWrap, false, 'rows 缺失时必须隐藏包裹层')
  assert.equal(missingRowsCase.showEmpty, true, 'rows 缺失时必须显示空态')

  const nullRowsCase = resolveDataTableVisibility({ rows: null })
  assert.equal(nullRowsCase.showTableWrap, false, 'rows 为 null 时必须隐藏包裹层')
  assert.equal(nullRowsCase.showEmpty, true, 'rows 为 null 时必须显示空态')

  // 3. rows.length === 0 时：隐藏包裹层、显示空态
  const emptyRowsCase = resolveDataTableVisibility({ rows: [] })
  assert.equal(emptyRowsCase.showTableWrap, false, 'rows.length === 0 时必须隐藏包裹层')
  assert.equal(emptyRowsCase.showEmpty, true, 'rows.length === 0 时必须显示空态')

  // 4. rows.length > 0 时：显示包裹层、隐藏空态
  const singleRowCase = resolveDataTableVisibility({ rows: [['A', 'B']] })
  assert.equal(singleRowCase.showTableWrap, true, 'rows.length > 0 时必须显示包裹层')
  assert.equal(singleRowCase.showEmpty, false, 'rows.length > 0 时必须隐藏空态')

  const multiRowCase = resolveDataTableVisibility({ rows: [['A', 'B'], ['C', 'D']] })
  assert.equal(multiRowCase.showTableWrap, true, '多行数据时必须显示包裹层')
  assert.equal(multiRowCase.showEmpty, false, '多行数据时必须隐藏空态')

  // 5. 互斥性与二值覆盖性穷举验证
  const scenarios = [
    null,
    undefined,
    {},
    { rows: null },
    { rows: undefined },
    { rows: [] },
    { rows: [[]] },
    { rows: [['col1', 'col2']] },
    { rows: new Array(10).fill(['data']) }
  ]

  for (const scen of scenarios) {
    const res = resolveDataTableVisibility(scen)
    assert.equal(
      res.showTableWrap && res.showEmpty,
      false,
      `包裹层与空态互斥性违背：scen=${JSON.stringify(scen)}`
    )
    assert.equal(
      res.showTableWrap || res.showEmpty,
      true,
      `包裹层与空态二值覆盖性违背：scen=${JSON.stringify(scen)}`
    )
  }
})
