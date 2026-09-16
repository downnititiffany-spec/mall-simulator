// 目的：钉住**页面侧**「AI 建议 → 决策草稿」入口（契约 r8 §5 补遗 v1.1，S3-42）。
// `AiAssistant.vue` 依赖 `vue`/SFC 编译，本仓无 `node_modules` ⇒ 只能做**源码文本守卫**
// （与 `tests/overviewOutsideMetric.test.js` 同型）。
// 被治的缺陷（S3-42 开工前实测）：`web/src` 无任何创建决策的调用、无 `evidenceId` 读取
// ⇒ `POST /api/v1/decisions` 从页面不可达（而权限冻结表已把它登记为「前端 api.js 实际调用的端点」）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const webSrc = join(here, '..', 'src')
const apiSrc = readFileSync(join(webSrc, 'api.js'), 'utf8')
const aiSrc = readFileSync(join(webSrc, 'views', 'AiAssistant.vue'), 'utf8')
const contextSrc = readFileSync(join(webSrc, 'utils', 'context.js'), 'utf8')
const ownerSrc = readFileSync(join(webSrc, 'utils', 'decisionDraft.js'), 'utf8')

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else if (/\.(js|vue)$/.test(name)) out.push(p)
  }
  return out
}
const rel = (p) => p.slice(webSrc.length + 1).replace(/\\/g, '/')
const allFiles = walk(webSrc)
const filesWith = (needle) => allFiles.filter((p) => readFileSync(p, 'utf8').includes(needle)).map(rel).sort()

test('api.js 暴露 decisionCreate，且打到集合路径 POST /decisions（不是 /{id}/…）', () => {
  assert.match(apiSrc, /decisionCreate:\s*\(body\)\s*=>\s*client\.post\('\/decisions',\s*body\)/, 'api.js 必须有 decisionCreate → POST /decisions')
  const line = apiSrc.split(/\r?\n/).find((l) => l.includes('decisionCreate:'))
  assert.doesNotMatch(line, /\$\{id\}|\/decisions\/'/, 'decisionCreate 不得打到 /{id}/… 路径')
  assert.doesNotMatch(line, /source|status/, 'api.js 不得替页面注入 source/status（服务端固定）')
})

test('AiAssistant 的草稿字段只来自唯一属主 utils/decisionDraft.js', () => {
  assert.match(
    aiSrc,
    /import\s*\{[^}]*buildDraftBody[^}]*\}\s*from\s*'\.\.\/utils\/decisionDraft'/,
    'AiAssistant.vue 必须从 ../utils/decisionDraft 引入 buildDraftBody'
  )
  for (const name of ['draftSuggestions', 'draftAnchor', 'anchorText', 'ANCHOR_KIND', 'DRAFT_BLOCK', 'DIRECTION_CHOICES', 'SUBMIT_REQUIREMENT_TEXT']) {
    assert.match(aiSrc, new RegExp(`import\\s*\\{[^}]*${name}[^}]*\\}\\s*from\\s*'\\.\\./utils/decisionDraft'`), `必须引入 ${name}`)
  }
  assert.doesNotMatch(aiSrc, /evidencePackageId\s*:/, '页面不得自己拼 evidencePackageId（字段口径只有一个属主）')
  assert.doesNotMatch(aiSrc, /suggestionSnapshotId\s*:/, '页面不得自己拼 suggestionSnapshotId')
})

test('页面不复刻服务端提交校验、不造锚点（无 prompt 拼字段）', () => {
  assert.doesNotMatch(aiSrc, /evalWindowDays/, 'evalWindowDays 由服务端默认，页面不得出现')
  assert.doesNotMatch(aiSrc, /missingForSubmit/, '不得复刻服务端 missingForSubmit 校验（第二属主）')
  assert.doesNotMatch(aiSrc, /\bwindow\.prompt\(|\bprompt\(/, '不得用 prompt() 拼字段/锚点')
  assert.doesNotMatch(aiSrc, /['"`]EV-/, '页面不得伪造证据包 ID 前缀')
  assert.doesNotMatch(aiSrc, /['"`]S20\d{6}/, '页面不得伪造快照号')
})

test('无可用锚点时按钮禁用：入口条件来自 draftAnchor，不来自本地猜测', () => {
  assert.match(aiSrc, /draftAnchor\(\s*\{/, '必须调用属主 draftAnchor({ evidenceId, snapshotId })')
  assert.match(aiSrc, /ANCHOR_KIND\.NONE/, '必须以 ANCHOR_KIND.NONE 判定「无锚点」')
  assert.match(aiSrc, /:disabled="[^"]*canCreateDraft/, '「转决策草稿」按钮必须以 canCreateDraft 绑定 disabled')
  assert.match(aiSrc, /v-for="[^"]*draftSuggestions/, '建议列表必须来自属主 draftSuggestions')
})

test('草稿创建走 api.decisionCreate，成功后指向决策中心', () => {
  assert.match(aiSrc, /api\.decisionCreate\(/, '创建草稿必须调用 api.decisionCreate')
  assert.match(aiSrc, /decisionNo/, '成功提示必须展示服务端返回的 decisionNo')
  assert.match(aiSrc, /决策中心/, '成功后必须指向决策中心提交审批')
  assert.match(aiSrc, /\/decisions/, '必须给出决策中心入口（/decisions）')
  assert.match(aiSrc, /SUBMIT_REQUIREMENT_TEXT/, '提交审批缺项说明必须引用属主常量，不另写一套')
})

test('证据包 ID 搬到页面（顶层 evidenceId）；占位/缺失按「未提供」显示', () => {
  assert.match(contextSrc, /evidenceId/, 'context.js 必须搬运顶层 evidenceId')
  assert.match(aiSrc, /evidence\.evidenceId/, '页面必须展示证据包 ID')
  assert.match(aiSrc, /未提供/, '缺失时必须显示「未提供」，不得留空或编造')
})

test('decisionDraft 是唯一属主：字段清单/锚点判据只此一处', () => {
  assert.deepEqual(filesWith('DRAFT_FIELDS ='), ['utils/decisionDraft.js'])
  assert.deepEqual(filesWith('ANCHOR_KIND ='), ['utils/decisionDraft.js'])
  assert.deepEqual(filesWith('export function isRealEvidenceId'), ['utils/decisionDraft.js'], 'evidenceId 真实性判据只能有一个属主')
  assert.deepEqual(filesWith('DRAFT_BLOCK ='), ['utils/decisionDraft.js'])
  assert.match(ownerSrc, /export function isRealEvidenceId/, '属主必须导出 isRealEvidenceId')
})
