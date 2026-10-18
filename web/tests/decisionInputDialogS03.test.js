/**
 * decisionInputDialogS03.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 浏览器虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Decisions.vue 与 Ops.vue 源码并断言结构模式，
 * 锁定 Decisions.vue 对话框模板区块锚点、字段循环、按键绑定（@keyup.esc / @keyup.enter）、取消与确定按钮、
 * 对话框核心机制函数（openInputDialog / settleInputDialog / confirmInputDialog / cancelInputDialog）、
 * 全文零 prompt() / 零 alert() 调用、并发重入 fail-closed 守卫、组件卸载结算、
 * 四调用方（submit / approve / reject / cancel）与三校验助手的新接线、fail-closed 纯函数判定仿真与反向仿真、
 * 以及 Ops.vue 独立性、Decisions.vue 既有 13 列表格结构、不含 <style>、不引入 tabindex 等范围守护。
 * 特此注明：本测试属于静态源码结构守卫与判定纯函数仿真，绝不冒充真实浏览器 DOM 渲染或真实用户交互证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const decisionsSource = readFileSync(join(root, 'src/views/Decisions.vue'), 'utf8')
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')

test('a. 对话框模板锚点：v-if="inputDialog" 根行（含 @keyup.esc="cancelInputDialog"）、v-for 字段行、v-model="field.value" 与 @keyup.enter="confirmInputDialog" 输入行、取消/确定两按钮行逐字在位', () => {
  // 1. 根元素行在位，带 v-if 与 @keyup.esc
  assert.match(
    decisionsSource,
    /<div v-if="inputDialog" class="chart-box" @keyup\.esc="cancelInputDialog">/,
    '需包含 inputDialog 根元素且带 @keyup.esc="cancelInputDialog"'
  )
  // 2. 标题行
  assert.match(
    decisionsSource,
    /<div class="chart-title">\{\{\s*inputDialog\.title\s*\}\}<\/div>/,
    '需包含 inputDialog.title 标题'
  )
  // 3. v-for 字段行
  assert.match(
    decisionsSource,
    /<div v-for="field in inputDialog\.fields" :key="field\.key" style="margin-bottom:8px">/,
    '需包含 v-for 循环字段'
  )
  // 4. 输入框行带 v-model 与 @keyup.enter
  assert.match(
    decisionsSource,
    /<input v-model="field\.value" @keyup\.enter="confirmInputDialog" \/>/,
    '输入框需绑定 v-model="field.value" 与 @keyup.enter="confirmInputDialog"'
  )
  // 5. 取消与确定两按钮行逐字在位
  assert.match(
    decisionsSource,
    /<button class="btn btn-sm" @click="cancelInputDialog">取消<\/button>/,
    '需包含取消按钮且绑定 cancelInputDialog'
  )
  assert.match(
    decisionsSource,
    /<button class="btn btn-sm btn-primary" @click="confirmInputDialog">确定<\/button>/,
    '需包含确定按钮且绑定 confirmInputDialog'
  )
})

test('b. 机制与零 prompt：Decisions.vue 全文 doesNotMatch /\\bprompt\\(/ 与 /\\balert\\(/；四对话框控制函数逐字锚点；并发重入守卫与 onUnmounted 结算行在位', () => {
  // 1. 全文无 prompt() 与 alert()
  assert.doesNotMatch(decisionsSource, /\bprompt\(/, 'Decisions.vue 全文不得存在 prompt() 调用')
  assert.doesNotMatch(decisionsSource, /\balert\(/, 'Decisions.vue 全文不得存在 alert() 调用')

  // 2. 四对话框控制函数逐字锚点
  assert.match(decisionsSource, /function openInputDialog\(\{ title, fields \}\) \{/)
  assert.match(decisionsSource, /function settleInputDialog\(values\) \{/)
  assert.match(decisionsSource, /function confirmInputDialog\(\) \{/)
  assert.match(decisionsSource, /function cancelInputDialog\(\) \{/)

  // 3. 并发重入守卫：已打开时直接返回 Promise.resolve(null)，保护在途 resolve 不被覆盖
  assert.match(
    decisionsSource,
    /if \(inputDialog\.value\) return Promise\.resolve\(null\)/,
    'openInputDialog 需包含并发重入 fail-closed 守卫'
  )

  // 4. onUnmounted 结算在途对话框为 null
  assert.match(
    decisionsSource,
    /onUnmounted\(\(\) => \{\s*decisionFetchSeq \+= 1\s*settleInputDialog\(null\)\s*cancel\(\)\s*\}\)/,
    'onUnmounted 需在组件卸载时结算在途对话框'
  )
})

test('c. fail-closed 判定仿真：纯函数复刻「打开→回执（对象/null）→校验」链——取消/空白/非法日期 fail-closed，反向仿真断言缺失监听或字段不足被检出', () => {
  // 仿真三校验助手与流程逻辑
  function validateReason(raw, actionLabel) {
    if (raw === null || raw === undefined) return { ok: false, error: null, reason: null }
    const reason = String(raw).trim()
    if (!reason) return { ok: false, error: `${actionLabel}原因不能为空`, reason: null }
    return { ok: true, error: null, reason }
  }

  function validateApprovalText(raw, emptyMessage) {
    if (raw === null || raw === undefined) return { ok: false, error: null, value: null }
    const value = String(raw).trim()
    if (!value) return { ok: false, error: emptyMessage, value: null }
    return { ok: true, error: null, value }
  }

  function validateDueDate(rawValue) {
    const dueDate = (rawValue || '').trim()
    if (!dueDate) return { ok: false, error: '截止日期不能为空', dueDate: null }
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dueDate)
    if (!match) return { ok: false, error: '截止日期格式必须为 YYYY-MM-DD', dueDate: null }
    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])
    const parsed = new Date(Date.UTC(year, month - 1, day))
    if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) {
      return { ok: false, error: '截止日期不是有效日历日期', dueDate: null }
    }
    return { ok: true, error: null, dueDate }
  }

  // 取消回执 null：四调用方均中止且不置 busy（fail-closed）
  function simulateWorkflow(actionType, dialogResult) {
    let busy = false
    let apiCalled = false
    let actionError = ''

    if (actionType === 'submit') {
      const raw = dialogResult ? dialogResult.value : null
      const res = validateApprovalText(raw, '负责人不能为空')
      if (!res.ok) {
        if (res.error) actionError = res.error
        return { aborted: true, busy, apiCalled, actionError }
      }
      busy = true
      apiCalled = true
      return { aborted: false, busy, apiCalled, actionError, payload: { owner: res.value } }
    }

    if (actionType === 'approve') {
      if (!dialogResult) return { aborted: true, busy, apiCalled, actionError }
      const owner = (dialogResult.owner || '').trim()
      if (!owner) {
        actionError = '负责人不能为空'
        return { aborted: true, busy, apiCalled, actionError }
      }
      const dueRes = validateDueDate(dialogResult.dueDate)
      if (!dueRes.ok) {
        actionError = dueRes.error
        return { aborted: true, busy, apiCalled, actionError }
      }
      busy = true
      apiCalled = true
      return { aborted: false, busy, apiCalled, actionError, payload: { owner, dueDate: dueRes.dueDate } }
    }

    if (actionType === 'reject' || actionType === 'cancel') {
      const actionLabel = actionType === 'reject' ? '驳回' : '取消'
      const raw = dialogResult ? dialogResult.reason : null
      const res = validateReason(raw, actionLabel)
      if (!res.ok) {
        if (res.error) actionError = res.error
        return { aborted: true, busy, apiCalled, actionError }
      }
      busy = true
      apiCalled = true
      return { aborted: false, busy, apiCalled, actionError, payload: { reason: res.reason } }
    }
  }

  // 1. 取消时四调用方均中止且不置 busy、不调接口
  for (const act of ['submit', 'approve', 'reject', 'cancel']) {
    const res = simulateWorkflow(act, null)
    assert.equal(res.aborted, true, `${act} 取消必须中止`)
    assert.equal(res.busy, false, `${act} 取消不得置 busy`)
    assert.equal(res.apiCalled, false, `${act} 取消不得发 API 请求`)
    assert.equal(res.actionError, '', `${act} 取消不得报错误`)
  }

  // 2. 空白 owner/reason 分别得既有消息
  const submitBlank = simulateWorkflow('submit', { value: '   ' })
  assert.equal(submitBlank.aborted, true)
  assert.equal(submitBlank.actionError, '负责人不能为空')
  assert.equal(submitBlank.busy, false)

  const approveBlankOwner = simulateWorkflow('approve', { owner: '  ', dueDate: '2026-10-18' })
  assert.equal(approveBlankOwner.aborted, true)
  assert.equal(approveBlankOwner.actionError, '负责人不能为空')
  assert.equal(approveBlankOwner.busy, false)

  const rejectBlank = simulateWorkflow('reject', { reason: '   ' })
  assert.equal(rejectBlank.aborted, true)
  assert.equal(rejectBlank.actionError, '驳回原因不能为空')
  assert.equal(rejectBlank.busy, false)

  const cancelBlank = simulateWorkflow('cancel', { reason: '   ' })
  assert.equal(cancelBlank.aborted, true)
  assert.equal(cancelBlank.actionError, '取消原因不能为空')
  assert.equal(cancelBlank.busy, false)

  // 3. dueDate 三段校验（空 / 格式错 / 2026-02-30 日历错 / 合法日期）
  const dueEmpty = validateDueDate('')
  assert.equal(dueEmpty.ok, false)
  assert.equal(dueEmpty.error, '截止日期不能为空')

  const dueFmt = validateDueDate('2026/10/18')
  assert.equal(dueFmt.ok, false)
  assert.equal(dueFmt.error, '截止日期格式必须为 YYYY-MM-DD')

  const dueCal = validateDueDate('2026-02-30')
  assert.equal(dueCal.ok, false)
  assert.equal(dueCal.error, '截止日期不是有效日历日期')

  const dueValid = validateDueDate('  2026-10-18  ')
  assert.equal(dueValid.ok, true)
  assert.equal(dueValid.dueDate, '2026-10-18')

  // 4. 反向仿真：静态检查检测机制
  function verifyDialogTemplate(src) {
    const issues = []
    if (!/@keyup\.esc="cancelInputDialog"/.test(src)) {
      issues.push('MISSING_ESC')
    }
    if (!/<button[^>]*@click="confirmInputDialog"[^>]*>确定<\/button>/.test(src)) {
      issues.push('CONFIRM_MISBOUND')
    }
    const approveMatch = src.match(/async function approve\(d\) \{[\s\S]*?fields:\s*\[([\s\S]*?)\]/)
    if (!approveMatch) {
      issues.push('APPROVE_NOT_FOUND')
    } else {
      const fieldsText = approveMatch[1]
      const hasOwner = fieldsText.includes("key: 'owner'")
      const hasDue = fieldsText.includes("key: 'dueDate'")
      if (!hasOwner || !hasDue) {
        issues.push('APPROVE_FIELDS_INCOMPLETE')
      }
    }
    return issues
  }

  // 真实源码校验通过
  const realIssues = verifyDialogTemplate(decisionsSource)
  assert.deepEqual(realIssues, [], 'Decisions.vue 真实源码必须无任何模板与字段缺陷')

  // 反向仿真变体检测
  const mutNoEsc = decisionsSource.replace('@keyup.esc="cancelInputDialog"', '')
  assert.ok(verifyDialogTemplate(mutNoEsc).includes('MISSING_ESC'))

  const mutWrongConfirm = decisionsSource.replace('@click="confirmInputDialog">确定', '@click="cancelInputDialog">确定')
  assert.ok(verifyDialogTemplate(mutWrongConfirm).includes('CONFIRM_MISBOUND'))

  const mutSingleFieldApprove = decisionsSource.replace(
    "{ key: 'dueDate', label: '截止日期（必填，格式 YYYY-MM-DD）', initialValue: '' }",
    ''
  )
  assert.ok(verifyDialogTemplate(mutSingleFieldApprove).includes('APPROVE_FIELDS_INCOMPLETE'))
})

test('d. 调用方接线与顺序：submit / approve / reject / cancel 接线正确、busy 时机前置输入之后、载荷一致；act / evaluate 逐字保留', () => {
  // 1. submitDecision 接线与顺序
  const submitBody = decisionsSource.slice(
    decisionsSource.indexOf('async function submitDecision(d)'),
    decisionsSource.indexOf('async function approve(d)')
  )
  assert.match(
    submitBody,
    /await requiredApprovalText\('负责人（提交审核前必填）', '负责人不能为空', currentOwner\)/
  )
  const submitReqAt = submitBody.indexOf('await requiredApprovalText')
  const submitBusyAt = submitBody.indexOf('busy.value = true')
  const submitApiAt = submitBody.indexOf("api.decisionAction(d.id, 'submit', { owner })")
  assert.ok(submitReqAt >= 0 && submitBusyAt > submitReqAt && submitApiAt > submitBusyAt)

  // 2. approve 单次 openInputDialog 且采集 owner 与 dueDate 双字段
  const approveBody = decisionsSource.slice(
    decisionsSource.indexOf('async function approve(d)'),
    decisionsSource.indexOf('async function rejectDecision(d)')
  )
  const openDialogMatches = approveBody.match(/openInputDialog\(/g) || []
  assert.equal(openDialogMatches.length, 1, 'approve 必须且仅能调用一次 openInputDialog')
  const ownerLabelAt = approveBody.indexOf("label: '负责人（必填，例如：运营-小李）'")
  const dueLabelAt = approveBody.indexOf("label: '截止日期（必填，格式 YYYY-MM-DD）'")
  const approveBusyAt = approveBody.indexOf('busy.value = true')
  const approveApiAt = approveBody.indexOf("api.decisionAction(d.id, 'approve', { owner, dueDate })")
  assert.ok(ownerLabelAt >= 0 && dueLabelAt > ownerLabelAt)
  assert.ok(approveBusyAt > dueLabelAt, '两项输入与校验完成前不能置 busy')
  assert.ok(approveApiAt > approveBusyAt, '置 busy 前不能发请求')

  // 3. rejectDecision 与 cancelDecision 接线
  const rejectBody = decisionsSource.slice(
    decisionsSource.indexOf('async function rejectDecision(d)'),
    decisionsSource.indexOf('async function cancelDecision(d)')
  )
  assert.match(rejectBody, /const reason = await requiredReason\('驳回'\)/)
  assert.match(rejectBody, /api\.decisionAction\(d\.id, 'reject', \{ reason \}\)/)
  const rejectReqAt = rejectBody.indexOf('await requiredReason')
  const rejectBusyAt = rejectBody.indexOf('busy.value = true')
  assert.ok(rejectBusyAt > rejectReqAt)

  const cancelBody = decisionsSource.slice(
    decisionsSource.indexOf('async function cancelDecision(d)'),
    decisionsSource.indexOf('async function evaluate(d)')
  )
  assert.match(cancelBody, /const reason = await requiredReason\('取消'\)/)
  assert.match(cancelBody, /api\.decisionAction\(d\.id, 'cancel', \{ reason \}\)/)
  const cancelReqAt = cancelBody.indexOf('await requiredReason')
  const cancelBusyAt = cancelBody.indexOf('busy.value = true')
  assert.ok(cancelBusyAt > cancelReqAt)

  // 4. act 与 evaluate 关键行逐字保留
  assert.match(decisionsSource, /async function act\(d, action\) \{/)
  assert.match(decisionsSource, /await api\.decisionAction\(d\.id, action, \{\}\)/)
  assert.match(decisionsSource, /async function evaluate\(d\) \{/)
  assert.match(decisionsSource, /const result = await api\.decisionAction\(d\.id, 'evaluate', \{\}\)/)
  assert.match(
    decisionsSource,
    /evaluations\.value = \{ \.\.\.evaluations\.value, \[d\.id\]: mapEvaluationRows\(\[result\]\)\[0\] \}/
  )
})

test('e. 范围守护：Ops.vue 内 prompt( 恰 1 处；Decisions.vue 表格与 13 列结构不变；不含 <style、不含 tabindex', () => {
  // 1. Ops.vue 内 prompt( 恰 1 处（重置密码，不在范围）
  const opsPromptMatches = opsSource.match(/\bprompt\(/g) || []
  assert.equal(opsPromptMatches.length, 1, 'Ops.vue 内 prompt( 必须恰 1 处（仅重置密码）')
  assert.match(opsSource, /const pwd = prompt\(`重置 \$\{u\.username\} 的密码（至少 6 位）`, ''\)/)

  // 2. Decisions.vue 内 table 与 13 列结构锚点不变
  assert.match(decisionsSource, /<table class="data-table">/)
  const thHeaders = [
    '编号', '标题', '来源', '目标指标', '基线',
    '建议快照', '基线快照', '口径版本', '负责人', '截止日期',
    '状态', '效果', '操作'
  ]
  const thMatches = decisionsSource.match(/<th[^>]*>[^<]*<\/th>/g) || []
  assert.equal(thMatches.length, 13, 'Decisions.vue 表头必须保留恰 13 列')
  for (const h of thHeaders) {
    assert.ok(decisionsSource.includes(`<th>${h}</th>`) || decisionsSource.includes(`>${h}</th>`), `表头必须包含 ${h}`)
  }

  // 3. Decisions.vue 不含 <style
  assert.doesNotMatch(decisionsSource, /<style/i, 'Decisions.vue 不得包含 <style 标签')

  // 4. Decisions.vue 不含 tabindex
  assert.doesNotMatch(decisionsSource, /tabindex/i, 'Decisions.vue 不得引入 tabindex')
})
