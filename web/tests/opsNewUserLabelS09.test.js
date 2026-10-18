/**
 * opsNewUserLabelS09.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue 源码并断言结构模式，
 * 锁定 Ops 运维页新建用户区四个控件（用户名、姓名、角色、初始密码）的无障碍命名（id 与 aria-label 补齐）、属性防回退、全仓/全页 id 唯一性与纯函数命名完整性仿真，防止无障碍与交互属性发生回退。
 * 特此注明：本测试属于静态源码结构守卫与无障碍命名判定纯函数仿真，并非真实浏览器 DOM 渲染或屏幕阅读器可访问性实测证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')

test('a. 用户名与姓名关联：两输入的 id/aria-label 逐字锚点在位（正则逐字断言两行新形态）', () => {
  // 1. username input 新形态逐字锚点
  assert.match(
    opsSource,
    /<input id="ops-new-username" v-model="newUser\.username" placeholder="用户名" style="padding:4px" aria-label="新用户用户名" \/>/,
    'Ops 需包含用户名 input 且带 id="ops-new-username" 与 aria-label="新用户用户名"'
  )

  // 2. realName input 新形态逐字锚点
  assert.match(
    opsSource,
    /<input id="ops-new-real-name" v-model="newUser\.realName" placeholder="姓名" style="padding:4px" aria-label="新用户姓名" \/>/,
    'Ops 需包含姓名 input 且带 id="ops-new-real-name" 与 aria-label="新用户姓名"'
  )

  // 3. 用户名与姓名两行连续在位正则断言
  assert.match(
    opsSource,
    /<input id="ops-new-username" v-model="newUser\.username" placeholder="用户名" style="padding:4px" aria-label="新用户用户名" \/>\s*<input id="ops-new-real-name" v-model="newUser\.realName" placeholder="姓名" style="padding:4px" aria-label="新用户姓名" \/>/,
    'Ops 新建用户区用户名与姓名输入控件必须按契约顺序连续在位'
  )
})

test('b. 角色与密码关联：select 开标签的 id/aria-label 逐字锚点与三个 option 逐字在位；密码输入的 id/aria-label 逐字锚点在位', () => {
  // 1. role select 开标签新形态逐字锚点
  assert.match(
    opsSource,
    /<select id="ops-new-role" v-model="newUser\.role" style="padding:4px" aria-label="新用户角色">/,
    'Ops 需包含角色 select 开标签且带 id="ops-new-role" 与 aria-label="新用户角色"'
  )

  // 2. 三个 option 逐字在位
  assert.match(
    opsSource,
    /<option value="operator">运营专员<\/option>/,
    '角色 select 必须包含运营专员 option'
  )
  assert.match(
    opsSource,
    /<option value="analyst">数据分析师<\/option>/,
    '角色 select 必须包含数据分析师 option'
  )
  assert.match(
    opsSource,
    /<option value="admin">系统管理员<\/option>/,
    '角色 select 必须包含系统管理员 option'
  )

  // 3. 角色 select 完整结构连续在位（含三个 option 与闭合标签）
  assert.match(
    opsSource,
    /<select id="ops-new-role" v-model="newUser\.role" style="padding:4px" aria-label="新用户角色">\s*<option value="operator">运营专员<\/option>\s*<option value="analyst">数据分析师<\/option>\s*<option value="admin">系统管理员<\/option>\s*<\/select>/,
    'Ops 角色 select 开标签、三个选项与闭合标签必须逐字按序在位'
  )

  // 4. password input 新形态逐字锚点
  assert.match(
    opsSource,
    /<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" \/>/,
    'Ops 需包含密码 input 且带 id="ops-new-password"、type="password" 与 aria-label="新用户初始密码"'
  )

  // 5. 角色与密码两控件连续在位正则断言
  assert.match(
    opsSource,
    /<\/select>\s*<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" \/>/,
    'Ops 角色 select 与密码输入必须连续在位'
  )
})

test('c. id 唯一性与零 tabindex：四个新 id 在 Ops.vue 内以 id="..." 形态各恰出现 1 次，且 Ops.vue 源码不含 tabindex', () => {
  const newIds = [
    'ops-new-username',
    'ops-new-real-name',
    'ops-new-role',
    'ops-new-password'
  ]

  // 1. 四个新 id 在 Ops.vue 内以 id="..." 形态各恰好出现 1 次
  for (const id of newIds) {
    const idRegex = new RegExp(`id="${id}"`, 'g')
    const matches = opsSource.match(idRegex) || []
    assert.equal(
      matches.length,
      1,
      `Ops.vue 内 id="${id}" 出现次数必须恰为 1 次（实测 ${matches.length} 次）`
    )
  }

  // 2. Ops.vue 内全部 id="..." 出现的总数恰为 10（保持 Ops.vue 其余部分零 id 规范）
  const allIdMatches = opsSource.match(/\bid="[^"]+"/g) || []
  assert.equal(
    allIdMatches.length,
    10,
    `Ops.vue 内 id 属性总数必须恰为 10（实测 ${allIdMatches.length} 个：${allIdMatches.join(', ')}）`
  )

  // 3. Ops.vue 源码不含 tabindex 属性（符合 UP-49～52 规范口径，保持现行零 tabindex）
  assert.doesNotMatch(
    opsSource,
    /tabindex/i,
    'Ops.vue 源码不得包含 tabindex 属性（沿用 UP-49～52 复核符合口径）'
  )
})

test('d. 行为不回退：四个 v-model="newUser.*" 绑定、两个 placeholder、password 的 type="password"、创建按钮行逐字锚点均在位', () => {
  // 1. 四个 v-model 绑定在位
  assert.match(opsSource, /v-model="newUser\.username"/, 'Ops 需保留 v-model="newUser.username"')
  assert.match(opsSource, /v-model="newUser\.realName"/, 'Ops 需保留 v-model="newUser.realName"')
  assert.match(opsSource, /v-model="newUser\.role"/, 'Ops 需保留 v-model="newUser.role"')
  assert.match(opsSource, /v-model="newUser\.password"/, 'Ops 需保留 v-model="newUser.password"')

  // 2. 两个普通输入框 placeholder 及密码 placeholder 在位
  assert.match(opsSource, /placeholder="用户名"/, 'Ops 需保留 placeholder="用户名"')
  assert.match(opsSource, /placeholder="姓名"/, 'Ops 需保留 placeholder="姓名"')
  assert.match(opsSource, /placeholder="初始密码\(≥6位\)"/, 'Ops 需保留 placeholder="初始密码(≥6位)"')

  // 3. password 输入框的 type="password" 在位
  assert.match(
    opsSource,
    /<input[^>]*type="password"[^>]*id="ops-new-password"|<input[^>]*id="ops-new-password"[^>]*type="password"/,
    '密码输入框必须保留 type="password"'
  )

  // 4. 创建用户按钮行逐字锚点在位
  assert.match(
    opsSource,
    /<button class="btn btn-sm btn-success" @click="createUser" :disabled="loading \|\| busy">创建用户<\/button>/,
    '创建按钮行必须逐字保留 @click="createUser" 与 :disabled="loading || busy"'
  )

  // 5. 新建用户完整工具栏容器连贯结构在位
  assert.match(
    opsSource,
    /<div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">\s*<input id="ops-new-username"[^>]*\/>\s*<input id="ops-new-real-name"[^>]*\/>\s*<select id="ops-new-role"[\s\S]*?<\/select>\s*<input id="ops-new-password"[^>]*\/>\s*<button class="btn btn-sm btn-success" @click="createUser" :disabled="loading \|\| busy">创建用户<\/button>\s*<\/div>/,
    '新建用户整行容器内各控件必须按契约连续排列且包裹在 flex 容器中'
  )
})

test('e. 命名完整性仿真：以纯函数复刻「新建用户四控件无障碍命名」判定，并以反向仿真断言异常会被检出', () => {
  // 1. 纯函数：复刻「新建用户四控件无障碍命名」判定逻辑
  const simulateNewUserAccessibility = (sourceText, expectedIds = ['ops-new-username', 'ops-new-real-name', 'ops-new-role', 'ops-new-password']) => {
    // 提取 sourceText 中所有 input 与 select 控件标签属性
    const elementMatches = [...sourceText.matchAll(/<(input|select)\b([^>]*)>/g)]
    const controls = elementMatches.map((m) => {
      const tag = m[1]
      const attrs = m[2]
      const idMatch = attrs.match(/\bid="([^"]+)"/)
      const ariaLabelMatch = attrs.match(/\baria-label="([^"]+)"/)
      return {
        tag,
        id: idMatch ? idMatch[1] : null,
        ariaLabel: ariaLabelMatch ? ariaLabelMatch[1] : null,
        rawAttrs: attrs
      }
    })

    const controlsById = new Map(controls.filter((c) => c.id).map((c) => [c.id, c]))
    const missingIds = []
    const missingAriaLabels = []
    const ariaLabels = []

    for (const expectedId of expectedIds) {
      const ctrl = controlsById.get(expectedId)
      if (!ctrl) {
        missingIds.push(expectedId)
      } else if (!ctrl.ariaLabel || ctrl.ariaLabel.trim() === '') {
        missingAriaLabels.push(expectedId)
      } else {
        ariaLabels.push(ctrl.ariaLabel.trim())
      }
    }

    const uniqueAriaLabels = new Set(ariaLabels)
    const hasDuplicateAriaLabels = uniqueAriaLabels.size !== ariaLabels.length

    return {
      totalFound: expectedIds.length - missingIds.length,
      expectedCount: expectedIds.length,
      missingIds,
      missingAriaLabels,
      ariaLabels,
      uniqueAriaLabels: [...uniqueAriaLabels],
      hasDuplicateAriaLabels,
      passed: missingIds.length === 0 && missingAriaLabels.length === 0 && !hasDuplicateAriaLabels
    }
  }

  // 2. 对 Ops.vue 源码执行纯函数判定
  const result = simulateNewUserAccessibility(opsSource)
  assert.equal(result.passed, true, 'Ops.vue 新建用户四控件无障碍命名仿真判定必须通过')
  assert.equal(result.totalFound, 4, '必须成功找到全部 4 个新建用户控件')
  assert.deepEqual(result.missingIds, [], '所有预期的 id 必须均存在于页面中')
  assert.deepEqual(result.missingAriaLabels, [], '所有四控件必须均具有非空 aria-label')
  assert.equal(result.hasDuplicateAriaLabels, false, '四控件的 aria-label 必须互不相同（以便屏幕阅读器准确区分各控件）')
  assert.deepEqual(
    result.ariaLabels,
    ['新用户用户名', '新用户姓名', '新用户角色', '新用户初始密码'],
    '四控件 aria-label 必须完全符合契约规范'
  )

  // 3. 反向仿真：缺失 aria-label 必须被检出
  const badSourceMissingAria = `
    <input id="ops-new-username" v-model="newUser.username" placeholder="用户名" />
    <input id="ops-new-real-name" v-model="newUser.realName" placeholder="姓名" aria-label="新用户姓名" />
    <select id="ops-new-role" v-model="newUser.role" aria-label="新用户角色"></select>
    <input id="ops-new-password" v-model="newUser.password" aria-label="新用户初始密码" />
  `
  const badCheckMissingAria = simulateNewUserAccessibility(badSourceMissingAria)
  assert.equal(badCheckMissingAria.passed, false, '缺少 aria-label 时仿真判定必须失败')
  assert.deepEqual(badCheckMissingAria.missingAriaLabels, ['ops-new-username'], '必须准确检出缺少 aria-label 的控件 ID')

  // 4. 反向仿真：存在重复 aria-label 必须被检出
  const badSourceDuplicateAria = `
    <input id="ops-new-username" v-model="newUser.username" aria-label="新用户用户名" />
    <input id="ops-new-real-name" v-model="newUser.realName" aria-label="新用户用户名" />
    <select id="ops-new-role" v-model="newUser.role" aria-label="新用户角色"></select>
    <input id="ops-new-password" v-model="newUser.password" aria-label="新用户初始密码" />
  `
  const badCheckDuplicateAria = simulateNewUserAccessibility(badSourceDuplicateAria)
  assert.equal(badCheckDuplicateAria.passed, false, '存在重复 aria-label 时仿真判定必须失败')
  assert.equal(badCheckDuplicateAria.hasDuplicateAriaLabels, true, '必须检出重复 aria-label')

  // 5. 反向仿真：缺失某个预期 id 必须被检出
  const badSourceMissingId = `
    <input v-model="newUser.username" aria-label="新用户用户名" />
    <input id="ops-new-real-name" v-model="newUser.realName" aria-label="新用户姓名" />
    <select id="ops-new-role" v-model="newUser.role" aria-label="新用户角色"></select>
    <input id="ops-new-password" v-model="newUser.password" aria-label="新用户初始密码" />
  `
  const badCheckMissingId = simulateNewUserAccessibility(badSourceMissingId)
  assert.equal(badCheckMissingId.passed, false, '缺少控件 ID 时仿真判定必须失败')
  assert.deepEqual(badCheckMissingId.missingIds, ['ops-new-username'], '必须准确检出缺失的控件 ID')
})
