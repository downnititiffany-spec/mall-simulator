/**
 * pipelineRunStageColumn.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Pipeline.vue 源码并断言结构模式与属性绑定，
 * 锁定实例表「当前阶段」列与失败码附注呈现，防止结构和不变量发生回退。
 * 特此注明：本测试属于静态源码结构守卫与呈现逻辑纯函数仿真，并非真实浏览器 DOM 渲染证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const VUE = fileURLToPath(new URL('../src/views/Pipeline.vue', import.meta.url))
const text = readFileSync(VUE, 'utf8')

const runTableMatch = text.match(/<table class="data-table">([\s\S]*?)<\/table>/)
const runTable = runTableMatch ? runTableMatch[1] : ''

test('Pipeline.vue 实例表表头含「当前阶段」且原9个表头完整保留，位置在「状态」之后与「尝试」之前', () => {
  assert.ok(runTable.length > 0, '需成功截取实例表 table.data-table 片段')

  const headMatch = runTable.match(/<thead><tr[^>]*>([\s\S]*?)<\/tr><\/thead>/)
  assert.ok(headMatch, '需找到实例表表头行 <thead><tr>')

  // 1. 表头包含 <th>当前阶段</th>
  assert.match(headMatch[1], /<th>当前阶段<\/th>/, '实例表表头需包含「当前阶段」列')

  // 2. 表头位置在 <th>状态</th> 之后、<th>尝试</th> 之前
  assert.match(
    headMatch[1],
    /<th>状态<\/th>\s*<th>当前阶段<\/th>\s*<th>尝试<\/th>/,
    '「当前阶段」列需紧邻在「状态」之后、「尝试」之前'
  )

  // 3. 原 9 个表头全部仍在
  const originalHeaders = [
    'ID',
    '流水线',
    '业务时间',
    '源数据版本',
    '输入批次',
    '目标快照',
    '状态',
    '尝试',
    '操作'
  ]
  for (const header of originalHeaders) {
    const escaped = header.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    assert.match(headMatch[1], new RegExp(`<th[^>]*>${escaped}</th>`), `原表头「${header}」必须保留`)
  }

  // 4. 表头全部 10 列顺序断言
  const thTitles = [...headMatch[1].matchAll(/<th[^>]*>(.*?)<\/th>/g)].map(m => m[1].trim())
  assert.deepEqual(thTitles, [
    'ID',
    '流水线',
    '业务时间',
    '源数据版本',
    '输入批次',
    '目标快照',
    '状态',
    '当前阶段',
    '尝试',
    '操作'
  ], '表头 10 列名称与顺序需完全符合预期')
})

test('Pipeline.vue 实例表行单元格绑定当前阶段与失败码红色附注', () => {
  assert.ok(runTable.length > 0, '需成功截取实例表 table.data-table 片段')

  const rowMatch = runTable.match(/<tr v-for="r in runRows"[\s\S]*?<\/tr>/)
  assert.ok(rowMatch, '需找到 runRows 数据行模版')
  const row = rowMatch[0]

  // b. 行单元格绑定 {{ r.currentStage || '—' }} 存在于实例表内
  assert.match(
    row,
    /<td class="mono">\{\{\s*r\.currentStage\s*\|\|\s*'—'\s*\}\}/,
    '数据行内需包含当前阶段单元格且未提供时回退为破折号'
  )

  // c. 失败码附注条件为 v-if="r.status === 'FAILED' && r.errorCode" 且附注体为 {{ r.errorCode }}，样式含 color:#dc2626
  assert.match(
    row,
    /<span\s+v-if="r\.status === 'FAILED' && r\.errorCode"\s+style="margin-left:6px;color:#dc2626">\{\{\s*r\.errorCode\s*\}\}<\/span>/,
    '失败码附注需严格在 FAILED 且有错误码时展示红色附注'
  )

  // 单元格位置：紧随状态徽标单元格之后、尝试次数之前
  assert.match(
    row,
    /<\/span>\s*<\/td>\s*<td class="mono">\{\{\s*r\.currentStage\s*\|\|\s*'—'\s*\}\}<span v-if="r\.status === 'FAILED' && r\.errorCode" style="margin-left:6px;color:#dc2626">\{\{\s*r\.errorCode\s*\}\}<\/span><\/td>\s*<td>\{\{\s*r\.attemptNo\s*\}\}<\/td>/,
    '当前阶段单元格需位于状态徽标列后与尝试次数列前'
  )
})

test('Pipeline.vue 实例表空态行 colspan 为 10 且与表头列数严格一致（互证既有不变量）', () => {
  assert.ok(runTable.length > 0, '需成功截取实例表 table.data-table 片段')

  const headMatch = runTable.match(/<thead><tr[^>]*>([\s\S]*?)<\/tr><\/thead>/)
  assert.ok(headMatch, '需找到实例表表头行')
  const thCount = (headMatch[1].match(/<th/g) || []).length

  // d. 空态行 colspan="10"，且实例表表头 <th 计数为 10
  assert.equal(thCount, 10, '实例表表头 <th 计数必须为 10')

  const emptyCellMatch = runTable.match(/<td colspan="(\d+)" class="el-empty">暂无运行记录<\/td>/)
  assert.ok(emptyCellMatch, '需找到空态单元格')
  assert.equal(emptyCellMatch[1], '10', '空态单元格 colspan 必须更新为 10')
  assert.equal(Number(emptyCellMatch[1]), thCount, '空态 colspan 必须与表头列数严格一致')
})

test('Pipeline.vue 实例表保留既有行级操作：重试、续跑、按阶段重试与标记失败', () => {
  assert.ok(runTable.length > 0, '需成功截取实例表 table.data-table 片段')

  const rowMatch = runTable.match(/<tr v-for="r in runRows"[\s\S]*?<\/tr>/)
  assert.ok(rowMatch, '需找到 runRows 数据行模版')
  const row = rowMatch[0]

  // e. 既有行级操作保留
  assert.match(
    row,
    /<button class="btn btn-sm" v-if="r\.status === 'FAILED'" @click="retry\(r\.id\)"[^>]*>重试<\/button>/,
    '需保留 FAILED 行基础重试按钮'
  )
  assert.match(row, /<template v-if="canOperatePipeline">/, '需保留管理员/运维操作门禁')
  assert.match(
    row,
    /<button class="btn btn-sm" v-if="r\.status === 'FAILED'" @click="beginRecovery\(r, 'resume'\)"[^>]*>续跑<\/button>/,
    '需保留续跑操作入口'
  )
  assert.match(
    row,
    /<button class="btn btn-sm" v-if="r\.status === 'FAILED'" @click="beginRecovery\(r, 'retry-stage'\)"[^>]*>按阶段重试<\/button>/,
    '需保留按阶段重试操作入口'
  )
  assert.match(
    row,
    /<button class="btn btn-sm btn-danger" v-if="r\.status === 'PENDING' \|\| r\.status === 'RUNNING'" @click="beginRecovery\(r, 'mark-failed'\)"[^>]*>标记失败…<\/button>/,
    '需保留标记失败操作入口'
  )
})

test('流水线实例当前阶段与失败码呈现逻辑仿真验证', () => {
  // f. 呈现逻辑仿真：以纯函数复刻单元格文本构成（阶段回退 — + 仅 FAILED&&errorCode 时附注）
  const renderStageCell = (status, currentStage, errorCode) => {
    const stage = currentStage || '—'
    const hasErrorCodeBadge = status === 'FAILED' && Boolean(errorCode)
    return {
      stage,
      hasErrorCodeBadge,
      errorCode: hasErrorCodeBadge ? errorCode : null,
      renderedText: hasErrorCodeBadge ? `${stage} ${errorCode}` : stage
    }
  }

  // 组合 1：(FAILED, 'BUILD_ADS', 'PIPELINE_QUALITY_FAILED') → 含阶段与失败码
  const c1 = renderStageCell('FAILED', 'BUILD_ADS', 'PIPELINE_QUALITY_FAILED')
  assert.equal(c1.stage, 'BUILD_ADS')
  assert.equal(c1.hasErrorCodeBadge, true)
  assert.equal(c1.errorCode, 'PIPELINE_QUALITY_FAILED')
  assert.equal(c1.renderedText, 'BUILD_ADS PIPELINE_QUALITY_FAILED')

  // 组合 2：(RUNNING, 'LOAD_ODS', null) → 仅阶段
  const c2 = renderStageCell('RUNNING', 'LOAD_ODS', null)
  assert.equal(c2.stage, 'LOAD_ODS')
  assert.equal(c2.hasErrorCodeBadge, false)
  assert.equal(c2.errorCode, null)
  assert.equal(c2.renderedText, 'LOAD_ODS')

  // 组合 3：(SUCCESS, null, null) → '—' 无附注
  const c3 = renderStageCell('SUCCESS', null, null)
  assert.equal(c3.stage, '—')
  assert.equal(c3.hasErrorCodeBadge, false)
  assert.equal(c3.errorCode, null)
  assert.equal(c3.renderedText, '—')

  // 组合 4：(FAILED, 'WAIT_LANDING', '') → 仅阶段无附注
  const c4 = renderStageCell('FAILED', 'WAIT_LANDING', '')
  assert.equal(c4.stage, 'WAIT_LANDING')
  assert.equal(c4.hasErrorCodeBadge, false)
  assert.equal(c4.errorCode, null)
  assert.equal(c4.renderedText, 'WAIT_LANDING')

  // 补充测试组合 5：非 FAILED 状态（如 SUCCESS）即便误带 errorCode 也不附注
  const c5 = renderStageCell('SUCCESS', 'PUBLISH_METRIC', 'STALE_ERROR')
  assert.equal(c5.stage, 'PUBLISH_METRIC')
  assert.equal(c5.hasErrorCodeBadge, false)
  assert.equal(c5.errorCode, null)
  assert.equal(c5.renderedText, 'PUBLISH_METRIC')

  // 补充测试组合 6：FAILED 状态但 currentStage 缺失时回退 '—' 且附注失败码
  const c6 = renderStageCell('FAILED', null, 'TIMEOUT')
  assert.equal(c6.stage, '—')
  assert.equal(c6.hasErrorCodeBadge, true)
  assert.equal(c6.errorCode, 'TIMEOUT')
  assert.equal(c6.renderedText, '— TIMEOUT')
})
