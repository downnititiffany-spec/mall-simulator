# Deferred Verification Plan

> 状态：CURRENT
> 用途：工作项验证状态总账；完整批次命令与结果不再塞进聊天或本文件。
> 执行协议：`docs/verification/TEST_EXECUTION_PROTOCOL.md`
> 当前批次入口：`docs/verification/CURRENT_BATCH.md`
> 永久结果归档：`docs/verification/results/`

## 1. 规则

- 每个批次固定精确 SHA；不得用“当前最新”替代。
- 默认采用**批量延迟验证**：并列、低风险、接口稳定的工作先连续开发，累计到功能簇后一次性验证。
- 只有后续实现直接依赖运行结果、公共契约/状态机/迁移/安全边界高风险、即将进入真库/E2E/阶段验收等情况才提前测试。
- `PASS` 必须写清证据层级；unit/build 绿不等于浏览器、真库、真实 Provider 或集群 E2E 已验收。
- Code Agent 不修改远端 Git；按 `CURRENT_BATCH.md` 一次执行整批测试，并将报告写入 `.verify/CURRENT_BATCH_RESULT.md`。
- ChatGPT 复核结果后，永久归档到 `docs/verification/results/` 并更新本总账。
- Codex Work 只在核心安全、重大迁移、关键状态机/幂等恢复、正式阶段验收、合并 main 前等高价值节点按需调用；普通批次不用。

状态：`PENDING / PARTIAL / PASS / FAIL / SUPERSEDED`。

## 2. 当前总览

| ID | 工作项 | 状态 | 当前证明边界 |
|---|---|---|---|
| V-001 | S3-53 AI evidenceId 兼容 | SUPERSEDED | 由 V-004 覆盖最终 ID 形状 |
| V-002 | Web 统一 `npm run verify` | PASS | Node test + Vite build |
| V-003 | S3-54 AI summary 展示 | PARTIAL | 工具函数/源码接线；无浏览器 E2E |
| V-004 | S3-55/56 AI ID 严格形状/草稿锚点 | PARTIAL | 纯逻辑/源码接线；无真实提交链 E2E |
| V-005 | S3-57 provider provenance | PASS | unit/default；无真实 Provider |
| V-006 | S3-58 Text-to-SQL timeout 分类 | PASS | unit/default；无真实 3307 timeout |
| V-007 | S3-59 AI operation audit 成败分类 | PASS | unit/default；无真库 audit 落库 |
| V-008 | S3-60 AI explanation 回退原因 | PASS | unit/default；无真实 Provider |
| V-009 | S3-61 Overview 口径版本展示 | PASS | Node test + build；无浏览器 E2E |
| V-010 | S3-62 决策取消接线 | SUPERSEDED after PASS | 最终 reason 行为由 V-012 覆盖 |
| V-011 | S3-63 AI 在途问数显式取消 | PASS | Node test + build；无真实浏览器 Abort E2E |
| V-012 | S3-64 决策 reject/cancel 原因采集 | PASS | Node test + build；无真实 HTTP/落库 E2E |
| V-013 | S3-65 批准时显式负责人/截止日期 | PASS | Node test + build；无真实浏览器/HTTP E2E |
| V-014 | S3-66 提交审核时补 owner | PASS | Node test + build；无真实浏览器/HTTP E2E |

当前没有等待 Code Agent 的 PENDING 工作项。下一次达到批量测试点时，由 ChatGPT 更新 `CURRENT_BATCH.md` 为 `READY`。

## 3. 已验证工作项摘要

### V-002 — Web 统一验证入口

- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- `npm run verify = npm test && npm run build`。
- 多轮独立执行均为 Node tests 全绿 + Vite production build 成功。
- 最新 Batch E：**212/212 PASS** + Vite build PASS。

### V-005 — S3-57 Explanation provider provenance

- **Implementation baseline**：`a134df88aa9d31ef8b26fc00f4785a5cbc5c2362`
- Fixture correction：`3e0d3bc`。
- `AiExplanationEndpointTest` 7/7；`ExplanationProviderProvenanceTest` 3/3。
- 模型结果实际采用才记录真实 `providerName()`；模板回退记录 `template`。
- 真实外网/真实供应商未测。

### V-006 — S3-58 Text-to-SQL timeout classification

- **Implementation baseline**：`0c5df95ad835ba214ec1788cd76fba85d8ab7120`
- `AiQueryTimeoutMappingTest` 3/3 PASS。
- SQL/JDBC timeout → `QUERY_TIMEOUT`；普通 EXPLAIN 异常仍 fail-closed。
- 真实 3307 慢查询/JDBC timeout 未测。

### V-007 — S3-59 AI operation audit failure classification

- **Implementation baseline**：`eecd6839ad21ae12f2dbc5018a009741e929f025`
- `AiControllerAuditStatusTest` 3/3 PASS。
- 真实 `operation_audit_log` 落库未测。

### V-008 — S3-60 AI explanation 回退原因准确性

- **Implementation baseline**：`0a77c21a21c3ccb782c75ca97f5c453f7a916f93`
- Verified at `842f2e783fced8ddfe13678a7f401245d52a529b`。
- `ExplanationEvidenceTest` 7/7；`ExplanationProviderProvenanceTest` 3/3。
- Provider timeout、摘要形状失败、数值守卫原因分类均按既有契约稳定。

### V-009 — S3-61 Overview 指标口径版本展示

- **Implementation baseline**：`9b9caa53fd3d6b8f7f36e51dcfa44a9112d00664`
- `metricDefinitionVersionDisplay.test.js` 4/4 PASS。
- 页面与 CSV 消费后端 `definitionVersion`；缺失显示 `—`，不猜默认版本。

