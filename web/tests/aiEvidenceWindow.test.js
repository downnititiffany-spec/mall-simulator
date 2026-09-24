// QA-04（2026-09-22 实测评价 §QA-04）：AI 提问不再伪造时间范围，页面只展示后端结构化 window。
//
// 实测事实：页面固定调用 `api.aiQuery(text, '近30天', …)`，后端解释消费这个**前端编造**的标签，
// SQL 却自行选了真实日期 ⇒ 问「最近 7 天」时 SQL 是 20260915..20260921，结论与证据时间范围写
// 「近30天」；问「最新一期」（SQL 单日）仍写「近30天」。根因是两个时间范围事实来源。
//
// 修法：请求体不再带标签；后端的 `query.window` 成为有效查询期/参考业务日/覆盖情况/提示的唯一
// 来源；结构化字段缺失时如实降级，绝不打印「近30天」。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import {
  MISSING_TEXT,
  buildAiEvidenceContext,
  normalizeAiWindow,
  windowDisplayText
} from '../src/utils/context.js'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const readSrc = (rel) => readFileSync(join(root, rel), 'utf8')
const aiSrc = readSrc('src/views/AiAssistant.vue')
const apiSrc = readSrc('src/api.js')

// 只用代码行做硬断言：注释里解释历史缺陷（「曾经硬编码 近30天」）是允许的
const codeLines = (src) => src
  .split(/\r?\n/)
  .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*'))
  .join('\n')

const aiCode = codeLines(aiSrc)
const apiCode = codeLines(apiSrc)

/** 真实后端契约样例：最近 7 天请求，结果只覆盖 1 天 */
const WEEK_WINDOW = {
  requested: '最近 7 天',
  requestedDays: 7,
  from: '2026-09-15',
  to: '2026-09-21',
  days: 7,
  referenceBusinessDate: '2026-09-21',
  coveredDays: 1,
  notice: '有效查询区间 7 天，结果只覆盖 1 天（6 天无数据），不能据此判断趋势'
}

const withWindow = (window) => ({
  query: { status: 'EXECUTED', rows: [{ dt: '20260921', sale_amount: '1200.00' }], window },
  explanation: {
    summary: '结论文本。',
    evidence: { snapshotId: 'S20260921_20', timeRange: '2026-09-15 ~ 2026-09-21', returnedRows: 1 }
  }
})

// ── ① 结构化 window 的归一化：缺字段一律 null，绝不编造 ────────────────────────

test('normalizeAiWindow：完整结构化窗口原样搬运，参考业务日与覆盖情况都保留', () => {
  assert.deepEqual(normalizeAiWindow(WEEK_WINDOW), WEEK_WINDOW)
})

test('normalizeAiWindow：无结构化窗口 / 非对象 / 字段缺失 ⇒ null，不猜天数也不猜日期', () => {
  assert.equal(normalizeAiWindow(undefined), null)
  assert.equal(normalizeAiWindow(null), null)
  assert.equal(normalizeAiWindow([]), null)
  assert.equal(normalizeAiWindow('近30天'), null)
  const partial = normalizeAiWindow({ from: '2026-09-15', to: '2026-09-21' })
  assert.equal(partial.requested, null)
  assert.equal(partial.requestedDays, null)
  assert.equal(partial.days, 0, 'from/to 可得但后端未给 days 时按契约取 0，不前端算差')
  assert.equal(partial.referenceBusinessDate, null)
  assert.equal(partial.coveredDays, null)
  assert.equal(partial.notice, null)
})

// ── ② 页面文案：structured window 是唯一来源 ────────────────────────────────

test("windowDisplayText：有结构化窗口时展示有效区间与天数，绝不出现「近30天」", () => {
  const text = windowDisplayText(normalizeAiWindow(WEEK_WINDOW), '近30天')
  assert.match(text, /2026-09-15 ~ 2026-09-21/)
  assert.match(text, /共 7 天/)
  assert.doesNotMatch(text, /近30天/)
  assert.doesNotMatch(text, /近 30 天/)
})

test('windowDisplayText：后端 notice（覆盖不足）必须原样转达，不能被截断', () => {
  const text = windowDisplayText(normalizeAiWindow(WEEK_WINDOW), null)
  assert.ok(text.includes(WEEK_WINDOW.notice))
})

test('windowDisplayText：notice 缺失但后端声明覆盖天数不足时，页面补一句事实（不说 7 天趋势）', () => {
  const { notice, ...withoutNotice } = WEEK_WINDOW
  const text = windowDisplayText(normalizeAiWindow(withoutNotice), null)
  assert.match(text, /覆盖 1 \/ 7 天/)
  assert.doesNotMatch(text, /趋势上涨/)
})

test('windowDisplayText：无结构化窗口时降级到证据 timeRange，其次「接口未提供」，绝不回落到请求标签', () => {
  assert.equal(windowDisplayText(null, '2026-09-15 ~ 2026-09-21'), '2026-09-15 ~ 2026-09-21')
  assert.equal(windowDisplayText(null, null), MISSING_TEXT)
  assert.equal(windowDisplayText(null, '近30天'), '近30天', '没有结构化窗口时只能如实展示后端返回的文本')
  assert.equal(windowDisplayText(null, '   '), MISSING_TEXT)
})

// ── ③ 上下文：window 与 timeRange 一起进证据包 ───────────────────────────────

