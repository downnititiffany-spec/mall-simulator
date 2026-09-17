# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-J-WEB-RFM-SNAPSHOT-PINNING`
- **Tested commit**：`172d80b06ac45c0c27942a4e11007916090c4254`
- **Branch context**：`feature/v3-development`
- **Scope**：S3-77 RFM 跨接口 snapshot 一致性：`/analysis/rfm` 先固定主快照，`/analysis/users` 复用同一 snapshotId；主响应缺 snapshotId 时跳过第二请求，避免混快照。
- **Risk**：低—中；仅 Web 请求组装与降级展示，不改后端 API、数据库、状态机、权限或 AI SQL。
- **Permanent plan**：`docs/verification/batches/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-PLAN.md`

## Execution

完整命令、预期、回归范围、边界与 PASS 规则全部以永久计划为准。Code Agent 必须完整读取并执行该计划，不得自行拆轮、修代码或改测试。

核心命令顺序：

```powershell
git fetch origin
git checkout --detach 172d80b06ac45c0c27942a4e11007916090c4254
git rev-parse HEAD
git status --short
cd web
node --test tests/rfmSnapshotPinning.test.js
node --test tests/rfmMatrixOwnership.test.js tests/rfmObservationWindow.test.js
npm run verify
```

预期：S3-77 4/4；RFM 关键回归 8/8；Web full gate 预计约 251 tests（实际数量以 runner 为准）且 Vite production build 必须实际执行并 PASS。

## Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
```

Code Agent 只能向上述结果分支/结果路径写本批结果；不得修改被测分支、源码、测试、脚本、docs 或测试计划。

完成后用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```
