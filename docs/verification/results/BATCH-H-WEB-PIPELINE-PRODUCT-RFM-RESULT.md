# BATCH-H-WEB-PIPELINE-PRODUCT-RFM — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `8468c3d90af99c187e9debad6accd55d40dfef61`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-H-WEB-PIPELINE-PRODUCT-RFM`
- Tested commit: `20db9072c37e20ebecf6648f55d002d36aa452b0`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 12:40:35 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 12:40:25 +08:00] guard backend-owned RFM matrix categories`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空；仅 `web/dist` 等 gitignored 构建产物变化）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰）
- Scope: S3-72 Pipeline retry 失败处理 + S3-73 商品页服务端分页/排序 + S3-74 RFM matrix 类目唯一属主 + 关键回归

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
pwsh: 7.6.6
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未运行（计划 §6 明确不运行 default/spark/isolated/3307 与真实浏览器 E2E）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   31fbbaf..8468c3d  feature/v3-development
git checkout --detach 20db9072c37e20ebecf6648f55d002d36aa452b0  exit=0
git rev-parse HEAD                                              exit=0   = 20db9072c37e20ebecf6648f55d002d36aa452b0
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineRetryHandling.test.js                 exit=0   4 tests / 4 pass / 0 fail
node --test tests/productServerPagination.test.js               exit=0   5 tests / 5 pass / 0 fail
node --test tests/rfmMatrixOwnership.test.js                    exit=0   4 tests / 4 pass / 0 fail
node --test tests/pipelineLocalBusinessDate.test.js \
            tests/decisionExecutionContextDisplay.test.js       exit=0   9 tests / 9 pass / 0 fail
npm run verify                                                  exit=0   239 tests / 239 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        672 modules transformed；built in 6.84s
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未触碰被测分支 Git 历史。

## S3-72 — pipelineRetryHandling.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ FAILED run 的重试按钮受 busy 保护，避免重复提交 retry
✔ retry 进入 busy、清理旧结果，并在 finally 中恢复 busy
✔ retry 成功才刷新列表，失败时转成可见 FAILED 结果而不是未处理 Promise
✔ 失败结果没有 runId 时不渲染 run#undefined
```

逐条确认（`web/src/views/Pipeline.vue` 独立复核）：

1. FAILED run 的重试按钮受 `busy` 保护：L60 `<button v-if="r.status === 'FAILED'" @click="retry(r.id)" :disabled="busy" …>重试</button>` — 确认。
2. `retry()` 自身 fail-closed：L139 `async function retry(id) {`，L140 `if (busy.value) return` — 确认。
3. retry 开始时清理旧 `runResult`、成功后刷新列表：L142 `runResult.value = null`；L144 `runResult.value = await api.retryPipelineRun(id)`，随后 `await load()`（测试断言二者相邻顺序） — 确认。
4. 失败被 catch 转成可见结果：L147 `runResult.value = { status: 'FAILED: ' + (e.message || e) }`，无未处理 Promise — 确认。
5. `finally` 恢复 busy：L148–L149 `} finally { busy.value = false }`（另 L134–L135 同结构用于 run 提交路径） — 确认。
6. 无 runId 不显示 `run#undefined`：L29 `<template v-if="runResult.runId">流水线 run#{{ runResult.runId }}：{{ runResult.status }}</template>`，L30 `<template v-else>{{ runResult.status }}</template>`；旧的无条件拼接写法已不存在（测试 `doesNotMatch`） — 确认。

## S3-73 — productServerPagination.test.js

```text
counts: 5 tests / 5 pass / 0 fail        expected 5/5 → 符合
result: PASS
```

```text
✔ 商品页请求显式携带 page/size/sort，不再把单页结果做本地分页
✔ 分页按钮使用后端 page/hasMore 元数据驱动
✔ 只开放后端契约允许的商品排序字段，名称和转化率不伪造本地全量排序
✔ 切换排序会回到第 1 页并重新请求后端
✔ 导出明确限定当前页，并继续受 exportable 门禁保护
```

逐条确认（`web/src/views/Products.vue` 独立复核）：

1. 显式发送 `page` / `size` / `sort`：L136–L142 `requestParams()` 返回 `{ page: page.value, size: pageSize.value, sort: \`${sortKey.value},${sortOrder.value}\` }`，L144 `const load = () => analysis.load(requestParams())` — 确认。
2. 不再本地排序/分页：`Products.vue` 内 `sortRows` / `paginate` 出现次数为 0（测试对整文件 `doesNotMatch`） — 确认。
3. 上一页/下一页由后端元数据驱动：L97 `responsePage = computed(() => isNumeric(data.value.page) ? Number(data.value.page) : page.value)`、L101 `hasMore = computed(() => data.value.hasMore === true)`；L60 `:disabled="loading || responsePage <= 1"` + `goPage(responsePage - 1)`、L62 `:disabled="loading || !hasMore"` + `goPage(responsePage + 1)` — 确认（`hasMore` 严格取自后端；`responsePage` 优先后端 `page`，仅当后端未回 `page` 时回退到本地请求页，属显式降级而非本地重算）。
4. 可排序字段严格限于契约白名单：`Products.vue` 中带 `sortKey` 的列仅 L126 `rank`、L128 `pv`、L129 `fav`、L130 `cart`、L131 `buy`、L132 `heat` — 确认。后端契约核对：`AnalysisService.SORT_FIELDS` = `rank/heat/pv/fav/cart/buy`（L320–L326），`resolveSort` 对未登记字段 fail-fast `PARAM_INVALID`（L350–L353）；前端未自造字段，`sort` 形式 `字段,asc|desc` 与后端 L337 说明一致。
5. 商品名称与转化率不伪装成服务端排序：L127 `{ key: 'productName', title: '商品' }`、L133 `{ key: 'conversionRate', title: '转化率' }` 均无 `sortKey`，且 L158 `toggleSort` 对无 `sortKey` 的列直接 return（不会改 `sortKey`/不会触发请求） — 确认。
6. 切换排序回第 1 页并重新请求：L157–L167 `toggleSort(col)` 内 L165 `page.value = 1`、L166 `return load()` — 确认。
7. 导出限定当前页且受 `exportable` 门禁：L9 `:disabled="!exportable"` + `导出当前页 CSV`；L176–L177 `doExport()` 首行 `if (!exportable.value) return`；L179 `baseName: \`product-analysis-page-${responsePage.value}\``；L182 `rows: rows.value.map(…)`（仅当前页行） — 确认。

