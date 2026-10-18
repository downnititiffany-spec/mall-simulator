# FUNCTION-POLISH-U04-S03 结果报告：Ops 禁用用户二次确认（切片关账，U04 全项处置完毕）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U04、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U04-S03.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U04-S02 关账提交 `2257009`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U04-S03-REPORT.md`，末行标记 `U04S03-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-27（P2，Ops 禁用用户无二次确认，点击即发请求，误触即生效）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Ops.vue`（仅 `toggle` 函数内一处）：在首行守卫 `if (busy.value || loading.value) return` 之后、`busy.value = true` 之前插入规约逐字四行——仅禁用路径（`if (!enable)`）调用 `window.confirm(`确认要禁用用户 ${u.username} 吗？禁用后该用户将无法登录，重新启用前其请求会被拦截。`)`，`if (!confirmed) return` 在置 busy、清 actionError、发请求之前中止，取消零副作用；启用路径不进入该分支、逐字不变。模板、createUser、resetPwd、loadUsers 与其余函数逐字未动。按 RuntimeProfiles `window.confirm` 先例补齐，盘点时总控已批准，非 §3.2 事项。
- `web/tests/opsDisableConfirm.test.js`（新增）：5 个用例——禁用分支存在与完整确认文案、语句顺序不变量（守卫 < confirm < confirmed 拦截 < 置 busy < API 调用）、确认文案全文恰好出现一次且仅在 toggle 内（createUser/resetPwd 未扩散）、模板两处禁用/两处启用按钮保留且带 `:disabled="loading || busy"`（与既有 `opsInteractionHardening` 不变量互证）、决策逻辑仿真（启用不弹确认发请求 / 禁用确认发请求 / 禁用取消不发请求且不置 busy / confirm 缺省或异常按取消 + busy/loading 前置守卫补充组合）。文件头如实注明源码结构守卫形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未改后端、接口契约、权限与状态模型；未处理 UP-20261018-29（已裁决维持现状）；未给启用/重置密码/创建用户顺手加确认；未新增依赖；未改既有测试文件；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u04s03acc20261018`，日志 `/tmp/v25tests-u04s03acc20261018`，副本 `mall-simulator-notes/u04s03-acc-web.log`）：tests=518、pass=518、fail=0，`npm run build` exit=0；脚本因计数基线仍为 513 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 513 → 518，增量 +5 构成全部为本切片新增 `web/tests/opsDisableConfirm.test.js` 的 5 个测试块（禁用分支与文案 1 + 顺序不变量 1 + 文案恰好一次 1 + 模板保留 1 + 决策仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 518`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u04s03gate20261018`，日志 `/tmp/v25tests-u04s03gate20261018`，副本 `mall-simulator-notes/u04s03-gate-web.log`）：tests=518 MATCH、pass=518、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：本轮巡检时 VM 已于 06:39 重启，代理中继（127.0.0.1:18128）与 3307 隔离 MySQL 均未监听；总控按 TOOLS.md 恢复节重新拉起两者并实查监听/ready 后继续（中继 LISTEN、mysqld 8.0.46 ready for connections port 3307）。本切片验收本身不连库。

## 未测范围

- 未做真实浏览器走查（结论为源码级交互安全补齐 + web 档源码结构守卫与决策仿真证据）；禁用确认弹窗的真实页面观感走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库（3307 仅恢复拉起、未做业务操作），未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-27 关账。**U04 全项处置完毕**（UP-18 RuntimeProfiles 一半与 UP-28 随 U04-S01 关账、UP-26 随 U04-S02 关账、UP-27 随本切片关账、UP-29 裁决维持现状、UP-30～33 复核符合；UP-18 的 Reports 一半仍归 U05）。转 U05 盘点（U05-INV 只读盘点规约已立并派发 agy Flash，UP-18 的 Reports 一半为首位既有候选，U03 盘点 §4.2 的 Reports 无 onUnmounted 观察随盘点核清；RES-01 审核单列，不在本包内销项）。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
