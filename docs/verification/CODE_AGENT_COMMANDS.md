# Code Agent Verification Commands

> 状态：CURRENT
> 目的：用户只转发一个短命令；所有测试细节都由仓库文件提供。

## VERIFY_CURRENT_BATCH

收到以下精确命令时：

```text
VERIFY_CURRENT_BATCH
```

Code Agent 必须执行下面流程，不要求用户再粘贴测试细节：

1. 读取：
   - `docs/verification/TEST_EXECUTION_PROTOCOL.md`
   - `docs/verification/CURRENT_BATCH.md`
2. 若 `CURRENT_BATCH.md` 状态不是 `READY`：
   - 不自行挑选测试；
   - 不修改任何文件；
   - 返回 `NO_READY_BATCH`。
3. 若状态为 `READY`：
   - fetch 远端；
   - checkout 文件指定的精确被测 SHA；
   - 验证 `git rev-parse HEAD` 精确匹配；
   - 记录测试前 `git status --short`；
   - 一次执行 `CURRENT_BATCH.md` 中列出的全部命令、定向验证与完整门禁；
   - 不自行拆轮、不修代码、不改测试。
4. 把完整结果写入：

```text
.verify/CURRENT_BATCH_RESULT.md
```

5. 再把完全相同的结果发布到 GitHub 专用结果分支：

```text
branch: verification-results
path: docs/verification/agent-results/<Batch-ID>-RESULT.md
```

6. GitHub 发布前必须 fail-closed 检查：
   - 不在被测 detached 工作区直接提交结果；使用单独 worktree/临时 clone/等价隔离方式操作 `verification-results`；
   - staged 文件只能有一个；
   - 唯一路径必须匹配 `docs/verification/agent-results/*-RESULT.md`；
   - 若 staged 中出现其他文件，立即取消提交并停止；
   - 禁止 push `main`、`feature/v3-development` 或其他分支。
7. 结果提交成功后，在本地 `.verify/CURRENT_BATCH_RESULT.md` 末尾补充：
   - GitHub result branch
   - GitHub result path
   - GitHub result commit SHA
8. 最终聊天回复只需：

```text
Batch: <Batch-ID>
Overall: <PASS | PASS_EXCEPT_KNOWN_ENV | FAIL_NEW_REGRESSION | PARTIAL>
Local result: .verify/CURRENT_BATCH_RESULT.md
GitHub result: verification-results:<path>
Result commit: <sha>
```

## 权限边界

`VERIFY_CURRENT_BATCH` 不授权任何开发行为。Code Agent 仍然不得修改/提交源码、测试代码、脚本、设计文档、状态文档、`CURRENT_BATCH.md` 或 `DEFERRED_TEST_PLAN.md`。

GitHub 唯一写例外是：`verification-results` 分支下的 `docs/verification/agent-results/*-RESULT.md`。
