/**
 * opsNewUserEnterSubmitS13.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 Ops.vue 源码并断言结构模式，
 * 锁定 Ops 运维页新建用户区三个文本输入框（用户名、姓名、初始密码）的 Enter 键提交绑定（@keyup.enter="createUser"）、
 * 角色 select 原生语义保持（排除 keyup 监听）、创建按钮绑定防回退、全页 keyup.enter 范围与计数规范、
 * 不引入 <form> 标签守护以及 Enter 触发判定纯函数仿真。
 * 特此注明：本测试属于静态源码结构守卫与键盘事件触发判定纯函数仿真，并非真实浏览器 DOM 渲染或真实浏览器键盘事件证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const opsSource = readFileSync(join(root, 'src/views/Ops.vue'), 'utf8')

test('a. 用户名/姓名锚点：两输入新形态逐字在位（正则逐字断言含 @keyup.enter="createUser"）+ 两输入连贯序列在位', () => {
  // 1. 用户名 input 新形态逐字锚点（含 @keyup.enter="createUser"）
  assert.match(
    opsSource,
    /<input id="ops-new-username" v-model="newUser\.username" placeholder="用户名" style="padding:4px" aria-label="新用户用户名" @keyup\.enter="createUser" \/>/,
    'Ops 需包含用户名 input 且带 id="ops-new-username" 与 @keyup.enter="createUser"'
  )

  // 2. 姓名 input 新形态逐字锚点（含 @keyup.enter="createUser"）
  assert.match(
    opsSource,
    /<input id="ops-new-real-name" v-model="newUser\.realName" placeholder="姓名" style="padding:4px" aria-label="新用户姓名" @keyup\.enter="createUser" \/>/,
    'Ops 需包含姓名 input 且带 id="ops-new-real-name" 与 @keyup.enter="createUser"'
  )

  // 3. 用户名与姓名两行连续在位正则断言
  assert.match(
    opsSource,
    /<input id="ops-new-username" v-model="newUser\.username" placeholder="用户名" style="padding:4px" aria-label="新用户用户名" @keyup\.enter="createUser" \/>\s*<input id="ops-new-real-name" v-model="newUser\.realName" placeholder="姓名" style="padding:4px" aria-label="新用户姓名" @keyup\.enter="createUser" \/>/,
    'Ops 新建用户区用户名与姓名输入控件必须按契约顺序连续在位'
  )
})

test('b. 密码锚点与按钮保留：密码输入新形态逐字在位 + select 收尾至密码输入的序列在位 +「创建用户」按钮行逐字保留', () => {
  // 1. 初始密码 input 新形态逐字锚点（含 @keyup.enter="createUser"）
  assert.match(
    opsSource,
    /<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" @keyup\.enter="createUser" \/>/,
    'Ops 需包含密码 input 且带 id="ops-new-password"、type="password" 与 @keyup.enter="createUser"'
  )

  // 2. select 收尾至密码输入的序列在位
  assert.match(
    opsSource,
    /<\/select>\s*<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" @keyup\.enter="createUser" \/>/,
    'Ops 角色 select 与密码输入必须连续在位'
  )

  // 3. 「创建用户」按钮行逐字保留
  assert.match(
    opsSource,
    /<button class="btn btn-sm btn-success" @click="createUser" :disabled="loading \|\| busy">创建用户<\/button>/,
    '创建按钮行必须逐字保留 @click="createUser" 与 :disabled="loading || busy"'
  )

  // 4. 密码输入至创建按钮连续在位
  assert.match(
    opsSource,
    /<input id="ops-new-password" v-model="newUser\.password" placeholder="初始密码\(≥6位\)" type="password" style="padding:4px" aria-label="新用户初始密码" @keyup\.enter="createUser" \/>\s*<button class="btn btn-sm btn-success" @click="createUser" :disabled="loading \|\| busy">创建用户<\/button>/,
    'Ops 密码输入与「创建用户」按钮必须连续在位'
  )
})

test('c. 范围守护与计数：Ops.vue 内 @keyup.enter 恰 3 处且均为 @keyup.enter="createUser"；角色 select 开标签逐字保留且不含 keyup；Ops.vue 源码不含 <form；async function createUser() 定义与其首行 busy/loading 守卫逐字保留', () => {
  // 1. Ops.vue 内 @keyup.enter 恰 3 处
  const allEnterMatches = opsSource.match(/@keyup\.enter\b/g) || []
  assert.equal(
    allEnterMatches.length,
    3,
    `Ops.vue 内 @keyup.enter 出现次数必须恰为 3 次（实测 ${allEnterMatches.length} 次）`
  )

  // 2. Ops.vue 内 @keyup.enter 均为 @keyup.enter="createUser"
  const createUserEnterMatches = opsSource.match(/@keyup\.enter="createUser"/g) || []
  assert.equal(
    createUserEnterMatches.length,
    3,
    `Ops.vue 内 @keyup.enter="createUser" 出现次数必须恰为 3 次（实测 ${createUserEnterMatches.length} 次）`
  )

  // 3. 角色 select 开标签逐字保留且不含 keyup
  assert.match(
    opsSource,
    /<select id="ops-new-role" v-model="newUser\.role" style="padding:4px" aria-label="新用户角色">/,
    '角色 select 开标签必须逐字保留'
  )
  const roleSelectMatch = opsSource.match(/<select id="ops-new-role"[^>]*>/)
  assert.ok(roleSelectMatch, '需定位角色 select 元素')
  assert.doesNotMatch(
    roleSelectMatch[0],
    /keyup/i,
    '角色 select 标签不得包含任何 keyup 监听（原生 select 排除裁决）'
  )

  // 4. Ops.vue 源码不含 <form 标签
  assert.doesNotMatch(
    opsSource,
    /<form\b/i,
    'Ops.vue 源码不得包含 <form 标签（禁止引入 <form> 包裹）'
  )

  // 5. async function createUser() 定义与其首行 busy/loading 守卫逐字保留
  assert.match(
    opsSource,
    /async function createUser\(\)/,
    'Ops.vue 必须保留 async function createUser() 定义'
  )
  assert.match(
    opsSource,
    /async function createUser\(\)\s*\{\s*if \(busy\.value \|\| loading\.value\) return/,
    'createUser 入口处必须保留 busy/loading 守卫'
  )
})

test('d. 行为不回退：三输入 v-model/placeholder/type/style/aria-label 逐字保留（以新形态锚点复核）、三 option 逐字保留、Ops.vue 内其余 @keyup 零（除恰 3 处 enter 外无其他 keyup 监听）', () => {
  // 1. 用户名输入各属性逐字保留
  assert.match(opsSource, /<input id="ops-new-username"[^>]*v-model="newUser\.username"/, '用户名输入保留 v-model="newUser.username"')
  assert.match(opsSource, /<input id="ops-new-username"[^>]*placeholder="用户名"/, '用户名输入保留 placeholder="用户名"')
  assert.match(opsSource, /<input id="ops-new-username"[^>]*style="padding:4px"/, '用户名输入保留 style="padding:4px"')
  assert.match(opsSource, /<input id="ops-new-username"[^>]*aria-label="新用户用户名"/, '用户名输入保留 aria-label="新用户用户名"')

  // 2. 姓名输入各属性逐字保留
  assert.match(opsSource, /<input id="ops-new-real-name"[^>]*v-model="newUser\.realName"/, '姓名输入保留 v-model="newUser.realName"')
  assert.match(opsSource, /<input id="ops-new-real-name"[^>]*placeholder="姓名"/, '姓名输入保留 placeholder="姓名"')
  assert.match(opsSource, /<input id="ops-new-real-name"[^>]*style="padding:4px"/, '姓名输入保留 style="padding:4px"')
  assert.match(opsSource, /<input id="ops-new-real-name"[^>]*aria-label="新用户姓名"/, '姓名输入保留 aria-label="新用户姓名"')

  // 3. 初始密码输入各属性逐字保留
  assert.match(opsSource, /<input id="ops-new-password"[^>]*v-model="newUser\.password"/, '密码输入保留 v-model="newUser.password"')
  assert.match(opsSource, /<input id="ops-new-password"[^>]*placeholder="初始密码\(≥6位\)"/, '密码输入保留 placeholder="初始密码(≥6位)"')
  assert.match(opsSource, /<input id="ops-new-password"[^>]*type="password"/, '密码输入保留 type="password"')
  assert.match(opsSource, /<input id="ops-new-password"[^>]*style="padding:4px"/, '密码输入保留 style="padding:4px"')
  assert.match(opsSource, /<input id="ops-new-password"[^>]*aria-label="新用户初始密码"/, '密码输入保留 aria-label="新用户初始密码"')

  // 4. 三 option 逐字保留
  assert.match(opsSource, /<option value="operator">运营专员<\/option>/, '角色 select 必须包含运营专员 option')
  assert.match(opsSource, /<option value="analyst">数据分析师<\/option>/, '角色 select 必须包含数据分析师 option')
  assert.match(opsSource, /<option value="admin">系统管理员<\/option>/, '角色 select 必须包含系统管理员 option')
  assert.match(
    opsSource,
    /<select id="ops-new-role" v-model="newUser\.role" style="padding:4px" aria-label="新用户角色">\s*<option value="operator">运营专员<\/option>\s*<option value="analyst">数据分析师<\/option>\s*<option value="admin">系统管理员<\/option>\s*<\/select>/,
    '角色 select 完整结构（含三个选项与闭合标签）必须逐字在位'
  )

  // 5. Ops.vue 内其余 @keyup 零（除恰 3 处 enter 外无其他 keyup 监听）
  const allKeyupMatches = opsSource.match(/@keyup\b/g) || []
  assert.equal(
    allKeyupMatches.length,
    3,
    `Ops.vue 内 @keyup 监听总数必须恰为 3 次（除恰 3 处 enter 外无其他 keyup，实测 ${allKeyupMatches.length} 次）`
  )
})

test('e. Enter 触发判定仿真：以纯函数复刻「新建用户输入 Enter → createUser」判定（按 id 定位三输入，断言各带 @keyup.enter="createUser" 且 handler 名与创建按钮 @click handler 名一致；并以反向仿真断言缺失监听、handler 名不符、select 被误加监听时会被检出），只仿真判定逻辑，不声称 DOM/真实键盘事件证据', () => {
  // 1. 纯函数：复刻「新建用户输入 Enter → createUser」判定
  const simulateNewUserEnterSubmitCheck = (
    sourceText,
    targetInputIds = ['ops-new-username', 'ops-new-real-name', 'ops-new-password'],
    forbiddenIds = ['ops-new-role'],
    buttonPattern = /<button[^>]*@click="([^"]+)"[^>]*>创建用户<\/button>/
  ) => {
    const btnMatch = sourceText.match(buttonPattern)
    const buttonHandler = btnMatch ? btnMatch[1] : null

    const elementMatches = [...sourceText.matchAll(/<(input|select)\b([^>]*)>/g)]
    const elementsById = new Map()
    for (const m of elementMatches) {
      const tag = m[1]
      const rawAttrs = m[2]
      const idMatch = rawAttrs.match(/\bid="([^"]+)"/)
      if (idMatch) {
        const enterMatch = rawAttrs.match(/@keyup\.enter="([^"]+)"/)
        const anyKeyupMatch = rawAttrs.match(/@keyup(?:\.[a-z]+)?="([^"]+)"/)
        elementsById.set(idMatch[1], {
          id: idMatch[1],
          tag,
          enterHandler: enterMatch ? enterMatch[1] : null,
          hasKeyup: Boolean(anyKeyupMatch),
          rawAttrs
        })
      }
    }

    const missingInputs = []
    const missingEnterListeners = []
    const mismatchedHandlers = []
    const matched = []
    const forbiddenElementsWithKeyup = []

    for (const id of targetInputIds) {
      const elem = elementsById.get(id)
      if (!elem) {
        missingInputs.push(id)
        continue
      }
      if (!elem.enterHandler) {
        missingEnterListeners.push(id)
        continue
      }
      if (!buttonHandler || elem.enterHandler !== buttonHandler) {
        mismatchedHandlers.push({
          id,
          enterHandler: elem.enterHandler,
          buttonHandler
        })
        continue
      }
      matched.push({ id, enterHandler: elem.enterHandler, buttonHandler })
    }

    for (const id of forbiddenIds) {
      const elem = elementsById.get(id)
      if (elem && elem.hasKeyup) {
        forbiddenElementsWithKeyup.push(id)
      }
    }

    const passed = (
      Boolean(buttonHandler) &&
      missingInputs.length === 0 &&
      missingEnterListeners.length === 0 &&
      mismatchedHandlers.length === 0 &&
      forbiddenElementsWithKeyup.length === 0
    )

    return {
      buttonHandler,
      checkedIds: targetInputIds,
      matched,
      missingInputs,
      missingEnterListeners,
      mismatchedHandlers,
      forbiddenElementsWithKeyup,
      passed
    }
  }

  // 2. 对 Ops.vue 源码执行纯函数判定
  const opsCheck = simulateNewUserEnterSubmitCheck(opsSource)
  assert.equal(opsCheck.passed, true, 'Ops 新建用户三输入 Enter 提交仿真判定必须通过')
  assert.equal(opsCheck.buttonHandler, 'createUser', '创建用户按钮 handler 必须为 createUser')
  assert.deepEqual(opsCheck.missingInputs, [], '三个目标输入框必须全部在位')
  assert.deepEqual(opsCheck.missingEnterListeners, [], '三输入框均需挂载 @keyup.enter')
  assert.deepEqual(opsCheck.mismatchedHandlers, [], '三输入框 enter handler 必须与创建按钮 createUser 一致')
  assert.deepEqual(opsCheck.forbiddenElementsWithKeyup, [], '角色 select 不得包含任何 keyup 监听')
  assert.equal(opsCheck.matched.length, 3, '三个目标输入框均成功匹配')

  // 3. 反向仿真 1：缺失 @keyup.enter 监听时会被检出
  const badSourceMissingEnter = `
    <input id="ops-new-username" placeholder="用户名" />
    <input id="ops-new-real-name" placeholder="姓名" @keyup.enter="createUser" />
    <select id="ops-new-role"></select>
    <input id="ops-new-password" placeholder="密码" @keyup.enter="createUser" />
    <button @click="createUser">创建用户</button>
  `
  const badCheck1 = simulateNewUserEnterSubmitCheck(badSourceMissingEnter)
  assert.equal(badCheck1.passed, false, '缺失 @keyup.enter 监听时仿真判定必须失败')
  assert.deepEqual(badCheck1.missingEnterListeners, ['ops-new-username'], '必须检出缺失 @keyup.enter 的输入框')

  // 4. 反向仿真 2：handler 名与按钮 @click 不一致时会被检出
  const badSourceMismatchedHandler = `
    <input id="ops-new-username" placeholder="用户名" @keyup.enter="otherHandler" />
    <input id="ops-new-real-name" placeholder="姓名" @keyup.enter="createUser" />
    <select id="ops-new-role"></select>
    <input id="ops-new-password" placeholder="密码" @keyup.enter="createUser" />
    <button @click="createUser">创建用户</button>
  `
  const badCheck2 = simulateNewUserEnterSubmitCheck(badSourceMismatchedHandler)
  assert.equal(badCheck2.passed, false, 'handler 名与按钮 @click 不一致时仿真判定必须失败')
  assert.equal(badCheck2.mismatchedHandlers.length, 1, '必须检出 1 个 handler 不匹配')
  assert.equal(badCheck2.mismatchedHandlers[0].id, 'ops-new-username')
  assert.equal(badCheck2.mismatchedHandlers[0].enterHandler, 'otherHandler')
  assert.equal(badCheck2.mismatchedHandlers[0].buttonHandler, 'createUser')

  // 5. 反向仿真 3：角色 select 被误加 keyup 监听时会被检出
  const badSourceSelectWithKeyup = `
    <input id="ops-new-username" placeholder="用户名" @keyup.enter="createUser" />
    <input id="ops-new-real-name" placeholder="姓名" @keyup.enter="createUser" />
    <select id="ops-new-role" @keyup.enter="createUser"></select>
    <input id="ops-new-password" placeholder="密码" @keyup.enter="createUser" />
    <button @click="createUser">创建用户</button>
  `
  const badCheck3 = simulateNewUserEnterSubmitCheck(badSourceSelectWithKeyup)
  assert.equal(badCheck3.passed, false, '角色 select 被误加 keyup 监听时仿真判定必须失败')
  assert.deepEqual(badCheck3.forbiddenElementsWithKeyup, ['ops-new-role'], '必须检出角色 select 误加 keyup 监听')

  // 6. 反向仿真 4：目标输入框缺失时会被检出
  const badSourceMissingInput = `
    <input id="ops-new-username" placeholder="用户名" @keyup.enter="createUser" />
    <input id="ops-new-real-name" placeholder="姓名" @keyup.enter="createUser" />
    <select id="ops-new-role"></select>
    <button @click="createUser">创建用户</button>
  `
  const badCheck4 = simulateNewUserEnterSubmitCheck(badSourceMissingInput)
  assert.equal(badCheck4.passed, false, '缺失密码输入框时仿真判定必须失败')
  assert.deepEqual(badCheck4.missingInputs, ['ops-new-password'], '必须检出缺失密码输入框')
})
