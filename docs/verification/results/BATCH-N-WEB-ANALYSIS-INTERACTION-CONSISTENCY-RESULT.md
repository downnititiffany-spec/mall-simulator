# BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `6d82ea6897a5105649fafc2d9954bdd6d71fe35c`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- Tested commit: `58411f92a8e5591c435f5597143896f4fadac020`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 20:09:48 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 20:09:35 +08:00] extend analysis interaction hardening coverage`
- Accepted predecessor: `BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION` @ `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` / PASS（本批基线 274/274）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- Scope: Behavior / Sales / Overview / RFM 分析页 loading 交互锁 + 实际导出子集资格统一收口；新增 `web/tests/analysisFilterInteractionHardening.test.js`

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
git fetch origin                                                exit=0   fb8dd64..6d82ea6  feature/v3-development
git checkout --detach 58411f92a8e5591c435f5597143896f4fadac020  exit=0
git rev-parse HEAD                                              exit=0   = 58411f92a8e5591c435f5597143896f4fadac020
git status --short                                              exit=0   空（clean）

cd web
node --test tests/analysisFilterInteractionHardening.test.js    exit=0   8 tests / 8 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/rfmMatrixOwnership.test.js                    exit=1   4 tests / 3 pass / 1 fail / 0 cancelled   ← 失败
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/metricDefinitionVersionDisplay.test.js        exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=1   282 tests / 280 pass / 2 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        Vite build 未执行（npm test 失败即短路）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史；未做任何修复。

## Targeted suites

```text
analysisFilterInteractionHardening.test.js  8/8 PASS（expected 8/8）
exportFreshnessGuard.test.js                6/6 PASS（expected 6/6）
rfmMatrixOwnership.test.js                  3/4 FAIL（expected 4/4）  ← 新回归
rfmObservationWindow.test.js                4/4 PASS（expected 4/4）
rfmSnapshotPinning.test.js                  4/4 PASS（expected 4/4）
metricDefinitionVersionDisplay.test.js      4/4 PASS（expected 4/4）
定向合计 29/30（expected 30/30），rfmMatrixOwnership exit=1，其余 exit=0
```

新测试文件 `analysisFilterInteractionHardening.test.js` 8 条全部通过（本批目标行为本身成立）：

```text
✔ Behavior 日期输入在 loading 期间锁定，load handler 自身也拒绝重入
✔ Behavior 漏斗 CSV 资格要求整页 ready 且 stages 子集非空
✔ Sales 日期输入在 loading 期间锁定
✔ Sales load handler 在重置页码和请求前拒绝 loading 重入
✔ Overview 日期输入与 load handler 在 loading 期间 fail-closed
✔ Overview 指标 CSV 只在 cards 子集非空时允许导出
✔ RFM load handler 在清空降级错误与请求前拒绝 loading 重入
✔ RFM CSV 只在真实 segmentRows 子集非空时允许导出
```

## Full gate

```text
command: cd web; npm run verify          exit=1
node --test "tests/**/*.test.js": total=282  passed=280  failed=2  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=280；`✖` 行数=5（2 个用例 + 3 行汇总/失败清单）
vite production build:            未执行（npm test 失败即短路，未进入 vite build）
```

- Expected 282 confirmed: **YES**（Batch M-R1 基线 274 + 本批新增 `analysisFilterInteractionHardening.test.js` 8 条 = 282，无增删既有用例）。
- Actual total = **282**；passed = 280；Failed = **2**；Cancelled / Skipped = 0 / 0；count drift = **0（计数本身无偏差，失败是内容而非计数）**。
- Vite build 未执行证据：日志无 `vite v5.4.21 building for production...` 行；`web/dist` 最后写入时间仍为 `2026-09-17 20:02:12`（Batch M-R1 构建），本批运行时刻为 `20:38:07`，未被刷新。
- Known environmental failures: **N/A for this Web-only batch**（已知环境红 `IngestionManifestRuntimePatrolTest` 属 Java 批次，不参与本批）
- New failures: **2 条**（见下节）

## New regressions（精确失败项）

