# S3-35 设计差异登记 —— `/pipeline` 取数状态收敛到唯一属主 ＋ 缺失告知链路修复（E5-c 续）

* 日期：2026-09-16
* 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
* 类别：**A 类（实现/加性 ＋ 内部重复属主退休）** —— 不触发 11 门（逐门核对见 §6）
* 上游：S3-34 交付了 `/pipeline` 的上下文条（`docs/acceptance/s3-34-pipeline-source-identity-20260916/`）
* 契约变更：`docs/contracts/analysis-viewmodel-r7-4.md` 加一段**前端归一化视图补记**（加性，见 §3）

## §0 证据边界（先说清楚哪些是实测、哪些不是）

1. 本轮所有「通过」均指 `web/` 前端套件：`npm test`（= `node --test "tests/**/*.test.js"`，纯 `node:test`，**不需要** `web/node_modules`）。
2. 该套件**不属于**统一门禁 `scripts/run-tests.ps1`（门禁覆盖 analytics-server / mall-simulator / synthetic-data-generator 与 spark 档）。本轮改动全在 `web/**`，**未跑也无需跑**统一门禁；**禁止**把 111 说成「统一门禁通过」。
3. **未做渲染验证**：`web/node_modules` 缺失 ⇒ `vite build` 与真浏览器均未跑。本轮的页面断言是**源码文本守卫**，只证明接线仍在，**不等于**渲染/像素验证。
4. 未新增后端调用、未改 `analytics-server/**` ⇒ 无新增接口实测；`/pipeline-runs` 真实响应中 `sourceDataVersion` 的取值分布（含 NULL 可能）**仍未测**（沿用 S3-34 的未测边界）。

## §1 本轮行前提与实测改写

S3-35 不是 backlog 里的独立行，而是 **S3-34 自身未做完的收口**：S3-34 给 `/pipeline` 挂上上下文条时，在页面里**另起了一套加载状态机**（页内 `state`/`error` ref），而仓库既有事实是「页面加载状态由唯一属主 `useAnalysis` 拥有」。

开工前实测（全部本轮真跑，见 §5 命令）：

* F1：`web/src/views/` 共 10 个页面，**9 个**挂 `AnalysisContext`（`Login.vue` 不挂，正确）；9 个都传 `:state="state" :error="error"`。
* F2：其中 **8 个**的 `state`/`error` 来自 `useAnalysis`；**只有 `Pipeline.vue`（S3-34 引入）** 是页内 `ref` ⇒ 同一套状态词汇存在两个属主。
* F3：非信封接口页面的既有约定（`Decisions.vue` L124-140、`Ops.vue` L266/L284/L295）：**在 fetcher 内**用 `buildFallbackContext()` 拼上下文并返回，状态/导出上下文交给 `useAnalysis`，页面绑 `:context="exportContext"`。
* F4：`NON_ANALYSIS_ROW_KEYS`（`context.js` L222-227）是「非信封接口页面行键」的唯一属主，**没有** `/pipeline` 的行键。
* F5：`api.pipelineRuns(limit = 10)` 不接受 options ⇒ 无法传 `AbortSignal`；同文件既有先例 `snapshots(limit, options)` / `quality(limit, options)` 用 `...(options || {})`。
* F6：`readEnvelope()`（`envelope.js` L22-40）返回**固定字段集**，**不含** `missingNotice`；而 `AnalysisContext.vue:21`（页横幅）与 `csv.js:55`（导出件「# 上下文缺失」行）都读 `missingNotice`。
* F7：`buildFallbackContext()`（`context.js` L151）**会**生成 `missingNotice`；`Decisions.vue:134`、`Ops.vue:284/343` 的 fetcher **也显式带上了**该字段 ⇒ 生产者的意图明确存在。
* F8（本轮关键发现）：`useAnalysis.exportContext`（`useAnalysis.js` L35-47）**只**映射 snapshotId/businessTime/dataUpdatedAt/source/definitionVersion/qualityStatus/filters/warnings ⇒ **`missingNotice` 在归一化处被丢弃**。

