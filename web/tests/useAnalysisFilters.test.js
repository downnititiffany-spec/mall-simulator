// 目的：钉住一条**实现事实** —— 导出上下文里的 `filters` **只**来自信封回显
// （契约 `docs/contracts/analysis-viewmodel-r7-4.md:182`「原样回显生效筛选」），
// 并禁止注释再声称一个**不存在**的「缺失时回退到本次请求参数」。
//
// 背景：S3-35 实测登记（`docs/acceptance/s3-35-pipeline-state-owner-20260916/
// DESIGN-DIFF-REGISTER-20260916.md` §…）—— `load(requestParams)` 不保存请求参数，
// `exportContext` 只读 `ctx.filters` ⇒ 该回退**不存在**；S3-38 把注释改成与实现一致，
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

test('requestParams 在代码内恰 2 处（形参与透传），不存在"保存请求参数以备回退"', () => {
  // 只看**代码行**：L53 的 JSDoc 里也提到 `requestParams`（文档，不是行为）
  const code = src
    .split('\n')
    .filter((l) => !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*'))
  const lines = code.filter((l) => l.includes('requestParams'))
  assert.equal(lines.length, 2, `代码内 requestParams 出现 ${lines.length} 处（应为 2 处）`)
  assert.match(lines[0], /async function load\(requestParams = \{\}\)/)
  assert.match(lines[1], /fetcher\(requestParams,/)
  // 更本质的不变量：`requestParams` **只允许出现在入参位置**（前面是 `(` 或 `,`）。
  // 任何「把它当值读出来」的写法（`lastParams = requestParams`、`= { ...requestParams }`、
  // `= requestParams.x`…，无论变量叫什么名字）都会违反它 ⇒ 这正是"实现回退"的前置条件。
  // 诚实边界：这是**文本守卫、不是污点分析** —— 用 `arguments[0]`／`Object.assign(t, arguments[0])`
  // 之类**根本不写出 `requestParams`** 的写法仍可绕过，该残余绕过面已在该轮登记 §7 如实登记。
  const codeText = code.join('\n')
  for (const m of codeText.matchAll(/requestParams/g)) {
    const before = codeText.slice(Math.max(0, m.index - 8), m.index)
    assert.match(
      before,
      /[(,]\s*$/,
      `requestParams 出现在非入参位置：…${codeText.slice(Math.max(0, m.index - 40), m.index + 24)}…`
    )
  }
})
