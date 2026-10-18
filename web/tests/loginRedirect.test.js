import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveLoginRedirect } from '../src/utils/routeAccess.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_DIR = join(HERE, '..')
const ROUTER_SRC = readFileSync(join(WEB_DIR, 'src', 'router.js'), 'utf8')
const LOGIN_SRC = readFileSync(join(WEB_DIR, 'src', 'views', 'Login.vue'), 'utf8')

test('resolveLoginRedirect: 合法白名单路由原样回跳（含完整 query / hash）', () => {
  // 规约必测项：/sales、/overview、带 query 的 /reports?x=1
  assert.equal(resolveLoginRedirect('/sales'), '/sales')
  assert.equal(resolveLoginRedirect('/overview'), '/overview')
  assert.equal(resolveLoginRedirect('/reports?x=1'), '/reports?x=1')

  // 根路径自身
  assert.equal(resolveLoginRedirect('/'), '/')

  // 12 个功能路由及带 query / hash 形态
  assert.equal(resolveLoginRedirect('/behavior'), '/behavior')
  assert.equal(resolveLoginRedirect('/products'), '/products')
  assert.equal(resolveLoginRedirect('/rfm'), '/rfm')
  assert.equal(resolveLoginRedirect('/ai'), '/ai')
  assert.equal(resolveLoginRedirect('/decisions'), '/decisions')
  assert.equal(resolveLoginRedirect('/reports'), '/reports')
  assert.equal(resolveLoginRedirect('/pipeline'), '/pipeline')
  assert.equal(resolveLoginRedirect('/ops'), '/ops')
  assert.equal(resolveLoginRedirect('/sources/wizard'), '/sources/wizard')
  assert.equal(resolveLoginRedirect('/runtime-profiles'), '/runtime-profiles')
  assert.equal(
    resolveLoginRedirect('/sales?date=2026-10-18&snapshot=s1#trend'),
    '/sales?date=2026-10-18&snapshot=s1#trend'
  )
})

test('resolveLoginRedirect: 异常形态与非法类型安全回落 /', () => {
  // 规约必测项：空/undefined/数组
  assert.equal(resolveLoginRedirect(''), '/')
  assert.equal(resolveLoginRedirect(undefined), '/')
  assert.equal(resolveLoginRedirect(null), '/')
  assert.equal(resolveLoginRedirect(['/sales']), '/')
  assert.equal(resolveLoginRedirect([]), '/')

  // 其他非字符串入参
  assert.equal(resolveLoginRedirect({ path: '/sales' }), '/')
  assert.equal(resolveLoginRedirect(123), '/')
  assert.equal(resolveLoginRedirect(false), '/')
})

test('resolveLoginRedirect: 协议头、双斜杠与开放重定向攻击安全回落 /', () => {
  // 规约必测项：//evil.example、https://evil.example、javascript:alert(1)
  assert.equal(resolveLoginRedirect('//evil.example'), '/')
  assert.equal(resolveLoginRedirect('//evil.example/sales'), '/')
  assert.equal(resolveLoginRedirect('https://evil.example'), '/')
  assert.equal(resolveLoginRedirect('http://evil.example'), '/')
  assert.equal(resolveLoginRedirect('javascript:alert(1)'), '/')

  // 变体与伪造协议
  assert.equal(resolveLoginRedirect('data:text/html,evil'), '/')
  assert.equal(resolveLoginRedirect('/https://evil.example'), '/')
  assert.equal(resolveLoginRedirect('/javascript:alert(1)'), '/')
})

test('resolveLoginRedirect: 反斜杠、空白、控制字符安全回落 /', () => {
  // 规约必测项：含反斜杠
  assert.equal(resolveLoginRedirect('/\\evil.example'), '/')
  assert.equal(resolveLoginRedirect('/sales\\evil'), '/')
  assert.equal(resolveLoginRedirect('\\sales'), '/')

  // 空白与控制字符
  assert.equal(resolveLoginRedirect('/sales evil'), '/')
  assert.equal(resolveLoginRedirect('/sales\tevil'), '/')
  assert.equal(resolveLoginRedirect('/sales\nevil'), '/')
  assert.equal(resolveLoginRedirect('/sales\x00evil'), '/')
  assert.equal(resolveLoginRedirect(' /sales'), '/')
})

test('resolveLoginRedirect: /login、未知路由与相对路径安全回落 /', () => {
  // 规约必测项：/login、未知路径 /admin-secret、相对路径 sales
  assert.equal(resolveLoginRedirect('/login'), '/')
  assert.equal(resolveLoginRedirect('/login?redirect=%2Fsales'), '/')
  assert.equal(resolveLoginRedirect('/admin-secret'), '/')
  assert.equal(resolveLoginRedirect('/mall'), '/')
  assert.equal(resolveLoginRedirect('sales'), '/')
  assert.equal(resolveLoginRedirect('overview'), '/')

  // 白名单之外的子路径与畸形路径
  assert.equal(resolveLoginRedirect('/sales/extra'), '/')
  assert.equal(resolveLoginRedirect('/unknown-path'), '/')
})

test('接线守护：router.js 未登录守卫携带 redirect 参数', () => {
  assert.match(
    ROUTER_SRC,
    /next\(\s*\{\s*path:\s*['"]\/login['"]\s*,\s*query:\s*\{\s*redirect:\s*to\.fullPath\s*\}\s*\}\s*\)/,
    'router.js 未登录拦截分支必须使用带 redirect: to.fullPath 的对象跳转'
  )
})

test('接线守护：Login.vue 引入并调用 resolveLoginRedirect', () => {
  assert.match(
    LOGIN_SRC,
    /import\s*\{\s*resolveLoginRedirect\s*\}\s*from\s*['"]\.\.\/utils\/routeAccess['"]/,
    'Login.vue 必须从 ../utils/routeAccess 引入 resolveLoginRedirect'
  )
  assert.match(
    LOGIN_SRC,
    /import\s*\{[^}]*useRoute[^}]*\}\s*from\s*['"]vue-router['"]/,
    'Login.vue 必须从 vue-router 引入 useRoute'
  )
  assert.match(
    LOGIN_SRC,
    /const\s+route\s*=\s*useRoute\(\)/,
    'Login.vue 必须声明 const route = useRoute()'
  )
  assert.match(
    LOGIN_SRC,
    /router\.push\(\s*resolveLoginRedirect\(\s*route\.query\.redirect\s*\)\s*\)/,
    'Login.vue onSubmit 登录成功后必须调用 router.push(resolveLoginRedirect(route.query.redirect))'
  )
})
