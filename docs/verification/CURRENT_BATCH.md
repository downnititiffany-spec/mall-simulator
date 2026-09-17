# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW`
- **Tested commit**：`b5e4972bdb272ca476be20b1638b0c78d905438b`
- **Branch context**：`feature/v3-development`
- **Scope**：S3-75 人工流水线操作 identity 一致性 + S3-76 RFM 观察窗口展示，以及相关 Web 回归。
- **Risk**：低—中；仅 Web 请求组装/展示/导出，不改后端 API、数据库、状态机、权限或 AI SQL。
- **Permanent plan**：`docs/verification/batches/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`

## Execution

完整命令、预期、回归范围、边界与 PASS 规则全部以永久计划为准。Code Agent 必须完整读取并执行该计划，不得自行拆轮、修代码或改测试。

核心命令顺序：

```powershell
git fetch origin
git checkout --detach b5e4972bdb272ca476be20b1638b0c78d905438b
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineOperationIdentity.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/pipelineRetryHandling.test.js tests/pipelineLocalBusinessDate.test.js tests/rfmMatrixOwnership.test.js
npm run verify
```

预期定向：

- S3-75：4/4 PASS；
- S3-76：4/4 PASS；
- 关键回归：13/13 PASS；
- Web full gate：预计 247 tests，实际数量以运行结果为准；Failed/Cancelled=0；Vite build PASS。

本批不运行 Java default/spark/isolated/3307，也不运行真实浏览器 E2E。

## Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
```

Code Agent 只能向上述结果分支/结果路径写本批结果；不得修改被测分支、源码、测试、脚本、docs 或测试计划。

完成后用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```
