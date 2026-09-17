# Deferred Verification Plan

> 状态：CURRENT
> 用途：统一登记“已实现但独立验证尚未完成/仅完成部分层级”的工作项，以及 Code Agent 的独立执行结论。
> 规则来源：`docs/governance/DEVELOPMENT_AND_VERIFICATION_RULES.md`、Decision Log D-009。
> 验证角色：Code Agent 跑既有测试/环境验证；Codex Work 只在核心安全、重大迁移、正式阶段验收等高价值节点按需调用。

## 1. 规则

- 每个工作项固定精确 SHA；不得用“当前最新”替代。
- 并列模块可继续开发；只有运行结果成为后续直接依赖时才阻塞对应链路。
- 后续实现改变旧项行为时，旧项标记 `SUPERSEDED`，重新登记最终行为。
- `PASS` 必须写清证据层级；unit/build 绿不等于浏览器、真库、真实 Provider 或集群 E2E 已验收。
- 测试方不得修改 Git；独立结果回到 ChatGPT 后才更新本文件。

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
| V-013 | S3-65 批准时显式负责人/截止日期 | PENDING | 等 Code Agent |
| V-014 | S3-66 提交审核时补 owner | PENDING | 等 Code Agent |

## 3. 已验证工作项摘要

### V-002 — Web 统一验证入口

- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- `npm run verify = npm test && npm run build`。
- 2026-09-17 多轮独立执行均为 Node tests 全绿 + Vite production build 成功。
- 最新已验证批次 `f69294444ceac09c25158996eca4324dc63c84c2`：**203/203 PASS**，Vite build PASS。
- 不宣称覆盖真实浏览器/E2E/后端联调。

### V-005 — S3-57 Explanation provider provenance

- **Implementation baseline**：`a134df88aa9d31ef8b26fc00f4785a5cbc5c2362`
- **Fixture correction**：`3e0d3bc`。
- `AiExplanationEndpointTest` 7/7 PASS；实际 `providerUsed=mock-provider`。
- `ExplanationProviderProvenanceTest` 3/3 PASS。
- 最终采用模型时记录真实 `providerName()`；回退模板时记录 `template`。
- 真实外网/真实供应商未测。

### V-006 — S3-58 Text-to-SQL timeout classification

- **Implementation baseline**：`0c5df95ad835ba214ec1788cd76fba85d8ab7120`
- `AiQueryTimeoutMappingTest` 3/3 PASS。
- SQL/JDBC timeout → `QUERY_TIMEOUT`；普通 EXPLAIN 异常仍 fail-closed。
- 真实 3307 慢查询/JDBC timeout 未测。

### V-007 — S3-59 AI operation audit failure classification

- **Implementation baseline**：`eecd6839ad21ae12f2dbc5018a009741e929f025`
- `AiControllerAuditStatusTest` 3/3 PASS。
- `FAILED/REJECTED/ERROR*/FAIL*` 走失败审计；成功状态不误判。
- `operation_audit_log` 真库写入未测。

### V-008 — S3-60 AI explanation 回退原因准确性

- **Implementation baseline**：`0a77c21a21c3ccb782c75ca97f5c453f7a916f93`
- **Verified at**：`842f2e783fced8ddfe13678a7f401245d52a529b`。
- `ExplanationEvidenceTest` 7/7 PASS；`ExplanationProviderProvenanceTest` 3/3 PASS。
- Provider TIMEOUT 不再写成“数值校验失败”；已调用模型后失败不再声称“未调用大模型”；空摘要归形状/长度守卫；伪造数字仍命中 `SUMMARY_NUMBER_GUARD`。
- 真实 Provider 未测。

### V-009 — S3-61 Overview 指标口径版本展示

- **Implementation baseline**：`9b9caa53fd3d6b8f7f36e51dcfa44a9112d00664`
- **Verified at**：`842f2e783fced8ddfe13678a7f401245d52a529b`。
- `metricDefinitionVersionDisplay.test.js` 4/4 PASS。
- 页面与 CSV 都消费后端 `definitionVersion`；缺失显示 `—`，不硬编码 `v1`、不补 `v`。
- 无真实浏览器/HTTP 联调。

