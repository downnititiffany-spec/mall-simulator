// 权限冻结表 ↔ 前端调用面「跨树对账」守卫（S3-43，纯测试新增，不改产品代码）
//
// 为什么要它：`ControllerPermissionCoverageTest` 的 `FROZEN_FRONTEND_EXPECTATIONS` 表头原本自称
// 「前端 api.js 实际调用的端点」，但该 Java 测试体**从不读** `web/src/api.js`（它只把冻结表与
// **扫描到的控制器端点**对比）⇒ 那是**手写镜像**，不是对账。本守卫把两侧的**源码文本**摆到一起求差：
//
//   ① Java 侧（`analytics-server/platform-app/src/test/java/.../ControllerPermissionCoverageTest.java`）
//      - `EXEMPT`：无需权限码的端点（冻结集合，只有登录/会话自身/健康检查）
//      - `FROZEN_FRONTEND_EXPECTATIONS`：端点 → 期望权限码冻结表（路径占位符统一写成 {id}）
//   ② 前端侧（`web/src/api.js`）：唯一发请求的地方（前提由本文件第一条断言守卫）
//
// 判据（四条，全部是「集合相等/包含」，不是「存在某个字符串」）：
//   A. 前端调用面 ⊆ 冻结表 ∪ 豁免表 —— 前端不可能调用「没有权限期望」的端点
//   B. 冻结表 ⊆ 前端调用面 ∪ NOT_WIRED_IN_FRONTEND —— 冻结表里前端**并没有**调用的行必须逐条登记原因，
//      不允许「声称是前端调用、实际不存在」的行静默存在（S3-42 实测到的正是这一类的 1 条）
//   C. 末段是动态参数（`/x/${id}/${action}`）的调用必须登记在 DYNAMIC_CALL_RULES，且展开出的动作取值集合
//      与冻结表同前缀行**集合相等**（把「动态调用覆盖了这些行」变成被校验的断言，而不是假设）
//   D. 冻结表 / 豁免表无重复键、无重叠
//
// 诚实边界（不得越界表述）：
//   - 本守卫读的是**源码文本**，不做求值、不做污点分析：路径由变量拼出来（非模板字面量里的 `${...}`）时
//     扫不到 —— 因此 A 的前提断言（只有 api.js 发请求）是必须的，它把「扫不到」的面钉在 0；
//   - `DYNAMIC_CALL_RULES` 里的动作取值是**存在性检查**（该字面量出现在页面源码里），不是「运行时一定会传」；
//   - 本文件在 **web 套件**里（`cd web && npm test`，node --test），**不在**统一门禁 `run-tests.ps1` 内；
//   - 它**不**证明权限码正确（那是 Java 侧 `frozenFrontendExpectations` 的职责），只证明「前端调用面 ↔ 冻结表」
//     两侧的**集合关系**与登记一致。

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative, sep } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_DIR = join(HERE, '..')
const REPO_DIR = join(WEB_DIR, '..')

const JAVA_TEST = join(
  REPO_DIR, 'analytics-server', 'platform-app', 'src', 'test', 'java',
  'com', 'graduation', 'analytics', 'controller', 'ControllerPermissionCoverageTest.java'
)
const API_JS = join(WEB_DIR, 'src', 'api.js')
const JAVA_SRC = readFileSync(JAVA_TEST, 'utf8')

// ── ① Java 侧解析 ─────────────────────────────────────────────────────────────

/** 冻结表：`FROZEN_FRONTEND_EXPECTATIONS.put("METHOD /api/v1/...", PermissionCode.X)` → "METHOD /path" */
function frozenEndpoints() {
  const out = []
  const re = /FROZEN_FRONTEND_EXPECTATIONS\.put\("([A-Z]+)\s+(\/api\/v1[^"]*)"\s*,\s*[A-Za-z_.]+\)/g
  for (const m of JAVA_SRC.matchAll(re)) out.push(`${m[1]} ${m[2]}`)
  return out
}

