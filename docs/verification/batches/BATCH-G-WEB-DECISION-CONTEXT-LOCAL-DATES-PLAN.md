# BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES — Verification Plan

> 状态：READY
> Executor：Code Agent (Execution Tester)
> Tested commit：`4366bcb7dcc6347657744e115f0cc0706aa6baea`
> Branch context：`feature/v3-development`
> Scope：S3-69/S3-70 决策执行上下文展示 + S3-71 本地日历日期默认值统一，以及相关 Web 回归。
> Risk：低—中；仅 Web 映射、展示和日期默认值 helper，不改后端 API、数据库、状态机、权限或 AI SQL。

## 1. Checkout discipline

```powershell
git fetch origin
git checkout --detach 4366bcb7dcc6347657744e115f0cc0706aa6baea
git rev-parse HEAD
git status --short
```

要求：

- HEAD 精确等于 `4366bcb7dcc6347657744e115f0cc0706aa6baea`；
- tracked workspace 测试前后必须 clean；
- 不修改源码、测试、docs、脚本；
- gitignored build artifacts 允许由测试自然产生。

## 2. 定向验证 — S3-69 / S3-70：决策执行上下文

```powershell
cd web
node --test tests/decisionExecutionContextDisplay.test.js
```

预期：`4/4 PASS`。

必须确认：

1. `decisionRows` 原样搬运 `baselineSnapshotId`、`definitionVersion`、`dueDate`；
2. 缺失字段显示 `—`，不猜测基线快照、版本或截止日期；
3. 决策表区分「建议快照」与「基线快照」，不能再声称批准基线记录在 suggestionSnapshotId；
4. 页面展示后端批准时锁定的 `baselineSnapshotId` 与 `definitionVersion`；
5. 页面展示人工批准时采集的 `dueDate`；
6. CSV 的 `COLUMNS.decisions` 同步包含建议快照、基线快照、口径版本、截止日期；
7. 不重算指标、不修改后端数据语义。

缺陷背景：后端 `DecisionTask` 明确把 `suggestionSnapshotId`（AI 建议来源）与 `baselineSnapshotId`（批准时锁定的评价基线）分开，并保存 `definitionVersion` / `dueDate`；旧 Web 丢弃后三者且说明文案错误地把建议快照称为批准基线。

## 3. 定向验证 — S3-71：本地日历日期默认值

```powershell
node --test tests/pipelineLocalBusinessDate.test.js
```

预期：`5/5 PASS`。

必须确认：

1. `localIsoDay` 通过本地 `getFullYear/getMonth/getDate` 生成 `YYYY-MM-DD`；
2. `localIsoDayOffset` 按本地日历加减天并正确跨月；
3. Pipeline 默认业务日使用 `localIsoDay()`，不得使用 `toISOString().slice(0, 10)`；
4. Behavior / Sales / Overview 的默认近 7 天日期范围统一使用 `localIsoDayOffset(-6/0)`；
5. 上述四个页面不得再用 UTC instant 截断生成 HTML date 默认值；
6. Pipeline 仍把用户确认的业务日原样组装为 `<date>T00:00:00`，不引入 UTC 转换。

缺陷背景：HTML `<input type="date">` 表示用户本地日历日；`new Date().toISOString().slice(0,10)` 使用 UTC，在 UTC± 时区接近午夜时可能默认成前一天/后一天。

## 4. 决策中心回归

```powershell
node --test tests/decisionRowIdentity.test.js tests/decisionBaselineDisplay.test.js tests/decisionApprovalInput.test.js tests/decisionSubmitOwner.test.js tests/decisionCancelWiring.test.js tests/decisionRequiredReason.test.js
```

预期：

- decisionRowIdentity：3/3；
- decisionBaselineDisplay：2/2；
- decisionApprovalInput：5/5；
- decisionSubmitOwner：4/4；
- decisionCancelWiring：3/3；
- decisionRequiredReason：4/4；
- 合计：21/21 PASS。

不得回归：真实 id、基线单次格式化、submit owner、approve owner/dueDate、reject/cancel reason、业务 cancel 与请求取消职责分离。

## 5. Web 完整门禁

```powershell
npm run verify
```

上一已验证 Batch F：217 tests。

本批新增测试：

- `decisionExecutionContextDisplay.test.js`：4；
- `pipelineLocalBusinessDate.test.js`：原 3 条扩展为 5 条，相对 Batch F 净新增 5（Batch F 时该文件尚不存在，因此本批总新增仍按文件当前 5 条计）；

因此预计总数：`217 + 4 + 5 = 226`。

226 只是推导值，必须以实际 Node test 汇总为准。

要求：

- Node tests 全部 PASS；
- Failed / Cancelled = 0；
- Vite production build PASS；
- 任一新增 failure = `FAIL_NEW_REGRESSION`。

本批无 Java 生产代码变更，不运行 default/spark/isolated/3307；不运行真实浏览器 E2E。

## 6. 未覆盖边界

本批 PASS 不能宣称以下内容已验收：

- 真实浏览器渲染与 HTML date 控件跨时区交互；
- 真实 HTTP 决策状态机；
- 决策基线快照/截止日期真实数据库落库；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E。

## 7. 结果保存

完整结果必须同时保存：

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github branch: verification-results
github path: docs/verification/results/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-RESULT.md
```

GitHub 写入必须使用隔离 worktree/temp clone；唯一 staged 文件必须是上述结果文件；禁止 push `main` 或 `feature/v3-development`。

## 8. Result fields

至少包含：

```text
Batch ID:
Tested commit:
Git status before/after:
Environment: Node/npm
S3-69/S3-70: counts + result
S3-71: counts + result
Decision regression: counts + result
Web verify: total/passed/failed/cancelled/skipped + Vite build + exit
Expected 226 confirmed: YES/NO + actual total
New failures:
Unverified runtime areas:
Local result path:
GitHub result branch/path/commit:
Overall: PASS | FAIL_NEW_REGRESSION | PARTIAL
```

## 9. PASS rule

`PASS` 仅当：

- 精确 SHA 正确；
- S3-69/S3-70 4/4；
- S3-71 5/5；
- 决策回归 21/21；
- `npm run verify` 全绿且 Vite build PASS；
- 无新增回归；
- 结果同时落本地与 GitHub 结果文件。