### V-011 — S3-63 AI 在途问数显式取消

- **Implementation baseline**：`4079fd7a2a5427f0cea1dd622a6e1e23762cf3d4`
- **Verified at**：`f69294444ceac09c25158996eca4324dc63c84c2`。
- `aiAskCancellation.test.js`：5/5 PASS。
- busy 时主按钮可点击并显示“放弃本次分析”；`cancelAsk()` 先推进 `askSeq` 再 abort；取消后清 `busy/queryResult`；旧响应受序号守卫约束不得写回；在途期间推荐问题与历史回填禁用。
- 同批 Web：203/203 PASS，Vite build PASS。
- 真实浏览器 AbortController/网络中止未做 E2E。

### V-012 — S3-64 决策 reject/cancel 原因采集

- **Implementation baseline**：`d024a2388edff4702802c34f45b4cf1cd4eb769c`
- **Verified at**：`f69294444ceac09c25158996eca4324dc63c84c2`。
- `decisionCancelWiring.test.js` 3/3 PASS；`decisionRequiredReason.test.js` 4/4 PASS。
- 驳回不再走 `act(...,'reject')` 空 `{}`；reject/cancel 都要求员工输入真实 reason；取消 prompt/纯空白 fail-closed；非空值 trim 后 `{ reason }` 发送；取消不再硬编码“策略调整”；成功仍 `flush()`。
- 同批 Web：203/203 PASS，Vite build PASS。
- 真实 `POST .../reject|cancel`、reason 落库/审计未做 E2E。

## 4. PARTIAL：后续阶段联调再补

### V-003 — AI summary 展示

- **Implementation baseline**：`80623a335e9df1b9fc758cb22172d72032b312db`。
- 工具函数和源码接线已通过；结论只取 `explanation.summary`，不从 query/rows 推导。
- **Remaining**：真实 Vue/browser + 后端响应联调。

### V-004 — AI ID 严格形状与草稿锚点

- **Implementation baseline**：`88c715e3976469ead3ff1e8e73dfa00a41d22dc6`。
- 字符串 ID 不 trim、不数字转串；unknown 无效；evidence package 与 snapshot 二选一；无锚点 fail-closed。
- **Remaining**：真实 `AiAssistant → draftAnchor → buildDraftBody → HTTP` 浏览器链。

## 5. PENDING：下一批 Code Agent

### V-013 — S3-65 批准动作显式收集负责人和截止日期

- **Final verification baseline**：`84153f13917628769967211edf218e9566dab624`（包含后续 S3-66 对共享输入 helper 的兼容调整）。
- **Implementation commits**：`28778e8`（生产逻辑）+ `49023a8`（初版守卫）+ `3d51b84`（共享 helper 演进后的守卫同步）。
- **Area**：`web/src/views/Decisions.vue`、`web/tests/decisionApprovalInput.test.js`。
- **Risk**：低—中；只改前端人工输入纪律，不改后端 approve 状态机、权限或请求字段。

#### Defect fact

旧页面把负责人 prompt 的默认值硬编码为 `运营-小李`，并把 dueDate 自动写成 `Date.now()+3天`。员工可以在没有明确输入真实负责人和截止日期的情况下批准，导致业务字段被前端默认值替代。

#### Invariants

1. approve 不硬编码默认负责人；
2. dueDate 不再自动 +3 天；
3. 负责人取消/空白时不发请求；
4. 截止日期必须显式输入，格式为 `YYYY-MM-DD` 且为真实日历日期；
5. 两项均齐备后才进入 busy/发请求；
6. 请求只发送 `{ owner, dueDate }`，成功后 `flush()`；
7. 不改变后端批准状态机、基线锁定或权限。

#### Code Agent later

在最终批次 SHA 上执行 `cd web; npm run verify`；确认 `decisionApprovalInput.test.js` 5/5 PASS + Vite build PASS。

