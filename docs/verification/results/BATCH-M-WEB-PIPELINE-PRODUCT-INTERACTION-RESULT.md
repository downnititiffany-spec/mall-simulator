# BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `88823784abff33854610b75c211745eb6c7affa6`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION`
- Tested commit: `61776daf52cfcd396325d7bbdf56e890f1731224`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 17:11:57 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 17:11:36 +08:00] align pipeline page guard with busy lock`
- Accepted predecessor: `BATCH-L-WEB-INTERACTION-CONSISTENCY` @ `395eead89d78d0a40665f2b985002371943f5eb0` / PASS（本批基线 271/271）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批改动范围（只读核对）：`web/src/views/Pipeline.vue`、`web/src/views/Products.vue`、`web/tests/pipelineOperationIdentity.test.js`(+2)、`web/tests/productServerPagination.test.js`(+1 及断言升级)、`web/tests/pipelinePage.test.js`（仅改断言）。**未**改动 `web/tests/pipelineLocalBusinessDate.test.js`。无后端/DB/Flyway/状态机/权限/AI SQL/3307/Spark/Hive/Flume 改动。
- Scope: post-Batch-L Web 交互一致性簇（Pipeline 触发输入冻结与实例化取值、Products 当前页导出资格与分页交互锁定）+ 既有 Pipeline 同一性与共享 CSV freshness 回归

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
git fetch origin                                                exit=0   f661184..8882378  feature/v3-development
git checkout --detach 61776daf52cfcd396325d7bbdf56e890f1731224  exit=0
git rev-parse HEAD                                              exit=0   = 61776daf52cfcd396325d7bbdf56e890f1731224
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineOperationIdentity.test.js             exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/pipelinePage.test.js                          exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/productServerPagination.test.js               exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/pipelineRetryHandling.test.js                 exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=1   274 tests / 273 pass / 1 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        Vite production build 未执行（npm test 失败即短路）
复现验证（独立第二次执行，确定性）
node --test tests/pipelineLocalBusinessDate.test.js             exit=1   5 tests / 4 pass / 1 fail / 0 cancelled
```

一次执行完整批次，未拆轮；未对失败做任何修复；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置。

## Targeted suites

```text
pipelineOperationIdentity.test.js  6/6 PASS（expected 6/6）
pipelinePage.test.js               6/6 PASS（expected 6/6）
productServerPagination.test.js    6/6 PASS（expected 6/6）
pipelineRetryHandling.test.js      4/4 PASS（expected 4/4）
exportFreshnessGuard.test.js       6/6 PASS（expected 6/6）
定向合计 28/28，全部 exit 0（与计划 §5 完全一致）
```

```text
✔ runOnce 自身受 busy fail-closed 保护，不能只依赖按钮 disabled
✔ 一次人工流水线触发只生成一个 operationId
✔ sourceDataVersion 与 Idempotency-Key 复用同一 operationId
✔ runOnce 仍保持成功刷新、失败可见和 finally 释放 busy
✔ runOnce 在第一个 await 前冻结业务时间与运行环境，并只用冻结值创建实例
✔ 流水线触发在途时锁住业务输入和手工刷新，避免操作上下文被 UI 改写
✔ 商品页请求显式携带 page/size/sort，不再把单页结果做本地分页
✔ 分页按钮使用后端 page/hasMore 元数据驱动
✔ 只开放后端契约允许的商品排序字段，名称和转化率不伪造本地全量排序
✔ 切换排序会回到第 1 页并重新请求后端
✔ 当前页导出资格要求整页 ready 且当前 hot 页确实非空，按钮和 handler 使用同一谓词
✔ 分页大小在加载期间锁定，applyFilters 自身也拒绝 loading 重入
✔ FAILED run 的重试按钮受 busy 保护，避免重复提交 retry
✔ retry 进入 busy、清理旧结果，并在 finally 中恢复 busy
✔ retry 成功才刷新列表，失败时转成可见 FAILED 结果而不是未处理 Promise
✔ 失败结果没有 runId 时不渲染 run#undefined
✔（pipelinePage 6 条与 exportFreshnessGuard 6 条已验收用例保持绿）
```

## Full gate

