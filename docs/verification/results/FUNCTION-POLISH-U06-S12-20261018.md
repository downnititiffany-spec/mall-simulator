# FUNCTION-POLISH-U06-S12 结果报告：分析看板三页日期筛选 Enter 提交补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S12.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S11 关账提交 `868e0e8`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S12-REPORT.md`，末行标记 `U06S12-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-46（P2，筛选 Enter 提交缺失，本切片处置分片一——分析看板三页日期筛选）与 UP-20261018-47（P2/测试空白，本切片部分）。总控分片裁决：UP-46 三种机制异质，按单切片闭环分片处置——分片一为三页日期筛选 Enter 提交（本切片）；Ops 新建用户 Enter 提交为分片二、RuntimeProfiles 编辑器与 Pipeline 恢复表单 Escape 关闭为分片三，均排后续。机制裁决：沿 AiAssistant 提问框 `@keyup.enter="ask"` 先例逐输入监听同一 `load` handler，不引入 `<form>`（避免改变三页筛选区结构与隐式提交语义）；Products 每页条数输入（applyFilters 语义）与 Rfm（无筛选输入）裁决不纳入、复核符合。

## 交付内容（总控逐行核 diff）

- `web/src/views/Overview.vue` / `Behavior.vue` / `Sales.vue`（各 2 行改动）：三页六个日期输入各在 `aria-label="..."` 之后、`/>` 之前插入 ` @keyup.enter="load"`，其余属性（id/class/type/v-model/:disabled/style/aria-label）逐字未动；「加载」按钮 `@click="load"` 与 script 内 `const load = () =>` 逐字未动。总控实测计数：三页各 `@keyup.enter` 恰 2 处、AiAssistant 恰 1 处不变、Products/Rfm 恰 0 处、三页均无 `<form`。
- `web/tests/filterLabelAssociationS08.test.js`（规约预列的跨切片同步，仅块 a/b/c 九处正则）：每处在 `aria-label="..."` 与 `\s*\/>` 之间插入 ` @keyup\.enter="load"` 正则形态，断言消息、块数与其余断言（块 d/e）逐字不变。总控实测 diff 为 9 行替换，与规约枚举逐项对应。
- `web/tests/filterEnterSubmitS12.test.js`（新增，实测 285 行）：恰 5 个用例——Overview 锚点 1 + Behavior 锚点 1 + Sales 锚点（含 rangeError 横幅抽锚）1 + 范围守护与计数（三页各恰 2 处、AiAssistant 恰 1 处、Products/Rfm 零、零 `<form`、`const load` 定义保留）1 + Enter 触发判定纯函数正反向仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器键盘事件证据。
- 与规约偏差：**零偏差**。未改三页 script、任何文案、样式与布局，未改 theme.css 与其余任何页面，未加新依赖，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s12acc20261018`，日志 `mall-simulator-notes/u06s12-acc-web.log`）：tests=587、pass=587、fail=0，build exit=0；脚本因计数基线仍为 582 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过，**无修复轮**（与 S11 不同，本切片首轮即干净通过，跨切片断言预检与规约预列同步全部命中）。
- **计数漂移书面登记**：web 基线 582 → 587，增量 +5 构成全部为本切片新增 `web/tests/filterEnterSubmitS12.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 587`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s12gate20261018`，日志 `mall-simulator-notes/u06s12-gate-web.log`）：tests=587 MATCH、pass=587、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继 18128——巡检开始时中继未监听，总控按 TOOLS.md 恢复节拉起并实查 LISTEN 后执行；JDK17，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，日志 `mall-simulator-notes/u06s12-contract.log`；注记：本轮 Maven 本地仓参数中的 `~` 未展开，依赖经中继下载至仓库内字面 `~` 目录后完成测试，该误建目录已移入回收站、不入提交，测试结论不受影响）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器键盘事件走查：日期输入框内真实 Enter 触发加载、loading 守卫下的防重复提交与日期倒置校验拦截属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 `@keyup.enter` 结构 + web 档源码结构守卫与判定仿真。
- UP-20261018-46 分片二（Ops 新建用户 Enter 提交）与分片三（RuntimeProfiles 编辑器与 Pipeline 恢复表单 Escape 关闭，及盘点登记的 AiAssistant 决策草稿卡片 Escape 随分片三评估）未在本切片处置，分片二排下一切片 U06-S13。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-46 分片一关账，UP-20261018-47 本切片部分关账。
- U06 余项：UP-20261018-46 分片二立切片 U06-S13 派发（跨切片断言预检已亲做，见 U-PROGRAM 登记）；分片三排其后；UP-20261018-45 裁决维持现状、UP-20261018-48 待本地审阅不变。U06 收尾后，U05 余项 UP-20261018-35（已裁决方案 A，`notes/U05-UP35-RULING.md`）立切片派发，赶在 U07 回归前落地。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
