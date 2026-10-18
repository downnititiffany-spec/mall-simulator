# FUNCTION-POLISH-U05 盘点结果报告：U05 AI、报告与决策体验（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U05、§4 问题分级、§3.2 单独裁决项、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ 85cfba5`（含 U04-S03 关账提交；盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U05-INV.md` 只读执行，交付 `mall-simulator-notes/U05-INVENTORY-REPORT.md`（末行标记 `U05INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 518 PASS（RunId `u04s03gate20261018`，基线登记 518）；本包无代码改动，未重跑门禁，基线继续有效。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- 首位候选 UP-20261018-18（Reports 一半）属实：`web/src/views/Reports.vue:89` 模板 `v-if="loading"` 的状态行与行 93 表格 `v-if="!loading && !listError && reports.length"` 使刷新期间已有表格被卸载；`loadPage`（行 261-279）失败分支行 274 显式 `reports.value = []` 抹除旧列表——P2 成立（与 U04-S01 已关账的 RuntimeProfiles 一半同型）。
- UP-20261018-34 属实：`Reports.vue:167` 仅引入 `computed, onMounted, ref, watch`，无 `onUnmounted`/`onBeforeUnmount`；`loadPage` 有 `requestSequence`（行 208、262）但卸载时不递增、且 `api.reportsPage` 调用未传 signal（`web/src/api.js:145-147` 三个报告接口均支持 options 透传、页面未用）；`openReport`（行 350-362）与 `loadArtifact`（行 364-375）无序号守卫，快速连点不同报告存在迟到覆盖——P2 成立。
- UP-20261018-35 事实属实：`Decisions.vue:291,302` 以全局 `prompt(...)` 收集原因/负责人/截止日期（盘点称 `window.prompt`，实为裸 `prompt` 全局调用，语义等价、仅措辞小误）；校验为 fail-closed（空值/日历合法性拦截属实）——交互形态 P2 成立。
- UP-20261018-36 属实：总控 grep 实查 `web/tests/` 无任何针对 `Reports.vue` 模板条件/`reports.value = []` 的断言，现存报告测试仅 `reportRequest`/`reportPageResponse` 纯函数层——组件层守卫空白成立，随实施切片补齐。
- UP-20261018-37 事实属实：`ReportInstanceController.java:61` 的 `POST /api/v1/reports` 创建端点无 Idempotency-Key 形参/处理（总控亲查方法签名与方法体），前端 `creating` 防连击仅防在途连点，超时结果未知后人工重试存在重复创建风险——裁决见下。
- 复核符合抽验属实：AiAssistant 四类结果区分与 `unknown` 快照 fail-closed 拦截（`context.js:37`、`decisionDraft.js:93` 锚点与盘点一致）；Decisions 状态按钮受状态严格门控、无无审批执行入口；评价 `INSUFFICIENT_DATA` 与空值 `—` 口径（与 U02 关账口径衔接）；GlobalExceptionHandler 脱敏。测试覆盖表采信为盘点清单，后端用例计数未逐一复点，不影响分级结论；盘点明确替身证据不冒充真实模型证据，纪律合规。

## 范围收窄（两处，不影响分级）

- 盘点建议首切片把 UP-18（列表刷新）、UP-34（卸载/竞态/AbortSignal）与 UP-36（测试）捆绑为一个 S01，总控收窄为**分两切片**：S01 只处置列表刷新保留这一单一机制（模板两条件 + 删除失败清表一行 + 新增守卫），与 U04-S01 先例同型、独立可验；UP-34 的序号/卸载/Abort 改动面涉及 `openReport`/`loadArtifact`/`loadDecisionOptions` 与 api 调用形态，另立 U05-S02 在 S01 关账后规约处置。盘点 §0.1 第 3 点提及的 `openReport` 失败清 `selectedReport` 问题未编号、不在 S01 内处理，随 S02 触及 `openReport` 时一并评估登记。
- UP-20261018-35（prompt 改内联表单/模态）涉及引入新交互组件，属 §3.2「引入新组件」边缘：不在本轮批准范围，排 U05 后续候选；若推进须先提交影响与方案另行裁决，不与 S01/S02 捆绑。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-18（Reports 一半） | P2 | 成立。纳入首个实施切片 U05-S01；关账后 UP-18 全项关账（RuntimeProfiles 一半已随 U04-S01 关账） |
| UP-20261018-34 | P2 | 成立。排 U05-S02 候选（S01 关账后立规约：openReport/loadArtifact 序号守卫 + 卸载递增序号，AbortSignal 是否引入在规约时按 api 透传现状裁定） |
| UP-20261018-35 | P2 | 成立但涉 §3.2 边缘（新组件），排后续候选，推进前另行裁决 |
| UP-20261018-36 | P2/测试空白 | 成立。随 U05-S01 新增 Reports 刷新保留守卫部分关账；详情竞态守卫随 S02 补齐 |
| UP-20261018-37 | §3.2 待裁决 | 已裁决（见下）：维持现状，不加幂等键 |
| UP-20261018-38～41 | 复核符合 | 采信，不改代码（AI 证据可信度、决策状态机、效果评价说明、错误卫生与脱敏） |

## §3.2 裁决登记

1. **报告生成幂等键（UP-20261018-37）**：属 §3.2「改接口契约」项（为 `POST /api/v1/reports` 新增 `Idempotency-Key` 语义将改变创建去重契约）。总控裁决：**维持现状，不加幂等键**——理由：报告生成是同步校验快照/窗口后落库的受控操作，前端已有 `creating` 在途防连击；重复报告是可见、可区分（独立 id/创建时间）的普通记录，不产生错误数据或资金类后果，与流水线 operationId 裁决（UP-29）口径一致；结果未知时的正确操作是先刷新列表核对是否已生成，而非盲目重放 POST（盘点纪律一致）。此为维持现状登记，不构成变更授权；日后确需幂等，另行提交影响与方案再裁决。

## 首个实施切片

- `U05-S01` Reports 列表刷新平滑保留与失败防丢失修复：仅改 `web/src/views/Reports.vue` 三处（行 89 加载状态行条件改 `loading && reports.length === 0`；行 93 表格条件改 `reports.length`，使失败横幅与旧表格可同屏；`loadPage` catch 删除 `reports.value = []` 一行）；新增 `web/tests/reportsRefreshRetention.test.js` 源码结构守卫（含条件链真值表仿真）。边界：不改 `openReport`/`loadArtifact`/`loadDecisionOptions`、不引入 AbortSignal、不改后端/契约、无新依赖、不改 run-tests.ps1（计数基线由总控验收后登记）。规约见 `mall-simulator-notes/U05-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：结论为源码级核对 + web 518 既有测试证据；未做真实浏览器走查、未做真实慢网络/断网端到端验证；未调用任何真实 LLM（RES-01 审核单列，未在本包采信或销项任何本地实链报告）；真实页面走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
