# BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `31fbbaf92c82d896f6856311075c5e4a51b83ac7`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（未变，自 `12ceffd`）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（未变，自 `12ceffd`）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - `docs/decisions/ADR-0003-verification-artifact-persistence.md`
  - 永久计划：`docs/verification/batches/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-PLAN.md`
- Batch ID: `BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES`
- Tested commit: `4366bcb7dcc6347657744e115f0cc0706aa6baea`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 11:55:53 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 11:55:42 +08:00] guard local calendar date defaults`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Scope: S3-69/S3-70 决策执行上下文展示 + S3-71 本地日历日期默认值统一 + 决策中心回归
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空；仅 `web/dist` 等 gitignored 构建产物变化）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰）

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
pwsh: 7.6.6
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未使用（CURRENT_BATCH.md §5 明确要求不运行 Java default/spark/isolated/3307 与真实浏览器 E2E）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   12ceffd..31fbbaf  feature/v3-development
git checkout --detach 4366bcb7dcc6347657744e115f0cc0706aa6baea  exit=0
git rev-parse HEAD                                              exit=0   = 4366bcb7dcc6347657744e115f0cc0706aa6baea
git status --short                                              exit=0   空（clean）

cd web
node --test tests/decisionExecutionContextDisplay.test.js       exit=0   4 tests / 4 pass / 0 fail
node --test tests/pipelineLocalBusinessDate.test.js             exit=0   5 tests / 5 pass / 0 fail
node --test tests/decisionRowIdentity.test.js tests/decisionBaselineDisplay.test.js \
            tests/decisionApprovalInput.test.js tests/decisionSubmitOwner.test.js \
            tests/decisionCancelWiring.test.js tests/decisionRequiredReason.test.js
                                                                exit=0   21 tests / 21 pass / 0 fail
npm run verify                                                  exit=0   226 tests / 226 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        672 modules transformed；built in 6.89s
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未触碰被测分支 Git 历史。

## S3-69 / S3-70 — decisionExecutionContextDisplay.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ decisionRows 搬运截止日期、基线快照与口径版本，缺失值保持显式占位
✔ 决策 CSV 列定义包含建议快照、真实基线快照、口径版本和截止日期
✔ 决策页面同时展示建议快照与批准后锁定的基线快照，不能把二者混为一谈
✔ 决策页面展示批准时采集的截止日期，并继续通过统一列定义导出
```

逐条确认（源码 `web/src/utils/tables.js`、`web/src/views/Decisions.vue` 独立复核）：

1. `decisionRows` 搬运 `baselineSnapshotId` / `definitionVersion` / `dueDate`：`tables.js` L112 `baselineSnapshotId: text(d.baselineSnapshotId)`、L113 `definitionVersion: text(d.definitionVersion)`、L116 `dueDate: text(d.dueDate)`；缺失经 `text()` 归一为显式占位 `—`（测试断言 `'—'`） — 确认。
2. 页面区分「建议快照」与「基线快照」：`Decisions.vue` L40 表头 `<th>建议快照</th><th>基线快照</th><th>口径版本</th>…`；L50/L51/L52 分别直出三个字段；L16 说明「『建议快照』是 AI 建议来源；批准时真正锁定的评价基线来自『基线快照』，并同时记录口径版本」。旧误导文案（把基线说成记录在「建议快照」字段）已删除（测试 `doesNotMatch` 该句） — 确认。
3. 页面显示批准锁定的基线快照、口径版本与人工截止日期：L51 `{{ d.baselineSnapshotId || '—' }}`、L52 `{{ d.definitionVersion || '—' }}`、L54 `{{ d.dueDate || '—' }}`（`dueDate` 来自 approve 时人工采集，见 Batch F 的 `requiredDueDate()`） — 确认。
4. `COLUMNS.decisions` / CSV 同步包含这些字段：`tables.js` L211 `suggestionSnapshotId/'建议快照'`、L212 `baselineSnapshotId/'基线快照'`、L213 `definitionVersion/'口径版本'`、L216 `dueDate/'截止日期'`；导出仍走统一列定义（测试断言 `headers: COLUMNS.decisions.map(...)` 与 `rows: rows.value.map((r) => COLUMNS.decisions.map((c) => r[c.key]))`） — 确认。
5. 不猜值、不重算：三个字段均为 `text()` 直通搬运，无默认值生成、无日期重算（唯一日期校验仍属 approve 的人工输入校验） — 确认。

## S3-71 — pipelineLocalBusinessDate.test.js

```text
counts: 5 tests / 5 pass / 0 fail        expected 5/5 → 符合
result: PASS
```

