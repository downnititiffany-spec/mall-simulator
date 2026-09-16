import test from 'node:test'
import assert from 'node:assert/strict'

import { buildAiEvidenceContext } from '../src/utils/context.js'
import { ANCHOR_KIND, buildDraftBody, draftAnchor } from '../src/utils/decisionDraft.js'

const suggestion = {
  title: '检查退款率异常',
  action: '复核退款订单与商品维度',
  targetMetricCode: 'refund_rate'
}

test('S3-53：顶层 evidenceId 优先于 explanation.evidence.evidenceId，保持 /ai/queries 现有口径', () => {
  const ctx = buildAiEvidenceContext({
    evidenceId: 'EV-TOP-001',
    explanation: {
      evidence: {
        evidenceId: 'EV-NESTED-001',
        snapshotId: 'S20260916_01'
      }
    },
    query: { rows: [] }
  })

  assert.equal(ctx.evidence.evidenceId, 'EV-TOP-001')
})

test('S3-53：顶层 evidenceId 缺失时回退 explanation.evidence.evidenceId', () => {
  const ctx = buildAiEvidenceContext({
    explanation: {
      evidence: {
        evidenceId: 'EV-NESTED-002',
        snapshotId: 'S20260916_02'
      }
    },
    query: { rows: [] }
  })

  assert.equal(ctx.evidence.evidenceId, 'EV-NESTED-002')
  const anchor = draftAnchor({
    evidenceId: ctx.evidence.evidenceId,
    snapshotId: ctx.snapshotId
  })
  assert.deepEqual(anchor, { kind: ANCHOR_KIND.EVIDENCE_PACKAGE, value: 'EV-NESTED-002' })
})

test('S3-53：顶层占位值 unknown 不得压住真实嵌套 evidenceId', () => {
  const ctx = buildAiEvidenceContext({
    evidenceId: 'unknown',
    explanation: {
      evidence: {
        evidenceId: 'EV-NESTED-003',
        snapshotId: 'S20260916_03'
      }
    },
    query: { rows: [] }
  })

  assert.equal(ctx.evidence.evidenceId, 'EV-NESTED-003')
})

test('S3-53：两处 evidenceId 都是占位/空值时不造证据锚点，仍回退真实 snapshot', () => {
  const ctx = buildAiEvidenceContext({
    evidenceId: 'UNKNOWN',
    explanation: {
      evidence: {
        evidenceId: '   ',
        snapshotId: 'S20260916_04'
      }
    },
    query: { rows: [] }
  })

  assert.equal(ctx.evidence.evidenceId, null)
  assert.deepEqual(
    draftAnchor({ evidenceId: ctx.evidence.evidenceId, snapshotId: ctx.snapshotId }),
    { kind: ANCHOR_KIND.SUGGESTION_SNAPSHOT, value: 'S20260916_04' }
  )
})

test('S3-53：嵌套 evidenceId 能一路进入决策草稿 evidencePackageId，不同时提交 snapshot 锚点', () => {
  const ctx = buildAiEvidenceContext({
    explanation: {
      evidence: {
        evidenceId: 'EV-NESTED-005',
        snapshotId: 'S20260916_05'
      }
    },
    query: { rows: [] }
  })

  const built = buildDraftBody({
    suggestion,
    evidenceId: ctx.evidence.evidenceId,
    snapshotId: ctx.snapshotId,
    direction: 'DOWN',
    owner: '运营-小李'
  })

  assert.equal(built.ok, true)
  assert.equal(built.body.evidencePackageId, 'EV-NESTED-005')
  assert.equal('suggestionSnapshotId' in built.body, false)
  assert.equal(built.body.targetDirection, 'DOWN')
})
