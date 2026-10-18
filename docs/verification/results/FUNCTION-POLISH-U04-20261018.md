# FUNCTION-POLISH-U04 盘点结果报告：U04 管理操作与任务反馈（只读盘点验收）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U04、§4 问题分级、§3.2 单独裁决项、§6.1 分层验证、§7 证据与动态文档
- 代码版本：开发线 `codex/cloud-v3-development @ 687b75e`（含 U03-S02 关账提交；盘点期间仓库零代码改动，工作树仅既有 untracked `sample-data/`，不属本包）
- 执行：盘点由 Antigravity（Flash）按规约 `mall-simulator-notes/U04-INV.md` 只读执行，交付 `mall-simulator-notes/U04-INVENTORY-REPORT.md`（末行标记 `U04INV-DONE`）；验收由总控亲验，不采信执行方自述数值。
- 基线引用：U00 总控亲跑 default 1563 PASS（RunId `u00base20261018`）、web 505 PASS（RunId `u03s02gate20261018`，基线登记 505）；本包无代码改动，未重跑门禁，基线继续有效。

## 总控亲验记录（抽验锚点）

逐项实查现行源码，与盘点报告对照：

- 首位候选 UP-20261018-18（RuntimeProfiles 一半）属实：`web/src/views/RuntimeProfiles.vue:133-156` 的 `loadProfiles()` 开头即 `profiles.value = []`（行 136）、`sources.value = []`（行 137）；模板行 26 `<div v-if="loading" class="empty-state">` 在刷新期间卸载 `table-wrap`（行 33 起）；`loadProfiles` 被刷新按钮及 saveProfile（行 226）/runTest（行 246）/activate（行 266）/disable（行 285）四条写后路径串行调用，闪空触发面与盘点一致；失败进 catch 后仅置 `pageError`，旧列表已先行清空不可恢复——P2 成立。
- UP-20261018-26 属实：`Pipeline.vue` 实例表头仅 ID/流水线/业务时间/源数据版本/输入批次/目标快照/状态/尝试/操作九列，行内无 `currentStage`/失败码；`currentStage` 仅见于触发结果卡片（行 32）与恢复表单（行 134）——列表概览不可见，P2 成立。
- UP-20261018-27 属实：`Ops.vue` 全文无 `window.confirm`/`confirm` 调用，禁用按钮（行 53-57）直达 `toggle(u, false)`（行 754-768）；对照 `RuntimeProfiles.vue:256,275` 激活/停用均有 `window.confirm` 二次确认——口径不一致属实，P2 成立。
- UP-20261018-28 属实：`web/tests/runtimeProfiles.test.js:58-59` 以 `assert.match` 固化 `profiles.value = []` 与 `sources.value = []` 两行清空断言，且无刷新保留/失败防丢失守卫——测试空白成立，随 U04-S01 同步处置。
- UP-20261018-29 事实属实：`Pipeline.vue:376-388` 人工触发以 `'manual-' + Date.now()` 单次生成 `operationId`，同值作 `sourceDataVersion` 与 `Idempotency-Key`；结果未知后再次点击将生成新键。服务端 `PipelineController:59,76` 该端点带 `@RateLimited(scope = "pipeline-submit", requests = 10)`（总控亲验）——裁决见下。
- 复核符合抽验属实：`SourceWizard.vue:271-276` 同步 watch 任一输入变动即 `clearPreview()` 作废报告与激活中间态（UP-30）；RuntimeProfiles 状态徽标/激活门控/凭据仅引用无明文（页面无 `type="password"`，UP-31）；Pipeline 恢复新鲜度预检/必填原因/确认勾选与轮询世代锁+卸载清理（UP-32）；Pipeline/Ops 分页列表经 `requirePageResponse` 强校验、失败保留旧数据（UP-33，RuntimeProfiles 为该口径的唯一例外，即 UP-18 本身）。测试覆盖表采信为盘点清单，关键空白锚点（runtimeProfiles.test.js:58）已亲验，其余用例计数未逐一复点，不影响分级结论。

## 范围收窄（一处，不影响分级）

