/**
 * panelEscapeCloseS14.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 RuntimeProfiles.vue、Pipeline.vue 与 AiAssistant.vue 源码并断言结构模式，
 * 锁定三处展开式编辑面板（RuntimeProfiles 编辑器卡片、Pipeline 恢复表单、AiAssistant 决策草稿卡片）根元素的 Escape 关闭事件绑定（@keyup.esc 冒泡机制）、
 * 各面板既有关闭/取消按钮与守卫逻辑防回退、全仓 keyup.esc 范围与计数规范、不改动 script 内部结构及不引入全局监听守护、
 * 以及 Escape 关闭判定纯函数仿真。
 * 特此注明：本测试属于静态源码结构守卫与键盘事件关闭判定纯函数仿真，并非真实浏览器 DOM 渲染或真实浏览器键盘事件证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const rtpSource = readFileSync(join(root, 'src/views/RuntimeProfiles.vue'), 'utf8')
const pipelineSource = readFileSync(join(root, 'src/views/Pipeline.vue'), 'utf8')
const aiSource = readFileSync(join(root, 'src/views/AiAssistant.vue'), 'utf8')

test('a. RuntimeProfiles 锚点：改后根元素行逐字在位（含 !saving && closeForm() 守卫表达式）+「关闭」「取消」两按钮行逐字保留（含 :disabled="saving" 与 @click="closeForm"）+ const closeForm 定义行逐字保留', () => {
  // 1. RuntimeProfiles 编辑器卡片根元素改后形态逐字在位（含 @keyup.esc="!saving && closeForm()"）
  assert.match(
    rtpSource,
    /<section v-if="formOpen" class="card editor-card" @keyup\.esc="!saving && closeForm\(\)">/,
    'RuntimeProfiles 需包含编辑器卡片根元素且带 @keyup.esc="!saving && closeForm()"'
  )

  // 2. 「关闭」按钮逐字保留（含 :disabled="saving" 与 @click="closeForm"）
  assert.match(
    rtpSource,
    /<button class="btn btn-sm btn-ghost" :disabled="saving" @click="closeForm">关闭<\/button>/,
    'RuntimeProfiles 头部关闭按钮必须逐字保留 :disabled="saving" 与 @click="closeForm"'
  )

  // 3. 「取消」按钮逐字保留（含 :disabled="saving" 与 @click="closeForm"）
  assert.match(
    rtpSource,
    /<button type="button" class="btn btn-ghost" :disabled="saving" @click="closeForm">取消<\/button>/,
    'RuntimeProfiles 表单操作区取消按钮必须逐字保留 :disabled="saving" 与 @click="closeForm"'
  )

  // 4. const closeForm 定义行逐字保留
  assert.match(
    rtpSource,
    /const closeForm = \(\) => \{\s*formOpen\.value = false;\s*saveError\.value = ''\s*\}/,
    'RuntimeProfiles script 中必须逐字保留 const closeForm 定义'
  )
})

test('b. Pipeline 锚点：改后根元素行逐字在位 + 取消按钮行逐字保留 + closeRecoveryForm 定义与其首行 recoveryBusy 守卫逐字保留', () => {
  // 1. Pipeline 恢复表单卡片根元素改后形态逐字在位（含 @keyup.esc="closeRecoveryForm"）
  assert.match(
    pipelineSource,
    /<div v-if="recoveryForm" class="chart-box" @keyup\.esc="closeRecoveryForm">/,
    'Pipeline 需包含恢复表单根元素且带 @keyup.esc="closeRecoveryForm"'
  )

  // 2. 取消按钮行逐字保留
  assert.match(
    pipelineSource,
    /<button class="btn" @click="closeRecoveryForm" :disabled="recoveryBusy">取消<\/button>/,
    'Pipeline 恢复表单取消按钮必须逐字保留 @click="closeRecoveryForm" 与 :disabled="recoveryBusy"'
  )

  // 3. closeRecoveryForm 定义与其首行 recoveryBusy 守卫逐字保留
  assert.match(
    pipelineSource,
    /function closeRecoveryForm\(\)\s*\{\s*if\s*\(recoveryBusy\.value\)\s*return/,
    'Pipeline script 中必须保留 closeRecoveryForm 定义及首行 recoveryBusy 守卫'
  )
  assert.match(
    pipelineSource,
    /function closeRecoveryForm\(\)\s*\{\s*if\s*\(recoveryBusy\.value\)\s*return;?\s*recoveryForm\.value = null;?\s*recoveryFormError\.value = ''\s*\}/,
    'Pipeline script 中必须逐字保留 closeRecoveryForm 完整实现'
  )
})

test('c. AiAssistant 锚点：改后根元素行逐字在位 + 取消按钮行逐字保留 + closeDraft 定义与其首行 draftBusy 守卫逐字保留', () => {
  // 1. AiAssistant 决策草稿卡片根元素改后形态逐字在位（含 @keyup.esc="closeDraft"）
  assert.match(
    aiSource,
    /<div class="chart-box" v-if="draftForm" @keyup\.esc="closeDraft">/,
    'AiAssistant 需包含决策草稿卡片根元素且带 @keyup.esc="closeDraft"'
  )

  // 2. 取消按钮行逐字保留
  assert.match(
    aiSource,
    /<button :disabled="draftBusy" @click="closeDraft" style="padding:6px 16px">取消<\/button>/,
    'AiAssistant 决策草稿卡片取消按钮必须逐字保留 :disabled="draftBusy" 与 @click="closeDraft"'
  )

  // 3. closeDraft 定义与其首行 draftBusy 守卫逐字保留
  assert.match(
    aiSource,
    /function closeDraft\(\)\s*\{\s*if\s*\(draftBusy\.value\)\s*return/,
    'AiAssistant script 中必须保留 closeDraft 定义及首行 draftBusy 守卫'
  )
  assert.match(
    aiSource,
    /function closeDraft\(\)\s*\{\s*if\s*\(draftBusy\.value\)\s*return;?\s*draftForm\.value = null;?\s*draftCreated\.value = null;?\s*draftError\.value = ''\s*\}/,
    'AiAssistant script 中必须逐字保留 closeDraft 完整实现'
  )
})

test('d. 范围守护与计数：三文件各 @keyup.esc 恰 1 处；三文件均不含 keydown、不含 addEventListener 新增痕迹（以三文件 script 段不含 Escape 字样且 @keyup.esc 仅在根元素行断言）；RuntimeProfiles 既有 <form class="profile-form" 恰 1 处不变、Pipeline/AiAssistant 不含 <form', () => {
  // 1. 三文件各 @keyup.esc 恰 1 处
  const rtpEscMatches = rtpSource.match(/@keyup\.esc\b/g) || []
  assert.equal(
    rtpEscMatches.length,
    1,
    `RuntimeProfiles.vue 中 @keyup.esc 出现次数必须恰为 1（实测 ${rtpEscMatches.length} 次）`
  )

  const pipelineEscMatches = pipelineSource.match(/@keyup\.esc\b/g) || []
  assert.equal(
    pipelineEscMatches.length,
    1,
    `Pipeline.vue 中 @keyup.esc 出现次数必须恰为 1（实测 ${pipelineEscMatches.length} 次）`
  )

  const aiEscMatches = aiSource.match(/@keyup\.esc\b/g) || []
  assert.equal(
    aiEscMatches.length,
    1,
    `AiAssistant.vue 中 @keyup.esc 出现次数必须恰为 1（实测 ${aiEscMatches.length} 次）`
  )

  // 2. 三文件均不含 keydown 监听
  assert.doesNotMatch(rtpSource, /keydown/i, 'RuntimeProfiles.vue 不得包含 keydown 监听')
  assert.doesNotMatch(pipelineSource, /keydown/i, 'Pipeline.vue 不得包含 keydown 监听')
  assert.doesNotMatch(aiSource, /keydown/i, 'AiAssistant.vue 不得包含 keydown 监听')

  // 3. 三文件均不含 addEventListener 新增痕迹
  assert.doesNotMatch(rtpSource, /addEventListener/, 'RuntimeProfiles.vue 不得包含 addEventListener')
  assert.doesNotMatch(pipelineSource, /addEventListener/, 'Pipeline.vue 不得包含 addEventListener')
  assert.doesNotMatch(aiSource, /addEventListener/, 'AiAssistant.vue 不得包含 addEventListener')

  // 4. 三文件 script 段不含 Escape 字样（禁止引入 script 全局监听或键盘代码）
  const rtpScript = rtpSource.match(/<script[\s\S]*?<\/script>/)?.[0] || ''
  const pipelineScript = pipelineSource.match(/<script[\s\S]*?<\/script>/)?.[0] || ''
  const aiScript = aiSource.match(/<script[\s\S]*?<\/script>/)?.[0] || ''

  assert.doesNotMatch(rtpScript, /Escape/, 'RuntimeProfiles.vue script 段不得包含 Escape')
  assert.doesNotMatch(pipelineScript, /Escape/, 'Pipeline.vue script 段不得包含 Escape')
  assert.doesNotMatch(aiScript, /Escape/, 'AiAssistant.vue script 段不得包含 Escape')

  // 5. @keyup.esc 仅在根元素行断言
  const rtpLinesWithEsc = rtpSource.split('\n').filter((line) => line.includes('@keyup.esc'))
  assert.equal(rtpLinesWithEsc.length, 1, 'RuntimeProfiles.vue 中仅根元素行包含 @keyup.esc')
  assert.match(rtpLinesWithEsc[0], /<section v-if="formOpen" class="card editor-card" @keyup\.esc="!saving && closeForm\(\)">/)

  const pipelineLinesWithEsc = pipelineSource.split('\n').filter((line) => line.includes('@keyup.esc'))
  assert.equal(pipelineLinesWithEsc.length, 1, 'Pipeline.vue 中仅根元素行包含 @keyup.esc')
  assert.match(pipelineLinesWithEsc[0], /<div v-if="recoveryForm" class="chart-box" @keyup\.esc="closeRecoveryForm">/)

  const aiLinesWithEsc = aiSource.split('\n').filter((line) => line.includes('@keyup.esc'))
  assert.equal(aiLinesWithEsc.length, 1, 'AiAssistant.vue 中仅根元素行包含 @keyup.esc')
  assert.match(aiLinesWithEsc[0], /<div class="chart-box" v-if="draftForm" @keyup\.esc="closeDraft">/)

  // 6. RuntimeProfiles 既有 <form class="profile-form" 恰 1 处不变、Pipeline/AiAssistant 不含 <form
  const rtpForms = rtpSource.match(/<form\b/gi) || []
  assert.equal(rtpForms.length, 1, 'RuntimeProfiles.vue 中 <form 标签必须恰有 1 处（既有存量结构）')
  assert.match(rtpSource, /<form class="profile-form" @submit\.prevent="saveProfile">/)

  assert.doesNotMatch(pipelineSource, /<form\b/i, 'Pipeline.vue 源码不得包含 <form 标签')
  assert.doesNotMatch(aiSource, /<form\b/i, 'AiAssistant.vue 源码不得包含 <form 标签')
})

test('e. Escape 关闭判定仿真：以纯函数复刻「面板打开且焦点在面板内、非忙碌态按 Escape → 调用既有关闭函数关闭；忙碌态 → 不关闭」判定（按三面板各自的守卫口径建模：RTP 模板守卫 !saving、Pipeline/AiAssistant 函数内守卫），断言三面板正常态均判定关闭、三面板忙碌态均判定不关闭；并以反向仿真断言根元素缺失监听、监听绑错函数名、RTP 缺失 !saving 守卫时会被检出。只仿真判定逻辑，不声称 DOM/真实键盘事件证据', () => {
  // 纯函数：解析面板源码并仿真 Escape 键盘事件关闭判定
  const simulatePanelEscapeClose = ({
    sourceText,
    panelConfig,
    state: {
      isOpen = true,
      isFocusInside = true,
      isBusy = false,
      key = 'Escape'
    } = {}
  }) => {
    const { rootPattern, expectedHandler, guardKind, busyVar, functionGuardPattern } = panelConfig

    // 1. 定位根元素
    const rootMatch = sourceText.match(rootPattern)
    if (!rootMatch) {
      return {
        passed: false,
        closed: false,
        error: 'ROOT_ELEMENT_NOT_FOUND',
        detail: '源码中未匹配到面板根元素'
      }
    }
    const rootTag = rootMatch[0]

    // 2. 检查 @keyup.esc 属性绑定
    const escAttrMatch = rootTag.match(/@keyup\.esc="([^"]+)"/)
    if (!escAttrMatch) {
      return {
        passed: false,
        closed: false,
        error: 'MISSING_ESC_LISTENER',
        detail: '面板根元素缺失 @keyup.esc 监听属性'
      }
    }
    const rawExpression = escAttrMatch[1].trim()

    // 3. 提取绑定的 handler 与模板守卫
    let hasTemplateGuard = false
    let boundHandler = rawExpression

    if (guardKind === 'template') {
      const templateGuardRegex = new RegExp(`^!${busyVar}\\s*&&\\s*([a-zA-Z0-9_$]+)(?:\\(\\))?$`)
      const guardMatch = rawExpression.match(templateGuardRegex)
      if (guardMatch) {
        hasTemplateGuard = true
        boundHandler = guardMatch[1]
      } else {
        const nakedMatch = rawExpression.match(/^([a-zA-Z0-9_$]+)(?:\(\))?$/)
        if (nakedMatch) {
          boundHandler = nakedMatch[1]
        }
      }

      if (!hasTemplateGuard) {
        return {
          passed: false,
          closed: false,
          error: 'MISSING_TEMPLATE_GUARD',
          boundHandler,
          detail: `模板层缺失 !${busyVar} 守卫表达式`
        }
      }
    } else {
      const nakedMatch = rawExpression.match(/^([a-zA-Z0-9_$]+)(?:\(\))?$/)
      if (nakedMatch) {
        boundHandler = nakedMatch[1]
      }
    }

    // 4. 校验绑定的函数名与预期契约
    if (boundHandler !== expectedHandler) {
      return {
        passed: false,
        closed: false,
        error: 'HANDLER_NAME_MISMATCH',
        boundHandler,
        expectedHandler,
        detail: `绑定的关闭函数 [${boundHandler}] 与契约预期 [${expectedHandler}] 不符`
      }
    }

    // 5. 若守卫位于函数内部，校验 script 中函数定义首行守卫
    if (guardKind === 'function' && functionGuardPattern) {
      if (!functionGuardPattern.test(sourceText)) {
        return {
          passed: false,
          closed: false,
          error: 'MISSING_FUNCTION_GUARD',
          detail: `函数 ${expectedHandler} 内部缺失首行 ${busyVar} 守卫`
        }
      }
    }

    // 6. 仿真 Escape 键盘事件与冒泡触发判定：
    // 条件：面板打开、按键为 Escape、焦点在面板内部控件
    if (!isOpen || key !== 'Escape' || !isFocusInside) {
      return {
        passed: true,
        closed: false,
        calledHandler: null,
        reason: !isOpen ? 'PANEL_NOT_OPEN' : (key !== 'Escape' ? 'NOT_ESCAPE_KEY' : 'FOCUS_NOT_INSIDE')
      }
    }

    // 7. 守卫执行逻辑
    if (guardKind === 'template') {
      // 模板层守卫：!saving 为假时表达式短路，不调用关闭函数
      if (isBusy) {
        return {
          passed: true,
          closed: false,
          calledHandler: null,
          blockedBy: 'TEMPLATE_GUARD',
          reason: `BUSY_BLOCKED_BY_TEMPLATE_GUARD`
        }
      }
      return {
        passed: true,
        closed: true,
        calledHandler: expectedHandler,
        reason: 'CLOSED_BY_TEMPLATE_EXPRESSION'
      }
    } else {
      // 函数内守卫：调用关闭函数，函数体内首行 if (busy.value) return 拦截
      if (isBusy) {
        return {
          passed: true,
          closed: false,
          calledHandler: expectedHandler,
          blockedBy: 'FUNCTION_GUARD',
          reason: `BUSY_BLOCKED_BY_FUNCTION_GUARD`
        }
      }
      return {
        passed: true,
        closed: true,
        calledHandler: expectedHandler,
        reason: 'CLOSED_BY_FUNCTION_BODY'
      }
    }
  }

  // 三个面板的测试契约配置
  const rtpConfig = {
    panelName: 'RuntimeProfiles',
    rootPattern: /<section\b[^>]*class="card editor-card"[^>]*>/,
    expectedHandler: 'closeForm',
    guardKind: 'template',
    busyVar: 'saving'
  }

  const pipelineConfig = {
    panelName: 'Pipeline',
    rootPattern: /<div\b[^>]*v-if="recoveryForm"[^>]*class="chart-box"[^>]*>|<div\b[^>]*class="chart-box"[^>]*v-if="recoveryForm"[^>]*>/,
    expectedHandler: 'closeRecoveryForm',
    guardKind: 'function',
    busyVar: 'recoveryBusy',
    functionGuardPattern: /function closeRecoveryForm\(\)\s*\{\s*if\s*\(recoveryBusy\.value\)\s*return/
  }

  const aiConfig = {
    panelName: 'AiAssistant',
    rootPattern: /<div\b[^>]*class="chart-box"[^>]*v-if="draftForm"[^>]*>/,
    expectedHandler: 'closeDraft',
    guardKind: 'function',
    busyVar: 'draftBusy',
    functionGuardPattern: /function closeDraft\(\)\s*\{\s*if\s*\(draftBusy\.value\)\s*return/
  }

  // 1. 断言三面板正常态（打开、焦点在内、非忙碌、按 Escape）均判定关闭
  const rtpNormal = simulatePanelEscapeClose({
    sourceText: rtpSource,
    panelConfig: rtpConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: false, key: 'Escape' }
  })
  assert.equal(rtpNormal.passed, true, 'RuntimeProfiles 正常态仿真判定必须通过')
  assert.equal(rtpNormal.closed, true, 'RuntimeProfiles 正常态按 Escape 必须判定关闭')
  assert.equal(rtpNormal.calledHandler, 'closeForm', 'RuntimeProfiles 调用的关闭函数必须为 closeForm')

  const pipelineNormal = simulatePanelEscapeClose({
    sourceText: pipelineSource,
    panelConfig: pipelineConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: false, key: 'Escape' }
  })
  assert.equal(pipelineNormal.passed, true, 'Pipeline 恢复表单正常态仿真判定必须通过')
  assert.equal(pipelineNormal.closed, true, 'Pipeline 恢复表单正常态按 Escape 必须判定关闭')
  assert.equal(pipelineNormal.calledHandler, 'closeRecoveryForm', 'Pipeline 调用的关闭函数必须为 closeRecoveryForm')

  const aiNormal = simulatePanelEscapeClose({
    sourceText: aiSource,
    panelConfig: aiConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: false, key: 'Escape' }
  })
  assert.equal(aiNormal.passed, true, 'AiAssistant 决策草稿卡片正常态仿真判定必须通过')
  assert.equal(aiNormal.closed, true, 'AiAssistant 决策草稿卡片正常态按 Escape 必须判定关闭')
  assert.equal(aiNormal.calledHandler, 'closeDraft', 'AiAssistant 调用的关闭函数必须为 closeDraft')

  // 2. 断言三面板忙碌态（saving/recoveryBusy/draftBusy=true）按 Escape 均判定不关闭
  const rtpBusy = simulatePanelEscapeClose({
    sourceText: rtpSource,
    panelConfig: rtpConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: true, key: 'Escape' }
  })
  assert.equal(rtpBusy.passed, true)
  assert.equal(rtpBusy.closed, false, 'RuntimeProfiles saving=true 忙碌态按 Escape 不得关闭')
  assert.equal(rtpBusy.blockedBy, 'TEMPLATE_GUARD', 'RuntimeProfiles 必须由模板层 !saving 守卫短路拦截')

  const pipelineBusy = simulatePanelEscapeClose({
    sourceText: pipelineSource,
    panelConfig: pipelineConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: true, key: 'Escape' }
  })
  assert.equal(pipelineBusy.passed, true)
  assert.equal(pipelineBusy.closed, false, 'Pipeline recoveryBusy=true 忙碌态按 Escape 不得关闭')
  assert.equal(pipelineBusy.blockedBy, 'FUNCTION_GUARD', 'Pipeline 必须由 closeRecoveryForm 首行守卫拦截')

  const aiBusy = simulatePanelEscapeClose({
    sourceText: aiSource,
    panelConfig: aiConfig,
    state: { isOpen: true, isFocusInside: true, isBusy: true, key: 'Escape' }
  })
  assert.equal(aiBusy.passed, true)
  assert.equal(aiBusy.closed, false, 'AiAssistant draftBusy=true 忙碌态按 Escape 不得关闭')
  assert.equal(aiBusy.blockedBy, 'FUNCTION_GUARD', 'AiAssistant 必须由 closeDraft 首行守卫拦截')

  // 3. 焦点边界条件仿真：焦点不在面板内按 Escape 不触发关闭
  const rtpBlur = simulatePanelEscapeClose({
    sourceText: rtpSource,
    panelConfig: rtpConfig,
    state: { isOpen: true, isFocusInside: false, isBusy: false, key: 'Escape' }
  })
  assert.equal(rtpBlur.closed, false, '焦点不在面板内时按 Escape 不关闭（冒泡监听边界）')
  assert.equal(rtpBlur.reason, 'FOCUS_NOT_INSIDE')

  // 4. 反向仿真 1：根元素缺失监听会被检出
  const badSourceMissingEsc = '<section v-if="formOpen" class="card editor-card"><button @click="closeForm">关闭</button></section>'
  const badResult1 = simulatePanelEscapeClose({
    sourceText: badSourceMissingEsc,
    panelConfig: rtpConfig
  })
  assert.equal(badResult1.passed, false, '根元素缺失 @keyup.esc 监听时仿真判定必须失败')
  assert.equal(badResult1.error, 'MISSING_ESC_LISTENER', '必须检出缺失 @keyup.esc 监听')

  // 5. 反向仿真 2：监听绑错函数名会被检出
  const badSourceWrongHandler = '<section v-if="formOpen" class="card editor-card" @keyup.esc="!saving && wrongHandler()"><button @click="closeForm">关闭</button></section>'
  const badResult2 = simulatePanelEscapeClose({
    sourceText: badSourceWrongHandler,
    panelConfig: rtpConfig
  })
  assert.equal(badResult2.passed, false, '监听绑错函数名时仿真判定必须失败')
  assert.equal(badResult2.error, 'HANDLER_NAME_MISMATCH', '必须检出 handler 名称不匹配')
  assert.equal(badResult2.boundHandler, 'wrongHandler')

  // 6. 反向仿真 3：RTP 缺失 !saving 守卫时会被检出
  const badSourceRtpMissingGuard = '<section v-if="formOpen" class="card editor-card" @keyup.esc="closeForm()"><button @click="closeForm">关闭</button></section>'
  const badResult3 = simulatePanelEscapeClose({
    sourceText: badSourceRtpMissingGuard,
    panelConfig: rtpConfig
  })
  assert.equal(badResult3.passed, false, 'RTP 根元素缺失 !saving 守卫时仿真判定必须失败')
  assert.equal(badResult3.error, 'MISSING_TEMPLATE_GUARD', '必须检出缺失模板层 !saving 守卫')
})
