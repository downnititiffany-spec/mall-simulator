# Deferred Verification Plan

> 状态：CURRENT
> 用途：统一登记“代码已实现、独立验证尚未执行/尚未完全执行”的工作项。Code Agent 后续按本文件批量验证；Codex Work 只在高价值节点按需调用。
> 规则来源：`docs/governance/DEVELOPMENT_AND_VERIFICATION_RULES.md`、Decision Log D-009。

## 1. 使用规则

- 每个工作项必须固定一个被测 commit SHA；测试方不得用“当前最新”替代它。
- ChatGPT 可以继续开发并列模块；后续 commit 不自动改变旧条目的被测 SHA。
- 若后续实现覆盖了旧工作项的行为，旧条目标记 `SUPERSEDED`，新增最终被测 SHA。
- Code Agent：只跑既有测试/环境验证；不得改 Git。
- Codex Work：只在核心安全、重大迁移、正式阶段验收等高价值节点做主动找反例/故障注入；普通小修与常规批次不默认调用，不得改 Git。
- 独立验证结果返回 ChatGPT，由 ChatGPT 判断并修改生产代码/测试/设计。

状态：`PENDING / PARTIAL / PASS / FAIL / SUPERSEDED`。

## 2. 当前待验证队列

### V-001 — S3-53 AI evidenceId 形状兼容

- **Status**：SUPERSEDED → 由 V-004 覆盖最终 ID 形状与草稿锚点行为
- **Implementation baseline**：`4480d28aa5473a24e09333ea09b7e1554d824fad`
- **Implementation commits**：`0054870`（生产逻辑）、`0ebd0e8`（developer tests）、`f1e1205`（Decision Log）、`4480d28`（原 Test Handoff）
- **Area**：`web/src/utils/context.js`、`web/src/utils/decisionDraft.js`、`web/src/views/AiAssistant.vue`
- **Risk**：低—中；前端响应形状兼容，不改后端契约、不改数据库、不改权限。
- **Blocks further development**：NO。仅阻塞依赖“evidenceId fallback 已经在真实前端链路中确认”的后续工作；其它阶段6并列项可继续。

#### Invariants

1. `/ai/queries` 顶层真实 `evidenceId` 优先；
2. 顶层缺失/占位时可回退 `explanation.evidence.evidenceId`；
3. 空白、`unknown`、`UNKNOWN` 不得当真实 ID；
4. 真实 evidence package ID 优先于 snapshot 锚点；
5. 决策草稿请求只提交一种锚点；
6. 前端不得生成/猜测 evidenceId。

#### Code Agent later

本条已被 V-004 取代，不再单独测试旧 SHA。

#### Codex Work later

本条已被 V-004 取代；原攻击面并入 V-004，且只在后续高价值验收节点需要时调用。

#### Existing detailed handoff

`docs/acceptance/s3-53-ai-evidence-id-fallback-20260916/TEST-HANDOFF.md`

### V-002 — Web 统一验证入口

- **Status**：PASS（2026-09-17 Code Agent，commit `080b8b0e1234416f1dc884bed4f1948e75464070`）
- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- **Implementation commits**：`e36a350`（`npm run verify`）+ `db810d6`（结构守卫）+ `351fee9`（Decision Log D-011）
- **Area**：`web/package.json`、`web/tests/packageScripts.test.js`
- **Risk**：低；只增加前端验证入口与守卫，不改生产运行时代码。
- **Blocks further development**：NO。

#### Invariants

1. 统一入口唯一命令为 `npm run verify`；
2. 先执行 `npm test`，成功后再执行 `npm run build`；
3. 不把 build 成功替代单测通过；
4. 该入口不宣称覆盖真实浏览器/E2E/后端联调。

#### Execution evidence — 2026-09-17

- `cd web; npm run verify` → exit `0`；
- Node `v24.16.0` / npm `11.13.0`；
- Node tests `187/187` PASS；
- Vite production build 成功（671 modules，6.45s）；
- `verify` 实读为 `npm test && npm run build`。

