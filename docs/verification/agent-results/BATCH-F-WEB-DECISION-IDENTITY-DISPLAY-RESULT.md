# BATCH-F-WEB-DECISION-IDENTITY-DISPLAY — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md:6`）
- Protocol read at remote ref: `origin/feature/v3-development` = `98c649b74410f7ecd4bd56fc33aee748a94424f9`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（53 行）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（84 行）
  - `docs/verification/CURRENT_BATCH.md`（111 行，状态 `READY`）
  - `docs/decisions/ADR-0002-code-agent-result-write-exception.md`（41 行）
- Batch ID: `BATCH-F-WEB-DECISION-IDENTITY-DISPLAY`
- Tested commit: `1671d3a8b09c296d70e1dfb74098a3cb1132b977`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 11:27:26 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 11:27:16 +08:00] guard decision baseline display`
  - 被测 SHA 为 `origin/feature/v3-development` 的祖先（该远端 tip 此时已前进到 `98c649b` 的开批提交；本批严格按 `CURRENT_BATCH.md` 指定的精确 SHA 执行）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空；仅 `web/dist` 等 gitignored 构建产物变化）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出 `D:\Develop_code\GraduationProject` 未触碰）

## Environment

```text
OS:      Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node:    v24.16.0
npm:     11.13.0
pwsh:    7.6.6 (PowerShell Core)
Web 依赖: web/node_modules 已存在（未重新安装）
Java/Maven: 本批未使用（CURRENT_BATCH.md 明确要求本批不运行 Java default/spark/isolated/3307/浏览器 E2E）
```

## Commands executed（含退出码）

```text
git fetch origin                                   exit=0   c42ee34..98c649b  feature/v3-development；[new branch] verification-results
git checkout --detach 1671d3a8b09c296d70e1dfb74098a3cb1132b977   exit=0
git rev-parse HEAD                                 exit=0   = 1671d3a8b09c296d70e1dfb74098a3cb1132b977
git status --short                                 exit=0   空（clean）

cd web
node --test tests/decisionRowIdentity.test.js      exit=0   3 tests / 3 pass / 0 fail
node --test tests/decisionBaselineDisplay.test.js  exit=0   2 tests / 2 pass / 0 fail
node --test tests/decisionApprovalInput.test.js tests/decisionSubmitOwner.test.js \
            tests/decisionCancelWiring.test.js tests/decisionRequiredReason.test.js
                                                   exit=0   16 tests / 16 pass / 0 fail
npm run verify                                     exit=0   217 tests / 217 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                            671 modules transformed；built in 2.72s
```

执行顺序与 `CURRENT_BATCH.md` §2–§5 完全一致，一次执行完整批次，未拆轮、未改动任何源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`，未 commit/push 开发分支。

## S3-67 / V-015 — decisionRowIdentity.test.js

```text
counts: 3 tests / 3 pass / 0 fail          expected 3/3 → 符合
result: PASS
```

```text
✔ decisionRows 保留后端数值 id 的原始类型，不转字符串也不生成展示占位符
✔ decisionRows 缺失或 null id 时保持 null，不能制造伪造身份
✔ 保留 id 不改变既有展示字段的搬运与格式化规则
```

逐点确认（源码 `web/src/utils/tables.js` + `web/src/views/Decisions.vue` 独立复核）：

1. `decisionRows` 保留后端 `id`：新增映射 `id: d.id === null || d.id === undefined ? null : d.id` — 确认。
2. 数值 id 保持数值，不转字符串：等价逻辑独立复算 `decisionRows([{id:42}])` → `{"id":42,"type":"number"}` — 确认。
3. null/缺失 id 保持 `null`：`[{id:null}]` → `null`；`[{}]` → `null`；未生成 `—` / 0 / 其他伪造身份 — 确认（同一次独立复算）。
4. 新增 identity 不改变既有映射：测试断言 `decisionNo='D-007'`、`baselineValue='0.12'`、`targetValue='0.15'`、`suggestionSnapshotId='S20260901_24'`、`owner='运营-A'`、`status='PENDING_REVIEW'` 全部保持 — 确认；独立复算另证 `baselineValue` 仍为千分位文本 `'2,042.00'`。
5. 页面消费真实 id：`Decisions.vue` L43 `:key="d.id"`、L53–L57 `evaluations[d.id]`（含 `.result` / `.improvementRate` / `.baselineValue` / `.actualValue` / `.evalWindowDays`）、L169/L226/L243/L258/L273/L286 全部 `decisionAction(d.id, ...)` — 确认；本批后不再存在 `/decisions/undefined/...` 风险。

