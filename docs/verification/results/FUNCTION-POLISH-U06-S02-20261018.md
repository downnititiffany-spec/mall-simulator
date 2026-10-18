# FUNCTION-POLISH-U06-S02 结果报告：Products/Sales 排序表头键盘可达（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S01 关账提交 `58e451f`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S02-REPORT.md`，末行标记 `U06S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-44（P2，Products/Sales 排序 th@click 纯键盘不可达）与 UP-20261018-47（P2/测试空白，本切片部分）。总控裁决（U06 盘点）：采用 A 方案（th 内嵌原生 button），非 §3.2 事项。

## 交付内容（总控逐行核 diff）

- `web/src/views/Products.vue`（仅商品明细表头一处）：th 去除 `:style` 与 `@click`，可排序六列在 th 内嵌 `<button v-if="col.sortKey" type="button" class="sort-btn" @click="toggleSort(col)">`（标题与箭头 span 包入 button，箭头条件改以 `sortKey === col.sortKey` 判定，与规约逐字一致）；不可排序两列（productName/conversionRate）走 `<template v-else>{{ col.title }}</template>` 纯文本分支，不变得可排序；`toggleSort` 函数体、columns 定义逐字未动。
- `web/src/views/Sales.vue`（仅销售明细表头一处）：th 去除 `style="cursor:pointer"` 与 `@click`，六列全嵌 `<button type="button" class="sort-btn" @click="toggleSort(col.key)">`（箭头 span 条件 `sortKey === col.key` 不变）；`toggleSort` 函数体逐字未动。
- `web/src/styles/theme.css`（仅一处）：在 `.data-table th .sort-caret` 规则后新增恰一条 `.data-table th .sort-btn { background: none; border: none; padding: 0; margin: 0; font: inherit; color: inherit; cursor: pointer; }`，逐字如规约裁决；未用 `.btn` 固定高度规则，焦点环由既有全局 `:focus-visible` 提供。
- `web/tests/tableSortKeyboard.test.js`（新增，313 行）：恰 5 个用例——Products 结构 1 + Products 排序面不回退（sortKey 恰六键、两列不带、箭头条件）1 + Sales 结构 1 + 机制锚点（`.sort-btn` 逐字、两页零 tabindex）1 + 键盘语义仿真（Products 键切换/同键翻序/无 sortKey 拒绝、Sales 同名逻辑）1。文件头如实注明源码结构守卫与纯函数仿真形态，不冒充真实浏览器键盘走查证据。
- 与规约偏差：零偏差。未加 tabindex/role/aria-sort/按键监听，未改其他页面表头，未新增依赖，未改既有测试文件，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s02acc20261018`，日志 `mall-simulator-notes/u06s02-acc-web.log`）：tests=537、pass=537、fail=0，build exit=0；脚本因计数基线仍为 532 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过。
- **计数漂移书面登记**：web 基线 532 → 537，增量 +5 构成全部为本切片新增 `web/tests/tableSortKeyboard.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 537`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s02gate20261018`，日志 `/tmp/v25tests-u06s02gate20261018`，副本 `mall-simulator-notes/u06s02-gate-web.log`）：tests=537 MATCH、pass=537、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：巡检开始时代理中继与 3307 隔离 MySQL 未监听（VM 替换后遗症），总控按 TOOLS.md 恢复节重新拉起并实查（中继 18128 LISTEN、mysqld 3307 LISTEN）后继续；本切片验收本身不连库。

## 未测范围

- 未做真实浏览器键盘走查：Tab 焦点落点、Enter/Space 触发排序与 `:focus-visible` 焦点环呈现属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级原生 button 结构 + web 档源码结构守卫与语义仿真。
- UP-20261018-42 其余站点（Ops 批次/审计网格、AiAssistant、DataTable 组件等）、UP-20261018-43、UP-20261018-46 未在本切片处置，留后续切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-44 关账；UP-20261018-47 本切片部分关账。
- U06 余项立切片 U06-S03（UP-20261018-42 余项的一部分：Products/Sales/Reports 三张明细/列表表横向滚动容器补齐——同一 `.table-wrap` 机制、Reports 的 v-if 移至包裹 div）派发 agy Flash，验收后另行登记。UP-42 其余站点（Ops/AiAssistant/DataTable）、UP-43/46 排其后；UP-45 维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
