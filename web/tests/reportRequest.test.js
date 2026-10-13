import test from 'node:test'
import assert from 'node:assert/strict'
import { buildReportRequest } from '../src/utils/reportRequest.js'

const snapshot = { snapshotId: 'snapshot-17', businessDate: '2026-10-11' }

test('decision effect request sends only its decision identity', () => {
  assert.deepEqual(buildReportRequest({
    templateCode: 'DECISION_EFFECT',
    snapshot,
    decisionId: '42',
    fromDate: '2026-10-05'
  }), {
    templateCode: 'DECISION_EFFECT',
    templateVersion: '1',
    includeAiNarrative: false,
    decisionId: 42
  })
})

test('daily report request is bound to the selected snapshot and business date', () => {
  assert.deepEqual(buildReportRequest({ templateCode: 'OPS_DAILY', snapshot }), {
    templateCode: 'OPS_DAILY',
    templateVersion: '1',
    includeAiNarrative: false,
    snapshotId: 'snapshot-17',
    businessDate: '2026-10-11'
  })
})

test('weekly report request carries the explicit seven-day window', () => {
  assert.deepEqual(buildReportRequest({
    templateCode: 'OPS_WEEKLY',
    snapshot,
    fromDate: '2026-10-05'
  }), {
    templateCode: 'OPS_WEEKLY',
    templateVersion: '1',
    includeAiNarrative: false,
    snapshotId: 'snapshot-17',
    businessDate: '2026-10-11',
    fromDate: '2026-10-05',
    toDate: '2026-10-11'
  })
})
