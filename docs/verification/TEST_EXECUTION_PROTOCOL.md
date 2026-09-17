# Test Execution Protocol

> 状态：CURRENT
> 目标：测试计划与测试结果都必须落盘，避免关键验证信息只存在于聊天窗口。
> 角色：ChatGPT = 总控与代码/设计主写作者；Code Agent = Execution Tester；Codex Work = 仅重要节点的对抗验证者。

## 1. 固定文件与分支

- `docs/verification/CURRENT_BATCH.md`：当前唯一批量测试任务入口。ChatGPT 在需要验证时写入精确 SHA、测试范围、命令、预期与判定规则。
- `.verify/CURRENT_BATCH_RESULT.md`：Code Agent 本地执行结果文件。Code Agent 每批允许创建/覆盖这个文件。
- `verification-results`：Code Agent 唯一允许执行 GitHub 写入的专用分支。
- `docs/verification/agent-results/<batch-id>-RESULT.md`：Code Agent 在 `verification-results` 分支上的唯一允许写入路径。
- `docs/verification/results/`：ChatGPT 复核后写入 `feature/v3-development` 的永久结果归档。
- `docs/verification/DEFERRED_TEST_PLAN.md`：工作项状态总账，不再承担每次测试的完整命令说明。
- `docs/verification/CODE_AGENT_COMMANDS.md`：用户转发给 Code Agent 的短命令定义。

## 2. 标准流程

1. ChatGPT 连续开发多个彼此独立、接口稳定的工作项。
2. 达到批量测试点后，ChatGPT 把一次完整批次写入 `CURRENT_BATCH.md` 并提交到 `feature/v3-development`。
3. ChatGPT 在聊天中只给用户一条短命令：`VERIFY_CURRENT_BATCH`。
4. Code Agent 收到该命令后读取 `CODE_AGENT_COMMANDS.md`、`CURRENT_BATCH.md` 与本协议，并一次执行整个批次，不自行拆轮。
5. Code Agent 测试时：
   - checkout `CURRENT_BATCH.md` 指定的精确 SHA；
   - 不修改任何源码、测试、docs、脚本或被测分支 Git 历史；
   - 把完整报告写到 `.verify/CURRENT_BATCH_RESULT.md`。
6. Code Agent 测完后还必须把同一份报告写入 GitHub：
   - 仅允许分支 `verification-results`；
   - 仅允许路径 `docs/verification/agent-results/<batch-id>-RESULT.md`；
   - 禁止向 `main`、`feature/v3-development` 或任何其他分支 push；
   - 禁止提交任何其他路径；
   - 提交前必须检查 staged path，若存在允许目录以外的文件立即停止且不得 push。
7. 用户只需要把 Code Agent 返回的 `batch-id + result commit SHA + Overall` 发给 ChatGPT；完整报告已经同时存在本地和 GitHub。
8. ChatGPT 复核 `verification-results` 上的结果后：
   - 复制/整理为 `docs/verification/results/<batch-id>-RESULT.md` 永久归档；
   - 更新 `DEFERRED_TEST_PLAN.md`；
   - 若通过继续开发；若失败只修真正阻塞的缺陷。

## 3. Code Agent GitHub 写权限边界

Code Agent 的项目角色权限定义为：

- 读：整个仓库；
- 本地写：仅 `.verify/CURRENT_BATCH_RESULT.md` 与测试工具自然产生的 gitignored 构建/日志文件；
- GitHub 写：仅 `verification-results` 分支下 `docs/verification/agent-results/*-RESULT.md`；
- 禁止：源码、测试代码、脚本、设计文档、状态文档、`CURRENT_BATCH.md`、`DEFERRED_TEST_PLAN.md`、`main`、`feature/v3-development`。

提交前必须执行等价检查：

```text
git diff --cached --name-only
```

输出必须只有一个文件，且匹配：

```text
docs/verification/agent-results/*-RESULT.md
```

否则不得 commit/push。

> 说明：这是项目治理与专用分支/路径隔离。若 Code Agent 使用的 GitHub credential 本身仍拥有仓库级 Contents write，GitHub token 层面并不会自动获得“按目录写权限”。要做到凭据级强制隔离，需要另配专用 GitHub App/细粒度服务或仓库 ruleset。当前流程通过专用分支、固定目录和提交前 fail-closed 检查降低误写风险，不能把它描述成 GitHub 凭据层面的物理 ACL。

## 4. 为什么采用批量而不是每个小改动都停一次

默认策略是批量延迟验证，不是“改一项就停一项”。只有以下情况才提前测试：

1. 后续实现直接依赖某项运行结果，错误会沿依赖链放大；
2. 数据库迁移、状态机、权限/认证、AI SQL 安全、正式契约等高风险公共边界；
3. 基础测试已经无法建立基本正确性；
4. 后续修改会破坏当前故障现场或让失败难以定位；
5. 即将进入真实 MySQL/Spark/Hive/Flume/HTTP E2E、合并 main 或阶段验收。

除此之外，前端展示、独立 helper、加性 DTO、互不依赖的常规 bugfix 等都应先累计成一个功能簇，再一次性测试。

## 5. 每批测试范围

不是每次都无差别跑仓库全部测试，而是“定向测试 + 受影响域完整门禁”：

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
GitHub result path:
GitHub result commit:
```

要求：所有数量来自实际执行；必须区分 NEW REGRESSION 与已登记环境红；不能因为总套件绿就把未实际覆盖的 E2E 写成 PASS；不得修代码。

## 7. Codex Work 调用原则

普通批次不调用。仅在核心安全、重大 Flyway/数据模型、权限认证、AI SQL 安全、关键状态机/幂等恢复、正式阶段验收、合并 main 前的高价值节点按需调用。
