import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSnapshotOptions, initialSnapshotId, withSnapshotId } from '../src/utils/snapshotOptions.js'

test('selector contains only published, source-bound snapshots and uses safe display labels', () => {
  const options = buildSnapshotOptions([
    { snapshotId: 'S20260918_3', sourceId: 2, status: 'ACTIVE', businessTime: '2026-09-18T00:00:00', definitionVersion: 'v3' },
    { snapshotId: 'S20260917_9', sourceId: 2, status: 'ARCHIVED', businessTime: '2026-09-17T00:00:00' },
    { snapshotId: 'SFAILED', sourceId: 2, status: 'FAILED', businessTime: '2026-09-18T00:00:00' },
    { snapshotId: 'SNO_SOURCE', sourceId: null, status: 'ARCHIVED', businessTime: '2026-09-18T00:00:00' },
    { snapshotId: 'SNO_DATE', sourceId: 2, status: 'ARCHIVED', businessTime: null }
  ], [
    { sourceId: 2, sourceCode: 'mall-a', displayName: '商城甲', profilePath: 'must-not-be-used' }
  ])

  assert.deepEqual(options.map(({ snapshotId, sourceId, sourceName, businessDate, status }) => ({
    snapshotId, sourceId, sourceName, businessDate, status
  })), [
    { snapshotId: 'S20260918_3', sourceId: 2, sourceName: '商城甲', businessDate: '2026-09-18', status: 'ACTIVE' },
    { snapshotId: 'S20260917_9', sourceId: 2, sourceName: '商城甲', businessDate: '2026-09-17', status: 'ARCHIVED' }
  ])
  assert.match(options[0].label, /商城甲 · 2026-09-18 · S20260918_3 · 当前/)
  assert.doesNotMatch(JSON.stringify(options), /profilePath|must-not-be-used/)
})

test('saved published snapshot is preserved; initial default never falls back to archived', () => {
  const options = [
    { snapshotId: 'OLD', status: 'ARCHIVED' },
    { snapshotId: 'ACTIVE', status: 'ACTIVE' }
  ]
  assert.equal(initialSnapshotId(options, 'OLD'), 'OLD')
  assert.equal(initialSnapshotId(options, 'gone'), 'ACTIVE')
  assert.equal(initialSnapshotId([{ snapshotId: 'OLD', status: 'ARCHIVED' }], null), '')
})

test('snapshotId is merged without dropping existing page filters', () => {
  assert.deepEqual(withSnapshotId({ from: '2026-09-01', to: '2026-09-18' }, 'S20260918_3'), {
    from: '2026-09-01', to: '2026-09-18', snapshotId: 'S20260918_3'
  })
  assert.deepEqual(withSnapshotId({ page: 2 }, null), { page: 2 })
})
