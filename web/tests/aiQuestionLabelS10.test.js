/**
 * aiQuestionLabelS10.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 AiAssistant.vue 源码并断言结构模式，
 * 锁定 AiAssistant 自然语言问数区提问输入框的无障碍命名（id 与 aria-label 补齐）、续行与按钮防回退、全页 id 唯一性与纯函数命名完整性仿真，防止无障碍与交互属性发生回退。
 * 特此注明：本测试属于静态源码结构守卫与无障碍命名判定纯函数仿真，并非真实浏览器 DOM 渲染或屏幕阅读器可访问性实测证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const aiSource = readFileSync(join(root, 'src/views/AiAssistant.vue'), 'utf8')

test('a. 提问框关联锚点：新形态整行逐字在位（id/class/v-model/@keyup.enter/placeholder/aria-label 一条正则逐字断言）', () => {
  // 1. 提问框新形态第一物理行逐字锚点（包含 id, class, v-model, @keyup.enter, placeholder, aria-label）
  assert.match(
    aiSource,
    /<input id="ai-question-input" class="input" v-model="question" @keyup\.enter="ask" placeholder="例如：最近 7 天销售额变化趋势如何？" aria-label="分析问题输入"/,
    'AiAssistant 提问框第一物理行需包含新形态逐字锚点（含 id="ai-question-input" 与 aria-label="分析问题输入"）'
  )

  // 2. 提问框两物理行连贯闭合结构在位
  assert.match(
    aiSource,
    /<input id="ai-question-input" class="input" v-model="question" @keyup\.enter="ask" placeholder="例如：最近 7 天销售额变化趋势如何？" aria-label="分析问题输入"\s*:disabled="busy \|\| draftBusy" \/>/,
    'AiAssistant 提问框两物理行新形态结构必须按契约连贯闭合在位'
  )
})

test('b. 续行与按钮不回退：:disabled="busy || draftBusy" /> 续行与发送按钮行逐字锚点在位', () => {
  // 1. 提问框续行逐字锚点
  assert.match(
    aiSource,
    /:disabled="busy \|\| draftBusy" \/>/,
    '提问框续行需保留 :disabled="busy || draftBusy" />'
  )

  // 2. 发送按钮行逐字锚点
  assert.match(
    aiSource,
    /<button class="btn btn-ai" @click="handleAskAction" :disabled="draftBusy \|\| \(!busy && !question\.trim\(\)\)">/,
    '发送按钮行需保留逐字锚点（含 @click="handleAskAction" 与 :disabled 绑定）'
  )

  // 3. 发送按钮内部状态提示与闭合标签
  assert.match(
    aiSource,
    /\{\{\s*busy \? '放弃本次分析' : \(draftBusy \? '草稿创建中…' : '发送'\)\s*\}\}\s*<\/button>/,
    '发送按钮内部状态提示与闭合标签必须逐字保留'
  )

  // 4. 提问栏完整容器结构在位
  assert.match(
    aiSource,
    /<div class="chat-input-bar">\s*<input id="ai-question-input" class="input" v-model="question" @keyup\.enter="ask" placeholder="例如：最近 7 天销售额变化趋势如何？" aria-label="分析问题输入"\s*:disabled="busy \|\| draftBusy" \/>\s*<button class="btn btn-ai" @click="handleAskAction" :disabled="draftBusy \|\| \(!busy && !question\.trim\(\)\)">/,
    'chat-input-bar 容器内提问框与按钮必须按契约顺序连续排列'
  )
})

test('c. id 唯一性与零 tabindex：新 id 在 AiAssistant.vue 内以 id="..." 形态恰出现 1 次，且 AiAssistant.vue 源码不含 tabindex', () => {
  const newId = 'ai-question-input'

  // 1. 新 id 在 AiAssistant.vue 内以 id="..." 形态恰好出现 1 次
  const idRegex = new RegExp(`id="${newId}"`, 'g')
  const matches = aiSource.match(idRegex) || []
  assert.equal(
    matches.length,
    1,
    `AiAssistant.vue 内 id="${newId}" 出现次数必须恰为 1 次（实测 ${matches.length} 次）`
  )

  // 2. AiAssistant.vue 内全部 id="..." 出现的总数恰为 1（保持 AiAssistant.vue 提问框唯一 id 规范）
  const allIdMatches = aiSource.match(/\bid="[^"]+"/g) || []
  assert.equal(
    allIdMatches.length,
    1,
    `AiAssistant.vue 内 id 属性总数必须恰为 1（实测 ${allIdMatches.length} 个：${allIdMatches.join(', ')}）`
  )

  // 3. AiAssistant.vue 源码不含 tabindex 属性（保持现行零 tabindex 规范）
  assert.doesNotMatch(
    aiSource,
    /tabindex/i,
    'AiAssistant.vue 源码不得包含 tabindex 属性（保持现行零 tabindex 规范）'
  )
})

test('d. 范围守护：AiAssistant.vue 内 aria-label 总数恰为 2（既有反馈原因 select 一处逐字保留 + 本切片新增一处），且不新增 <label 元素（源码中 <label 恰 5 处、均为草稿区既有包裹式 label，逐字保留、计数不变）', () => {
  // 1. AiAssistant.vue 内 aria-label 属性总数恰为 2
  const ariaLabelMatches = aiSource.match(/\baria-label="[^"]+"/g) || []
  assert.equal(
    ariaLabelMatches.length,
    2,
    `AiAssistant.vue 内 aria-label 属性总数必须恰为 2（实测 ${ariaLabelMatches.length} 个：${ariaLabelMatches.join(', ')}）`
  )

  // 2. 既有反馈原因 select 上的 aria-label="反馈原因" 逐字保留
  assert.match(
    aiSource,
    /<select :value="feedbackReasons\[h\.id\] \|\| 'ANSWER_MATCHED_EVIDENCE'"\s+aria-label="反馈原因" @change="setFeedbackReason\(h\.id, \$event\.target\.value\)">/,
    '既有反馈原因 select 必须逐字保留 aria-label="反馈原因"'
  )

  // 3. 本切片新增提问框 aria-label="分析问题输入" 在位
  assert.match(
    aiSource,
    /aria-label="分析问题输入"/,
    '提问输入框必须包含 aria-label="分析问题输入"'
  )

  // 4. AiAssistant.vue 内 <label 元素总数恰为 5（均为草稿区既有包裹式 label）
  const labelMatches = aiSource.match(/<label\b/g) || []
  assert.equal(
    labelMatches.length,
    5,
    `AiAssistant.vue 内 <label 元素总数必须恰为 5（实测 ${labelMatches.length} 个）`
  )

  // 5. 草稿区 5 处包裹式 label 逐字在位
  assert.match(aiSource, /<label>标题<input v-model="draftForm\.title"/, '保留标题草稿包裹式 label')
  assert.match(aiSource, /<label>动作<textarea v-model="draftForm\.action"/, '保留动作草稿包裹式 label')
  assert.match(aiSource, /<label>目标指标<input v-model="draftForm\.metricCode"/, '保留目标指标草稿包裹式 label')
  assert.match(aiSource, /<label>目标方向（必选，页面不代选）\s*<select v-model="draftForm\.direction"/, '保留目标方向草稿包裹式 label')
  assert.match(aiSource, /<label>负责人（可留空，提交审批前必填）<input v-model="draftForm\.owner"/, '保留负责人草稿包裹式 label')
})

test('e. 命名完整性仿真：以纯函数复刻「提问框无障碍命名」判定，并以反向仿真断言缺失 aria-label 时会被检出', () => {
  // 1. 纯函数：复刻「提问框无障碍命名」判定逻辑
  const simulateAiQuestionAccessibility = (sourceText, expectedId = 'ai-question-input', expectedAriaLabel = '分析问题输入') => {
    const inputMatches = [...sourceText.matchAll(/<input\b([^>]*)>/g)]
    const inputs = inputMatches.map((m) => {
      const rawAttrs = m[1]
      const idMatch = rawAttrs.match(/\bid="([^"]+)"/)
      const ariaLabelMatch = rawAttrs.match(/\baria-label="([^"]+)"/)
      const vModelMatch = rawAttrs.match(/\bv-model="([^"]+)"/)
      return {
        id: idMatch ? idMatch[1] : null,
        ariaLabel: ariaLabelMatch ? ariaLabelMatch[1] : null,
        vModel: vModelMatch ? vModelMatch[1] : null,
        rawAttrs
      }
    })

    const targetInput = inputs.find((inp) => inp.id === expectedId || inp.vModel === 'question')
    const hasTarget = Boolean(targetInput)
    const hasId = Boolean(targetInput && targetInput.id === expectedId)
    const hasAriaLabel = Boolean(targetInput && targetInput.ariaLabel && targetInput.ariaLabel.trim() === expectedAriaLabel)
    const passed = Boolean(hasTarget && hasId && hasAriaLabel)

    return {
      targetInput,
      hasTarget,
      hasId,
      hasAriaLabel,
      passed
    }
  }

  // 2. 对 AiAssistant.vue 源码执行纯函数判定
  const result = simulateAiQuestionAccessibility(aiSource)
  assert.equal(result.passed, true, 'AiAssistant.vue 提问框无障碍命名仿真判定必须通过')
  assert.equal(result.hasTarget, true, '必须成功找到提问框控件')
  assert.equal(result.hasId, true, '提问框必须具备正确的 id="ai-question-input"')
  assert.equal(result.hasAriaLabel, true, '提问框必须具备非空且符合契约的 aria-label')
  assert.equal(result.targetInput.ariaLabel, '分析问题输入', 'aria-label 文案必须为「分析问题输入」')

  // 3. 反向仿真 1：缺失 aria-label 时会被检出
  const badSourceMissingAria = `<input id="ai-question-input" class="input" v-model="question" placeholder="例如：..." />`
  const badCheck1 = simulateAiQuestionAccessibility(badSourceMissingAria)
  assert.equal(badCheck1.passed, false, '缺失 aria-label 时仿真判定必须失败')
  assert.equal(badCheck1.hasAriaLabel, false, '必须准确检出缺失 aria-label')

  // 4. 反向仿真 2：缺失 id 时会被检出
  const badSourceMissingId = `<input class="input" v-model="question" aria-label="分析问题输入" />`
  const badCheck2 = simulateAiQuestionAccessibility(badSourceMissingId)
  assert.equal(badCheck2.passed, false, '缺失 id 时仿真判定必须失败')
  assert.equal(badCheck2.hasId, false, '必须准确检出缺失 id')

  // 5. 反向仿真 3：aria-label 为空白字符串时会被检出
  const badSourceEmptyAria = `<input id="ai-question-input" class="input" v-model="question" aria-label="   " />`
  const badCheck3 = simulateAiQuestionAccessibility(badSourceEmptyAria)
  assert.equal(badCheck3.passed, false, 'aria-label 为空白字符串时仿真判定必须失败')
  assert.equal(badCheck3.hasAriaLabel, false, '必须准确检出空白 aria-label')

  // 6. 反向仿真 4：aria-label 文案不符契约时会被检出
  const badSourceWrongAria = `<input id="ai-question-input" class="input" v-model="question" aria-label="错误文案" />`
  const badCheck4 = simulateAiQuestionAccessibility(badSourceWrongAria)
  assert.equal(badCheck4.passed, false, 'aria-label 文案不符契约时仿真判定必须失败')
  assert.equal(badCheck4.hasAriaLabel, false, '必须准确检出 aria-label 文案不符')
})
