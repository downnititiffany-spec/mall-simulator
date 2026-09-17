# BATCH-K-WEB-CONSISTENCY-HARDENING — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `2862b4529e00aaf35a7c952a45309e4e2f1fd046`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-K-WEB-CONSISTENCY-HARDENING-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-K-WEB-CONSISTENCY-HARDENING`
- Tested commit: `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 16:10:04 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 16:09:51 +08:00] cover post J-R1 consistency hardening`
- Accepted predecessor: `BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING` @ `30d5713edae8c90e8849ea51cccaa269d80d3f00` / PASS（本批基线 251/251）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批生产改动范围（只读核对，相对 J-R1 已验收 SHA）：`web/src/composables/useAnalysis.js`(+5/−1)、`web/src/utils/exportCsv.js`(+20/−1)、`web/src/views/Behavior.vue`(+18/−9)、`web/src/views/Decisions.vue`(+6)、`web/src/views/Overview.vue`(+1)、`web/src/views/Rfm.vue`(+5/−1)、`web/src/views/Sales.vue`(+1)，以及新增 `web/tests/postJr1WebHardening.test.js`、`web/tests/exportFreshnessGuard.test.js`。无后端/DB/Flyway/状态机/权限/AI SQL/Spark/Hive/Flume 改动。
- Scope: post-J-R1 Web 一致性加固簇（同快照组合与回声拒绝、导出 freshness/空子集 fail-closed、Decision 写动作重入守卫、共享 CSV helper 原生 Node ESM 可加载性、已验收 RFM 行为回归）

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
git fetch origin                                                exit=0   82bc6a7..2862b45  feature/v3-development
git checkout --detach bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf  exit=0
git rev-parse HEAD                                              exit=0   = bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf
git status --short                                              exit=0   空（clean）

cd web
node --test tests/postJr1WebHardening.test.js                   exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmMatrixOwnership.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   261 tests / 261 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 2.71s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史。

## Targeted suites

```text
postJr1WebHardening.test.js        4/4 PASS（expected 4/4）
exportFreshnessGuard.test.js       6/6 PASS（expected 6/6）
rfmSnapshotPinning.test.js         4/4 PASS（expected 4/4）
rfmObservationWindow.test.js       4/4 PASS（expected 4/4）
rfmMatrixOwnership.test.js         4/4 PASS（expected 4/4）
定向合计 22/22，全部 exit 0
```

```text
✔ Behavior 先锁定 funnel snapshot，再用同一 snapshot 请求 overview，并拒绝回声错快照
✔ RFM 保留主响应 publisher source，并拒绝 users 聚合回声成另一快照
✔ Sales 与 Overview 的导出 handler 自身也执行 exportable fail-closed
✔ Decision 所有写动作在 prompt/API 之前先拒绝 busy 重入
✔ useAnalysis 导出上下文携带当前 viewState，下载层能判断 freshness
✔ ready 且有真实行时允许下载
✔ loading/stale/error/empty 即使屏上仍有旧行也禁止下载
✔ 整页 ready 但当前导出子集为空时禁止生成空 CSV
✔ 未携带 viewState 的旧调用方保持兼容，但仍要求非空数据行
✔ exportAnalysisCsv 在创建 Blob 前执行最终 canDownloadCsv 守卫
✔（RFM 三套件 12 条已验收用例全部保持绿）
```

## Required semantic checks（计划 §7，逐条）

