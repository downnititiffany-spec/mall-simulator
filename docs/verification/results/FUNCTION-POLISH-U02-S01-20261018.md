# FUNCTION-POLISH-U02-S01 结果报告：分析页业务来源 ID 透传修复与 fetcher 字段完整性守护（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U02、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U02-S01.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U02 盘点验收提交 `f4a7aa4`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U02-S01-REPORT.md`，末行标记 `U02S01-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-09（P1，Behavior fetchBehavior 独漏 sourceId）、UP-20261018-10（P1，Rfm fetchRfm 同漏）、UP-20261018-13（P2/测试空白，经总控勘误收窄为仅 fetcher 字段完整性无覆盖）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Behavior.vue`：`fetchBehavior` 返回对象在 `snapshotId` 之后、`businessTime` 之前新增一行 `sourceId: funnel.sourceId || overview.sourceId,`，与同对象既有主/备回退风格一致；其余逐字未动。
- `web/src/views/Rfm.vue`：`fetchRfm` 返回对象同位置新增一行 `sourceId: rfm.sourceId,`，业务来源以主响应 rfm 为准，不被 users 聚合信封覆盖；其余逐字未动。
- `web/tests/analysisFetcherContext.test.js`（新增）：8 个源码结构守卫用例，断言作用域限定在对应 fetcher 片段内——Behavior/Rfm 透传形态与字段位置、禁写死字面量/禁从 params 补造/禁 users 覆盖，及 Overview/Sales/Products 直通信封三页不退化为手工拼装、五页完整性守护。
- 与规约偏差：零偏差。未改后端、接口契约、指标/日期口径与 useAnalysis/envelope/context/csv 等下游文件；未处理 UP-20261018-11（排 U03）与 UP-20261018-12（另行排队为 U02-S02）；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u02s01acc20261018`，日志 `/tmp/v25tests-u02s01acc20261018`）：tests=486、pass=486、fail=0，`npm run build` exit=0；脚本因计数基线仍为 478 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 478 → 486，增量 +8 构成全部为本切片新增 `web/tests/analysisFetcherContext.test.js` 的 8 个测试块（Behavior/Rfm 透传与禁补造守护 4 + 直通三页形态守护 3 + 五页完整性 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 486`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u02s01gate20261018`，日志 `/tmp/v25tests-u02s01gate20261018`）：tests=486 MATCH、pass=486、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级修复 + web 档源码结构守卫与既有下游动态测试证据）；真实浏览器上下文展示走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-09、UP-20261018-10、UP-20261018-13 关账。U02 余项仅 UP-20261018-12（P2，Rfm segmentRows 将 users 的 null 强转 0）：总控已亲验边界——后端 `RfmService.rfmMatrix` 八类补 0 由后端以真实 0 值下发，前端保留 null 不影响矩阵补 0 口径；仅降级 rfmSegments 路径与异常 null 行受影响。立 U02-S02 切片派发 agy Flash。UP-20261018-11 已排 U03 候选。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
