// 目的：钉住「AI 建议 → 决策草稿」的请求体口径（契约 `docs/contracts/r8-evidence-security-decision.md`
// §5 补遗 v1.1，S3-42）。
// 被治的缺陷（S3-42 开工前实测）：
//   `web/src/api.js` 只有 `GET /decisions`、`GET /{id}/evaluations`、`POST /{id}/{action}`；
//   全 `web/src` 无 `decisionCreate`；`/ai/queries` 响应**顶层** `evidenceId`（证据包 ID，r8 §1 规则 5
//   规定由决策任务 `evidence_package_id` 引用）前端从未读取 ⇒ 员工无法把 AI 建议转成决策草稿，
//   而 `ControllerPermissionCoverageTest` 的冻结表里已经写着前端会调 `POST /api/v1/decisions`。
// 边界：本模块**只**决定「往 `POST /decisions` 装什么」——
//   ① 不复刻服务端 `missingForSubmit` 校验（避免第二属主，缺项由服务端 `PARAM_INVALID` 判定）；
//   ② 不下发 `source`/`status`/`evalWindowDays`（服务端固定/给默认）；
//   ③ 不猜目标方向（必须员工显式选）、不猜目标指标（模板分支后端给 null 就留空）。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ANCHOR_KIND,
  DRAFT_BLOCK,
  DRAFT_DIRECTIONS,
  DRAFT_FIELDS,
  SUBMIT_REQUIREMENT_TEXT,
  anchorText,
  buildDraftBody,
  draftAnchor,
  draftSuggestions,
  isRealEvidenceId,
  normalizeDirection
} from '../src/utils/decisionDraft.js'

// 后端真实形状（`ExplanationService.actionSuggestions` / 提示词 JSON）：title ≤60 字、action 原文、
// targetMetricCode 模板分支恒为 null。
const TEMPLATE_SUGGESTION = {
  title: '核查规则 REFUND_RATE_HIGH（MEDIUM）：退款率高于阈值，属可能相关，不构成因果',
  action: '核查规则 REFUND_RATE_HIGH（MEDIUM）：退款率高于阈值，属可能相关，不构成因果 [观测 0.6000 / 阈值 0.3000 / 偏离 0.3000，指标 refund_rate]',
  targetMetricCode: null
}
const MODEL_SUGGESTION = {
  title: '排查退款原因',
  action: '按商品维度下钻退款率最高的 5 个商品',
  targetMetricCode: 'refund_rate'
}
const EV = 'EV-20260916-3f2a9c'

test('draftSuggestions 只搬运后端给的字段，空列表/非数组得空数组（不补建议）', () => {
  assert.deepEqual(draftSuggestions(null), [])
  assert.deepEqual(draftSuggestions(undefined), [])
  assert.deepEqual(draftSuggestions([]), [])
  assert.deepEqual(draftSuggestions([null, 'x', 3]), [])
  assert.deepEqual(draftSuggestions([MODEL_SUGGESTION]), [
    { index: 0, title: '排查退款原因', action: '按商品维度下钻退款率最高的 5 个商品', targetMetricCode: 'refund_rate' }
  ])
  // 模板分支：targetMetricCode=null 原样保留 null，不由前端从 action 文案里猜
  const t = draftSuggestions([TEMPLATE_SUGGESTION])[0]
  assert.equal(t.targetMetricCode, null)
  assert.equal(t.action, TEMPLATE_SUGGESTION.action)
})

test('buildDraftBody：模板分支 targetMetricCode=null ⇒ 不下发该键（不猜指标）', () => {
  const r = buildDraftBody({ suggestion: TEMPLATE_SUGGESTION, evidenceId: EV, direction: 'UP' })
  assert.equal(r.ok, true)
  assert.equal(Object.prototype.hasOwnProperty.call(r.body, 'targetMetricCode'), false)
  assert.equal(r.body.title, TEMPLATE_SUGGESTION.title)
  assert.equal(r.body.action, TEMPLATE_SUGGESTION.action)
  assert.equal(r.body.evidencePackageId, EV)
})

