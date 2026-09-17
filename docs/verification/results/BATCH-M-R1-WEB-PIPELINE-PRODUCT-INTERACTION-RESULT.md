# BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `fb8dd64f2636d849e4568d63c9c2acf343f2f54e`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本 R1 定义）
  - 永久计划：`docs/verification/batches/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION`
- Tested commit: `7748caf8b2628bd47ed5075db62ec2cd26a42fe6`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 19:58:09 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 19:58:00 +08:00] align local business date guard with frozen input`
- Accepted predecessor: `BATCH-L-WEB-INTERACTION-CONSISTENCY` @ `395eead89d78d0a40665f2b985002371943f5eb0` / PASS（本批基线 271/271）
- Failed attempt retained for audit（未覆盖、未改写）：
  - `BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION` @ `61776daf52cfcd396325d7bbdf56e890f1731224` / `FAIL_NEW_REGRESSION`
  - raw result：`verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`，commit `0c46b9c79ba82252ef1e8a961e9def0eb32fabba`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- R1 改动范围（只读核对，相对失败批次 SHA `61776da`）：**仅** `web/tests/pipelineLocalBusinessDate.test.js`（+3/−2，重写既有断言的 5 行）；生产代码 `web/src/views/Pipeline.vue`、`web/src/views/Products.vue` 与其余测试文件**逐字节未变**。无后端/DB/Flyway/状态机/权限/AI SQL/3307/Spark/Hive/Flume 改动。
- Scope: 修正 Batch M 暴露出的一条过时 characterization test，使其验证真实语义（冻结后组包）而非旧变量名；随后复测 Pipeline/Products 交互一致性簇

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
git fetch origin                                                exit=0   8882378..fb8dd64  feature/v3-development
git checkout --detach 7748caf8b2628bd47ed5075db62ec2cd26a42fe6  exit=0
git rev-parse HEAD                                              exit=0   = 7748caf8b2628bd47ed5075db62ec2cd26a42fe6
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineLocalBusinessDate.test.js             exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/pipelineOperationIdentity.test.js             exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/pipelinePage.test.js                          exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/productServerPagination.test.js               exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/pipelineRetryHandling.test.js                 exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   274 tests / 274 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 4.55s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史；未覆盖原失败结果文件。

## Targeted suites

```text
pipelineLocalBusinessDate.test.js  5/5 PASS（expected 5/5，原失败用例已转为通过）
pipelineOperationIdentity.test.js  6/6 PASS（expected 6/6）
pipelinePage.test.js               6/6 PASS（expected 6/6）
productServerPagination.test.js    6/6 PASS（expected 6/6）
pipelineRetryHandling.test.js      4/4 PASS（expected 4/4）
exportFreshnessGuard.test.js       6/6 PASS（expected 6/6）
定向合计 33/33，全部 exit 0（与计划 §5 完全一致）
```

```text
✔ localIsoDay 按本地日历字段组装 YYYY-MM-DD，不经 UTC toISOString
✔ localIsoDayOffset 按本地日历加减天数并处理跨月
✔ Pipeline 默认业务日使用本地日历 helper，不能退回 UTC 日期截断
✔ 分析页默认近 7 天范围共用本地日历 helper，不再各自从 UTC instant 截日期
✔ Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime，允许先冻结再异步组包   ← Batch M 新回归，本 R1 已修
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
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=274  passed=274  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=274；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 4.55s
```

- Expected 274 confirmed: **YES**（Batch L 基线 271 + `pipelineOperationIdentity` +2 + `productServerPagination` +1 = 274；R1 仅重写 1 条既有断言，未增删用例，故计数与失败尝试一致）。
- Actual total = **274**；passed = 274；Failed / Cancelled / Skipped = **0 / 0 / 0**；count drift = **0（无未解释偏差）**。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 4.55s`；`web/dist` 最后写入时间由 `2026-09-17 16:59:57`（Batch L 构建）刷新为 `2026-09-17 20:02:12`（本 R1 执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**（Batch M 的 1 条新回归在本 R1 消除）

## Regression resolution（Batch M 失败项 → R1 结果）

```text
失败项（Batch M @61776da）：tests/pipelineLocalBusinessDate.test.js:47
  旧断言：assert.match(pipelineSource, /businessTime: businessDate\.value \+ 'T00:00:00'/)

R1 修改（本 SHA，diff 61776da..7748caf 仅此 5 行）：
  test('Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime，允许先冻结再异步组包', () => {
    assert.match(pipelineSource, /const requestedBusinessDate = businessDate\.value/)
    assert.match(pipelineSource, /businessTime: requestedBusinessDate \+ 'T00:00:00'/)
  })

语义保全核对：
  - 仍断言「用户确认的业务日」被捕获（`requestedBusinessDate = businessDate.value`）；
  - 仍断言 `businessTime` 由该本地业务日 + 字面 `T00:00:00` 组成（非 UTC 截断）；
  - 未放宽为「任意包含 T00:00:00」，仍绑定具体标识符与组包顺序；
  - 生产代码未回退：Pipeline.vue L121/L137 与 Batch M 逐字节一致。
结果：用例转绿，全量门禁 274/274，Vite build 实际执行并通过。
```

## Required semantic checks（计划 §7，逐条）

