# BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `3c675d38fd63ac2992535dbe56da1f28d31f5be2`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`（已完整读取并按 §1–§7 执行）
- Batch ID: `BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW`
- Tested commit: `2cf150b82168904fe6500089cf699d986c374514`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 13:59:03 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 13:58:38 +08:00] ignore comments in pipeline identity guard`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 相对 Batch I 被测提交 `b5e4972b…` 的差异（只读核对）：仅 3 个文件 —— `docs/verification/CURRENT_BATCH.md`、`docs/verification/batches/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`、`web/tests/pipelineOperationIdentity.test.js`（+7/−2）；**生产代码零改动**，与计划 §Risk 表述一致。
- Scope: Batch I 失败复测（修正 pipeline identity 守卫对 `//` 注释的误计数）+ S3-76 / 关键回归 / Web full gate

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
pwsh: 7.6.6
git:  core.autocrlf = true（检出为 CRLF；索引 blob 为 LF）← 本批复测失败的直接环境因素，见下
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未运行（计划未要求；本批为 Web-only 复测）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   6970b52..3c675d3  feature/v3-development
git checkout --detach 2cf150b82168904fe6500089cf699d986c374514  exit=0
git rev-parse HEAD                                              exit=0   = 2cf150b82168904fe6500089cf699d986c374514
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineOperationIdentity.test.js             exit=1   4 tests / 3 pass / 1 FAIL   ← 仍未修复
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail
node --test tests/pipelineRetryHandling.test.js \
            tests/pipelineLocalBusinessDate.test.js \
            tests/rfmMatrixOwnership.test.js                    exit=0   13 tests / 13 pass / 0 fail
npm run verify                                                  exit=1   247 tests / 246 pass / 1 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        npm test 非零退出 ⇒ npm run build 未执行（Vite build 再次未验证）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未触碰被测分支 Git 历史。

## S3-75 — pipelineOperationIdentity.test.js 【FAIL（复测仍未通过）】

```text
counts: 4 tests / 3 pass / 1 fail        expected 4/4 → 不符合
result: FAIL
```

```text
✔ runOnce 自身受 busy fail-closed 保护，不能只依赖按钮 disabled
✖ 一次人工流水线触发只生成一个 operationId      ← FAIL（与 Batch I 同一用例、同一数值）
✔ sourceDataVersion 与 Idempotency-Key 复用同一 operationId
✔ runOnce 仍保持成功刷新、失败可见和 finally 释放 busy
```

失败断言原文（Node test runner 输出，未删改）：

```text
test at tests\pipelineOperationIdentity.test.js:31:1
✖ 一次人工流水线触发只生成一个 operationId (0.5986ms)
  AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:

  2 !== 1

      at TestContext.<anonymous> (file:///D:/Develop_code/GraduationProject-wt/v3-dev/web/tests/pipelineOperationIdentity.test.js:34:10)
    code: 'ERR_ASSERTION',
    actual: 2,
    expected: 1,
    operator: 'strictEqual'
```

行号位移说明：Batch I 在 `:26`/`:29`，本批在 `:31`/`:34`（新增 `executableText` 辅助函数 5 行），断言内容与实测数值 `actual 2 / expected 1` 完全一致。

### R1 修正为何在本环境无效（只读定位，未修改任何文件）

本批新增的守卫修正为：

```js
const executableText = (text) => text
  .split('\n')
  .map((line) => line.replace(/\/\/.*$/, ''))
  .join('\n')
```

在本机（`core.autocrlf = true`）检出的工作区文件行尾是 **CRLF**：

```text
git ls-files --eol 结果：
  i/lf  w/crlf  web/src/views/Pipeline.vue
  i/lf  w/crlf  web/tests/pipelineOperationIdentity.test.js
  i/lf  w/crlf  web/tests/rfmObservationWindow.test.js
```

因此 `split('\n')` 得到的每一行末尾仍带 `\r`；而 `/\/\/.*$/` **没有 `m` 标志**，`.*` 不能跨越 `\r`（`\r` 在 JS 中属行终止符），非多行模式下 `$` 又只匹配输入末尾 ⇒ 该正则在 `…// 注释\r` 上**匹配失败**，注释未被剥离，`Date.now()` 文本计数仍为 2。独立复算（内存处理，未写文件）：

```text
runOnce 切片含 CR 个数 = 26（即行尾为 CRLF）
现行 executableText（/\/\/.*$/ 无 m 标志）      → Date.now() 计数 = 2   ← 测试断言要求 1，故失败
对照 A（/\/\/.*\r?$/，显式吞掉 \r）              → Date.now() 计数 = 1
对照 B（/\/\/.*/，去掉 $ 锚点）                  → Date.now() 计数 = 1
单行复现（对 `…  // 两者不能各自 Date.now() 导致不一致\r`）：/\/\/.*$/ 命中 = false；/\/\/.*\r?$/ 命中 = true；/\/\/.*/ 命中 = true
```

结论（供总控判定，Code Agent 不做修复）：

- **本次修正未生效于实际执行环境**：计划 §2.2「测试只统计可执行文本中的 `Date.now()`，`//` 注释不参与调用次数判定」在本机 **不满足**；§2.3「可执行 `Date.now()` 恰好 1 次」的**事实判断在代码上成立**（真实可执行调用仅 L124 一处），但守卫未能在本环境测出该事实。
- **该守卫当前是行尾环境相关的**：仓库索引 blob 为 LF，若在 LF 检出（如 Linux CI）上运行，同一测试会通过；本机 `core.autocrlf=true` 检出为 CRLF 则失败。同一份提交在不同检出平台会给出不同结论，这是比单点失败更值得记录的性质。
- 计划 §2.1 / §2.4 / §2.5 对应断言与 §5（成功刷新、失败可见、finally 释放 busy）均 PASS。
- 生产代码自 Batch I 被测提交起未变，S3-76 与关键回归全部仍绿（见下），**无既有行为回归**。

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

