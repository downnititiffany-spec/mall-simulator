# FUNCTION-POLISH-U03 盘点结果报告：U03 交互状态与失败恢复（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U03、§4 问题分级、§3.2 单独裁决项、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ 04677b8`（含 U02-S02 关账提交；盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U03-INV.md` 只读执行，交付 `mall-simulator-notes/U03-INVENTORY-REPORT.md`（末行标记 `U03INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 491 PASS（RunId `u02s02gate20261018`，基线登记 491）；本包无代码改动，未重跑门禁，基线继续有效。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- `web/src/views/Overview.vue:27,85`：主体 `<template v-if="!empty && !failed">`，error 态整墙（指标卡、双趋势图盒、质量盒、口径表）从 DOM 卸载，仅显一行 `el-empty` 失败文案——属实（UP-20261018-11，P2 成立）。`web/src/components/AnalysisContext.vue:35-37` 的 error 横幅明文「页面保留的数据为上一次成功结果」，与 Overview 主体行为自相矛盾。
- 其余四分析页同口径抽验属实：`Sales.vue`、`Behavior.vue`、`Products.vue`、`Rfm.vue` 全文无 `!failed` 顶层阻断（全仓 `!failed` 仅 Overview 两处），error 态卡片/表格保留旧数据、图表经 `ChartState` 显局部错误遮罩，与横幅承诺一致。
- 状态模型抽验属实：`useAnalysis.js` 以递增序号（seq，行 22/68/86/88/98）+ `AbortController`（行 69-70）+ pinSnapshot 快照 ID 校验三重防护，`load()` 不清 `data/context`（stale 不闪空），`cancel()`（行 110-114）递增序号并 abort；五分析页均 `onBeforeUnmount(() => analysis.cancel())`（Overview:243、Sales:198、Behavior:152、Products:198、Rfm:215-218）。`chartState.js` 五态规则（error 优先、empty 不冒充 error、仅 ready 可导出）与盘点 §1 一致。
- 错误卫生抽验属实：后端 `ApiResponse` 为 `record(code, message, data, traceId)` 现行字段；`GlobalExceptionHandler` 未处理异常统一脱敏为「系统繁忙，请稍后重试」；前端 `api.js` 的 `handleApiResponse` 成功仅回 `body.data`、失败仅取 `body.message`，**traceId 被剥离、前端未消费未展示**——属实（UP-20261018-21，§3.2 登记，见下裁决）。
- 闪空抽验属实：`Reports.vue:89-93` 列表表格以 `v-if="!loading && !listError && reports.length"` 挂载，刷新置 loading 即销毁旧表；`RuntimeProfiles.vue:26-33` 以 `v-if="loading"` 的 empty-state 替换已有环境表——属实（UP-20261018-18，P2 成立，处置见下）。`ChartState.vue:6-8` error 遮罩为纯文本、无就地重试控件——属实（UP-20261018-19，P2 成立）。
- 恢复入口与竞态复核符合抽验属实：五分析页 error 后筛选栏加载/刷新按钮与 `SnapshotSelector` 均可用，不存在必须整页刷新（F5）才能恢复的分析页；GET 刷新未被盘点建议扩展为 POST 自动重试（UP-20261018-22～25，复核符合采信）。

## 勘误（两处，不影响分级结论）

1. **盘点 §9 建议的 S01 改法有一处逻辑死条件，总控已修正后才立规约**：盘点建议把失败分支改为「仅在 `empty && failed` 时触发」——但 `empty` 与 `failed` 同源于 `useAnalysis` 的单一 `state` 计算属性（`chartState` 返回值互斥：error 时 state 恒为 `error`，`empty` 恒为 false），`empty && failed` 永不成立；且若仅把主体条件改为 `!empty`，首次加载失败（无旧数据）时主体空模板会被渲染、全页失败提示永不出现。故 U03-S01 规约改用总控亲验方案：新增 `hasData`（metrics/salesTrend/activeTrend 任一非空）与 `showBody = failed ? hasData : !empty` 计算属性——error 有旧数据时保留主体（卡片/质量保留、图表经 ChartState 显错误遮罩，与其余四页同口径），error 无旧数据时仍走既有全页失败提示，empty/loading/stale/ready 行为逐字不变。
2. **盘点 §4.2 的 Reports 卸载观察未编号，不另立 UP**：`Reports.vue` 有 `requestSequence` 乱序守卫但无 `onUnmounted`、未传 AbortSignal——属实，但其业务与深挖归 U05（报告体验），随 U05 盘点时一并核清是否成项，本包不跨包扩围、不预设缺陷。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-11 | P2 | 成立。纳入首个实施切片 U03-S01（随 UP-20 一并闭环） |
| UP-20261018-18 | P2 | 成立。不在 U03 跨包扩围：Reports 闪空排 U05 候选、RuntimeProfiles 闪空排 U04 候选（采盘点 §10 方案 A） |
| UP-20261018-19 | P2 | 成立。ChartState 遮罩内就地重试属交互增强，排 U03 后续 P2 候选，S01 关账后按收益排队；不与 S01 捆绑扩围 |
| UP-20261018-20 | P2/测试空白 | 成立。页面 error 态 DOM 保留守卫随 U03-S01 新增源码结构守卫测试补齐（本仓无 SFC 编译器，守卫形态与既有先例一致，不冒充真实 DOM 渲染证据） |
| UP-20261018-21 | §3.2 待裁决 | 已裁决（见下）：维持现状，前端不展示 traceId |
| UP-20261018-22～25 | 复核符合 | 采信，不改代码（状态模型/导出 fail-closed、竞态与卸载防护、错误信息卫生、恢复入口） |

## §3.2 裁决登记

1. **traceId 前端展示（UP-20261018-21）**：属 §3.2「修改接口契约」边缘项（后端字段现行存在，前端消费并展示将改变错误呈现契约）。总控裁决：**维持现状，不在前端横幅展示 traceId**——现行错误文案已说明「发生了什么、当前结果是否可用、下一步可以做什么」（指导书 §5 U03 口径）；排障时技术人员可经浏览器 Network 面板响应体或服务端日志取得 traceId。此为维持现状登记，不构成新展示授权；日后确需面向用户展示，另行提交影响与方案再裁决。
2. **非 useAnalysis 页面闪空治理归属（UP-20261018-18）**：不属 §3.2 事项，按包归属裁定排入 U04（RuntimeProfiles）/U05（Reports）候选，U03 不跨包施工。

## 首个实施切片

- `U03-S01` Overview 大盘错误态旧数据呈现与横幅口径一致性修复：仅改 `web/src/views/Overview.vue`（模板主体条件改经 `showBody` 计算属性 + 新增 `hasData/showBody` 两计算属性，总控修正版方案见上勘误 1）；新增 `web/tests/overviewErrorStateConsistency.test.js` 源码结构守卫（禁旧条件回退、showBody/hasData 形态、失败/空态分支可达、其余四页无 `!failed` 顶层阻断、横幅文案未动、showBody 真值表仿真）。边界：不改 AnalysisContext/ChartState/useAnalysis/chartState、不改其余页面、不改后端/契约、无新依赖、不改 run-tests.ps1（计数基线由总控验收后登记）。规约见 `mall-simulator-notes/U03-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：状态呈现结论为源码级核对 + web 491 既有测试证据；未做真实浏览器走查、未做真实慢网络/断网端到端验证（竞态证据为既有测试替身，已如实标注）；真实页面恢复走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
