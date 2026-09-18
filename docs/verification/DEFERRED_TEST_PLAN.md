# Deferred Verification Plan

> 状态：CURRENT
> 用途：工作项验证状态总账；完整批次测试计划与结果分别永久保存在 `batches/` 与 `results/`。
> 执行协议：`docs/verification/TEST_EXECUTION_PROTOCOL.md`
> 当前批次入口：`docs/verification/CURRENT_BATCH.md`
> 永久测试计划：`docs/verification/batches/`
> 永久接受结果：`docs/verification/results/`
> Code Agent 原始结果：`verification-results:docs/verification/results/`

## 1. 规则

- 每个批次固定精确 SHA；不得用“当前最新”替代。
- 每次 `CURRENT_BATCH.md` 进入 `READY` 时，ChatGPT 同步保存 `docs/verification/batches/<Batch-ID>-PLAN.md`，避免后续覆盖当前批次文件后丢失测试要求。
- 默认采用**批量延迟验证**：并列、低风险、接口稳定的工作先连续开发，累计到功能簇后一次性验证。
- 只有后续实现直接依赖运行结果、公共契约/状态机/迁移/安全边界高风险、即将进入真库/E2E/阶段验收等情况才提前测试。
- `PASS` 必须写清证据层级；unit/build 绿不等于浏览器、真库、真实 Provider 或集群 E2E 已验收。
- Code Agent 不修改开发分支；按 `CURRENT_BATCH.md` 一次执行整批测试，将完整结果写入本地 `.verify/CURRENT_BATCH_RESULT.md` 和 GitHub `verification-results:docs/verification/results/<Batch-ID>-RESULT.md`。
- 用户无需复制完整报告；只需告诉 ChatGPT“测试完成，测试结果已写入”。ChatGPT 直接从 GitHub 读取、复核、归档并更新本总账。
- Codex Work 只在核心安全、重大迁移、关键状态机/幂等恢复、正式阶段验收、合并 main 前等高价值节点按需调用；普通批次不用。

状态：`PENDING / PARTIAL / PASS / FAIL / SUPERSEDED`。

## 2. 当前总览

| ID | 工作项 | 状态 | 当前证明边界 |
|---|---|---|---|
| V-001 | S3-53 AI evidenceId 兼容 | SUPERSEDED | 由 V-004 覆盖最终 ID 形状 |
| V-002 | Web 统一 `npm run verify` | PASS | Node test + Vite build；最新 Batch P 307/307 |
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
| V-015 | S3-67 决策行 identity 保留 | PASS | Node test + Web full gate；无真实 HTTP E2E |
| V-016 | S3-68 决策基线单次格式化 | PASS | Node test + Web full gate；无真实浏览器 E2E |
| V-017 | S3-69/S3-70 决策执行上下文展示 | PASS | Node test + Web full gate；无真实 HTTP/DB E2E |
| V-018 | S3-71 本地日历日期默认值 | PASS | Node helper/source guard + Web full gate；无真实浏览器跨时区 E2E |
| V-019 | S3-72 Pipeline retry 失败处理 | PASS | Node source guard + Web full gate；无真实 retry HTTP/state-machine E2E |
| V-020 | S3-73 商品页服务端分页/排序 | PASS | Node source guard + Web full gate；无真实 HTTP/DB 排序 E2E |
| V-021 | S3-74 RFM matrix 类目唯一属主 | PASS | Node source guard + Web full gate；无真实 HTTP/DOM E2E |
| V-022 | Post-J-R1 Web consistency hardening | PASS | 同快照/导出 freshness/reentry source guards + Web 261/261；无真实浏览器/HTTP/state-machine/DB E2E |
| V-023 | Post-Batch-K Web interaction consistency | PASS | Ops admin/export + AI query/draft concurrency source guards + Web 271/271；无真实浏览器/HTTP/admin persistence/decision DB E2E |
| V-024 | Batch M/M-R1 Pipeline + Product interaction consistency | PASS | M 初次因陈旧测试守卫 FAIL；M-R1 33/33 + Web 274/274 + build PASS；无真实 HTTP/DB E2E |
| V-025 | Batch N/N-R1 analysis + post-N interaction consistency | PASS | N 初次因两条陈旧守卫 FAIL；N-R1 53/53 + Web 296/296 + build PASS；无真实浏览器/HTTP/DB/Spark-Hive-Flume E2E |
| V-026 | Batch O secondary-read concurrency | PASS | RFM/Decision 旁路状态 latest-request ownership + Decision 读写互斥；55/55 + Web 305/305 + build PASS；无真实浏览器/HTTP/state-machine/DB E2E |
| V-027 | Batch P in-flight interaction locks | PASS | Sales loading 期锁本地排序/翻页 + AI 草稿字段锁 + Pipeline 读写互斥；41/41 + Web 307/307 + build PASS；无真实浏览器/HTTP/DB/Spark-Hive-Flume E2E |
| V-028 | Pipeline 多 attempt / retry-from-stage / startup recovery L1 加固 | PARTIAL | `0f77322` + `96f4ad2`：developer test 32/32；default 1016 MATCH（唯一红仍为既有环境 patrol）；真库 delete/completedStages、真实进程重启、真实 Spark 未验 |
| V-029 | Pipeline 全阶段 fail-fast L1 矩阵 | PARTIAL | `384dedf`：7 个 Spark 承载阶段逐格失败；PipelineServiceTest 38/38；default 1022 MATCH；真实 Spark 失败形态未验 |
| V-030 | 统一测试入口 abandoned mutex 接管 | PASS | `a253446`：真实 named-mutex owner 异常退出后，真实 `run-tests.ps1` 命中 AbandonedMutexException 并安全接管；正常 default 1022/13/110 MATCH；跨用户/路径等价等不在本项 |

`BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT` 已执行并经总控接受为 **BLOCKED_ENV**。原始结果 commit `2c32a45fedfba65ab7c5510b1662631b2d0adce2`；接受记录见 `docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`。当前没有可执行的 Stage 7 下一门：3307 管理员认证恢复后应新建 `BATCH-Q-R1`，固定届时最新开发基线重跑 isolated 55/55；在其 PASS 前禁止进入完整 HTTP ingestion→pipeline 链，且禁止回退 3306。

## 3. 已验证工作项摘要

### V-002 — Web 统一验证入口

- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- `npm run verify = npm test && npm run build`。
- 多轮独立执行均为 Node tests 全绿 + Vite production build 成功。
- 最新 Batch P：**307/307 PASS** + Vite 5.4.21 production build PASS（672 modules，2.90s）。

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
- `decisionApprovalInput.test.js`：5/5 PASS。
- approve 不再硬编码负责人、不再自动 `+3天`；owner/dueDate 取消或空白时 API 前返回；dueDate 要求 `YYYY-MM-DD` 且经过真实日历日期校验；最终只发送 `{ owner, dueDate }`，成功后刷新。
- 真实浏览器 prompt/HTTP/状态机未做 E2E。

### V-014 — S3-66 DRAFT 提交审核时补 owner

- **Final verification baseline**：`c42ee34d4bfb2364c646f060f675af67974a6a6c`
- `decisionSubmitOwner.test.js`：4/4 PASS。
- DRAFT 提交不再走空 `{}`；已有 owner 仅作可编辑初始值，缺失时为空；取消/空白在 API 前 fail-closed；最终只发送 `{ owner }` 并刷新。
- 真实浏览器/HTTP/后端提交校验未做 E2E。

### V-015 / V-016 — S3-67/S3-68 决策 identity 与基线显示

- **Final verification baseline**：`1671d3a8b09c296d70e1dfb74098a3cb1132b977`。
- `decisionRowIdentity.test.js`：3/3；`decisionBaselineDisplay.test.js`：2/2。
- `decisionRows()` 保留真实后端 id；页面直接展示已格式化 baselineValue，不再二次格式化。
- Batch F Web full gate 217/217 + Vite build PASS。

### V-017 / V-018 — S3-69/S3-70/S3-71 决策执行上下文与本地日期

- **Final verification baseline**：`4366bcb7dcc6347657744e115f0cc0706aa6baea`。
- `decisionExecutionContextDisplay.test.js`：4/4；`pipelineLocalBusinessDate.test.js`：5/5。
- 决策页区分建议快照与批准锁定的基线快照，并展示 definitionVersion / dueDate；CSV 同步。
- Pipeline / Behavior / Sales / Overview 的 HTML date 默认值统一走本地日历 helper，不再用 UTC `toISOString().slice(0,10)`。
- Batch G Web full gate 226/226 + Vite build PASS。

### V-019 / V-020 / V-021 — S3-72/S3-73/S3-74 Pipeline retry、商品服务端分页、RFM matrix 属主

- **Final verification baseline**：`20db9072c37e20ebecf6648f55d002d36aa452b0`。
- `pipelineRetryHandling.test.js`：4/4；`productServerPagination.test.js`：5/5；`rfmMatrixOwnership.test.js`：4/4。
- Pipeline retry 有 busy 防重复、catch/finally 收口，失败不再显示 `run#undefined`。
- 商品页按后端 `page/size/sort/hasMore` 工作，不再把单页结果本地伪装成全量分页/排序；CSV 明确只导出当前页。
- RFM 八类矩阵由后端 `rfmMatrix` 唯一维护；旧响应只展示真实 `rfmSegments`，前端不制造第二套 0 人类目。
- Batch H Web full gate：**239/239 PASS** + Vite build PASS。

### V-022 — Post-J-R1 Web consistency hardening

- **Final verification baseline**：`bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`。
- 永久测试计划：`docs/verification/batches/BATCH-K-WEB-CONSISTENCY-HARDENING-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md`；raw result commit `c5d06389c6d630e5376d8736f9d7996af2e2996a`。
- 定向：`postJr1WebHardening` 4/4、`exportFreshnessGuard` 6/6、`rfmSnapshotPinning` 4/4、`rfmObservationWindow` 4/4、`rfmMatrixOwnership` 4/4，共 **22/22 PASS**。
- Web full gate：**261/261 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 build PASS（672 modules，2.71s）。
- 同快照组合/回声拒绝、RFM primary publisher 保留、Sales/Overview handler + shared CSV freshness/empty-subset fail-closed、Decision write-action busy reentry guards 均满足计划语义检查。
- 未覆盖真实浏览器交互、真实 HTTP race、后端 decision 状态机/DB 写入、3307 与 Spark/Hive/Flume E2E。

