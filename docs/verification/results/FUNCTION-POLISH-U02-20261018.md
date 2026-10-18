# FUNCTION-POLISH-U02 盘点结果报告：U02 指标含义、来源、快照与导出（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U02、§4 问题分级、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ 90f921c`（含 U01-S03 关账提交；盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U02-INV.md` 只读执行，交付 `mall-simulator-notes/U02-INVENTORY-REPORT.md`（末行标记 `U02INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 478 PASS（RunId `u01s03gate20261018`，基线登记 478）；本包无代码改动，未重跑门禁，基线继续有效。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- `web/src/views/Behavior.vue` 的 `fetchBehavior`：内部经 `readEnvelope` 解包 funnel 与 overview 两信封（均含合法 `sourceId`），但返回给 `useAnalysis` 的对象字面量含 snapshotId/businessTime/dataUpdatedAt/source/definitionVersion/qualityStatus/filters/warnings/data，**独漏 `sourceId`**——属实（UP-20261018-09，P1 成立）。下游链路无过错：`readEnvelope`（envelope.js:32）分字段保留 sourceId、`useAnalysis` 与 CSV（csv.js:47）均已透传且有既有测试覆盖，缺口仅在此手工拼装点。
- `web/src/views/Rfm.vue` 的 `fetchRfm`：同样解包 rfm 信封后手工拼装返回对象，**独漏 `sourceId: rfm.sourceId`**——属实（UP-20261018-10，P1 成立）。
- `web/src/views/Overview.vue:27`：主体 `v-if="!empty && !failed"`，error 态隐藏卡片/图表并另显失败空态，与 `AnalysisContext` 横幅「页面保留的数据为上一次成功结果」表述不一致；其余四页 error 态保留旧数据——属实（UP-20261018-11，P2 成立）。
- `web/src/views/Rfm.vue` 的 `segmentRows` 映射：`users: s.users === undefined || s.users === null ? 0 : s.users`，同映射 amount/avgRecencyDays 遇 null 保留 null——属实（UP-20261018-12，P2 成立）。
- 复核符合项抽验属实：`web/src/utils/number.js` 对 null/NaN 统一返回占位符 `—`、真实 0 显示 0/0.00/0.00%；五页均 `pinSnapshot: true`，`useAnalysis` 以序号守卫 + AbortController + 快照 ID 校验拒绝旧响应与串快照；`exportCsv.js` 的 `canDownloadCsv` 仅 ready 放行；默认快照限定 ACTIVE、未知快照空信封经快照 ID 不一致校验进入 error 且不回落（UP-20261018-14～17，复核符合采信）。

## 勘误（收窄一处测试空白结论，不影响 P1/P2 分级）

报告 §6 测试覆盖表遗漏四个既有测试文件，§6.2 空白 3、4 据此收窄：

1. `web/tests/useAnalysisRequestRace.test.js`（7 例，Vite SSR 动态模拟）已覆盖旧响应不覆盖新选择、Abort 取消、固定快照 ID 不匹配/缺失拒绝写入——报告所称「缺乏 useAnalysis 竞态动态模拟测试」不成立。
2. `web/tests/exportFreshnessGuard.test.js`（6 例）已覆盖 loading/stale/error/empty 禁止下载与 `exportAnalysisCsv` 前置守卫——stale 导出拦截已有测试。
3. `web/tests/sourceIdContext.test.js` 与 `web/tests/analysisFivePointContext.test.js` 已覆盖 sourceId 经 readEnvelope → useAnalysis → exportContext → CSV 元信息行的全链透传；`web/tests/secondaryReadConcurrency.test.js`、`web/tests/analysisContext.test.js` 亦未被报告列入。
4. 据此 UP-20261018-13 收窄为：**仅** Behavior/Rfm 两个手工拼装 fetcher 的上下文字段完整性无测试覆盖（这正是 UP-09/10 长期未被门禁捕获的原因）；字面 `UNKNOWN_SNAPSHOT` 空信封的动态用例仍缺，但其拒绝机制（快照 ID 缺失/不一致 → error）已由竞态测试覆盖，登记为低优先空白，不单独立包。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-09 | P1 | 成立。并入首个实施切片 U02-S01 |
| UP-20261018-10 | P1 | 成立。并入首个实施切片 U02-S01 |
| UP-20261018-11 | P2 | 成立。其性质属错误态呈现与恢复一致性，排入 U03（交互状态与失败恢复）候选切片，不在 U02-S01 扩围 |
| UP-20261018-12 | P2 | 成立。登记为 U02 后续 P2 切片候选，S01 关账后排队（改动前需再确认后端 rfmMatrix 八类补 0 口径与前端降级路径的边界） |
| UP-20261018-13 | P2/测试空白 | 按上文勘误收窄后成立：fetcher 字段完整性守护随 U02-S01 补齐 |
| UP-20261018-14～17 | 复核符合 | 采信，不改代码 |

## §3.2 裁决登记

1. **前端跨日聚合（多日客单价算术平均 / 多日 UV 简单累加）**：属 §3.2「改变指标公式或日期口径」。总控裁决：**维持现行契约，禁止前端聚合**——多日客单价须由数仓按总金额/总订单重算、多日 UV 须跨日去重，均为数仓口径；本轮不立项、不改代码。此为维持现状登记，不构成新口径授权；日后确需跨日聚合指标，按 §3.2 另行提交影响与方案，在 ADS 层立项。
2. **商品/RFM 扩展日期范围筛选**：属 §3.2「修改接口契约/改变日期口径」。总控裁决：**维持快照粒度看板定位**，本轮不扩展；日后立项另行裁决。
3. 报告 §9 第 3 项（Overview error 态旧数据保留策略）不属 §3.2 范畴，已按 UP-20261018-11 排入 U03 候选，不在此裁决。

## 首个实施切片

- `U02-S01` 分析页业务来源 ID 透传修复与 fetcher 上下文字段完整性守护：Behavior.vue 与 Rfm.vue 各补 1 行 `sourceId` 透传（与同文件既有字段回退风格一致）；新增 `web/tests/analysisFetcherContext.test.js` 以仓库既有源码结构守卫风格（参照 rfmSnapshotPinning/analysisContext 先例，因 SFC fetcher 未导出、web 未装 SFC 编译器）锁定两处透传不回退。边界：不改后端、不改接口契约、不改指标口径、不处理 UP-11/12。规约见 `mall-simulator-notes/U02-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：未做真实浏览器走查（上下文展示结论为源码级核对 + web 478 既有测试证据）；真实认证浏览器走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
