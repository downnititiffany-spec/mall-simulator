import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { canAccessRoute } from '../src/utils/routeAccess.js'
import { requireRuntimeProfileList } from '../src/utils/runtimeProfiles.js'

const root = join(fileURLToPath(new URL('..', import.meta.url)))
const router = readFileSync(join(root, 'src/router.js'), 'utf8')
const app = readFileSync(join(root, 'src/App.vue'), 'utf8')
const api = readFileSync(join(root, 'src/api.js'), 'utf8')
const page = readFileSync(join(root, 'src/views/RuntimeProfiles.vue'), 'utf8')

test('运行环境页面遵循服务端 runtime:manage 角色范围', () => {
  assert.equal(canAccessRoute({ role: 'admin' }, { requiresRuntimeManage: true }), true)
  assert.equal(canAccessRoute({ role: 'data_dev' }, { requiresRuntimeManage: true }), true)
  assert.equal(canAccessRoute({ role: 'operator' }, { requiresRuntimeManage: true }), false)
  assert.equal(canAccessRoute({ role: 'analyst' }, { requiresRuntimeManage: true }), false)
  assert.equal(canAccessRoute(null, { requiresRuntimeManage: true }), false)
  assert.equal(canAccessRoute({ role: 'data_dev' }, { requiresAdmin: true }), false)
  assert.match(router, /path: '\/runtime-profiles'.*requiresRuntimeManage:\s*true/)
  assert.match(app, /'\/runtime-profiles'/)
})

test('运行环境页通过统一 API 客户端调用完整受保护工作流', () => {
  for (const method of [
    'runtimeProfiles:', 'runtimeProfile:', 'createRuntimeProfile:', 'updateRuntimeProfile:',
    'testRuntimeProfile:', 'activateRuntimeProfile:', 'disableRuntimeProfile:'
  ]) assert.ok(api.includes(method), `缺少 API 包装 ${method}`)
  for (const call of [
    'api.runtimeProfiles()', 'api.runtimeProfile(profile.id)', 'api.createRuntimeProfile(payload)',
    'api.updateRuntimeProfile(editingId.value, payload)', 'api.testRuntimeProfile(profile.id)',
    'api.activateRuntimeProfile(profile.id)', 'api.disableRuntimeProfile(profile.id)'
  ]) assert.ok(page.includes(call), `页面未接线 ${call}`)
})

test('环境激活需要本页面最近一次通过测试并再次确认，活动环境不可直接编辑', () => {
  assert.match(page, /latestPassedProfileId/)
  assert.match(page, /if \(!canActivate\(profile\)\) return/)
  assert.match(page, /window\.confirm\(`激活/)
  assert.match(page, /服务端激活时还会重新执行所有适用检查/)
  assert.match(page, /profile\.status === 'ACTIVE'\) return/)
  assert.doesNotMatch(page, /type="password"/)
})

test('环境列表 API 的错误结构不能伪装成空列表', () => {
  assert.deepEqual(requireRuntimeProfileList([], '运行环境'), [])
  assert.deepEqual(requireRuntimeProfileList([{ id: 1 }], '来源'), [{ id: 1 }])
  assert.throws(() => requireRuntimeProfileList({ items: [] }, '运行环境'), {
    name: 'TypeError',
    message: '运行环境列表响应格式无效'
  })
  assert.throws(() => requireRuntimeProfileList(null, '来源'), {
    name: 'TypeError',
    message: '来源列表响应格式无效'
  })
  assert.doesNotMatch(page, /profiles\.value = \[\]/)
  assert.doesNotMatch(page, /sources\.value = \[\]/)
  assert.match(page, /requireRuntimeProfileList\(profileResult\.value, '运行环境'\)/)
  assert.match(page, /requireRuntimeProfileList\(sourceResult\.value, '来源'\)/)
  assert.match(page, /v-else-if="pageError && profiles\.length === 0"/)
  assert.doesNotMatch(page, /v-if="!loading && profiles\.length === 0"/)
})
