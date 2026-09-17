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

#### Existing detailed handoff

`docs/acceptance/s3-53-ai-evidence-id-fallback-20260916/TEST-HANDOFF.md`

### V-002 — Web 统一验证入口

- **Status**：PASS（2026-09-17 Code Agent，commit `080b8b0e1234416f1dc884bed4f1948e75464070`）
- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- **Implementation commits**：`e36a350`（`npm run verify`）+ `db810d6`（结构守卫）+ `351fee9`（Decision Log D-011）
- **Area**：`web/package.json`、`web/tests/packageScripts.test.js`
- **Risk**：低；只增加前端验证入口与守卫，不改生产运行时代码。
- **Blocks further development**：NO。

#### Execution evidence — 2026-09-17

- `cd web; npm run verify` → exit `0`；
- Node `v24.16.0` / npm `11.13.0`；
- 首批 Node tests `187/187` PASS；后续第三批在 `842f2e7` 上 `194/194` PASS；
- Vite production build 两批均成功。

**Limit**：真实浏览器/E2E/后端联调仍不属于该入口。

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

`npm run verify` 全绿，直接相关 4 条用例通过。真实 Vue/browser 渲染 + 后端联调仍未覆盖。

### V-004 — S3-55/S3-56 AI 标识严格形状与草稿锚点单一判据

- **Status**：PARTIAL（Code Agent：工具函数/源码接线测试通过；真实浏览器→提交链未测）
- **Implementation baseline**：`88c715e3976469ead3ff1e8e73dfa00a41d22dc6`
- **Implementation commits**：`617642c`（context 严格 ID 读取）+ `776bc74`（decisionDraft 去二次 trim）+ `88c715e`（developer tests）+ `28bd4ea`（Decision Log D-013）
- **Area**：`web/src/utils/context.js`、`web/src/utils/decisionDraft.js`、`web/tests/decisionDraft.test.js`、`web/tests/aiEvidenceIdFallback.test.js`
- **Risk**：低—中；前端 fail-closed 收紧，不改后端 ID 生成与决策契约。
- **Blocks further development**：NO。

#### Invariants

1. ID 只能是后端返回的精确字符串，不 trim、不数字转串；
2. `unknown` 任意大小写均无效；
3. 顶层真实 evidenceId 仍优先，顶层无效才回退嵌套真实 evidenceId；
4. `decisionDraft.js` 不再建立第二个 ID 归一化规则；
5. evidence package 与 snapshot 锚点仍二选一；
6. 两类 ID 都无效时 fail-closed，不构造草稿请求。

#### Execution evidence — 2026-09-17

`npm run verify` 全绿；相关纯逻辑/源码接线用例通过。真实 `AiAssistant → draftAnchor → buildDraftBody → HTTP` 浏览器链仍未测。

### V-005 — S3-57 Explanation provider provenance

- **Status**：PASS（unit/default 层；2026-09-17 Code Agent 在 `e0c7d91052773dd2183bb24fc48969ada2613af2` 复测确认）
- **Implementation baseline**：`a134df88aa9d31ef8b26fc00f4785a5cbc5c2362`
- **Implementation commits**：`97ed8ac`（ExplanationResult/调用链来源）+ `d7780d4`（控制器直读 providerUsed）+ `a134df8`（developer tests）
- **Fixture correction**：`3e0d3bc`（端点测试显式 stub `llm.providerName()="mock-provider"`，断言真实 provider 名称；生产逻辑不回退）
- **Area**：`analytics-server/ai-decision`、`analytics-server/platform-app/AiController`
- **Risk**：中；加性响应字段与来源事实修正，不改 LLM 安全策略。
- **Blocks further development**：NO；真实 provider/外网质量仍留到阶段6集成验收。

#### Retest evidence — 2026-09-17