## S3-74 — rfmMatrixOwnership.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ RFM 页面优先使用后端 rfmMatrix，而不是把前端固定类目追加到真实结果
✔ 旧响应缺少 rfmMatrix 时只展示真实 rfmSegments，不制造额外 0 人类目
✔ 矩阵行仅做字段搬运，缺失数值按既有空值语义处理
✔ 图表与 CSV 都消费同一个 segmentRows，不再产生第二套类别所有者
```

逐条确认（`web/src/views/Rfm.vue` 独立复核）：

1. `rfmMatrix` 存在时直接消费后端矩阵：L136–L140 `segmentRows` 计算属性 `const matrix = Array.isArray(data.value.rfmMatrix) ? …filter((s) => s && s.valueGroup) : []`，`const source = matrix.length ? matrix : (Array.isArray(data.value.rfmSegments) ? … : [])` — 确认。消费路径 L28 `v-for="s in segmentRows"`。
2. 前端不再维护 `SEGMENTS`：`Rfm.vue` 内 `const SEGMENTS =` 与 `...SEGMENTS` 出现次数为 0；L38 页面说明与 L134–L135 注释明确「八类补 0 口径由后端 `RfmService.rfmMatrix` 唯一维护；前端只搬运」 — 确认。
3. 缺 `rfmMatrix` 时仅展示真实 `rfmSegments`：降级分支为 `rfmSegments.filter((s) => s && s.valueGroup)`，不生成固定类目/0 人分组（测试 `doesNotMatch` 旧 `new Set([...list.map…重要价值…])` 写法）；L34 空数据显示「当前快照没有 RFM 分层数据」 — 确认。
4. 图表与 CSV 共用同一 `segmentRows`：L152 `rfmMatrixOption(segmentRows.value, segmentRows.value.map((s) => s.valueGroup), COLORS)`；L166 `rows: segmentRows.value.map(…)`；L161 `if (!exportable.value) return` 门禁保持 — 确认。
5. 只做搬运/空值处理，不重算 RFM：L142–L145 `valueGroup: s.valueGroup`、`users: … === undefined || === null ? 0 : s.users`、`amount: … === undefined ? null : s.amount`、`avgRecencyDays: … === undefined ? null : s.avgRecencyDays`；无分档、无阈值、无聚合计算 — 确认。

契约侧独立核对（只读，未修改后端）：`RfmService.VALUE_GROUPS`（L36）为八类全量类目，`AnalysisService.RfmData`（L184）同时返回 `rfmSegments` 与 `rfmMatrix`，后端测试断言 `rfmMatrix` 恒为 8 行且「真库无该分层 → 补 0」（`AnalysisServiceTest` L567–L570）。即补 0 口径确由后端唯一持有，前端不得再追加类目。

## 关键回归

```text
counts: 9 tests / 9 pass / 0 fail        expected 9/9 → 符合
result: PASS
  pipelineLocalBusinessDate.test.js       5/5
  decisionExecutionContextDisplay.test.js 4/4
```

Pipeline retry 改动未破坏上一批的本地业务日修复；决策执行上下文展示行为保持稳定。

## Web 完整门禁

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=239  passed=239  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=239；`✖` 行数=0
vite production build:            PASS（672 modules transformed；built in 6.84s）
```

- Expected 239 confirmed: **YES**
- Actual total: **239**（Batch G 226 + `pipelineRetryHandling` 4 + `productServerPagination` 5 + `rfmMatrixOwnership` 4；以实测汇总为准）
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## New failures

```text
无。239/239 全绿，无 failed/cancelled/skipped/todo，无新增回归。
```

## Unverified runtime areas（本批未覆盖，不得据此宣称已验收）

- 真实浏览器 retry 双击/网络异常行为（本批为源码级守卫断言）；
- `/pipeline-runs/{id}/retry` 真实 HTTP 与后端状态机；
- 商品分页/排序真实 HTTP 数据顺序与数据库结果；
- RFM 真 HTTP 返回矩阵与页面 DOM 渲染；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-h-web-verify.log（25478 bytes, exit=0）
构建产物:            web/dist（gitignored，由本次 vite build 重写）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 20db9072…）
本批改动范围:        web/src/views/{Pipeline,Products,Rfm}.vue + 3 个新测试（237 insertions / 88 deletions）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §10 PASS rule）：

- 精确 SHA 正确：`20db9072c37e20ebecf6648f55d002d36aa452b0` — 满足；
- S3-72 4/4 — 满足；
- S3-73 5/5 — 满足；
- S3-74 4/4 — 满足；
- 关键回归 9/9 — 满足；
- `npm run verify` 全绿（239/239，failed/cancelled=0）且 Vite build PASS — 满足；
- 无新增回归 — 满足；
- 结果同时落本地与 GitHub 结果文件 — 满足。
