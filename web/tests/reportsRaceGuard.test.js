/**
 * reportsRaceGuard.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Reports.vue 源码并断言结构模式，
 * 锁定 Reports 详情/制品/决策候选竞态守卫与组件卸载失效机制，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与纯函数序号竞态逻辑仿真，并非真实浏览器 DOM 渲染证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const page = readFileSync(join(root, 'src/views/Reports.vue'), 'utf8')

const openReportMatch = page.match(/async function openReport\([\s\S]*?\n\}/)
const openReportSnippet = openReportMatch ? openReportMatch[0] : ''

const loadArtifactMatch = page.match(/async function loadArtifact\([\s\S]*?\n\}/)
const loadArtifactSnippet = loadArtifactMatch ? loadArtifactMatch[0] : ''

const loadDecisionOptionsMatch = page.match(/async function loadDecisionOptions\([\s\S]*?\n\}/)
const loadDecisionOptionsSnippet = loadDecisionOptionsMatch ? loadDecisionOptionsMatch[0] : ''

const loadPageMatch = page.match(/async function loadPage\([\s\S]*?\n\}/)
const loadPageSnippet = loadPageMatch ? loadPageMatch[0] : ''

const onUnmountedMatch = page.match(/onUnmounted\(\(\)\s*=>\s*\{[\s\S]*?\}\)/)
const onUnmountedSnippet = onUnmountedMatch ? onUnmountedMatch[0] : ''

test('import 声明与 onUnmounted 卸载守卫：引入 onUnmounted 且卸载时递增全部四个请求序号 (规约 2.a)', () => {
  // 2.a. import 含 onUnmounted
  assert.match(
    page,
    /import\s*\{[^}]*\bonUnmounted\b[^}]*\}\s*from\s*['"]vue['"]/,
    "Reports.vue 需从 'vue' 导入 onUnmounted"
  )

  // 2.a. 页面需声明全部四个序号变量
  assert.match(page, /let requestSequence = 0/, '需声明 requestSequence 序号变量')
  assert.match(page, /let detailSequence = 0/, '需声明 detailSequence 序号变量')
  assert.match(page, /let artifactSequence = 0/, '需声明 artifactSequence 序号变量')
  assert.match(page, /let decisionSequence = 0/, '需声明 decisionSequence 序号变量')

  // 2.a. onUnmounted 块内四个序号均有递增失效语句
  assert.ok(onUnmountedSnippet.length > 0, '需成功截取 onUnmounted 函数块')
  assert.match(onUnmountedSnippet, /requestSequence \+= 1/, 'onUnmounted 需递增 requestSequence 使在途列表请求失效')
  assert.match(onUnmountedSnippet, /detailSequence \+= 1/, 'onUnmounted 需递增 detailSequence 使在途详情请求失效')
  assert.match(onUnmountedSnippet, /artifactSequence \+= 1/, 'onUnmounted 需递增 artifactSequence 使在途制品请求失效')
  assert.match(onUnmountedSnippet, /decisionSequence \+= 1/, 'onUnmounted 需递增 decisionSequence 使在途决策请求失效')
})

test('openReport 详情竞态守卫：包含 detailSequence、失效制品、响应校验与失败清空守护 (规约 2.b)', () => {
  assert.ok(openReportSnippet.length > 0, '需成功截取 openReport 函数片段')

  // 2.b. const sequence = ++detailSequence 与 artifactSequence += 1
  assert.match(
    openReportSnippet,
    /const sequence = \+\+detailSequence/,
    'openReport 需包含 const sequence = ++detailSequence'
  )
  assert.match(
    openReportSnippet,
    /artifactSequence \+= 1/,
    'openReport 切换报告时需递增 artifactSequence 使在途制品请求失效'
  )

  // 2.b. 成功赋值前 if (sequence !== detailSequence) return 与 selectedReport 成功赋值
  assert.match(
    openReportSnippet,
    /if \(sequence !== detailSequence\) return[\s\r\n]*selectedReport\.value = detail/,
    'openReport 成功赋值前需校验序号并丢弃旧响应'
  )

  // 2.b. catch 内同款守卫且保留失败清空 selectedReport.value = null 与 detailError 赋值 (裁决 2)
  assert.match(
    openReportSnippet,
    /catch\s*\((?:error)?\)\s*\{[\s\S]*?if \(sequence !== detailSequence\) return[\s\S]*?selectedReport\.value = null[\s\S]*?detailError\.value = error\?\.message \|\| '读取报告详情失败'/,
    'openReport catch 块需校验序号，最新失败清空 selectedReport 并赋 detailError (裁决 2)'
  )

  // 2.b. finally 内 if (sequence === detailSequence) detailLoading.value = false 条件复位
  assert.match(
    openReportSnippet,
    /finally\s*\{[\s\S]*?if \(sequence === detailSequence\) detailLoading\.value = false[\s\S]*?\}/,
    'openReport finally 需仅在最新请求完成时复位 detailLoading'
  )
})

test('loadArtifact 制品竞态守卫：单次捕获 reportId、artifactSequence 校验与条件复位 (规约 2.c)', () => {
  assert.ok(loadArtifactSnippet.length > 0, '需成功截取 loadArtifact 函数片段')

  // 2.c. 先捕获 const reportId = selectedReport.value.report.id 与 const sequence = ++artifactSequence
  assert.match(
    loadArtifactSnippet,
    /const reportId = selectedReport\.value\.report\.id/,
    'loadArtifact 需在异步调用前捕获 reportId'
  )
  assert.match(
    loadArtifactSnippet,
    /const sequence = \+\+artifactSequence/,
    'loadArtifact 需包含 const sequence = ++artifactSequence'
  )
  assert.match(
    loadArtifactSnippet,
    /await api\.reportArtifact\(reportId\)/,
    'loadArtifact 需将捕获的 reportId 传入 api.reportArtifact'
  )

  // 2.c. 片段内不再出现第二次 selectedReport.value.report.id 读取
  const reportIdReads = loadArtifactSnippet.match(/selectedReport\.value\.report\.id/g) || []
  assert.equal(
    reportIdReads.length,
    1,
    'loadArtifact 片段内 selectedReport.value.report.id 只能读取一次并赋值给 local variable'
  )
  assert.doesNotMatch(
    loadArtifactSnippet,
    /api\.reportArtifact\(selectedReport/,
    'loadArtifact 不得在 api.reportArtifact 中再次动态读取 selectedReport'
  )

  // 2.c. 成功/catch/finally 三处序号校验
  assert.match(
    loadArtifactSnippet,
    /if \(sequence !== artifactSequence\) return[\s\r\n]*artifact\.value = result/,
    'loadArtifact 成功赋值前需校验序号 if (sequence !== artifactSequence) return'
  )
  assert.match(
    loadArtifactSnippet,
    /catch\s*\((?:error)?\)\s*\{[\s\S]*?if \(sequence !== artifactSequence\) return[\s\S]*?detailError\.value = error\?\.message \|\| '读取报告制品失败'/,
    'loadArtifact catch 需校验序号 if (sequence !== artifactSequence) return'
  )
  assert.match(
    loadArtifactSnippet,
    /finally\s*\{[\s\S]*?if \(sequence === artifactSequence\) detailLoading\.value = false[\s\S]*?\}/,
    'loadArtifact finally 需条件复位 if (sequence === artifactSequence) detailLoading.value = false'
  )
})

test('loadDecisionOptions 决策候选竞态守卫：decisionSequence 并发后校验、异常校验与条件复位 (规约 2.d)', () => {
  assert.ok(loadDecisionOptionsSnippet.length > 0, '需成功截取 loadDecisionOptions 函数片段')

  // 2.d. 首行插入 const sequence = ++decisionSequence
  assert.match(
    loadDecisionOptionsSnippet,
    /async function loadDecisionOptions[^{]*\{[\s\r\n]*const sequence = \+\+decisionSequence/,
    'loadDecisionOptions 函数首行需自增获取 decisionSequence 序号'
  )

  // 2.d. await forEachConcurrent 块之后、写入 decisionOptions.value 之前守卫
  assert.match(
    loadDecisionOptionsSnippet,
    /await forEachConcurrent\([\s\S]*?\)\s*if \(sequence !== decisionSequence\) return[\s\S]*?decisionOptions\.value =/,
    'loadDecisionOptions 需在 forEachConcurrent 完成后、写入 decisionOptions 前校验序号'
  )

  // 2.d. catch 守卫
  assert.match(
    loadDecisionOptionsSnippet,
    /catch\s*\((?:error)?\)\s*\{[\s\r\n]*if \(sequence !== decisionSequence\) return[\s\S]*?decisionError\.value = error\?\.message \|\| '未知错误'/,
    'loadDecisionOptions catch 块首行需校验序号 if (sequence !== decisionSequence) return'
  )

  // 2.d. finally 条件复位
  assert.match(
    loadDecisionOptionsSnippet,
    /finally\s*\{[\s\S]*?if \(sequence === decisionSequence\) decisionLoading\.value = false[\s\S]*?\}/,
    'loadDecisionOptions finally 需条件复位 if (sequence === decisionSequence) decisionLoading.value = false'
  )
})

test('loadPage 既有列表序号守卫不回退：保持 requestSequence 递增校验与 finally 条件复位 (规约 2.e)', () => {
  assert.ok(loadPageSnippet.length > 0, '需成功截取 loadPage 函数片段')

  // 2.e. 仍含 const sequence = ++requestSequence 与 finally 条件复位
  assert.match(
    loadPageSnippet,
    /const sequence = \+\+requestSequence/,
    'loadPage 需维护请求序号 const sequence = ++requestSequence'
  )
  assert.match(
    loadPageSnippet,
    /if \(sequence !== requestSequence\) return[\s\r\n]*reports\.value = result\.items/,
    'loadPage 成功赋值前需校验序号 if (sequence !== requestSequence) return'
  )
  assert.match(
    loadPageSnippet,
    /catch\s*\((?:error)?\)\s*\{[\s\S]*?if \(sequence !== requestSequence\) return[\s\S]*?listError\.value = error\?\.message \|\| '未知错误'/,
    'loadPage catch 内需校验序号'
  )
  assert.match(
    loadPageSnippet,
    /finally\s*\{[\s\S]*?if \(sequence === requestSequence\) loading\.value = false[\s\S]*?\}/,
    'loadPage finally 需条件复位 if (sequence === requestSequence) loading.value = false'
  )
})

test('序号竞态与卸载失效纯函数仿真：A/B 乱序覆盖丢弃、失败迟到防冲掉新详情与卸载全部丢弃 (规约 2.f)', async () => {
  // 2.f. 纯函数复刻「发起时取号、写入前验号」逻辑，覆盖 Reports 四条路径与卸载失效
  const createSimulator = () => {
    let requestSequence = 0
    let detailSequence = 0
    let artifactSequence = 0
    let decisionSequence = 0

    const state = {
      selectedReport: null,
      detailError: '',
      detailLoading: false,
      artifact: null,
      decisionOptions: [],
      decisionError: '',
      decisionLoading: false
    }

    async function openReport(report, fetchDetail) {
      const sequence = ++detailSequence
      artifactSequence += 1
      state.detailLoading = true
      state.detailError = ''
      state.artifact = null
      try {
        const detail = await fetchDetail()
        if (sequence !== detailSequence) return
        state.selectedReport = detail
      } catch (error) {
        if (sequence !== detailSequence) return
        state.selectedReport = null
        state.detailError = error?.message || '读取报告详情失败'
      } finally {
        if (sequence === detailSequence) state.detailLoading = false
      }
    }

    async function loadArtifact(fetchArtifact) {
      if (!state.selectedReport?.report?.id) return
      const reportId = state.selectedReport.report.id
      const sequence = ++artifactSequence
      state.detailLoading = true
      state.detailError = ''
      try {
        const result = await fetchArtifact(reportId)
        if (sequence !== artifactSequence) return
        state.artifact = result
      } catch (error) {
        if (sequence !== artifactSequence) return
        state.detailError = error?.message || '读取报告制品失败'
      } finally {
        if (sequence === artifactSequence) state.detailLoading = false
      }
    }

    function unmount() {
      requestSequence += 1
      detailSequence += 1
      artifactSequence += 1
      decisionSequence += 1
    }

    return {
      state,
      openReport,
      loadArtifact,
      unmount,
      getSequences: () => ({ requestSequence, detailSequence, artifactSequence, decisionSequence })
    }
  }

  const deferred = () => {
    let resolve, reject
    const promise = new Promise((res, rej) => {
      resolve = res
      reject = rej
    })
    return { promise, resolve, reject }
  }

  // 场景 1：A 先发 B 后发、B 先返回写入成功、A 后返回被丢弃（最终详情为 B）
  {
    const sim = createSimulator()
    const defA = deferred()
    const defB = deferred()

    const pA = sim.openReport({ id: 101 }, () => defA.promise)
    const pB = sim.openReport({ id: 102 }, () => defB.promise)

    assert.equal(sim.state.detailLoading, true, '正在加载中')

    // B 先返回成功
    defB.resolve({ report: { id: 102 }, summary: { factCount: 5 } })
    await pB

    assert.deepEqual(
      sim.state.selectedReport,
      { report: { id: 102 }, summary: { factCount: 5 } },
      'B 先返回时写入详情成功'
    )
    assert.equal(sim.state.detailLoading, false, 'B 完成后 loading 应复位为 false')

    // A 后返回
    defA.resolve({ report: { id: 101 }, summary: { factCount: 1 } })
    await pA

    // 断言 A 响应被丢弃，最终详情依然为 B
    assert.deepEqual(
      sim.state.selectedReport,
      { report: { id: 102 }, summary: { factCount: 5 } },
      'A 迟到响应被丢弃，最终详情依然保持为 B'
    )
    assert.equal(sim.state.detailLoading, false, 'A 迟到处理不改变 loading 状态')
  }

  // 场景 2：其一失败迟到不得清掉已写入的新详情 (裁决 2 守护)
  {
    const sim = createSimulator()
    const defA = deferred()
    const defB = deferred()

    const pA = sim.openReport({ id: 201 }, () => defA.promise)
    const pB = sim.openReport({ id: 202 }, () => defB.promise)

    // B 成功写入新详情
    defB.resolve({ report: { id: 202 }, summary: { factCount: 9 } })
    await pB
    assert.equal(sim.state.selectedReport?.report?.id, 202, 'B 成功写入详情')

    // A 迟到失败抛错
    defA.reject(new Error('A 接口网络超时 504'))
    await pA

    // 断言 A 的失败被序号守卫丢弃：不得清空 selectedReport，不得覆盖 detailError
    assert.equal(sim.state.selectedReport?.report?.id, 202, '迟到失败不得清空最新成功写入的详情 (裁决 2)')
    assert.equal(sim.state.detailError, '', '迟到失败的 detailError 不得写入页面状态')
    assert.equal(sim.state.detailLoading, false, 'loading 依然维持 false')
  }

  // 场景 3：卸载递增后全部在途响应被丢弃
  {
    const sim = createSimulator()
    const defSuccess = deferred()
    const defFail = deferred()

    const pSuccess = sim.openReport({ id: 301 }, () => defSuccess.promise)
    assert.equal(sim.state.detailLoading, true)

    // 触发卸载
    sim.unmount()
    const seqs = sim.getSequences()
    assert.equal(seqs.detailSequence, 2, '卸载时 detailSequence 递增')
    assert.equal(seqs.requestSequence, 1, '卸载时 requestSequence 递增')
    assert.equal(seqs.artifactSequence, 2, '卸载时 artifactSequence 递增')
    assert.equal(seqs.decisionSequence, 1, '卸载时 decisionSequence 递增')

    // 卸载后成功响应到达
    defSuccess.resolve({ report: { id: 301 }, summary: {} })
    await pSuccess

    assert.equal(sim.state.selectedReport, null, '卸载后到达的成功详情响应被丢弃')
    assert.equal(sim.state.detailLoading, true, '卸载后 finally 序号校验不匹配，不回写 loading')

    // 卸载后失败响应到达
    const pFail = sim.openReport({ id: 302 }, () => defFail.promise)
    sim.unmount()
    defFail.reject(new Error('卸载后请求超时'))
    await pFail

    assert.equal(sim.state.detailError, '', '卸载后到达的失败响应被丢弃，不赋值 detailError')
    assert.equal(sim.state.selectedReport, null, '卸载后失败响应不影响 selectedReport')
  }

  // 场景 4：切换报告使在途制品请求失效
  {
    const sim = createSimulator()
    const defReport1 = deferred()
    const defArtifact1 = deferred()

    // 打开报告 1
    const pRep1 = sim.openReport({ id: 401 }, () => defReport1.promise)
    defReport1.resolve({ report: { id: 401 } })
    await pRep1

    // 发起报告 1 的制品加载
    const pArt1 = sim.loadArtifact(() => defArtifact1.promise)
    assert.equal(sim.state.detailLoading, true)

    // 用户切换到报告 2（触发 openReport，自增 artifactSequence）
    const defReport2 = deferred()
    const pRep2 = sim.openReport({ id: 402 }, () => defReport2.promise)
    defReport2.resolve({ report: { id: 402 } })
    await pRep2

    // 此时报告 1 的制品终于返回
    defArtifact1.resolve({ evidence: { facts: [{ metricCode: 'GMV', value: 100 }] } })
    await pArt1

    // 断言报告 1 的制品被丢弃，并未挂载到报告 2 上
    assert.equal(sim.state.artifact, null, '切换报告使在途制品请求失效，旧制品被丢弃')
  }
})