**改写结论**：F6+F8 合起来说明「取不到就如实标注」这条核心纪律，在经 `useAnalysis` 的页面上**只活到单元层、到不了屏幕**（横幅不显示、导出件不写该行）。S3-34 把 `:context` 从「页内 computed」换成 `exportContext` 时，我**亲手删掉了**该页本可显示的缺失横幅 —— 这不是历史遗留，是本轮必须补上的差额。

## §2 开工前实测命令（可复现）

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev\web'
# F1/F2：页面与状态来源
Get-ChildItem src\views\*.vue | ForEach-Object { Select-String -Path $_.FullName -Pattern 'AnalysisContext|useAnalysis|state = ref|error = ref' }
# F3：非信封页面约定
Get-Content src\views\Decisions.vue -Encoding UTF8 | Select-Object -Index (123..140)
# F4/F7：行键属主与 missingNotice 生产者
Select-String -Path src\utils\context.js -Pattern 'NON_ANALYSIS_ROW_KEYS|missingNotice'
# F5：api 先例
Select-String -Path src\api.js -Pattern 'snapshots:|quality:|pipelineRuns:'
# F6/F8：消费端与归一化
Get-Content src\utils\envelope.js -Encoding UTF8 | Select-Object -Index (21..40)
Get-Content src\composables\useAnalysis.js -Encoding UTF8 | Select-Object -Index (34..47)
```

## §3 本轮冻结的口径声明

1. **单一状态属主**：`/pipeline` 的 loading/stale/error/empty/ready 一律由 `useAnalysis` 计算；页内**不得**再持有 `state`/`error`/`requestStatus`。页面只保留与取数无关的交互态（`busy` 触发中、`runResult` 触发结果）。
2. **上下文属主不变**：响应级上下文仍由 `buildFallbackContext()` 拼（唯一非信封上下文属主），页面绑 `exportContext`（与 `Decisions/Ops` 同形）。裸数组接口不提供的**响应级**字段（业务时间/数据更新时间/口径版本/质量状态）**不得**从某一行取值冒充整页口径；多快照由既有规则如实标注。
3. **缺失告知链路**：`missingNotice` 是**前端**生成的"接口没给什么"说明，随 `readEnvelope()` 归一化透传，并由 `exportContext` 交给页横幅与导出件；后端统一信封无此字段 ⇒ 归一化为 `null`，**不臆造**。
4. **契约先行（顺序偏差如实登记）**：`docs/contracts/analysis-viewmodel-r7-4.md` 记录的**后端信封字段表未变**（无 `missingNotice`），本轮只加"前端归一化视图补记"。按契约文件 L3-4「先改本文件再改代码」，本轮实际顺序是**先写失败测试（TDD）后改代码、契约补记在同一次提交内完成** —— 偏差如实登记，不追认成"先改契约"。
5. **无行为删除**：除页内重复状态机外，无任何已发布行为被删除；`/pipeline-runs` 的请求参数在不传 `options` 时与改前逐字节一致（`{ params: { limit } }`）。

## §4 实现面（7 文件，全部 `web/**` ＋ 1 个契约文档；代码/测试 +121/−48）

| 文件 | 改动 |
| --- | --- |
| `web/src/utils/context.js` | `+3/−1`：`NON_ANALYSIS_ROW_KEYS` 加性登记 `pipelineRuns: ['pipelineRuns']`（属主内登记，不新建属主） |
| `web/src/api.js` | `+3/−1`：`pipelineRuns(limit, options)` 支持 `...(options \|\| {})`（与 `snapshots/quality` 同形，供 `AbortSignal`）；不传时行为不变 |
| `web/src/utils/envelope.js` | `+5/−0`：`readEnvelope()` 透传 `missingNotice`（`asText` ⇒ 缺省 `null`） |
| `web/src/composables/useAnalysis.js` | `+4/−1`：`exportContext` 带 `missingNotice`（横幅与导出件的共同来源） |
| `web/src/views/Pipeline.vue` | `+42/−38`：删页内状态机；`fetchRuns(_params, signal)`＋`useAnalysis`；`:context="exportContext"`；刷新按钮沿用 `:disabled="loading"`；`onBeforeUnmount(() => analysis.cancel())` |
| `web/tests/context.test.js` | `+29/−0`：行键属主用例（加性、含既有键不可少） ＋ `missingNotice` 过 `readEnvelope` 的链路用例 |
| `web/tests/pipelinePage.test.js` | `+35/−7`：4 条页面守卫＋1 条跨页属主守卫（`exportContext` 必须带 `missingNotice`） |
| `docs/contracts/analysis-viewmodel-r7-4.md` | 前端归一化视图补记（加性，不改后端字段表） |

## §5 证据矩阵（全部本轮真跑）

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| RED-1 | `npm test` | `tests 109 / pass 106 / fail 3`，`exit=1`；3 红 = 行键未登记、未用 `useAnalysis`、`loading` 未接 |
| GREEN-1 | `npm test` | 2 红残留：守卫正则要求 `}))`，重构后调用块收尾为 `})` ⇒ **测试侧**修正为 `\n\s*\}\)` |
| GREEN-2 | `npm test` | `109 / 109 / 0`，`exit=0` |
| 探针 D | 复原页内 `const state = ref('loading')` | `109/108/1`，`exit=1`，唯一红＝「唯一属主」守卫；复原后 `Get-FileHash` **一致** |
| 探针 E | 删 `context.js` 的 `pipelineRuns: ['pipelineRuns']` | `109/108/1`，`exit=1`，唯一红＝行键属主用例；复原一致 |
| RED-2 | `npm test` | `111 / 109 / 2`，`exit=1`；2 红 = 链路用例＋跨页属主守卫 |
| GREEN-3 | `npm test` | 1 红残留：`asText` 缺省为 `null` 而非 `''` ⇒ 测试断言改为本归一化器既有约定（`null`） |
| GREEN-4 | `npm test` | `111 / 111 / 0`，`exit=0` |
| 探针 F | 删 `envelope.js` 的 `missingNotice` 透传 | `111/110/1`，`exit=1`，唯一红＝链路用例；复原一致 |
| 探针 G | 删 `useAnalysis` 的 `missingNotice` | `111/110/1`，`exit=1`，唯一红＝跨页属主守卫；复原一致 |
| 收口 | `npm test` | `111 / 111 / 0`，`exit=0` |

探针合计：**D/E/F/G 四个探针各只产生 1 条红**，说明四条守卫都有牙；两次「只绿不红」的修复分别源于**守卫正则形状**（GREEN-1）与**缺省值约定**（GREEN-3），均已在表内如实登记，不是抹平失败。

**终态指纹（`Get-FileHash -Algorithm SHA256` 前 16 位，提交前实测）**：
`api.js 98C94753389790A3`、`useAnalysis.js 0EC31B25FCA55522`、`context.js 4A0C5881B3EEEB3E`、`envelope.js B01112E3915A68CB`、`Pipeline.vue EA8D62970F593B37`、`context.test.js A50CE21C805FC3B9`、`pipelinePage.test.js 05F53EAFA4320B18`。

## §6 类别判定与 11 门逐门核对

判定 **A 类（实现/加性＋内部重复属主退休）**。逐门：

①DROP 表/列 ✗ ②改已有字段类型或既有业务语义 ✗（`missingNotice` 为前端新增字段，后端信封字段表未变）③改已发布 Flyway migration ✗ ④写/迁移正式 3306 数据 ✗ ⑤切 ACTIVE ✗ ⑥改 `contract-specs/**` 已有契约语义 ✗（只改 `docs/contracts/**`，加性）⑦改 V3.0 总体架构 ✗ ⑧改正式项目范围 ✗ ⑨删除已发布功能 ✗（退休的是 S3-34 当轮引入的**页内重复状态机**，非已发布功能；页面对外可见行为只增不减：新增 stale/empty 四态、在途取消、缺失横幅）⑩引入 V3.0 未规划大型基础组件 ✗ ⑪重大长期架构分叉 ✗（收敛到**既有**属主，方向唯一）。

## §7 未测与边界（不得越界表述）

1. **渲染未验**：`web/node_modules` 缺失 ⇒ 未跑 `vite build`、未开浏览器；`AnalysisContext` 横幅是否真的出现在 `/pipeline` 上**未亲眼验证**（链路在源码层已可证：`readEnvelope` ⇒ `exportContext` ⇒ `AnalysisContext.vue:21`，但"链路通"≠"渲染对"）。
2. **`useAnalysis.exportContext` 无单元测试**：该文件 `import { ... } from 'vue'`，而 `web/node_modules` 缺失 ⇒ 本仓库测试**无法**实例化它；探针 G 的红来自**源码文本守卫**，只证明该字段在源码里存在，不证明运行时值。
3. **`@click="load"` 会把 MouseEvent 当 `requestParams` 传入**：本轮实测 `useAnalysis.load()`（L55-78）**不存** `requestParams`，`fetchRuns(_params, ...)` 亦忽略 ⇒ 无泄漏到上下文/导出的路径；但这是"当前代码如此"，非契约保证。另注：`useAnalysis.js` L33 注释称「filters 缺失时回退到本次请求参数」，与实现（只用 `ctx.filters`）**不一致**，本轮未改、未认领。
4. **后端真实响应未测**：本轮 0 个后端调用；`/pipeline-runs` 的 `sourceDataVersion`/`targetSnapshotId` 真实分布仍未测。
5. **E5-c 未关闭**：`/pipeline` 只做到"挂上上下文条 ＋ 实例级溯源列"；**业务源身份**（`source_id`/`sourceCode`/per-source namespace）仍是 **B 类、未实现**，行不关。

## §8 遗留与待裁决

* **R-1（登记，本轮未做）**：`useAnalysis.js` L33 注释与实现口径不一致（filters 回退）。属文档级不一致，A 类可改；本轮只登记，避免与 S3-35 混在一个提交里。
* **R-2（登记，沿用 S3-34 R-1）**：`pipeline_run.input_batch_id` 恒 NULL（无生产者）；E5-c 行的「业务源身份」仍待总控口径。
* **R-3**：`web/node_modules` 缺失使 `useAnalysis`/SFC 层长期不可测 ⇒ 若论文必须给出"页面渲染实测"，需先解决依赖安装（**环境类事项，非本会话可解**）。

## §9 反熵声明（本轮）

* **本轮存在真实的属主退休**：删除 `Pipeline.vue` 页内 `state`/`error`/`loadRuns` 这套**第二个**页面加载状态机，收敛到既有唯一属主 `useAnalysis`（F2 证明它是唯一例外）。守卫以**否定断言**固定该边界（页内不得再出现 `state = ref(`）。
* **不新建属主**：`missingNotice` 的修复放在**既有**两个属主内（归一化器 `readEnvelope` ＋ 导出上下文 `exportContext`），没有新增字段映射层、没有新增页面私有副本；行键登记进既有 `NON_ANALYSIS_ROW_KEYS`。
* **保留 fallback 的理由**：非信封接口的 `buildFallbackContext` 是**既有**降级路径（Decisions/Ops 在用），本轮不合并、不删除；`/pipeline` 只是成为它的第 9 个调用方。
* **未删任何已发布行为**：`api.pipelineRuns` 不传 `options` 时请求形状与改前一致；`readEnvelope` 只增字段。