### V-014 — S3-66 DRAFT 提交审核时补 owner

- **Implementation baseline**：`84153f13917628769967211edf218e9566dab624`。
- **Implementation commits**：`12a440f`（生产接线）+ `84153f1`（developer guards）。
- **Area**：`web/src/views/Decisions.vue`、`web/tests/decisionSubmitOwner.test.js`。
- **Risk**：低—中；只使用后端既有 `SubmitReq.owner` 能力，不新增 API 字段、不改状态机。

#### Defect fact

旧 DRAFT 的“提交审核”按钮直接走 `act(d,'submit')` 并发送 `{}`。而后端提交审批要求 owner 齐备，且 `SubmitReq` 已允许在 submit 时补 owner；因此“草稿创建时 owner 留空”的合法路径会在决策中心无 UI 可补负责人，只能得到 `PARAM_INVALID`。

#### Invariants

1. DRAFT 按钮调用独立 `submitDecision(d)`；
2. 不再发送空 `{}`；
3. 已有真实 owner 只作为可编辑初始值，缺失时初始值为空，不制造 `admin/demo/运营-小李`；
4. 取消/纯空白 owner 时在 busy/API 前 fail-closed；
5. 成功请求只提交 `{ owner }` 并 `flush()`；
6. target metric/direction/evidence 等仍由后端既有提交齐备校验负责，本项不放宽任何字段。

#### Code Agent later

在最终批次 SHA 上执行 `cd web; npm run verify`；确认 `decisionSubmitOwner.test.js` 4/4 PASS，并确认 V-013、V-011、V-012 不回归。

## 6. 2026-09-17 独立执行批次

### Batch A — `080b8b0e1234416f1dc884bed4f1948e75464070`

- Web 187/187 + build PASS。
- default：analytics 1011（当时 2 failures：旧 provider fixture + 已知环境红）、mall 13、generator 110。
- provider fixture 后续已修。

### Batch B — `e0c7d91052773dd2183bb24fc48969ada2613af2`

- `AiExplanationEndpointTest` 7/7；provider regression fixed。
- analytics 1011（F=1/E=0/S=1），唯一红仍为已知环境红。

### Batch C — `842f2e783fced8ddfe13678a7f401245d52a529b`

- V-008 10/10 相关定向测试 PASS。
- Web 194/194 + build PASS；V-009 4/4、V-010 3/3。
- analytics **1013**（F=1/E=0/S=1）＝ `105+353+172+97+126+160`；mall 13；generator 110；总 1136。
- 无 NEW REGRESSION。

### Batch D — `f69294444ceac09c25158996eca4324dc63c84c2`

- Web：**203/203 PASS**；`aiAskCancellation` 5/5、`decisionCancelWiring` 3/3、`decisionRequiredReason` 4/4；Vite build PASS。
- default 正式恢复**不带** `-AllowCountDrift`：analytics **1013 MATCH**（F=1/E=0/S=1），mall 13 MATCH，generator 110 MATCH，总计 **1136 MATCH**。
- 唯一失败仍为既有环境红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 but was 0）；**New failures = 0**。
- 由此确认 `scripts/run-tests.ps1` 的 analytics 基线 `1013` 已恢复正式 count gate；`-AllowCountDrift` 不再作为常规入口。

## 7. 已知环境红与未覆盖面

- 已知环境红：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，当前环境 expected 43 / actual 0；保留，不 delete/skip/放宽。
- 尚未覆盖：真实浏览器/E2E、前后端联调、真实 LLM provider、真实 3307 JDBC timeout、operation audit 真库写入、`spark` 档、`isolated` 档、3306/3307 真数据链与 Spark/Hive/Flume E2E。

## 8. 下一批验证触发点

优先在以下节点集中执行：一个功能簇完成、阶段准备收口、即将进入真库/集群/E2E、合并 main 前、PENDING 累积导致定位成本明显上升，或后续工作直接依赖 PENDING 的运行行为。
