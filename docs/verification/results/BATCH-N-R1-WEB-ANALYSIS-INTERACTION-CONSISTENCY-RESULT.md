# BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `4ae97e69fcc5cd924ebedb28667db6e9ffb29fde`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本 R1 定义）
  - 永久计划：`docs/verification/batches/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-PLAN.md`（已完整读取并按 §1–§11 执行）
- Batch ID: `BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- Tested commit: `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-18 09:47:12 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-18 09:47:00 +08:00] align overview export hardening guard`
  - **注意**：`origin/feature/v3-development` 当时为 `4ae97e69fcc5cd924ebedb28667db6e9ffb29fde`（文档提交），本批**未测该 HEAD**，只测批次指定 SHA。
- Failed predecessor（保留，未覆盖）：`BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY` @ `58411f92a8e5591c435f5597143896f4fadac020` / FAIL_NEW_REGRESSION
  - raw result：`verification-results:docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md` @ `bd4f41d46cd021177f85792d52c4cdc5e36326f6`
- Accepted predecessor before N：`BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION` @ `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` / PASS
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- R1 修复范围（只读核对，相对修复前开发 HEAD `dcc15740a70bdeb12b65b18d44863b4807f702ca`）：**仅 2 个测试文件**
  - `web/tests/rfmMatrixOwnership.test.js`（+2/−1）
  - `web/tests/postJr1WebHardening.test.js`（+9/−6）
  - 生产文件（`Rfm.vue`、`Overview.vue`、`Sales.vue`、`Behavior.vue`、后端、DB/Flyway、auth/security、AI SQL、决策状态机、3307、Spark/Hive/Flume）**零改动**。

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
git fetch origin                                                exit=0   dcc1574..4ae97e6  feature/v3-development
git checkout --detach 0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d  exit=0
git rev-parse HEAD                                              exit=0   = 0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d
git status --short                                              exit=0   空（clean）

cd web
node --test tests/analysisFilterInteractionHardening.test.js    exit=0   8 tests / 8 pass / 0 fail / 0 cancelled
node --test tests/exportFreshnessGuard.test.js                  exit=0   6 tests / 6 pass / 0 fail / 0 cancelled
node --test tests/rfmMatrixOwnership.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/metricDefinitionVersionDisplay.test.js        exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/postJr1WebHardening.test.js                   exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/loginInteractionHardening.test.js             exit=0   3 tests / 3 pass / 0 fail / 0 cancelled
node --test tests/aiHistoryConcurrency.test.js                  exit=0   5 tests / 5 pass / 0 fail / 0 cancelled
node --test tests/baseChartResize.test.js                       exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/opsInteractionHardening.test.js               exit=0   7 tests / 7 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   296 tests / 296 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 4.66s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史；未覆盖 Batch N 失败结果。

## Targeted suites

```text
原 Batch N 组
  analysisFilterInteractionHardening.test.js  8/8 PASS（expected 8/8）
  exportFreshnessGuard.test.js                6/6 PASS（expected 6/6）
  rfmMatrixOwnership.test.js                  4/4 PASS（expected 4/4）  ← Batch N 失败项，R1 修复后转绿
  rfmObservationWindow.test.js                4/4 PASS（expected 4/4）
  rfmSnapshotPinning.test.js                  4/4 PASS（expected 4/4）
  metricDefinitionVersionDisplay.test.js      4/4 PASS（expected 4/4）
  postJr1WebHardening.test.js                 4/4 PASS（expected 4/4）  ← Batch N 失败项，R1 修复后转绿
继承的 post-N 低风险簇
  loginInteractionHardening.test.js           3/3 PASS（expected 3/3）
  aiHistoryConcurrency.test.js                5/5 PASS（expected 5/5）
  baseChartResize.test.js                     4/4 PASS（expected 4/4）
  opsInteractionHardening.test.js             7/7 PASS（expected 7/7）