### V-011 — S3-63 AI 在途问数显式取消

- **Implementation baseline**：`4079fd7a2a5427f0cea1dd622a6e1e23762cf3d4`
- Batch D / E 回归均通过；`aiAskCancellation.test.js` 5/5 PASS。
- 真实浏览器 AbortController/网络中止未做 E2E。

### V-012 — S3-64 决策 reject/cancel 原因采集

- **Implementation baseline**：`d024a2388edff4702802c34f45b4cf1cd4eb769c`
- `decisionCancelWiring.test.js` 3/3；`decisionRequiredReason.test.js` 4/4。
- reject/cancel 必须由员工提供非空 reason；不再发送空 reject 或硬编码取消原因。
- 真 HTTP/reason 落库与审计未做 E2E。

### V-013 — S3-65 批准时显式负责人/截止日期

- **Final verification baseline**：`c42ee34d4bfb2364c646f060f675af67974a6a6c`
- 实现链：`28778e8` + `49023a8` + `3d51b84`。
- `decisionApprovalInput.test.js`：**5/5 PASS**。
- approve 不再硬编码负责人、不再自动 `+3天`；owner/dueDate 取消或空白时 API 前返回；dueDate 要求 `YYYY-MM-DD` 且经过真实日历日期校验；最终只发送 `{ owner, dueDate }`，成功后刷新。
- 真实浏览器 prompt/HTTP/状态机未做 E2E。

### V-014 — S3-66 DRAFT 提交审核时补 owner

- **Final verification baseline**：`c42ee34d4bfb2364c646f060f675af67974a6a6c`
- 实现链：`12a440f` + `84153f1`。
- `decisionSubmitOwner.test.js`：**4/4 PASS**。
- DRAFT 提交不再走空 `{}`；已有 owner 仅作可编辑初始值，缺失时为空；取消/空白在 API 前 fail-closed；最终只发送 `{ owner }` 并刷新。
- 真实浏览器/HTTP/后端提交校验未做 E2E。

## 4. PARTIAL：后续阶段联调再补

### V-003 — AI summary 展示

- **Implementation baseline**：`80623a335e9df1b9fc758cb22172d72032b312db`。
- 工具函数和源码接线通过；结论只取 `explanation.summary`。
- **Remaining**：真实 Vue/browser + 后端响应联调。

### V-004 — AI ID 严格形状与草稿锚点

- **Implementation baseline**：`88c715e3976469ead3ff1e8e73dfa00a41d22dc6`。
- 字符串 ID 不 trim、不数字转串；unknown 无效；evidence package 与 snapshot 二选一；无锚点 fail-closed。
- **Remaining**：真实 `AiAssistant → draftAnchor → buildDraftBody → HTTP` 浏览器链。

## 5. 2026-09-17 独立执行批次

### Batch A — `080b8b0e1234416f1dc884bed4f1948e75464070`

- Web 187/187 + build PASS。
- default：analytics 1011；当时旧 provider fixture + 已知环境红各一条。

### Batch B — `e0c7d91052773dd2183bb24fc48969ada2613af2`

- provider fixture 修复复测通过。
- analytics 1011（F=1/E=0/S=1），唯一红为已知环境红。

### Batch C — `842f2e783fced8ddfe13678a7f401245d52a529b`

- V-008 定向 10/10 PASS。
- Web 194/194 + build PASS；V-009/V-010 通过。
- analytics 1013（F=1/E=0/S=1），mall 13，generator 110，总 1136；无新回归。

### Batch D — `f69294444ceac09c25158996eca4324dc63c84c2`

- Web 203/203 + build PASS；V-011/V-012 通过。
- default 正式不带 `-AllowCountDrift`：analytics **1013 MATCH**，mall 13 MATCH，generator 110 MATCH，总 1136 MATCH。
- 唯一红仍为既有环境红。

### Batch E — `c42ee34d4bfb2364c646f060f675af67974a6a6c`

- 永久报告：`docs/verification/results/BATCH-E-WEB-DECISION-INPUT-RESULT.md`。
- Web：**212/212 PASS**；Vite production build PASS。
- V-013：`decisionApprovalInput.test.js` 5/5 PASS。
- V-014：`decisionSubmitOwner.test.js` 4/4 PASS。
- 回归：`aiAskCancellation` 5/5、`decisionCancelWiring` 3/3、`decisionRequiredReason` 4/4。
- New failures：0。
- 未覆盖真浏览器 prompt、真 HTTP approve/submit、后端状态机与落库。

## 6. 已知环境红与未覆盖面

- 已知环境红：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，当前环境 expected 43 / actual 0；保留，不 delete/skip/放宽。
- 尚未覆盖：真实浏览器/E2E、前后端联调、真实 LLM provider、真实 3307 JDBC timeout、operation audit 真库写入、`spark` 档、`isolated` 档、3306/3307 真数据链与 Spark/Hive/Flume E2E。

## 7. 下一批验证触发点

达到以下任一节点时，把所有相关测试一次性写入 `CURRENT_BATCH.md`：

1. 一个功能簇完成；
2. 某阶段准备收口；
3. 即将进入真实 MySQL / Spark / Hive / Flume / HTTP E2E；
4. 即将合并 `main`；
5. 未验证变更累积到继续开发会明显增加失败定位成本；
6. 后续工作直接依赖某个尚未验证的运行行为。

普通低风险并列工作不再因为每个小改动单独停下来测试。