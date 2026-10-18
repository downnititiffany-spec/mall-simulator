# FUNCTION-POLISH-U03-S01 结果报告：Overview 大盘错误态旧数据呈现与横幅口径一致性修复（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U03、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U03-S01.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U03 盘点验收提交 `053037c`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U03-S01-REPORT.md`，末行标记 `U03S01-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-11（P2，Overview error 态主体被 `!empty && !failed` 整墙卸载，与 AnalysisContext 横幅「页面保留的数据为上一次成功结果」矛盾）、UP-20261018-20（P2/测试空白，页面 error 态 DOM 保留无守卫）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Overview.vue`：主体模板条件由 `<template v-if="!empty && !failed">` 改为 `<template v-if="showBody">`（仅此一行）；解构行后新增总控勘误方案的两个计算属性（逐字采用规约文本）：`hasData`（metrics/salesTrend/activeTrend 任一数组非空）与 `showBody = failed ? hasData : !empty`。行 85 全页失败分支（`v-else-if="failed"`）与行 86 空快照分支（`v-else`）逐字未动——error 且无旧数据时 showBody=false 仍落入行 85，首次失败提示可达；error 有旧数据时主体保留、图表经 ChartState 呈局部 error 遮罩，与其余四分析页同口径。未新增 import（`computed` 既有），fetcher/cards/图表 state 派生/load/生命周期逐字未动。
- `web/tests/overviewErrorStateConsistency.test.js`（新增）：8 个用例——主体 showBody 形态守护、hasData 三数组判定守护、showBody 公式形态守护、行 85/86 分支保留守护、其余四页无 `&& !failed` 顶层阻断的五页同口径守护、AnalysisContext 横幅文案守护、showBody 真值表纯函数仿真（六行真值表中四行可区分情形）、hasData 行为语义仿真。文件头如实注明源码结构守卫 + 公式仿真形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未改 AnalysisContext.vue、ChartState.vue、BaseChart.vue、useAnalysis.js、chartState.js 及任何其他页面/后端文件；未改接口契约与指标口径；未处理 UP-20261018-18（排 U04/U05）、UP-20261018-19（排 U03 后续切片 U03-S02，见后续节）、UP-20261018-21（已裁决维持现状）；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u03s01acc20261018`，日志 `/tmp/v25tests-u03s01acc20261018`）：tests=499、pass=499、fail=0，`npm run build` exit=0；脚本因计数基线仍为 491 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 491 → 499，增量 +8 构成全部为本切片新增 `web/tests/overviewErrorStateConsistency.test.js` 的 8 个测试块（主体形态 1 + hasData 判定 1 + showBody 公式 1 + 分支保留 1 + 五页同口径 1 + 横幅文案 1 + 真值表仿真 1 + hasData 语义仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 499`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u03s01gate20261018`，日志 `/tmp/v25tests-u03s01gate20261018`）：tests=499 MATCH、pass=499、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级修复 + web 档源码结构守卫与公式仿真证据）；Overview error 态旧数据保留的真实页面走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-11、UP-20261018-20 关账。U03 余项：UP-20261018-19（P2，ChartState 错误遮罩无就地重试）立切片 U03-S02 派发 agy Flash（总控方案：ChartState error 遮罩内新增可选重试按钮、经 `retry` 事件触发，五分析页 10 处 ChartState 用法统一绑定各自既有 load 处理器，不改接口契约/状态模型）；UP-20261018-18 已按包归属排 U04/U05、UP-20261018-21 已裁决维持现状、UP-20261018-22～25 复核符合。U03-S02 关账后 U03 全项处置完毕，转 U04 盘点。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