定向合计 53/53，全部 exit 0（与计划 §6 完全一致）
```

## Full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=296  passed=296  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=296；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 4.66s
```

- Expected 296 confirmed: **YES**（Batch N 282 + post-N 继承簇净新增 14（`loginInteractionHardening` +3、`aiHistoryConcurrency` +5、`baseChartResize` +4、`opsInteractionHardening` +2）= 296；R1 只改写既有断言，未增删用例）。
- Actual total = **296**；passed = 296；Failed / Cancelled / Skipped = **0 / 0 / 0**；count drift = **0（无未解释偏差）**。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 4.66s`；`web/dist` 最后写入时间由 `2026-09-17 20:02:12` 刷新为 `2026-09-18 09:52:48`（本 R1 执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**（Batch N 的 2 条新回归在本 R1 全部消除）

## Regression resolution（Batch N 失败项 → R1 结果）

```text
失败 1（Batch N @58411f9）tests/rfmMatrixOwnership.test.js:32
  旧断言：assert.match(source, /function doExport\(\) \{[\s\S]*if \(!exportable\.value\) return/)
R1 修复（74e9c0f）：
  assert.match(source, /const segmentExportable = computed\(\(\) => exportable\.value && segmentRows\.value\.length > 0\)/)
  assert.match(source, /function doExport\(\) \{[\s\S]*if \(!segmentExportable\.value\) return/)
  → 由「页面级 exportable 字面量」升级为「实际 segmentRows 子集谓词」，同时补齐谓词定义断言，未放宽强度。

失败 2（Batch N @58411f9）tests/postJr1WebHardening.test.js:35
  旧断言：Sales 与 Overview 循环 assert.match(body, /if \(!exportable\.value\) return/)
R1 修复（0c7af0d）：拆分为两条独立断言
  Sales  ：仍 assert.match(salesBody, /if \(!exportable\.value\) return/)，保持页面级 exportable 契约；
  Overview：assert.match(overview, /const metricExportable = computed\(\(\) => exportable\.value && cards\.value\.length > 0\)/)
            + assert.match(overviewBody, /if \(!metricExportable\.value\) return/)
  → 两条分支各自绑定当前正确谓词，Sales 契约未被放松。

生产语义保全核对：`git diff --name-status dcc1574 0c7af0d` 仅列出上述两个测试文件；
Rfm.vue L178/L187/L192、Overview.vue L179/L187/L188、Sales.vue L11/L141/L142 与本批修复前逐字节一致。
结果：两条用例转绿，定向 53/53，全量门禁 296/296，Vite build 实际执行并通过。
```

## Required semantic checks（计划 §8，逐条）

```text
1) 原 Batch N §7 第 1–9 项仍成立
   → `git diff --name-status 58411f9 0c7af0d -- web/src/views/{Rfm,Overview,Sales,Behavior}.vue` 输出为空，
     四个分析页生产源码自 Batch N 以来未改动，N 的 §7 结论原样延续（详见本批已归档的 Batch N 报告）   满足

2) RFM segmentExportable = exportable && segmentRows.length > 0；按钮与 doExport 同用；CSV 行仍取 segmentRows
   → Rfm.vue L178 `const segmentExportable = computed(() => exportable.value && segmentRows.value.length > 0)`；
     L7 `:disabled="!segmentExportable" @click="doExport"`；L187 `if (!segmentExportable.value) return`；
     L192 `rows: segmentRows.value.map((s) => […])`                                                    满足

3) Overview metricExportable = exportable && cards.length > 0；按钮与 doExport 同用；CSV 行由 cards 派生
   → Overview.vue L179 `const metricExportable = computed(() => exportable.value && cards.value.length > 0)`；
     L11 `:disabled="!metricExportable" @click="doExport"`；L187 `if (!metricExportable.value) return`；
     L188 `const rows = cards.value.map((c) => […])`                                                    满足

