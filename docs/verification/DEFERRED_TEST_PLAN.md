# Deferred Verification Plan

> 状态：CURRENT
> 用途：统一登记“代码已实现、独立验证尚未执行”的工作项。Code Agent 与 Codex Work 后续按本文件批量验证。
> 规则来源：`docs/governance/DEVELOPMENT_AND_VERIFICATION_RULES.md`、Decision Log D-009。

## 1. 使用规则

- 每个工作项必须固定一个被测 commit SHA；测试方不得用“当前最新”替代它。
- ChatGPT 可以继续开发并列模块；后续 commit 不自动改变旧条目的被测 SHA。
- 若后续实现覆盖了旧工作项的行为，旧条目标记 `SUPERSEDED`，新增最终被测 SHA。
- Code Agent：只跑既有测试/环境验证；不得改 Git。
- Codex Work：主动找反例、故障注入、临时探针；不得改 Git。
- 独立验证结果返回 ChatGPT，由 ChatGPT 判断并修改生产代码/测试/设计。

状态：`PENDING / RUNNING / PASS / FAIL / SUPERSEDED`。

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

本条已被 V-004 取代；原攻击面并入 V-004。

#### Existing detailed handoff

`docs/acceptance/s3-53-ai-evidence-id-fallback-20260916/TEST-HANDOFF.md`

### V-002 — Web 统一验证入口

- **Status**：PENDING
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

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm run verify
```

返回 Node/npm 版本、测试总数、失败数、build exit code 与日志位置。

#### Codex Work later

重点攻击：

- 在单测失败时 build 是否仍被错误执行；
- `verify` 是否出现递归调用自身；
- Windows / PowerShell / cmd 下 npm script 链是否符合预期；
- package script 与文档口径是否漂移；
- 是否存在另一个实际前端入口绕过该统一命令。

### V-003 — S3-54 AI 结论 summary 展示链

- **Status**：PENDING
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

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm run verify
```

返回测试总数、失败数、build 结果和日志位置。

#### Codex Work later

重点攻击：

- 后端 summary 为 `''`、空白、null、非字符串；
- query/evidence 同时出现伪造 summary 时是否被错误采用；
- 页面是否还存在其它结论渲染路径绕过 `evidenceContext.summary`；
- summary 包含 HTML/特殊字符时 Vue 是否按文本安全渲染；
- developer tests 是否只覆盖工具函数而未钉住页面接线。

### V-004 — S3-55/S3-56 AI 标识严格形状与草稿锚点单一判据

