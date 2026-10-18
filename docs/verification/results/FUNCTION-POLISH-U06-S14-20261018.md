# FUNCTION-POLISH-U06-S14 结果报告：三处展开面板 Escape 关闭补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S14.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S13 关账提交 `ea92b3f`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S14-REPORT.md`，末行标记 `U06S14-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-46（P2，筛选 Enter 与面板 Escape 缺失，本切片处置分片三即最后一片——三处展开面板 Escape 关闭）与 UP-20261018-47（P2/测试空白，本切片部分）。总控裁决：机制采用面板根元素 `@keyup.esc` 冒泡监听绑定既有关闭函数，不引入全局 `window`/`document` 监听、不加生命周期代码、不改任何 script；守卫口径区别有据——Pipeline `closeRecoveryForm` 与 AiAssistant `closeDraft` 函数体内自带 busy 守卫、直接绑定函数名即与各自取消按钮口径等效，RuntimeProfiles `closeForm` 无内部守卫、模板层以 `@keyup.esc="!saving && closeForm()"` 补齐与两按钮 `:disabled="saving"` 口径一致；AiAssistant 决策草稿卡片经总控评估纳入（盘点登记「随分片三评估」，与前两处同为展开式编辑面板、机制同型、风险同级）。

## 交付内容（总控逐行核 diff）

- `web/src/views/RuntimeProfiles.vue`（1 行改动）：编辑器卡片根元素 `<section v-if="formOpen" class="card editor-card">` 末尾插入 ` @keyup.esc="!saving && closeForm()"`，其余逐字未动；「关闭」「取消」两按钮 `@click="closeForm" :disabled="saving"`、既有 `<form class="profile-form">` 存量结构、script 内 `closeForm` 定义逐字未动。
- `web/src/views/Pipeline.vue`（1 行改动）：恢复表单根元素 `<div v-if="recoveryForm" class="chart-box">` 末尾插入 ` @keyup.esc="closeRecoveryForm"`，其余逐字未动；取消按钮与自带 `recoveryBusy` 守卫的 `closeRecoveryForm` 定义逐字未动。
- `web/src/views/AiAssistant.vue`（1 行改动）：决策草稿卡片根元素 `<div class="chart-box" v-if="draftForm">` 末尾插入 ` @keyup.esc="closeDraft"`，其余逐字未动；取消按钮与自带 `draftBusy` 守卫的 `closeDraft` 定义逐字未动。
- 总控实测：三文件 `@keyup.esc` 各恰 1 处、全仓恰 3 处且均在各面板根元素行；三文件 tabindex 零、无 `keydown`、无 `addEventListener` 新增痕迹。
- `web/tests/panelEscapeCloseS14.test.js`（新增，实测 437 行）：恰 5 个用例——RuntimeProfiles 锚点（根元素守卫表达式 + 两按钮 + closeForm 定义）1 + Pipeline 锚点（根元素 + 取消按钮 + 函数内守卫）1 + AiAssistant 锚点（根元素 + 取消按钮 + 函数内守卫）1 + 范围守护与计数（三文件各 1 处、无全局监听痕迹、既有 form 存量与零新增 form）1 + Escape 关闭判定纯函数正反向仿真（正常态关闭/忙碌态不关闭/焦点在面板外不关闭；缺失监听、绑错函数名、RTP 缺失 `!saving` 守卫三组反向判据）1。文件头如实注明源码结构守卫形态，不冒充真实浏览器键盘事件证据。
- 与规约偏差：**零偏差**。未改三文件 script、任何文案、样式与布局，未改 theme.css 与其余任何页面，未加新依赖，未改任何既有测试文件，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s14acc20261018`，日志 `mall-simulator-notes/u06s14-acc-web.log`）：tests=597、pass=597、fail=0，build exit=0；脚本因计数基线仍为 592 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过，**无修复轮**（首轮即干净通过，跨切片断言预检结论经实跑证实：无既有守卫锁定三处根元素行形态）。
- **计数漂移书面登记**：web 基线 592 → 597，增量 +5 构成全部为本切片新增 `web/tests/panelEscapeCloseS14.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 597`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s14gate20261018`，日志 `mall-simulator-notes/u06s14-gate-web.log`）：tests=597 MATCH、pass=597、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继 18128，巡检开始时中继在位、3307 本轮未用；JDK17，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，日志 `mall-simulator-notes/u06s14-contract.log`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器键盘事件走查：三处面板在焦点位于面板内/外时的真实 Escape 关闭路径、忙碌态（saving/recoveryBusy/draftBusy）下的防误关属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；已知机制边界诚实登记——焦点不在面板内时 Escape 不触发关闭（根元素冒泡监听的固有边界）。本切片证据为源码级 `@keyup.esc` 结构 + web 档源码结构守卫与判定仿真。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-46 **全项关账**（分片一 S12 日期筛选 Enter + 分片二 S13 Ops 新建用户 Enter + 本切片分片三 Escape），UP-20261018-47 本切片部分关账。
- U06 云端可处置项就此完毕：UP-42/43/44/46 全项关账、UP-45 裁决维持现状、UP-47 随各切片分步补齐、UP-49～52 复核符合；仅余 UP-20261018-48 待用户本地真实视觉审阅（RES-06 沿用签收处置，不自动销项）。
- 下一切片：U05 余项 UP-20261018-35 立切片 U05-S03（已裁决方案 A，裁决书 `notes/U05-UP35-RULING.md`：Decisions 页原生 prompt 改页面内对话框），赶在 U07 增量回归前落地；关账后 U05 全项处置完毕，再转 U07。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
