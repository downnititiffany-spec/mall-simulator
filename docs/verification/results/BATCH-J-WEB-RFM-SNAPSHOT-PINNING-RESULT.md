# BATCH-J-WEB-RFM-SNAPSHOT-PINNING — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `c12befae509239cdb41ed68d888729ed801fe371`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-PLAN.md`（已完整读取并按 §1–§8 执行）
- Batch ID: `BATCH-J-WEB-RFM-SNAPSHOT-PINNING`
- Tested commit: `172d80b06ac45c0c27942a4e11007916090c4254`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 14:19:25 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `test(web): [2026-09-17 14:19:11 +08:00] guard RFM snapshot pinning`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批生产改动范围（只读核对）：`web/src/views/Rfm.vue`（+约 28/−22）+ 新增 `web/tests/rfmSnapshotPinning.test.js`
- Scope: S3-77 RFM 跨接口 snapshot 一致性 + RFM 关键回归 + Web full gate（含 Vite build）

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
git fetch origin                                                exit=0   80d9215..c12befa  feature/v3-development
git checkout --detach 172d80b06ac45c0c27942a4e11007916090c4254  exit=0
git rev-parse HEAD                                              exit=0   = 172d80b06ac45c0c27942a4e11007916090c4254
git status --short                                              exit=0   空（clean）

cd web
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail
node --test tests/rfmMatrixOwnership.test.js \
            tests/rfmObservationWindow.test.js                  exit=1   8 tests / 6 pass / 2 FAIL   ← 回归失败
npm run verify                                                  exit=1   251 tests / 249 pass / 2 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        npm test 非零退出 ⇒ npm run build 未执行（Vite build 未验证）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`；未触碰被测分支 Git 历史。

## S3-77 — rfmSnapshotPinning.test.js

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS
```

```text
✔ RFM 用户聚合请求固定到主 RFM 响应的 snapshotId
✔ RFM 响应缺 snapshotId 时跳过第二个聚合请求而不是重新取 ACTIVE
✔ 页面响应上下文仍以 RFM 主响应快照为唯一 snapshotId
✔ 生命周期与偏好只消费被固定快照的 usersData，不参与 RFM 主口径重算
```

逐条确认（`web/src/views/Rfm.vue` 只读复核，计划 §3）：

1. `/analysis/users` 复用主 RFM 响应的 snapshotId：L106 `const users = readEnvelope(await api.users({ snapshotId: rfm.snapshotId }, { signal }))` —— 确认，参数取自已解码的 `rfm`（主响应）。
2. 旧的未固定写法已消失：`api.users({}` 在文件中出现次数 = **0**（L97 仍为 `api.rfm({}, { signal })`，即主请求本身不带快照，符合设计） —— 确认。
3. 主响应缺 `snapshotId` 时跳过第二个请求：L102–L103 `if (!rfm.snapshotId) { usersError.value = 'RFM 响应未提供 snapshotId，为避免混快照已跳过生命周期/偏好聚合请求' }`，`else` 分支才发起 `api.users` —— 确认，未再独立解析 ACTIVE。
4. 页面响应上下文仍由主 RFM 响应拥有：L116 `snapshotId: rfm.snapshotId`（响应上下文构造点），生命周期/偏好仅填充各自聚合区块（L110–L111 失败仅写 `usersError`，不覆盖主口径） —— 确认。

## RFM regression — 【FAIL：2 条既有守卫被本批生产改动打破】

```text
counts: 8 tests / 6 pass / 2 fail        expected 8/8 → 不符合
result: FAIL
```

```text
rfmMatrixOwnership.test.js（4/4 全部通过）
✔ RFM 页面优先使用后端 rfmMatrix，而不是把前端固定类目追加到真实结果
✔ 旧响应缺少 rfmMatrix 时只展示真实 rfmSegments，不制造额外 0 人类目
✔ 矩阵行仅做字段搬运，缺失数值按既有空值语义处理
✔ 图表与 CSV 都消费同一个 segmentRows，不再产生第二套类别所有者

rfmObservationWindow.test.js（2/4 —— Batch I 已验收的 S3-76 守卫）
✔ RFM 只从 rfm 响应搬运 periodStart/periodEnd，不从 users 聚合接口猜观察期
✖ 观察期必须起止都存在才展示完整窗口，任一缺失时显示未提供
✔ RFM defaults 显式包含 periodStart/periodEnd null，不能制造默认窗口
✖ RFM CSV 同步导出后端观察期起止，不重算日期
```

失败断言原文（Node test runner 输出，未删改）：

```text
test at tests\rfmObservationWindow.test.js:17:1
✖ 观察期必须起止都存在才展示完整窗口，任一缺失时显示未提供
  AssertionError [ERR_ASSERTION]: The input did not match the regular expression
    /const periodText = computed\(\(\) => \([\s\S]*periodStart\.value && periodEnd\.value[\s\S]*'未提供'/
    operator: 'match'

test at tests\rfmObservationWindow.test.js:28:1
✖ RFM CSV 同步导出后端观察期起止，不重算日期
    actual:   （Rfm.vue 全文，见日志）
    expected: /periodStart\.value \|\| ''/
    operator: 'match'
```

