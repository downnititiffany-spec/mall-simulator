// S3-34（E5-c 剩余 A 类切片）：`/pipeline` 页此前**不挂** `AnalysisContext`（其余 8 个分析页都挂），
// 因此该页看不到结果所属数据源/发布方上下文。本文件是**源码文本守卫**，只证明「接线仍在」：
//   · **不能**替代渲染验证 —— 本仓库无 `web/node_modules`，`vite build` 与真浏览器均未跑
//   · 取不到的值一律显示「未知」/占位符，**不得**在页面重算或编造后端未返回的字段
// 依据：`docs/PROJECT_STATUS.md` E5-c 行（S3-26 只推进一半、本行不关闭）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const VUE = fileURLToPath(new URL('../src/views/Pipeline.vue', import.meta.url))
const text = readFileSync(VUE, 'utf8')

// 上下文口径的全部入参都在这个调用块里。守卫必须打在**代码**上：
// 探针实测过，只断言裸 token（如 /ENVELOPE_MISSING/）会被**注释**满足 ⇒ 探针删掉真实代码行仍全绿。
const CTX_CALL = text.match(/buildFallbackContext\(\{[\s\S]*?\}\)\)/)

test('Pipeline.vue 挂载 AnalysisContext，上下文由 buildFallbackContext 拼装并如实标注非信封接口', () => {
  assert.match(text, /import AnalysisContext from '\.\.\/components\/AnalysisContext\.vue'/)
  assert.match(text, /import \{[^}]*buildFallbackContext[^}]*\} from '\.\.\/utils\/context\.js'/)
  assert.match(text, /<AnalysisContext\s+:context=/)
  assert.match(text, /:state="state"/)
  assert.match(text, /:error="error"/)
  assert.ok(CTX_CALL, '未找到 buildFallbackContext 调用块')
  // /pipeline-runs 返回裸数组（无统一信封）：调用块里必须显式登记，不能静默当信封用
  assert.match(CTX_CALL[0], /warnings:\s*\['ENVELOPE_MISSING'\]/)
})

test('Pipeline.vue 的上下文快照号取自实例 targetSnapshotId，且不把行级业务时间冒充响应级口径', () => {
  assert.match(text, /targetSnapshotId/)
  assert.ok(CTX_CALL, '未找到 buildFallbackContext 调用块')
  assert.match(CTX_CALL[0], /snapshotIds:/)
  // 裸数组接口不提供响应级业务时间/数据更新时间/口径版本/质量状态：
  // 不得从某一实例行挑一个值当成整页口径（多快照由 buildFallbackContext 既有规则如实标注）。
  // 断言范围限定在调用块内 —— 触发实例的请求体里本来就有 businessTime（创建入参），
  // 那不是上下文口径，不得被这条守卫误伤。
  assert.ok(!/businessTime:\s*[A-Za-z_$]/.test(CTX_CALL[0]), '不得把实例行 businessTime 当作响应级业务时间')
  assert.ok(!/qualityStatus:\s*[A-Za-z_$]/.test(CTX_CALL[0]), '裸数组接口不提供质量状态，不得在页面推断')
  // 形状守卫：取数结果只按数组处理（api.js 解包 body.data ⇒ List<PipelineRun>），
  // 形状意外时退化为空表而不是让页面抛错（与 Ops.vue 对同一实体的处理一致）
  assert.match(text, /Array\.isArray\(runs\.value\)/)
})

test('Pipeline.vue 实例表经 pipelineRunRows 单一映射所有者渲染，并展示源数据版本与目标快照', () => {
  assert.match(text, /pipelineRunRows/)
  assert.match(text, /v-for="r in runRows"/)
  assert.match(text, /源数据版本/)
  assert.match(text, /目标快照/)
})
