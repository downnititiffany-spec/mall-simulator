# FUNCTION-POLISH-U06-S07 结果报告：DataTable 通用表组件横向滚动容器补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S07.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S06 关账提交 `252b70a`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S07-REPORT.md`，末行标记 `U06S07-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-42（P2，多列宽表缺横向滚动容器，本切片处置余项最后一处 DataTable 通用组件）与 UP-20261018-47（P2/测试空白，本切片部分）。机制沿用 U06-S01/S03/S04/S05/S06 已关账先例（既有 `.table-wrap` 类、v-if 移至包裹 div）。

## 交付内容（总控逐行核 diff）

- `web/src/components/DataTable.vue`（仅模板 table 一处）：组件内部为 table 外加一层既有 `<div v-if="table && table.rows && table.rows.length" class="table-wrap">` 包裹，复合条件由 table 移至包裹 div，table 改为 `<table class="data-table">`；`</table>` 后新增 `</div>`；其后 `v-else` 空态 div（`{{ emptyText }}`）逐字未动且绑定对象改为包裹 div、条件链语义不变；thead（表头循环、操作列表头）、tbody（行循环、高亮、着色、空值 `—`、操作按钮「查看指标」）逐字未动；最外层 `<div>`、script setup 与 scoped style 逐字未动。改造后 DataTable.vue 内 `class="table-wrap"` 恰 1 处、`<table` 恰 1 处、无 `overflow-x` 字面；唯一使用点 Ops.vue（行 95 `<DataTable :table="snapshotTable" ...>`）逐字未动，Ops.vue 内 table-wrap 仍恰 7 处。
- `web/tests/tableOverflowWrapS07.test.js`（新增，226 行）：恰 5 个用例——包裹结构 1 + 内容不回退（表头/行/单元格循环、高亮、着色、空值、操作按钮）1 + 机制锚点（`.table-wrap` 含 `overflow-x: auto`、组件内 table-wrap 恰 1 处且无 overflow-x、table 恰 1 处）1 + 范围守护（Ops 使用行逐字在位、Ops 内 table-wrap 恰 7 处）1 + 复合条件真值表仿真（null/rows 缺失/空/有行四类场景互斥与覆盖）1。文件头如实注明源码结构守卫形态，不冒充真实浏览器渲染/视觉证据。
- 既有测试同步（规约预先列明，S03 教训）：`web/tests/tableOverflowWrapS05.test.js` 块 d 与 `web/tests/tableOverflowWrapS06.test.js` 块 d 各一处——DataTable 断言由 table v-if 原形态改为包裹形态正则与消息，含块标题/注释表述同步；两文件测试块数与计数不变，其余断言逐字未动。
- 与规约偏差：零偏差。未改 theme.css、Ops.vue 与其余任何页面/组件，未改 DataTable 的 script/style/props/文案，未加新样式类/新依赖，未改 `run-tests.ps1` 与上述两文件以外的任何既有测试文件（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s07acc20261018`，日志 `mall-simulator-notes/u06s07-acc-web.log`）：tests=562、pass=562、fail=0，但 npm build 在收尾期被 VM 替换以 SIGTERM 中断（exit=143），该轮证据不完整、不作验收依据；VM 替换后总控按 TOOLS.md 恢复代理中继 18128 与 3307 隔离 MySQL（实查 LISTEN、mysqld ready）后复跑。
- 复跑轮 web 档（RunId `u06s07accb20261018`，日志 `mall-simulator-notes/u06s07-accb-web.log`）：tests=562、pass=562、fail=0，build exit=0；脚本因计数基线仍为 557 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过。
- **计数漂移书面登记**：web 基线 557 → 562，增量 +5 构成全部为本切片新增 `web/tests/tableOverflowWrapS07.test.js` 的 5 个测试块（S05/S06 同步仅改断言形态、块数不变），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 562`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s07gate20261018`，日志 `mall-simulator-notes/u06s07-gate-web.log`）：tests=562 MATCH、pass=562、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`；离线首试因 m2repo 的 `_remote.repositories` 跟踪致父 POM 解析失败、与代码无关，改在线后全过）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器横向滚动走查：窄屏下滚动条出现、拖动交互与视觉排版属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 `.table-wrap` 结构 + web 档源码结构守卫与真值表仿真。
- UP-20261018-43、UP-20261018-46 未在本切片处置，留后续切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-42 **全项关账**（连同 S01 两表、S03 三表、S04 四表、S05 一表、S06 两表，本切片为最后一处，共 13 处站点）；UP-20261018-47 本切片部分关账。
- U06 余项立切片 U06-S08（UP-20261018-43 部分：分析看板四页 Overview/Behavior/Sales/Products 筛选控件标签关联——既有 label 加 `for` 指向新 id、组内输入框各加 id 与区分性 aria-label，文案/样式/行为逐字不变；Ops/AiAssistant 等余项留后续）派发 agy Flash，验收后另行登记。UP-46 排其后；UP-45 维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
