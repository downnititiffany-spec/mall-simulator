# FUNCTION-POLISH-U06-S13 结果报告：Ops 新建用户 Enter 提交补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S13.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S12 关账提交 `bbcb9bd`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S13-REPORT.md`，末行标记 `U06S13-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-46（P2，筛选 Enter 与面板 Escape 缺失，本切片处置分片二——Ops 新建用户 Enter 提交）与 UP-20261018-47（P2/测试空白，本切片部分）。总控裁决：仅三个文本类输入（用户名/姓名/初始密码）各加 `@keyup.enter="createUser"`，沿 S12 先例逐输入监听同一 handler、不引入 `<form>`；角色 select 不加（键盘 Enter 在原生 select 上属展开/确认选项的原生语义，加监听会在确认选项时误触创建；且选值后焦点移至密码输入、提交路径完整）；`createUser` 入口自带 `busy/loading` 守卫，回车连击与在途重复触发安全返回。

## 交付内容（总控逐行核 diff）

- `web/src/views/Ops.vue`（3 行改动）：新建用户行三个文本输入各在 `aria-label="..."` 之后、`/>` 之前插入 ` @keyup.enter="createUser"`，其余属性（id/v-model/placeholder/type/style/aria-label）逐字未动；角色 select 与三 option、「创建用户」按钮 `@click="createUser" :disabled="loading || busy"`、script 内 `async function createUser()` 及其首行守卫逐字未动。总控实测：Ops.vue 内 `@keyup.enter` 恰 3 处且均为 `="createUser"`、`<form` 零处。
- `web/tests/opsNewUserLabelS09.test.js`（规约预列的跨切片同步，五处正则：块 a 三处 + 块 b 两处）与 `web/tests/opsToolbarSelectLabelS11.test.js`（规约预列的跨切片同步，块 d 三处正则）：每处在 `aria-label="..."` 与 `\s*\/>` 之间插入 ` @keyup\.enter="createUser"` 正则形态，断言消息、块数与其余断言逐字不变。总控实测两文件 diff 合计 8 处替换，与规约枚举逐项对应。
- `web/tests/opsNewUserEnterSubmitS13.test.js`（新增，实测 308 行）：恰 5 个用例——用户名/姓名锚点 1 + 密码锚点与按钮保留 1 + 范围守护与计数（Ops 内 `@keyup.enter` 恰 3 处且均指向 createUser、角色 select 开标签逐字保留且不含 keyup、零 `<form`、createUser 定义与守卫保留）1 + 行为不回退 1 + Enter 触发判定纯函数正反向仿真（缺失监听/handler 不符/select 被误加/输入缺失四组反向判据）1。文件头如实注明源码结构守卫形态，不冒充真实浏览器键盘事件证据。
- 与规约偏差：**零偏差**。未改 Ops.vue script、任何文案、样式与布局，未改 theme.css 与其余任何页面，未加新依赖，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s13acc20261018`，日志 `mall-simulator-notes/u06s13-acc-web.log`）：tests=592、pass=592、fail=0，build exit=0；脚本因计数基线仍为 587 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过，**无修复轮**（首轮即干净通过，跨切片断言预检与规约预列同步全部命中）。
- **计数漂移书面登记**：web 基线 587 → 592，增量 +5 构成全部为本切片新增 `web/tests/opsNewUserEnterSubmitS13.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 592`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s13gate20261018`，日志 `mall-simulator-notes/u06s13-gate-web.log`）：tests=592 MATCH、pass=592、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继 18128，巡检开始时中继在位、3307 本轮未用；JDK17，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，日志 `mall-simulator-notes/u06s13-contract.log`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器键盘事件走查：新建用户输入框内真实 Enter 触发创建、busy/loading 守卫下的防重复提交、原生 select 的展开与选项选择属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 `@keyup.enter` 结构 + web 档源码结构守卫与判定仿真。
- UP-20261018-46 分片三（RuntimeProfiles 编辑器与 Pipeline 恢复表单 Escape 关闭，及盘点登记的 AiAssistant 决策草稿卡片 Escape）未在本切片处置，排下一切片 U06-S14。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-46 分片二关账，UP-20261018-47 本切片部分关账。
- U06 余项：UP-20261018-46 分片三立切片 U06-S14 派发（总控亲做预检：三处面板均有现成关闭函数——RuntimeProfiles `closeForm` 无内部 saving 守卫、模板层以 `!saving` 守卫补齐与禁用按钮口径一致；Pipeline `closeRecoveryForm` 与 AiAssistant `closeDraft` 均自带 busy 内部守卫、可直接绑定；机制采用面板根元素 `@keyup.esc` 冒泡监听，不引入全局监听与生命周期代码）；UP-20261018-45 裁决维持现状、UP-20261018-48 待本地审阅不变。U06 收尾后，U05 余项 UP-20261018-35（已裁决方案 A，`notes/U05-UP35-RULING.md`）立切片派发，赶在 U07 回归前落地。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
