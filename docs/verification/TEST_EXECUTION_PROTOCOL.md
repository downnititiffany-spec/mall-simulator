# Test Execution Protocol

> 状态：CURRENT
> 目标：测试计划与测试结果都必须落盘到 GitHub，聊天只传短命令/完成通知，避免关键验证信息丢失。
> 角色：ChatGPT = 总控与代码/设计主写作者；Code Agent = Execution Tester；Codex Work = 仅重要节点的对抗验证者。

## 1. 固定文件与目录

- `docs/verification/CURRENT_BATCH.md`：当前唯一批量测试任务入口。ChatGPT 在需要验证时写入精确 SHA、测试范围、命令、预期与判定规则。
- `docs/verification/batches/<Batch-ID>-PLAN.md`：每批测试计划的**永久快照**。ChatGPT 在把 `CURRENT_BATCH.md` 置为 `READY` 时同步创建；后续不得覆写历史计划。
- `.verify/CURRENT_BATCH_RESULT.md`：Code Agent 本地执行结果文件。
- `verification-results`：Code Agent 唯一允许执行 GitHub 写入的专用分支。
- `verification-results:docs/verification/results/<Batch-ID>-RESULT.md`：Code Agent 在 GitHub 上保存完整原始测试结果的唯一允许路径。
- `feature/v3-development:docs/verification/results/<Batch-ID>-RESULT.md`：ChatGPT 复核后保存的接受结果/摘要；可引用原始结果分支与 commit。
- `docs/verification/DEFERRED_TEST_PLAN.md`：工作项状态总账。
- `docs/verification/CODE_AGENT_COMMANDS.md`：用户转发给 Code Agent 的短命令定义。

## 2. 标准流程

1. ChatGPT 连续开发多个彼此独立、接口稳定的工作项。
2. 达到批量测试点后，ChatGPT：
   - 写完整 `CURRENT_BATCH.md`；
   - 同时把同一批测试要求冻结到 `docs/verification/batches/<Batch-ID>-PLAN.md`；
   - 提交后把 `CURRENT_BATCH.md` 置为 `READY`。
3. ChatGPT 在聊天中只给用户一条短命令：`VERIFY_CURRENT_BATCH`。
4. Code Agent 收到命令后必须先 `git fetch origin`，并从 `origin/feature/v3-development` 读取最新：
   - `CODE_AGENT_COMMANDS.md`；
   - `TEST_EXECUTION_PROTOCOL.md`；
   - `CURRENT_BATCH.md`。
   不允许依赖本地旧副本猜测命令语义。
5. 若 `CURRENT_BATCH.md` 为 `READY`，Code Agent：
   - checkout 文件指定的精确被测 SHA；
   - 验证 HEAD 精确匹配、tracked workspace clean；
   - 一次执行完整批次，不自行拆轮；
   - 不修改源码、测试、脚本、docs 或被测分支 Git 历史；
   - 把完整报告写到 `.verify/CURRENT_BATCH_RESULT.md`。
6. Code Agent 同时把**完整原始报告**写入 GitHub：
   - 仅分支 `verification-results`；
   - 仅路径 `docs/verification/results/<Batch-ID>-RESULT.md`；
   - 使用独立 worktree/临时 clone/等价隔离方式；
   - staged 必须只有这一个允许文件，否则 fail-closed 停止；
   - 禁止 push `main`、`feature/v3-development` 或其他分支。
7. 测试结束后，用户不需要复制完整报告。只需在总控聊天中说：

```text
测试完成，测试结果已写入
```

8. ChatGPT 根据 `CURRENT_BATCH.md` 的 Batch ID 直接读取 `verification-results:docs/verification/results/<Batch-ID>-RESULT.md`，复核后：
   - 在 `feature/v3-development:docs/verification/results/<Batch-ID>-RESULT.md` 保存接受结果/摘要；
   - 更新 `DEFERRED_TEST_PLAN.md`；
   - 把 `CURRENT_BATCH.md` 关闭并记录 plan/result 路径；
   - PASS 则继续开发，FAIL 则只修真正阻塞的缺陷。

因此，聊天窗口不再是测试规格或测试结果的唯一载体。

## 3. Code Agent GitHub 写权限边界

Code Agent 的项目角色权限定义为：

- 读：整个仓库；
- 本地写：仅 `.verify/CURRENT_BATCH_RESULT.md` 与测试工具自然产生的 gitignored 构建/日志文件；
- GitHub 写：仅 `verification-results` 分支下 `docs/verification/results/*-RESULT.md`；
- 禁止：源码、测试代码、脚本、设计文档、状态文档、测试计划、`CURRENT_BATCH.md`、`DEFERRED_TEST_PLAN.md`、`main`、`feature/v3-development`。

提交前必须执行等价检查：

```text
git diff --cached --name-only
```

输出必须只有一个文件，且匹配：

```text
docs/verification/results/*-RESULT.md
```

否则不得 commit/push。

> 说明：这是项目治理与专用分支/路径隔离，不是 GitHub token 层面的目录 ACL。若凭据仍有仓库级 Contents write，真正的物理目录级限制仍需要 GitHub App/细粒度服务或 ruleset 支撑。

## 4. 为什么采用批量而不是每个小改动都停一次

默认策略是批量延迟验证。只有以下情况提前测试：

1. 后续实现直接依赖某项运行结果，错误会沿依赖链放大；
2. 数据库迁移、状态机、权限/认证、AI SQL 安全、正式契约等高风险公共边界；
3. 基础测试已经无法建立基本正确性；
4. 后续修改会破坏当前故障现场或让失败难以定位；
5. 即将进入真实 MySQL/Spark/Hive/Flume/HTTP E2E、合并 main 或阶段验收。

除此之外，前端展示、独立 helper、加性 DTO、互不依赖的常规 bugfix 等应先累计成一个功能簇，再一次性测试。

## 5. 每批测试范围

采用“定向测试 + 受影响域完整门禁”，不是每次无差别跑全仓：

- 仅 Web 改动：相关定向测试 + `cd web && npm run verify`；
- analytics Java 改动：相关定向 Maven 测试 + `pwsh scripts/run-tests.ps1 -Suite default`；
- Spark 改动：相关定向测试 + `-Suite spark`；
- 隔离数据库/真实链路：对应 `isolated`/真库命令 + 必要 default 回归；
- 跨域或阶段收口：把多个相关门禁组合进同一个 `CURRENT_BATCH.md`，由 Code Agent 一次执行完。

## 6. Code Agent 结果文件最小字段

```text
Batch ID:
Tested commit:
Git status:
Environment:
Commands executed:
Per-suite counts and exit codes:
Per-item result:
Known environmental failures:
New failures:
Unverified runtime areas:
Evidence/log paths:
Overall: PASS | PASS_EXCEPT_KNOWN_ENV | FAIL_NEW_REGRESSION | PARTIAL
GitHub result branch: verification-results
GitHub result path: docs/verification/results/<Batch-ID>-RESULT.md
GitHub result commit:
```

要求：所有数量来自实际执行；必须区分 NEW REGRESSION 与已登记环境红；不能因为总套件绿就把未实际覆盖的 E2E 写成 PASS；不得修代码。

## 7. Codex Work 调用原则

普通批次不调用。仅在核心安全、重大 Flyway/数据模型、权限认证、AI SQL 安全、关键状态机/幂等恢复、正式阶段验收、合并 main 前的高价值节点按需调用。
