# FUNCTION-POLISH-U06-S03 结果报告：Products/Sales/Reports 宽表横向滚动容器补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S03.md`；修复规约 `mall-simulator-notes/U06-S03-FIX.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S02 关账提交 `1d44af7`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S03-REPORT.md`，末行标记 `U06S03-DONE`；修复轮 `U06-S03-FIX-REPORT.md`，末行标记 `U06S03FIX-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-42（P2，多列宽表缺横向滚动容器，本切片处置余项中的三张表）与 UP-20261018-47（P2/测试空白，本切片部分）。机制沿用 U06-S01 已关账先例（既有 `.table-wrap` 类、v-if 移至包裹 div）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Products.vue`（仅商品明细表一处）：在 `<table class="data-table">` 外新增一层 `<div class="table-wrap">` 包裹；table 及其全部内容（表头 U06-S02 的 `.sort-btn`、空态 `colspan="8"`、分页与提示在包裹之外）逐字未动。
- `web/src/views/Sales.vue`（仅销售明细表一处）：同型新增 `<div class="table-wrap">` 包裹；table 内容（`.sort-btn` 表头、空态 `colspan="6"`）逐字未动。
- `web/src/views/Reports.vue`（仅「我的报告」列表表一处）：新增 `<div v-if="reports.length" class="table-wrap">` 包裹，table 改为 `<table class="data-table">`（v-if 移至包裹 div，与 U06-S01 Decisions 先例同型）；前置状态行/错误横幅/空态分支逐字未动；详情区既有 `.report-table-scroll` 逐字未动。
- `web/tests/tableOverflowWrapS03.test.js`（新增，265 行）：恰 5 个用例——Products 包裹结构 1 + Sales 包裹结构 1 + Reports 包裹结构（7 表头齐全、详情区滚动容器保留）1 + 机制锚点（`.table-wrap` 含 `overflow-x: auto`、三文件各恰 1 处）1 + 条件真值表仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器渲染/视觉证据。
- 与规约偏差：生产代码与新测试零偏差。唯一处置外事项为下述跨切片旧断言冲突，经修复轮定点更新（见下），已如实登记，不静默。

## 首轮不合格与同轮修复（总控亲跑抓获）

- 首轮 web 档（RunId `u06s03acc20261018`，日志 `mall-simulator-notes/u06s03-acc-web.log`）：tests=542、pass=541、fail=1，build exit=0。唯一失败为既有守卫 `web/tests/reportsRefreshRetention.test.js`（U05-S01 建立）第 2 块 2.c 断言——它锁定旧形态 `<table v-if="reports.length" class="data-table">`，与本切片经批准的 v-if 移至包裹层必然冲突；收口轮 RunId `u06s03gate20261018` 同一失败复现（541/542）。生产代码经总控逐行核对无误；根因为 U06-S03 规约「不改既有测试文件」未预见此跨切片断言。
- 修复轮 U06-S03-FIX（agy Flash 仅文件读写，总控同轮等待并亲验）：仅改该文件 2.c 一条断言及其注释——正则改为包裹形态 `/<div v-if="reports\.length" class="table-wrap">\s*<table class="data-table">/`、失败消息改「数据表格需以 reports.length 为挂载条件（包裹层）」；其后 `doesNotMatch !loading && !listError` 与全文件其余断言逐字不动，测试块仍 3 块、web 总数仍 542；生产代码零改动。
- 复验 web 档（RunId `u06s03fix20261018`，日志 `mall-simulator-notes/u06s03-fix-web.log`）：tests=542、pass=542、fail=0，build exit=0，**542 MATCH、PASS exit=0**。

## 总控亲跑验证（不采信执行方自述）

- **计数漂移书面登记**：web 基线 537 → 542，增量 +5 构成全部为本切片新增 `web/tests/tableOverflowWrapS03.test.js` 的 5 个测试块，无其他增减（修复轮只改断言形态、计数不变）。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 542`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：本轮巡检开始时中继 18128 与 3307 隔离 MySQL 均在监听，本切片验收本身不连库。

## 未测范围

- 未做真实浏览器横向滚动走查：窄屏下滚动条出现、拖动交互与视觉排版属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 `.table-wrap` 结构 + web 档源码结构守卫与真值表仿真。
- UP-20261018-42 其余站点（Ops 批次/审计网格、AiAssistant、DataTable 组件）、UP-20261018-43、UP-20261018-46 未在本切片处置，留后续切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-42 本切片三表部分关账（连同 U06-S01 两表，已处置 5 表）；UP-20261018-47 本切片部分关账。
- U06 余项（UP-42 其余站点、UP-43、UP-46）由后续巡检按队列逐片处置；UP-45 维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
