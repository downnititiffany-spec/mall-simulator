# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-H-WEB-PIPELINE-PRODUCT-RFM`
- **Tested commit**：`20db9072c37e20ebecf6648f55d002d36aa452b0`
- **Branch context**：`feature/v3-development`
- **Scope**：S3-72 Pipeline retry 失败处理 + S3-73 商品页服务端分页/排序 + S3-74 RFM matrix 类目唯一属主，以及相关 Web 回归。
- **Risk**：低—中；仅 Web 交互/展示/请求参数，不改后端 API、数据库、状态机、权限或 AI SQL。
- **Permanent plan**：`docs/verification/batches/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-PLAN.md`

## Execution

完整命令、预期、回归范围、边界与 PASS 规则全部以永久计划为准。Code Agent 必须完整读取并执行该计划，不得自行拆轮、修代码或改测试。

核心命令顺序：

```powershell
git fetch origin
git checkout --detach 20db9072c37e20ebecf6648f55d002d36aa452b0
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineRetryHandling.test.js
node --test tests/productServerPagination.test.js
node --test tests/rfmMatrixOwnership.test.js
node --test tests/pipelineLocalBusinessDate.test.js tests/decisionExecutionContextDisplay.test.js
npm run verify
```

预期定向：

- S3-72：4/4 PASS；
- S3-73：5/5 PASS；
- S3-74：4/4 PASS；
- 关键回归：9/9 PASS；
- Web full gate：预计 239 tests，实际数量以运行结果为准；Failed/Cancelled=0；Vite build PASS。

本批不运行 Java default/spark/isolated/3307，也不运行真实浏览器 E2E。

## Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-RESULT.md
```

Code Agent 只能向上述结果分支/结果路径写本批结果；不得修改被测分支、源码、测试、脚本、docs 或测试计划。

完成后用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```
