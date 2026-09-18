# BATCH-O-WEB-SECONDARY-READ-CONCURRENCY — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `f29c3c352e19581fc425fe9633298ff0fd18d34a`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-PLAN.md`（已完整读取并按 §1–§11 执行）
- Batch ID: `BATCH-O-WEB-SECONDARY-READ-CONCURRENCY`
- Tested commit: `d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-18 10:06:35 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-18 10:06:23 +08:00] cover decision read write serialization`
  - **注意**：`origin/feature/v3-development` 当时为 `f29c3c352e19581fc425fe9633298ff0fd18d34a`（文档提交），本批**未测该 HEAD**，只测批次指定 SHA。
- Accepted predecessor: `BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY` @ `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d` / PASS（Web 全量基线 296/296）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批生产改动（属被测对象，只读核对）：`web/src/views/Rfm.vue`（旁路 `rfmFetchSeq`）、`web/src/views/Decisions.vue`（旁路 `decisionFetchSeq` + 读写互斥）
- 本批测试改动：新增 `web/tests/secondaryReadConcurrency.test.js`（9）；改写 `web/tests/postJr1WebHardening.test.js`（钉 `busy || loading`）、`web/tests/decisionCancelWiring.test.js`（钉新卸载包装，保留业务取消所有者）；未增删其它用例
- 后端 API/状态机契约、DB/Flyway、auth/security、AI SQL、3307、Spark/Hive/Flume 均不在本批范围

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
git fetch origin                                                exit=0   4ae97e6..f29c3c3  feature/v3-development
git checkout --detach d4a53a08d3121ce2ce8de9ee4e0582b7230835ef  exit=0
git rev-parse HEAD                                              exit=0   = d4a53a08d3121ce2ce8de9ee4e0582b7230835ef
git status --short                                              exit=0   空（clean）

cd web
node --test tests/secondaryReadConcurrency.test.js              exit=0   9 tests / 9 pass / 0 fail / 0 cancelled
node --test tests/analysisFilterInteractionHardening.test.js    exit=0   8 tests / 8 pass / 0 fail / 0 cancelled
node --test tests/postJr1WebHardening.test.js                   exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/decisionCancelWiring.test.js                  exit=0   3 tests / 3 pass / 0 fail / 0 cancelled
node --test tests/decisionApprovalInput.test.js                 exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/decisionSubmitOwner.test.js                   exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/decisionRequiredReason.test.js                exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmMatrixOwnership.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   305 tests / 305 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 3.00s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史；未覆盖任何既有结果文件。

## Targeted suites

```text
本批核心
  secondaryReadConcurrency.test.js           9/9 PASS（expected 9/9）   ← 本批新增簇
回归覆盖
  analysisFilterInteractionHardening.test.js 8/8 PASS（expected 8/8）
  postJr1WebHardening.test.js                4/4 PASS（expected 4/4）   ← 已改写为钉 busy || loading
  decisionCancelWiring.test.js               3/3 PASS（expected 3/3）   ← 已改写为钉新卸载包装
  decisionApprovalInput.test.js              5/5 PASS（expected 5/5）
  decisionSubmitOwner.test.js                4/4 PASS（expected 4/4）
  decisionRequiredReason.test.js             4/4 PASS（expected 4/4）
  rfmSnapshotPinning.test.js                 4/4 PASS（expected 4/4）
  rfmObservationWindow.test.js               4/4 PASS（expected 4/4）
  rfmMatrixOwnership.test.js                 4/4 PASS（expected 4/4）
  exportFreshnessGuard.test.js               6/6 PASS（expected 6/6）
定向合计 55/55，全部 exit 0（与计划 §6 完全一致）
```

## Full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=305  passed=305  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=305；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 3.00s
```

- Expected 305 confirmed: **YES**（Batch N-R1 基线 296 + `secondaryReadConcurrency` 新增 9 = 305；既有用例只被改写、未增删）。
- Actual total = **305**；passed = 305；Failed / Cancelled / Skipped = **0 / 0 / 0**；count drift = **0（无未解释偏差）**。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 3.00s`；`web/dist` 最后写入时间由 `2026-09-18 09:52:48` 刷新为 `2026-09-18 10:31:19`（本批执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## Required semantic checks（计划 §8，逐条）