- `AiExplanationEndpointTest` 7/7 PASS；`providerUsed=mock-provider`；
- `ExplanationProviderProvenanceTest` 3/3 PASS；
- default 无新增失败，唯一失败仍是既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`。

### V-006 — S3-58 AI Text-to-SQL timeout classification

- **Status**：PASS（unit/default 层；真实 3307/JDBC 超时仍未测）
- **Implementation baseline**：`0c5df95ad835ba214ec1788cd76fba85d8ab7120`
- **Implementation commits**：`054408e`（EXECUTE/服务层）+ `bc3dac5`（EXPLAIN）+ `0c5df95`（developer tests）
- **Area**：`analytics-server/ai-decision`
- **Risk**：中；错误分类修正，不改 SQL 允许/拒绝规则。
- **Blocks further development**：NO；但进入真实 MySQL 慢查询/超时验收前必须补运行证据。

#### Execution evidence — 2026-09-17

`AiQueryTimeoutMappingTest` 3/3 PASS。真实 3307 慢查询/JDBC timeout 未覆盖。

### V-007 — S3-59 AI operation audit failure classification

- **Status**：PASS（unit/default 层；operation_audit_log 真库写入仍未测）
- **Implementation baseline**：`eecd6839ad21ae12f2dbc5018a009741e929f025`
- **Implementation commits**：`a6e3639`（生产逻辑）+ `eecd683`（developer tests）
- **Area**：`analytics-server/platform-app/AiController`
- **Risk**：低—中；只修 operation audit 成败分类。
- **Blocks further development**：NO；但正式审计验收前必须补真库证据。

#### Execution evidence — 2026-09-17

`AiControllerAuditStatusTest` 3/3 PASS。真实 `operation_audit_log` 落库仍未测。

### V-008 — S3-60 AI explanation 回退原因准确性

- **Status**：PASS（unit/default 层；真实 LLM provider/外网仍未测）
- **Implementation baseline**：`0a77c21a21c3ccb782c75ca97f5c453f7a916f93`
- **Implementation commits**：`8ab0058`（生产逻辑）+ `97d6e83`（developer tests）+ `0a77c21`（Decision Log D-018）
- **Verified at**：`842f2e783fced8ddfe13678a7f401245d52a529b`
- **Area**：`analytics-server/ai-decision/ExplanationService`、`ExplanationEvidenceTest`
- **Risk**：低—中；只纠正模板回退原因与内部分类，不新增公开字段，不改 Provider 调用次数、SQL、权限或快照策略。
- **Blocks further development**：NO。

#### Invariants

1. Provider 超时/限流/网络/鉴权/格式失败不得显示为“数值校验失败”；
2. 空摘要/超长摘要属于摘要形状/长度守卫，不属于数值守卫；
3. 数值越界仍由原 `SUMMARY_NUMBER_GUARD` 拒绝并明确说明数值校验；
4. 已经真实尝试模型调用后失败，问数解释不得声称“未调用大模型”；
5. 所有回退最终仍 `providerUsed=template`，成功采用模型时仍记录真实 provider；
6. limitation 只暴露稳定原因类别，不回显 Provider 原始异常 message/响应体/URL/凭据/堆栈。

#### Execution evidence — 2026-09-17

- `ExplanationEvidenceTest`：7/7 PASS；
- `ExplanationProviderProvenanceTest`：3/3 PASS；
- `fallsBackWhenProviderThrows` 明确确认 TIMEOUT → “模型调用超时”，且不含“数值校验”；
- `queryFallbackDoesNotClaimModelWasNeverCalled` 明确确认已调用后超时不得声称“未调用大模型”；
- `rejectsBlankRewriteAsShapeFailure` → `SUMMARY_LENGTH_GUARD`；
- `rejectsFabricatedNumbers` → `SUMMARY_NUMBER_GUARD`；
- default 全量中相关类保持全绿。

### V-009 — S3-61 Overview 指标口径版本展示

- **Status**：PASS（unit/build 层；真实浏览器/HTTP 联调仍未测）
- **Implementation baseline**：`9b9caa53fd3d6b8f7f36e51dcfa44a9112d00664`
- **Implementation commits**：`9f0f6d8`（页面/CSV 接线）+ `9b9caa5`（developer tests）
- **Verified at**：`842f2e783fced8ddfe13678a7f401245d52a529b`
- **Area**：`web/src/views/Overview.vue`、`web/tests/metricDefinitionVersionDisplay.test.js`
- **Risk**：低；只消费既有 `metrics[*].definitionVersion`，不改后端契约、指标值、数据库、权限或口径算法。
- **Blocks further development**：NO。

#### Execution evidence — 2026-09-17

- `metricDefinitionVersionDisplay.test.js`：4/4 PASS；
- `npm run verify`：Node tests 194/194 PASS；Vite v5.4.21 production build PASS（671 modules）；
- 固定清单和清单外指标都读取后端 `definitionVersion`；缺失显示 `—`；不硬编码 `v1`，不擅自拼 `v`；
- CSV 与卡片复用同一 `definitionVersionText`。

**Remaining runtime gap**：真实浏览器渲染与真实后端响应联调未覆盖。

### V-010 — S3-62 决策中心取消动作接线修复

- **Status**：SUPERSEDED after PASS → 核心“取消按钮必须调用业务 cancel 而非 `useAnalysis.cancel`”已在 `842f2e7` 独立验证通过；后续 V-012 又收紧了 reason 语义，当前最终行为以 V-012 为准。
- **Implementation baseline**：`a1a2eaba30dabd82a093a994a01704d83f3e9586`
- **Implementation commits**：`bf5900d`（生产接线修复）+ `a1a2eab`（developer tests）
- **Verified at**：`842f2e783fced8ddfe13678a7f401245d52a529b`
- **Area**：`web/src/views/Decisions.vue`、`web/tests/decisionCancelWiring.test.js`
- **Risk**：低—中；修复既有按钮调用错函数，不改后端状态机、权限码或 API 形状。

#### Historical execution evidence — 2026-09-17

- `decisionCancelWiring.test.js`：3/3 PASS；
- `IN_PROGRESS` 的“取消”按钮确认调用 `cancelDecision(d)`；
- 当时 `cancelDecision` 使用固定 `{ reason: '策略调整' }`；这一**固定原因形态已被 V-012 明确淘汰**，不能再当当前行为；
- `useAnalysis.cancel` 仍只用于 `onUnmounted(cancel)`；
- 同一 `npm run verify`：194/194 + Vite build PASS。

### V-011 — S3-63 AI 在途问数显式取消

- **Status**：PENDING
- **Implementation baseline**：`4079fd7a2a5427f0cea1dd622a6e1e23762cf3d4`
- **Implementation commits**：`f085adc`（生产接线）+ `4079fd7`（developer source guards）
- **Area**：`web/src/views/AiAssistant.vue`、`web/tests/aiAskCancellation.test.js`
- **Risk**：低—中；只修前端请求取消/过期响应展示，不改后端 AI、SQL、Provider、权限或数据库。
- **Blocks further development**：NO；真实 HTTP AbortController 行为留前端/后端联调。

#### Defect fact

旧模板按钮在 `busy` 时被 `:disabled="busy || !question.trim()"` 禁用，却同时显示“分析中…（再次点击可放弃上一次）”；因此用户实际上无法点击取消。同时推荐问题/历史回填在 busy 时仍可改变输入文字，使页面输入可能与仍在执行的旧请求错位。

#### Invariants

1. busy 时主按钮仍可点击，动作变为显式 `cancelAsk()`；
2. `cancelAsk()` 必须先推进 `askSeq` 使旧响应失效，再 abort 当前 controller；
3. 取消后立即 `busy=false`、清掉可能已经到达但尚未完成链路的 `queryResult`，并显示明确取消提示；
4. 旧请求随后 resolve/reject 都不得重新写回结果或 busy 状态；
5. 在途时推荐问题与历史回填禁用，避免输入文本与执行中的问题错位；
6. 正常非 busy 状态仍走原 `ask()`，不改变 Text-to-SQL/证据链。

#### Code Agent later

在精确包含 `f085adc` + `4079fd7` 的 SHA 上执行：

1. `cd web; npm run verify`；
2. 确认 `aiAskCancellation.test.js` 5/5 PASS；
3. Vite production build PASS；
4. 浏览器 E2E 后续再验证真实点击取消是否触发 AbortController、网络请求中止且旧结果不闪回。

#### Codex Work later

普通前端交互修复不调用；阶段6/7浏览器联调时若需要对抗验证，可攻击“响应恰好在点击取消前后到达”的竞态边界。

### V-012 — S3-64 决策驳回/取消原因契约接线

- **Status**：PENDING
- **Implementation baseline**：`d024a2388edff4702802c34f45b4cf1cd4eb769c`
- **Implementation commits**：`187fb88`（生产接线）+ `9733009`（更新取消守卫）+ `d024a23`（新增 reason 契约守卫）
- **Area**：`web/src/views/Decisions.vue`、`web/tests/decisionCancelWiring.test.js`、`web/tests/decisionRequiredReason.test.js`
- **Risk**：低—中；只修前端对既有 `ReasonReq.reason` 必填契约的调用，不改后端状态机、权限、API 路径或数据库。
- **Blocks further development**：NO；真实 prompt/HTTP/状态变化仍留浏览器 E2E。

#### Defect fact

后端 `DecisionController` 对 `/{id}/reject` 与 `/{id}/cancel` 都接收 `ReasonReq`，`DecisionService.reject/cancel` 又明确要求 reason 非空。旧前端却有两种不一致：驳回按钮走通用 `act(d,'reject')` 并发送 `{}`，因此必然在后端被 `PARAM_INVALID` 拒绝；取消虽然已在 S3-62 接上正确业务函数，却写死 `{ reason: '策略调整' }`，会把前端默认文案冒充成员工真实取消原因写入审计。

#### Invariants

1. `PENDING_REVIEW` 的“驳回”按钮必须走独立 `rejectDecision(d)`，不得再走发送空 `{}` 的通用 `act`；
2. reject/cancel 共用一个 `requiredReason` 前端判据：员工取消 prompt → 不发请求；纯空白 → 本地拒绝并提示；非空 → trim 后原样下发；
3. `rejectDecision` 只发送 `{ reason }`，成功后 `flush()`；
4. `cancelDecision` 同样只发送 `{ reason }`，不得写死“策略调整”等默认业务理由；
5. 不改变 submit/start/complete/evaluate 的既有请求形状，不改变状态机；
6. `useAnalysis.cancel` 仍只负责页面取数取消，与业务 cancel 分离。

#### Code Agent later

在精确包含 `187fb88` + `9733009` + `d024a23` 的 SHA 上执行：

1. `cd web; npm run verify`；
2. `decisionCancelWiring.test.js` 3/3 PASS；
3. `decisionRequiredReason.test.js` 4/4 PASS；
4. Vite production build PASS；
5. 浏览器 E2E 后续再验证 prompt 取消/空白/真实 reason 三条路径以及真实 `PARAM_INVALID` 不再由空 reject 请求触发。

#### Codex Work later

普通前端契约接线不调用。正式决策状态机验收时再攻击越级状态、重复提交、并发 reject/approve/cancel 等边界。

## 3. 2026-09-17 独立执行结论

### 第一批：`080b8b0e1234416f1dc884bed4f1948e75464070`

- Web：`npm run verify` exit 0，187/187 PASS，Vite build 成功；
- JDK17 default：analytics-server 1011（F=2/E=0/S=1）、mall 13/13、generator 110/110；
- 两个 analytics failure 中：旧 provider fixture 漂移后续已修；另一条是既有环境红；
- analytics `1002 → 1011` 的 +9 精确来自 `ExplanationProviderProvenanceTest` 3 + `AiQueryTimeoutMappingTest` 3 + `AiControllerAuditStatusTest` 3。

### 第二批复测：`e0c7d91052773dd2183bb24fc48969ada2613af2`

- `AiExplanationEndpointTest` 7/7 PASS，`providerUsed=mock-provider`；
- default：analytics-server 1011（F=1/E=0/S=1）、mall 13、generator 110，总计 1134；
- 无新增失败，唯一失败仍是既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`。