/** 豁免表：`EXEMPT = Set.of("...", ...)`（冻结集合；新增豁免在 Java 侧就会失败） */
function exemptEndpoints() {
  const block = /EXEMPT = Set\.of\(([\s\S]*?)\);/.exec(JAVA_SRC)
  assert.ok(block, '未在 Java 测试里找到 EXEMPT 冻结集合（解析锚点变了？）')
  return [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1])
}

// ── ② 前端侧解析 ─────────────────────────────────────────────────────────────

/** 把 api.js 里的路径字面量归一成冻结表的写法：`${x}` → `{x}`，相对路径补 `/api/v1` 前缀 */
export function normalizeCallPath(raw) {
  const p = raw.replace(/\$\{([^}]*)\}/g, (_, expr) => `{${expr.trim()}}`)
  return p.startsWith('/api/') ? p : `/api/v1${p}`
}

/** api.js 的静态调用点：`client.get('/x')` / `client.post(`/x/${id}`)` —— 第一参数必须是字符串字面量 */
export function callSites(source) {
  const out = []
  const re = /client\.(get|post|put|delete)\(\s*([`'"])([^`'"]+)\2/g
  for (const m of source.matchAll(re)) {
    out.push({ call: `${m[1].toUpperCase()} ${normalizeCallPath(m[3])}`, raw: m[3] })
  }
  return out
}

function listSourceFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...listSourceFiles(full))
    else if (/\.(js|vue)$/.test(entry.name)) out.push(full)
  }
  return out
}

// ── ③ 登记表（本守卫的「例外面」；集合相等 ⇒ 增删都必须改这里） ────────────────

/**
 * 末段是动态参数的调用：`值` 必须与冻结表同前缀行集合相等（判据 C），且每个值都能在页面源码里找到带引号字面量。
 * S3-43 实测：两条都在 `web/src/api.js:113`（`/decisions/${id}/${action}`）与 `:119`（`/admin/users/${id}/${action}`）。
 */
const DYNAMIC_CALL_RULES = [
  {
    call: 'POST /api/v1/decisions/{id}/{action}',
    values: ['submit', 'approve', 'reject', 'start', 'complete', 'cancel', 'evaluate'],
    uiSource: join('src', 'views', 'Decisions.vue'),
  },
  {
    call: 'POST /api/v1/admin/users/{id}/{action}',
    values: ['toggle', 'reset-password'],
    uiSource: join('src', 'views', 'Ops.vue'),
  },
]

/**
 * 冻结表里**前端目前并没有调用**的行 → 原因（S3-43 实测：48 条中 15 条）。
 * 这些行在 Java 侧仍是有意义的「端点 → 权限码」期望（新接口不该忘权限码），但**不是**前端调用证据。
 * 判据 B 要求本表与「冻结表 − 前端调用面」**集合相等**：新增一条没接前端的冻结行，这里必须显式登记。
 */
