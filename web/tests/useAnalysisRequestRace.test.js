import test, { after, before } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

let vite
let useAnalysis

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