test('buildDraftBody：员工没选方向 ⇒ 不下发 targetDirection（不猜方向）', () => {
  for (const direction of ['', null, undefined, '  ', 'up', 'UP ', '提升', '涨']) {
    const r = buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId: EV, direction })
    assert.equal(r.ok, true, `direction=${JSON.stringify(direction)} 应仍可构造草稿`)
    assert.equal(
      Object.prototype.hasOwnProperty.call(r.body, 'targetDirection'),
      false,
      `direction=${JSON.stringify(direction)} 不得下发方向`
    )
  }
  assert.equal(buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId: EV, direction: 'UP' }).body.targetDirection, 'UP')
  assert.equal(buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId: EV, direction: 'DOWN' }).body.targetDirection, 'DOWN')
  // 页面下拉框的「未选择」用空串表达，只能归一成 null，不得当成默认 UP
  assert.equal(normalizeDirection(''), null)
  assert.equal(normalizeDirection('UP'), DRAFT_DIRECTIONS.UP)
})

test('buildDraftBody：锚点优先证据包 ID，且两个锚点不得同时下发', () => {
  const r = buildDraftBody({
    suggestion: MODEL_SUGGESTION, evidenceId: EV, snapshotId: 'S20260901_24', direction: 'UP'
  })
  assert.equal(r.body.evidencePackageId, EV)
  assert.equal(Object.prototype.hasOwnProperty.call(r.body, 'suggestionSnapshotId'), false, '不得同时下发快照锚点')
})

test('buildDraftBody：证据包 ID 缺失或占位 ⇒ 回退真实快照号；占位快照号不算真实值', () => {
  for (const evidenceId of [null, '', '   ', 'unknown', 'UNKNOWN', 'UnKnOwN']) {
    const r = buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId, snapshotId: 'S20260901_24' })
    assert.equal(r.ok, true)
    assert.equal(r.body.suggestionSnapshotId, 'S20260901_24')
    assert.equal(Object.prototype.hasOwnProperty.call(r.body, 'evidencePackageId'), false)
  }
  assert.equal(draftAnchor({ evidenceId: null, snapshotId: 'unknown' }).kind, ANCHOR_KIND.NONE)
  assert.equal(draftAnchor({ evidenceId: 'unknown', snapshotId: null }).kind, ANCHOR_KIND.NONE)
})

test('S3-56：ID 逐字来自后端——带前后空白、数字、大小写 unknown 都不能被 trim/转串后接受', () => {
  const fallback = buildDraftBody({
    suggestion: MODEL_SUGGESTION,
    evidenceId: ` ${EV} `,
    snapshotId: 'S20260901_24'
  })
  assert.equal(fallback.ok, true)
  assert.equal(fallback.body.suggestionSnapshotId, 'S20260901_24')
  assert.equal(Object.prototype.hasOwnProperty.call(fallback.body, 'evidencePackageId'), false)

  for (const evidenceId of [` ${EV}`, `${EV} `, 12345, 'uNkNoWn']) {
    const a = draftAnchor({ evidenceId, snapshotId: null })
    assert.equal(a.kind, ANCHOR_KIND.NONE, `evidenceId=${JSON.stringify(evidenceId)} 不得被前端归一成真实 ID`)
  }
  for (const snapshotId of [' S20260901_24', 'S20260901_24 ', 2026090124, 'uNkNoWn']) {
    const a = draftAnchor({ evidenceId: null, snapshotId })
    assert.equal(a.kind, ANCHOR_KIND.NONE, `snapshotId=${JSON.stringify(snapshotId)} 不得被前端归一成真实 ID`)
  }
})

test('buildDraftBody：无可用锚点 ⇒ 拒绝构造（服务端 submit 要求二者之一，前端不造锚点）', () => {
  const r = buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId: null, snapshotId: 'unknown', direction: 'UP' })
  assert.equal(r.ok, false)
  assert.equal(r.body, null)
  assert.equal(r.blocked, DRAFT_BLOCK.NO_EVIDENCE_ANCHOR)
  assert.match(anchorText(draftAnchor({ evidenceId: null, snapshotId: null })), /不可创建/)
})

