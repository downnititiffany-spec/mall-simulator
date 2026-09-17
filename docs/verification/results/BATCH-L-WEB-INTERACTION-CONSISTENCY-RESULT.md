# BATCH-L-WEB-INTERACTION-CONSISTENCY — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `f66118488697ecfdf085d7d3695497e4a7c9cb23`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-L-WEB-INTERACTION-CONSISTENCY-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-L-WEB-INTERACTION-CONSISTENCY`
- Tested commit: `395eead89d78d0a40665f2b985002371943f5eb0`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 16:53:04 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 16:52:51 +08:00] align AI cancel guards with draft busy`
- Accepted predecessor: `BATCH-K-WEB-CONSISTENCY-HARDENING` @ `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf` / PASS（本批基线 261/261）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批改动范围（只读核对，相对 Batch K 已验收 SHA）：`web/src/views/AiAssistant.vue`(+14/−7)、`web/src/views/Ops.vue`(+18/−10)、`web/tests/aiAskCancellation.test.js`（改写既有 5 条，数量不变）、新增 `web/tests/aiDraftQueryConcurrency.test.js`(5 条)、新增 `web/tests/opsInteractionHardening.test.js`(5 条)。无后端/DB/Flyway/状态机/权限/AI SQL/3307/Spark/Hive/Flume 改动。
- Scope: post-Batch-K Web 交互一致性簇（Ops admin 重入与按子集导出资格、AI 草稿与问答互斥、既有取消语义与共享 CSV freshness 回归）

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
vite: 5.4.21
pwsh: 7.6.6
git:  core.autocrlf = true（未改动；检出 CRLF / 索引 blob LF）
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未运行（Web 源码/测试/构建批次）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   2862b45..f661184  feature/v3-development
git checkout --detach 395eead89d78d0a40665f2b985002371943f5eb0  exit=0
git rev-parse HEAD                                              exit=0   = 395eead89d78d0a40665f2b985002371943f5eb0
git status --short                                              exit=0   空（clean）

cd web
node --test tests/opsInteractionHardening.test.js               exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/aiDraftQueryConcurrency.test.js               exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/aiAskCancellation.test.js                     exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   271 tests / 271 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 2.84s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史。

## Targeted suites

```text
opsInteractionHardening.test.js    5/5 PASS（expected 5/5）
aiDraftQueryConcurrency.test.js    5/5 PASS（expected 5/5）
aiAskCancellation.test.js          5/5 PASS（expected 5/5）
exportFreshnessGuard.test.js       6/6 PASS（expected 6/6）
定向合计 21/21，全部 exit 0（与计划 §5 完全一致）
```

```text
✔ admin 三类写动作在 prompt/API 前先拒绝 busy 重入
✔ 快照指标导出 handler 自身检查 metricExportable
✔ 流水线导出资格同时要求页面 ready 且当前子集非空
✔ AI 两类审计各自按自己的非空子集决定导出资格
✔ exportAudit 在真正下载前按所选审计子集二次 fail-closed
✔ 草稿创建 handler 自身拒绝 draftBusy 重入
✔ 草稿创建在途时新问答入口与 ask 本体都 fail-closed
✔ 推荐问题入口也拒绝 busy/draftBusy 重入
✔ 问答输入、发送、推荐问题和历史回填在 draftBusy 期间都被 UI 禁用
✔ 打开/关闭草稿不会在草稿创建请求在途时改写表单状态
✔ 主按钮在 ask busy 时仍可点击取消，但 draftBusy 时禁止启动另一条问答
✔ cancelAsk 使旧响应失效、真正 abort，并立即清理 busy/旧结果
✔ 同一个主按钮在 draftBusy 时先拒绝，在 ask busy 时取消，否则正常 ask
✔ ask 保留序号守卫，取消后的旧响应不得重新写回页面状态
✔ 在途分析或草稿创建期间推荐问题和历史回填均禁用，避免跨操作状态错位
✔（exportFreshnessGuard 6 条已验收用例保持绿）
```

## Required semantic checks（计划 §7，逐条）

```text
1) createUser / toggle / resetPwd 均以 if (busy.value) return 开头；resetPwd 的守卫在 prompt() 之前
   → Ops.vue L443–L445 createUser：`async function createUser() { if (busy.value) return; busy.value = true`
     L458–L460 toggle：同型入口守卫
     L474–L478 resetPwd：`async function resetPwd(u) { if (busy.value) return; const pwd = prompt(…) …; busy.value = true`
     —— 守卫（L475）严格早于 prompt（L476），busy 置位（L478）晚于用户输入                              满足

2) exportMetrics() 在构造导出数据之前检查 metricExportable.value
   → L403–L407 `function exportMetrics() { if (!metricExportable.value) return; … exportAnalysisCsv({ … })`
     （metricExportable 仍为 useAnalysis 的 exportable，L308 `exportable: metricExportable`）            满足

3) pipelineExportable = exportable.value && pipelineTable.value.rows.length > 0；按钮与 exportRuns() 同用
   → L381 `const pipelineExportable = computed(() => exportable.value && pipelineTable.value.rows.length > 0)`
     L114 按钮 `:disabled="!pipelineExportable" @click="exportRuns"`（L115 文案同源）
     L416–L417 `function exportRuns() { if (!pipelineExportable.value) return`                             满足

