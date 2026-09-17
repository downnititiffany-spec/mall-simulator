# Current Verification Batch

> 状态：READY
> Code Agent 收到 `VERIFY_CURRENT_BATCH` 后，必须先读取 `docs/verification/CODE_AGENT_COMMANDS.md` 与本文件，并一次执行完整批次。

## Batch

- **Batch ID**：`BATCH-F-WEB-DECISION-IDENTITY-DISPLAY`
- **Tested commit**：`1671d3a8b09c296d70e1dfb74098a3cb1132b977`
- **Branch context**：`feature/v3-development`
- **Scope**：S3-67 决策行 identity 保留 + S3-68 决策基线单次格式化，以及决策中心既有交互回归
- **Risk**：低—中；仅 Web 映射/展示链，不改后端 API、状态机、数据库、权限或 AI SQL。

## 1. Checkout discipline

执行前：

```powershell
git fetch origin
git checkout --detach 1671d3a8b09c296d70e1dfb74098a3cb1132b977
git rev-parse HEAD
git status --short
```

要求：

- `HEAD` 必须精确等于 `1671d3a8b09c296d70e1dfb74098a3cb1132b977`；
- 测试开始前 tracked workspace 必须 clean；
- 不修改源码、测试、docs、脚本；
- 测试运行产生的 ignored build artifacts 允许存在。

## 2. 定向验证 — S3-67 / V-015

执行：

```powershell
cd web
node --test tests/decisionRowIdentity.test.js
```

预期：`3/3 PASS`。

必须确认：

1. `decisionRows` 保留后端 `id`；
2. 数值 id 保持数值，不变成字符串；
3. null/缺失 id 保持 `null`，不生成 `—`、0 或其它伪造 identity；
4. 新增 identity 不改变 decisionNo、baselineValue、targetValue、snapshot、owner、status 的既有映射；
5. `Decisions.vue` 的 `:key="d.id"`、`evaluations[d.id]` 和所有 `decisionAction(d.id, ...)` 现在都能消费被保留的真实 id。

缺陷背景：此前 `decisionRows()` 丢弃后端实体 `id`，而决策页面所有操作和评价索引都使用映射后的 `d.id`，会形成 `/decisions/undefined/...` 与评价无法按行命中的风险。

## 3. 定向验证 — S3-68 / V-016

执行：

```powershell
node --test tests/decisionBaselineDisplay.test.js
```

预期：`2/2 PASS`。

必须确认：

1. `decisionRows([{ baselineValue: 2042 }])` 产生 `2,042.00`；
2. `Decisions.vue` 直接展示 `d.baselineValue`；
3. 页面不得再次执行 `formatNumber(d.baselineValue)`；
4. `Decisions.vue` 不再保留仅用于该重复格式化的 `formatNumber` import。

缺陷背景：`decisionRows()` 已执行数值格式化；旧页面再次 `formatNumber('2,042.00')` 时，`Number('2,042.00')` 为 `NaN`，千位以上基线会错误退化成占位符 `—`。

## 4. 决策中心回归

单独确认以下既有测试仍通过：

```powershell
node --test tests/decisionApprovalInput.test.js tests/decisionSubmitOwner.test.js tests/decisionCancelWiring.test.js tests/decisionRequiredReason.test.js
```

预期：

- `decisionApprovalInput.test.js`：5/5 PASS；
- `decisionSubmitOwner.test.js`：4/4 PASS；
- `decisionCancelWiring.test.js`：3/3 PASS；
- `decisionRequiredReason.test.js`：4/4 PASS；
- 合计 16/16 PASS。

不得回归：submit owner、approve owner/dueDate、reject/cancel reason、业务 cancel 与 `useAnalysis.cancel` 职责分离。

## 5. Web 完整门禁

执行：

```powershell
npm run verify
```

上一已验证批次为 212 tests。本批新增：

- `decisionRowIdentity.test.js`：3；
- `decisionBaselineDisplay.test.js`：2。

因此**预计**总数 217，但最终必须以实际 Node test 汇总为准，不得用推导值代替实测。

要求：

- Node tests 全部 PASS；
- Failed/Cancelled = 0；
- Vite production build PASS；
- 任何新增 failure 都算 `NEW REGRESSION`。

本批只有 Web 改动，因此**不要**额外运行 Java default、spark、isolated、3307 或真实浏览器 E2E。

## 6. 结果保存

完整报告必须同时保存：

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/agent-results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md
```

GitHub 写入必须遵守 `CODE_AGENT_COMMANDS.md`：使用隔离 worktree/temp clone；只允许结果分支和上述单一结果文件；禁止 push `feature/v3-development` / `main`。

## 7. Result report fields

结果至少包含：

```text
Batch ID:
Tested commit:
Git status before/after:
Environment: Node/npm
S3-67 / V-015: counts + result
S3-68 / V-016: counts + result
Decision regression: counts + result
Web verify: total/passed/failed/cancelled/skipped + Vite build + exit
Expected 217 confirmed: YES/NO + actual total
Known environmental failures: N/A for this Web-only batch
New failures:
Unverified runtime areas: real browser / real HTTP / backend state machine / DB
Local result path:
GitHub result branch/path/commit:
Overall: PASS | FAIL_NEW_REGRESSION | PARTIAL
```

## 8. Pass rule

`PASS` 仅当：

- 精确 SHA 正确；
- S3-67 3/3；
- S3-68 2/2；
- 决策回归 16/16；
- `npm run verify` 全绿且 Vite build PASS；
- 无新增回归；
- 结果已同时落本地与 `verification-results` GitHub 结果文件。

真实浏览器点击、真实 HTTP `/decisions/{id}/...`、后端状态机与 DB 落库本批未覆盖，不能因为本批 PASS 宣称这些 E2E 已验收。