**Limit**：本轮测试全绿，因此“单测失败时 `&&` 是否真正阻止 build”只具结构性证据，没有故意制造失败的运行时证据；该限制不影响 V-002 当前 PASS，但后续若改脚本需重新验证。

#### Codex Work later

普通批次不调用。若未来前端发布/合并 main 前需要对抗验证，可重点看短路、递归入口、Windows shell 行为与旁路入口。

### V-003 — S3-54 AI 结论 summary 展示链

- **Status**：PARTIAL（Code Agent：工具函数/源码接线测试通过；真实浏览器链未测）
- **Implementation baseline**：`80623a335e9df1b9fc758cb22172d72032b312db`
- **Implementation commit**：`80623a3`
- **Area**：`web/src/utils/context.js`、`web/src/views/AiAssistant.vue`、`web/tests/aiSummaryContext.test.js`
- **Risk**：低；只修前端只读展示链，不改后端契约、LLM 生成、SQL、安全或数据库。
- **Blocks further development**：NO。

#### Invariants

1. 页面结论只来自 `explanation.summary`；
2. summary 缺失/空白时返回 `null`，页面显示“后端未给出结论文本”；
3. `query.summary`、查询行或 evidence 字段不得替代 ExplanationResult.summary；
4. 不在前端生成、推导或改写业务结论。

#### Execution evidence — 2026-09-17

`npm run verify` 全绿，直接相关 4 条用例通过：只搬运 `explanation.summary`、空白降级、`query.summary` 不得覆盖、页面源码接线消费 `evidenceContext.summary`。

**Remaining runtime gap**：没有真实 Vue/browser 渲染 + 后端联调；HTML/特殊字符实际渲染与页面是否存在运行时旁路仍未验证。阶段5真实页面联调时补证据，不为此单独停开发。

### V-004 — S3-55/S3-56 AI 标识严格形状与草稿锚点单一判据

- **Status**：PARTIAL（Code Agent：工具函数/源码接线测试通过；真实浏览器→提交链未测）
- **Implementation baseline**：`88c715e3976469ead3ff1e8e73dfa00a41d22dc6`
- **Implementation commits**：`617642c`（context 严格 ID 读取）+ `776bc74`（decisionDraft 去二次 trim）+ `88c715e`（developer tests）+ `28bd4ea`（Decision Log D-013）
- **Area**：`web/src/utils/context.js`、`web/src/utils/decisionDraft.js`、`web/tests/decisionDraft.test.js`、`web/tests/aiEvidenceIdFallback.test.js`
- **Risk**：低—中；前端 fail-closed 收紧，不改后端 ID 生成与决策契约。
- **Blocks further development**：NO。后续若依赖“污染 ID 在真实浏览器链路确实被拒绝”才需要先验证；其它并列开发继续。

#### Invariants

1. ID 只能是后端返回的精确字符串，不 trim、不数字转串；
2. `unknown` 任意大小写均无效；
3. 顶层真实 evidenceId 仍优先，顶层无效才回退嵌套真实 evidenceId；
4. `decisionDraft.js` 不再建立第二个 ID 归一化规则；
5. evidence package 与 snapshot 锚点仍二选一；
6. 两类 ID 都无效时 fail-closed，不构造草稿请求。

#### Execution evidence — 2026-09-17

`npm run verify` 187/187 全绿；相关用例确认：占位/空白 ID 为 null、证据包锚点优先且二选一、带空白/数字/任意大小写 unknown 不被 trim/转串接受、无锚点拒绝构造请求。

**Remaining runtime gap**：真实 `AiAssistant` 展示 → `draftAnchor` → `buildDraftBody` → HTTP 提交未做浏览器/E2E 联调；阶段5/6 联调时补。

### V-005 — S3-57 Explanation provider provenance