4) Sales 仍用页面级 exportable 保护其主要趋势数据集
   → Sales.vue L11 `:disabled="!exportable" @click="doExport"`；L141–L142 `function doExport() { if (!exportable.value) return`   满足

5) 两条修复后的测试不再要求过时字面量，改为钉住更严格的当前谓词
   → rfmMatrixOwnership.test.js L32–L33（segmentExportable 定义 + handler 守卫）；
     postJr1WebHardening.test.js L34（Sales 页面级）、L39–L40（Overview metricExportable 定义 + handler 守卫）  满足

6) Login 在途输入锁仍在
   → Login.vue L9/L14 用户名与密码 `:disabled="loading"`；L17 登录按钮 `:disabled="loading"`；
     L32 `const loading = ref(false)`；L36 `if (loading.value) return`；L41 置真 / L52 释放           满足

7) AI history 刷新保持序号 + Abort 防护与卸载取消
   → AiAssistant.vue L219 `let historySeq = 0`；L330 `const mySeq = ++historySeq`；
     L331–L332 旧请求 `abort()` 并新建 `AbortController`；L335 请求带 `signal`；
     L336/L339 晚到响应按序号丢弃；L342 仅在最新序号时清空 controller；
     L424–L429 `onUnmounted` 中 `askSeq += 1` / `askController.abort()` / `historySeq += 1` / `historyController.abort()`  满足

8) BaseChart 在高度变化后于 DOM 更新后 resize
   → BaseChart.vue L14 引入 `nextTick`/`watch`；L50 `const resize = () => chart && chart.resize()`；
     L63 `watch(() => props.height, () => nextTick(resize))`；L52 `onMounted` 初始化、L60 option 深度 watch  满足

9) Ops 整页刷新与 admin 写操作经 loading || busy 双向互斥
   → Ops.vue L398–L399 `async function loadAll() { if (loading.value || busy.value) return`；
     写操作 L445 `createUser`、L460 `toggle`、L476 `resetPwd` 均为 `if (busy.value || loading.value) return` 后置 busy；
     按钮层 L10/L27/L52–L57 全部 `:disabled="loading || busy"`；L233 `const busy = ref(false)`     满足

10) 共享 CSV freshness / stale / 空行 fail-closed 保持绿
   → exportFreshnessGuard.test.js 6/6 PASS（exit=0）                                                  满足

11) 全量 Web 门禁 296/296 且 Vite production build 通过
   → npm run verify exit=0；296 tests / 296 pass / 0 fail / 0 cancelled / 0 skipped；
     vite v5.4.21 build PASS（672 modules / 4.66s，dist 已刷新）                                      满足
```

## Evidence boundary（计划 §9，本 R1 不证明）

- 真实浏览器时序/DOM 交互与点击竞态；
- 真实 HTTP 并发与晚到响应竞态；
- 浏览器内真实 CSV 下载行为；
- 后端运行时/数据库写入与 3307；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-nr1-web-verify.log（30564 bytes, exit=0）
构建产物:            web/dist（gitignored，本 R1 vite build 已刷新：2026-09-18 09:52:48）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 0c7af0d…，测试前后 clean）
保留的失败证据:      Batch N 失败原始结果完整保留于
                     verification-results:docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md @ bd4f41d
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §11 PASS rule 逐条）：

- checked-out SHA 精确等于 `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d` — 满足；
- 定向套件 53/53、零 failed/cancelled/skipped — 满足；
- 全量 Web 门禁 exit 0、296/296、零 failed/cancelled/skipped、无未解释漂移 — 满足；
- Vite production build 实际执行并 PASS（672 modules / 4.66s，dist 已刷新）— 满足；
- 计划 §8 十一项语义复核逐条满足 — 满足；
- 工作区执行前后均 clean — 满足；
- 验证期间未做任何修复（R1 的两处修复均在被测 SHA 内，属被测对象）— 满足。
