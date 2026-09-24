// QA-03（2026-09-22 实测评价 §QA-03）：AI 证据快照的**唯一**归一化。
//
// 实测事实：API `explanation.evidence.snapshotId` 是真实快照号（如 S20260921_20），SQL 也逐字
// pin 了该快照，页面却显示「证据快照 未提供」，并宣称「SQL 用 MAX(snapshot_id)」——SQL 里没有 MAX。
// 根因：context.js 把 snapshotId 放在返回对象顶层，嵌套 evidence 不含 snapshotId，页面读的却是
// 嵌套字段；提示文案又来自一条硬编码的旧解释。
//
// 本文件钉两件事：
//   ① 快照身份**只**由 context.js 的归一化产出（snapshotIdentity / aiSnapshotIdentity），
//      展示值与决策草稿锚点必须读同一结果，页面不得自建第二份判据；
//   ② 真实号 ⇒ 原样展示且**没有**任何占位告警；unknown / 空 / 纯空白 / 数字 / 前后空白 ⇒
//      「未提供」+ 只说明「证据包没给出可用快照号」，绝不推断 SQL 用了 MAX，也绝不回退成 unknown。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import {
  aiSnapshotIdentity,
  buildAiEvidenceContext,
  snapshotIdentity
} from '../src/utils/context.js'
import { ANCHOR_KIND, buildDraftBody, draftAnchor } from '../src/utils/decisionDraft.js'

/** 快照号缺失时的页面文案：快照是精确标识，缺失只说「未提供」 */
const SNAPSHOT_MISSING_TEXT = '未提供'

const here = dirname(fileURLToPath(import.meta.url))
const aiSrc = readFileSync(join(here, '..', 'src', 'views', 'AiAssistant.vue'), 'utf8')
// 提示文案只在共享属主里出现：源码守卫要排除注释（注释里说明「不推断 MAX」是允许的）
const aiCode = aiSrc
  .split(/\r?\n/)
  .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*'))
  .join('\n')

const wrap = (raw) => ({
  query: { status: 'EXECUTED', rows: [] },
  explanation: { evidence: { snapshotId: raw, returnedRows: 1 } }
})

const suggestion = { title: '检查退款率异常', action: '复核退款订单', targetMetricCode: 'refund_rate' }

// ── ① 归一化函数本身：真实 / unknown / 空 / 数字 / 前后空白 ──────────────────────

test('snapshotIdentity：真实快照号原样保留，且不产生任何占位告警', () => {
  const identity = snapshotIdentity('S20260921_20')
  assert.equal(identity.snapshotId, 'S20260921_20')
  assert.equal(identity.text, 'S20260921_20')
  assert.equal(identity.hint, '')
})

test('snapshotIdentity：unknown（任意大小写）/ 空 / 纯空白 / 数字 / 前后空白一律按未提供，失败关闭', () => {
  for (const raw of ['unknown', 'UNKNOWN', 'Unknown', '', '   ', 20260921, ' S20260921_20', 'S20260921_20 ', null, undefined, {}]) {
    const identity = snapshotIdentity(raw)
    assert.equal(identity.snapshotId, null, `snapshotId=${JSON.stringify(raw)} 不得当快照号`)
    assert.equal(identity.text, SNAPSHOT_MISSING_TEXT, `snapshotId=${JSON.stringify(raw)} 必须显示「未提供」`)
    assert.ok(identity.hint, '未提供时必须给出提示，不得静默留空')
    assert.doesNotMatch(identity.hint, /MAX/, '提示不得推断 SQL 用了 MAX(snapshot_id)')
    assert.doesNotMatch(identity.hint, /unknown/i, '提示不得把占位串当成快照号展示')
    assert.doesNotMatch(identity.hint, /\d{6,}/, '提示不得回显原始占位输入')
  }
})

test('aiSnapshotIdentity：evidence → query → 结果行的回退顺序保持，且回退来源可解释', () => {
  assert.deepEqual(
    aiSnapshotIdentity({ evidence: { snapshotId: 'S20260921_20' }, query: { snapshotId: 'S20260901_24' }, rows: [{ snapshot_id: 'S20260831_23' }] }),
    { snapshotId: 'S20260921_20', text: 'S20260921_20', hint: '', source: 'evidence' }
  )
  assert.deepEqual(
    aiSnapshotIdentity({ evidence: { snapshotId: 'unknown' }, query: { snapshotId: 'S20260901_24' }, rows: [] }),
    { snapshotId: 'S20260901_24', text: 'S20260901_24', hint: '', source: 'query' }
  )
  assert.deepEqual(
    aiSnapshotIdentity({ evidence: {}, query: {}, rows: [{ snapshot_id: 'S20260831_23' }] }),
    { snapshotId: 'S20260831_23', text: 'S20260831_23', hint: '', source: 'row' }
  )
  const none = aiSnapshotIdentity({ evidence: { snapshotId: 20260921 }, query: {}, rows: [] })
  assert.equal(none.snapshotId, null)
  assert.equal(none.source, 'none')
  assert.equal(none.text, SNAPSHOT_MISSING_TEXT)
  assert.ok(none.hint)
})

// ── ② 上下文：顶层与嵌套必须是同一份归一化结果（QA-03 的原始缺陷）──────────────