### 第三批：`842f2e783fced8ddfe13678a7f401245d52a529b`

- V-008 定向：`ExplanationEvidenceTest` 7/7、`ExplanationProviderProvenanceTest` 3/3，BUILD SUCCESS；
- Web：`npm run verify` exit 0，**194/194 PASS**，Vite v5.4.21 build PASS（671 modules，2.74s）；
- V-009：4/4 PASS；V-010：3/3 PASS；
- default（临时 `-AllowCountDrift` 仅用于最终量数）：analytics-server **1013**（F=1/E=0/S=1）＝ `105+353+172+97+126+160`；mall 13；generator 110；总计 **1136**；
- analytics `1011 → 1013` 的 +2 精确落在 ai-decision，来自 V-008 新增用例；
- **1013 已独立确认**。提交 `5a64ad44229cf41cedf9f7aedb8265c2070c92ed` 已把 `$BaselineDefault['analytics-server']` 从 1002 同步为 **1013**，后续常规 default 门禁应恢复为**不带** `-AllowCountDrift`；
- 对 `5a64ad4` 的 commit diff 复核显示：实际行为改动只有基线 `1002→1013`；另有一处历史注释词汇从“写守卫”变为“写断言”，不影响任何命令/判据/计数语义；
- 唯一失败仍是既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 but was 0），没有 NEW REGRESSION。

Codex Work 这三批**暂不调用**。当前结果足以定位普通回归；继续只在核心安全/重大迁移/正式阶段验收等重要节点调用。

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