### V-023 — Post-Batch-K Web interaction consistency

- **Final verification baseline**：`395eead89d78d0a40665f2b985002371943f5eb0`。
- 永久测试计划：`docs/verification/batches/BATCH-L-WEB-INTERACTION-CONSISTENCY-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-L-WEB-INTERACTION-CONSISTENCY-RESULT.md`；raw result commit `c573a21a697ff48cbc6ee6184117555121464466`。
- 定向：`opsInteractionHardening` 5/5、`aiDraftQueryConcurrency` 5/5、`aiAskCancellation` 5/5、`exportFreshnessGuard` 6/6，共 **21/21 PASS**。
- Web full gate：**271/271 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 build PASS（672 modules，2.84s）。
- Ops 三类 admin 写动作 handler 级 `busy` 防重入、指标/流水线/审计导出资格与 handler/UI 同源、AI 决策草稿创建与新问答互斥、既有 ask cancel 序号/abort 语义均满足计划 §7 十项复核。
- 未覆盖真实浏览器双击/prompt 时序、真实 HTTP race、admin 权限与实际持久化、decision 状态机/DB 写入、AI provider/Text-to-SQL 运行时、3307 与 Spark/Hive/Flume E2E。


### V-024 — Batch M/M-R1 Pipeline + Product interaction consistency

- **Failed Batch M baseline**：`61776daf52cfcd396325d7bbdf56e890f1731224`，定向 28/28 绿，但全量 **273/274**，失败为 `pipelineLocalBusinessDate.test.js` 仍绑定旧的直接 `businessDate.value` 字面量；生产代码方向正确。Raw FAIL result commit：`0c46b9c79ba82252ef1e8a961e9def0eb32fabba`。
- **Final verification baseline / M-R1**：`7748caf8b2628bd47ed5075db62ec2cd26a42fe6`。
- 接受结果：`docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`；raw result commit `6406880dd00ba13c769ba91a85a3a3a8b3464c35`。
- 定向 **33/33 PASS**；Web full gate **274/274 PASS**；Vite 5.4.21 production build PASS（672 modules，4.55s）。
- Pipeline 在首次 await 前冻结 business date/runtime profile，busy 期间锁输入/刷新；Products 服务端分页导出资格与 loading 交互收口；M-R1 仅同步陈旧测试守卫，生产文件与 M 失败批次逐字节一致。
- 初次 FAIL 保留用于审计，不改写历史。
- 未覆盖真实浏览器交互、真实 HTTP race、后端/DB 运行时与 3307 E2E。

### V-025 — Batch N/N-R1 analysis + post-N interaction consistency

- **Failed Batch N baseline**：`58411f92a8e5591c435f5597143896f4fadac020`。新增目标测试 8/8 全绿；定向 **29/30**、Web full gate **280/282**，两条既有 characterization guard 仍绑定旧 `exportable` 字面量，Vite build 因 test fail 未执行。Raw FAIL result commit：`bd4f41d46cd021177f85792d52c4cdc5e36326f6`。
- **Final verification baseline / N-R1**：`0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`。
- 永久测试计划：`docs/verification/batches/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`；raw result commit `23c02d5c4410a0f49786a18c4645ac34ef625875`；accepted archive commit `d379d39d8d59288e9487a002468b410649454001`。
- 定向 **53/53 PASS**；Web full gate **296/296 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 production build PASS（672 modules，4.66s）。
- Behavior / Sales / Overview / RFM loading 防重入与实际导出子集资格已收口；同时验收 Login in-flight input lock、AI history sequence/Abort late-response protection、BaseChart reactive-height resize、Ops refresh/admin-write `loading || busy` 双向互斥。
- N-R1 修复只改 `rfmMatrixOwnership.test.js` 与 `postJr1WebHardening.test.js` 两个测试文件，不改生产语义；初次 N FAIL 结果完整保留。
- 未覆盖真实浏览器时序、真实 HTTP race、浏览器 CSV、后端 runtime/DB、3307 与 Spark/Hive/Flume E2E。


### V-026 — Batch O secondary-read concurrency

- **Final verification baseline**：`d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`。
- 永久测试计划：`docs/verification/batches/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md`；raw result commit `0635bad33a29ca0b9b183dbaf8573e0d801f3296`；accepted archive commit `632502f3fa638ea2b00f0401e6bceff1ae7dfdc8`。
- 定向 **55/55 PASS**；Web full gate **305/305 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 production build PASS（672 modules，3.00s）。
- RFM `usersError` 与 Decision `evaluations/evaluationError` 的组合读取旁路状态均有独立 latest-request 序号所有权；旧请求晚到不能覆盖新状态。
- Decision 手工刷新与六个状态写动作统一 `loading || busy` 双向互斥；内部写后 `flush()` 仍直连 `load({})`，不被自身 busy 阻断。
- 既有 submit/approve/reject/cancel/evaluate payload 与输入校验、RFM snapshot pinning / observation window / matrix owner / export subset 语义全部回归保持。
- 未覆盖真实浏览器点击时序、真实 HTTP abort/late-response race、后端 Decision 状态机与 DB 持久化、3307 与 Spark/Hive/Flume E2E。