4) aiHistoryExportable / aiCallExportable 各自要求页面 ready + 自身非空子集；按钮绑定各自谓词
   → L382 `aiHistoryExportable = exportable.value && aiHistoryRows.value.length > 0`
     L383 `aiCallExportable = exportable.value && aiCallRows.value.length > 0`
     L154 按钮 `:disabled="!aiHistoryExportable" @click="exportAudit('ai')"`；
     L186 按钮 `:disabled="!aiCallExportable" @click="exportAudit('calls')"`                              满足

5) exportAudit(which) 先确定所选审计种类，子集不可导出时在 exportAnalysisCsv 之前 return
   → L428–L434 `function exportAudit(which) { const isAi = which === 'ai';
     if (isAi ? !aiHistoryExportable.value : !aiCallExportable.value) return;
     const generatedAt = …; const columns = …; const rows = …; exportAnalysisCsv({ … })`
     —— 早退发生在 L434 调用之前                                                                         满足

6) createDraft() 以 if (draftBusy.value) return 开头，之后才调用 api.decisionCreate
   → AiAssistant.vue L298–L309 `async function createDraft() { if (draftBusy.value) return; … draftBusy.value = true;
     … draftCreated.value = (await api.decisionCreate(payload.body)) || {}`                             满足

7) openDraft() 拒绝缺证据锚点或 draftBusy；closeDraft() 拒绝 draftBusy
   → L283–L284 `function openDraft(s) { if (!canCreateDraft.value || draftBusy.value) return`
     （canCreateDraft 由 evidenceAnchor.kind !== ANCHOR_KIND.NONE 派生，L258–L262）
     L291–L292 `function closeDraft() { if (draftBusy.value) return`                                    满足

8) 主输入/主按钮、推荐问题、历史回填在 draftBusy 期间均被禁用
   → L21 输入框 `:disabled="busy || draftBusy"`；
     L22 主按钮 `:disabled="draftBusy || (!busy && !question.trim())"`（busy 时仍可点 = 取消）；
     L161 推荐问题 `:disabled="busy || draftBusy"`；
     L172 历史回填 `:disabled="busy || draftBusy"`；
     草稿面板 L88/L120/L124 亦受 draftBusy 约束                                                          满足

9) handleAskAction() / ask() / askPreset() 均拒绝 draftBusy；ask() 仍拒绝普通 busy 重入
   → L349–L356 handleAskAction：`if (draftBusy.value) return; if (busy.value) { cancelAsk(); return } ask()`
     L358–L360 ask：`const text = question.value.trim(); if (!text || busy.value || draftBusy.value) return`
     L389–L390 askPreset：`if (busy.value || draftBusy.value) return`                                    满足

10) 既有显式取消语义保持：AI 查询 busy 时主按钮调用 cancelAsk()，递增 askSeq、abort、清理 busy/结果并阻止旧响应回写
   → L351–L353 busy 分支调用 cancelAsk()；
     L336–L347 cancelAsk：`askSeq += 1`（L338）、`const controller = askController; askController = null; if (controller) controller.abort()`
     （L339–L341）、清理 busy/旧结果并写入取消提示（L346）；
     L361 `const mySeq = ++askSeq`、L362 旧 controller abort；L371/L375 `if (mySeq !== askSeq) return` 丢弃过期响应写回；
     L413–L417 卸载时同样 `askSeq += 1` + abort                                                          满足
```

## Web full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=271  passed=271  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=271；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 2.84s
```

- Expected 271 confirmed: **YES**（Batch K 基线 261 + 新增 `opsInteractionHardening` 5 + `aiDraftQueryConcurrency` 5 = 271；`aiAskCancellation` 被改写但数量仍为 5，无新增/删除）。
- Actual total = **271**；passed = 271；Failed / Cancelled / Skipped = **0 / 0 / 0**；count drift = **0（无未解释偏差）**。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 2.84s`；`web/dist` 最后写入时间由 `2026-09-17 16:16:00`（Batch K 构建）刷新为 `2026-09-17 16:59:57`（本批执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## New failures

```text
无。定向 21/21，全量 271/271，failed/cancelled/skipped=0，Vite build 实际执行并 PASS，工作区 clean。
```

## Evidence boundary（计划 §8，本批不证明）

- 真实浏览器双击时序与真实 prompt/对话框行为；
- 真实 HTTP 竞态时序；
- admin 权限强制与实际用户变更持久化；
- decision 状态机与数据库写入；
- AI provider / Text-to-SQL 运行时行为；
- 3307 与 Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-l-web-verify.log（28296 bytes, exit=0）
构建产物:            web/dist（gitignored，本批 vite build 已刷新：16:59:57）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 395eead8…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-L-WEB-INTERACTION-CONSISTENCY-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §10 PASS rule 逐条）：

- checked-out SHA 精确等于 `395eead89d78d0a40665f2b985002371943f5eb0` — 满足；
- 四个定向套件全部按预期通过（5/5、5/5、5/5、6/6，合计 21/21）— 满足；
- `npm run verify` 退出 0，271/271，零 failure/cancellation/skip — 满足；
- Vite production build 实际执行并 PASS — 满足；
- 计划 §7 十项语义复核逐条满足 — 满足；
- 工作区执行前后均 clean — 满足；
- 结果本地/GitHub 双落盘 — 见下段。
