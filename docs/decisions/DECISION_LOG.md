# Decision Log

> 本文件记录当前正式执行决策。普通实现决策追加在此；重大、长期、跨模块或难以回滚的决策另建 ADR，并在本表索引。
> V3.0 指导书与设计文档保持冻结；本日志不得静默覆盖其正式范围、架构或契约语义。

## 2026-09-16

### D-001 — GitHub 远端唯一写入者

**Decision**：毕业设计后续开发中，GitHub 远端写入只由当前 ChatGPT 执行。Code Agent、Codex Work、Independent Reviewer 均只读远端仓库。

**Reason**：避免多执行者同时 push、相互覆盖、测试对象漂移，并确保设计与实现修改有单一责任人。

**Operational consequence**：验证方必须针对精确 commit SHA 测试；发现问题只返回证据，不直接修远端代码。

### D-002 — 所有项目决策必须落盘

**Decision**：任何正式项目决策都必须进入 GitHub。聊天上下文只作为工作缓存，不构成正式决策源。

**Storage rule**：普通实现决策进入本 Decision Log；重大决策进入 `docs/decisions/ADR-*.md`；当前事实进入 `docs/PROJECT_STATUS.md`。

**Reason**：降低长上下文压缩、换会话、记忆偏差造成的实现漂移。

### D-003 — 设计实现与独立验证分离

**Decision**：ChatGPT 负责 Architect + Implementer；Code Agent 负责 Execution Tester；Codex Work 负责 Adversarial Verifier。

**Boundary**：ChatGPT 写生产代码与 developer tests；Code Agent 主要跑既有测试和本地环境；Codex Work 主动设计反例、故障注入和临时探针。两类验证者均不得写远端 Git。

**ADR**：见 `ADR-0001-development-verification-separation.md`。

### D-004 — 重大模块启用 Independent Reviewer

**Decision**：遇到高风险或长期影响模块时，增加新的独立 Reviewer Chat。由 ChatGPT 主动识别触发点并提供完整 Reviewer Prompt。

**Typical triggers**：重大 Flyway/数据模型、鉴权/安全、核心 Spark 指标语义、AI SQL/LLM 安全、状态机/幂等/恢复、跨模块正式契约、发布回滚、跨多个核心模块的高风险变更。

### D-005 — 指导书后续转为验收规范型

**Decision**：V3.0 不原地重写。下一正式版本 V3.1 起，指导书主要描述阶段目标、完成定义、不变量、测试矩阵、边界、故障注入、证据要求与通过标准，不再承担具体代码实现说明书职责。

**Reason**：设计与实现已由 ChatGPT 直接承担，测试方需要独立验收规范而不是照抄实现步骤。

### D-006 — 当前聊天的模块级连续开发停止条件

**Decision**：普通实现中，不因子任务、单个 commit 或普通 backlog 完成而停。当前模块持续实现到代码面完整并需要 Code Agent/Codex Work 独立测试时才形成 Test Handoff；HARD DECISION 或工具/依赖阻塞除外。

**Reason**：避免原先“一小段实现 → 汇报 → 等待下一任务”的人工派工节奏。

### D-007 — 当前开发分支

**Decision**：继续以 `feature/v3-development` 作为当前远端开发主线；仅 ChatGPT 对该分支写入。`main` 不自动合并，必须由用户明确批准。

**Current baseline when recorded**：`d3a7a0b13d4ec24801230293df5b115a413c07cf`（S3-52 后）。

### D-008 — S3-53 AI evidenceId 形状兼容

**Decision**：`buildAiEvidenceContext` 对证据包 ID 采用“顶层优先、嵌套回退”的只读归一化：先读取 `/ai/queries` 当前响应顶层 `evidenceId`；若顶层缺失或为占位值，再读取 `explanation.evidence.evidenceId`。两处都无真实值时返回 `null`，不得生成 ID。

**Reason**：`EvidencePackage` 本身拥有 `evidenceId` 字段，而当前前端只搬运顶层 `evidenceId`；这会使嵌套 EvidencePackage 已给出真实 ID 时，页面仍显示“未提供”，且 AI 建议转决策草稿无法使用已有 evidence package 锚点。该改动只做响应形状兼容，不改变后端值语义、决策契约或锚点优先级。

**Invariants**：

- 顶层真实 `evidenceId` 仍优先，保持现有 `/ai/queries` 行为；
- `unknown` / `UNKNOWN` / 空白仍不是合法 evidenceId；
- 真实 evidence package ID 优先于 snapshot 锚点；
- 决策草稿请求中 evidence package 与 snapshot 锚点仍二选一；
- 前端不得构造、改写或猜测 evidenceId。

