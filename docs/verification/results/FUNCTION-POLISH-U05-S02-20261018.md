# FUNCTION-POLISH-U05-S02 结果报告：Reports 详情/制品/决策候选竞态守卫与卸载失效（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U05、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U05-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U05-S01 关账提交 `69cd2d2`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U05-S02-REPORT.md`，末行标记 `U05S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-34（P2，Reports 无卸载清理与详情/制品/决策候选竞态）与 UP-20261018-36（P2/测试空白，本切片余项，随本切片全项关账）。
- 总控裁决（规约前提）：① 序号失效方案落地，AbortSignal 网络层取消不纳入本切片（与本文件 `loadPage` 既有 `requestSequence` 先例同型、改动面最小；卸载后在途请求仍完成网络往返，但响应一律被序号校验丢弃、不回写状态，诚实登记）；② `openReport` 失败清 `selectedReport` 维持现状（单选详情专属横幅口径，序号守卫保证仅最新失败才清，与列表多行集合性质不同）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Reports.vue`（仅规约六处）：import 引入 `onUnmounted`；`requestSequence` 后新增 `detailSequence`/`artifactSequence`/`decisionSequence` 三序号；`openReport` 整函数替换为序号守卫形态（取号、切换报告自增 `artifactSequence` 失效在途制品、成功/失败/finally 三处校验、最新失败仍清 `selectedReport` 并置 `detailError`——裁决 2 守护）；`loadArtifact` 整函数替换（先捕获 `reportId` 单次读取、序号三处校验）；`loadDecisionOptions` 三处插入（首行取号、并发后写入前校验、catch 首行校验、finally 条件复位）；`onMounted` 块后新增 `onUnmounted` 四序号递增失效。`loadPage`（U05-S01 关账形态）、`createReport`、模板与样式逐字未动；快照选择共享状态不在失效范围。
- `web/tests/reportsRaceGuard.test.js`（新增）：6 个用例——import/onUnmounted 卸载守卫 1 + openReport 竞态守卫 1 + loadArtifact 单次捕获与竞态守卫 1 + loadDecisionOptions 竞态守卫 1 + loadPage 既有守卫不回退 1 + 序号竞态与卸载失效纯函数仿真 1（A/B 乱序丢弃、迟到失败不冲新详情、卸载全部丢弃、切换报告制品失效四场景）。文件头如实注明源码结构守卫与纯函数仿真形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未改后端、`api.js`、接口契约；未加 AbortSignal/AbortController（裁决 1）；未处理 UP-20261018-35（Decisions prompt，涉 §3.2 边缘，另行裁决汇报）；未改报告生成幂等（UP-37 已裁决维持现状）；未新增依赖；未改既有测试文件；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u05s02acc20261018`，日志 `/tmp/v25tests-u05s02acc20261018`）：tests=527、pass=527、fail=0，`npm run build` 成功；脚本因计数基线仍为 521 报 DRIFT——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 521 → 527，增量 +6 构成全部为本切片新增 `web/tests/reportsRaceGuard.test.js` 的 6 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 527`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u05s02gate20261018`，日志 `/tmp/v25tests-u05s02gate20261018`，副本 `mall-simulator-notes/u05s02-gate-web.log`）：tests=527 MATCH、pass=527、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：代理中继（127.0.0.1:18128）与 3307 隔离 MySQL 持续在监听（前轮恢复），本切片验收本身不连库。

## 未测范围

- 未做真实浏览器走查（结论为源码级竞态守卫修复 + web 档源码结构守卫与纯函数仿真证据）；Reports 详情连点/切换的真实页面走查并入 U06/U07 本地事项或后续按受影响路径登记。
- 卸载后在途请求仍会完成网络往返（序号丢弃、不回写），网络层取消（AbortSignal）未纳入本切片，已诚实登记。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-34 **关账**；UP-20261018-36 **全项关账**（S01 列表部分 + 本切片竞态部分）。
- U05 余项仅 UP-20261018-35（P2，Decisions 全链路原生 prompt 输入改内联/模态对话框）：涉指导书 §3.2「引入新组件」边缘，总控不擅自推进，已按巡检纪律在主聊天报告影响与方案（A：轻量内联/模态对话框组件替代 prompt；B：维持现状——现行 prompt 已有非空与日历合法性 fail-closed 校验、功能完整），等用户/总控裁决；裁决前 U05 保持「进行中（余 UP-35 待裁决）」。
- 不依赖该裁决的工作继续：转 U06 盘点（U06-INV 只读盘点规约已立并派发 agy Flash；云端只做可自动化/源码级盘点，真实视觉审阅为用户本地事项、RES-06 沿用签收处置）。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
