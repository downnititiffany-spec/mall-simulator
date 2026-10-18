# FUNCTION-POLISH-U04-S02 结果报告：Pipeline 实例表当前阶段与失败码就地回显（切片关账）

- 日期：2026-10-18（Asia/Shanghai）
- 依据：《现有功能完善实施指导书 V1.0》§5 U04、§4 单切片闭环、§6.1 分层验证、§7 证据与动态文档；规约 `mall-simulator-notes/U04-S02.md`
- 代码版本：开发线 `codex/cloud-v3-development`，基线含 U04-S01 关账提交 `7e04c78`；本切片提交随本报告一并推送（见 PROJECT_STATUS 登记）
- 执行：Antigravity（Flash）按规约仅文件读写交付（`U04-S02-REPORT.md`，末行标记 `U04S02-DONE`）；验收由总控亲跑，不采信执行方自述数值。
- 关联问题：UP-20261018-26（P2，Pipeline 实例表无当前/失败阶段与失败码列，须打开恢复表单才能看到）。

## 交付内容（总控逐行核 diff）

- `web/src/views/Pipeline.vue`（仅实例表三处）：表头在「状态」之后、「尝试」之前插入 `<th>当前阶段</th>`；行内在状态徽标单元格之后插入 `<td class="mono">{{ r.currentStage || '—' }}<span v-if="r.status === 'FAILED' && r.errorCode" style="margin-left:6px;color:#dc2626">{{ r.errorCode }}</span></td>`（任何状态回显当前阶段、空值回退 `—` 不臆造，仅 FAILED 且有失败码时红色附注）；空态行 colspan 9→10。其余（触发卡片、阶段明细表、恢复表单、轮询与重试/恢复按钮）逐字未动。两字段均为列表行对象既有字段（恢复表单已直接消费），属纯呈现补齐，不改后端与接口契约。
- `web/tests/pipelineRunStageColumn.test.js`（新增）：5 个用例——表头含「当前阶段」且位置在状态之后/尝试之前并保留原 9 列与 10 列顺序、行单元格阶段绑定与失败码红色附注条件、空态 colspan=10 与表头列数互证（与既有 `pipelinePage.test.js` colspan 不变量互证）、既有行级操作（重试/续跑/按阶段重试/标记失败）保留、呈现逻辑仿真（规约四组合外加两补充组合：非 FAILED 有残留码不附注、FAILED 阶段缺失回退 `—` 且附注）。文件头如实注明源码结构守卫形态，不冒充真实 DOM 渲染证据（本仓无 SFC 编译器，与既有先例一致）。
- 与规约偏差：零偏差。未改后端、接口契约、权限与状态模型；未处理 UP-20261018-27（排后续切片 U04-S03）；未处理 UP-20261018-29（已裁决维持现状）；未新增依赖；未改既有测试文件；未改 `run-tests.ps1`（计数基线由总控验收后登记，见下）。未跟踪的 `sample-data/` 为 2026-10-16 旧遗留，不属本切片、未入提交。

## 总控亲跑验证（不采信执行方自述）

- 首轮 web 档（RunId `u04s02acc20261018`，日志 `/tmp/v25tests-u04s02acc20261018`，副本 `mall-simulator-notes/u04s02-acc-web.log`）：tests=513、pass=513、fail=0，`npm run build` exit=0；脚本因计数基线仍为 508 报 DRIFT/FAIL exit=7——属新增测试的预期计数变化，非测试失败。
- **计数漂移书面登记**：web 基线 508 → 513，增量 +5 构成全部为本切片新增 `web/tests/pipelineRunStageColumn.test.js` 的 5 个测试块（表头列位置与原列保留 1 + 单元格绑定与附注 1 + colspan 互证 1 + 行级操作保留 1 + 呈现仿真 1），无其他增减。总控据此在 `scripts/run-tests.ps1` 再登记 `$BaselineWebTests = 513`（附 2026-10-18 变更注释），未使用 `-AllowCountDrift`。
- 收口轮 web 档（RunId `u04s02gate20261018`，日志 `/tmp/v25tests-u04s02gate20261018`，副本 `mall-simulator-notes/u04s02-gate-web.log`）：tests=513 MATCH、pass=513、fail=0，build exit=0，**PASS exit=0**。
- 脚本契约（改统一脚本必纳验收）：`AnalyticsIsolationScriptsContractTest` 总控定向亲跑 **13/13 PASS**（基线登记前后各跑一次，均全过；离线 Maven，JDK17，本地仓 m2repo，`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`，另加 `-Dmaven.legacyLocalRepo=true`）。
- 本切片仅影响 web 档；default/spark/isolated 无对应改动，按指导书 §6.1 分层未重跑，U00 基线（default 1563 PASS）继续有效。

## 未测范围

- 未做真实浏览器走查（结论为源码级呈现补齐 + web 档源码结构守卫证据）；实例表新列的真实页面观感走查并入 U07 演示走查或后续按受影响路径登记。
- 未连接任何数据库，未启停服务，未触碰 3306，未使用 Docker，未调用 DeepSeek API。

## 后续

- UP-20261018-26 关账。U04 余项：UP-20261018-27（P2，Ops 禁用用户无二次确认）立切片 U04-S03 派发 agy Flash（按 RuntimeProfiles `window.confirm` 先例仅在禁用路径补确认、启用路径不动、取消即中止且不置 busy 不发请求，非 §3.2 事项、盘点时总控已批准，验收后另行登记）；UP-20261018-29 已裁决维持现状；UP-20261018-30～33 复核符合。U04-S03 关账后 U04 全项处置完毕，转 U05 盘点。

## 与签收边界

本报告为增量完善登记，不回写、不升级 2026-10-18 签收（限定通过，main `d9cdd6b`）；RES-01～11 沿用签收处置，不自动销项；不合并 main、不发版。