const NOT_WIRED_IN_FRONTEND = new Map([
  // ── AI 面（2）：前端只走 /ai/queries 与 /ai/history/my、/ai/audit/*（`git grep -n '/ai/analyses|/ai/explanations' -- web/src` 只命中 context.js 的注释）──
  ['POST /api/v1/ai/analyses', '前端无该调用（web/src 无 /ai/analyses 命中）；AI 页目前只走 /ai/queries、/ai/history/my、/ai/audit/*'],
  ['POST /api/v1/ai/explanations', '前端无该调用（仅 web/src/utils/context.js:181 注释提到该分支）；AI 页消费的是 /ai/queries 响应里的 explanation'],
  // ── 采集面（1）：前端只有 POST /ingestion/runs 与 GET /ingestion/status ──
  ['GET /api/v1/ingestion/batches', '前端无该调用；运维页只读 GET /ingestion/status（批次列表页尚未接线）'],
  // ── 运维/管理流水线面（4）：前端只有 POST /pipeline-runs、GET /pipeline-runs、GET /{id}、POST /{id}/retry（不含 admin/ 那组）──
  ['GET /api/v1/admin/pipeline-runs/recovery-report', '前端无该调用（/admin/pipeline-runs 在 web/src 零命中）；故障恢复页尚未接线'],
  ['POST /api/v1/admin/pipeline-runs/{id}/resume', '前端无该调用（/admin/pipeline-runs 在 web/src 零命中）；续跑入口尚未接线'],
  ['POST /api/v1/admin/pipeline-runs/{id}/mark-failed', '前端无该调用（/admin/pipeline-runs 在 web/src 零命中）；标记失败入口尚未接线'],
  ['POST /api/v1/admin/pipeline-runs/{id}/retry-from-stage', '前端无该调用（/admin/pipeline-runs 在 web/src 零命中）；按阶段重试入口尚未接线'],
  // ── 运行时配置面（6）：/runtime-profiles 在 web/src 全树零命中 ──
  ['GET /api/v1/runtime-profiles', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；运行时配置页尚未接线'],
  ['GET /api/v1/runtime-profiles/active', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；生效配置页尚未接线'],
  ['GET /api/v1/runtime-profiles/{id}', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；配置详情页尚未接线'],
  ['POST /api/v1/runtime-profiles', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；新建配置入口尚未接线'],
  ['PUT /api/v1/runtime-profiles/{id}', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；编辑配置入口尚未接线'],
  ['POST /api/v1/runtime-profiles/{id}/test', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；连通性测试入口尚未接线'],
  ['POST /api/v1/runtime-profiles/{id}/activate', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；切换 ACTIVE 入口尚未接线（且切 ACTIVE 属硬门禁⑤，不在本轮面内）'],
  ['POST /api/v1/runtime-profiles/{id}/disable', '前端无该调用（/runtime-profiles 在 web/src 全树零命中）；停用入口尚未接线'],
])

// ── ④ 对账面计算（供断言与失败消息共用） ──────────────────────────────────────

function reconcile() {
  const frozen = frozenEndpoints()
  const exempt = exemptEndpoints()
  const known = new Set([...frozen, ...exempt])
  const sites = callSites(readFileSync(API_JS, 'utf8'))

  const ruleByCall = new Map(DYNAMIC_CALL_RULES.map((r) => [r.call, r]))
  const unresolved = []
  const frontCalls = new Set()
  for (const site of sites) {
    // 先按字面量直接对账：`{id}` 就是冻结表里的路径占位符写法（`GET /pipeline-runs/{id}` 这类**不需要**动作规则）
    if (known.has(site.call)) {
      frontCalls.add(site.call)
      continue
    }
    const rule = ruleByCall.get(site.call)
    if (rule) {
      for (const v of rule.values) frontCalls.add(site.call.replace('{action}', v))
      continue
    }
    // 未命中冻结表又带占位符 ⇒ 要么是没权限期望的调用（判据 A 会报），要么是末段动态参数没登记（判据 C0）
    if (!/\{[^}]*\}$/.test(site.call)) frontCalls.add(site.call)
    else unresolved.push(site.call)
  }
  return { frozen, exempt, sites, frontCalls: [...frontCalls].sort(), unresolved }
}

const frozen = frozenEndpoints()
const exempt = exemptEndpoints()
const { sites, frontCalls, unresolved } = reconcile()
const known = new Set([...frozen, ...exempt])

// ── 断言 ─────────────────────────────────────────────────────────────────────

test('前提：只有 web/src/api.js 发请求（否则本守卫扫不到的面不为 0）', () => {
  const stray = []
  for (const file of listSourceFiles(join(WEB_DIR, 'src'))) {
    if (file === API_JS) continue
    if (/client\.(get|post|put|delete)\s*\(/.test(readFileSync(file, 'utf8'))) {
      stray.push(relative(WEB_DIR, file).split(sep).join('/'))
    }
  }
  assert.deepEqual(stray, [], `这些文件绕开 api.js 直接发请求，本守卫扫不到：${stray.join(', ')}`)
  // axios 只允许在 api.js 里出现（同一前提的另一半）
  const axiosImporters = listSourceFiles(join(WEB_DIR, 'src'))
    .filter((f) => /from\s+'axios'/.test(readFileSync(f, 'utf8')))
    .map((f) => relative(WEB_DIR, f).split(sep).join('/'))
  assert.deepEqual(axiosImporters, ['src/api.js'], `axios 只应在 src/api.js 里被引入：${axiosImporters.join(', ')}`)
})

test('判据 A：前端每个调用点都在冻结表或豁免表里（不调用无权限期望的端点）', () => {
  const noExpectation = frontCalls.filter((c) => !known.has(c))
  assert.deepEqual(noExpectation, [], `这些前端调用既不在冻结表、也不在豁免表：${noExpectation.join('; ')}`)
})

test('判据 C0：末段动态参数的调用必须已登记（不登记就无法对账）', () => {
  assert.deepEqual(unresolved, [], `这些调用末段是动态参数且未登记进 DYNAMIC_CALL_RULES：${unresolved.join('; ')}`)
})

test('判据 C1：动态调用的动作取值与冻结表同前缀行集合相等，且在页面源码里有带引号字面量', () => {
  for (const rule of DYNAMIC_CALL_RULES) {
    const site = sites.find((s) => s.call === rule.call)
    assert.ok(site, `DYNAMIC_CALL_RULES 登记了 ${rule.call}，但 api.js 里已无该调用（登记过期）`)
    const prefix = rule.call.replace('{action}', '')
    const frozenUnderPrefix = frozen.filter((c) => c.startsWith(prefix)).sort()
    const expanded = rule.values.map((v) => `${prefix}${v}`).sort()
    assert.deepEqual(expanded, frozenUnderPrefix,
      `${prefix} 展开集合与冻结表不一致：登记=${expanded.join(', ')} 冻结=${frozenUnderPrefix.join(', ')}`)
    const ui = readFileSync(join(WEB_DIR, rule.uiSource), 'utf8')
    for (const v of rule.values) {
      assert.ok(ui.includes(`'${v}'`), `页面源码 ${rule.uiSource} 里找不到动作字面量 '${v}'（动态调用可能已不再传它）`)
    }
  }
})

test('判据 B：冻结表逐条对账 —— 前端真调用，或已登记「前端未接线」原因（集合相等）', () => {
  const unwired = frozen.filter((c) => !frontCalls.includes(c)).sort()
  const registered = [...NOT_WIRED_IN_FRONTEND.keys()].sort()
  assert.deepEqual(unwired, registered,
    `冻结表里「前端并没有调用」的行与登记不一致：\n  实际=${JSON.stringify(unwired, null, 2)}\n  登记=${JSON.stringify(registered, null, 2)}`)
  for (const [call, reason] of NOT_WIRED_IN_FRONTEND) {
    assert.ok(typeof reason === 'string' && reason.trim().length >= 10, `${call} 的登记原因太短/为空`)
  }
})

test('判据 D：冻结表与豁免表不重叠、各自无重复键', () => {
  assert.equal(new Set(frozen).size, frozen.length, '冻结表出现重复键')
  assert.equal(new Set(exempt).size, exempt.length, '豁免表出现重复键')
  const both = frozen.filter((c) => exempt.includes(c))
  assert.deepEqual(both, [], `这些端点既在冻结表又在豁免表（口径冲突）：${both.join('; ')}`)
})

test('Java 表头不再自称「前端 api.js 实际调用的端点」，且指向本守卫（S3-43 纠正）', () => {
  const header = JAVA_SRC.slice(0, JAVA_SRC.indexOf('FROZEN_FRONTEND_EXPECTATIONS ='))
  assert.ok(!header.includes('前端 api.js 实际调用的端点'),
    'Java 冻结表表头仍自称「前端 api.js 实际调用的端点」——该说法与实际不符（测试体不读 api.js）')
  assert.ok(header.includes('web/tests/permissionReconcile.test.js'),
    'Java 冻结表表头应指向本守卫，避免下一位读者再把冻结表当成前端调用证据')
})
