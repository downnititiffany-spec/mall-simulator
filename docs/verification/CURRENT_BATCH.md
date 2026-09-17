# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW`
- **Tested commit**：`2cf150b82168904fe6500089cf699d986c374514`
- **Branch context**：`feature/v3-development`
- **Scope**：Batch I 失败复测：修正 pipeline identity 测试守卫误计 `//` 注释，并复验 S3-75 / S3-76、关键回归和 Web full gate。
- **Risk**：低；相对 Batch I 生产代码未变，仅测试守卫修正。
- **Permanent plan**：`docs/verification/batches/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`

## Execution

完整命令、预期、回归范围、边界与 PASS 规则全部以永久计划为准。Code Agent 必须完整读取并执行该计划，不得自行拆轮、修代码或改测试。

核心命令顺序：

```powershell
git fetch origin
git checkout --detach 2cf150b82168904fe6500089cf699d986c374514
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineOperationIdentity.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/pipelineRetryHandling.test.js tests/pipelineLocalBusinessDate.test.js tests/rfmMatrixOwnership.test.js
npm run verify
```

预期：S3-75 4/4；S3-76 4/4；关键回归 13/13；Web full gate 预计 247 tests（以实际为准）且 Vite production build PASS。

## Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
```

Code Agent 只能向上述结果分支/结果路径写本批结果；不得修改被测分支、源码、测试、脚本、docs 或测试计划。

完成后用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```