```text
command: cd web; npm run verify          exit=1
node --test "tests/**/*.test.js": total=274  passed=273  failed=1  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=273；`✖` 行数=3（1 条用例 + 2 行失败汇总）
vite production build:            未执行（npm run verify = npm test && npm run build；测试失败短路）
                                  证据：web/dist 最后写入时间保持 2026-09-17 16:59:57（Batch L 构建），
                                  本批执行时刻为 17:58:19，目录未被刷新
```

- Expected 274 confirmed: **计数一致**（Batch L 基线 271 + `pipelineOperationIdentity` +2 + `productServerPagination` +1 = 274，`pipelinePage` 数量不变）。
- Actual total = **274**；passed = **273**；Failed = **1**；Cancelled / Skipped = 0 / 0 ⇒ **本批不满足 PASS 规则**（要求 274/274、零 failure，且 Vite build 实际执行）。

## New failures

```text
新回归 1 条（该用例在已验收 Batch L（271/271 全绿）中通过，本批变为失败）。

失败用例：tests/pipelineLocalBusinessDate.test.js:47
  "Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime"
  断言（测试文件 L48）：
    assert.match(pipelineSource, /businessTime: businessDate\.value \+ 'T00:00:00'/)
  实际输出（Pipeline.vue 源码）：
    L121  const requestedBusinessDate = businessDate.value
    L137  businessTime: requestedBusinessDate + 'T00:00:00', sourceDataVersion: operationId
  报错：
    AssertionError [ERR_ASSERTION]: The input did not match the regular expression
    /businessTime: businessDate\.value \+ 'T00:00:00'/

复现：单独执行 `node --test tests/pipelineLocalBusinessDate.test.js` ⇒ exit=1，4/5（确定性，非并发偶发）。

根因（只陈述事实，未做修复）：
  本批生产改动 `cac0694 fix(web): freeze pipeline trigger inputs` 将业务日在第一个 await 之前冻结为
  `requestedBusinessDate` 并用于 createPipelineRun；而已验收守卫
  `web/tests/pipelineLocalBusinessDate.test.js`（最后改动 `4366bcb`，2026-09-17 11:55:53，
  经 Batch K/L 全量门禁验为绿）仍按旧标识符文本 `businessDate.value + 'T00:00:00'` 做源码不变量断言。
  本批更新了自己的三个测试文件（pipelineOperationIdentity / pipelinePage / productServerPagination），
  但未同步该既有守卫，导致全量门禁出现 1 条新回归。

影响：
  - `npm run verify` exit=1，`npm test` 失败使 `vite build` 未执行（dist 未被刷新）；
  - 定向 5 套件 28/28 全绿，说明本批计划内新增断言与子集资格逻辑本身通过；
  - 计划 §10 规定「otherwise record FAIL with the exact new failure and stop; do not repair it」⇒ 本次不修复。
```

## Required semantic checks（计划 §7，逐条）

