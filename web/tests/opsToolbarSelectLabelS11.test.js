/**
 * opsToolbarSelectLabelS11.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue 源码并断言结构模式，
 * 锁定 Ops 运维页六处工具栏 select 控件（用户列表排序与每页、问答审计排序与每页、调用审计排序与每页）
 * 的无障碍命名（id 与 aria-label 补齐）、属性与方法防回退、全页 id/aria-label 计数规范与纯函数命名完整性仿真。
 * 特此注明：本测试属于静态源码结构守卫与无障碍命名判定纯函数仿真，并非真实浏览器 DOM 渲染或屏幕阅读器可访问性实测证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')

test('a. 用户列表两控件锚点：userSort/userPageSize 两行新形态逐字在位（开标签 id/v-model/:disabled/@change/aria-label 正则逐字断言 + 各自 option 列表逐字保留）', () => {
  // 1. userSort 开标签新形态逐字锚点（含 id, v-model, :disabled, @change, aria-label）
  assert.match(
    opsSource,
    /<select id="ops-user-sort" v-model="userSort" :disabled="loading \|\| busy" @change="changeUserSort" aria-label="用户列表排序">/,
    'Ops 需包含用户列表排序 select 开标签且带 id="ops-user-sort" 与 aria-label="用户列表排序"'
  )

  // 2. userSort 各 option 逐字在位
  assert.match(opsSource, /<option value="id,asc">ID（升序）<\/option>/, 'userSort 必须包含 ID（升序）option')
  assert.match(opsSource, /<option value="username,asc">用户名（升序）<\/option>/, 'userSort 必须包含 用户名（升序）option')
  assert.match(opsSource, /<option value="role,asc">角色（升序）<\/option>/, 'userSort 必须包含 角色（升序）option')
  assert.match(opsSource, /<option value="createdAt,desc">创建时间（新到旧）<\/option>/, 'userSort 必须包含 创建时间（新到旧）option')

  // 3. userSort 完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-user-sort" v-model="userSort" :disabled="loading \|\| busy" @change="changeUserSort" aria-label="用户列表排序"><option value="id,asc">ID（升序）<\/option><option value="username,asc">用户名（升序）<\/option><option value="role,asc">角色（升序）<\/option><option value="createdAt,desc">创建时间（新到旧）<\/option><\/select>/,
    'Ops userSort select 开标签、四个选项与闭合标签必须逐字在位'
  )

  // 4. userPageSize 开标签新形态逐字锚点
  assert.match(
    opsSource,
    /<select id="ops-user-page-size" v-model\.number="userPageSize" :disabled="loading \|\| busy" @change="changeUserPageSize" aria-label="用户列表每页条数">/,
    'Ops 需包含用户列表每页 select 开标签且带 id="ops-user-page-size" 与 aria-label="用户列表每页条数"'
  )

  // 5. userPageSize 各 option 逐字在位
  assert.match(opsSource, /<option :value="10">10 条<\/option>/, 'userPageSize 必须包含 10 条 option')
  assert.match(opsSource, /<option :value="20">20 条<\/option>/, 'userPageSize 必须包含 20 条 option')
  assert.match(opsSource, /<option :value="50">50 条<\/option>/, 'userPageSize 必须包含 50 条 option')

  // 6. userPageSize 完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-user-page-size" v-model\.number="userPageSize" :disabled="loading \|\| busy" @change="changeUserPageSize" aria-label="用户列表每页条数"><option :value="10">10 条<\/option><option :value="20">20 条<\/option><option :value="50">50 条<\/option><\/select>/,
    'Ops userPageSize select 开标签、三个选项与闭合标签必须逐字在位'
  )
})

test('b. 审计四控件锚点：aiHistorySort/aiHistoryPageSize/aiCallSort/aiCallPageSize 四行新形态逐字在位（同上口径）', () => {
  // 1. aiHistorySort 开标签与完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-ai-history-sort" v-model="aiHistorySort" :disabled="loading" @change="changeAiHistorySort" aria-label="问答审计排序">/,
    'Ops 需包含问答审计排序 select 开标签且带 id="ops-ai-history-sort" 与 aria-label="问答审计排序"'
  )
  assert.match(
    opsSource,
    /<select id="ops-ai-history-sort" v-model="aiHistorySort" :disabled="loading" @change="changeAiHistorySort" aria-label="问答审计排序"><option value="id,desc">ID（新到旧）<\/option><option value="createdAt,desc">时间（新到旧）<\/option><option value="status,asc">状态（升序）<\/option><option value="elapsedMs,desc">耗时（高到低）<\/option><\/select>/,
    'Ops aiHistorySort select 开标签、四个选项与闭合标签必须逐字在位'
  )

  // 2. aiHistoryPageSize 开标签与完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-ai-history-page-size" v-model\.number="aiHistoryPageSize" :disabled="loading" @change="changeAiHistoryPageSize" aria-label="问答审计每页条数">/,
    'Ops 需包含问答审计每页 select 开标签且带 id="ops-ai-history-page-size" 与 aria-label="问答审计每页条数"'
  )
  assert.match(
    opsSource,
    /<select id="ops-ai-history-page-size" v-model\.number="aiHistoryPageSize" :disabled="loading" @change="changeAiHistoryPageSize" aria-label="问答审计每页条数"><option :value="10">10 条<\/option><option :value="20">20 条<\/option><option :value="50">50 条<\/option><\/select>/,
    'Ops aiHistoryPageSize select 开标签、三个选项与闭合标签必须逐字在位'
  )

  // 3. aiCallSort 开标签与完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-ai-call-sort" v-model="aiCallSort" :disabled="loading" @change="changeAiCallSort" aria-label="调用审计排序">/,
    'Ops 需包含调用审计排序 select 开标签且带 id="ops-ai-call-sort" 与 aria-label="调用审计排序"'
  )
  assert.match(
    opsSource,
    /<select id="ops-ai-call-sort" v-model="aiCallSort" :disabled="loading" @change="changeAiCallSort" aria-label="调用审计排序"><option value="id,desc">ID（新到旧）<\/option><option value="createdAt,desc">时间（新到旧）<\/option><option value="useCase,asc">用例（升序）<\/option><option value="status,asc">状态（升序）<\/option><option value="elapsedMs,desc">耗时（高到低）<\/option><\/select>/,
    'Ops aiCallSort select 开标签、五个选项与闭合标签必须逐字在位'
  )

  // 4. aiCallPageSize 开标签与完整结构在位
  assert.match(
    opsSource,
    /<select id="ops-ai-call-page-size" v-model\.number="aiCallPageSize" :disabled="loading" @change="changeAiCallPageSize" aria-label="调用审计每页条数">/,
    'Ops 需包含调用审计每页 select 开标签且带 id="ops-ai-call-page-size" 与 aria-label="调用审计每页条数"'
  )
  assert.match(
    opsSource,
    /<select id="ops-ai-call-page-size" v-model\.number="aiCallPageSize" :disabled="loading" @change="changeAiCallPageSize" aria-label="调用审计每页条数"><option :value="10">10 条<\/option><option :value="20">20 条<\/option><option :value="50">50 条<\/option><\/select>/,
    'Ops aiCallPageSize select 开标签、三个选项与闭合标签必须逐字在位'
  )
})

test('c. id 唯一性与计数：六个新 id 在 Ops.vue 内以 id="..." 形态各恰出现 1 次；Ops.vue 内 id="..." 总数恰 10、aria-label="..." 总数恰 10；Ops.vue 源码不含 tabindex', () => {
  const newIds = [
    'ops-user-sort',
    'ops-user-page-size',
    'ops-ai-history-sort',
    'ops-ai-history-page-size',
    'ops-ai-call-sort',
    'ops-ai-call-page-size'
  ]

  // 1. 六个新 id 在 Ops.vue 内以 id="..." 形态各恰好出现 1 次
  for (const id of newIds) {
    const idRegex = new RegExp(`id="${id}"`, 'g')
    const matches = opsSource.match(idRegex) || []
    assert.equal(
      matches.length,
      1,
      `Ops.vue 内 id="${id}" 出现次数必须恰为 1 次（实测 ${matches.length} 次）`
    )
  }

  // 2. Ops.vue 内全部 id="..." 出现的总数恰为 10（4 个新建用户控件 + 6 个工具栏 select）
  const allIdMatches = opsSource.match(/\bid="[^"]+"/g) || []
  assert.equal(
    allIdMatches.length,
    10,
    `Ops.vue 内 id 属性总数必须恰为 10（实测 ${allIdMatches.length} 个：${allIdMatches.join(', ')}）`
  )

  // 3. Ops.vue 内全部 aria-label="..." 出现的总数恰为 10（4 个新建用户控件 + 6 个工具栏 select）
  const allAriaLabelMatches = opsSource.match(/\baria-label="[^"]+"/g) || []
  assert.equal(
    allAriaLabelMatches.length,
    10,
    `Ops.vue 内 aria-label 属性总数必须恰为 10（实测 ${allAriaLabelMatches.length} 个：${allAriaLabelMatches.join(', ')}）`
  )

  // 4. Ops.vue 源码不含 tabindex 属性（符合规范口径，保持现行零 tabindex）
  assert.doesNotMatch(
    opsSource,
    /tabindex/i,
    'Ops.vue 源码不得包含 tabindex 属性'
  )
})

test('d. 范围守护与行为不回退：Ops.vue 内 <label 恰 8 处不变（四组包裹式 label 逐字抽锚保留）、U06-S09 新建用户四控件新形态逐字保留、六控件的 @change 处理函数名（changeUserSort/changeUserPageSize/changeAiHistorySort/changeAiHistoryPageSize/changeAiCallSort/changeAiCallPageSize）在模板与 script 中逐字保留', () => {
  // 1. Ops.vue 内 <label 元素总数恰为 8
  const labelMatches = opsSource.match(/<label\b/g) || []
  assert.equal(
    labelMatches.length,
    8,
    `Ops.vue 内 <label 元素总数必须恰为 8（实测 ${labelMatches.length} 个）`
  )

  // 2. 四组包裹式 label 逐字抽锚保留
  // 快照排序/每页
  assert.match(
    opsSource,
    /<label style="font-size:13px">排序\s*<select v-model="snapshotSort" :disabled="loading \|\| busy" @change="changeSnapshotSort" style="margin-left:6px;padding:4px">/,
    '快照排序包裹式 label 必须逐字在位'
  )
  assert.match(
    opsSource,
    /<label style="font-size:13px">每页\s*<select v-model\.number="snapshotPageSize" :disabled="loading \|\| busy" @change="changeSnapshotPageSize" style="margin-left:6px;padding:4px">/,
    '快照每页包裹式 label 必须逐字在位'
  )
  // 流水线排序/每页
  assert.match(
    opsSource,
    /<label style="font-size:13px">排序\s*<select v-model="runSort" :disabled="loading \|\| busy" @change="changeRunSort" style="margin-left:6px;padding:4px">/,
    '流水线排序包裹式 label 必须逐字在位'
  )
  assert.match(
    opsSource,
    /<label style="font-size:13px">每页\s*<select v-model\.number="runPageSize" :disabled="loading \|\| busy" @change="changeRunPageSize" style="margin-left:6px;padding:4px">/,
    '流水线每页包裹式 label 必须逐字在位'
  )
  // 采集批次排序/每页
  assert.match(
    opsSource,
    /<label style="font-size:13px">批次排序\s*<select v-model="ingestionSort" :disabled="loading \|\| busy" @change="changeIngestionSort" style="margin-left:6px;padding:4px">/,
    '采集批次排序包裹式 label 必须逐字在位'
  )
  assert.match(
    opsSource,
    /<label style="font-size:13px">每页\s*<select v-model\.number="ingestionPageSize" :disabled="loading \|\| busy" @change="changeIngestionPageSize" style="margin-left:6px;padding:4px">/,
    '采集每页包裹式 label 必须逐字在位'
  )
  // 质量排序/每页
  assert.match(
    opsSource,
    /<label style="font-size:13px">排序\s*<select v-model="qualitySort" :disabled="loading \|\| busy" @change="changeQualitySort" style="margin-left:6px;padding:4px">/,
    '数据质量排序包裹式 label 必须逐字在位'
  )
  assert.match(
    opsSource,
    /<label style="font-size:13px">每页\s*<select v-model\.number="qualityPageSize" :disabled="loading \|\| busy" @change="changeQualityPageSize" style="margin-left:6px;padding:4px">/,
    '数据质量每页包裹式 label 必须逐字在位'
  )

  // 3. U06-S09 新建用户四控件新形态逐字保留
  assert.match(
    opsSource,
    /<input id="ops-new-username" v-model="newUser\.username" placeholder="用户名" style="padding:4px" aria-label="新用户用户名" \/>/,
    'Ops 需保留用户名 input 新形态'
  )
  assert.match(
    opsSource,
    /<input id="ops-new-real-name" v-model="newUser\.realName" placeholder="姓名" style="padding:4px" aria-label="新用户姓名" \/>/,
    'Ops 需保留姓名 input 新形态'
  )
  assert.match(
    opsSource,
    /<select id="ops-new-role" v-model="newUser\.role" style="padding:4px" aria-label="新用户角色">/,
    'Ops 需保留角色 select 新形态'
  )
  assert.match(
    opsSource,
    /<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" \/>/,
    'Ops 需保留密码 input 新形态'
  )

  // 4. 六控件的 @change 处理函数名在模板与 script 中逐字保留
  const handlerNames = [
    'changeUserSort',
    'changeUserPageSize',
    'changeAiHistorySort',
    'changeAiHistoryPageSize',
    'changeAiCallSort',
    'changeAiCallPageSize'
  ]
  for (const fn of handlerNames) {
    const templateRegex = new RegExp(`@change="${fn}"`)
    assert.match(opsSource, templateRegex, `模板中必须保留 @change="${fn}"`)
    const scriptRegex = new RegExp(`function\\s+${fn}\\b`)
    assert.match(opsSource, scriptRegex, `script 中必须保留 function ${fn}`)
  }
})

test('e. 命名完整性仿真：以纯函数复刻「工具栏 select 无障碍命名」判定，并以反向仿真断言缺失 aria-label、缺失 id、文案不符时会被检出', () => {
  const EXPECTED_CONTROLS = [
    { model: 'userSort', expectedId: 'ops-user-sort', expectedAriaLabel: '用户列表排序' },
    { model: 'userPageSize', expectedId: 'ops-user-page-size', expectedAriaLabel: '用户列表每页条数' },
    { model: 'aiHistorySort', expectedId: 'ops-ai-history-sort', expectedAriaLabel: '问答审计排序' },
    { model: 'aiHistoryPageSize', expectedId: 'ops-ai-history-page-size', expectedAriaLabel: '问答审计每页条数' },
    { model: 'aiCallSort', expectedId: 'ops-ai-call-sort', expectedAriaLabel: '调用审计排序' },
    { model: 'aiCallPageSize', expectedId: 'ops-ai-call-page-size', expectedAriaLabel: '调用审计每页条数' }
  ]

  // 1. 纯函数：复刻「工具栏 select 无障碍命名」判定逻辑（按 v-model 定位六控件）
  const simulateToolbarSelectAccessibility = (sourceText, expectedItems = EXPECTED_CONTROLS) => {
    const selectMatches = [...sourceText.matchAll(/<select\b([^>]*)>/g)]
    const controls = selectMatches.map((m) => {
      const rawAttrs = m[1]
      const idMatch = rawAttrs.match(/\bid="([^"]+)"/)
      const ariaLabelMatch = rawAttrs.match(/\baria-label="([^"]+)"/)
      const vModelMatch = rawAttrs.match(/\bv-model(?:\.number)?="([^"]+)"/)
      return {
        id: idMatch ? idMatch[1] : null,
        ariaLabel: ariaLabelMatch ? ariaLabelMatch[1] : null,
        vModel: vModelMatch ? vModelMatch[1] : null,
        rawAttrs
      }
    })

    const controlsByModel = new Map()
    for (const c of controls) {
      if (c.vModel) {
        controlsByModel.set(c.vModel, c)
      }
    }

    const missingModels = []
    const missingIds = []
    const mismatchedIds = []
    const missingAriaLabels = []
    const mismatchedAriaLabels = []
    const foundAriaLabels = []
    const allAriaLabels = []

    for (const item of expectedItems) {
      const ctrl = controlsByModel.get(item.model)
      if (!ctrl) {
        missingModels.push(item.model)
        continue
      }
      if (!ctrl.id) {
        missingIds.push(item.model)
      } else if (ctrl.id !== item.expectedId) {
        mismatchedIds.push({ model: item.model, actual: ctrl.id, expected: item.expectedId })
      }

      if (ctrl.ariaLabel && ctrl.ariaLabel.trim() !== '') {
        allAriaLabels.push(ctrl.ariaLabel.trim())
      }

      if (!ctrl.ariaLabel || ctrl.ariaLabel.trim() === '') {
        missingAriaLabels.push(item.model)
      } else if (ctrl.ariaLabel.trim() !== item.expectedAriaLabel) {
        mismatchedAriaLabels.push({ model: item.model, actual: ctrl.ariaLabel.trim(), expected: item.expectedAriaLabel })
      } else {
        foundAriaLabels.push(ctrl.ariaLabel.trim())
      }
    }

    const uniqueAriaLabels = new Set(allAriaLabels)
    const hasDuplicateAriaLabels = uniqueAriaLabels.size !== allAriaLabels.length

    const passed = (
      missingModels.length === 0 &&
      missingIds.length === 0 &&
      mismatchedIds.length === 0 &&
      missingAriaLabels.length === 0 &&
      mismatchedAriaLabels.length === 0 &&
      !hasDuplicateAriaLabels
    )

    return {
      totalExpected: expectedItems.length,
      totalFound: controlsByModel.size,
      missingModels,
      missingIds,
      mismatchedIds,
      missingAriaLabels,
      mismatchedAriaLabels,
      allAriaLabels,
      foundAriaLabels,
      uniqueAriaLabels: [...uniqueAriaLabels],
      hasDuplicateAriaLabels,
      passed
    }
  }

  // 2. 对 Ops.vue 源码执行纯函数判定
  const result = simulateToolbarSelectAccessibility(opsSource)
  assert.equal(result.passed, true, 'Ops.vue 六处工具栏 select 无障碍命名仿真判定必须通过')
  assert.deepEqual(result.missingModels, [], '六个目标模型必须全部定位成功')
  assert.deepEqual(result.missingIds, [], '所有六控件必须均具有 id')
  assert.deepEqual(result.mismatchedIds, [], '所有六控件的 id 必须与契约完全一致')
  assert.deepEqual(result.missingAriaLabels, [], '所有六控件必须均具有非空 aria-label')
  assert.deepEqual(result.mismatchedAriaLabels, [], '所有六控件的 aria-label 必须与契约文案完全一致')
  assert.equal(result.hasDuplicateAriaLabels, false, '六控件的 aria-label 必须互不相同（以便辅助技术清晰区分）')
  assert.deepEqual(
    result.foundAriaLabels,
    ['用户列表排序', '用户列表每页条数', '问答审计排序', '问答审计每页条数', '调用审计排序', '调用审计每页条数'],
    '六控件 aria-label 必须完全符合契约规范'
  )

  // 3. 反向仿真 1：缺失 aria-label 时会被检出
  const badSourceMissingAria = `
    <select id="ops-user-sort" v-model="userSort"></select>
    <select id="ops-user-page-size" v-model.number="userPageSize" aria-label="用户列表每页条数"></select>
    <select id="ops-ai-history-sort" v-model="aiHistorySort" aria-label="问答审计排序"></select>
    <select id="ops-ai-history-page-size" v-model.number="aiHistoryPageSize" aria-label="问答审计每页条数"></select>
    <select id="ops-ai-call-sort" v-model="aiCallSort" aria-label="调用审计排序"></select>
    <select id="ops-ai-call-page-size" v-model.number="aiCallPageSize" aria-label="调用审计每页条数"></select>
  `
  const badCheck1 = simulateToolbarSelectAccessibility(badSourceMissingAria)
  assert.equal(badCheck1.passed, false, '缺失 aria-label 时仿真判定必须失败')
  assert.deepEqual(badCheck1.missingAriaLabels, ['userSort'], '必须准确检出缺少 aria-label 的控件')

  // 4. 反向仿真 2：缺失 id 时会被检出
  const badSourceMissingId = `
    <select v-model="userSort" aria-label="用户列表排序"></select>
    <select id="ops-user-page-size" v-model.number="userPageSize" aria-label="用户列表每页条数"></select>
    <select id="ops-ai-history-sort" v-model="aiHistorySort" aria-label="问答审计排序"></select>
    <select id="ops-ai-history-page-size" v-model.number="aiHistoryPageSize" aria-label="问答审计每页条数"></select>
    <select id="ops-ai-call-sort" v-model="aiCallSort" aria-label="调用审计排序"></select>
    <select id="ops-ai-call-page-size" v-model.number="aiCallPageSize" aria-label="调用审计每页条数"></select>
  `
  const badCheck2 = simulateToolbarSelectAccessibility(badSourceMissingId)
  assert.equal(badCheck2.passed, false, '缺失 id 时仿真判定必须失败')
  assert.deepEqual(badCheck2.missingIds, ['userSort'], '必须准确检出缺少 id 的控件')

  // 5. 反向仿真 3：文案不符契约时会被检出
  const badSourceWrongText = `
    <select id="ops-user-sort" v-model="userSort" aria-label="错误文案"></select>
    <select id="ops-user-page-size" v-model.number="userPageSize" aria-label="用户列表每页条数"></select>
    <select id="ops-ai-history-sort" v-model="aiHistorySort" aria-label="问答审计排序"></select>
    <select id="ops-ai-history-page-size" v-model.number="aiHistoryPageSize" aria-label="问答审计每页条数"></select>
    <select id="ops-ai-call-sort" v-model="aiCallSort" aria-label="调用审计排序"></select>
    <select id="ops-ai-call-page-size" v-model.number="aiCallPageSize" aria-label="调用审计每页条数"></select>
  `
  const badCheck3 = simulateToolbarSelectAccessibility(badSourceWrongText)
  assert.equal(badCheck3.passed, false, 'aria-label 文案不符契约时仿真判定必须失败')
  assert.equal(badCheck3.mismatchedAriaLabels.length, 1, '必须检出文案不符')
  assert.equal(badCheck3.mismatchedAriaLabels[0].model, 'userSort')

  // 6. 反向仿真 4：存在重复 aria-label 时会被检出
  const badSourceDuplicate = `
    <select id="ops-user-sort" v-model="userSort" aria-label="用户列表排序"></select>
    <select id="ops-user-page-size" v-model.number="userPageSize" aria-label="用户列表排序"></select>
    <select id="ops-ai-history-sort" v-model="aiHistorySort" aria-label="问答审计排序"></select>
    <select id="ops-ai-history-page-size" v-model.number="aiHistoryPageSize" aria-label="问答审计每页条数"></select>
    <select id="ops-ai-call-sort" v-model="aiCallSort" aria-label="调用审计排序"></select>
    <select id="ops-ai-call-page-size" v-model.number="aiCallPageSize" aria-label="调用审计每页条数"></select>
  `
  const badCheck4 = simulateToolbarSelectAccessibility(badSourceDuplicate)
  assert.equal(badCheck4.passed, false, '存在重复 aria-label 时仿真判定必须失败')
  assert.equal(badCheck4.hasDuplicateAriaLabels, true, '必须检出重复 aria-label')
})