test('buildDraftBody：建议缺 title/action ⇒ 拒绝构造，不替后端补文案', () => {
  assert.equal(buildDraftBody({ suggestion: { action: 'x' }, evidenceId: EV }).blocked, DRAFT_BLOCK.SUGGESTION_INCOMPLETE)
  assert.equal(buildDraftBody({ suggestion: { title: 'x' }, evidenceId: EV }).blocked, DRAFT_BLOCK.SUGGESTION_INCOMPLETE)
  assert.equal(buildDraftBody({ suggestion: { title: '  ', action: ' x ' }, evidenceId: EV }).blocked, DRAFT_BLOCK.SUGGESTION_INCOMPLETE)
  assert.equal(buildDraftBody({}).blocked, DRAFT_BLOCK.SUGGESTION_INCOMPLETE)
  // 只有 title/action 是必需项：其余留空也应能建草稿（缺项由服务端 submit 阶段判定）
  const only = buildDraftBody({ suggestion: { title: 't', action: 'a' }, evidenceId: EV })
  assert.equal(only.ok, true)
  assert.deepEqual(Object.keys(only.body).sort(), ['action', 'evidencePackageId', 'title'])
})

test('buildDraftBody：键集合 ⊆ DRAFT_FIELDS，且不下发服务端固定项', () => {
  const r = buildDraftBody({
    suggestion: MODEL_SUGGESTION, evidenceId: EV, snapshotId: 'S20260901_24', direction: 'UP', owner: ' 张三 '
  })
  for (const key of Object.keys(r.body)) {
    assert.ok(DRAFT_FIELDS.includes(key), `请求体出现未登记字段 ${key}`)
  }
  for (const forbidden of ['source', 'status', 'evalWindowDays', 'id', 'decisionNo', 'createdBy']) {
    assert.equal(Object.prototype.hasOwnProperty.call(r.body, forbidden), false, `不得下发 ${forbidden}`)
  }
  assert.equal(r.body.owner, '张三')
  // 空值与 null 一律不下发（后端 trimToNull 之外不再制造空串）
  const noOwner = buildDraftBody({ suggestion: MODEL_SUGGESTION, evidenceId: EV, owner: '   ' })
  assert.equal(Object.prototype.hasOwnProperty.call(noOwner.body, 'owner'), false)
})

test('buildDraftBody 是纯函数：不改入参，且不接受非对象建议', () => {
  const suggestion = { ...MODEL_SUGGESTION }
  const frozen = JSON.stringify(suggestion)
  buildDraftBody({ suggestion, evidenceId: EV, direction: 'UP', owner: 'a' })
  assert.equal(JSON.stringify(suggestion), frozen)
  assert.equal(buildDraftBody({ suggestion: 'not-an-object', evidenceId: EV }).blocked, DRAFT_BLOCK.SUGGESTION_INCOMPLETE)
})

test('isRealEvidenceId / anchorText：占位、空白和形状污染不算真实证据包 ID，文案点明锚点来源', () => {
  assert.equal(isRealEvidenceId(EV), true)
  assert.equal(isRealEvidenceId(` ${EV} `), false)
  assert.equal(isRealEvidenceId(' unknown '), false)
  assert.equal(isRealEvidenceId('uNkNoWn'), false)
  assert.equal(isRealEvidenceId(123), false)
  assert.equal(isRealEvidenceId(''), false)
  assert.equal(isRealEvidenceId(null), false)
  assert.match(anchorText({ kind: ANCHOR_KIND.EVIDENCE_PACKAGE, value: EV }), /^evidence_package:EV-/)
  assert.match(anchorText({ kind: ANCHOR_KIND.SUGGESTION_SNAPSHOT, value: 'S20260901_24' }), /^suggestion_snapshot:S/)
  assert.match(SUBMIT_REQUIREMENT_TEXT, /服务端/)
})