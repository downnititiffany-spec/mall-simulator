# FUNCTION-POLISH-U03-S02 结果报告：ChartState 错误遮罩就地重试（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U03、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U03-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U03-S01 关账提交 `9bd99a8`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U03-S02-REPORT.md`，末行标记 `U03S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-19（P2，ChartState error 遮罩为纯文本、无就地重试控件，图表失败后须移至页面顶部筛选栏恢复，动线长）。

## 交付内容（总控逐行核 diff）

- `web/src/components/ChartState.vue`：error 遮罩内错误文案之后新增重试按钮一行（逐字采用规约文本：`v-if="retryable"`、`@click="emit('retry')"`、文案「重试」）；`defineProps` 新增 `retryable: { type: Boolean, default: false }`（默认关，组件单独使用时不显示，杜绝无监听的死按钮）；新增 `const emit = defineEmits(['retry'])`；样式末尾追加可选的 `.state-retry { flex: none; }` 一条。loading/empty/透传 BaseChart 分支与既有三态文案逐字未动。
- 五分析页全部 10 处 `<ChartState ... />` 用法统一追加 `retryable @retry="load"`：Overview 2 处、Sales 3 处、Behavior 2 处、Products 2 处、Rfm 1 处。各页既有 `load` 处理器逐字未动——重试语义即以当前页面参数重跑既有 load（Products 为 `analysis.load(requestParams())` 按当前筛选/分页/排序重跑），不新增请求路径、不改 useAnalysis/chartState 状态模型。
- `web/tests/chartStateRetry.test.js`（新增）：6 个用例——error 分支重试按钮形态、retryable prop 与 emit 声明、按钮仅 error 分支隔离（loading/empty 片段不含 `emit('retry')`）、五页 10 处接线逐页计数守护、五页 load 处理器保留（Products 另断言 `analysis.load(requestParams())` 形态）、既有三态文案逐字守护。文件头如实注明源码结构守卫形态，不冒充真实 DOM 渲染/点击证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差（执行方报告称新测试 158 行，实测 157 行，仅报告计数小误，不影响内容）。未改 useAnalysis.js、chartState.js、BaseChart.vue、AnalysisContext.vue 及任何后端文件；未改接口契约与指标口径；未处理 UP-20261018-18（排 U04/U05）；未给 loading/empty 态加按钮；未新增依赖；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u03s02acc20261018`，日志 `/tmp/v25tests-u03s02acc20261018`）：tests=505、pass=505、fail=0，`npm run build` exit=0；脚本因计数基线仍为 499 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 499 → 505，增量 +6 构成全部为本切片新增 `web/tests/chartStateRetry.test.js` 的 6 个测试块（error 按钮形态 1 + prop/emit 声明 1 + 分支隔离 1 + 五页接线守护 1 + load 保留 1 + 三态文案 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 505`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u03s02gate20261018`，日志 `/tmp/v25tests-u03s02gate20261018`）：tests=505 MATCH、pass=505、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级修复 + web 档源码结构守卫证据）；error 遮罩重试按钮的真实页面点击恢复走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-19 关账。U03 全部问题处置完毕：UP-20261018-11/20 随 U03-S01 关账、UP-20261018-19 随本切片关账、UP-20261018-18 已按包归属排 U04（RuntimeProfiles 刷新闪空）/U05（Reports 刷新闪空及无 onUnmounted 观察核清）、UP-20261018-21 已裁决维持现状、UP-20261018-22～25 复核符合。**U03 全项处置完毕**，转 U04 盘点（U04-INV 已派发）。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