test('QA-03：真实快照号必须同时出现在顶层与嵌套 evidence，且 `未提供` 不再出现', () => {
  const ctx = buildAiEvidenceContext(wrap('S20260921_20'))
  assert.equal(ctx.snapshotId, 'S20260921_20')
  assert.equal(ctx.evidence.snapshotId, 'S20260921_20', '页面读的是嵌套字段，必须与顶层同源')
  assert.equal(ctx.evidence.snapshotText, 'S20260921_20')
  assert.equal(ctx.evidence.snapshotHint, '')
  assert.doesNotMatch(ctx.evidence.snapshotHint, /未提供/)
  assert.doesNotMatch(ctx.missingNotice, /snapshotId/)
  assert.doesNotMatch(ctx.evidence.snapshotHint, /MAX/)
})

test('QA-03：unknown / 空 / 数字 / 前后空白 ⇒ 嵌套显示「未提供」，顶层同样为 null（一份判据）', () => {
  for (const raw of ['unknown', 'UNKNOWN', '', 20260921, ' S20260921_20', 'S20260921_20 ', null]) {
    const ctx = buildAiEvidenceContext(wrap(raw))
    assert.equal(ctx.evidence.snapshotId, null, `evidence.snapshotId=${JSON.stringify(raw)} 必须为 null`)
    assert.equal(ctx.snapshotId, null, `ctx.snapshotId=${JSON.stringify(raw)} 必须与嵌套同值`)
    assert.equal(ctx.evidence.snapshotText, SNAPSHOT_MISSING_TEXT)
    assert.ok(ctx.evidence.snapshotHint, '未提供时必须有提示')
    assert.doesNotMatch(ctx.evidence.snapshotHint, /MAX/, '不得再讲 MAX(snapshot_id) 的故事')
    assert.match(ctx.missingNotice, /snapshotId/)
  }
})

test('QA-03：顶层/嵌套同时给出不同值时仍只有一份归一化结果（嵌套优先，预登记回退路径）', () => {
  const ctx = buildAiEvidenceContext({
    snapshotId: 'S20260901_24',
    query: { status: 'EXECUTED', rows: [] },
    explanation: { evidence: { snapshotId: 'S20260921_20', returnedRows: 1 } }
  })
  assert.equal(ctx.evidence.snapshotId, 'S20260921_20')
  assert.equal(ctx.snapshotId, 'S20260921_20')
  assert.equal(ctx.evidence.snapshotText, ctx.snapshotId)
})

test('QA-03：草稿证据锚点携带同一份归一化快照号（真实号走 suggestionSnapshotId）', () => {
  const ctx = buildAiEvidenceContext(wrap('S20260921_20'))
  const anchor = draftAnchor({ evidenceId: ctx.evidence.evidenceId, snapshotId: ctx.evidence.snapshotId })
  assert.equal(ctx.evidence.evidenceId, null)
  assert.deepEqual(anchor, { kind: ANCHOR_KIND.SUGGESTION_SNAPSHOT, value: 'S20260921_20' })

  const built = buildDraftBody({
    suggestion,
    evidenceId: ctx.evidence.evidenceId,
    snapshotId: ctx.evidence.snapshotId,
    direction: 'UP',
    owner: '运营-小李'
  })
  assert.equal(built.ok, true)
  assert.equal(built.body.suggestionSnapshotId, 'S20260921_20')
  assert.equal('evidencePackageId' in built.body, false)
})

test('QA-03：占位快照号不得被锚点接受（fail-closed，草稿拒绝创建）', () => {
  const ctx = buildAiEvidenceContext(wrap('unknown'))
  assert.deepEqual(
    draftAnchor({ evidenceId: ctx.evidence.evidenceId, snapshotId: ctx.evidence.snapshotId }),
    { kind: ANCHOR_KIND.NONE, value: null }
  )
  const built = buildDraftBody({
    suggestion,
    evidenceId: ctx.evidence.evidenceId,
    snapshotId: ctx.evidence.snapshotId,
    direction: 'UP',
    owner: '运营-小李'
  })
  assert.equal(built.ok, false)
})

// ── ③ 源码守卫：AiAssistant 只有一条展示路径，不再自带判据/硬编码文案 ─────────────

test('QA-03：页面绑定共享归一化结果，且不再自行判断快照真实性', () => {
  assert.match(aiSrc, /证据快照/, '模板必须有证据快照一栏')
  assert.match(aiSrc, /\{\{\s*evidence\.snapshotText/, '模板直接绑定共享的 snapshotText')
  assert.match(aiSrc, /\{\{\s*evidence\.snapshotHint\s*\}\}/, '提示直接绑定共享的 snapshotHint')
  assert.equal((aiSrc.match(/evidence\.snapshotText/g) || []).length, 1, '展示值只允许一条来源')
  assert.equal((aiSrc.match(/evidence\.snapshotHint/g) || []).length, 2, '提示只允许「条件 + 输出」这一条渲染路径')
  assert.doesNotMatch(aiCode, /isRealSnapshotId\(/, '页面不得自建快照真实性判据')
})

test('QA-03：页面代码里不再有 MAX(snapshot_id) 的错误说明，也不再让组件名与字段名撞车', () => {
  assert.doesNotMatch(aiCode, /MAX\(snapshot_id\)/)
  assert.doesNotMatch(aiCode, /evidenceSnapshotText\s*=\s*computed/)
  assert.doesNotMatch(aiCode, /evidenceSnapshotHint\s*=\s*computed/)
  assert.doesNotMatch(aiCode, /pickRawEvidenceSnapshot/)
})
