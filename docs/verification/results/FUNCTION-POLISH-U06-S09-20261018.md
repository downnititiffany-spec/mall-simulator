# FUNCTION-POLISH-U06-S09 结果报告：Ops 新建用户四控件标签关联补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S09.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S08 关账提交 `2d4ce19`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S09-REPORT.md`，末行标记 `U06S09-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-43（P2，标签关联缺失，本切片处置 Ops 新建用户四控件部分）与 UP-20261018-47（P2/测试空白，本切片部分）。合规依据：U06 盘点对 UP-43 的处置建议明列「补充 `<label for="id">`、包裹式 label 或 `aria-label`」三式并列；U06-S08 已关账先例。总控裁决：四控件本无可见 label 元素，采用 `aria-label` 一式加 `id` 补齐无障碍命名，不新增可见标签、不改布局/文案/行为。

## 交付内容（总控逐行核 diff）

- `web/src/views/Ops.vue`（仅新建用户行四控件，4 行改动）：用户名/姓名/角色 select/初始密码四控件各最前加 `id`（`ops-new-username`/`ops-new-real-name`/`ops-new-role`/`ops-new-password`）、末尾加区分性 `aria-label`（新用户用户名/新用户姓名/新用户角色/新用户初始密码）；`v-model`/`placeholder`/`type`/`style`、三个 option、创建按钮行、区块标题逐字未动；未新增可见 label 元素，Ops.vue 保持零 `tabindex`。
- `web/tests/opsNewUserLabelS09.test.js`（新增，实测 253 行）：恰 5 个用例——用户名与姓名逐字锚点 1 + 角色与密码逐字锚点（含三 option）1 + 四个新 id 各恰出现 1 次且全页 id 总数恰 4、零 tabindex 1 + 四个 v-model/placeholder/type/创建按钮行行为不回退 1 + 命名完整性纯函数正反向仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器/屏幕阅读器证据。
- 与规约偏差：零偏差。未改 Ops.vue script、任何文案（含 placeholder）、样式与布局，未改 theme.css 与其余任何页面，未加新依赖，未改 `run-tests.ps1` 与任何既有测试文件（计数基线由总控验收后登记，见下）。跨切片断言预检经规约与关账双重复核无冲突（既有守卫仅锁定创建按钮行与 createUser 函数体，均不在本切片范围）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s09acc20261018`，日志 `mall-simulator-notes/u06s09-acc-web.log`）：tests=572、pass=572、fail=0，build exit=0；脚本因计数基线仍为 567 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过。
- **计数漂移书面登记**：web 基线 567 → 572，增量 +5 构成全部为本切片新增 `web/tests/opsNewUserLabelS09.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 572`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s09gate20261018`，日志 `mall-simulator-notes/u06s09-gate-web.log`）：tests=572 MATCH、pass=572、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。无修复轮。

## 未测范围

- 未做真实浏览器/屏幕阅读器走查：屏幕阅读器对四控件 aria-label 的实际播报属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 id/aria-label 结构 + web 档源码结构守卫与命名仿真。
- UP-20261018-43 其余站点（AiAssistant 提问框、各工具栏 select）与 UP-20261018-46 未在本切片处置，留后续切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-43 Ops 新建用户四控件部分关账，UP-20261018-47 本切片部分关账。
- U06 余项立切片 U06-S10（UP-20261018-43 部分：AiAssistant 提问框标签关联——提问框本无可见 label 元素，总控裁决沿 S09 先例采用 `aria-label` 并加 id，不改 placeholder/Enter 发送/禁用逻辑；各工具栏 select 余项留后续）派发 agy Flash，验收后另行登记。UP-46 排其后；UP-45 维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
