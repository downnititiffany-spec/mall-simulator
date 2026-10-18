# FUNCTION-POLISH-U05-S03 结果报告：Decisions 原生 prompt 改页面内对话框（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U05、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U05-S03.md`；裁决书 `mall-simulator-notes/U05-UP35-RULING.md`（方案 A，2026-10-18 用户授权总控裁定）
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U06-S14 关账提交 `d25181f`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U05-S03-REPORT.md`，末行标记 `U05S03-DONE`；修复轮 `U05-S03-FIX-REPORT.md`，末行标记 `U05S03FIX-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-35（P2，Decisions 全链路原生 prompt 输入）关账；UP-20261018-47 同族测试空白口径随本切片补齐（本切片为 U05 测试补齐，非 U06 编号问题，如实注明）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Decisions.vue`：`actionError` 横幅后插入页面内对话框区块（既有 `chart-box` 面板形态，根元素 `@keyup.esc="cancelInputDialog"`、字段 `v-for` 驱动、输入框 `@keyup.enter="confirmInputDialog"`、取消/确定两按钮），与规约逐字一致；script 新增对话框机制四函数（`openInputDialog` 带并发重入 fail-closed 守卫——在途时直接回执 null 不得覆盖在途回执器、`settleInputDialog`、`confirmInputDialog`、`cancelInputDialog`，置于 `act` 之后）；三助手改造——`requiredReason`/`requiredApprovalText` 改 async 经对话框采集（取消/空白 fail-closed、消息逐字不变），`requiredDueDate` 改纯校验（空/格式/`Date.UTC` 日历合法性三段消息逐字保留）；四调用方——`submitDecision` 改 `await`，`approve` 改**单对话框双字段一次采集**（owner + dueDate，消除盘点所指连弹两次；校验顺序与消息：负责人不能为空 → 截止日期不能为空 → 格式 → 日历合法性；busy 置位在全部采集与校验完成后；载荷 `{ owner, dueDate }` 逐字不变），`rejectDecision`/`cancelDecision` 改 `await requiredReason(...)`；`onUnmounted` 在 `cancel()` 前新增 `settleInputDialog(null)` 卸载结算。总控实测：Decisions.vue 内 `prompt(` 调用 **0** 处（改前 2 处），Ops.vue 内 `prompt(` 恰 1 处（重置密码，不在本切片范围、逐字未动）。
- 既有测试同步（规约 §4 预列三文件，逐项落地）：`decisionApprovalInput.test.js`（块 1 对话框字段锚点、块 2 改 `openInputDialog` 与 label 锚点两条、块 4 ownerAt/dueAt 锚点）、`decisionRequiredReason.test.js`（块 2 label 锚点、块 3/4 加 `await`）、`decisionCancelWiring.test.js`（块 2 加 `await`、块 3 onUnmounted 正则含卸载结算行）；`decisionSubmitOwner.test.js` 与 `postJr1WebHardening.test.js` 经逐项核对无需改动（子串匹配，`await` 前缀不影响，实跑通过证实）。
- `web/tests/decisionInputDialogS03.test.js`（新增，实测 343 行）：恰 5 个用例——对话框模板锚点 1 + 机制与零 prompt（全文无 `prompt(`/`alert(`、四机制函数锚点、重入守卫与卸载结算）1 + fail-closed 判定纯函数仿真（取消回执 null 四调用方均中止不置 busy、空白 owner/reason 既有消息、dueDate 空/格式错/2026-02-30 日历错三消息与合法日期原样返回；缺失 `@keyup.esc`、确定按钮绑错、approve 只采单字段三组反向判据）1 + 调用方接线与顺序（四调用方 `await` 采集均在 busy 之前、载荷逐字、act/evaluate 关键行保留）1 + 范围守护（Ops `prompt(` 恰 1 处、Decisions 13 列表格锚点不变、无 `<style`、无 tabindex）1。文件头如实注明源码结构守卫形态，不冒充真实浏览器交互证据。
- 登记偏差两项（均经总控裁决，非静默）：
  1. `decisionApprovalInput.test.js` 块 4：规约 §4 要求保留 `/if \(!owner\) return/` 与规约 §3 给定的 approve 多行实现（`if (!owner) { actionError...; return }`）自相矛盾、原样保留必然失败；执行方首版放宽为 `/if \(!owner\)/` 并在报告中登记。总控不接受放宽口径，修复轮按总控裁决收紧为多行等强形态 `/if \(!owner\) \{\s*actionError\.value = '负责人不能为空'\s*return\s*\}/`（同时锁定错误赋值与 return，判据意图不变）。
  2. 规约 §4 跨切片枚举遗漏第四个文件（见下修复轮），属规约预检疏漏、非执行方偏差，总控登记教训在案。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u05s03acc20261018`，日志 `mall-simulator-notes/u05s03-acc-web.log`）：tests=602、pass=601、fail=1，build exit=0，判**不合格**——唯一失败为 `secondaryReadConcurrency.test.js:57`「Decision 卸载先使旁路 fetch 序号失效，再取消 useAnalysis 请求」：该守卫以正则逐字锁定 `onUnmounted` 旧形态（序号递增后直接 `cancel()`），与经批准新增的卸载结算行必然冲突；U05-S03 规约 §4 枚举锁定 prompt/onUnmounted 形态的既有守卫时遗漏此文件（与 U06-S03 同型教训）。生产代码经总控逐行复核无误。
- 同轮修复轮 `U05-S03-FIX`（规约 `mall-simulator-notes/U05-S03-FIX.md`，agy Flash 仅文件读写，生产代码零改动）：仅两处单行断言——`secondaryReadConcurrency.test.js:58` 正则插入 `settleInputDialog\(null\)\s*`（与 decisionCancelWiring 已同步形态逐字一致，顺序判据不变）；`decisionApprovalInput.test.js` 块 4 按上裁决收紧。总控逐行核 diff 与修复规约零偏差。复验 web 档（RunId `u05s03fix20261018`，日志 `mall-simulator-notes/u05s03-fix-web.log`）：tests=602、pass=602、fail=0，build exit=0（脚本因基线 597 报 DRIFT 为预期）。
- **计数漂移书面登记**：web 基线 597 → 602，增量 +5 构成全部为本切片新增 `web/tests/decisionInputDialogS03.test.js` 的 5 个测试块；既有测试同步仅改断言锚点/形态、块数不变。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 602`（附 2026-10-18 变更注释），未使用 `-All
...[truncated 2144 chars]