test('QA-04：query.window 进 evidence.window；timeRange 优先用结构化有效区间而非请求标签', () => {
  const ctx = buildAiEvidenceContext(withWindow(WEEK_WINDOW))
  assert.deepEqual(ctx.evidence.window, WEEK_WINDOW)
  assert.match(ctx.evidence.timeRange, /^2026-09-15 ~ 2026-09-21（共 7 天）/)
  assert.doesNotMatch(ctx.evidence.timeRange, /近30天/)
  assert.match(ctx.filters['时间范围'], /2026-09-15 ~ 2026-09-21/)
  assert.match(ctx.filters['时间范围'], /共 7 天/)
  assert.ok(ctx.filters['时间范围'].includes(WEEK_WINDOW.notice))
})

test('QA-04：最新一期（单日）与显式区间都由结构化窗口陈述，不沿用任何请求标签', () => {
  const latest = normalizeAiWindow({
    requested: '最新一期',
    requestedDays: 1,
    from: '2026-09-21',
    to: '2026-09-21',
    days: 1,
    referenceBusinessDate: '2026-09-21',
    coveredDays: 1,
    notice: null
  })
  const latestText = windowDisplayText(latest, '近30天')
  assert.match(latestText, /2026-09-21 ~ 2026-09-21/)
  assert.match(latestText, /共 1 天/)
  assert.doesNotMatch(latestText, /近30天/)

  const explicit = normalizeAiWindow({
    requested: '2026-09-15..2026-09-21',
    requestedDays: 7,
    from: '2026-09-15',
    to: '2026-09-21',
    days: 7,
    referenceBusinessDate: '2026-09-21',
    coveredDays: 7,
    notice: null
  })
  assert.equal(windowDisplayText(explicit, '近30天'), '2026-09-15 ~ 2026-09-21（共 7 天）')
})

test('QA-04：旧后端/错误响应没有结构化窗口时诚实降级，不打印「近30天」', () => {
  const legacy = buildAiEvidenceContext({
    query: { status: 'EXECUTED', rows: [] },
    explanation: { evidence: { snapshotId: 'S20260921_20', timeRange: '2026-09-15 ~ 2026-09-21' } }
  })
  assert.equal(legacy.evidence.window, null)
  assert.equal(legacy.evidence.timeRange, '2026-09-15 ~ 2026-09-21')

  const empty = buildAiEvidenceContext({ query: { rows: [] }, explanation: { evidence: {} } })
  assert.equal(empty.evidence.window, null)
  assert.equal(empty.evidence.timeRange, null)
  assert.equal(windowDisplayText(empty.evidence.window, empty.evidence.timeRange), MISSING_TEXT)
})

test('QA-04：空窗口（后端返回 window 但 from/to 为 null）不冒充单日，也不写日期', () => {
  const emptyWindow = normalizeAiWindow({
    requested: null,
    requestedDays: null,
    from: null,
    to: null,
    days: 0,
    referenceBusinessDate: '2026-09-21',
    coveredDays: 0,
    notice: '有效查询区间为空，结果未覆盖任何业务日'
  })
  const ctx = buildAiEvidenceContext(withWindow(emptyWindow))
  assert.deepEqual(ctx.evidence.window, emptyWindow)
  const text = windowDisplayText(ctx.evidence.window, ctx.evidence.timeRange)
  assert.ok(text.includes(emptyWindow.notice))
  assert.doesNotMatch(text, /2026-09-15/)
  assert.equal(ctx.evidence.timeRange, '2026-09-15 ~ 2026-09-21', '没有结构化区间时仍如实展示证据自带文本')
})

// ── ④ 源码守卫：请求体不再伪造标签，页面只渲染结构化窗口 ──────────────────────

test('QA-04：api.js 的 timeRange 是真正可选（缺省时不发送该字段），且不再有默认标签', () => {
  const aiQueryBody = apiSrc
    .slice(apiSrc.indexOf('aiQuery:'), apiSrc.indexOf('aiHistoryMine:'))
    .split(/\r?\n/)
    .filter((l) => !l.trim().startsWith('//'))
    .join('\n')
  assert.doesNotMatch(aiQueryBody, /timeRange\s*=\s*'/, '默认参数不得再写死标签')
  assert.match(aiQueryBody, /const body\s*=\s*\{\s*question\s*\}/, '请求体初始只含问题原文，不含任何时间标签')
  assert.match(aiQueryBody, /hasTimeRange/)
  assert.match(aiQueryBody, /if \(hasTimeRange\) body\.timeRange = timeRange/)
})

test('QA-04：AiAssistant 不再硬编码标签，只把问题交给 API（时间范围参数缺省）', () => {
  assert.doesNotMatch(aiCode, /'近30天'/)
  assert.doesNotMatch(aiCode, /"近30天"/)
  assert.match(aiCode, /api\.aiQuery\(\s*text,\s*undefined,\s*ctl\s*\?\s*\{ signal: ctl\.signal \}\s*:\s*undefined\s*\)/)
})

test('QA-04：页面展示结构化窗口（唯一来源），并直接消费共享的 evidence.window', () => {
  assert.match(aiSrc, /import\s*\{\s*[^}]*windowDisplayText[^}]*\}\s*from\s*'\.\.\/utils\/context'/, '窗口文案必须来自共享属主')
  assert.match(aiSrc, /evidence\.window/, '页面必须消费 context.js 搬运的结构化窗口')
  assert.match(aiCode, /windowDisplayText\(/, '页面必须调用共享属主，不得自拼窗口文案')
  assert.doesNotMatch(aiCode, /近30天/)
})