回归根因（只读定位，`git diff 4ae3b4c…（Batch I-R2 被测提交） 172d80b…（本批被测提交） -- web/src/views/Rfm.vue`）：

```diff
-const periodStart = computed(() => data.value.periodStart || null)
-const periodEnd = computed(() => data.value.periodEnd || null)
-const periodText = computed(() => (
-  periodStart.value && periodEnd.value ? `${periodStart.value} 至 ${periodEnd.value}` : '未提供'
-))
+const observationWindow = computed(() => {
+  const start = data.value.periodStart
+  const end = data.value.periodEnd
+  return start && end ? `${start} 至 ${end}` : '未提供'
+})

模板： -观察期：{{ periodText }}   →   +观察期：{{ observationWindow }}
导出： -periodStart.value || '', periodEnd.value || ''   →   +data.value.periodStart || '', data.value.periodEnd || ''
```

即：本批生产改动把观察期由「两个具名 computed + `periodText`」**改名并内联**为 `observationWindow`（局部 `start`/`end` 读 `data.value`），而 Batch I 已验收的守卫是按旧标识符（`periodText`、`periodStart.value`、`periodEnd.value`）书写的文本断言，因此断言失配。

独立语义核对（判断“行为是否也变了”，与断言语义分开陈述）：

```text
当前实现 Rfm.vue L147–L151：
  const observationWindow = computed(() => {
    const start = data.value.periodStart
    const end = data.value.periodEnd
    return start && end ? `${start} 至 ${end}` : '未提供'
  })
  模板 L9：观察期：{{ observationWindow }}
  导出 L188：data.value.periodStart || '', data.value.periodEnd || ''（仍为后端原值）
  new Date() 出现次数 = 0
```

- 起止都存在才展示 `start 至 end`、任一缺失显示「未提供」——**运行时语义与 Batch I 目标一致**；
- CSV 仍使用后端原值、未重算日期——**语义一致**；
- 但按计划 §4/§8，回归必须 **8/8** 且「无新增回归」，本批为 **6/8**，故 **不满足 PASS**；协议 §6 要求不得因“语义看起来等价”而记为 PASS —— 此项留给总控判定（可选路径二选一：让守卫跟随新标识符，或让实现保留旧标识符；两者均属源码/测试修改，超出 Code Agent 权限，本次未做任何改动）。

## Web full gate

```text
command: cd web; npm run verify          exit=1
node --test "tests/**/*.test.js": total=251  passed=249  failed=2  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=249；`✖` 行数=5（2 条失败用例 + 汇总行等）
vite production build:            NOT EXECUTED
```

- 计划 §5 要求「Vite production build 必须实际执行并 PASS」—— **未达成**：`npm run verify` = `npm test && npm run build`，`npm test` 以 1 退出，构建未执行。
- 佐证：日志无 `vite v…` / `modules transformed` / `built in` 输出；`web/dist` 最后写入时间保持 `2026-09-17 14:09:21`（Batch I-R2 的构建），本批执行时刻为 `14:22`，未被刷新。
- Expected ≈251 confirmed: **YES**；passed = 249、failed = 2（247 基线 + `rfmSnapshotPinning` 4 = 251）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **2（均为 Batch I 已验收的 S3-76 守卫，被本批 `Rfm.vue` 改名打破）**

## New failures

```text
1) tests/rfmObservationWindow.test.js:18
   /const periodText = computed\(\(\) => \([\s\S]*periodStart\.value && periodEnd\.value[\s\S]*'未提供'/
   → 不匹配：实现已改名为 observationWindow 并内联 data.value 读取
2) tests/rfmObservationWindow.test.js:30
   /periodStart\.value \|\| ''/
   → 不匹配：导出改为 data.value.periodStart || ''
隔离复现：node --test tests/rfmObservationWindow.test.js → 2/4，同一断言；
全量复现：npm run verify → 249/251，同一 2 条。
```

## Unverified runtime areas（计划 §6 明确未验证，不得据此宣称已验收）

- 真实浏览器渲染；
- ACTIVE 快照切换期间的真实 HTTP 竞态（本批仅为源码级接线断言）；
- 真实数据库快照内容；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E；
- **Vite production build（本批因 `npm test` 非零退出而未执行）**。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-j-web-verify.log（67136 bytes, exit=1）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 172d80b0…，测试前后 clean）
本批改动范围:        web/src/views/Rfm.vue（+28/−22）+ web/tests/rfmSnapshotPinning.test.js（新增 33 行）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: FAIL_NEW_REGRESSION
```

判定依据（计划 §8 PASS rule 逐条）：

- 精确 SHA 匹配 `172d80b06ac45c0c27942a4e11007916090c4254` — 满足；
- S3-77 = 4/4 PASS — 满足；
- RFM regression = 8/8 PASS — **不满足（6/8）**；
- Web full gate 零 failure / 零 cancellation — **不满足（249/251，failed=2）**；
- Vite build 实际执行并 PASS — **未执行，无法判定**；
- 无新增回归或 tracked 工作区改动 — **不满足**：2 条既有守卫回归（工作区本身 clean）；
- 结果本地/GitHub 双落盘 — 见下段。
