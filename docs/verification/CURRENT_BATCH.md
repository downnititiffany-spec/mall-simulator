# Current Verification Batch

> 状态：CLOSED
> 当前没有待执行批次。
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后读取 `docs/verification/CODE_AGENT_COMMANDS.md` 与本文件；若状态不是 `READY`，不得自行挑测试运行。

## Last completed batch

- Batch ID：`BATCH-E-WEB-DECISION-INPUT`
- Tested commit：`c42ee34d4bfb2364c646f060f675af67974a6a6c`
- Scope：V-013 / V-014 + V-011 / V-012 回归
- Result：PASS
- Web：212/212 PASS，Vite production build PASS
- Permanent result：`docs/verification/results/BATCH-E-WEB-DECISION-INPUT-RESULT.md`

## When this file becomes READY

ChatGPT 会在这里一次性写入：

- Batch ID
- 精确 commit SHA
- 必须执行的全部命令
- 受影响域的完整门禁
- 重点工作项及预期
- 已知环境红
- 本地结果文件路径
- GitHub 结果分支与结果路径
- Overall 判定规则

Code Agent 必须一次执行完整批次，并同时保存：

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/agent-results/<Batch-ID>-RESULT.md
```

Code Agent 不得修改被测分支；GitHub 唯一写例外遵循 `TEST_EXECUTION_PROTOCOL.md`。
