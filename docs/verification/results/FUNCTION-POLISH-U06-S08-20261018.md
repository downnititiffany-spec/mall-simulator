# FUNCTION-POLISH-U06-S08 结果报告：分析看板四页筛选控件标签关联补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S08.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S07 关账提交 `68594f3`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S08-REPORT.md`，末行标记 `U06S08-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-43（P2，标签关联缺失，本切片处置分析看板四页部分）与 UP-20261018-47（P2/测试空白，本切片部分）。合规先例：Login.vue 的 `<label for>`+`id` 形态、Reports.vue 与 RuntimeProfiles.vue 的同型先例。

## 交付内容（总控逐行核 diff）

- `web/src/views/Overview.vue`（仅筛选栏三行）：既有 label 加 `for="overview-trend-from"`（class 与文案逐字未动）；from/to 两输入各最前加 `id="overview-trend-from"`/`id="overview-trend-to"`、末尾加 `aria-label="趋势起始日期"`/`aria-label="趋势结束日期"`，其余属性逐字未动。
- `web/src/views/Behavior.vue`（仅筛选区三行）：label 加 `for="behavior-trend-from"`；两输入各加 `id="behavior-trend-from"`/`id="behavior-trend-to"` 与 `aria-label="活跃趋势起始日期"`/`aria-label="活跃趋势结束日期"`，其余逐字未动。
- `web/src/views/Sales.vue`（仅筛选区三行）：label 加 `for="sales-trend-from"`；两输入各加 `id="sales-trend-from"`/`id="sales-trend-to"` 与 `aria-label="销售趋势起始日期"`/`aria-label="销售趋势结束日期"`，其余逐字未动。
- `web/src/views/Products.vue`（仅筛选区两行）：label 加 `for="products-page-size"`；输入加 `id="products-page-size"` 与 `aria-label="每页条数"`，`v-model.number`/min/max/style 逐字未动。
- `web/tests/filterLabelAssociationS08.test.js`（新增，实测 275 行）：恰 5 个用例——Overview 关联逐字锚点 1 + Behavior 关联逐字锚点 1 + Sales 关联逐字锚点 1 + Products 关联与七个新 id 局部/跨页唯一性及零 tabindex 1 + v-model 行为不回退与 for→id 配对/命名完整性纯函数仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器/屏幕阅读器证据。
- 与规约偏差：零偏差。未改四页 script、任何文案、样式与布局，未改 theme.css，未改其余任何页面，未加新依赖，未改 `run-tests.ps1` 与任何既有测试文件（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s08acc20261018`，日志 `mall-simulator-notes/u06s08-acc-web.log`）：tests=567、pass=567、fail=0，build exit=0；脚本因计数基线仍为 562 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过。
- **计数漂移书面登记**：web 基线 562 → 567，增量 +5 构成全部为本切片新增 `web/tests/filterLabelAssociationS08.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 567`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s08gate20261018`，日志 `mall-simulator-notes/u06s08-gate-web.log`）：tests=567 MATCH、pass=567、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。无修复轮。

## 未测范围

- 未做真实浏览器/屏幕阅读器走查：label 点击聚焦、屏幕阅读器对起始/结束日期的差异化播报属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 label/id/aria-label 结构 + web 档源码结构守卫与配对仿真。
- UP-20261018-43 其余站点（Ops 新建用户四控件、AiAssistant 提问框、各工具栏 select）与 UP-20261018-46 未在本切片处置，留后续切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-43 分析看板四页部分关账，UP-20261018-47 本切片部分关账。
- U06 余项立切片 U06-S09（UP-20261018-43 部分：Ops 新建用户四控件标签关联——四控件本无可见 label 元素，总控裁决采用 aria-label 补齐无障碍命名并加 id，不新增可见标签、不改布局/文案/行为；AiAssistant 与工具栏 select 余项留后续）派发 agy Flash，验收后另行登记。UP-46 排其后；UP-45 维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