**Implementation commits**：`0054870`（生产逻辑）+ `0ebd0e8`（developer tests）。

### D-009 — 独立测试改为批量延迟验证

**Decision**：完成一个小模块后，不再默认暂停开发等待 Code Agent / Codex Work 的测试结果。每个工作项先把实现、developer tests、关键不变量和后续验证要求登记到统一 `docs/verification/DEFERRED_TEST_PLAN.md`，然后继续实现其它并列或仅依赖稳定接口的工作项。

**Stop rule**：只有当后续工作直接依赖尚未验证的运行行为/数据库形状/协议形状/性能边界，或当前改动属于会向多模块扩散错误的共享基础设施、高风险状态/权限/安全/迁移边界时，才暂停受影响链路先取测试结果；其它并列模块继续。

**Batch point**：功能簇完成、阶段收口、进入真实 DB/Spark/Hive/E2E、合并 main 前，或延迟验证队列过长到显著增加定位成本时，集中跑一批。

**Reason**：把本地测试作为独立验证队列，而不是把每个小实现变成人工停工点；同时保留精确 commit SHA 和独立测试计划，避免测试延期后无法定位回归。

### D-010 — Git commit 主题显式携带精确时间

**Decision**：从本决策起，由 ChatGPT 创建的 GitHub commit message 必须在主题中显式带项目时间戳，统一格式为 `[YYYY-MM-DD HH:mm:ss +08:00]`。

**Reason**：GitHub 列表页经常只显示“几分钟前/几天前”的相对时间；虽然 commit 元数据本身保存精确 author/committer 时间，但把时间写进主题后，用户无需点进详情即可按分钟/秒核对开发顺序。

**Identity**：通过当前 GitHub 连接写入仓库时，GitHub 使用连接账户的身份记录提交；当前实测提交的 author/committer 均显示 `downnititiffany-spec`。ChatGPT 不伪造额外作者身份。

**Timezone**：项目研发记录继续使用 `+08:00`，与 V3 阶段既有开发事实记录保持一致。

### D-011 — Web 统一验证入口

**Decision**：`web/package.json` 以 `npm run verify` 作为前端统一验证入口，固定顺序为 `npm test` 后 `npm run build`。

**Reason**：延迟批量验证模式需要一个稳定、低歧义的前端入口；测试失败时不应继续把 build 成功误读为功能通过，因此单测先于生产构建。

**Boundary**：该入口只统一现有 Node 测试与 Vite production build，不代表真实浏览器/E2E、后端联调或权限链已经验证。

**Implementation commits**：`e36a350`（package script）+ `db810d6`（结构守卫）。

### D-012 — S3-54 AI 结论展示只消费后端 summary

**Decision**：`buildAiEvidenceContext` 必须显式搬运 `explanation.summary` 为页面结论字段；缺失或空白时返回 `null`，由页面显示既有“后端未给出结论文本”降级文案。不得从查询结果、证据字段或前端规则自行生成结论。

**Reason**：`AiAssistant.vue` 已读取 `evidenceContext.summary`，但上下文构造器此前从未返回该字段，导致后端已经给出 `ExplanationResult.summary` 时页面仍固定显示缺失文案。

**Boundary**：本改动只修展示链路，不改变 LLM/模板解释生成、证据数值、provider 判定、Text-to-SQL 或后端契约。

## 2026-09-17

### D-013 — AI 证据锚点 ID 保持后端原始形状

**Decision**：所有用于决策草稿锚点的 `evidenceId` / `snapshotId` 必须是后端返回的**精确字符串**。前端不得对 ID 做 `trim()`、数字转字符串或其它“修复后再接受”的归一化；`unknown` 任意大小写、空串、带前后空白、非字符串都视为无效。

**Reason**：S3-55 在 `context.js` 已把 ID 读取收紧为严格字符串，但 `decisionDraft.js` 仍先经过 `draftText()`，会把 `" EV-... "` / `" S... "` trim 成合法形状，形成第二个归一化所有者并绕过严格边界。S3-56 删除这层 ID trim，只让普通业务文本继续使用 `draftText()`。

**Invariants**：

- `context.js#isRealSnapshotId` 是当前前端 ID 真实性的单一判据；
- evidence package ID 与 snapshot ID 均不做 trim；
- 数字 ID 不自动转字符串；
- evidence package 真实值仍优先于 snapshot；
- 无真实锚点时仍 fail-closed，不构造决策草稿请求。

**Implementation commits**：`617642c`（S3-55 上下文严格读取）+ `776bc74`（S3-56 草稿锚点去二次归一化）+ `88c715e`（developer tests）。