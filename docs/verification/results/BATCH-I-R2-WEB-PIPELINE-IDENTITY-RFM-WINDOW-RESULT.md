# BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `80d921573fa4d6eb51ec1933a14efc2aee7d9127`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW-PLAN.md`（已完整读取并按 §1–§9 执行）
- Batch ID: `BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW`
- Tested commit: `4ae3b4c72cea9f01f3880c085bacab75f541c2b6`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 14:06:26 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 14:06:13 +08:00] make pipeline identity guard CRLF-safe`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件，未改动任何 Git 配置）
- 相对 Batch I 被测提交：**生产代码零改动**（与计划 §2 一致）。测试侧差异：`web/tests/pipelineOperationIdentity.test.js` 的 `executableText` 由
  `.split('\n').map((line) => line.replace(/\/\/.*$/, ''))`
  改为
  `.split(/\r?\n/).map((line) => line.replace(/\/\/.*/, ''))`，并补充 LF/CRLF 归一化注释（+6/−2）。
- Scope: Batch I-R1 失败复测（守卫行尾无关化）+ S3-76 / 关键回归 / Web full gate（含 Vite build）

## Environment

```text
OS:   Microsoft Windows 11 家庭版 中文版 (10.0.26100)
Node: v24.16.0
npm:  11.13.0
vite: 5.4.21
pwsh: 7.6.6
git:  core.autocrlf = true（未改动；检出为 CRLF，索引 blob 为 LF）
Web 依赖: web/node_modules 既有（未重装）
Java/Maven: 本批未运行（本批为 Web 源码/测试/构建批次）
```

## Commands executed（含退出码）

```text
git fetch origin                                                exit=0   3c675d3..80d9215  feature/v3-development
git checkout --detach 4ae3b4c72cea9f01f3880c085bacab75f541c2b6  exit=0
git rev-parse HEAD                                              exit=0   = 4ae3b4c72cea9f01f3880c085bacab75f541c2b6
git status --short                                              exit=0   空（clean）

cd web
node --test tests/pipelineOperationIdentity.test.js             exit=0   4 tests / 4 pass / 0 fail
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail
node --test tests/pipelineRetryHandling.test.js \
            tests/pipelineLocalBusinessDate.test.js \
            tests/rfmMatrixOwnership.test.js                    exit=0   13 tests / 13 pass / 0 fail
npm run verify                                                  exit=0   247 tests / 247 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 2.65s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未改动 Git 配置；未触碰被测分支 Git 历史。

## CRLF-specific evidence（计划 §6）

```text
git ls-files --eol web/tests/pipelineOperationIdentity.test.js  →  i/lf  w/crlf  attr/
git ls-files --eol web/src/views/Pipeline.vue                   →  i/lf  w/crlf  attr/
git config --get core.autocrlf                                  →  true（未改动）

仓库内最终守卫实现（web/tests/pipelineOperationIdentity.test.js）：
  L20  const executableText = (text) => text
  L21    .split(/\r?\n/)
  L22    .map((line) => line.replace(/\/\/.*/, ''))
  L34  const body = executableText(functionBody('runOnce', 'retry'))
  L36  assert.equal((body.match(/Date\.now\(\)/g) || []).length, 1)

独立复算（内存处理，未写任何文件；同一 runOnce 切片的两种行尾输入）：
  输入 CRLF（本机检出，切片含 \r = true）  → 可执行 Date.now() 计数 = 1   ← 本批通过
  输入纯 LF（模拟 Linux 检出，含 \r = false）→ 可执行 Date.now() 计数 = 1   ← 行尾无关，符合计划要求
  对照 Batch I-R1 旧实现（/\/\/.*$/）在 CRLF 下 → 计数 = 2（即 R1 失败的根因，已被本批修正）
```

守卫不再依赖检出平台行尾：LF 与 CRLF 两种输入均得 1，且未通过改动 Git 配置或放宽语义达成。

## S3-75 — pipelineOperationIdentity.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ runOnce 自身受 busy fail-closed 保护，不能只依赖按钮 disabled
✔ 一次人工流水线触发只生成一个 operationId
✔ sourceDataVersion 与 Idempotency-Key 复用同一 operationId
✔ runOnce 仍保持成功刷新、失败可见和 finally 释放 busy
```

对应计划 §4 逐条：`runOnce` 内部 `busy` fail-closed 保护保持；行尾归一化 + 行注释剥离后可执行 `Date.now()` 恰为 1 次；`sourceDataVersion` 与 `Idempotency-Key` 复用同一 `operationId`；成功刷新列表、失败可见（`FAILED: …`）、`finally` 释放 `busy` 均未回归。Batch I / R1 的失败用例（`tests/pipelineOperationIdentity.test.js:34`，`actual 2 / expected 1`）本批 **转为 PASS**。

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

## Key regression

```text
counts: 13 tests / 13 pass / 0 fail      expected 13/13 → 符合
result: PASS
  pipelineRetryHandling.test.js      4/4   （Batch H retry fail-closed）
  pipelineLocalBusinessDate.test.js  5/5   （Batch G 本地业务日）
  rfmMatrixOwnership.test.js         4/4   （后端唯一 RFM matrix 类目属主）
```

## Web full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=247  passed=247  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=247；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 2.65s
```

- Expected 247 confirmed: **YES**
- Actual total: **247**（Batch I/R1 为 247，本批复测未增删用例）
- Failed / Cancelled = **0 / 0**
- Vite build 执行证据：构建输出实际出现在日志中；`web/dist` 最后写入时间由 `2026-09-17 12:42:51`（Batch H 的构建）刷新为 `2026-09-17 14:09:21`（本批执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## New failures

```text
无。247/247 全绿，failed/cancelled=0，Vite build 实际执行并 PASS，无新增回归。
```

## Unverified runtime areas（本批未覆盖，不得据此宣称已验收）

- 真实浏览器人工触发 interaction 与重复点击/网络异常（源码级守卫断言，非运行时验证）；
- `/pipeline-runs`（`Idempotency-Key`）真实 HTTP 与后端幂等语义；
- `/analysis/rfm` 真实 HTTP 返回的 `periodStart`/`periodEnd` 与页面 DOM；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-i-r2-web-verify.log（26176 bytes, exit=0）
构建产物:            web/dist（gitignored，本批 vite build 已刷新：14:09:21）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 4ae3b4c7…，测试前后 clean）
本批复测改动范围:    web/tests/pipelineOperationIdentity.test.js（+6/−2，仅测试守卫）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §9 PASS rule 逐条）：

- 精确 SHA 匹配 `4ae3b4c72cea9f01f3880c085bacab75f541c2b6` — 满足；
- S3-75 = 4/4 — 满足；
- S3-76 = 4/4 — 满足；
- regression = 13/13 — 满足；
- full Web test suite 零 failure / 零 cancellation（247/247）— 满足；
- Vite production build 实际执行并 PASS（672 modules，2.65s）— 满足；
- 未产生 tracked 工作区改动（测试后 `git status --short` 为空）— 满足；
- 结果已落本地并发布到授权的 `verification-results` 路径 — 见下段。
