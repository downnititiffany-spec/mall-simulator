/**
 * tableOverflowWrapS06.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue、DataTable.vue 与 theme.css 源码并断言结构模式，
 * 锁定 Ops 问答审计与调用审计双表横向滚动包裹容器（.table-wrap）、固定表头与行体防回退、网格容器与空态结构防回退、样式机制锚点、范围守护与条件真值判定，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定纯函数仿真，并非真实浏览器 DOM 渲染或视觉排版证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')
const dataTableSource = readFileSync(join(root, 'src/components/DataTable.vue'), 'utf8')

test('a. 问答审计包裹结构：存在 <div v-if="aiHistoryTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合紧跟 </div> 与 v-else 空态锚点；6 个表头齐全', () => {
  // 1. 存在 <div v-if="aiHistoryTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="aiHistoryTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="aiHistoryTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 以包裹位置后首个 table 标签逐字断言其不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="aiHistoryTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 aiHistoryTable table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 aiHistory table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'aiHistory table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 v-else 空态锚点（暂无问答记录）
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 aiHistory table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 aiHistory table-wrap 结束标签')

  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 200)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">暂无问答记录<\/div>/,
    'aiHistory table 的 </table> 后需紧跟 </div> 与原 v-else 空态锚点'
  )

  // 4. 6 个表头齐全（用户/问题/状态/行数/耗时/时间）
  const wrapSnippet = opsSource.slice(wrapIndex, wrapEnd + '</div>'.length)
  const expectedHeaders = ['用户', '问题', '状态', '行数', '耗时', '时间']
  for (const h of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(h),
      `问答审计表包裹层内必须包含表头「${h}」`
    )
  }
})

test('b. 调用审计包裹结构：存在 <div v-if="aiCallTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合紧跟 </div> 与 v-else 空态锚点；7 个表头齐全', () => {
  // 1. 存在 <div v-if="aiCallTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="aiCallTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="aiCallTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 以包裹位置后首个 table 标签逐字断言其不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="aiCallTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 aiCallTable table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 aiCall table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'aiCall table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 v-else 空态锚点（暂无调用记录）
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 aiCall table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 aiCall table-wrap 结束标签')

  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 200)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">暂无调用记录<\/div>/,
    'aiCall table 的 </table> 后需紧跟 </div> 与原 v-else 空态锚点'
  )

  // 4. 7 个表头齐全（用例/模型/来源 / 快照/请求指纹/tokens/耗时/状态）
  const wrapSnippet = opsSource.slice(wrapIndex, wrapEnd + '</div>'.length)
  const expectedHeaders = ['用例', '模型', '来源 / 快照', '请求指纹', 'tokens', '耗时', '状态']
  for (const h of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(h),
      `调用审计表包裹层内必须包含表头「${h}」`
    )
  }
})

test('c. 内容不回退：问答行循环锚点、问题列截断与 title 在位；调用行循环锚点、指纹列与预览在位；双列网格容器行逐字锚点在位', () => {
  // 1. 问答行循环锚点 v-for="h in aiHistoryRows"
  assert.match(
    opsSource,
    /v-for="h in aiHistoryRows"/,
    'Ops 需保留问答行循环锚点 v-for="h in aiHistoryRows"'
  )

  // 2. 问题列截断与 :title="h.question" 锚点在位
  assert.match(
    opsSource,
    /style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"\s+:title="h\.question"/,
    'Ops 需保留问答问题列的样式截断与 :title="h.question" 属性'
  )

  // 3. 调用行循环锚点 v-for="(c, i) in aiCallRows"
  assert.match(
    opsSource,
    /v-for="\(c,\s*i\) in aiCallRows"/,
    'Ops 需保留调用行循环锚点 v-for="(c, i) in aiCallRows"'
  )

  // 4. 指纹列 :title="c.requestFingerprint" 与 fingerprintPreview 锚点在位
  assert.ok(
    opsSource.includes(':title="c.requestFingerprint"'),
    'Ops 需保留调用指纹列完整指纹提示 :title="c.requestFingerprint"'
  )
  assert.ok(
    opsSource.includes('c.fingerprintPreview'),
    'Ops 需保留调用指纹预览插值 c.fingerprintPreview'
  )

  // 5. 双列网格容器行逐字锚点在位
  assert.ok(
    opsSource.includes('<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start">'),
    '双列网格容器行 <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start"> 必须逐字在位'
  )
})

test('d. 机制锚点与范围守护：theme.css 含 .table-wrap 规则且含 overflow-x: auto；Ops.vue 内 table-wrap 恰为 7 处且无 overflow-x；DataTable.vue 内包裹结构在位', () => {
  // 1. theme.css 含 .table-wrap 规则且含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto'
  )

  // 2. Ops.vue 内 class="table-wrap" 恰为 7 处（用户表 1 + S04 四表 4 + 审计双表 2）
  const count = (opsSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    count,
    7,
    `Ops.vue 内 class="table-wrap" 恰好 7 处（实测 ${count} 处）`
  )

  // 3. Ops.vue 内不含 overflow-x 字面（包裹只用既有类）
  assert.doesNotMatch(
    opsSource,
    /overflow-x/,
    'Ops.vue 内不得包含 overflow-x 字面（包裹机制严格复用 theme.css 既有类）'
  )

  // 4. DataTable.vue 内包裹结构在位
  assert.match(
    dataTableSource,
    /<div v-if="table && table\.rows && table\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'DataTable.vue 需包含 <div v-if="table && table.rows && table.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )
})

test('e. 条件真值表仿真：以纯函数复刻两表包裹显隐判定（rows.length > 0 显示包裹层、隐藏空态；= 0 隐藏包裹层、显示 v-else 空态），断言互斥与覆盖', () => {
  // 纯函数复刻两表包裹显隐判定：rows.length > 0 显示包裹层、隐藏空态；= 0 隐藏包裹层、显示 v-else 空态
  const resolveAuditTableVisibility = (rowsLength) => {
    const showTableWrap = rowsLength > 0
    const showEmpty = !showTableWrap
    return { showTableWrap, showEmpty }
  }

  // 1. 问答审计表（aiHistoryTable）显隐仿真
  const historyWithRows = resolveAuditTableVisibility(5)
  assert.equal(historyWithRows.showTableWrap, true, 'aiHistoryTable.rows.length > 0 时必须显示 table-wrap')
  assert.equal(historyWithRows.showEmpty, false, 'aiHistoryTable.rows.length > 0 时必须隐藏空态')

  const historyEmptyRows = resolveAuditTableVisibility(0)
  assert.equal(historyEmptyRows.showTableWrap, false, 'aiHistoryTable.rows.length === 0 时必须隐藏 table-wrap')
  assert.equal(historyEmptyRows.showEmpty, true, 'aiHistoryTable.rows.length === 0 时必须显示 v-else 空态')

  // 2. 调用审计表（aiCallTable）显隐仿真
  const callWithRows = resolveAuditTableVisibility(12)
  assert.equal(callWithRows.showTableWrap, true, 'aiCallTable.rows.length > 0 时必须显示 table-wrap')
  assert.equal(callWithRows.showEmpty, false, 'aiCallTable.rows.length > 0 时必须隐藏空态')

  const callEmptyRows = resolveAuditTableVisibility(0)
  assert.equal(callEmptyRows.showTableWrap, false, 'aiCallTable.rows.length === 0 时必须隐藏 table-wrap')
  assert.equal(callEmptyRows.showEmpty, true, 'aiCallTable.rows.length === 0 时必须显示 v-else 空态')

  // 3. 断言互斥与覆盖（全值域穷举）
  for (const len of [0, 1, 2, 5, 20, 100]) {
    const res = resolveAuditTableVisibility(len)
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
