# BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `82bc6a7e1795e33c3d19f211b626e645a07daf66`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING-PLAN.md`（已完整读取并按 §1–§9 执行）
- Batch ID: `BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING`
- Tested commit: `30d5713edae8c90e8849ea51cccaa269d80d3f00`
  - `git rev-parse HEAD` 精确匹配 = True
  - 提交元数据：2026-09-17 14:28:41 +0800 / downnititiffany-spec `<downniti.tiffany@gmail.com>` / `fix(web): [2026-09-17 14:28:13 +08:00] preserve RFM observation-window contract`
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空，无 tracked 工作区改动）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何文件）
- 本批生产改动范围（只读核对）：`web/src/views/Rfm.vue` 相对 Batch J 被测 SHA 仅 15 行差异，全部集中在观察期派生与 CSV 取值（恢复具名 `periodStart`/`periodEnd`/`periodText`）；相对 Batch I-R2 已验收 SHA 净改动 +23/−16（含 S3-77 快照固定接线）。无后端/DB/状态机/权限/AI SQL/Spark/Hive/Flume 改动。
- Scope: Batch J 失败复测 —— 保留 S3-77 快照固定，同时恢复 Batch I 已验收的 S3-76 观察期合同

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
git fetch origin                                                exit=0   c12befa..82bc6a7  feature/v3-development
git checkout --detach 30d5713edae8c90e8849ea51cccaa269d80d3f00  exit=0
git rev-parse HEAD                                              exit=0   = 30d5713edae8c90e8849ea51cccaa269d80d3f00
git status --short                                              exit=0   空（clean）

cd web
node --test tests/rfmSnapshotPinning.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmObservationWindow.test.js                  exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
node --test tests/rfmMatrixOwnership.test.js                    exit=0   4 tests / 4 pass / 0 fail / 0 cancelled
npm run verify                                                  exit=0   251 tests / 251 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo
                                                                        vite v5.4.21 production build PASS（672 modules transformed；built in 2.84s）
```

一次执行完整批次，未拆轮；未修改源码/测试/脚本/docs/`CURRENT_BATCH.md`/`DEFERRED_TEST_PLAN.md`/Git 配置；未触碰被测分支 Git 历史。

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

## S3-76 — rfmObservationWindow.test.js（Batch J 的失败项复测）

```text
counts: 4 tests / 4 pass / 0 fail        expected 4/4 → 符合
result: PASS（Batch J 的 2 条失败已全部转为 PASS）
```

```text
✔ RFM 只从 rfm 响应搬运 periodStart/periodEnd，不从 users 聚合接口猜观察期
✔ 观察期必须起止都存在才展示完整窗口，任一缺失时显示未提供      ← Batch J 失败项，现 PASS
✔ RFM defaults 显式包含 periodStart/periodEnd null，不能制造默认窗口
✔ RFM CSV 同步导出后端观察期起止，不重算日期                    ← Batch J 失败项，现 PASS
```

Batch J 失败的两条断言（`tests/rfmObservationWindow.test.js:18` 的 `/const periodText = computed\(\(\) => \([\s\S]*periodStart\.value && periodEnd\.value[\s\S]*'未提供'/`，以及 `:30`/`:31` 的 `/periodStart\.value \|\| ''/`、`/periodEnd\.value \|\| ''/`）在本批被测提交下全部满足；该测试文件本身**未改动**（守卫文本与 Batch J 一致），满足来自生产实现的恢复。

## RFM matrix ownership — rfmMatrixOwnership.test.js

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

## Required semantic checks（计划 §7，逐条）

```text
1) api.users({ snapshotId: rfm.snapshotId }, { signal }) 存在，旧 api.users({} 写法缺失
   → L106 `const users = readEnvelope(await api.users({ snapshotId: rfm.snapshotId }, { signal }))`
   → `api.users({}` 全文出现次数 = 0（L97 仍为 `api.rfm({}, { signal })`，主请求本身不带快照，符合设计）  满足

2) 主 RFM 响应缺 snapshotId 时跳过第二请求
   → L102–L103 `if (!rfm.snapshotId) { usersError.value = 'RFM 响应未提供 snapshotId，为避免混快照已跳过生命周期/偏好聚合请求' }`
     仅 else 分支发起 api.users                                                                        满足

3) 页面响应上下文 snapshotId 仍归主 RFM 响应
   → L116 `snapshotId: rfm.snapshotId`（响应上下文构造点）；users 失败仅写 usersError（L111），不覆盖主口径   满足