- **Status**：PASS（unit/default 层；2026-09-17 Code Agent 在 `e0c7d91052773dd2183bb24fc48969ada2613af2` 复测确认）
- **Implementation baseline**：`a134df88aa9d31ef8b26fc00f4785a5cbc5c2362`
- **Implementation commits**：`97ed8ac`（ExplanationResult/调用链来源）+ `d7780d4`（控制器直读 providerUsed）+ `a134df8`（developer tests）
- **Fixture correction**：`3e0d3bc`（端点测试显式 stub `llm.providerName()="mock-provider"`，断言真实 provider 名称；生产逻辑不回退）
- **Area**：`analytics-server/ai-decision`、`analytics-server/platform-app/AiController`
- **Risk**：中；加性响应字段与来源事实修正，不改 LLM 安全策略。
- **Blocks further development**：NO；真实 provider/外网质量仍留到阶段6集成验收。

#### Invariants

1. 模型输出实际被采用时 `providerUsed = llmProvider.providerName()`；
2. provider 不可用、调用异常、数值守卫拒绝而最终回退模板时 `providerUsed = template`；
3. 模型输出即使与模板文本逐字相同，也不能被误判为 template；
4. 控制器不得再通过文本比较反推 provider。

#### First execution — 2026-09-17

- `ExplanationProviderProvenanceTest`：3/3 PASS；
- 旧 `AiExplanationEndpointTest.providerUsedIsLlmWhenRewriteAccepted`：FAIL，expected `llm` but actual `null`；
- 根因裁决：旧 Mockito fixture 没有 stub `providerName()`，而 D-014 已明确生产响应必须直读 provider 名称；真实 provider 实现返回非空名称。故修测试夹具，不回退生产代码。

#### Retest evidence — 2026-09-17

被测精确 SHA：`e0c7d91052773dd2183bb24fc48969ada2613af2`。

- 最小端点复测：`AiExplanationEndpointTest` 7/7 PASS，`providerUsedIsLlmWhenRewriteAccepted` 实际断言值为 `mock-provider`；
- default 复测临时使用 `-AllowCountDrift` 只用于区分功能失败与登记漂移；analytics-server = 1011（F=1/E=0/S=1），`AiExplanationEndpointTest` 7/7 PASS、`ExplanationProviderProvenanceTest` 3/3 PASS；
- 唯一失败仍是既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 but was 0）；没有新增失败；
- 真实 LLM provider、真实外网、真实浏览器/E2E 本轮未覆盖，因此本条 PASS 只代表 unit/default 层。

### V-006 — S3-58 AI Text-to-SQL timeout classification

- **Status**：PASS（unit/default 层；真实 3307/JDBC 超时仍未测）
- **Implementation baseline**：`0c5df95ad835ba214ec1788cd76fba85d8ab7120`
- **Implementation commits**：`054408e`（EXECUTE/服务层）+ `bc3dac5`（EXPLAIN）+ `0c5df95`（developer tests）
- **Area**：`analytics-server/ai-decision`
- **Risk**：中；错误分类修正，不改 SQL 允许/拒绝规则。
- **Blocks further development**：NO；但进入真实 MySQL 慢查询/超时验收前必须补运行证据。

#### Invariants

1. `SQLTimeoutException` → `FAILED + QUERY_TIMEOUT`；
2. Spring `QueryTimeoutException`（EXPLAIN）→ `QUERY_TIMEOUT`，不得冒充 `SQL_COST_TOO_HIGH`；
3. 普通 EXPLAIN 异常仍 fail-closed 为 `SQL_COST_TOO_HIGH`；
4. 超时同样写 `ai_query_history`，errors 含稳定码；
5. 本轮不声称 `/api/v1/ai/queries` 已改 HTTP 504。

#### Execution evidence — 2026-09-17

`AiQueryTimeoutMappingTest` 3/3 PASS；ai-decision 当轮汇总相关模块无 F/E。未执行真实 3307 慢查询，不据此声称真实 JDBC timeout 或 HTTP 状态码已验收。

### V-007 — S3-59 AI operation audit failure classification

