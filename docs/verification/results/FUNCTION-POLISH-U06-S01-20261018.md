# FUNCTION-POLISH-U06-S01 结果报告：Decisions/Pipeline 宽表横向滚动容器补齐（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U06-S01.md`，修复规约 `mall-simulator-notes/U06-S01-FIX.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06 盘点验收提交 `b0e5d46`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U06-S01-REPORT.md`，末行标记 `U06S01-DONE`；修复轮 `U06-S01-FIX-REPORT.md`，末行标记 `U06S01FIX-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-42（P2，多列宽表缺横向滚动容器，本切片只处置 Decisions 与 Pipeline 两张最宽的表，其余站点为余项）与 UP-20261018-47（P2/测试空白，本切片部分）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Decisions.vue`（仅决策列表一处）：外加 `<div v-if="rows.length" class="table-wrap">` 包裹，`v-if` 由 table 移至包裹 div，内层 table 改为 `<table class="data-table">`，`</table>` 后新增 `</div>`；紧随的 `v-else-if` 空态 div 逐字未动，条件链语义不变；13 列表头与行内操作按钮逐字未动。
- `web/src/views/Pipeline.vue`（仅最近实例表一处）：外加 `<div class="table-wrap">` 包裹，table 行本身逐字未动；10 列表头与空态 `colspan="10"` 逐字未动。
- `web/tests/tableOverflowContainer.test.js`（新增）：5 个用例——Decisions 包裹结构与条件链锚点 1 + Decisions 内容不回退（13 表头与按钮绑定）1 + Pipeline 包裹结构（10 表头与空态）1 + 机制锚点（theme.css `.table-wrap` 含 overflow-x:auto、两视图无 overflow-x 字面）1 + 条件链真值表仿真 1。文件头如实注明源码结构守卫形态，不冒充真实浏览器渲染/视觉证据。
- 与规约偏差：生产代码零偏差。测试文件首版有一处断言范围过宽，经修复轮收窄（见下），修复后与规约意图一致；未改 theme.css 与其余站点，未改 DataTable，未新增依赖，未改既有测试文件，未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u06s01acc20261018`，日志 `mall-simulator-notes/u06s01-acc-web.log`）：tests=532、pass=531、fail=1，build exit=0——**判不合格**。唯一失败为新守卫块 c：其全文件断言 `doesNotMatch(pipelineSource, /<table[^>]*v-if/)` 误伤 Pipeline.vue 行 39 既有的运行结果阶段表（该表本带 `v-if`、不在本切片范围、逐字未动）。生产代码经总控逐行核对无误，缺陷在测试断言范围。
- 修复轮（U06-S01-FIX，同轮跟进）：仅块 c 内该一条断言改为范围收窄——定位 `.table-wrap` 后首个 table 开标签并逐字断言等于 `<table class="data-table">`；其余断言与生产代码零改动。总控核 diff 后复验 web 档（RunId `u06s01fix20261018`，日志 `mall-simulator-notes/u06s01-fix-web.log`）：tests=532、pass=532、fail=0，build exit=0；脚本因计数基线仍为 527 报 DRIFT——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 527 → 532，增量 +5 构成全部为本切片新增 `web/tests/tableOverflowContainer.test.js` 的 5 个测试块，无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 532`（附 2026-10-18 变更注释，含首轮失败与修复经过），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u06s01gate20261018`，日志 `/tmp/v25tests-u06s01gate20261018`，副本 `mall-simulator-notes/u06s01-gate-web.log`）：tests=532 MATCH、pass=532、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。
- 环境注记：代理中继（127.0.0.1:18128）与 3307 隔离 MySQL 在监听，本切片验收本身不连库。

## 未测范围

- 未做真实浏览器走查：窄视口下两表的实际横向滚动呈现、滚动条与卡片观感属 UP-20261018-48 本地审阅清单（用户本地事项，RES-06 沿用签收处置）；本切片证据为源码级包裹结构 + web 档源码结构守卫。
- UP-20261018-42 其余站点（Ops 批次/审计网格、Products、Sales、Reports、AiAssistant、DataTable 组件）未处置，留后续切片逐个评估；商品名截断不在本切片。
- 未连接任何数据库，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-42 本切片两表部分关账（余项继续排队）；UP-20261018-47 本切片部分关账。
- U06 余项立切片 U06-S02（UP-20261018-44，Products/Sales 排序表头键盘可达——总控已裁决 A 方案：th 内嵌原生 button、样式收敛为 theme.css 新增一条 `.sort-btn` 继承规则、排序逻辑逐字不变）派发 agy Flash，验收后另行登记。UP-20261018-43/46 与 UP-42 余项排其后；UP-45 裁决维持现状、UP-48 待本地审阅不变。
- U05 余项 UP-20261018-35 仍等用户裁决，不受本切片影响。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