### 失败 1 — `web/tests/rfmMatrixOwnership.test.js:29`（定向与全量均失败）

```text
用例：图表与 CSV 都消费同一个 segmentRows，不再产生第二套类别所有者
断言（L32）：assert.match(source, /function doExport\(\) \{[\s\S]*if \(!exportable\.value\) return/)
报错：AssertionError [ERR_ASSERTION]: The input did not match the regular expression
      /function doExport\(\) \{[\s\S]*if \(!exportable\.value\) return/
位置：tests\rfmMatrixOwnership.test.js:32
```

实际被测源码（`web/src/views/Rfm.vue` @ 本 SHA）：

```text
L178: const segmentExportable = computed(() => exportable.value && segmentRows.value.length > 0)
L186: function doExport() {
L187:   if (!segmentExportable.value) return
```

### 失败 2 — `web/tests/postJr1WebHardening.test.js:30`（定向未覆盖，全量门禁暴露）

```text
用例：Sales 与 Overview 的导出 handler 自身也执行 exportable fail-closed
断言（L35）：assert.match(body, /if \(!exportable\.value\) return/, `${name} handler 不能只依赖按钮 disabled`)
报错：AssertionError [ERR_ASSERTION]: Overview handler 不能只依赖按钮 disabled
位置：tests\postJr1WebHardening.test.js:35（用例起始 L30）
说明：该用例对 Sales 与 Overview 循环断言；Sales 仍保留 `if (!exportable.value) return`（L142）故通过，
      Overview 改为 `if (!metricExportable.value) return`（L187）故在 Overview 一轮失败。
```

实际被测源码（`web/src/views/Overview.vue` @ 本 SHA）：

```text
L179: const metricExportable = computed(() => exportable.value && cards.value.length > 0)
L186: function doExport() {
L187:   if (!metricExportable.value) return
```

### 根因（仅陈述事实，未修复）

```text
本批 adf…/6490ad2…58411f9 在 Overview 与 RFM 把 doExport 的 handler 级谓词
从页面级 `exportable` 收敛为「整页 ready 且实际导出子集非空」的
`metricExportable` / `segmentExportable`（计划 §3 明确要求），语义方向正确；
但两条**已验收**的文本型源码不变量守卫仍要求旧字面量 `if (!exportable.value) return`：
  - rfmMatrixOwnership.test.js:32（既有验收用例）
  - postJr1WebHardening.test.js:35（Batch K 验收用例）
本批新增的 8 条测试断言的是新谓词，说明新增用例已同步而这两条既有守卫未同步。
本批 diff 未包含这两个测试文件：git diff --name-status 7748caf..58411f9 中测试侧仅新增
web/tests/analysisFilterInteractionHardening.test.js。
与 Batch M 同类问题（生产标识符/谓词演进后，既有源码不变量守卫文本失配）。
```

## Required semantic checks（计划 §7，逐条）

