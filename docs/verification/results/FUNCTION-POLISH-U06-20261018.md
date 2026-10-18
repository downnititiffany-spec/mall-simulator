# FUNCTION-POLISH-U06 盘点结果报告：U06 视觉、键盘与响应体验（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U06、§4 问题分级、§3.2 单独裁决项、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ 1ab9d5d`（含 U05-S02 关账提交；盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U06-INV.md` 只读执行，交付 `mall-simulator-notes/U06-INVENTORY-REPORT.md`（末行标记 `U06INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 527 PASS（RunId `u05s02gate20261018`，基线登记 527）；本包无代码改动，未重跑门禁，基线继续有效。
- 云端边界：真实视觉审阅（1366×768 / 1920×1080、125%/150% 放大、肉眼走查）为用户本地事项（RES-06 沿用签收处置）；本盘点只下源码结构结论，不声称已截图或已视觉验收。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- UP-20261018-42 属实：`web/src/styles/theme.css:334` 的 `.table-wrap` 含 `overflow-x: auto`（且为带边框/圆角的卡片式容器，Ops 系统用户表与 RuntimeProfiles 环境表已在其内使用，有先例）；兼容层行 604 的 `.chart-box, .table-box` 仅为卡片别名、无横向滚动。宽表裸置属实——Decisions 决策列表 13 列（总控逐个点数表头：编号/标题/来源/目标指标/基线/建议快照/基线快照/口径版本/负责人/截止日期/状态/效果/操作）直接置于 `.chart-box`；Pipeline 最近实例表 10 列（含 U04-S02 新增「当前阶段」列、空态 colspan=10）同样裸置；Ops 采集批次 9 列与双列网格并排审计表、Products 8 列、Sales 6 列、Reports 列表 7 列、AiAssistant 动态列、DataTable 通用组件（根为裸 `<div>`）均无滚动容器——P2 成立。
- UP-20261018-43 属实：Overview 趋势日期 `<label>` 无 `for` 且未包裹、两 date 输入无 `id`/`aria-label`（总控亲查模板行 11-14）；Ops 新建用户 4 控件仅 placeholder、无任何 `<label>`/`aria-label`（行 20-26）；AiAssistant 核心提问输入无 label/id/aria-label（行 20）。合规先例（SnapshotSelector、SourceWizard、RuntimeProfiles、Reports 表单的 `for`/`id` 映射）经抽验属实——P2 成立。
- UP-20261018-44 属实：Products（行 38-42）与 Sales（行 58-60）的排序表头为原生 `<th @click>`，无 `tabindex`、无按键监听；总控 grep 实查全仓 `web/src/` 零 `tabindex`——纯键盘不可达成立，P2。
- 图表生命周期复核属实（UP-51）：`BaseChart.vue` 统一托管——`onMounted` 注册 `window resize`、`onBeforeUnmount` 移除监听并 `chart.dispose()`、高度变化 `watch(props.height) → nextTick(resize)`；全仓无 `ResizeObserver`、无绕过统一组件的散落 `echarts.init`。
- 焦点与语义复核属实（UP-49/50）：`theme.css:119` 全局 `:focus-visible` 2px 描边在位；两处 `outline: none`（行 437、680）均伴 `box-shadow` 光环替代；全仓零正值 `tabindex`、交互元素均为语义化 button/router-link。
- 等待与规模复核属实（UP-52）：各页等待反馈形态与 U03/U04/U05 已关账形态衔接，核心长列表均为服务端分页；测试覆盖表采信为盘点清单——`web/tests/` 中仅 sourceWizard 一例涉及可见标签断言，U06 结构守卫空白（UP-47）成立。

## 勘误两处（不影响分级）

- 图表实例计数：盘点 §1.4 与 UP-51 称「8 个图表实例」，总控实查 `<ChartState>` 用法为 **10 处**（Overview 2、Behavior 2、Products 2、Rfm 1、Sales 3），盘点自身 §4 对照表亦列 10 行；计数以 10 为准，「全量经 BaseChart 托管」的结论不变。
- UP-45 的示例不成立：盘点称侧边栏在 1200px 断点由 240px 收缩至 200px 时「视口不变、图表不触发重绘」——断点跨越本身即窗口宽度变化，必然触发 `window resize`，该示例不能证明缺口。容器尺寸在窗口不变时变化的真实场景在本项目中很窄（无侧栏折叠开关、无容器内布局切换），故 UP-45 按下文降级处置。