```text
1) RFM 声明 rfmFetchSeq；每次 fetchRfm 在 api.rfm(...) 之前捕获 const mySeq = ++rfmFetchSeq
   → Rfm.vue L94 `let rfmFetchSeq = 0`；L97–L99 `async function fetchRfm(params, signal) { const mySeq = ++rfmFetchSeq; const rfmRaw = await api.rfm({}, { signal })`   满足

2) RFM 缺失 snapshotId 时的 usersError 写入受 mySeq === rfmFetchSeq 保护
   → Rfm.vue L104–L107 `if (!rfm.snapshotId) { if (mySeq === rfmFetchSeq) { usersError.value = 'RFM 响应未提供 snapshotId，……' } }`                              满足

3) RFM 旁路 users 请求失败仅在「非 abort 且 mySeq === rfmFetchSeq」时写 usersError
   → Rfm.vue L116–L119 `catch (e) { if (!isAbort(e) && mySeq === rfmFetchSeq) usersError.value = (e && (e.message || e.code)) || '请求失败' }`；
     L95 `isAbort` 识别 ERR_CANCELED / CanceledError / AbortError                                                              满足

4) RFM 卸载时先自增 rfmFetchSeq 再 analysis.cancel()
   → Rfm.vue L205–L208 `onBeforeUnmount(() => { rfmFetchSeq += 1; analysis.cancel() })`                                       满足

5) Decisions 声明 decisionFetchSeq；每次 fetchDecisions 在 api.decisions(...) 之前捕获 const mySeq = ++decisionFetchSeq
   → Decisions.vue L104 `let decisionFetchSeq = 0`；L109–L111 `async function fetchDecisions(params, signal) { const mySeq = ++decisionFetchSeq; const raw = await api.decisions(20, { signal })`   满足

6) evaluations 与 evaluationError 只在 if (mySeq === decisionFetchSeq) 内成对提交
   → Decisions.vue L126–L129 `if (mySeq === decisionFetchSeq) { evaluations.value = evalMap; evaluationError.value = failed.join('；') }`；
     单行失败 L122–L124 仅在该分支内汇总（非 abort），不静默                                              满足

7) Decision 卸载时先自增 decisionFetchSeq 再 cancel()；业务取消仍只有一个显式 decisionAction(..., 'cancel', ...) 所有者
   → Decisions.vue L319–L321 `onUnmounted(() => { decisionFetchSeq += 1; cancel()`；L291 为唯一 `api.decisionAction(d.id, 'cancel', { reason })`
     （其余调用点仅 L183 通用 act、L241 submit、L259 approve、L275 reject、L305 evaluate）              满足

8) 手工刷新按钮使用 refresh、由 loading || busy 禁用，且 refresh() 自身在 load({}) 前拒绝同一条件
   → Decisions.vue L10 `<button … @click="refresh" :disabled="loading || busy">`；L157–L160
     `function refresh() { if (loading.value || busy.value) return; return load({}) }`                  满足

9) 内部 flush() 仍直接调用 load({})、不经过外部 refresh()，写入成功后在 busy=true 时仍能刷新
   → Decisions.vue L162–L164 `async function flush() { await load({}) }`；写操作在 `busy.value = true` 之后 `await flush()`
     （L184、L242、L260、L276、L292、L306），flush 无 busy 判断                                       满足

10) 所有 Decision 状态动作按钮由 loading || busy 禁用；六个状态写处理器在 prompt/API 前拒绝 busy || loading
   → Decisions.vue L67–L73 六个按钮全部 `:disabled="loading || busy"`；
     L179 act、L234 submitDecision、L251 approve、L269 rejectDecision、L285 cancelDecision、L301 evaluate
     首行均为 `if (busy.value || loading.value) return`                                            满足

11) Decision 既有业务语义保持完整：submit owner 采集、approve owner/dueDate 校验、reject/cancel 必填原因、
    评价接线与 payload 形状
   → decisionSubmitOwner 4/4、decisionApprovalInput 5/5、decisionRequiredReason 4/4、decisionCancelWiring 3/3、
     postJr1WebHardening 4/4 全部 PASS（exit=0）                                                    满足

12) RFM snapshot pinning、observation-window、matrix-owner、load 重入与导出子集语义保持绿
   → rfmSnapshotPinning 4/4、rfmObservationWindow 4/4、rfmMatrixOwnership 4/4、
     analysisFilterInteractionHardening 8/8（含 load 重入与导出子集）全部 PASS                      满足

13) 共享 CSV stale / 空子集 fail-closed 行为保持绿
   → exportFreshnessGuard.test.js 6/6 PASS（exit=0）                                                满足

14) 全量 Web 门禁 305/305 且 Vite production build 通过
   → npm run verify exit=0；305 tests / 305 pass / 0 fail / 0 cancelled / 0 skipped；
     vite v5.4.21 build PASS（672 modules / 3.00s，dist 已刷新）                                    满足
```

## Evidence boundary（计划 §9，本批不证明）

- 真实浏览器点击时序/DOM 竞态；
- 真实 HTTP abort / 晚到响应时序；
- 后端 Decision 状态机实际执行；
- Decision DB 持久化 / 评价持久化；
- 浏览器 CSV 行为；
- 3307 运行时；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-o-web-verify.log（31409 bytes, exit=0）
构建产物:            web/dist（gitignored，本批 vite build 已刷新：2026-09-18 10:31:19）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ d4a53a0…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §11 PASS rule 逐条）：

- checked-out SHA 精确等于 `d4a53a08d3121ce2ce8de9ee4e0582b7230835ef` — 满足；
- 定向套件 55/55、零 failed/cancelled/skipped — 满足；
- 全量 Web 门禁 exit 0、305/305、零 failed/cancelled/skipped、无未解释漂移 — 满足；
- Vite production build 实际执行并 PASS（672 modules / 3.00s，dist 已刷新）— 满足；
- 计划 §8 十四项语义复核逐条满足 — 满足；
- 工作区执行前后均 clean — 满足；
- 验证期间未做任何修复（本批生产与测试改动均在被测 SHA 内，属被测对象）— 满足。
