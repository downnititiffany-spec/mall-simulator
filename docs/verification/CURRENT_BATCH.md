# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后必须先同步并读取最新 `CODE_AGENT_COMMANDS.md`、`TEST_EXECUTION_PROTOCOL.md` 与本文件，然后一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES`
- **Tested commit**：`4366bcb7dcc6347657744e115f0cc0706aa6baea`
- **Branch context**：`feature/v3-development`
- **Scope**：S3-69/S3-70 决策执行上下文展示 + S3-71 本地日历日期默认值统一，以及决策中心回归。
- **Risk**：低—中；仅 Web 映射、展示与日期 helper，不改后端 API、数据库、状态机、权限或 AI SQL。
- **Permanent plan**：`docs/verification/batches/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-PLAN.md`

## 1. Checkout discipline

```powershell
git fetch origin
git checkout --detach 4366bcb7dcc6347657744e115f0cc0706aa6baea
git rev-parse HEAD
git status --short
```

要求 HEAD 精确匹配；测试前后 tracked workspace clean；不得修改源码、测试、docs 或脚本。

## 2. S3-69 / S3-70 — 决策执行上下文

```powershell
cd web
node --test tests/decisionExecutionContextDisplay.test.js
```

预期：`4/4 PASS`。

必须确认：

- `decisionRows` 搬运 `baselineSnapshotId`、`definitionVersion`、`dueDate`，缺失显示 `—`；
- 页面区分「建议快照」与「基线快照」，不再把 suggestionSnapshotId 误称为批准基线；
- 页面显示批准时锁定的基线快照、口径版本与人工截止日期；
- `COLUMNS.decisions` / CSV 同步包含这些字段；
- 不猜值、不重算。

## 3. S3-71 — 本地日历日期默认值

```powershell
node --test tests/pipelineLocalBusinessDate.test.js
```

预期：`5/5 PASS`。

必须确认：

- `localIsoDay` 使用本地年/月/日；
- `localIsoDayOffset` 本地日历加减天并正确跨月；
- Pipeline 使用 `localIsoDay()`；
- Behavior / Sales / Overview 使用 `localIsoDayOffset(-6/0)`；
- 这四个页面不再用 `toISOString().slice(0, 10)` 生成 HTML date 默认值；
- Pipeline 仍将用户确认日期组为 `<date>T00:00:00`。

## 4. 决策中心回归

```powershell
node --test tests/decisionRowIdentity.test.js tests/decisionBaselineDisplay.test.js tests/decisionApprovalInput.test.js tests/decisionSubmitOwner.test.js tests/decisionCancelWiring.test.js tests/decisionRequiredReason.test.js
```

预期：21/21 PASS：

- decisionRowIdentity 3/3
- decisionBaselineDisplay 2/2
- decisionApprovalInput 5/5
- decisionSubmitOwner 4/4
- decisionCancelWiring 3/3
- decisionRequiredReason 4/4

## 5. Web 完整门禁

```powershell
npm run verify
```

上一批 Batch F = 217 tests；本批新增 `decisionExecutionContextDisplay` 4 条 + `pipelineLocalBusinessDate` 5 条，因此预计总数 **226**，但必须以实测为准。

要求 Node tests 全绿、Failed/Cancelled=0、Vite production build PASS。任一新增 failure = `FAIL_NEW_REGRESSION`。

本批不要运行 Java default/spark/isolated/3307 或真实浏览器 E2E。

## 6. 未覆盖边界

本批 PASS 不代表以下已验收：真实浏览器 date/prompt、真实 HTTP 决策状态机、决策 DB 落库、Java/Spark/Hive/Flume E2E。

## 7. 结果保存

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github branch: verification-results
github path: docs/verification/results/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-RESULT.md
```

GitHub 写入必须隔离操作，唯一 staged 文件只能是上述结果文件；禁止 push `main`、`feature/v3-development` 或其它开发分支。

## 8. Result fields

至少包含：Batch ID、Tested commit、git status before/after、Node/npm、两个定向测试 counts/result、决策回归 counts/result、Web verify 汇总/build/exit、Expected 226 YES/NO、New failures、Unverified runtime areas、本地/远端结果路径与 commit、Overall。

## 9. PASS rule

`PASS` 仅当：精确 SHA 正确；S3-69/S3-70 4/4；S3-71 5/5；决策回归 21/21；`npm run verify` 全绿且 build PASS；无新增回归；结果同时写本地与 GitHub。