## 范围收窄（不影响分级）

盘点建议首切片 U06-S01 一次包裹六处以上宽表并改 DataTable 与 Products 商品名截断，总控收窄为**仅 Decisions（13 列）+ Pipeline（10 列）两张最宽、确定性最高的表**：同一机制（外加 `.table-wrap` 包裹层）、两文件、独立可验，与 U04/U05 的单机制切片先例一致。UP-42 其余站点（Ops 批次/审计网格、Products/Sales、Reports、AiAssistant、DataTable）保留为 UP-42 余项，排后续切片逐个处置，不在本切片扩围；商品名截断涉及呈现口径（截断+title 补全），与滚动容器分开评估。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-42 | P2 | 成立。首切片 U06-S01 只处置 Decisions+Pipeline 两表；其余站点为余项排后续切片 |
| UP-20261018-43 | P2 | 成立。排 U06 后续候选（标签关联补齐，可按页面群分片），不与 S01 捆绑 |
| UP-20261018-44 | P2 | 成立。总控裁决采用 A 方案（表头内嵌原生 button），见下；排后续切片 |
| UP-20261018-45 | P2→观察 | 示例勘误后影响面收窄：裁决维持现状不引入 ResizeObserver，见下；本地审阅若发现图表错位再立项 |
| UP-20261018-46 | P2 | 成立（筛选 Enter 提交、展开面板 Escape 关闭缺失）。排后续候选，不与 S01 捆绑 |
| UP-20261018-47 | P2/测试空白 | 成立。随各实施切片分步补齐（S01 新增宽表滚动容器守卫为第一步） |
| UP-20261018-48 | 待本地审阅 | 登记。盘点第 8 节本地审阅清单采信，供用户本地按两视口+放大+键盘遍历逐项核查；RES-06 沿用签收处置，不自动销项 |
| UP-20261018-49～52 | 复核符合 | 采信，不改代码（焦点环基准、语义元素与 tabindex、图表生命周期、等待反馈与列表规模） |

## 总控裁决登记

1. **排序表头键盘方案（UP-20261018-44）**：采用盘点 A 方案——排序列 `<th>` 内将标题与排序箭头包入原生 `<button type="button">`，原生获得可聚焦与 Enter/Space 触发，沿用全局 `:focus-visible` 焦点环；不采用 B 方案（th 上 tabindex+role+按键监听的自维护语义）。此为原生元素用法，不引入新组件、不属 §3.2 事项；具体样式收敛（无边框、继承表头排版）在立切片规约时锁定。本轮仅裁决登记，不实施。
2. **BaseChart ResizeObserver（UP-20261018-45）**：维持现状，不引入。理由：勘误后所举断点场景实际会触发 window resize；现行「window resize + 动态高度 watch」已覆盖本项目的实际尺寸变化路径，容器级 Observer 目前无已证实的失败场景。为无证实收益改动全站图表底座不符合单切片的问题依据原则；若用户本地审阅（UP-48 清单）发现具体错位，再以该证据立项。

## 首个实施切片

- `U06-S01` Decisions/Pipeline 宽表横向滚动容器补齐：仅在 `web/src/views/Decisions.vue` 决策列表与 `web/src/views/Pipeline.vue` 最近实例表外各加一层 `<div class="table-wrap">`（Decisions 的 `v-if="rows.length"` 由 table 移至包裹 div、其后 `v-else-if` 空态链保持绑定）；新增 `web/tests/tableOverflowContainer.test.js` 源码守卫 5 块。边界：不改其余页面与 DataTable、不改样式文件（`.table-wrap` 为既有类）、不改后端/契约、无新依赖、不改 run-tests.ps1（计数基线由总控验收后登记）。规约见 `mall-simulator-notes/U06-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：结论为源码级核对 + web 527 既有测试证据；未做真实浏览器走查、截图或肉眼审阅（UP-48/RES-06，用户本地事项）；源码守卫不得冒充视觉证据。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