```text
1) Behavior 用 funnel.snapshotId 请求 Overview，不自行再解析 ACTIVE
   → Behavior.vue L70 注释「先由漏斗接口固定主快照，再用同一 snapshotId 请求 Overview」；
     L73 `api.funnel({}, { signal })`；L76–L80 `if (funnel.snapshotId) { overview = … api.overview({ snapshotId: funnel.snapshotId }) }`    满足

2) Behavior 拒绝非空且不一致的 Overview snapshotId
   → L82–L83 `if (overview.snapshotId && overview.snapshotId !== funnel.snapshotId) { throw new Error(跨接口快照不一致：funnel=…, overview=…) }`  满足

3) RFM 页面上下文保留 source: rfm.source，次级 users 响应不能替换主 publisher
   → Rfm.vue L122 `source: rfm.source`（响应上下文构造点）；
     相对 J-R1 的 diff 仅新增 `source: rfm.source` 与 users 回声拒绝，未引入 users 侧 source 赋值        满足

4) RFM users 聚合固定主快照且缺/错快照 fail closed
   → L106 `await api.users({ snapshotId: rfm.snapshotId }, { signal })`；
     L102–L103 主响应缺 snapshotId ⇒ 跳过第二请求并写明原因；
     L107–L108 `if (users.snapshotId !== rfm.snapshotId) throw new Error('用户聚合快照不一致：…')`；
     捕获分支注释「其余错误（含快照回声不一致）只降级用户聚合区块」——主口径不被污染                  满足

5) 已验收的 RFM 观察期派生与后端类目属主保持未变且为绿
   → Rfm.vue L151–L153 仍为 J-R1 已验收的具名派生注释 + `const periodStart/periodEnd = computed(() => data.value.… || null)`（逐行未变）；
     `periodText` 与导出 `periodStart.value || ''` 未变；
     相对 J-R1 的 Rfm.vue diff 仅 3 处（回声拒绝 + source + 注释）；
     rfmObservationWindow 4/4、rfmMatrixOwnership 4/4 绿                                            满足

6) Sales / Overview 的 doExport() 各自检查 exportable.value
   → Sales.vue L140–L141 `function doExport() { if (!exportable.value) return`；
     Overview.vue L182–L183 `function doExport() { if (!exportable.value) return`（handler 级 fail-closed）  满足

7) useAnalysis.exportContext 携带 viewState；exportAnalysisCsv 在建 Blob 前拒绝非 ready/零行
   → useAnalysis.js L53–L54 注释 + `viewState: state.value`（在 exportContext computed 内）；
     exportCsv.js L10–L13 `canDownloadCsv`：`rows` 非数组或长度为 0 ⇒ false；`viewState` 存在时仅 `'ready'` 放行；
     L25 `if (!canDownloadCsv({ context, rows })) return null` 位于 L27 生成时间、L29 拼装、L30 `new Blob` 之前  满足

8) ready 且有真实行仍可导出；无 viewState 的兼容调用方保持可用（但仍需非空行）
   → exportCsv.js L12 仅在 `viewState` 非 undefined/null 时收紧 ⇒ 旧调用方不受影响；L11 仍强制非空行；
     对应测试「ready 且有真实行时允许下载」「未携带 viewState 的旧调用方保持兼容」均绿                  满足

9) Decision 六个写动作在 prompt/写 API 之前拒绝 busy 重入
   → act（start/complete 通用）L170、submitDecision L225、approve L242、rejectDecision L260、cancelDecision L276、evaluate L292
     均为函数入口 `if (busy.value) return`，其后的 `busy.value = true` 与 `api.decisionAction` 在守卫之后      满足

10) exportCsv.js 以显式 .js 导入，可被原生 Node ESM 直接加载
   → L2 `import { buildCsvText, buildExportFilename } from './csv.js'`；web/package.json `"type": "module"`；
     `node --test tests/exportFreshnessGuard.test.js` 直接导入该模块并 6/6 通过（无需 Vite 解析）           满足
```

## Web full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=261  passed=261  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=261；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 2.71s
```

- Expected 261 confirmed: **YES**（J-R1 基线 251 + 本批新增 10 条 = 261；`postJr1WebHardening` 4 + `exportFreshnessGuard` 6 = 10，无用例移除）。
- Actual total = **261**；passed = 261；Failed / Cancelled = **0 / 0**；count drift = **0（无未解释偏差）**。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 2.71s`；`web/dist` 最后写入时间由 `2026-09-17 14:32:50`（J-R1 构建）刷新为 `2026-09-17 16:16:00`（本批执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## New failures

```text
无。定向 22/22，全量 261/261，failed/cancelled=0，Vite build 实际执行并 PASS，工作区 clean。
```

## Evidence boundary（计划 §8，本批不证明）

- 真实浏览器交互 E2E（含按钮禁用与人工重复点击）；
- 真实 HTTP 竞态（ACTIVE 切换期间的跨接口抢跑）；
- 后端 decision 状态机语义与数据库写入；
- 3307 运行行为；
- Spark/Hive/Flume E2E；
- 真实数据库快照内容。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-k-web-verify.log（27430 bytes, exit=0）
构建产物:            web/dist（gitignored，本批 vite build 已刷新：16:16:00）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ bd7226b8…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §10 PASS rule 逐条）：

- checked-out SHA 精确等于 `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf` — 满足；
- 全部定向套件通过（4/4、6/6、4/4、4/4、4/4，合计 22/22）— 满足；
- 完整 Web 套件零 failure / 零 cancellation，且计数与预期一致（261/261，无未解释漂移）— 满足；
- Vite production build 实际执行并 PASS — 满足；
- 计划 §7 十项语义复核逐条满足 — 满足；
- 验证工作区保持 clean（测试前后 `git status --short` 为空）— 满足；
- 结果本地/GitHub 双落盘 — 见下段。
