/**
 * tableOverflowWrapS04.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue 与 theme.css 源码并断言结构模式，
 * 锁定 Ops 主区四张宽表横向滚动包裹容器（.table-wrap）、动态/固定表头与空态结构防回退、样式机制锚点与条件真值判定，防止代码发生回退。
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

test('a. metric 包裹结构：存在 <div v-if="metricTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合在位且 table-hint 与表头动态列在位', () => {
  // 1. 存在 <div v-if="metricTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="metricTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="metricTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. 以包裹位置后首个 table 标签逐字断言其不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="metricTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 metricTable table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 metric table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'metric table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 .table-hint 上下文提示锚点（「上下文来自所选快照」字面在位）
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 metric table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 metric table-wrap 结束标签')

  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 300)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div class="table-hint">/,
    'metric table 的 </table> 后需紧跟 </div> 与原 .table-hint 容器'
  )
  assert.ok(
    postWrapSnippet.includes('上下文来自所选快照'),
    'metric table-hint 必须包含「上下文来自所选快照」字面'
  )

  // 4. 表头动态列锚点 v-for="h in metricTable.headers" 在包裹层内
  const wrapSnippet = opsSource.slice(wrapIndex, wrapEnd + '</div>'.length)
  assert.match(
    wrapSnippet,
    /v-for="h in metricTable\.headers"/,
    'metric table 包裹层内需包含表头动态列锚点 v-for="h in metricTable.headers"'
  )
})

test('b. pipeline 包裹结构：存在 <div v-if="pipelineTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">；闭合在位且 v-else 空态与表头动态列在位', () => {
  // 1. 存在 <div v-if="pipelineTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="pipelineTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="pipelineTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. table 标签自身不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="pipelineTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 pipelineTable table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 pipeline table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'pipeline table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 该 table 的 </table> 后紧跟 </div> 与原 v-else 空态 div 逐字锚点（暂无流水线实例或该接口未返回数据）
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 pipeline table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 pipeline table-wrap 结束标签')

  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 300)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">暂无流水线实例或该接口未返回数据<\/div>/,
    'pipeline table 的 </table> 后需紧跟 </div> 与原 v-else 空态 div 逐字锚点'
  )

  // 4. 表头动态列锚点 v-for="h in pipelineTable.headers" 在包裹层内
  const wrapSnippet = opsSource.slice(wrapIndex, wrapEnd + '</div>'.length)
  assert.match(
    wrapSnippet,
    /v-for="h in pipelineTable\.headers"/,
    'pipeline table 包裹层内需包含表头动态列锚点 v-for="h in pipelineTable.headers"'
  )
})

test('c. ingestion 包裹结构：存在 <div v-if="ingestionBatches.length" class="table-wrap"> 紧邻 <table class="data-table">；固定 9 个表头齐全且闭合紧邻 v-else 空态', () => {
  // 1. 存在 <div v-if="ingestionBatches.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="ingestionBatches\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="ingestionBatches.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. table 标签不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="ingestionBatches.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 ingestionBatches table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 ingestion table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'ingestion table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. 固定 9 个表头文案（批次 ID/批次号/来源 / RuntimeProfile/状态/记录数/错误数/隔离数/开始时间/结束时间）齐全
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 ingestion table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 ingestion table-wrap 结束标签')
  const wrapSnippet = opsSource.slice(wrapIndex, wrapEnd + '</div>'.length)

  const expectedHeaders = [
    '批次 ID', '批次号', '来源 / RuntimeProfile', '状态', '记录数',
    '错误数', '隔离数', '开始时间', '结束时间'
  ]
  for (const header of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(header),
      `ingestion 表格包裹层内必须包含表头「${header}」`
    )
  }

  // 4. </table> 后紧跟 </div> 与原 v-else 空态锚点（暂无采集批次或接口未返回数据）
  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 300)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">暂无采集批次或接口未返回数据<\/div>/,
    'ingestion table 的 </table> 后需紧跟 </div> 与原 v-else 空态锚点'
  )
})

test('d. quality 包裹结构与机制锚点：存在 qualityTable 包裹；闭合紧跟 v-else 空态；theme.css 规则在位；Ops.vue 内 table-wrap 恰为 5 处且无 overflow-x；审计双表与 DataTable 逐字未动', () => {
  // 1. 存在 <div v-if="qualityTable.rows.length" class="table-wrap"> 紧邻 <table class="data-table">
  assert.match(
    opsSource,
    /<div v-if="qualityTable\.rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Ops 需包含 <div v-if="qualityTable.rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // 2. table 标签不带 v-if
  const wrapIndex = opsSource.indexOf('<div v-if="qualityTable.rows.length" class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 qualityTable table-wrap 起始标签')
  const tableTagMatch = opsSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 quality table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'quality table 标签自身保持逐字为 <table class="data-table">（不带 v-if）'
  )

  // 3. </table> 后紧跟 </div> 与原 v-else 空态锚点（暂无质量结果）
  const tableCloseIndex = opsSource.indexOf('</table>', wrapIndex)
  assert.ok(tableCloseIndex >= 0, '需找到 quality table 闭合标签')
  const wrapEnd = opsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 quality table-wrap 结束标签')

  const postWrapSnippet = opsSource.slice(tableCloseIndex, wrapEnd + '</div>'.length + 300)
  assert.match(
    postWrapSnippet,
    /<\/table>\s*<\/div>\s*<div v-else class="el-empty">暂无质量结果（跑一次流水线后出现）或该接口未返回数据<\/div>/,
    'quality table 的 </table> 后需紧跟 </div> 与原 v-else 空态锚点'
  )

  // 4. theme.css 含 .table-wrap 规则且含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto'
  )

  // 5. Ops.vue 内 class="table-wrap" 恰为 5 处（既有用户表 1 + 本切片 4）
  const count = (opsSource.match(/class="table-wrap"/g) || []).length
  assert.equal(
    count,
    5,
    `Ops.vue 内 class="table-wrap" 恰好 5 处（实测 ${count} 处）`
  )

  // 6. Ops.vue 内不含 overflow-x 字面（包裹只用既有类）
  assert.doesNotMatch(
    opsSource,
    /overflow-x/,
    'Ops.vue 内不得包含 overflow-x 字面（包裹机制严格复用 theme.css 既有类）'
  )

  // 7. 审计双表与 DataTable 使用处逐字未动
  // 断言 aiHistoryTable.rows.length 与 aiCallTable.rows.length 的 table 标签仍带 v-if 原形态
  assert.match(
    opsSource,
    /<table v-if="aiHistoryTable\.rows\.length" class="data-table">/,
    'aiHistoryTable 的 table 标签仍必须保留原有的 v-if="aiHistoryTable.rows.length" 形态'
  )
  assert.match(
    opsSource,
    /<table v-if="aiCallTable\.rows\.length" class="data-table">/,
    'aiCallTable 的 table 标签仍必须保留原有的 v-if="aiCallTable.rows.length" 形态'
  )
  // 断言 <DataTable 使用行仍在
  assert.match(
    opsSource,
    /<DataTable\s+:table="snapshotTable"/,
    '快照表所用 <DataTable :table="snapshotTable" 结构必须逐字保留'
  )
})

test('e. 条件真值表仿真：以纯函数复刻四表包裹显隐判定（metric 无 v-else 与其余三表 v-else 空态互斥逻辑）', () => {
  // 1. metric 表显隐纯函数仿真：表后无 v-else 分支，直接紧跟 table-hint
  const resolveMetricVisibility = (metricRowsLength) => {
    const showTableWrap = metricRowsLength > 0
    const showEmpty = false // metric 表无 v-else 空态分支
    return { showTableWrap, showEmpty }
  }

  // 1.1 metricTable.rows.length > 0 时显示包裹层，且无空态
  const metricWithData = resolveMetricVisibility(3)
  assert.equal(metricWithData.showTableWrap, true, 'metricTable.rows.length > 0 时必须显示 table-wrap')
  assert.equal(metricWithData.showEmpty, false, 'metric 表无论何时均无空态分支')

  // 1.2 metricTable.rows.length === 0 时隐藏包裹层，且无空态（由外层 status banner 或 table-hint 处理上下文）
  const metricEmpty = resolveMetricVisibility(0)
  assert.equal(metricEmpty.showTableWrap, false, 'metricTable.rows.length === 0 时隐藏 table-wrap')
  assert.equal(metricEmpty.showEmpty, false, 'metric 表隐藏时依然无空态分支')

  // 2. pipeline / ingestion / quality 三表显隐纯函数仿真：v-if 绑在 table-wrap 上，其后紧跟 v-else 空态 div
  const resolveIfElseTableVisibility = (rowsLength) => {
    const showTableWrap = rowsLength > 0
    const showEmpty = !showTableWrap
    return { showTableWrap, showEmpty }
  }

  // 2.1 pipeline 表仿真
  const pipelinePositive = resolveIfElseTableVisibility(5)
  assert.equal(pipelinePositive.showTableWrap, true, 'pipelineTable.rows.length > 0 时显示 table-wrap')
  assert.equal(pipelinePositive.showEmpty, false, 'pipelineTable.rows.length > 0 时不得显示空态')

  const pipelineZero = resolveIfElseTableVisibility(0)
  assert.equal(pipelineZero.showTableWrap, false, 'pipelineTable.rows.length === 0 时隐藏 table-wrap')
  assert.equal(pipelineZero.showEmpty, true, 'pipelineTable.rows.length === 0 时显示 v-else 空态')

  // 2.2 ingestion 表仿真
  const ingestionPositive = resolveIfElseTableVisibility(12)
  assert.equal(ingestionPositive.showTableWrap, true, 'ingestionBatches.length > 0 时显示 table-wrap')
  assert.equal(ingestionPositive.showEmpty, false, 'ingestionBatches.length > 0 时不得显示空态')

  const ingestionZero = resolveIfElseTableVisibility(0)
  assert.equal(ingestionZero.showTableWrap, false, 'ingestionBatches.length === 0 时隐藏 table-wrap')
  assert.equal(ingestionZero.showEmpty, true, 'ingestionBatches.length === 0 时显示 v-else 空态')

  // 2.3 quality 表仿真
  const qualityPositive = resolveIfElseTableVisibility(8)
  assert.equal(qualityPositive.showTableWrap, true, 'qualityTable.rows.length > 0 时显示 table-wrap')
  assert.equal(qualityPositive.showEmpty, false, 'qualityTable.rows.length > 0 时不得显示空态')

  const qualityZero = resolveIfElseTableVisibility(0)
  assert.equal(qualityZero.showTableWrap, false, 'qualityTable.rows.length === 0 时隐藏 table-wrap')
  assert.equal(qualityZero.showEmpty, true, 'qualityTable.rows.length === 0 时显示 v-else 空态')

  // 2.4 全值域互斥穷举断言：对 pipeline/ingestion/quality 三表，showTableWrap 与 showEmpty 永远互斥
  for (const len of [0, 1, 2, 10, 100]) {
    const res = resolveIfElseTableVisibility(len)
    assert.equal(
      res.showTableWrap && res.showEmpty,
      false,
      `三表显隐与空态互斥性违背：len=${len}`
    )
    assert.equal(
      res.showTableWrap || res.showEmpty,
      true,
      `三表二值覆盖性违背：len=${len}`
    )
  }
})
