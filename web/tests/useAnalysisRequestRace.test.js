import test, { after, before } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

let vite
let useAnalysis
let snapshotSelection
let selectExplicitSnapshot
let clearExplicitSnapshot

before(async () => {
  // The application uses Vite's browser-style extensionless imports. Load the real
  // composable through Vite rather than duplicating/translating its implementation.
  vite = await createServer({
    configFile: false,
    root: process.cwd(),
    server: { middlewareMode: true },
    appType: 'custom'
  })
  ;({ useAnalysis } = await vite.ssrLoadModule('/src/composables/useAnalysis.js'))
  ;({ snapshotSelection, selectExplicitSnapshot, clearExplicitSnapshot } = await vite.ssrLoadModule('/src/composables/useSnapshotSelection.js'))
})

after(async () => {
  if (vite) await vite.close()
})

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

test('useAnalysis：较早请求最后返回时，不覆盖较新请求的快照和数据', async () => {
  const first = deferred()
  const second = deferred()
  const requests = []
  const analysis = useAnalysis({
    fetcher: (params, signal) => {
      requests.push({ params, signal })
      // 刻意不因 AbortSignal 拒绝：模拟不支持取消或取消来不及生效的传输层。
      return params.requestId === 'first' ? first.promise : second.promise
    },
    rowKeys: ['rows'],
    defaults: []
  })

  const firstLoad = analysis.load({ requestId: 'first' })
  const secondLoad = analysis.load({ requestId: 'second' })

  assert.equal(requests.length, 2)
  assert.equal(requests[0].signal.aborted, true, '新请求应尝试取消旧请求')
  assert.equal(requests[1].signal.aborted, false)

  second.resolve({ snapshotId: 'S-new', data: { rows: [{ id: 'new' }] } })
  await secondLoad
  assert.equal(analysis.context.value.snapshotId, 'S-new')
  assert.deepEqual(analysis.data.value.rows, [{ id: 'new' }])

  first.resolve({ snapshotId: 'S-old', data: { rows: [{ id: 'old' }] } })
  await firstLoad

  assert.equal(analysis.context.value.snapshotId, 'S-new', '旧响应不得替换新快照')
  assert.deepEqual(analysis.data.value.rows, [{ id: 'new' }], '旧响应不得替换新数据')
  assert.equal(analysis.requestStatus.value, 'ready')
})

test('useAnalysis：cancel 后返回的在途响应不得写入状态', async () => {
  const request = deferred()
  const analysis = useAnalysis({
    fetcher: () => request.promise,
    rowKeys: ['rows'],
    defaults: []
  })

  const loading = analysis.load()
  analysis.cancel()
  request.resolve({ snapshotId: 'S-cancelled', data: { rows: [{ id: 'late' }] } })
  await loading

  assert.equal(analysis.context.value, null)
  assert.deepEqual(analysis.data.value, [])
  assert.equal(analysis.requestStatus.value, 'loading', '取消不应伪装成成功或失败')
})

test('useAnalysis：固定快照响应 ID 不匹配时拒绝写入数据', async () => {
  snapshotSelection.initialized = true
  snapshotSelection.selectedSnapshotId = 'S-selected'
  const analysis = useAnalysis({
    fetcher: async (params) => {
      assert.equal(params.snapshotId, 'S-selected')
      return { snapshotId: 'S-other', data: { rows: [{ id: 'wrong' }] } }
    },
    rowKeys: ['rows'],
    defaults: [],
    pinSnapshot: true
  })

  await analysis.load()

  assert.equal(analysis.requestStatus.value, 'error')
  assert.equal(analysis.context.value, null)
  assert.deepEqual(analysis.data.value, [])
  assert.equal(analysis.error.value, '分析数据快照与当前选择不一致，已拒绝显示。')
})

test('useAnalysis：固定快照响应缺少 snapshotId 时拒绝写入数据', async () => {
  snapshotSelection.initialized = true
  snapshotSelection.selectedSnapshotId = 'S-selected'
  const analysis = useAnalysis({
    fetcher: async () => ({ data: { rows: [{ id: 'unbound' }] } }),
    rowKeys: ['rows'],
    defaults: [],
    pinSnapshot: true
  })

  await analysis.load()

  assert.equal(analysis.requestStatus.value, 'error')
  assert.equal(analysis.context.value, null)
  assert.deepEqual(analysis.data.value, [])
})

test('useAnalysis：固定快照响应 ID 匹配时正常写入数据', async () => {
  snapshotSelection.initialized = true
  snapshotSelection.selectedSnapshotId = 'S-selected'
  const analysis = useAnalysis({
    fetcher: async () => ({ snapshotId: 'S-selected', data: { rows: [{ id: 'right' }] } }),
    rowKeys: ['rows'],
    defaults: [],
    pinSnapshot: true
  })

  await analysis.load()

  assert.equal(analysis.requestStatus.value, 'ready')
  assert.equal(analysis.context.value.snapshotId, 'S-selected')
  assert.deepEqual(analysis.data.value.rows, [{ id: 'right' }])
})

test('useAnalysis：显式归档 snapshotId 优先于当前 profile 选择且精确请求', async () => {
  snapshotSelection.initialized = true
  snapshotSelection.selectedSnapshotId = 'S-current-active'
  selectExplicitSnapshot('  S-other-profile-archived  ')
  let actualParams
  const analysis = useAnalysis({
    fetcher: async (params) => {
      actualParams = params
      return { snapshotId: 'S-other-profile-archived', data: { rows: [{ id: 'archive' }] } }
    },
    rowKeys: ['rows'],
    defaults: [],
    pinSnapshot: true
  })

  await analysis.load({ from: '2026-10-01', to: '2026-10-02' })

  assert.deepEqual(actualParams, {
    from: '2026-10-01', to: '2026-10-02', snapshotId: 'S-other-profile-archived'
  })
  assert.equal(snapshotSelection.selectedSnapshotId, 'S-current-active', '显式历史 ID 不改写当前 profile 选择')
  assert.equal(analysis.context.value.snapshotId, 'S-other-profile-archived')
  assert.equal(analysis.requestStatus.value, 'ready')
  clearExplicitSnapshot()
})

test('显式历史 snapshotId 校验输入且清除后不残留', () => {
  snapshotSelection.explicitSnapshotId = ''
  assert.throws(() => selectExplicitSnapshot('  '), /有效的快照 ID/)
  assert.throws(() => selectExplicitSnapshot('S'.repeat(257)), /有效的快照 ID/)
  assert.equal(snapshotSelection.explicitSnapshotId, '')
  assert.equal(selectExplicitSnapshot('S-ARCHIVE'), 'S-ARCHIVE')
  clearExplicitSnapshot()
  assert.equal(snapshotSelection.explicitSnapshotId, '')
})
