// S3-34/S3-35（E5-c 可 A 类实现部分 ＋ 状态属主收敛）：`/pipeline` 页此前**不挂** `AnalysisContext`
// （其余 8 个分析页都挂），因此看不到结果所属数据源；挂上之后又必须让**加载状态**落在既有唯一属主
// `useAnalysis` 上，而不是页内自造第二套状态机。本文件是**源码文本守卫**，只证明「接线仍在」：
//   · **不能**替代渲染验证 —— 本仓库无 `web/node_modules`，`vite build` 与真浏览器均未跑
//   · 取不到的值一律显示「未知」/占位符，**不得**在页面重算或编造后端未返回的字段
// 依据：`docs/PROJECT_STATUS.md` E5-c 行（S3-26/S3-34 各推进一半、本行不关闭）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const VUE = fileURLToPath(new URL('../src/views/Pipeline.vue', import.meta.url))
const text = readFileSync(VUE, 'utf8')

// 上下文口径的全部入参都在这个调用块里（fetcher 内 `const ctx = buildFallbackContext({…})`）。
// 守卫必须打在**代码**上：探针实测过，只断言裸 token（如 /ENVELOPE_MISSING/）会被**注释**满足
// ⇒ 探针删掉真实代码行仍全绿。
const CTX_CALL = text.match(/buildFallbackContext\(\{[\s\S]*?\n\s*\}\)/)

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

test('Pipeline.vue 的加载状态由唯一属主 useAnalysis 拥有（页内不得自造第二套状态机）', () => {
  assert.match(text, /import \{ useAnalysis \} from '\.\.\/composables\/useAnalysis/)
  assert.match(text, /import \{[^}]*NON_ANALYSIS_ROW_KEYS[^}]*\} from '\.\.\/utils\/context\.js'/)
  assert.match(text, /useAnalysis\(\{\s*fetcher:[^}]*rowKeys:\s*NON_ANALYSIS_ROW_KEYS\.pipelineRuns/)
  // 上下文条绑定属主给出的 exportContext（与 Decisions/Ops 同形）
  assert.match(text, /<AnalysisContext\s+:context="exportContext"/)
  // 退休页内状态机：状态/错误只能来自 useAnalysis
  assert.ok(!/state\s*=\s*ref\(/.test(text), '页内不得自造 state ref（属主＝useAnalysis）')
  assert.ok(!/error\s*=\s*ref\(/.test(text), '页内不得自造 error ref（属主＝useAnalysis）')
  assert.ok(!/requestStatus\s*=\s*ref\(/.test(text), '页内不得自造 requestStatus ref（属主＝useAnalysis）')
  // 卸载时取消在途请求（与 Behavior/Overview/Products/Rfm/Sales 同形）
  assert.match(text, /onBeforeUnmount\(\(\) => analysis\.cancel\(\)\)/)
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
  assert.match(text, /Array\.isArray\(/)
})

test('Pipeline.vue 实例表经 pipelineRunRows 单一映射所有者渲染，并展示源数据版本与目标快照', () => {
  assert.match(text, /pipelineRunRows/)
  assert.match(text, /v-for="r in runRows"/)
  assert.match(text, /源数据版本/)
  assert.match(text, /目标快照/)
  // 刷新在读请求 loading 或写动作 busy 期间都禁用，避免与触发/重试交错刷新。
  assert.match(text, /:disabled="loading \|\| busy"/)
})

test('Pipeline.vue 实例表展示「输入批次」，且空态 colspan 与表头列数一致', () => {
  // S3-37：S3-36 已让 `pipeline_run.input_batch_id` 真的落库，但前端 `pipelineRunRows` 没搬运
  // ⇒ 页面上看不到批次。本守卫打在**页内硬编码表**上（该表不用 COLUMNS，必须同批改表头/单元格）；
  // 同时固定「列数 ＝ 空态 colspan」这个既有不变量 —— 加列忘改 colspan 会让「暂无运行记录」错位。
  assert.match(text, /<th>输入批次<\/th>/)
  assert.match(text, /r\.inputBatchId/)
  const head = text.match(/<thead><tr[^>]*>([\s\S]*?)<\/tr><\/thead>/)
  assert.ok(head, '未找到实例表表头行')
  const thCount = (head[1].match(/<th/g) || []).length
  const span = text.match(/<td colspan="(\d+)"/)
  assert.ok(span, '未找到空态单元格')
  assert.equal(Number(span[1]), thCount, '空态 colspan 必须等于表头列数')
})

test('跨页共享属主 useAnalysis 的 exportContext 必须带 missingNotice（横幅与导出件的共同来源）', () => {
  // 页面绑 `exportContext`（与 Decisions/Ops 同形）⇒ 缺失告知要能到屏幕，就必须活过这一层；
  // 本守卫读的是共享属主源码，不是本页：改坏它 9 个页面一起受影响。
  const USE = fileURLToPath(new URL('../src/composables/useAnalysis.js', import.meta.url))
  const src = readFileSync(USE, 'utf8')
  const block = src.match(/const exportContext = computed\(\(\) => \{[\s\S]*?\n  \}\)/)
  assert.ok(block, '未找到 exportContext 定义块')
  assert.match(block[0], /missingNotice:/)
})
