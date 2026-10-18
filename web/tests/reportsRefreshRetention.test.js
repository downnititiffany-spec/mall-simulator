/**
 * reportsRefreshRetention.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Reports.vue 源码并断言结构模式，
 * 锁定刷新平滑保留与失败防丢失机制，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定真值表仿真，并非真实浏览器 DOM 渲染证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const page = readFileSync(join(root, 'src/views/Reports.vue'), 'utf8')

const loadPageMatch = page.match(/async function loadPage\([\s\S]*?\n\}/)
const loadPageSnippet = loadPageMatch ? loadPageMatch[0] : ''

test('loadPage 刷新平滑保留：失败分支不抹除旧列表且保留成功赋值与完整生命周期 (规约 2.a, 2.e)', () => {
  assert.ok(loadPageSnippet.length > 0, '需成功截取 loadPage 函数片段')

  // 2.a. loadPage 片段内不存在 reports.value = []
  assert.doesNotMatch(loadPageSnippet, /reports\.value = \[\]/, 'loadPage 失败分支不得抹除旧列表 reports.value = []')

  // 2.a. 仍含 reports.value = result.items 成功赋值与 const sequence = ++requestSequence
  assert.match(
    loadPageSnippet,
    /reports\.value = result\.items/,
    'loadPage 需包含报告列表成功赋值 reports.value = result.items'
  )
  assert.match(
    loadPageSnippet,
    /const sequence = \+\+requestSequence/,
    'loadPage 需维护请求序号防竞态 const sequence = ++requestSequence'
  )

  // 2.e. loadPage 生命周期保留：片段内仍含 loading.value = true、起始 listError.value = '' 与 finally 中 loading.value = false
  assert.match(loadPageSnippet, /loading\.value = true/, 'loadPage 起始需置 loading 为 true')
  assert.match(loadPageSnippet, /listError\.value = ''/, 'loadPage 起始需重置 listError 为空')
  assert.match(
    loadPageSnippet,
    /finally\s*\{[\s\S]*?loading\.value = false[\s\S]*?\}/,
    'loadPage finally 中需恢复 loading 为 false'
  )
})

test('模板条件与分支守护：状态行无闪烁、表格平滑保留且横幅空态链路完整 (规约 2.b, 2.c, 2.d)', () => {
  // 2.b. 模板行 89 条件为 v-if="loading && reports.length === 0"，页面中不再存在独立的 v-if="loading" class="state-line" 形态
  assert.match(
    page,
    /<div v-if="loading && reports\.length === 0" class="state-line">正在读取报告…<\/div>/,
    '模板加载状态行需在 loading 且 reports 为空时展示'
  )
  assert.doesNotMatch(
    page,
    /v-if="loading"\s+class="state-line"/,
    '页面不得存在无长度检查的独立 loading state-line 状态行'
  )

  // 2.c. 表格挂载条件为外层 <div v-if="reports.length" class="table-wrap"> 包裹 <table class="data-table">，页面中不再存在 !loading && !listError 表格条件形态
  assert.match(
    page,
    /<div v-if="reports\.length" class="table-wrap">\s*<table class="data-table">/,
    '数据表格需以 reports.length 为挂载条件（包裹层）'
  )
  assert.doesNotMatch(
    page,
    /!loading\s*&&\s*!listError/,
    '数据表格条件中不得再包含 !loading && !listError'
  )

  // 2.d. 行 90 横幅分支 v-else-if="listError" 与行 91 空态分支 v-else-if="!reports.length" 仍在（else-if 链保留守护）
  assert.match(
    page,
    /<div v-else-if="listError" class="banner banner-error" role="alert">报告列表读取失败：\{\{\s*listError\s*\}\}<\/div>/,
    '需保留错误横幅分支 v-else-if="listError"'
  )
  assert.match(
    page,
    /<div v-else-if="!reports\.length" class="el-empty">还没有报告<\/div>/,
    '需保留空态分支 v-else-if="!reports.length"'
  )
})

test('条件链真值表仿真：状态行、错误横幅、空态与数据表格五种呈现组合落点 (规约 2.f)', () => {
  // 2.f. 条件链真值表仿真：以纯函数复刻呈现判定
  // 状态行 = loading && len===0；横幅 = !状态行 && !!listError；空态 = !状态行 && !listError && len===0；表格 = len>0
  const resolveDisplayState = ({ loading, reportsLength, listError }) => {
    const statusLine = Boolean(loading && reportsLength === 0)
    const banner = Boolean(!statusLine && Boolean(listError))
    const empty = Boolean(!statusLine && !listError && reportsLength === 0)
    const table = Boolean(reportsLength > 0)
    return { statusLine, banner, empty, table }
  }

  // 1. (loading=true, len=0, err='') → 状态行（首次加载显示状态行，表格未挂载）
  assert.deepEqual(
    resolveDisplayState({ loading: true, reportsLength: 0, listError: '' }),
    { statusLine: true, banner: false, empty: false, table: false },
    '首次加载 (loading=true, len=0, err="") 应仅命中状态行'
  )

  // 2. (true, 3, '') → 表格且无状态行（已有数据时刷新，表格保持挂载，不闪空）
  assert.deepEqual(
    resolveDisplayState({ loading: true, reportsLength: 3, listError: '' }),
    { statusLine: false, banner: false, empty: false, table: true },
    '已有数据刷新 (loading=true, len=3, err="") 应保持表格挂载且无状态行'
  )

  // 3. (false, 3, 'err') → 横幅+表格同屏（刷新失败且有旧数据，旧表格保留 + 红色横幅同屏报错）
  assert.deepEqual(
    resolveDisplayState({ loading: false, reportsLength: 3, listError: '网络超时' }),
    { statusLine: false, banner: true, empty: false, table: true },
    '刷新失败有旧数据 (loading=false, len=3, err="err") 应横幅与旧表格同屏呈现'
  )

  // 4. (false, 0, 'err') → 横幅且无表格（首次加载失败无旧数据，横幅报错且表格不挂载）
  assert.deepEqual(
    resolveDisplayState({ loading: false, reportsLength: 0, listError: '连接被拒绝' }),
    { statusLine: false, banner: true, empty: false, table: false },
    '首次加载失败 (loading=false, len=0, err="err") 应呈现横幅且无表格'
  )

  // 5. (false, 0, '') → 空态（加载成功且确实无数据，呈现「还没有报告」空态）
  assert.deepEqual(
    resolveDisplayState({ loading: false, reportsLength: 0, listError: '' }),
    { statusLine: false, banner: false, empty: true, table: false },
    '成功且确实无数据 (loading=false, len=0, err="") 应仅呈现空态'
  )
})