## S3-68 / V-016 — decisionBaselineDisplay.test.js

```text
counts: 2 tests / 2 pass / 0 fail          expected 2/2 → 符合
result: PASS
```

```text
✔ decisionRows 对千位以上基线只格式化一次，得到可直接展示的千分位文本
✔ Decisions 页面直接展示映射后的 baselineValue，不再次调用 formatNumber
```

逐点确认：

1. `decisionRows([{ baselineValue: 2042 }])` → `'2,042.00'`：测试断言通过；独立复算 `targetValue: 3000` → `'3,000.00'`，`baselineValue: 2042` → `'2,042.00'` — 确认。
2. `Decisions.vue` 直接展示 `d.baselineValue`：L48 `<td class="mono">{{ d.baselineValue }}</td>` — 确认。
3. 页面不再执行 `formatNumber(d.baselineValue)`：全文 `formatNumber` 出现 0 次 — 确认。
4. `formatNumber` 的 import 已移除：`from '../utils/number'` 出现 0 次（本批 diff 删除 L93 import） — 确认。
   - 缺陷机制独立复算：`Number('2,042.00')` = `NaN`，即旧页面二次格式化会使千位以上基线退化为 `—`。

## Decision regression（决策中心回归）

```text
counts: 16 tests / 16 pass / 0 fail          expected 16/16 → 符合
result: PASS
  decisionApprovalInput.test.js   5/5 PASS
  decisionSubmitOwner.test.js     4/4 PASS
  decisionCancelWiring.test.js    3/3 PASS
  decisionRequiredReason.test.js  4/4 PASS
```

未回归项逐条确认：submit owner 采集（`{ owner }`）保持；approve owner + dueDate 显式输入与 `{ owner, dueDate }` 形状保持；reject/cancel reason 采集（`requiredReason`，无硬编码默认原因）保持；业务 cancel（`cancelDecision`）与 `useAnalysis.cancel`（仅 `onUnmounted` 中止取数）职责分离保持。

## Web 完整门禁

```text
command: cd web; npm run verify            exit=0
node --test "tests/**/*.test.js":  total=217  passed=217  failed=0  cancelled=0  skipped=0  todo=0
                                   `✔` 行数=217；`✖` 行数=0
vite production build:             PASS（vite 5.4.21；671 modules transformed；built in 2.72s）
```

- Expected 217 confirmed: **YES**
- Actual total: **217**（上一已验证批次 212 + `decisionRowIdentity` 3 + `decisionBaselineDisplay` 2；以实测汇总为准，未用推导值代替）
- Known environmental failures: **N/A for this Web-only batch**（本批未运行任何 Java 门禁，故不涉及 `IngestionManifestRuntimePatrolTest` 已登记环境红）
- New failures: **无**

## Unverified runtime areas（本批未覆盖，不得据此宣称 E2E 已验收）

- 真实浏览器点击与 `window.prompt` 交互；
- 真实 HTTP `/decisions/{id}/submit|approve|reject|cancel|evaluate` 调用；
- 后端状态机流转（DRAFT → PENDING_REVIEW → …）与 `SubmitReq`/`ReasonReq` 校验；
- 数据库落库（`operation_audit_log`、决策表实际写入）；
- Java default/spark/isolated/3307 门禁与 Spark/Hive/Flume 链路。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-f-web-verify.log（23315 bytes, exit=0）
构建产物:            web/dist（gitignored，27 个产物级文件由本次 vite build 重写）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 1671d3a8…）
```

## Result files

```text
Local result path:  .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/agent-results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（`CURRENT_BATCH.md` §8 Pass rule）：

- 精确 SHA 正确：`1671d3a8b09c296d70e1dfb74098a3cb1132b977` — 满足；
- S3-67 3/3 — 满足；
- S3-68 2/2 — 满足；
- 决策回归 16/16 — 满足；
- `npm run verify` 全绿（217/217，failed/cancelled=0）且 Vite build PASS — 满足；
- 无新增回归 — 满足；
- 结果同时落本地与 `verification-results` GitHub 结果文件 — 见下段。

<!-- GITHUB-PUBLISH -->
