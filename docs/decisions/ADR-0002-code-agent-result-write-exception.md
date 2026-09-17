# ADR-0002 — Code Agent 验证结果写入例外

- 状态：ACCEPTED
- 日期：2026-09-17
- 适用分支：`feature/v3-development` + 专用结果分支 `verification-results`

## Context

既有 D-001 / D-003 将 GitHub 远端写入集中在 ChatGPT，以避免多执行者修改源码、测试和正式文档。但 Execution Tester 的测试报告若只保留在聊天窗口或本地 `.verify`，存在会话丢失、本地清理后证据消失的风险。

## Decision

保留“Code Agent 不得修改项目实现”的原则，但增加一个最小、隔离的远端写入例外：

- Code Agent 仍对 `main`、`feature/v3-development` 和其它开发分支只读；
- Code Agent 唯一允许 push 的分支是 `verification-results`；
- 唯一允许提交的文件是 `docs/verification/agent-results/<batch-id>-RESULT.md`；
- 每次验证同时写本地 `.verify/CURRENT_BATCH_RESULT.md` 和 GitHub 结果文件；
- Code Agent 不得修改源码、测试、脚本、设计、状态、`CURRENT_BATCH.md`、`DEFERRED_TEST_PLAN.md`；
- 提交前 staged path 必须只有一个且位于允许目录，否则 fail-closed，不得 push；
- ChatGPT 复核原始报告后，再把接受的结果整理到开发分支 `docs/verification/results/` 并更新验证总账。

## Consequences

D-001 / D-003 的“Code Agent 远端只读”在**验证结果文件**这一窄范围内被本 ADR 覆盖；其它远端写入限制保持不变。

该机制把“测试执行者的原始证据”和“总控复核后的正式证据”分离，避免 Code Agent 直接改变被测代码或验证状态，同时减少测试报告只存在聊天/单机的丢失风险。

## Security / permission boundary

这是项目治理和分支/路径隔离规则，不等于 GitHub credential 原生按目录 ACL。若 Code Agent 的 token 本身拥有仓库级 Contents write，GitHub 凭据层仍可能允许更宽的写入。若未来要求物理强制的最小权限，应使用专用 GitHub App、独立结果仓库或可强制路径限制的仓库规则；在此之前，`verification-results` + 固定目录 + staged-path fail-closed 检查是当前执行边界。

## Execution entry

用户以后只需向 Code Agent 发送：

```text
VERIFY_CURRENT_BATCH
```

命令语义见 `docs/verification/CODE_AGENT_COMMANDS.md`，测试内容见 `docs/verification/CURRENT_BATCH.md`。
