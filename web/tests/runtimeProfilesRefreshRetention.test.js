/**
 * runtimeProfilesRefreshRetention.test.js
 *
 * 【守卫形态说明】
 * 本仓测试套件未引入 Vue SFC 编译器 / 虚拟 DOM 渲染器。
 * 本测试遵循既有 web/tests 源码结构守卫先例，通过直接读取 RuntimeProfiles.vue 源码并断言结构模式，
 * 锁定刷新平滑保留与失败防丢失机制，防止代码发生回退。
 * 特此注明：本测试属于静态源码结构守卫与分支判定真值表仿真，并非真实浏览器 DOM 渲染证据。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const page = readFileSync(join(root, 'src/views/RuntimeProfiles.vue'), 'utf8')

const loadProfilesMatch = page.match(/const loadProfiles = async \(\) => \{[\s\S]*?\n\}/)
const loadProfilesSnippet = loadProfilesMatch ? loadProfilesMatch[0] : ''

test('loadProfiles 刷新平滑保留：不先行清空列表且保留校验赋值与完整生命周期', () => {
  assert.ok(loadProfilesSnippet.length > 0, '需成功截取 loadProfiles 函数片段')

  // a. loadProfiles 片段内不存在 profiles.value = [] 与 sources.value = []
  assert.doesNotMatch(loadProfilesSnippet, /profiles\.value = \[\]/, 'loadProfiles 不得先行清空 profiles')
  assert.doesNotMatch(loadProfilesSnippet, /sources\.value = \[\]/, 'loadProfiles 不得先行清空 sources')

  // a. 仍含 requireRuntimeProfileList 成功赋值
  assert.match(
    loadProfilesSnippet,
    /profiles\.value = requireRuntimeProfileList\(profileResult\.value, '运行环境'\)/,
    'loadProfiles 需包含运行环境列表成功赋值'
  )
  assert.match(
    loadProfilesSnippet,
    /sources\.value = requireRuntimeProfileList\(sourceResult\.value, '来源'\)/,
    'loadProfiles 需包含来源列表成功赋值'
  )

  // d. loadProfiles 生命周期保留
  assert.match(loadProfilesSnippet, /loading\.value = true/, 'loadProfiles 起始需置 loading 为 true')
  assert.match(loadProfilesSnippet, /pageError\.value = ''/, 'loadProfiles 起始需重置 pageError 为空')
  assert.match(loadProfilesSnippet, /finally\s*\{[\s\S]*?loading\.value = false[\s\S]*?\}/, 'loadProfiles finally 中需置 loading 为 false')
})

test('模板状态分支守护：加载占位、错误占位、空态与数据表格四分支结构完整', () => {
  // b. 模板加载占位条件为 v-if="loading && profiles.length === 0"，且页面中不再存在独立的 v-if="loading" class="empty-state"
  assert.match(
    page,
    /<div v-if="loading && profiles\.length === 0" class="empty-state">正在读取运行环境…<\/div>/,
    '模板加载占位需在 loading 且 profiles 为空时展示'
  )
  assert.doesNotMatch(
    page,
    /v-if="loading"\s+class="empty-state"/,
    '页面不得存在无长度检查的独立 loading empty-state 占位'
  )

  // c. 行 27 错误占位与空态分支仍在，table-wrap 的 v-else 分支仍在（三分支保留守护）
  assert.match(
    page,
    /v-else-if="pageError && profiles\.length === 0"\s+class="empty-state"/,
    '需保留首次加载失败错误占位分支'
  )
  assert.match(page, /运行环境列表当前不可用，请修正错误后刷新。/, '需保留错误占位文案')

  assert.match(
    page,
    /v-else-if="profiles\.length === 0"\s+class="empty-state"/,
    '需保留无数据时的空态占位分支'
  )
  assert.match(page, /尚无运行环境。新建草稿后先测试，再按需激活。/, '需保留空态占位文案')

  assert.match(page, /v-else class="table-wrap"/, '需保留旧数据存在时的表格分支 table-wrap')

  // f. 卡片头进度指示保留：页面仍含 <span v-if="loading" class="muted">正在读取…</span>
  assert.match(
    page,
    /<span v-if="loading" class="muted">正在读取…<\/span>/,
    '需保留卡片头部 loading 进度指示'
  )
})

test('条件链真值表仿真：四分支判定顺序与落点仿真验证', () => {
  // e. 条件链真值表仿真：以纯函数复刻四分支判定顺序
  const resolveDisplayBranch = ({ loading, pageError, profilesLength }) => {
    if (loading && profilesLength === 0) {
      return 'loading-placeholder'
    } else if (pageError && profilesLength === 0) {
      return 'error-placeholder'
    } else if (profilesLength === 0) {
      return 'empty-placeholder'
    } else {
      return 'table'
    }
  }

  // 1. (loading=true, pageError='', len=3) → 表格（已有数据刷新时表格保持挂载，不闪空）
  assert.equal(
    resolveDisplayBranch({ loading: true, pageError: '', profilesLength: 3 }),
    'table',
    '已有数据刷新时应展示表格'
  )

  // 2. (true, '', 0) → 加载占位（首次加载显示占位）
  assert.equal(
    resolveDisplayBranch({ loading: true, pageError: '', profilesLength: 0 }),
    'loading-placeholder',
    '首次加载时应展示加载占位'
  )

  // 3. (false, 'err', 3) → 表格（刷新失败且有旧数据，旧表格保留）
  assert.equal(
    resolveDisplayBranch({ loading: false, pageError: '网络错误', profilesLength: 3 }),
    'table',
    '刷新失败且有旧数据时应保留表格'
  )

  // 4. (false, 'err', 0) → 错误占位（首次加载失败无旧数据，显示错误占位）
  assert.equal(
    resolveDisplayBranch({ loading: false, pageError: '网络错误', profilesLength: 0 }),
    'error-placeholder',
    '首次加载失败时应展示错误占位'
  )

  // 5. (false, '', 0) → 真空态（加载完成且确实为空）
  assert.equal(
    resolveDisplayBranch({ loading: false, pageError: '', profilesLength: 0 }),
    'empty-placeholder',
    '列表确实为空时应展示真空态'
  )

  // 额外组合：刷新中遇到旧错误/新错误但已有数据，仍保持表格
  assert.equal(
    resolveDisplayBranch({ loading: true, pageError: '刷新失败', profilesLength: 2 }),
    'table',
    '已有数据且刷新发生错误时依然保留表格'
  )
})