### V-027 — Batch P in-flight interaction locks

- **Final verification baseline**：`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`。
- 永久测试计划：`docs/verification/batches/BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS-RESULT.md`；raw result commit `4493bfa6818e9d3fb030616b55c4d6f345068cf4`；accepted archive commit `86ceda3764c2d11c25c3afc2192041c2fe7bcf00`。
- 定向 **41/41 PASS**；Web full gate **307/307 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 production build PASS（672 modules，2.90s）。
- Sales loading 期间锁本地排序/翻页，避免新的日期范围结果落在请求途中被改写的页码；AI 草稿创建期间五个可编辑字段锁定，与已冻结 payload 保持一致。
- Pipeline 手工刷新、触发、重试和业务输入统一 `loading || busy`；成功写后的内部 `load()` 仍可在 busy 期间刷新。
- 既有 AI ask/draft/history 并发守卫与 Pipeline operationId、业务日期/runtime profile 冻结、context/retry 语义全部回归保持。
- 未覆盖真实浏览器点击/键入时序、真实 HTTP race、Pipeline 后端执行/幂等、AI 草稿持久化/状态机、3307 与 Spark/Hive/Flume E2E。

### V-028 — Pipeline 多 attempt / retry-from-stage / startup recovery L1 加固

- **Implementation baselines**：`0f773220ad6a29d4b839e52d29b5b5fb6124d94b`（多 attempt + retry-from-stage）＋ `96f4ad2e55672e3a49a1faf5ae3be23fc237bef3`（startup recovery → admin resume 桥接）。
- 目的：在 Stage 7 的 3307 运行门被环境阻塞期间，收口 S3-48 已登记且不依赖真库的恢复路径纯 Java 缺口，不改变生产语义。
- 新增 `retryFromStagePreservesPrefixAndReexecutesExactSuffix`：首跑成功后从 `BUILD_DWS` 起重算；验证 mapper delete 被调用、成功前缀阶段记录 ID 与 `targetSnapshotId` 保持、删除后缀重新创建、Spark 提交恰好是声明后缀。
- 新增 `repeatedRetriesKeepSuccessfulPrefixSingleAndAdvanceAttempt`：`BUILD_DWS` 连续两次失败、第三次成功；attempt 1→2→3，已成功前缀保持单行，失败阶段保留两失败 + 一成功的三条 attempt 证据，后继阶段只在最终成功后执行一次。
- `96f4ad2` 新增桥接用例 `startupRecoveryThenAdminResumeContinuesFromFirstIncompleteStage`：构造旧进程死在 `BUILD_DWS` 前的真实形状（前四阶段 SUCCESS、run RUNNING、snapshot 已冻结），先由 `PipelineRecoveryService` 标记 `RUN_INTERRUPTED`，再由管理员 `resume`；最终 attempt=2、snapshot 不变、成功前缀不重建，只提交 `BUILD_DWS → PUBLISH_METRIC` 后缀。首轮 RED 暴露的是 fixture 不真实（成功阶段存在但 snapshot 仍 null），按生产 `execute()` 的冻结时序修正 fixture 后转绿，未改生产代码。
- developer targeted gate：`PipelineServiceTest` **32/32 PASS**，F/E/S=0，BUILD SUCCESS。
- default 两轮量数链：`0f77322` 后 analytics 1015（+2）；`96f4ad2` 后 analytics **1016 (F=1/E=0/S=1)**，新增 +1 仍全部在 warehouse-pipeline（174→175）；mall 13、generator 110；三棵树 1139。更新 baseline 后 fresh 收口轮 **1016/13/110 全部 MATCH**。
- default 唯一失败仍是既有环境 patrol：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 / actual 0）；无本工作集新增失败。
- **Remaining / why PARTIAL**：纯 Mockito L1 不初始化 MyBatis-Plus lambda column cache，所以 `retryFromStage` 仍只证明发出了 mapper delete，并由内存 store 模拟 delete 后可见状态；**不证明真实 MySQL 上 `LambdaQueryWrapper.delete` 的 SQL 形状/事务可见性**。startup recovery 桥接同样是内存持久化 fixture，不是实际杀/启 Spring 进程。真库 `completedStages`、真实进程重启、真实 Spark 重跑次序与 Stage 7 E2E 仍待后续独立验证。
- 不触碰 3306/3307，不跑 isolated/spark；没有生产 Java、DDL、迁移或正式契约变更，因此不新增 Decision Log / ADR。

### V-029 — Pipeline 全阶段 fail-fast L1 矩阵