```text
1) runOnce() 以 handler 级 if (busy.value) return 开头
   → Pipeline.vue L117–L118 `async function runOnce() { if (busy.value) return`（L21 按钮另有 :disabled="busy"）   满足

2) requestedBusinessDate / requestedRuntimeProfileId 在第一个 await api.ingestionRun() 之前捕获
   → L121 `const requestedBusinessDate = businessDate.value`；L122 `const requestedRuntimeProfileId = runtimeProfileId.value`；
     第一个 await 在 L130 `await api.ingestionRun()`（L131 另有 3000ms 等待）                                  满足

3) api.createPipelineRun(...) 只用冻结值，异步等待后不再读 live .value
   → L132–L138 `runResult.value = await api.createPipelineRun({ runtimeProfileId: requestedRuntimeProfileId,
     pipelineCode: 'ODS_TO_ADS', businessTime: requestedBusinessDate + 'T00:00:00', sourceDataVersion: operationId }, operationId)`
     —— 组包内无 `businessDate.value` / `runtimeProfileId.value` 二次读取                                      满足

4) businessTime 仍是用户确认的本地业务日 + 字面 T00:00:00；未重新引入 UTC toISOString().slice(0,10)
   → L137 `businessTime: requestedBusinessDate + 'T00:00:00'`；L121 上游取自本地日历 ref（默认值来自 localIsoDay）；
     全文件 `toISOString` 出现次数 = 0（仅 L52 展示列、L99 读取 ctx.businessTime，均非日期截断）；
     R1 守卫同时断言 L121 与 L137 两处文本                                                           满足

5) 业务日/运行环境输入在 busy 期间禁用；手工刷新在 loading || busy 时禁用
   → L16 `<input v-model="businessDate" type="date" :disabled="busy"`；
     L19 `<input v-model.number="runtimeProfileId" type="number" min="1" :disabled="busy"`；
     L40 刷新按钮 `@click="load" :disabled="loading || busy"`                                       满足

6) Pipeline 同一性/重试不变量保持：单一 operationId、共享 sourceDataVersion/幂等键、失败可见、仅成功刷新、finally 释放
   → L118 busy 守卫；L128 `const operationId = 'manual-' + Date.now()`（单次生成）；
     L137 `sourceDataVersion: operationId` 与 L138 第二实参 operationId 同源；
     L132–L142 try/catch 写 `FAILED: …`，L139 `await load()` 仅成功路径，L142 finally 释放；重试侧 L148 busy 守卫；
     pipelineOperationIdentity 6/6、pipelineRetryHandling 4/4 绿                                        满足

7) Products 定义 pageExportable = exportable.value && rows.value.length > 0，按钮与 doExport() 同用
   → Products.vue L126 `const pageExportable = computed(() => exportable.value && rows.value.length > 0)`；
     L9 按钮 `:disabled="!pageExportable" @click="doExport"`；L180–L181 `function doExport() { if (!pageExportable.value) return`  满足

8) applyFilters() 在重置页码/加载前做 handler 级 loading 拒绝；分页大小、翻页、排序保持 loading 锁定
   → L149–L153 `function applyFilters() { if (loading.value) return; page.value = 1; return load() }`；
     L7 分页大小 `:disabled="loading"`；
     L155–L158 goPage `if (loading.value || targetPage < 1) return`；
     L161–L162 toggleSort `if (!col || !col.sortKey || loading.value) return`；L60/L62 翻页按钮 loading 锁定  满足

9) Products 仍发送后端 page/size/sort，未重新引入本地全量分页/排序或非法排序字段
   → L89 `fetcher: (params, signal) => api.products(params, { signal })`；
     L141–L143 `page: page.value, size: pageSize.value, sort: \`${sortKey.value},${sortOrder.value}\``；
     L129–L135 仅 rank/pv/fav/cart/buy/heat 六个契约排序键；productServerPagination 6/6 绿              满足

10) 共享 CSV freshness/空行 fail-closed 保持绿
   → exportFreshnessGuard.test.js 6/6 PASS（exit=0）                                                    满足
```

## Evidence boundary（计划 §8，本 R1 不证明）

- 真实浏览器时序/DOM 交互；
- 真实 HTTP 竞态；
- 实际采集/流水线编排行为；
- 后端幂等性/状态机行为；
- 数据库写入与 3307 运行时；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-mr1-web-verify.log（28694 bytes, exit=0）
构建产物:            web/dist（gitignored，本 R1 vite build 已刷新：20:02:12）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 7748caf8…，测试前后 clean）
保留的失败证据:      本地结果已被本 R1 覆盖为当前批次；失败批次原始结果完整保留在
                     verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md @ 0c46b9c
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §10 PASS rule 逐条）：

- checked-out SHA 精确等于 `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` — 满足；
- 六个定向套件全部按预期通过（5/5、6/6、6/6、6/6、4/4、6/6，合计 33/33），零 failed/cancelled — 满足；
- 全量 Web 门禁 exit 0，274/274，零 failed/cancelled/skipped，无未解释计数漂移 — 满足；
- Vite production build 实际执行并 PASS（672 modules / 4.55s，dist 已刷新）— 满足；
- 计划 §7 十项语义复核逐条满足 — 满足；
- 工作区执行前后均 clean — 满足；
- 验证期间未做任何修复（R1 的修复已在被测 SHA 内，属被测对象）— 满足。
