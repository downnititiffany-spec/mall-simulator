# BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `6970b52f758d341f61e299a5c6f5c1bf8545e030`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`（已完整读取并按 §1–§9 执行）
- Batch ID: `BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW`
- Tested commit: `b5e4972bdb272ca476be20b1638b0c78d905438b`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 12:54:07 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 12:53:55 +08:00] guard RFM observation window display`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- Scope: S3-75 人工流水线操作 identity 一致性 + S3-76 RFM 观察窗口展示 + 关键回归

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
pwsh: 7.6.6
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未运行（计划 §6 明确不运行 default/spark/isolated/3307 与真实浏览器/HTTP/Spark-Hive-Flume E2E）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   8468c3d..6970b52  feature/v3-development
git checkout --detach b5e4972bdb272ca476be20b1638b0c78d905438b  exit=0
git rev-parse HEAD                                              exit=0   = b5e4972bdb272ca476be20b1638b0c78d905438b
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineOperationIdentity.test.js             exit=1   4 tests / 3 pass / 1 FAIL   ← 失败
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail
node --test tests/pipelineRetryHandling.test.js \
            tests/pipelineLocalBusinessDate.test.js \
            tests/rfmMatrixOwnership.test.js                    exit=0   13 tests / 13 pass / 0 fail
npm run verify                                                  exit=1   247 tests / 246 pass / 1 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        npm test 非零退出 ⇒ npm run build 未执行（Vite build 未验证）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未触碰被测分支 Git 历史。

## S3-75 — pipelineOperationIdentity.test.js 【FAIL】

```text
counts: 4 tests / 3 pass / 1 fail        expected 4/4 → 不符合
result: FAIL
```

```text
✔ runOnce 自身受 busy fail-closed 保护，不能只依赖按钮 disabled
✖ 一次人工流水线触发只生成一个 operationId      ← FAIL
✔ sourceDataVersion 与 Idempotency-Key 复用同一 operationId
✔ runOnce 仍保持成功刷新、失败可见和 finally 释放 busy
```

失败断言原文（Node test runner 输出，未删改）：

```text
test at tests\pipelineOperationIdentity.test.js:26:1
✖ 一次人工流水线触发只生成一个 operationId (0.6423ms)
  AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:

  2 !== 1

      at TestContext.<anonymous> (file:///D:/Develop_code/GraduationProject-wt/v3-dev/web/tests/pipelineOperationIdentity.test.js:29:10)
    code: 'ERR_ASSERTION',
    actual: 2,
    expected: 1,
    operator: 'strictEqual'
```

定位（只读复核，未修改任何文件）：

- 断言语句：`tests/pipelineOperationIdentity.test.js:29`
  `assert.equal((body.match(/Date\.now\(\)/g) || []).length, 1)`
  其中 `body = functionBody('runOnce', 'retry')` 为 `web/src/views/Pipeline.vue` 中 `async function runOnce(` 到 `async function retry(` 的**原始文本切片**（含注释）。
- 实测该切片内 `Date.now()` 文本出现 **2** 次：
  1. `Pipeline.vue` L124（可执行）：`const operationId = 'manual-' + Date.now()`
  2. `Pipeline.vue` L123（**行注释**）：`// 两者描述的是同一逻辑操作，不能各自 Date.now() 导致毫秒级不一致，破坏排障/证据关联。`
- 独立复算（在内存中按行剥离 `//` 注释，未修改仓库文件）：

```text
runOnce 切片原始文本 Date.now() 次数 = 2   ← 测试断言要求 1，故失败
按行剥离 // 注释后可执行 Date.now() 次数 = 1
真实可执行行：const operationId = 'manual-' + Date.now()
runOnce 内 operationId 使用点：
  const operationId = 'manual-' + Date.now()
  businessTime: businessDate.value + 'T00:00:00', sourceDataVersion: operationId
  }, operationId)        ← Idempotency-Key 参数
```

结论与归类（供总控判定，Code Agent 不做修复）：

- **失败性质：本批新增守卫测试的文本计数与源码注释冲突**（`// … Date.now() …` 被 `match` 计入），不是运行时行为回归；
- 计划 §2 的**功能意图**在代码上成立：`runOnce` 只有 1 处可执行 `Date.now()`，`sourceDataVersion` 与 `api.createPipelineRun(..., operationId)` 复用同一 `operationId`（对应计划 §2.1/§2.3/§2.5 的三条断言均 PASS）；
- 但按计划 §9 的显式 PASS 条件（S3-75 必须 4/4），本批**不满足 PASS**；按协议 §6 要求不得因“其余全绿”而写成 PASS；
- 既有基线未回归：Batch H 的 239 条全部仍为绿（见下方全量门禁）。

## S3-76 — rfmObservationWindow.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ RFM 只从 rfm 响应搬运 periodStart/periodEnd，不从 users 聚合接口猜观察期
✔ 观察期必须起止都存在才展示完整窗口，任一缺失时显示未提供
✔ RFM defaults 显式包含 periodStart/periodEnd null，不能制造默认窗口
✔ RFM CSV 同步导出后端观察期起止，不重算日期
```

逐条确认（`web/src/views/Rfm.vue` 只读复核）：`periodStart`/`periodEnd` 由 `/analysis/rfm` 响应的 `rfm.data` 搬运（defaults 显式 `null`）；起止同时存在才展示 `start 至 end`，任一缺失显示「未提供」；页面顶部展示观察期；CSV 增加「观察期开始/观察期结束」并使用后端原值；源码中无 `new Date()` 重算观察期。4/4 PASS，与计划 §3 一致。

## Regression

```text
counts: 13 tests / 13 pass / 0 fail      expected 13/13 → 符合
result: PASS
  pipelineRetryHandling.test.js      4/4   （Batch H retry fail-closed 未回归）
  pipelineLocalBusinessDate.test.js  5/5   （Batch G 本地业务日未回归）
  rfmMatrixOwnership.test.js         4/4   （后端唯一 RFM matrix 类目属主未回归）
```

## Web full gate

```text
command: cd web; npm run verify          exit=1
node --test "tests/**/*.test.js": total=247  passed=246  failed=1  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=246；`✖` 行数=3（1 条失败用例 + failing tests 汇总行）
vite production build:            NOT EXECUTED
```

- `npm run verify` = `npm test && npm run build`；`npm test` 以 1 退出（上述失败），因此 **`npm run build` 未执行**，本批 **Vite production build 未被验证**；
- 佐证：日志中只有 `> npm test && npm run build` 脚本行，无 `vite v…` / `modules transformed` / `built in` 输出；`web/dist` 最后写入时间为 `2026-09-17 12:42:51`（Batch H 的构建），晚于本批执行时刻仍保持该时间；
- Expected 247 confirmed: **YES（数量一致）**，但 passed = 246、failed = 1。
- Actual total: **247**（Batch H 239 + `pipelineOperationIdentity` 4 + `rfmObservationWindow` 4）
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **1 —— `pipelineOperationIdentity.test.js` 「一次人工流水线触发只生成一个 operationId」（源码文本计数 vs 行注释冲突，详见上文）**

## New failures

```text
1) tests/pipelineOperationIdentity.test.js:29
   assert.equal((body.match(/Date\.now\(\)/g) || []).length, 1)
   actual = 2, expected = 1
   原因：runOnce 原始文本切片同时计入 L123 行注释中的字面量 Date.now()；
   可执行调用仅 L124 一处，功能意图（单一 operationId 复用）成立。
   隔离复现：node --test tests/pipelineOperationIdentity.test.js → 3/4，同一断言同一数值；
   全量复现：npm run verify → 246/247，同一用例。
```

## Unverified runtime areas（本批未覆盖，不得据此宣称已验收）

- 真实浏览器人工触发的 identity 行为与重复点击/网络异常；
- `/pipeline-runs`（`Idempotency-Key`）真实 HTTP 与后端幂等语义；
- `/analysis/rfm` 真实 HTTP 返回的 `periodStart`/`periodEnd` 与页面 DOM；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E；
- **Vite production build（本批因 `npm test` 非零退出而未执行）**。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-i-web-verify.log（24603 bytes, exit=1）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ b5e4972b…，测试前后 clean）
本批改动范围:        web/src/views/{Pipeline,Rfm}.vue + 2 个新测试文件
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: FAIL_NEW_REGRESSION
```

判定依据（计划 §9 PASS rule 逐条）：

- 精确 SHA 正确：`b5e4972bdb272ca476be20b1638b0c78d905438b` — 满足；
- S3-75 4/4 — **不满足（3/4，1 条新增守卫测试失败）**；
- S3-76 4/4 — 满足；
- 回归 13/13 — 满足；
- Web full gate 全绿 — **不满足（246/247，failed=1）**；Vite build PASS — **未执行，无法判定**；
- 无新增回归（既有用例层面）— 满足：Batch G/H 既有 239 条全部仍绿，失败项为本批新增测试；
- 结果本地/GitHub 双落盘 — 见下段。

备注（仅陈述事实，不含修复）：失败可由两种方式之一消除 —— 调整该注释文案使其不含字面量 `Date.now()`，或让断言只统计可执行文本。二者均属源码/测试修改，超出 Code Agent 权限，本次未做任何改动。
