// 上下文条「来源（发布方）」展示守卫（S3-26）
// 依据：指导书 V3.0 阶段 5 L164「页面统一加载/空数据/成功/失败状态，显示更新时间、来源、快照和限制」；
//       设计 V3.0 §15 L687「错误对业务用户显示简明原因和可做动作，技术用户附 source/run/stage/rule/trace」；
//       契约 analysis-viewmodel-r7-4 v1.2 §2 字段表：`source` = `metric_snapshot.source`，是**发布方**
//       （§17.6 成功快照只接受 `spark-ads`），**不是业务源身份**（不得当源身份用）。
//
// 说明：本文件是**源码结构守卫**，不渲染组件（web/ 未装 node_modules，无 SFC 编译器）。
// 行为判据（取值 / 缺值 / 非发布方取值）由 tests/envelope.test.js 的 sourceText / readEnvelope 覆盖。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const COMPONENT = read('../src/components/AnalysisContext.vue')
const COMPOSABLE = read('../src/composables/useAnalysis.js')

// 上下文条是全站唯一的信封展示位：来源一栏只在这里落地
const VIEWS = [
  'Overview.vue',
  'Sales.vue',
  'Behavior.vue',
  'Products.vue',
  'Rfm.vue',
  'AiAssistant.vue',
  'Decisions.vue',
  'Ops.vue'
]

test('上下文条展示来源，值取自信封 source 且缺值走统一文案函数', () => {
  assert.match(COMPONENT, /来源/)
  assert.match(COMPONENT, /sourceText\(ctx\.source\)/)
  // 不得写死发布方字样冒充真实返回（缺值时必须显示「未知」）
  assert.doesNotMatch(COMPONENT, /spark-ads/)
})

test('来源一栏标明「非业务源身份」，防被当成源身份使用', () => {
  assert.match(COMPONENT, /非业务源身份/)
  // 契约 v1.2 口径边界：业务源身份由 source_system/source_instance_id 承载，不由 source 承载
  assert.doesNotMatch(COMPONENT, /source_id/)
  assert.doesNotMatch(COMPONENT, /sourceCode/)
})

test('导出上下文透传来源，CSV 元信息才能自证发布方', () => {
  assert.match(COMPOSABLE, /source: ctx\.source \|\| null/)
})

test('八个分析页仍统一挂载上下文条（来源一栏随之覆盖全站）', () => {
  for (const view of VIEWS) {
    const text = read(`../src/views/${view}`)
    assert.match(text, /AnalysisContext/, `${view} 应继续挂载 AnalysisContext`)
  }
})