- **Status**：PASS（unit/default 层；operation_audit_log 真库写入仍未测）
- **Implementation baseline**：`eecd6839ad21ae12f2dbc5018a009741e929f025`
- **Implementation commits**：`a6e3639`（生产逻辑）+ `eecd683`（developer tests）
- **Area**：`analytics-server/platform-app/AiController`
- **Risk**：低—中；只修 operation audit 成败分类。
- **Blocks further development**：NO；但正式审计验收前必须补真库证据。

#### Invariants

1. `FAILED`、`REJECTED`、`ERROR*`、`FAIL*` 均走 `audit.failure`；
2. null QueryResult / null status fail-closed；
3. `EXECUTED`、`REPAIRED`、`GENERATED` 不误记失败；
4. QueryResult 状态机本身不被本项改写。

#### Execution evidence — 2026-09-17

`AiControllerAuditStatusTest` 3/3 PASS。测试环境未应用 V14，日志明确显示 `operation_audit_log` 不存在，因此当前只证明 helper/controller 分类；真实审计表写入留到数据库集成验收。

### V-008 — S3-60 AI explanation 回退原因准确性

- **Status**：PENDING
- **Implementation baseline**：`0a77c21a21c3ccb782c75ca97f5c453f7a916f93`
- **Implementation commits**：`8ab0058`（生产逻辑）+ `97d6e83`（developer tests）+ `0a77c21`（Decision Log D-018）
- **Area**：`analytics-server/ai-decision/ExplanationService`、`ExplanationEvidenceTest`
- **Risk**：低—中；只纠正模板回退原因与内部分类，不新增公开字段，不改 Provider 调用次数、SQL、权限或快照策略。
- **Blocks further development**：NO；后续若要正式声称“AI 失败原因可解释”前必须完成独立验证。

#### Invariants

1. Provider 超时/限流/网络/鉴权/格式失败不得显示为“数值校验失败”；
2. 空摘要/超长摘要属于摘要形状/长度守卫，不属于数值守卫；
3. 数值越界仍由原 `SUMMARY_NUMBER_GUARD` 拒绝并明确说明数值校验；
4. 已经真实尝试模型调用后失败，问数解释不得声称“未调用大模型”；
5. 所有回退最终仍 `providerUsed=template`，成功采用模型时仍记录真实 provider；
6. limitation 只暴露稳定原因类别，不回显 Provider 原始异常 message/响应体/URL/凭据/堆栈。

#### Code Agent later

在精确 SHA（至少包含 `8ab0058` + `97d6e83`）上执行：

1. `ExplanationEvidenceTest`；预期当前源码定义 7 条用例全部通过；
2. `ExplanationProviderProvenanceTest`；必须继续 3/3 PASS，证明 D-014 未回归；
3. default 全量。以独立实测计数为准；按本轮代码增量推导 analytics-server **预计**从已确认 1011 增至 1013，但在 Code Agent 真跑前这只是预计值，不写成已验证事实。

当前 `scripts/run-tests.ps1` 的 analytics 数量登记仍是 1002。因为 S3-60 又新增 2 条 developer tests，本轮暂不先把登记硬改成 1011；等 Code Agent 独立确认最终计数后，一次同步到最终稳定值，再恢复不带 `-AllowCountDrift` 的常规数量门禁。F/E 判据始终不放宽。

#### Codex Work later

普通错误说明修正不调用。阶段6正式安全/错误链验收时，如需要对抗验证，可攻击异常类型伪造、未知类型、原始 provider message 泄漏、fallback providerUsed 漂移等边界。

### V-009 — S3-61 Overview 指标口径版本展示

- **Status**：PENDING
- **Implementation baseline**：`9b9caa53fd3d6b8f7f36e51dcfa44a9112d00664`
- **Implementation commits**：`9f0f6d8`（页面/CSV 接线）+ `9b9caa5`（developer tests）
- **Area**：`web/src/views/Overview.vue`、`web/tests/metricDefinitionVersionDisplay.test.js`
- **Risk**：低；只消费既有 `metrics[*].definitionVersion`，不改后端契约、指标值、数据库、权限或口径算法。
- **Blocks further development**：NO。

#### Invariants

