# Current Verification Batch

> 状态：CLOSED
> 当前没有待执行批次。
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`；若状态不是 `READY`，返回 `NO_READY_BATCH`，不得自行挑测试运行。

## Last completed batch

- Batch ID：`BATCH-F-WEB-DECISION-IDENTITY-DISPLAY`
- Tested commit：`1671d3a8b09c296d70e1dfb74098a3cb1132b977`
- Scope：S3-67 / V-015 + S3-68 / V-016 + 决策中心回归
- Result：PASS
- Web：217/217 PASS，Vite production build PASS
- Permanent plan：`docs/verification/batches/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-PLAN.md`
- Accepted result：`docs/verification/results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md`
- Raw Code Agent result：`verification-results:docs/verification/agent-results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md`
- Raw result commit：`9e10e4e47a1990029b69842f915a91ecce23cdb3`

## When this file becomes READY

ChatGPT 会一次性完成：

1. 把完整测试要求写入本文件；
2. 同时冻结永久计划：

```text
docs/verification/batches/<Batch-ID>-PLAN.md
```

3. 写明：
   - Batch ID
   - 精确 commit SHA
   - 必须执行的全部命令
   - 受影响域完整门禁
   - 重点工作项及预期
   - 已知环境红
   - 本地结果文件路径
   - GitHub 结果路径
   - Overall 判定规则

Code Agent 必须一次执行完整批次，同时保存：

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/<Batch-ID>-RESULT.md
```

Code Agent 不得修改被测分支、源码、测试、脚本、docs 或测试计划；GitHub 唯一写例外遵循 `TEST_EXECUTION_PROTOCOL.md`。

测试完成后，用户只需在总控聊天中说：

```text
测试完成，测试结果已写入
```

ChatGPT 会直接读取 GitHub 结果、复核、归档并继续推进，不再要求用户复制完整测试报告。
