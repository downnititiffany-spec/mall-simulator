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
