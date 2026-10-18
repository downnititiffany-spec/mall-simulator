/**
 * opsDisableConfirm.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue 源码并断言结构模式与关键语句顺序，
 * 锁定 Ops 页面禁用用户时的二次确认交互安全机制，防止交互安全逻辑发生回退。
 * 特此注明：本测试属于静态源码结构守卫与决策逻辑纯函数仿真，并非真实浏览器 DOM 渲染证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const VUE = fileURLToPath(new URL('../src/views/Ops.vue', import.meta.url))
const source = readFileSync(VUE, 'utf8')

function functionBody(name, nextName) {
  const start = source.indexOf(`function ${name}`)
  const asyncStart = source.indexOf(`async function ${name}`)
  const actualStart = start >= 0 ? start : asyncStart
  assert.ok(actualStart >= 0, `必须存在 ${name}`)
  const candidates = nextName
    ? [source.indexOf(`function ${nextName}`, actualStart + 1), source.indexOf(`async function ${nextName}`, actualStart + 1)].filter((v) => v > actualStart)
    : []
  const end = candidates.length ? Math.min(...candidates) : source.length
  return source.slice(actualStart, end)
}

const toggleFn = functionBody('toggle', 'resetPwd')

test('toggle 函数体内存在 if (!enable) 分支且包含 window.confirm 与禁用确认关键文案', () => {
  assert.ok(toggleFn.length > 0, '需成功截取 toggle 函数片段')

  // 存在 if (!enable) { 分支
  assert.match(toggleFn, /if\s*\(!enable\)\s*\{/, 'toggle 函数体内必须包含 if (!enable) 分支')

  // 其内含 window.confirm(
  assert.match(toggleFn, /window\.confirm\(/, 'toggle 禁用分支必须调用 window.confirm')

  // 确认文案关键片段 确认要禁用用户 ${u.username} 吗
  assert.match(
    toggleFn,
    /确认要禁用用户\s*\$\{u\.username\}\s*吗/,
    'toggle 确认提示需包含关键文案片段「确认要禁用用户 ${u.username} 吗」'
  )

  // 完整文案校验
  assert.match(
    toggleFn,
    /确认要禁用用户 \$\{u\.username\} 吗？禁用后该用户将无法登录，重新启用前其请求会被拦截。/,
    'toggle 禁用确认提示需包含完整安全说明文案'
  )
})

test('toggle 语句顺序不变量：守卫 < confirm < confirmed拦截 < 置busy < API调用', () => {
  assert.ok(toggleFn.length > 0, '需成功截取 toggle 函数片段')

  const guardAt = toggleFn.indexOf('if (busy.value || loading.value) return')
  const confirmAt = toggleFn.indexOf('window.confirm(')
  const checkConfirmedAt = toggleFn.indexOf('if (!confirmed) return')
  const setBusyAt = toggleFn.indexOf('busy.value = true')
  const apiCallAt = toggleFn.indexOf('api.adminUserAction')

  assert.ok(guardAt >= 0, 'toggle 必须包含 busy/loading 入口守卫')
  assert.ok(confirmAt >= 0, 'toggle 必须包含 window.confirm')
  assert.ok(checkConfirmedAt >= 0, 'toggle 必须包含 if (!confirmed) return')
  assert.ok(setBusyAt >= 0, 'toggle 必须包含 busy.value = true')
  assert.ok(apiCallAt >= 0, 'toggle 必须包含 api.adminUserAction')

  // busy/loading 守卫下标 < confirm 下标 < busy.value = true 下标 < api.adminUserAction 下标
  assert.ok(guardAt < confirmAt, 'busy/loading 入口守卫必须在 confirm 之前')
  assert.ok(confirmAt < setBusyAt, 'confirm 必须在置 busy 之前')
  assert.ok(setBusyAt < apiCallAt, '置 busy 必须在 api.adminUserAction 之前')

  // if (!confirmed) return 位于 confirm 之后、置 busy 之前
  assert.ok(checkConfirmedAt > confirmAt, 'if (!confirmed) return 必须位于 confirm 之后')
  assert.ok(checkConfirmedAt < setBusyAt, 'if (!confirmed) return 必须位于置 busy 之前')
})

test('确认文案在 Ops.vue 全文恰好出现一次（仅 toggle 禁用路径，未扩散至启用或其他操作）', () => {
  const keyword = '确认要禁用用户'
  const matches = [...source.matchAll(new RegExp(keyword, 'g'))]
  assert.equal(matches.length, 1, '「确认要禁用用户」文案在 Ops.vue 全文必须恰好出现一次')

  // 确认该文案位置严格在 toggle 函数体内
  const keywordIndex = source.indexOf(keyword)
  const toggleStart = source.indexOf('async function toggle(u, enable)')
  const toggleEnd = source.indexOf('async function resetPwd(u)')
  assert.ok(
    keywordIndex > toggleStart && keywordIndex < toggleEnd,
    '确认文案必须仅出现在 toggle 函数体内'
  )

  // 确认启用路径不弹确认（toggle 内 window.confirm 仅在 !enable 分支下）
  const toggleBranchMatch = toggleFn.match(/if\s*\(!enable\)\s*\{([\s\S]*?)\}/)
  assert.ok(toggleBranchMatch, '需提取 if (!enable) 分支块')
  assert.ok(toggleBranchMatch[1].includes('window.confirm'), 'window.confirm 必须位于 if (!enable) 分支块内部')

  // 验证其他写操作（createUser, resetPwd）未误加 confirm
  const createUserFn = functionBody('createUser', 'toggle')
  assert.doesNotMatch(createUserFn, /confirm\(/, 'createUser 不应调用 confirm')
  const resetPwdFn = functionBody('resetPwd', 'init')
  assert.doesNotMatch(resetPwdFn, /confirm\(/, 'resetPwd 不应调用 confirm（已有 prompt）')
})

test('模板保留：@click="toggle(u, false)" 与 @click="toggle(u, true)" 均保留且带 :disabled="loading || busy"', () => {
  const disableClicks = [...source.matchAll(/@click="toggle\(u,\s*false\)"\s+:disabled="loading \|\| busy"/g)]
  const enableClicks = [...source.matchAll(/@click="toggle\(u,\s*true\)"\s+:disabled="loading \|\| busy"/g)]

  assert.equal(disableClicks.length, 2, '模板中需保留 2 处禁用按钮且均带有 :disabled="loading || busy"')
  assert.equal(enableClicks.length, 2, '模板中需保留 2 处启用按钮且均带有 :disabled="loading || busy"')

  // 与既有 opsInteractionHardening.test.js 守卫互证
  const totalToggleMatches = source.match(/@click="toggle\(u, (?:false|true)\)" :disabled="loading \|\| busy"/g) || []
  assert.ok(totalToggleMatches.length >= 4, 'toggle 按钮总数必须 >= 4（互证既有不变量）')
})

test('toggle 决策逻辑纯函数仿真：四种 enable/confirm 组合及前置守卫断言', () => {
  /**
   * 纯函数复刻 toggle 的交互与前置决策逻辑
   *
   * @param {Object} options
   * @param {boolean} [options.busy=false] - 是否已有操作进行中
   * @param {boolean} [options.loading=false] - 是否整页数据加载中
   * @param {boolean} options.enable - 操作目标（true 为启用，false 为禁用）
   * @param {Function} [options.confirmFn] - 确认弹窗函数 (模拟 window.confirm)
   * @returns {{ calledConfirm: boolean, confirmed: boolean|null, didRequest: boolean, busySet: boolean }}
   */
  function simulateToggleDecision({ busy = false, loading = false, enable, confirmFn } = {}) {
    let busyState = busy
    let calledConfirm = false
    let confirmedResult = null

    // 1. 首行守卫：if (busy.value || loading.value) return
    if (busyState || loading) {
      return { calledConfirm, confirmed: confirmedResult, didRequest: false, busySet: busyState }
    }

    // 2. 禁用确认：if (!enable) { const confirmed = window.confirm(...); if (!confirmed) return }
    if (!enable) {
      calledConfirm = true
      try {
        confirmedResult = typeof confirmFn === 'function' ? Boolean(confirmFn()) : false
      } catch {
        confirmedResult = false
      }
      if (!confirmedResult) {
        return { calledConfirm, confirmed: confirmedResult, didRequest: false, busySet: busyState }
      }
    }

    // 3. 置 busy 并发送请求
    busyState = true
    return { calledConfirm, confirmed: confirmedResult, didRequest: true, busySet: busyState }
  }

  // 组合 1：(enable=true, 未弹确认) → 发请求
  const r1 = simulateToggleDecision({
    enable: true,
    confirmFn: () => { throw new Error('启用路径绝不应触发 confirm') }
  })
  assert.equal(r1.calledConfirm, false, '启用路径不得弹出确认弹窗')
  assert.equal(r1.confirmed, null, '未弹确认时 confirmed 为 null')
  assert.equal(r1.didRequest, true, '启用路径应直接发送请求')
  assert.equal(r1.busySet, true, '启用路径应置 busy 为 true')

  // 组合 2：(enable=false, confirm=true) → 发请求
  const r2 = simulateToggleDecision({
    enable: false,
    confirmFn: () => true
  })
  assert.equal(r2.calledConfirm, true, '禁用路径必须弹出确认弹窗')
  assert.equal(r2.confirmed, true, '确认结果为 true')
  assert.equal(r2.didRequest, true, '用户确认后应发送请求')
  assert.equal(r2.busySet, true, '用户确认后应置 busy 为 true')

  // 组合 3：(enable=false, confirm=false) → 不发请求且不置 busy
  const r3 = simulateToggleDecision({
    enable: false,
    confirmFn: () => false
  })
  assert.equal(r3.calledConfirm, true, '禁用路径必须弹出确认弹窗')
  assert.equal(r3.confirmed, false, '确认结果为 false')
  assert.equal(r3.didRequest, false, '用户取消时不得发送请求')
  assert.equal(r3.busySet, false, '用户取消时不得置 busy（保持 false）')

  // 组合 4：(enable=false, confirm 未调用/异常缺省) → 按取消处理不发请求且不置 busy
  const r4Default = simulateToggleDecision({
    enable: false
    // confirmFn 缺省未传入
  })
  assert.equal(r4Default.calledConfirm, true, '仍尝试调用 confirm')
  assert.equal(r4Default.confirmed, false, '缺失时缺省处理为 false（取消）')
  assert.equal(r4Default.didRequest, false, '缺省取消不得发送请求')
  assert.equal(r4Default.busySet, false, '缺省取消不得置 busy')

  const r4Error = simulateToggleDecision({
    enable: false,
    confirmFn: () => { throw new Error('Window confirm error') }
  })
  assert.equal(r4Error.calledConfirm, true)
  assert.equal(r4Error.confirmed, false, '弹窗异常兜底为取消')
  assert.equal(r4Error.didRequest, false, '异常取消不得发送请求')
  assert.equal(r4Error.busySet, false, '异常取消不得置 busy')

  // 补充前置守卫：busy 或 loading 时在 confirm 前短路
  const rBusy = simulateToggleDecision({ busy: true, enable: false, confirmFn: () => true })
  assert.equal(rBusy.calledConfirm, false, 'busy 时不得弹窗')
  assert.equal(rBusy.didRequest, false)

  const rLoading = simulateToggleDecision({ loading: true, enable: false, confirmFn: () => true })
  assert.equal(rLoading.calledConfirm, false, 'loading 时不得弹窗')
  assert.equal(rLoading.didRequest, false)
})
