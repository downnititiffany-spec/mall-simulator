/**
 * tableOverflowContainer.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Decisions.vue、Pipeline.vue 与 theme.css 源码并断言结构模式，
 * 锁定宽表横向滚动包裹容器（.table-wrap）与条件链语义，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定真值表仿真，并非真实浏览器 DOM 渲染或视觉排版证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const decisionsSource = readFileSync(join(root, 'src/views/Decisions.vue'), 'utf8')
const pipelineSource = readFileSync(join(root, 'src/views/Pipeline.vue'), 'utf8')
const themeCssSource = readFileSync(join(root, 'src/styles/theme.css'), 'utf8')

test('a. Decisions 决策列表外层包裹 table-wrap 且条件链锚点逐字在位', () => {
  // 源码中存在 <div v-if="rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">
  assert.match(
    decisionsSource,
    /<div v-if="rows\.length" class="table-wrap">\s*<table class="data-table">/,
    'Decisions 需包含 <div v-if="rows.length" class="table-wrap"> 且其后紧邻 <table class="data-table">'
  )

  // table 标签自身不再带 v-if
  assert.doesNotMatch(
    decisionsSource,
    /<table[^>]*v-if/,
    'Decisions 的 table 标签自身不得再带有 v-if 属性（已提升至外层 table-wrap div）'
  )

  // 该 table 的 </table> 后紧跟 </div> 与原 v-else-if 空态 div（条件链锚点逐字在位）
  assert.match(
    decisionsSource,
    /<\/table>\s*<\/div>\s*<div v-else-if="state !== 'loading' && state !== 'error'" class="el-empty">暂无决策<\/div>/,
    'Decisions 的 </table> 后需紧跟 </div>，且紧接着 v-else-if 空态 div（条件链锚点逐字在位）'
  )
})

test('b. Decisions 表格内容不回退：13 列表头与行内操作按钮 @click 绑定在包裹层内保留', () => {
  const wrapStart = decisionsSource.indexOf('<div v-if="rows.length" class="table-wrap">')
  assert.ok(wrapStart >= 0, '需找到 Decisions table-wrap 起始标签')
  const tableCloseIndex = decisionsSource.indexOf('</table>', wrapStart)
  assert.ok(tableCloseIndex >= 0, '需找到 Decisions table 闭合标签')
  const wrapEnd = decisionsSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 Decisions table-wrap 结束标签')
  const wrapSnippet = decisionsSource.slice(wrapStart, wrapEnd + '</div>'.length)

  // 13 个表头文案逐一验证（编号/标题/来源/目标指标/基线/建议快照/基线快照/口径版本/负责人/截止日期/状态/效果/操作）
  const expectedHeaders = [
    '编号', '标题', '来源', '目标指标', '基线',
    '建议快照', '基线快照', '口径版本', '负责人', '截止日期',
    '状态', '效果', '操作'
  ]
  for (const header of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(header),
      `Decisions 包裹层内必须包含表头「${header}」`
    )
  }

  // 行内操作按钮的 @click 绑定抽验（至少抽验两个：submitDecision / approve）
  assert.match(
    wrapSnippet,
    /@click="submitDecision\(d\)"/,
    'Decisions 包裹层内需包含 submitDecision(d) 按钮点击绑定'
  )
  assert.match(
    wrapSnippet,
    /@click="approve\(d\)"/,
    'Decisions 包裹层内需包含 approve(d) 按钮点击绑定'
  )
  assert.match(
    wrapSnippet,
    /@click="rejectDecision\(d\)"/,
    'Decisions 包裹层内需包含 rejectDecision(d) 按钮点击绑定'
  )
  assert.match(
    wrapSnippet,
    /@click="evaluate\(d\)"/,
    'Decisions 包裹层内需包含 evaluate(d) 按钮点击绑定'
  )
})

test('c. Pipeline 最近实例表外层包裹 table-wrap 且 10 列表头与空态 colspan="10" 保留', () => {
  // 最近实例表的 <table class="data-table"> 前紧邻 <div class="table-wrap">
  assert.match(
    pipelineSource,
    /<div class="table-wrap">\s*<table class="data-table">/,
    'Pipeline 最近实例表的 <table class="data-table"> 前需紧邻 <div class="table-wrap">'
  )

  // table 自身不加 v-if：范围收窄至 table-wrap 之后紧邻的 table 标签，断言逐字为 <table class="data-table">
  const wrapIndex = pipelineSource.indexOf('<div class="table-wrap">')
  assert.ok(wrapIndex >= 0, '需找到 Pipeline table-wrap 起始标签')
  const tableTagMatch = pipelineSource.slice(wrapIndex).match(/<table[^>]*>/)
  assert.ok(tableTagMatch, '需在 table-wrap 之后找到 table 开标签')
  assert.equal(
    tableTagMatch[0],
    '<table class="data-table">',
    'Pipeline 最近实例表的 table 标签保持逐字为 <table class="data-table">（不含 v-if 及其他属性）'
  )

  // 其 </table> 后紧跟 </div>
  assert.match(
    pipelineSource,
    /<\/table>\s*<\/div>/,
    'Pipeline 的 </table> 后需紧跟 </div>'
  )

  const wrapStart = pipelineSource.indexOf('<div class="table-wrap">')
  assert.ok(wrapStart >= 0, '需找到 Pipeline table-wrap 起始标签')
  const tableCloseIndex = pipelineSource.indexOf('</table>', wrapStart)
  assert.ok(tableCloseIndex >= 0, '需找到 Pipeline table 闭合标签')
  const wrapEnd = pipelineSource.indexOf('</div>', tableCloseIndex)
  assert.ok(wrapEnd >= 0, '需找到 Pipeline table-wrap 结束标签')
  const wrapSnippet = pipelineSource.slice(wrapStart, wrapEnd + '</div>'.length)

  // 10 个表头文案（含「当前阶段」）在包裹层内
  const expectedHeaders = [
    'ID', '流水线', '业务时间', '源数据版本', '输入批次',
    '目标快照', '状态', '当前阶段', '尝试', '操作'
  ]
  for (const header of expectedHeaders) {
    assert.ok(
      wrapSnippet.includes(header),
      `Pipeline 包裹层内必须包含表头「${header}」`
    )
  }

  // 空态 colspan="10" 仍在包裹层内
  assert.match(
    wrapSnippet,
    /colspan="10"/,
    'Pipeline 包裹层内需包含空态 colspan="10"'
  )
  assert.match(
    wrapSnippet,
    /<td colspan="10" class="el-empty">暂无运行记录<\/td>/,
    'Pipeline 包裹层内需完整保留空态行'
  )
})

test('d. 机制锚点：滚动能力来自 theme.css 既有 .table-wrap 类且视图组件无新增 overflow 样式', () => {
  // theme.css 中 .table-wrap 规则含 overflow-x: auto
  assert.match(
    themeCssSource,
    /\.table-wrap\s*\{[^}]*overflow-x:\s*auto[^}]*\}/,
    'theme.css 中 .table-wrap 规则必须包含 overflow-x: auto（断言滚动能力来自既有通用样式）'
  )

  // Decisions.vue 内不含 overflow-x 字面（只使用既有类，无内联或 scoped 自定义 overflow 样式）
  assert.doesNotMatch(
    decisionsSource,
    /overflow-x/,
    'Decisions.vue 内不得出现 overflow-x 字面（只使用既有类，不新增行内或局部样式）'
  )

  // Pipeline.vue 内不含 overflow-x 字面（只使用既有类，无内联或 scoped 自定义 overflow 样式）
  assert.doesNotMatch(
    pipelineSource,
    /overflow-x/,
    'Pipeline.vue 内不得出现 overflow-x 字面（只使用既有类，不新增行内或局部样式）'
  )
})

test('e. 条件链真值表仿真：Decisions 列表与空态互斥分支判定仿真', () => {
  // 以纯函数复刻 Decisions 的判定逻辑
  const resolveDecisionsBranch = ({ rowsLength, state }) => {
    if (rowsLength > 0) {
      return 'wrap'
    } else if (state !== 'loading' && state !== 'error') {
      return 'empty'
    } else {
      return 'none'
    }
  }

  // 1. 有行 → 包裹层（不管 state 为何，只要有数据即渲染表格包裹层）
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 5, state: 'ready' }),
    'wrap',
    '有行且就绪时应显示包裹层'
  )
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 1, state: 'loading' }),
    'wrap',
    '有行且加载中（刷新场景）时应保持显示包裹层'
  )
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 2, state: 'error' }),
    'wrap',
    '有行且出错时应保持显示包裹层'
  )

  // 2. 无行 + ready → 空态
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 0, state: 'ready' }),
    'empty',
    '无行且就绪时应显示空态'
  )
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 0, state: 'stale' }),
    'empty',
    '无行且数据陈旧时应显示空态'
  )
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 0, state: 'empty' }),
    'empty',
    '无行且状态为 empty 时应显示空态'
  )

  // 3. 无行 + loading → 皆不显示
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 0, state: 'loading' }),
    'none',
    '无行且加载中时两者皆不显示（由上方 loading 状态行承接反馈）'
  )

  // 4. 无行 + error → 皆不显示
  assert.equal(
    resolveDecisionsBranch({ rowsLength: 0, state: 'error' }),
    'none',
    '无行且出错时两者皆不显示（由上方 banner-error 承接反馈）'
  )
})
