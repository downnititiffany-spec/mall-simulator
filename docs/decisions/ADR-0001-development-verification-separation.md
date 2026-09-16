# ADR-0001：设计实现与独立验证分离

- Status: Accepted
- Date: 2026-09-16

## Context

此前项目主要采用“指导书详细描述实现 → Code Agent 按指导书编码并自行测试”的模式。随着当前 ChatGPT 已直接连接 GitHub，并承担项目设计牵头职责，继续沿用该模式会造成重复设计、实现与测试同源，以及长上下文下的确认偏差风险。

同时，Code Agent 与 Codex Work 具备本地/执行环境验证价值，尤其适合 Maven、Spark、MySQL 3307、Hive/Hadoop、Flume、HTTP、前端运行和对抗式边界测试。

## Decision

采用职责分离：

1. ChatGPT = Architect + Implementer，负责设计、生产实现、developer tests、GitHub 远端写入和修复决策；
2. Code Agent = Execution Tester，只读 GitHub，针对指定 commit 运行 ChatGPT 提供的测试与本地环境验证；
3. Codex Work = Adversarial Verifier，只读 GitHub，主动设计反例、临时探针、故障注入，目标是发现设计/实现错误；
4. 对重大模块增加 Independent Reviewer Chat，只做独立审查，不修改 Git；
5. 用户作为 Product Owner / Final Arbiter，处理范围、重大架构、正式契约及无法自动消解的冲突。

## Consequences

### Positive

- 设计/实现与验收分离，降低“自己出题、自己答题、自己判卷”风险；
- GitHub 写入单一责任人，减少分支漂移与并发修改冲突；
- 验证方可更专注真实环境与反例，而非重复实现；
- 长期决策落盘，可跨会话恢复。

### Negative / Cost

- ChatGPT 的设计与实现责任更重；
- 模块完成后需要 Test Handoff 与独立验证循环；
- 对重大模块可能增加第三方 Reviewer 成本；
- 普通聊天不是后台常驻进程，模块级工作仍受当前会话/工具执行边界约束。

## Risk Controls

- 仓库事实优先于聊天记忆；
- 一次只维持一个有限实现工作集；
- 先定义不变量与失败语义，再实现；
- 重要能力尽量有两个不同层级的证据；
- 所有正式决策落 GitHub；
- 重大模块主动触发 Independent Reviewer；
- 验证方针对精确 commit SHA，不针对模糊的“最新版本”。

## Supersedes

本 ADR 取代此前“Code Agent 作为主要编码执行者、指导书承担详细实现说明”的执行方式；不改变 V3.0 正式项目范围与总体架构。