1. Overview 每个指标卡都展示该行后端返回的 `definitionVersion`，不只对 `repeat_rate` 特判；
2. 固定清单指标与清单外指标走同一搬运规则；
3. 缺失版本显示 `—`，不得补 `v1`、`unknown` 或猜测版本；
4. 不擅自给版本值拼 `v` 前缀，后端字符串原样显示；
5. CSV 与卡片同步增加“口径版本”列，并复用同一已格式化字段；
6. 本项只补展示，不改变 `repeat_period_start/end`、快照级 period 与窗口 period 的既有语义。

#### Code Agent later

在包含 `9f0f6d8` + `9b9caa5` 的精确 SHA 上执行：

1. `cd web; npm run verify`；
2. 确认新增 `metricDefinitionVersionDisplay.test.js` 4 条全部通过；
3. 确认 Vite production build 成功，避免 Vue 模板/SFC 编译回归；
4. 真实浏览器/后端联调仍不是本条 unit/build 层 PASS 的前提，留阶段5/7 E2E。

#### Codex Work later

普通展示项不调用。阶段5整体页面验收或合并 main 前如需要对抗验证，再检查空白/非字符串版本、CSV 与页面不一致、旧快照缺版本等边界。

## 3. 2026-09-17 独立执行结论

### 第一批：`080b8b0e1234416f1dc884bed4f1948e75464070`

- Web：`npm run verify` exit 0，187/187 PASS，Vite build 成功；
- JDK17 default：analytics-server 1011（F=2/E=0/S=1）、mall 13/13、generator 110/110；
- 两个 analytics failure 中：
  1. `AiExplanationEndpointTest.providerUsedIsLlmWhenRewriteAccepted` 是 D-014 后的旧 fixture 漂移，已由 `3e0d3bc` 修正；
  2. `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched` 是既有环境红（landing 历史文件不在当前工作区），继续保留，不删除/skip/放宽；
- analytics 数量 `1002 → 1011` 的 +9 精确来自本批三组新测试：`ExplanationProviderProvenanceTest` 3 + `AiQueryTimeoutMappingTest` 3 + `AiControllerAuditStatusTest` 3。

### 第二批复测：`e0c7d91052773dd2183bb24fc48969ada2613af2`

- 最小 provider 复测：`AiExplanationEndpointTest` 7/7 PASS，`providerUsed=mock-provider`；
- default：analytics-server 1011（F=1/E=0/S=1）、mall 13/13、generator 110/110，总计 1134；
- `ExplanationProviderProvenanceTest`、`AiQueryTimeoutMappingTest`、`AiControllerAuditStatusTest` 均 3/3 PASS；
- 无新增失败，唯一失败仍是既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`；
- 1011 已被连续两轮独立执行确认。`scripts/run-tests.ps1` 当前数量登记仍为 1002；由于 V-008 又新增测试，数量登记等 V-008 独立计数后一次同步。`-AllowCountDrift` 只能作为临时复测工具，不能成为常规入口。

Codex Work 这两批**暂不调用**。当前结果已经由 Code Agent 足够定位；按用户要求，只在核心安全/重大迁移/正式阶段验收等重要节点调用 Codex Work。

## 4. 批量验证触发点

满足以下任一条件时优先集中跑本文件中的 PENDING/PARTIAL 队列：

1. 一个功能簇完成；
2. 某阶段准备宣布完成；
3. 即将进入真实 MySQL / Spark / Hive / Flume / HTTP E2E；
4. 即将合并 `main`；
5. PENDING 队列已经长到继续开发会明显增加失败定位成本；
6. 某个后续工作直接依赖一个 PENDING 项的运行行为。

## 5. 独立验证结果登记格式

每个验证者返回：

```text
Item: V-xxx
Role: Code Agent | Codex Work
Tested commit: <full SHA>
Environment: <JDK/Node/MySQL/Spark/...>
Expected: ...
Actual: ...
Commands/Reproduction: ...
Evidence: ...
Result: PASS | FAIL | PARTIAL
```

ChatGPT 复核后才更新本文件最终状态。