继续确认计划 §3：观察窗口只消费后端 `periodStart`/`periodEnd`，缺失不猜（显示「未提供」），CSV 使用后端原值、不重算日期 —— 与 Batch I 结论一致。

## Key regression

```text
counts: 13 tests / 13 pass / 0 fail      expected 13/13 → 符合
result: PASS
  pipelineRetryHandling.test.js      4/4
  pipelineLocalBusinessDate.test.js  5/5
  rfmMatrixOwnership.test.js         4/4
```

## Web full gate

```text
command: cd web; npm run verify          exit=1
node --test "tests/**/*.test.js": total=247  passed=246  failed=1  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=246；`✖` 行数=3（1 条失败用例 + failing tests 汇总行）
vite production build:            NOT EXECUTED
```

- 计划 §5 要求「Vite production build 必须实际执行并 PASS」—— **未达成**：`npm run verify` = `npm test && npm run build`，`npm test` 以 1 退出，构建步骤未被执行。
- 佐证：日志（`batch-i-r1-web-verify.log`）中无 `vite v…` / `modules transformed` / `built in` 任何输出；`web/dist` 最后写入时间仍为 `2026-09-17 12:42:51`（Batch H 的构建），本批复测执行时刻为 `2026-09-17 14:02`，未被刷新。
- Expected 247 confirmed: **YES（数量一致）**；passed = 246、failed = 1。
- Actual total: **247**（本复测未增删用例，与 Batch I 相同）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **1（与 Batch I 同一用例，同一断言，同一数值）**

## New failures

```text
1) tests/pipelineOperationIdentity.test.js:34
   assert.equal((body.match(/Date\.now\(\)/g) || []).length, 1)   // body = executableText(functionBody('runOnce','retry'))
   actual = 2, expected = 1
   原因：executableText 的 /\/\/.*$/ 在本机 CRLF 工作区不匹配（.* 不跨 \r，非多行 $ 仅匹配输入末尾），
         L123 行注释中的字面量 Date.now() 未被剥离，仍被计入。
   隔离复现：node --test tests/pipelineOperationIdentity.test.js → 3/4，同一断言同一数值；
   全量复现：npm run verify → 246/247，同一用例。
   对照验证：改为 /\/\/.*\r?$/ 或去掉 $ 锚点后，同一复算得 1（仅证明因果，未改动仓库任何文件）。
```

## Unverified runtime areas（本批未覆盖，不得据此宣称已验收）

- 真实浏览器人工触发的 identity 行为与重复点击/网络异常；
- `/pipeline-runs`（`Idempotency-Key`）真实 HTTP 与后端幂等语义；
- `/analysis/rfm` 真实 HTTP 返回的 `periodStart`/`periodEnd` 与页面 DOM；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E；
- **Vite production build（本批再次因 `npm test` 非零退出而未执行）**；
- 该守卫在 LF 检出环境下的行为（本机无法在不改动仓库的前提下验证；仅能确认索引 blob 为 LF）。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-i-r1-web-verify.log（24608 bytes, exit=1）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 2cf150b8…，测试前后 clean）
本批复测改动范围:    web/tests/pipelineOperationIdentity.test.js（+5 辅助函数 / 1 处调用点）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: FAIL_NEW_REGRESSION
```

判定依据（计划 §7 PASS rule 逐条）：

- 精确 SHA 正确：`2cf150b82168904fe6500089cf699d986c374514` — 满足；
- S3-75 4/4 — **不满足（3/4）**；
- S3-76 4/4 — 满足；
- 回归 13/13 — 满足；
- `npm run verify` 全绿 — **不满足（246/247，failed=1）**；Vite build PASS — **未执行，无法判定**；
- 无新增回归（既有用例层面）— 满足：Batch G/H 既有 239 条与 S3-76 4 条全部仍绿，失败项仍为本批同一新增守卫；
- 结果本地/GitHub 双落盘 — 见下段。

备注（仅陈述事实，不含修复；两类修改均属源码/测试权限，Code Agent 未做任何改动）：在本机环境消除该失败的最小改动是让剥离正则容忍行尾 `\r`（例如 `/\/\/.*\r?$/`）或去掉 `$` 锚点；若希望仓库在任意检出平台结论一致，也可只改测试读取的源文本规范化方式（如 `text.replace(/\r\n/g, '\n')` 后再按行剥离）。