- **Implementation baseline**：`384dedffd18b6d5d3ab783a17ad96af287d80277`。
- 原 `failedStageStopsEveryLaterStageOnTheWholeChain` 只有 `BUILD_DWS` 一个失败注入点；现改为 JUnit 参数化矩阵，覆盖 `INIT_SCHEMA / LOAD_ODS / BUILD_DWD / BUILD_DWS / BUILD_ADS / QUALITY_CHECK / PUBLISH_METRIC` 全部 7 个 Spark 承载阶段。
- 每个参数实例都要求：阶段记录恰好是 `STAGE_ORDER` 到失败阶段的前缀；Spark 提交恰好是去掉本地 `WAIT_LANDING` 后到失败阶段的前缀；失败阶段为 FAILED、run 为 FAILED；所有后继阶段既无阶段记录也从未被提交。
- 原 1 个 BUILD_DWS 样例替换为 7 个参数实例，因此测试净增 **+6**；定向 `PipelineServiceTest` **38/38 PASS**。
- default 量数轮：analytics **1022 (F=1/E=0/S=1)**，相对 1016 的 +6 全部落在 warehouse-pipeline（175→181）；mall 13、generator 110；三棵树 1145。更新 baseline 后 fresh 收口轮 **1022/13/110 全部 MATCH**。
- 唯一失败仍是既有环境 patrol `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 / actual 0），不是本工作集新增回归。
- **Remaining / why PARTIAL**：矩阵使用 Fake `SparkStageExecutor`，证明的是 Java 编排层 fail-fast；不证明真实 `spark-submit` 子进程、超时、进程崩溃、部分输出后失败或 Hive/HDFS 失败形态。真实 Stage 7 / Spark 专项验证仍必需。
- 本工作集只改测试与计数基线，不触碰生产代码、DDL、3306/3307、正式契约；不需要新的 Decision Log / ADR。

### V-030 — 统一测试入口 abandoned mutex 接管

- **Implementation baseline**：`a2534466335144e3d34f2d8a3e9e5c434e7d3c8e`。
- `scripts/run-tests.ps1` 原本已经 catch `System.Threading.AbandonedMutexException` 并把 `$lockTaken=true`，但没有任何可观察证据能区分“普通首次取得”与“异常 owner 后接管”。本项只增加 `$abandonedMutexRecovered` 标记与一行明确输出，不改变 mutex 名、WaitOne(0)、拒绝条件、退出码或套件执行语义。
- 真实进程探针：keeper PowerShell 进程先打开与指定 LogDir 对应的 `Local\v25tests-...` named mutex 句柄；owner 进程取得同一 mutex 后直接 `Environment.Exit(0)`，不调用 `ReleaseMutex`；keeper 继续持有对象句柄，保证 mutex 对象不因 owner 退出而销毁。随后真实 `scripts/run-tests.ps1` 使用同一 LogDir 启动，输出：`并发锁恢复: 检测到上一持有线程异常终止，已通过 AbandonedMutexException 安全接管。`
- 为避免接管探针再跑整套 Maven，接管后的 MavenCmd **故意替换为 `where.exe`**，因此后续 `0 tests / exit 7` 是预期探针终止手段，**不属于套件失败证据，也不作 PASS 依据**；PASS 只针对 abandoned-mutex 分支被真实触发且没有 `[REFUSE exit=5]`。
- 另一次真实 Maven default 正常路径在新增可观察输出后完整执行：analytics **1022 MATCH**、mall **13 MATCH**、generator **110 MATCH**、总计 **1145 MATCH**；唯一红仍为既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 / actual 0）。普通首次取得路径没有打印 abandoned 恢复行。
- **边界**：只证明当前 Windows / `Local\` namespace 下的 abandoned owner 接管；不证明多用户/多会话 `Global\` 互斥、路径别名规范化、isolated/spark/all 并发、Windows PowerShell 5.1，也不证明两轮并发都能完成。
- 零 3306/3307 操作；无生产代码、DDL、正式契约变更。

### V-031 — Stage 7 真实 Spark smoke 启动前边界加固

- **Implementation baseline**：`9c0556f`。
- `SparkStageExecutorSmokeIT` 不再硬编码读取 `D:\\Develop_code\\GraduationProject` 的 Spark JAR / golden dataset；两者统一经测试侧唯一 `RepoRoot` 从**当前 worktree**解析，并显式断言仍位于该根下，避免“当前 Java + 旧 worktree Spark 产物”混成伪证据。
- `spark-submit` 默认仍为既有 `D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd`，但可由 `-Dv25.spark.submit=<path>` 覆盖；启动前必须验证可执行文件真实存在。Windows 额外要求 `HADOOP_HOME` 非空且 `bin/winutils.exe` 存在，缺失时在启动任何 spark-submit 前 fail-fast。
- JobResult 轮询新增进程状态短路：若 `LocalProcessSparkSubmitter.status(externalJobId)` 已为 `FAILED` / `CANCELLED`，立即携带子进程日志失败，不再把“进程早已死掉”误报成数分钟后的 JobResult 超时。
- developer compile：`mvn -f analytics-server/pom.xml -pl warehouse-pipeline -am -DskipTests test-compile` **PASS**。
- 当前机器真实触发：`SparkStageExecutorSmokeIT` 在约 3 秒内按预期 fail-fast，唯一失败为 `HADOOP_HOME` 未设置；常见本地开发目录未找到 `winutils.exe`。这证明**环境检查生效**，不构成真实 Spark PASS。
- **Remaining / why PARTIAL**：补齐受信任的 Windows Hadoop 工具环境后，需重新构建当前 worktree 的 `spark-jobs` JAR 并真正执行 smoke，取得 spark-submit / ODL / golden dataset 行为证据；本项不涉及 3306/3307，不改变生产代码、DDL 或正式契约。

### V-032 — Metric 写入型 MySQL IT 双库所有权与 provisioning 收编（已完成）

- **Implementation baseline**：`3960cca` + `31d5ed5` + `49b82d0` + `c29ac50` + `cd95d48` + `9b2f18f`；证据后统一基线登记 `a496434`。
- 开工复核发现 `MetricAdsMySqlIT` / `MetricPublisherMySqlIT` 虽已有 `IsolationProfileCondition`、`TestIsolationGuard`、runId 自有对象和 cleanup 白名单，但旧夹具仍通过 metric 连接写 `runtime_profile`，等价于假设 meta/metric 合库；V3 实际物理所有权是 `runtime_profile → analytics_meta`，`metric_snapshot`/`metric_value`/ADS → `analytics_metric`。
- 两类 IT 已拆成三个实际连接角色：`metaDs(context.metaDb())`、`publishDs(context.metricDb())`、`readDs(context.metricDb())`；`runtime_profile` 登记/查询/cleanup 全部迁到 `JdbcTemplate meta`，指标快照/值/ADS 仍只走 metric；meta 与 metric 连接各自必须先通过 `verifyBeforeWrite`。
- 新增默认档结构守卫 `MetricMySqlItDatabaseOwnershipTest` **3/3 PASS**，防止以后把 `runtime_profile` 又写回 metric 连接；`metric-analysis` `test-compile` **BUILD SUCCESS**。
- default 量数轮：analytics **1025 (F=1/E=0/S=1)**，相对旧 1022 **+3** 恰好来自新守卫（metric-analysis 97→100）；mall 13、generator 110 均 MATCH。基线 1022→1025 后 fresh 收口轮为 **1025 MATCH / 13 MATCH / 110 MATCH / 总计 1148 MATCH**；唯一失败仍是既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（expected 43 / actual 0）。
- **历史预收编边界已解除**：`cd95d48` 已给 `MetricAdsMySqlIT` / `MetricPublisherMySqlIT` 加 `@Tag("it")`，并把 runner 的假绿门从只检查 `IsolationGuardMySqlIT` 扩成三个已收编类逐一必须出现；此变化是在真实双库 schema gate 首次 PASS 后才实施，没有提前把未真跑用例塞进标准门禁。
- **配置入口补强 `31d5ed5`**：`TestIsolationGuard` 新增 `V25_IT_*` 环境变量来源，优先级固定为 `-Dv25.it.* → V25_IT_* → integration.local.properties`；camelCase/dot 键统一映射为 snake-case（例 `serverFingerprint→V25_IT_SERVER_FINGERPRINT`、`metric.publish.password→V25_IT_METRIC_PUBLISH_PASSWORD`）。一旦环境来源出现，8 个核心上下文字段必须完整，禁止与陈旧档案混拼；`requiredProperty` 同样按系统属性→环境变量→档案且无正式默认值。模板 `scripts/it-isolation.env.template` 已登记完整 analytics 双库变量契约。`TestIsolationGuardTest` **29/29 PASS**；新增 1 条默认测试使 analytics 1025→1026，量数轮 `DRIFT +1` 后基线更新，收口 **1026 MATCH / 13 MATCH / 110 MATCH / 1149 MATCH**，唯一红仍是既有 patrol。
- **provisioning / Flyway gate `49b82d0`**：`it-prepare-isolation.ps1` 新增显式 `-IncludeAnalytics`，默认 mall/generator 路径不变；开启时才派生 `<runId>_analytics_meta`、`<runId>_analytics_metric`、`<runId>_metaapp`、`<runId>_metricapp`，两个账号各自只获自己库的写权限，analytics 密码只从 `V25_IT_META_PASSWORD` / `V25_IT_METRIC_PUBLISH_PASSWORD` 进程环境读取。DryRun 两档均 exit 0；缺 analytics 密码的真实执行探针 **exit 2** 且两个既有 credref 文件均 `False`，证明拒绝发生在落盘/连库之前。`run-isolated-tests.ps1` 新增显式 `-IncludeAnalyticsWriteIts`（只允许 `analytics|all`），完整派生/打印掩码后的 `V25_IT_*`、将 meta/metric 两个账号加入只读实例探针；缺两个 analytics 密码时实测 **exit 5**，发生在 MySQL 探针前。双库探针通过后的固定顺序是：注入完整 V25 上下文 → `platform-app -Pisolated-analytics-schema` → 日志必须命中 `AnalyticsIsolationFlywayIT` → schema 失败即 `exit 7`、拒绝后续写入型 IT → 才进入原 analytics isolated runner。
- **双库 Flyway IT `49b82d0` / `9b2f18f`**：`platform-app` 的 `AnalyticsIsolationFlywayIT` 在 Q-R1 真实 3307 上 **1/1 PASS**。重复使用已迁移 runId 时发现 `MigrateResult.targetSchemaVersion` 可为 null 的测试断言 bug，`9b2f18f` 改为 Flyway current version 非空 + 第二次 migrate=0 + `runtime_profile` / `metric_snapshot` 物理存在；修后重复验证 PASS。脚本形态守卫现为 **4/4 PASS**，并额外钉住两个写入 IT 必须保持 `@Tag("it")`。
- **默认档回归**：收编新增 1 条结构守卫后先量数 analytics **1030 (F=1/E=0/S=1) DRIFT +1**，确认增量只来自 `AnalyticsIsolationScriptsContractTest` 3→4；基线更新后 fresh 收口 **1030 MATCH / mall 13 MATCH / generator 110 MATCH**，唯一红仍是既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，两个新 `@Tag("it")` 类未污染默认档。
- **Q-R1 真库收口**：RunId `stage7q1_20260918_152245`，同一被测 SHA `9b2f18f`、同一 3307 实例。analytics schema **1/1 PASS**；analytics **11/11 PASS**（6+2+3）；controller 随后从忽略的 mall/generator credref 内部加载口令且不打印，直接跑 governed runner 得到 mall **30/30 PASS**、generator **19/19 PASS**。合计 **60/60 PASS**。证据后 `a496434` 将 unified isolated 基线 `analytics 6→11 / total 55→60`，并让 `run-tests.ps1 -Suite isolated|all` 自动传 `-IncludeAnalyticsWriteIts`、预检四类口令、独立复核三个 analytics IT 类。
- **Remaining**：V-032 本身已完成；剩余的是更高层 Stage 7 HTTP ingestion → pipeline → Spark/Hive/HDFS 实链、真实 Spark smoke（V-031）等独立验证项，不能由本次 3307 PASS 代替。

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
- default：analytics 1013 MATCH，mall 13 MATCH，generator 110 MATCH，总 1136 MATCH。
- 唯一红仍为既有环境红。

### Batch E — `c42ee34d4bfb2364c646f060f675af67974a6a6c`
- 永久报告：`docs/verification/results/BATCH-E-WEB-DECISION-INPUT-RESULT.md`。
- Web 212/212 PASS；Vite production build PASS。
- V-013 5/5；V-014 4/4；New failures 0。

### Batch F — `1671d3a8b09c296d70e1dfb74098a3cb1132b977`
- 永久测试计划：`docs/verification/batches/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md`。
- V-015 3/3；V-016 2/2；决策回归 16/16。
- Web full gate：217/217 PASS；Vite build PASS。

### Batch G — `4366bcb7dcc6347657744e115f0cc0706aa6baea`
- 永久测试计划：`docs/verification/batches/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-RESULT.md`。
- Raw result commit：`8d450b7c427c8ae14f72f86d2056aa6d6a262eb9`。
- S3-69/S3-70 4/4；S3-71 5/5；决策回归 21/21。
- Web full gate：226/226 PASS；Vite build PASS；New failures 0。

### Batch H — `20db9072c37e20ebecf6648f55d002d36aa452b0`
- 永久测试计划：`docs/verification/batches/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-RESULT.md`。
- Raw Code Agent result：`verification-results:docs/verification/results/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-RESULT.md`，commit `3fefe158dbaaf3448f8f59415c6585569f90c2ee`。
- S3-72 4/4；S3-73 5/5；S3-74 4/4；关键回归 9/9。
- Web full gate：**239/239 PASS**；Vite production build PASS；New failures 0。
- 未覆盖真浏览器、真 HTTP、后端状态机/DB 排序与 Spark/Hive/Flume E2E。

### Batch K — `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`
- 永久测试计划：`docs/verification/batches/BATCH-K-WEB-CONSISTENCY-HARDENING-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md`。
- Raw Code Agent result：`verification-results:docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md`，commit `c5d06389c6d630e5376d8736f9d7996af2e2996a`。
- 定向 22/22 PASS；计划 §7 十项语义复核全部满足。
- Web full gate：**261/261 PASS**；Vite 5.4.21 production build PASS（672 modules，2.71s）；New failures 0；workspace clean。
- 未覆盖真浏览器、真 HTTP race、后端 decision 状态机/DB 写入、3307 与 Spark/Hive/Flume E2E。

### Batch L — `395eead89d78d0a40665f2b985002371943f5eb0`
- 永久测试计划：`docs/verification/batches/BATCH-L-WEB-INTERACTION-CONSISTENCY-PLAN.md`。
- 接受结果：`docs/verification/results/BATCH-L-WEB-INTERACTION-CONSISTENCY-RESULT.md`。
- Raw Code Agent result：`verification-results:docs/verification/results/BATCH-L-WEB-INTERACTION-CONSISTENCY-RESULT.md`，commit `c573a21a697ff48cbc6ee6184117555121464466`。
- 定向 21/21 PASS；计划 §7 十项语义复核全部满足。
- Web full gate：**271/271 PASS**；Vite 5.4.21 production build PASS（672 modules，2.84s）；New failures 0；workspace clean。
- 未覆盖真浏览器双击/prompt 时序、真 HTTP race、admin 权限与持久化、decision 状态机/DB、AI provider/Text-to-SQL runtime、3307 与 Spark/Hive/Flume E2E。


### Batch M — `61776daf52cfcd396325d7bbdf56e890f1731224`
- Pipeline / Product interaction cluster 初次验证。
- 定向 28/28 PASS；Web full gate **273/274 FAIL**。
- 唯一失败：`pipelineLocalBusinessDate.test.js` 仍要求旧 `businessDate.value + 'T00:00:00'` 字面量，而生产已正确冻结 `requestedBusinessDate`。
- Vite build 未执行（test fail 短路）；raw FAIL result commit `0c46b9c79ba82252ef1e8a961e9def0eb32fabba`。
- 失败历史保留，不覆盖。

### Batch M-R1 — `7748caf8b2628bd47ed5075db62ec2cd26a42fe6`
- 仅同步陈旧 `pipelineLocalBusinessDate` characterization guard；生产语义不变。
- 定向 **33/33 PASS**。
- Web full gate：**274/274 PASS**；Vite 5.4.21 build PASS（672 modules，4.55s）。
- Raw result commit：`6406880dd00ba13c769ba91a85a3a3a8b3464c35`。
- 接受结果：`docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`。

### Batch N — `58411f92a8e5591c435f5597143896f4fadac020`
- Behavior / Sales / Overview / RFM interaction/export-subset cluster 初次验证。
- 新增 `analysisFilterInteractionHardening` 8/8 PASS；定向 **29/30 FAIL**。
- Web full gate：**280/282 FAIL**；两条既有守卫仍绑定旧 `exportable` 字面量；Vite build 未执行。
- Raw FAIL result commit：`bd4f41d46cd021177f85792d52c4cdc5e36326f6`。
- 失败历史保留，不覆盖。

### Batch N-R1 — `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`
- R1 相对修复前开发 HEAD `dcc15740...` 只修改两个测试守卫，生产文件零改动；同时继承 post-N 的 Login / AI history / BaseChart / Ops 低风险簇。
- 定向 **53/53 PASS**。
- Web full gate：**296/296 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 build PASS（672 modules，4.66s）；workspace clean。
- Raw result commit：`23c02d5c4410a0f49786a18c4645ac34ef625875`。
- 接受结果：`docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`；archive commit `d379d39d8d59288e9487a002468b410649454001`。


### Batch O — `d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`
- RFM / Decision secondary-read concurrency + Decision read/write serialization。
- 定向 **55/55 PASS**。
- Web full gate：**305/305 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 build PASS（672 modules，3.00s）；workspace clean。
- Raw result commit：`0635bad33a29ca0b9b183dbaf8573e0d801f3296`。
- 接受结果：`docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md`；archive commit `632502f3fa638ea2b00f0401e6bceff1ae7dfdc8`。
- 未覆盖真实浏览器/HTTP race、Decision 后端状态机/DB 持久化、3307 与 Spark/Hive/Flume E2E。


### Batch P — `2f3e79f676e1b614fe9a57e71e7ecad68106a51f`
- Sales / AI Draft / Pipeline in-flight interaction locks。
- 定向 **41/41 PASS**。
- Web full gate：**307/307 PASS**；failed/cancelled/skipped = 0；Vite 5.4.21 build PASS（672 modules，2.90s）；workspace clean。
- Raw result commit：`4493bfa6818e9d3fb030616b55c4d6f345068cf4`。
- 接受结果：`docs/verification/results/BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS-RESULT.md`；archive commit `86ceda3764c2d11c25c3afc2192041c2fe7bcf00`。
- 未覆盖真实浏览器/HTTP race、Pipeline 后端实际执行/幂等、AI 草稿状态机/持久化、3307 与 Spark/Hive/Flume E2E。

## 6. 已知环境红与未覆盖面

- 已知环境红：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，当前环境 expected 43 / actual 0；保留，不 delete/skip/放宽。
- 尚未覆盖：真实浏览器/E2E、前后端联调、真实 LLM provider、真实 3307 JDBC timeout、operation audit 真库写入、`spark` 档、`isolated` 档、3306/3307 真数据链与 Spark/Hive/Flume E2E。

## 7. 下一批验证触发点

达到以下任一节点时，把所有相关测试一次性写入 `CURRENT_BATCH.md` 并冻结 plan：

1. 一个功能簇完成；
2. 某阶段准备收口；
3. 即将进入真实 MySQL / Spark / Hive / Flume / HTTP E2E；
4. 即将合并 `main`；
5. 未验证变更累积到继续开发会明显增加失败定位成本；
6. 后续工作直接依赖某个尚未验证的运行行为。

普通低风险并列工作不再因为每个小改动单独停下来测试。