- 盘点 §9 首切片建议第 4 点「对孤立的 `testResult` 做出合理收敛」表述含糊，总控收窄为**不在 U04-S01 内处理**：`testResult` 为带环境名的独立结果卡（行 97-101），runTest/activate/disable/save 路径均在各自流程内先行清空（行 240/265/284/228），仅手动刷新保留上一结果；此时页面顶部 `pageError` 横幅（行 18）照常报错，不构成冒充当前成功，清空反而丢失排障证据。S01 只处置列表/来源数据的清空与模板卸载两项核心机制，`testResult` 逐字不动；日后如需收敛另立小切片登记。

## 问题清单处置

| 编号 | 分级 | 处置 |
|---|---|---|
| UP-20261018-18（RuntimeProfiles 一半） | P2 | 成立。纳入首个实施切片 U04-S01（随 UP-28 一并闭环）；Reports 一半仍归 U05 |
| UP-20261018-26 | P2 | 成立。排 U04 后续 P2 候选（S01 关账后按收益排队，拟 U04-S02/S03 序） |
| UP-20261018-27 | P2 | 成立。非 §3.2 事项，总控批准方向（见下裁决 2），排 U04 后续 P2 候选，不与 S01 捆绑 |
| UP-20261018-28 | P2/测试空白 | 成立。随 U04-S01 同步更新陈旧断言并新增刷新保留守卫 |
| UP-20261018-29 | §3.2 待裁决 | 已裁决（见下）：维持现状，不改幂等键生命周期 |
| UP-20261018-30～33 | 复核符合 | 采信，不改代码（向导预览失效/服务端三方哈希、运行环境状态与凭据、流水线恢复纪律、分页失败一致性） |

## §3.2 裁决登记

1. **流水线人工触发幂等键生命周期（UP-20261018-29）**：属 §3.2「改接口契约」边缘项（`operationId` 同时承担 `sourceDataVersion` 与 `Idempotency-Key`，固化其生命周期将改变重试去重语义）。总控裁决：**维持现状（采盘点方案 A），不做表单级固化**——理由：现行单次触发内两用途同值已保证证据关联；服务端该端点有 `@RateLimited(requests = 10)` 限流且触发面为 admin/运维受控入口；结果未知时的正确操作是先刷新列表确认是否已建 run，表单级固化反而可能让两次真实独立触发误用同一键被去重。此为维持现状登记，不构成变更授权；日后确需固化，另行提交影响与方案再裁决。
2. **禁用用户二次确认（UP-20261018-27）**：不属 §3.2 清单（不涉角色权限/指标口径/接口契约/迁移/数据范围/新组件/部署形态），属包内交互安全补齐。总控裁决：**批准按 RuntimeProfiles 既有 `window.confirm` 先例补齐**，排 U04 后续切片实施（禁用前确认、启用不加确认），验收时以源码守卫锁定；本裁决只定方向，切片规约另立。

## 首个实施切片

- `U04-S01` RuntimeProfiles 刷新平滑保留与失败防丢失修复：仅改 `web/src/views/RuntimeProfiles.vue` 两处（`loadProfiles` 删除 `profiles.value = []`/`sources.value = []` 两行；模板行 26 加载占位条件改 `v-if="loading && profiles.length === 0"`，已有数据刷新时表格保持挂载，卡片头既有「正在读取…」指示保留）；同步更新 `web/tests/runtimeProfiles.test.js` 两条固化清空的陈旧断言为 `doesNotMatch`；新增 `web/tests/runtimeProfilesRefreshRetention.test.js` 源码结构守卫（含条件链真值表仿真）。边界：不改 `testResult`、不改后端/契约、无新依赖、不改 run-tests.ps1（计数基线由总控验收后登记）。规约见 `mall-simulator-notes/U04-S01.md`，验收由总控亲跑 web 档后另行登记。

## 未测范围

- 本包为只读盘点：结论为源码级核对 + web 505 既有测试证据；未做真实浏览器走查、未做真实慢网络/断网端到端验证；`POST /runtime-profiles/{id}/test` 的写回副作用仅按源码与规约纪律核对，未实际调用；真实页面走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