```text
1) runOnce() 以 handler 级 if (busy.value) return 开头，不只依赖按钮 disabled
   → Pipeline.vue L117–L118 `async function runOnce() { if (busy.value) return`
     （L21 按钮另有 :disabled="busy"）                                                                  满足

2) requestedBusinessDate / requestedRuntimeProfileId 在第一个 await api.ingestionRun() 之前捕获
   → L121 `const requestedBusinessDate = businessDate.value`；
     L122 `const requestedRuntimeProfileId = runtimeProfileId.value`；
     第一个 await 在 L130 `await api.ingestionRun()`（其后 L131 还有 3000ms 等待）                          满足

3) api.createPipelineRun(...) 使用冻结值，不在异步等待后再读 live .value
   → L132–L138 `runResult.value = await api.createPipelineRun({ runtimeProfileId: requestedRuntimeProfileId,
     pipelineCode: 'ODS_TO_ADS', businessTime: requestedBusinessDate + 'T00:00:00', sourceDataVersion: operationId }, operationId)`
     —— 组包内无 `businessDate.value` / `runtimeProfileId.value` 的二次读取；
     但该冻结改动正是本次新回归的直接原因（见 New failures）                                            满足（且触发新回归）

4) 业务日与运行环境输入在 busy 期间禁用；手工刷新按钮在 loading || busy 时禁用
   → L16 `<input v-model="businessDate" type="date" :disabled="busy"`；
     L19 `<input v-model.number="runtimeProfileId" type="number" min="1" :disabled="busy"`；
     L40 刷新按钮 `@click="load" :disabled="loading || busy"`                                            满足

5) 既有 Pipeline 同一性/重试行为保持：单一 operationId、共享 sourceDataVersion/幂等键、busy 重入守卫、失败可见、finally 释放、成功刷新
   → L118 busy 守卫；L128 单一 `const operationId = 'manual-' + Date.now()`；
     L137 `sourceDataVersion: operationId` 与 L138 第二实参 operationId（Idempotency-Key）同源；
     L132–L142 try/catch 写入 `FAILED: …` 可见结果，L142 finally 释放；
     L139 `await load()` 仅成功路径刷新；重试侧 L148 另有 `if (busy.value) return`；
     pipelineOperationIdentity 6/6、pipelineRetryHandling 4/4 绿                                             满足

6) Products 定义 pageExportable = exportable.value && rows.value.length > 0；按钮与 doExport() 同用
   → Products.vue L126 `const pageExportable = computed(() => exportable.value && rows.value.length > 0)`；
     L9 按钮 `:disabled="!pageExportable" @click="doExport"`；
     L180–L181 `function doExport() { if (!pageExportable.value) return`（同一谓词）                        满足

7) applyFilters() 在重置页码与加载之前执行 handler 级 if (loading.value) return
   → L149–L153 `function applyFilters() { if (loading.value) return; page.value = 1; return load() }`
     —— 守卫（L150）早于 page 重置（L151）与 load()（L152）                                                 满足

8) Products 分页大小输入在 loading 期间禁用；翻页/排序 handler 继续拒绝 loading 重入
   → L7 `v-model.number="pageSize" … :disabled="loading"`；
     L155–L158 `function goPage(targetPage) { if (loading.value || targetPage < 1) return; … }`；
     L161–L162 `function toggleSort(col) { if (!col || !col.sortKey || loading.value) return`；
     L60/L62 翻页按钮另加 `:disabled="loading || …"`                                                        满足

9) Products 仍向后端发送 page/size/sort，未重新引入本地全量分页/排序或非法排序字段
   → L89 `fetcher: (params, signal) => api.products(params, { signal })`；
     L141–L143 请求体 `page: page.value, size: pageSize.value, sort: \`${sortKey.value},${sortOrder.value}\``；
     L129–L135 仅开放 rank/pv/fav/cart/buy/heat 六个后端契约排序键（名称、转化率列无 sortKey ⇒ 不可排序）；
     productServerPagination 6/6 绿（含「只开放后端契约允许的商品排序字段」）                                满足

10) 共享 CSV freshness/空行 fail-closed 保持绿
   → exportFreshnessGuard.test.js 6/6 PASS（单独执行 exit=0；全量门禁中 6 条亦全绿）                            满足
```

## Evidence boundary（计划 §8，本批不证明）

- 真实浏览器时序与 DOM 交互；
- 真实 HTTP 竞态；
- 实际采集/流水线编排行为；
- 后端幂等性/状态机行为；
- 数据库写入与 3307 运行时；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-m-web-verify.log（46469 bytes, exit=1）
构建产物:            web/dist 未被刷新（保持 2026-09-17 16:59:57，Batch L 构建）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 61776daf…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: FAIL_NEW_REGRESSION
```

判定依据（计划 §10 PASS rule 逐条）：

- checked-out SHA 精确等于 `61776daf52cfcd396325d7bbdf56e890f1731224` — 满足；
- 定向套件 28/28、零 failed/cancelled — 满足；
- 全量门禁 `npm run verify` exit 0 且 274/274、零 failure — **不满足**（exit=1，273/274，1 条新回归）；
- Vite production build 实际执行并 PASS — **不满足**（因 `npm test` 失败而未执行）；
- §7 十项语义复核 — 满足（其中第 3 项冻结实现本身正确，但破坏了既有守卫文本不变量）；
- 工作区执行前后均 clean — 满足；
- 验证期间未做任何源码/测试/文档修复 — 满足（遵守 PASS rule 的 fail-and-stop 要求）。
