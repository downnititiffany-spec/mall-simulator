# FUNCTION-POLISH-U06-S11 结果报告：Ops 工具栏六处裸 select 标签关联补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S11.md`，修复规约 `mall-simulator-notes/U06-S11-FIX.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S10 关账提交 `0981d48`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S11-REPORT.md`，末行标记 `U06S11-DONE`；修复轮 `U06-S11-FIX-REPORT.md`，末行标记 `U06S11FIX-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-43（P2，标签关联缺失，本切片处置余项最后一处——Ops 工具栏六处裸 select，关账后 UP-43 全项处置完毕）与 UP-20261018-47（P2/测试空白，本切片部分）。合规依据：U06 盘点对 UP-43 的处置建议明列「补充 `<label for="id">`、包裹式 label 或 `aria-label`」三式并列；U06-S09/S10 已关账先例。总控裁决：六控件本无可见 label 元素，沿 S09/S10 先例采用 `aria-label` 一式并加 `id` 补齐无障碍命名，不新增可见标签、不改布局/文案（含全部 option）/行为。总控普查结论：全仓其余工具栏 select（Ops 四组 8 处、Decisions 2 处、Pipeline 3 处、Reports 1 处、SnapshotSelector 1 处）均为包裹式 `<label>` 关联或 label-for 关联、复核符合，不在本切片。

## 交付内容（总控逐行核 diff）

- `web/src/views/Ops.vue`（6 行改动）：用户列表排序/每页、问答审计排序/每页、调用审计排序/每页六处裸 select 各在开标签最前加 `id`（ops-user-sort / ops-user-page-size / ops-ai-history-sort / ops-ai-history-page-size / ops-ai-call-sort / ops-ai-call-page-size）、末尾加区分性 `aria-label`；六控件 `v-model`/`v-model.number`、`:disabled`、`@change` 与全部 option 逐字未动。总控实测计数：Ops.vue 内 `id="..."` 恰 10（各新 id 恰 1 次）、`aria-label="..."` 恰 10、`<label` 恰 8 不变、`tabindex` 零。
- `web/tests/opsNewUserLabelS09.test.js`（规约预列的跨切片同步，仅块 c 一处）：Ops 全页 `id="..."` 总数断言 4→10 及紧邻注释/断言消息同步，块数与余断言逐字不变。
- `web/tests/opsToolbarSelectLabelS11.test.js`（新增，实测 412 行，含修复轮 +6 行）：恰 5 个用例——用户列表两控件锚点 1 + 审计四控件锚点 1 + id 唯一性与计数（六新 id 各恰 1 次、总数恰 10/10、零 tabindex）1 + 范围守护与行为不回退（`<label` 恰 8、四组包裹式 label 抽锚、S09 四控件保留、六 `@change` 函数名模板与 script 双在位）1 + 命名完整性纯函数正反向仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器/屏幕阅读器证据。
- 与规约偏差：生产代码与 S09 同步零偏差；新增测试块 e 的重复标签检出逻辑有缺陷（见下修复轮），经修复后与规约意图一致。未改 Ops.vue script、任何文案（含全部 option 与分页计数文案）、样式与布局，未改 theme.css 与其余任何页面，未加新依赖，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s11acc20261018`，日志 `mall-simulator-notes/u06s11-acc-web.log`）：tests=582、pass=581、fail=1，build exit=0，判**不合格**。唯一失败为新增守卫自身块 e 反向仿真 4（`opsToolbarSelectLabelS11.test.js:405`「必须检出重复 aria-label」）：其纯函数仅把与契约文案完全一致的标签收进重复检测集合，重复项因先落入「文案不符」分支而漏检（`passed` 仍为 false、专断言为 false）。生产代码无误（块 a～d 全过、diff 逐字合规）。
- 同轮修复轮 U06-S11-FIX（仅改新测试块 e 纯函数：新增全量标签收集 `allAriaLabels`，重复检测改为基于全部在场非空 aria-label 计算并随返回值带出；生产代码与反向仿真断言逐字未动、未删减或放宽断言）：总控核 diff 与修复规约一致后复验（RunId `u06s11fix20261018`，日志 `mall-simulator-notes/u06s11-fix-web.log`）：tests=582、pass=582、fail=0，build exit=0；脚本因计数基线仍为 577 报 DRIFT（exit=7）——属新增测试的预期计数变化，非测试失败，判内容全过。
- **计数漂移书面登记**：web 基线 577 → 582，增量 +5 构成全部为本切片新增 `web/tests/opsToolbarSelectLabelS11.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 582`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s11gate20261018`，日志 `mall-simulator-notes/u06s11-gate-web.log`）：tests=582 MATCH、pass=582、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记后一次；在线经本地中继，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器/屏幕阅读器走查：屏幕阅读器对六控件 aria-label 的实际播报属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级 id/aria-label 结构 + web 档源码结构守卫与命名仿真。
- UP-20261018-46（筛选 Enter 提交与面板 Escape 关闭）未在本切片处置，排下一切片 U06-S12（立规约前跨切片预检要点已登记于 U-PROGRAM：S08 守卫逐字锁定三页六个日期输入行形态，加 `@keyup.enter` 须将 S08 相应断言同步列入允许改动）。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-43 **全项关账**（S08 四页标签关联 + S09 新建用户四控件 + S10 提问框 + S11 工具栏六 select），UP-20261018-47 本切片部分关账。
- U06 余项：UP-20261018-46 排下一切片 U06-S12；UP-20261018-45 裁决维持现状、UP-20261018-48 待本地审阅不变。U06 收尾后，U05 余项 UP-20261018-35（已裁决方案 A，`notes/U05-UP35-RULING.md`）立切片派发，赶在 U07 回归前落地。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
