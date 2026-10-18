# FUNCTION-POLISH-U02-S02 结果报告：RFM 分层用户数 null 保留（切片关账）——U02 全项处置完毕

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U02、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U02-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U02-S01 关账提交 `293f4dc`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U02-S02-REPORT.md`，末行标记 `U02S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-12（P2，Rfm segmentRows 将 users 的 null/undefined 强转 0，与同映射 amount/avgRecencyDays 的 null 保留口径不一致）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Rfm.vue`：`segmentRows` 映射中 `users` 三元缺失结果由 `0` 改为 `null`（`s.users === undefined || s.users === null ? null : s.users`），仅此一字；数据源优先级、其余字段映射与注释逐字未动。真实数值（含后端矩阵补 0 的真实 0）原样透传，表格经 `formatInteger` 显示「0」；缺失显示「—」，CSV 导出为空，与同行 amount 处理一致。
- `web/tests/rfmMatrixOwnership.test.js`：第三个测试块中写死旧表达式的断言同步更新为新表达式（`? null : s.users`），其余断言逐字未动。
- `web/tests/rfmSegmentUsersNull.test.js`（新增）：5 个用例——segmentRows 片段内 users 保留 null 形态守护、禁 `? 0 : s.users` 强转、三字段（users/amount/avgRecencyDays）空值口径一致守护、表格 `formatInteger(s.users)` 渲染链（含 `formatInteger(null)='—'`、`formatInteger(0)='0'` 下游验证）、映射行为语义仿真（缺失保留 null、真实 0 与正值原样透传）。断言作用域限定在 segmentRows 片段与表格行片段内。
- 与规约偏差：零偏差。未改后端、接口契约、指标口径；未改图表层 `chartOptions.js` 的 `num(r.users) || 0`（既有行为，不在本切片）；未改 number.js/formatInteger；未处理 UP-20261018-11（排 U03）；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u02s02acc20261018`，日志 `/tmp/v25tests-u02s02acc20261018`）：tests=491、pass=491、fail=0，`npm run build` exit=0；脚本因计数基线仍为 486 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 486 → 491，增量 +5 构成全部为本切片新增 `web/tests/rfmSegmentUsersNull.test.js` 的 5 个测试块（保留 null 形态 1 + 禁强转 1 + 三字段口径一致 1 + formatInteger 渲染链 1 + 映射语义仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 491`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u02s02gate20261018`，日志 `/tmp/v25tests-u02s02gate20261018`）：tests=491 MATCH、pass=491、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级修复 + web 档源码结构守卫与下游 formatInteger 动态断言证据）；RFM 页 null 显示走查并入 U07 演示走查或后续按受影响路径登记。
- 图表层对 null 仍按既有 `num(r.users) || 0` 绘图，未在本切片变更，登记为既有行为备查。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-12 关账。至此 U02 全部问题处置完毕：UP-09/10/13 随 U02-S01 关账，UP-12 随本切片关账，UP-14～17 复核符合，UP-11（P2，Overview error 态与横幅不一致）已排 U03 首位候选。转 U03 盘点（U03-INV 只读盘点规约已立并派发 agy Flash）。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
