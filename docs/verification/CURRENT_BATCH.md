# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING`
- **Tested commit**：`30d5713edae8c90e8849ea51cccaa269d80d3f00`
- **Branch context**：`feature/v3-development`
- **Scope**：Batch J 失败复测：保留 S3-77 RFM snapshot pinning，同时恢复 Batch I 已验收的 S3-76 观察期具名派生/CSV 合同。
- **Risk**：低；相对 Batch J 仅调整 `Rfm.vue` 的观察期派生写法，不改变后端 API、数据库、状态机、权限或 AI SQL。
- **Permanent plan**：`docs/verification/batches/BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING-PLAN.md`

## Execution

完整命令、预期、语义复核与 PASS 规则全部以永久计划为准。Code Agent 必须完整读取并执行该计划，不得自行拆轮、修代码或改测试。

核心命令顺序：

```powershell
git fetch origin
git checkout --detach 30d5713edae8c90e8849ea51cccaa269d80d3f00
git rev-parse HEAD
git status --short
cd web
node --test tests/rfmSnapshotPinning.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmMatrixOwnership.test.js
npm run verify
```

预期：S3-77 4/4；S3-76 4/4；RFM matrix 4/4；Web full gate 全绿，且 Vite production build 必须实际执行并 PASS。

## Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
```

Code Agent 只能向上述结果分支/结果路径写本批结果；不得修改被测分支、源码、测试、脚本、docs 或测试计划。

完成后用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```
