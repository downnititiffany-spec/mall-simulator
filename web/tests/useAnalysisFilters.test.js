// 目的：钉住一条**实现事实** —— 导出上下文里的 `filters` **只**来自信封回显
// （契约 `docs/contracts/analysis-viewmodel-r7-4.md:182`「原样回显生效筛选」），
// 并禁止导出上下文把本次请求参数当成后端生效筛选的回退。
//
// 背景：S3-35 实测登记（`docs/acceptance/s3-35-pipeline-state-owner-20260916/
// DESIGN-DIFF-REGISTER-20260916.md` §…）—— 导出上下文 `exportContext` 只读 `ctx.filters`；
// 现在保留最近一次页面筛选仅用于用户切换已发布快照后的重新取数，不用于导出元信息，
// 本文件是"不许漂回去"的守卫（`useAnalysis.js` 依赖 `vue`，本仓无 `node_modules`
// ⇒ 只能做源码文本守卫，与 `tests/pipelinePage.test.js` 同型）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const src = readFileSync(join(here, '..', 'src', 'composables', 'useAnalysis.js'), 'utf8')
// 去掉 Markdown 强调符与反引号、压缩空白：让断言钉「语义措辞」而不是排版
const plain = src.replace(/[`*]/g, '').replace(/\s+/g, ' ')

test('useAnalysis 注释不再声称「回退到本次请求参数」（该回退不存在）', () => {
  // 在**归一化文本**上断言：原文件里「**不**回退…」的强调符会让后顾断言看不到「不」；
  // 「**不**回退到本次请求参数」是**正确**表述，只有**非否定**形式才算漂移
  assert.doesNotMatch(plain, /(?<!不)回退到本次请求参数/)
})

test('useAnalysis 注释正面写明 filters 只取信封回显且不回退', () => {
  assert.match(plain, /filters 只取信封回显/)
  assert.match(plain, /不回退到本次请求参数/)
})

test('exportContext 的 filters 只读 ctx.filters，不引用请求参数', () => {
  const line = src.split('\n').find((l) => l.includes('filters:'))
  assert.ok(line, '未找到 exportContext 的 filters 取值行')
  assert.match(line, /filters:\s*ctx\.filters/)
  assert.doesNotMatch(line, /requestParams/)
})

test('requestParams 只用于输入与刷新筛选，不作为导出元信息回退', () => {
  // 只看代码行：分页切换快照时保留最近筛选，导出仍只取信封的有效 filters。
  const code = src
    .split('\n')
    .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*'))
  const lines = code.filter((l) => l.includes('requestParams'))
  assert.equal(lines.length, 3, `代码内 requestParams 出现 ${lines.length} 处（应为 3 处）`)
  assert.match(lines[0], /async function load\(requestParams = \{\}\)/)
  assert.match(lines[1], /lastRequestParams = \{ \.\.\.requestParams \}/)
  assert.match(lines[2], /effectiveParams = \{ \.\.\.requestParams \}/)
  assert.match(src, /void load\(lastRequestParams\)/)
  const exportBlock = src.slice(src.indexOf('const exportContext'), src.indexOf('/**', src.indexOf('const exportContext')))
  assert.doesNotMatch(exportBlock, /requestParams/)
})