- **Status**：PENDING
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

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm run verify
```

返回测试总数/失败数、build 结果、Node/npm 版本和日志位置。

#### Codex Work later

重点攻击：

- `" EV-... "`、`" S... "` 是否被任何路径 trim 后重新接受；
- 数字、对象、数组、Boolean 是否被字符串化成 ID；
- `unknown` 的任意大小写组合；
- 顶层污染 ID + 嵌套真实 ID 是否正确回退；
- 顶层真实 ID + 嵌套冲突 ID 是否保持顶层优先；
- `AiAssistant.vue` 展示、`draftAnchor` 和最终 `buildDraftBody` 是否使用同一判据；
- 是否还有其它工具函数对 ID 做隐式 `String()` / `trim()` 后进入决策请求。

### V-005 — S3-57 Explanation provider provenance

- **Status**：PENDING
- **Implementation baseline**：`a134df88aa9d31ef8b26fc00f4785a5cbc5c2362`
- **Implementation commits**：`97ed8ac`（ExplanationResult/调用链来源）+ `d7780d4`（控制器直读 providerUsed）+ `a134df8`（developer tests）
- **Area**：`analytics-server/ai-decision`、`analytics-server/platform-app/AiController`
- **Risk**：中；加性响应字段与来源事实修正，不改 LLM 安全策略。
- **Blocks further development**：NO；但阶段6宣称“providerUsed 真实可审计”前必须验证。

#### Invariants

1. 模型输出实际被采用时 `providerUsed = llmProvider.providerName()`；
2. provider 不可用、调用异常、数值守卫拒绝而最终回退模板时 `providerUsed = template`；
3. 模型输出即使与模板文本逐字相同，也不能被误判为 template；
4. 控制器不得再通过文本比较反推 provider。

#### Code Agent later

在批量目标 SHA 上执行 JDK17 default 测试，至少确认：

```powershell
pwsh scripts/run-tests.ps1 -Suite default
```

并确认 `ExplanationProviderProvenanceTest` 被收集且通过。

#### Codex Work later

重点攻击：模型文本与模板完全相同、providerName 为空/null、模型超时后模板成功、数值守卫 REJECTED、`/ai/explanations` 与 `/ai/analyses` 两种响应是否一致携带真实来源，以及是否仍存在其它“比较文本推 provider”的路径。

### V-006 — S3-58 AI Text-to-SQL timeout classification

- **Status**：PENDING
- **Implementation baseline**：`0c5df95ad835ba214ec1788cd76fba85d8ab7120`
- **Implementation commits**：`054408e`（EXECUTE/服务层）+ `bc3dac5`（EXPLAIN）+ `0c5df95`（developer tests）
- **Area**：`analytics-server/ai-decision`
- **Risk**：中；错误分类修正，不改 SQL 允许/拒绝规则。
- **Blocks further development**：NO；但进入真实 MySQL 慢查询/超时验收前必须验证。

#### Invariants

1. `SQLTimeoutException` → `FAILED + QUERY_TIMEOUT`；
2. Spring `QueryTimeoutException`（EXPLAIN）→ `QUERY_TIMEOUT`，不得冒充 `SQL_COST_TOO_HIGH`；
3. 普通 EXPLAIN 异常仍 fail-closed 为 `SQL_COST_TOO_HIGH`；
4. 超时同样写 `ai_query_history`，errors 含稳定码；
5. 本轮不声称 `/api/v1/ai/queries` 已改 HTTP 504。

#### Code Agent later

JDK17 default 全量，并确认 `AiQueryTimeoutMappingTest` 三条被收集通过。若有可控 3307 慢查询环境，可额外做真实 JDBC timeout 证据；无环境则明确未测。

#### Codex Work later

重点攻击：EXPLAIN 超时与执行超时是否都保留审计、超时异常被多层包装时是否漏映射、超时发生在 LLM 阶段是否被误标数据库 QUERY_TIMEOUT、`REPAIRED` 路径超时后状态是否仍错误保留成功语义、generic RuntimeException 是否被错误改成 timeout。

### V-007 — S3-59 AI operation audit failure classification

- **Status**：PENDING
- **Implementation baseline**：`eecd6839ad21ae12f2dbc5018a009741e929f025`
- **Implementation commits**：`a6e3639`（生产逻辑）+ `eecd683`（developer tests）
- **Area**：`analytics-server/platform-app/AiController`
- **Risk**：低—中；只修 operation audit 成败分类。
- **Blocks further development**：NO；但审计验收前必须验证。

#### Invariants

1. `FAILED`、`REJECTED`、`ERROR*`、`FAIL*` 均走 `audit.failure`；
2. null QueryResult / null status fail-closed；
3. `EXECUTED`、`REPAIRED`、`GENERATED` 不误记失败；
4. QueryResult 状态机本身不被本项改写。

#### Code Agent later

JDK17 default 全量，并确认 `AiControllerAuditStatusTest` 被收集通过；若已有 operation audit 集成测试，额外验证 `FAILED` 真正写入 failure 结果而非只测 helper。

#### Codex Work later

重点攻击：大小写/复合状态、未来状态名包含 `FAIL` 的误判风险、`CANCELLED`/`TIMEOUT` 等潜在新状态、null question 导致 resourceId、audit.failure 自身异常的行为，以及查询失败后 `/ai/queries` 是否仍可能在其它路径写第二条 success。

## 3. 当前批量验证点（2026-09-17）

本轮已经从纯前端展示/锚点连续推进到 **AI provider provenance + Text-to-SQL 超时错误链 + operation audit 成败语义**。PENDING 队列已覆盖前端与 JDK17 后端两棵树，继续叠加会明显增加失败定位成本，因此按 D-009 触发一次批量验证点。

**批量测试时不要逐条 checkout 旧 SHA**：先 checkout 本文件所在的最终 handoff SHA，再统一执行当前树；各条 `Implementation baseline` 仅用于定位引入点。Code Agent 必须回报最终被测完整 SHA。

建议顺序：

```text
1. web: npm run verify
2. analytics/server: pwsh scripts/run-tests.ps1 -Suite default
3. Codex Work 对 V-003 ~ V-007 做对抗验证（V-002 也检查统一入口）
```

未运行 3307 / 真模型 / 真实浏览器时必须明确写“未测”，不得由 unit/default 结果推断通过。

## 4. 批量验证触发点

满足以下任一条件时优先集中跑本文件中的 PENDING 队列：

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
Result: PASS | FAIL
```

ChatGPT 复核后才更新本文件最终状态。
