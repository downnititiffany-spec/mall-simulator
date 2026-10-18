# FUNCTION-POLISH-U05-S01 结果报告：Reports 列表刷新平滑保留与失败防丢失修复（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U05、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U05-S01.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U05 盘点验收提交 `2c0e68a`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U05-S01-REPORT.md`，末行标记 `U05S01-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-18（P2，Reports 一半：刷新闪空且失败抹除旧列表）与 UP-20261018-36（P2/测试空白，本切片部分）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Reports.vue`（仅三处）：模板行 89 状态行条件由 `loading` 改为 `loading && reports.length === 0`（逐字如规约）；行 93 表格条件由 `!loading && !listError && reports.length` 改为 `reports.length`（与行 90 错误横幅同屏成立）；`loadPage()` 失败分支删除恰好一行 `reports.value = []`，序号守卫/生命周期/成功赋值逐字保留。`openReport`/`loadArtifact`/`loadDecisionOptions`（归 U05-S02）未动。
- `web/tests/reportsRefreshRetention.test.js`（新增）：3 个用例——loadPage 失败不清空与成功赋值/序号/生命周期守护 1 + 模板条件与横幅/空态 else-if 链守护 1 + 条件链真值表仿真（五种组合落点）1。文件头如实注明源码结构守卫形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未改后端、接口契约、权限与状态模型；未处理 UP-20261018-34（归 U05-S02）；未处理 UP-20261018-35；未改报告生成幂等（UP-37 已裁决维持现状）；未新增依赖；未改既有测试文件；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u05s01acc20261018`，日志 `/tmp/v25tests-u05s01acc20261018`，副本 `mall-simulator-notes/u05s01-acc-web.log`）：tests=521、pass=521、fail=0，`npm run build` exit=0；脚本因计数基线仍为 518 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 518 → 521，增量 +3 构成全部为本切片新增 `web/tests/reportsRefreshRetention.test.js` 的 3 个测试块（loadPage 守护 1 + 模板守护 1 + 真值表仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 521`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u05s01gate20261018`，日志 `/tmp/v25tests-u05s01gate20261018`，副本 `mall-simulator-notes/u05s01-gate-web.log`）：tests=521 MATCH、pass=521、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：本轮巡检时代理中继（127.0.0.1:18128）与 3307 隔离 MySQL 均在监听（前轮恢复后持续运行），本切片验收本身不连库。

## 未测范围

- 未做真实浏览器走查（结论为源码级呈现条件修复 + web 档源码结构守卫与真值表仿真证据）；Reports 刷新观感的真实页面走查并入 U06/U07 本地事项或后续按受影响路径登记。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-18 **全项关账**（RuntimeProfiles 一半随 U04-S01 关账、Reports 一半随本切片关账）；UP-20261018-36 本切片部分关账（竞态守卫部分随 U05-S02 补齐后全项关账）。U05 余项立切片 U05-S02（UP-20261018-34，Reports 详情/制品/决策候选竞态守卫与卸载失效；总控裁决：序号失效方案落地，AbortSignal 网络层取消不纳入本切片、openReport 失败清 selectedReport 维持现状，理由见 U05-S02 规约）派发 agy Flash，验收后另行登记。此后 UP-20261018-35 涉 §3.2 边缘，推进前另行裁决并在主聊天汇报。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
