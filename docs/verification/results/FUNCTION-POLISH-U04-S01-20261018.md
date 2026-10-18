# FUNCTION-POLISH-U04-S01 结果报告：RuntimeProfiles 刷新平滑保留与失败防丢失修复（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U04、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U04-S01.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U04 盘点验收提交 `024bee8`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U04-S01-REPORT.md`，末行标记 `U04S01-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-18（P2，RuntimeProfiles 刷新闪空且失败丢旧数据，本切片处置其中 RuntimeProfiles 一半；Reports 一半归 U05）、UP-20261018-28（P2/测试空白，runtimeProfiles.test.js 固化先行清空的陈旧断言）。

## 交付内容（总控逐行核 diff）

- `web/src/views/RuntimeProfiles.vue`：模板行 26 加载占位条件由 `v-if="loading"` 改为 `v-if="loading && profiles.length === 0"`（文案与 class 逐字未动）；`loadProfiles()` 删除开头 `profiles.value = []` 与 `sources.value = []` 两行先行清空，函数其余（loading 置位、pageError 重置、Promise.allSettled 双路、requireRuntimeProfileList 校验、catch/finally）逐字未动。效果：已有数据时刷新不再卸载整表（卡片头「正在读取…」指示保留），刷新失败旧列表/旧来源完整保留并由顶部 pageError 横幅报错；首次加载占位、首次失败错误占位、真空态三分支均照常。
- `web/tests/runtimeProfiles.test.js`：仅测试块「环境列表 API 的错误结构不能伪装成空列表」内两条固化清空断言由 `assert.match` 改为 `assert.doesNotMatch`（profiles/sources 各一），该块其余断言与其他测试块逐字未动。
- `web/tests/runtimeProfilesRefreshRetention.test.js`（新增）：3 个用例——loadProfiles 不先行清空且保留校验赋值与完整生命周期、模板四分支（加载占位/错误占位/真空态/表格）结构守护与卡片头进度指示保留、四分支判定顺序真值表仿真（含规约五种组合外加一种刷新出错有旧数据组合）。文件头如实注明源码结构守卫形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未处理 testResult 收敛（按总控盘点收窄裁决）；未改后端、接口契约、权限与状态模型；未处理 UP-20261018-26/27（排后续切片）；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u04s01acc20261018`，日志 `/tmp/v25tests-u04s01acc20261018`，副本 `mall-simulator-notes/u04s01-acc-web.log`）：tests=508、pass=508、fail=0，`npm run build` exit=0；脚本因计数基线仍为 505 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 505 → 508，增量 +3 构成全部为本切片新增 `web/tests/runtimeProfilesRefreshRetention.test.js` 的 3 个测试块（loadProfiles 保留与生命周期 1 + 模板四分支守护 1 + 真值表仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 508`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u04s01gate20261018`，日志 `/tmp/v25tests-u04s01gate20261018`，副本 `mall-simulator-notes/u04s01-gate-web.log`）：tests=508 MATCH、pass=508、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级修复 + web 档源码结构守卫证据）；刷新保留的真实页面观感走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-18 的 RuntimeProfiles 一半关账（Reports 一半仍归 U05）；UP-20261018-28 关账。U04 余项：UP-20261018-26（P2，Pipeline 实例表无当前/失败阶段与失败码列）立切片 U04-S02 派发 agy Flash（纯呈现补齐——两字段已在列表行对象内、恢复表单已直接消费，不改后端与契约；验收后另行登记）；UP-20261018-27（P2，Ops 禁用用户二次确认）排其后候选；UP-20261018-29 已裁决维持现状；UP-20261018-30～33 复核符合。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