```text
1) Behavior from/to 在 loading 期间禁用；load() 起始拒绝 loading 重入
   → Behavior.vue L7 `<input type="date" v-model="from" :disabled="loading"`、L9 同（to）；
     L123–L125 `const load = () => { if (loading.value) return; return analysis.load({ from: from.value, to: to.value }) }`   满足

2) Behavior CSV 用 funnelExportable = exportable.value && stages.value.length > 0；按钮与 doExport 共用；rows 仍取 stages
   → L121 `const funnelExportable = computed(() => exportable.value && stages.value.length > 0)`；
     L11 `:disabled="!funnelExportable" @click="doExport"`；L128–L129 `function doExport() { if (!funnelExportable.value) return`；
     L134 `rows: stages.value.map((s) => [s.label || s.stage, s.stage, s.users, …])`                          满足

3) Sales from/to 在 loading 期间禁用；load() 在 page.value = 1 与 analysis.load 之前拒绝重入
   → L7/L9 `:disabled="loading"`；L134–L137 `const load = () => { if (loading.value) return; page.value = 1;
     return analysis.load({ from: from.value, to: to.value }) }`（guard 先于页码重置，再于请求）              满足

4) Sales 导出语义未变：仍受页面级 exportable 保护并导出 sortedRows（页面主趋势数据集）
   → L11 `:disabled="!exportable" @click="doExport"`；L141–L142 `function doExport() { if (!exportable.value) return`    满足

5) Overview from/to 在 loading 期间禁用且 load() 拒绝 loading 重入
   → L7/L9 `:disabled="loading"`；L182–L185 `const load = () => { if (loading.value) return;
     return analysis.load({ from: from.value, to: to.value }) }`                                            满足

6) Overview CSV 用 metricExportable = exportable.value && cards.value.length > 0；按钮与 handler 共用；rows 由 cards 派生
   → L179、L11 `:disabled="!metricExportable"`、L186–L187、rows 由 cards 派生                               满足

7) RFM load() 在清空 usersError 或再次请求前拒绝 loading 重入
   → Rfm.vue L180–L184 `const load = () => { if (loading.value) return; usersError.value = '';
     return analysis.load({}) }`（guard 先于 usersError 清空，再于请求）                                    满足

8) RFM CSV 用 segmentExportable = exportable.value && segmentRows.value.length > 0；按钮与 handler 共用；rows 由 segmentRows 派生
   → L178、L7 `:disabled="!segmentExportable" @click="doExport"`、L186–L187、L192 `rows: segmentRows.value.map(…)`  满足

9) RFM 归属语义未变：rfmMatrix 仍是主发布者，降级用真实 rfmSegments，前端不伪造第二套固定 0 填充矩阵
   → L160–L171 `const matrix = Array.isArray(data.value.rfmMatrix) ? …`；`const source = matrix.length ? matrix :
     (Array.isArray(data.value.rfmSegments) ? …)`；无 `SEGMENTS` 常量；rfmMatrixOwnership 前 3 条用例通过   满足

10) 共享 CSV freshness / stale / 空行 fail-closed 保持绿，且全量 Web 门禁/构建按预期 282 通过
   → exportFreshnessGuard.test.js 6/6 PASS（满足前半）；
     全量门禁 exit=1、280/282、Vite build 未执行 ⇒ **后半不满足（本批 FAIL 判定依据）**                    不满足
```

## Regression baseline comparison

```text
BATCH-M-R1 @ 7748caf（已验收 PASS）：274 tests / 274 pass / 0 fail；Vite build 执行并成功
BATCH-N    @ 58411f9（本批）      ：282 tests / 280 pass / 2 fail；Vite build 未执行
计数层面无漂移（274 + 8 = 282 精确成立）；失败为两条既有已验收守卫的文本失配。
本批定向计划未列 postJr1WebHardening.test.js，故该条仅在 §4 全量门禁步骤暴露；
按计划 §4 命令顺序与 §10 PASS 规则，全量门禁失败即判定本批不通过。
```

## Evidence boundary（计划 §8，本批不证明）

- 真实浏览器输入禁用时序/点击竞态；
- 真实 HTTP 并发；
- 源码契约之外的后端快照行为；
- 浏览器内真实 CSV 下载行为；
- 数据库写入与 3307 运行时；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-n-web-verify.log（51166 bytes, exit=1）
构建产物:            web/dist（gitignored，本批未刷新：仍 2026-09-17 20:02:12）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 58411f92…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: FAIL_NEW_REGRESSION
```

判定依据（计划 §10 PASS rule 逐条）：

- checked-out SHA 精确等于 `58411f92a8e5591c435f5597143896f4fadac020` — 满足；
- 定向套件 30/30、零 failed/cancelled — **不满足**（29/30，`rfmMatrixOwnership` 1 fail）；
- 全量 Web 门禁 exit 0、282/282、零 failed — **不满足**（exit=1，280/282，2 fail）；
- Vite production build 实际执行并 PASS — **不满足**（未执行，测试失败短路）；
- 计划 §7 十项语义复核 — 第 1–9 项满足，第 10 项后半不满足；
- 工作区执行前后 clean — 满足；
- 验证期间未做任何修复 — 满足（本报告按 §10「record the exact failure and stop」停止）。