4) periodStart/periodEnd 仅从 RFM 响应搬运
   → L127 `periodStart: rfm.data.periodStart || null`、L128 `periodEnd: rfm.data.periodEnd || null`
   → `periodStart: usersData.` 出现次数 = 0；`localIsoDay` 出现次数 = 0                                  满足

5) periodText 仅当起止都存在时显示 `start 至 end`，否则「未提供」
   → L147 注释「保持 S3-76 已验收的具名观察期派生：显示和 CSV 共用同一后端原值所有者。」
   → L148 `const periodStart = computed(() => data.value.periodStart || null)`
   → L149 `const periodEnd = computed(() => data.value.periodEnd || null)`
   → L150–L152 `const periodText = computed(() => ( periodStart.value && periodEnd.value ? `${periodStart.value} 至 ${periodEnd.value}` : '未提供' ))`
   → 模板 L9 `观察期：{{ periodText }}`                                                                 满足

6) CSV 使用 periodStart.value || '' / periodEnd.value || ''，无当日计算
   → L185 headers 含 '观察期开始','观察期结束'；L189 `periodStart.value || '', periodEnd.value || ''`
   → `new Date(` 出现次数 = 0                                                                           满足

7) RFM 类目仍归后端所有，前端未复活固定八类清单
   → L154–L155 注释与 segmentRows 派生均声明 matrix 由后端 RfmService.rfmMatrix 唯一维护；无 `SEGMENTS` 常量
   → COLORS 为按后端返回名取色的颜色映射（8 个色值），不是类别所有者；与 Batch I-R2 已验收版本逐行比对**未改动**
   → 由 rfmMatrixOwnership 4/4 独立佐证                                                              满足
```

## Web full gate

```text
command: cd web; npm run verify          exit=0
node --test "tests/**/*.test.js": total=251  passed=251  failed=0  cancelled=0  skipped=0  todo=0
                                  `✔` 行数=251；`✖` 行数=0
vite production build:            PASS，且本批实际执行
                                  vite v5.4.21 building for production...
                                  ✓ 672 modules transformed.
                                  ✓ built in 2.84s
```

- 实际测试数 = **251**（247 基线 + 本批新增 `rfmSnapshotPinning` 4），与 Batch J 的 251 一致；passed = 251。
- Failed / Cancelled = **0 / 0**（Batch J 为 2 / 0，本批清零）。
- Vite build 执行证据：日志含 `vite v5.4.21 building for production...`、`✓ 672 modules transformed.`、`✓ built in 2.84s`；`web/dist` 最后写入时间由 `2026-09-17 14:09:21` 刷新为 `2026-09-17 14:32:50`（执行时刻）。
- Known environmental failures: **N/A for this Web-only batch**
- New failures: **无**

## New failures

```text
无。三个定向套件 12/12，全量 251/251，failed/cancelled=0，Vite build 实际执行并 PASS。
```

## Unverified runtime areas（本批未覆盖，不得据此宣称已验收）

- 真实浏览器渲染与人工交互；
- ACTIVE 快照切换期间的真实 HTTP 竞态（本批为源码级接线断言）；
- 真实数据库快照内容；
- Java default/spark/isolated/3307 门禁；
- Spark/Hive/Flume E2E。

## Evidence / log paths

```text
raw npm verify 日志: C:\Users\ASUS\AppData\Local\Temp\batch-j-r1-web-verify.log（26541 bytes, exit=0）
构建产物:            web/dist（gitignored，本批 vite build 已刷新：14:32:50）
被测工作区:          D:\Develop_code\GraduationProject-wt\v3-dev（detached @ 30d5713e…，测试前后 clean）
```

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```

## Overall

```text
Overall: PASS
```

判定依据（计划 §9 PASS rule 逐条）：

- 精确 SHA 匹配 `30d5713edae8c90e8849ea51cccaa269d80d3f00` — 满足；
- 三个定向套件全部 PASS（S3-77 4/4、S3-76 4/4、rfmMatrixOwnership 4/4）— 满足；
- 完整 Web 测试套件零 failure / 零 cancellation（251/251）— 满足；
- Vite production build 实际执行并 PASS — 满足；
- 计划 §7 七项语义复核逐条满足 — 满足；
- 工作区保持 clean（测试后 `git status --short` 为空）— 满足；
- 结果本地/GitHub 双落盘 — 见下段。