```text
✔ localIsoDay 按本地日历字段组装 YYYY-MM-DD，不经 UTC toISOString
✔ localIsoDayOffset 按本地日历加减天数并处理跨月
✔ Pipeline 默认业务日使用本地日历 helper，不能退回 UTC 日期截断
✔ 分析页默认近 7 天范围共用本地日历 helper，不再各自从 UTC instant 截日期
✔ Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime
```

逐条确认：

1. `localIsoDay` 使用本地年/月/日：`web/src/utils/localDate.js` L4–L9 用 `getFullYear()/getMonth()/getDate()` 拼接，无 `toISOString()` — 确认。
2. `localIsoDayOffset` 本地日历加减天并跨月：L11–L15 `new Date(now.getTime())` 后 `setDate(getDate() + offset)` 再走 `localIsoDay` — 确认。
3. Pipeline 使用 `localIsoDay()`：`Pipeline.vue` L77 `const businessDate = ref(localIsoDay())`，并 `import { localIsoDay } from '../utils/localDate.js'` — 确认。
4. Behavior / Sales / Overview 使用 `localIsoDayOffset(-6/0)`：三页面均 `import { localIsoDayOffset } from '../utils/localDate.js'`，`const from = ref(localIsoDayOffset(-6))`、`const to = ref(localIsoDayOffset(0))` — 确认（测试对三个文件逐一断言）。
5. 四个页面不再用 `toISOString().slice(0, 10)` 生成 HTML date 默认值：Pipeline / Behavior / Sales / Overview 该表达式出现次数均为 **0** — 确认。
6. Pipeline 仍把用户确认日期组为 `<date>T00:00:00`：`Pipeline.vue` L128 `businessTime: businessDate.value + 'T00:00:00'`（测试断言） — 确认。

独立复算（真实 `Date` + 非 UTC 时区，等价逻辑，非应用自带断言）：

```text
TZ=Asia/Shanghai    本地 2026-09-07 07:00 → helper=2026-09-07，旧写法 toISOString().slice=2026-09-06（错一天，一致=false）
TZ=America/New_York 本地 2026-09-07 23:00 → helper=2026-09-07，旧写法 toISOString().slice=2026-09-08（错一天，一致=false）
TZ=UTC             两个时刻 helper 与旧写法一致（说明缺陷只在非 UTC 时区的临界时刻暴露）
offset(0/-6/+30) on 2026-09-02 → 2026-09-02 / 2026-08-27 / 2026-10-02（跨月正确）
```

## 决策中心回归

```text
counts: 21 tests / 21 pass / 0 fail        expected 21/21 → 符合
result: PASS
  decisionRowIdentity.test.js      3/3
  decisionBaselineDisplay.test.js  2/2
  decisionApprovalInput.test.js    5/5
  decisionSubmitOwner.test.js      4/4
  decisionCancelWiring.test.js     3/3
  decisionRequiredReason.test.js   4/4
```

未回归项：决策行 identity 保留、基线单次格式化、submit owner 采集、approve owner/dueDate 显式输入、reject/cancel reason 采集、业务 cancel 与 `useAnalysis.cancel` 职责分离，全部保持（本批 `Decisions.vue` 改动仅涉及表头/字段直出与说明文案）。

## Web 完整门禁

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=226  passed=226  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=226；`✖` 行数=0
vite production build:            PASS（672 modules transformed；built in 6.89s）
```

- Expected 226 confirmed: **YES**
- Actual total: **226**（Batch F 217 + `decisionExecutionContextDisplay` 4 + `pipelineLocalBusinessDate` 5；以实测汇总为准）
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## Unverified runtime areas（本批未覆盖，不得据此宣称 E2E 已验收）

- 真实浏览器 `input[type=date]` 默认值与 `window.prompt` 交互；
- 真实 HTTP 决策状态机（`/decisions/{id}/submit|approve|…`）与后端校验；
- 决策 DB 落库（基线快照/口径版本/截止日期的真实持久化）；
- Java default/spark/isolated/3307 门禁与 Spark/Hive/Flume E2E；
- 时区相关的真实浏览器行为（本批仅在 Node 侧对 helper 做了多时区等价复算）。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-g-web-verify.log（24311 bytes, exit=0）
构建产物:            web/dist（gitignored，由本次 vite build 重写）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 4366bcb7…）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-G-WEB-DECISION-CONTEXT-LOCAL-DATES-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（`CURRENT_BATCH.md` §9 PASS rule）：

- 精确 SHA 正确：`4366bcb7dcc6347657744e115f0cc0706aa6baea` — 满足；
- S3-69/S3-70 4/4 — 满足；
- S3-71 5/5 — 满足；
- 决策回归 21/21 — 满足；
- `npm run verify` 全绿（226/226，failed/cancelled=0）且 Vite build PASS — 满足；
- 无新增回归 — 满足；
- 结果同时写本地与 `verification-results` GitHub 结果文件 — 见下段。
