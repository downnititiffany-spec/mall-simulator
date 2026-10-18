import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveDeniedPath } from '../src/utils/routeAccess.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_DIR = join(HERE, '..')
const ROUTER_SRC = readFileSync(join(WEB_DIR, 'src', 'router.js'), 'utf8')
const APP_SRC = readFileSync(join(WEB_DIR, 'src', 'App.vue'), 'utf8')

test('resolveDeniedPath: 合法功能路由原样回显', () => {
  // 规约必测项：/pipeline、/ops、/sources/wizard、/runtime-profiles、/sales
  assert.equal(resolveDeniedPath('/pipeline'), '/pipeline')
  assert.equal(resolveDeniedPath('/ops'), '/ops')
  assert.equal(resolveDeniedPath('/sources/wizard'), '/sources/wizard')
  assert.equal(resolveDeniedPath('/runtime-profiles'), '/runtime-profiles')
  assert.equal(resolveDeniedPath('/sales'), '/sales')

  // 其余已知功能路由
  assert.equal(resolveDeniedPath('/behavior'), '/behavior')
  assert.equal(resolveDeniedPath('/products'), '/products')
  assert.equal(resolveDeniedPath('/rfm'), '/rfm')
  assert.equal(resolveDeniedPath('/ai'), '/ai')
  assert.equal(resolveDeniedPath('/decisions'), '/decisions')
  assert.equal(resolveDeniedPath('/reports'), '/reports')
})

test('resolveDeniedPath: 非法类型与异常入参返回 null', () => {
  // 规约必测项：undefined / null / 数组、空字符串
  assert.equal(resolveDeniedPath(undefined), null)
  assert.equal(resolveDeniedPath(null), null)
  assert.equal(resolveDeniedPath(['/pipeline']), null)
  assert.equal(resolveDeniedPath([]), null)
  assert.equal(resolveDeniedPath(''), null)

  // 其他非字符串类型
  assert.equal(resolveDeniedPath(123), null)
  assert.equal(resolveDeniedPath(false), null)
  assert.equal(resolveDeniedPath({ path: '/pipeline' }), null)
})

test('resolveDeniedPath: 根路径、大盘页、登录页与未知路径返回 null', () => {
  // 规约必测项：/、/overview（防循环）、/login、未知路径 /admin-secret
  assert.equal(resolveDeniedPath('/'), null)
  assert.equal(resolveDeniedPath('/overview'), null)
  assert.equal(resolveDeniedPath('/login'), null)
  assert.equal(resolveDeniedPath('/admin-secret'), null)

  // 其他未知路径与未注册子路径
  assert.equal(resolveDeniedPath('/mall'), null)
  assert.equal(resolveDeniedPath('/sales/extra'), null)
  assert.equal(resolveDeniedPath('/unknown'), null)
})

test('resolveDeniedPath: 相对路径、带 query 或 hash 的字符串返回 null', () => {
  // 规约必测项：相对路径 pipeline、带 query 的 /pipeline?x=1、带 hash 的 /ops#top
  assert.equal(resolveDeniedPath('pipeline'), null)
  assert.equal(resolveDeniedPath('sales'), null)
  assert.equal(resolveDeniedPath('/pipeline?x=1'), null)
  assert.equal(resolveDeniedPath('/ops#top'), null)

  // 变体参数
  assert.equal(resolveDeniedPath('/sales?foo=bar#section'), null)
})

test('接线守护：router.js 无权分支携带 denied: to.path 且无 token 时保持 /login 字面量', () => {
  assert.match(
    ROUTER_SRC,
    /if\s*\(\s*!canAccessRoute\(\s*currentUser\(\)\s*,\s*to\.meta\s*\)\s*\)\s*\{[\s\S]*?next\(\s*token\s*\?\s*\{\s*path:\s*['"]\/overview['"]\s*,\s*query:\s*\{\s*denied:\s*to\.path\s*\}\s*\}\s*:\s*['"]\/login['"]\s*\)/,
    'router.js 无权回退分支必须是 token ? { path: \'/overview\', query: { denied: to.path } } : \'/login\''
  )
  assert.doesNotMatch(
    ROUTER_SRC,
    /denied:\s*to\.fullPath/,
    'router.js denied 参数严禁传递 to.fullPath（仅允许 to.path）'
  )
})

test('接线守护：App.vue 横幅含 role="alert"、调用 resolveDeniedPath、关闭走 router.replace', () => {
  // 横幅与无权提示文案
  assert.match(
    APP_SRC,
    /<div[^>]*\bv-if="deniedPath"[^>]*\brole="alert"/,
    'App.vue 必须包含 v-if="deniedPath" 且带 role="alert" 的提示横幅'
  )
  assert.match(
    APP_SRC,
    /无权访问页面\s*\{\{\s*deniedPath\s*\}\}，已返回运营大盘。/,
    'App.vue 必须展示规范提示文案「无权访问页面 {{ deniedPath }}，已返回运营大盘。」'
  )

  // 纯函数引入与调用
  assert.match(
    APP_SRC,
    /import\s*\{[^}]*resolveDeniedPath[^}]*\}\s*from\s*['"]\.\/utils\/routeAccess['"]/,
    'App.vue 必须从 ./utils/routeAccess 导入 resolveDeniedPath'
  )
  assert.match(
    APP_SRC,
    /resolveDeniedPath\(\s*route\.query\.denied\s*\)/,
    'App.vue 必须通过 resolveDeniedPath(route.query.denied) 计算 deniedPath'
  )

  // 关闭处理
  assert.match(
    APP_SRC,
    /router\.replace\(\s*\{\s*query:\s*[^}]+\s*\}\s*\)/,
    'App.vue 关闭提示必须通过 router.replace 移除 denied 参数'
  )
})
