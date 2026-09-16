# Deferred Verification Plan

> 状态：CURRENT
> 用途：统一登记“代码已实现、独立验证尚未执行”的工作项。Code Agent 与 Codex Work 后续按本文件批量验证。
> 规则来源：`docs/governance/DEVELOPMENT_AND_VERIFICATION_RULES.md`、Decision Log D-009。

## 1. 使用规则

- 每个工作项必须固定一个被测 commit SHA；测试方不得用“当前最新”替代它。
- ChatGPT 可以继续开发并列模块；后续 commit 不自动改变旧条目的被测 SHA。
- 若后续实现覆盖了旧工作项的行为，旧条目标记 `SUPERSEDED`，新增最终被测 SHA。
- Code Agent：只跑既有测试/环境验证；不得改 Git。
- Codex Work：主动找反例、故障注入、临时探针；不得改 Git。
- 独立验证结果返回 ChatGPT，由 ChatGPT 判断并修改生产代码/测试/设计。

状态：`PENDING / RUNNING / PASS / FAIL / SUPERSEDED`。

## 2. 当前待验证队列

### V-001 — S3-53 AI evidenceId 形状兼容

- **Status**：PENDING
- **Implementation baseline**：`4480d28aa5473a24e09333ea09b7e1554d824fad`
- **Implementation commits**：`0054870`（生产逻辑）、`0ebd0e8`（developer tests）、`f1e1205`（Decision Log）、`4480d28`（原 Test Handoff）
- **Area**：`web/src/utils/context.js`、`web/src/utils/decisionDraft.js`、`web/src/views/AiAssistant.vue`
- **Risk**：低—中；前端响应形状兼容，不改后端契约、不改数据库、不改权限。
- **Blocks further development**：NO。仅阻塞依赖“evidenceId fallback 已经在真实前端链路中确认”的后续工作；其它阶段6并列项可继续。

#### Invariants

1. `/ai/queries` 顶层真实 `evidenceId` 优先；
2. 顶层缺失/占位时可回退 `explanation.evidence.evidenceId`；
3. 空白、`unknown`、`UNKNOWN` 不得当真实 ID；
4. 真实 evidence package ID 优先于 snapshot 锚点；
5. 决策草稿请求只提交一种锚点；
6. 前端不得生成/猜测 evidenceId。

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm test
npm run build
```

返回：命令、exit code、测试总数/失败数、build 结果、日志位置、环境版本。

#### Codex Work later

重点攻击：

- 顶层/嵌套 ID 冲突；
- 顶层为 `unknown`、嵌套真实；
- 嵌套为 `unknown`、snapshot 真实；
- 非字符串 ID、空白、大小写占位；
- evidenceId 与 snapshot 同时出现时是否双提交；
- `AiAssistant.vue` 是否确实消费归一化后的 `evidence.evidenceId`；
- 嵌套路径变化是否被静默吞掉；
- developer tests 是否只验证工具函数而没有覆盖真实页面接线。

#### Existing detailed handoff

`docs/acceptance/s3-53-ai-evidence-id-fallback-20260916/TEST-HANDOFF.md`

## 3. 批量验证触发点

满足以下任一条件时优先集中跑本文件中的 PENDING 队列：

1. 一个功能簇完成；
2. 某阶段准备宣布完成；
3. 即将进入真实 MySQL / Spark / Hive / Flume / HTTP E2E；
4. 即将合并 `main`；
5. PENDING 队列已经长到继续开发会明显增加失败定位成本；
6. 某个后续工作直接依赖一个 PENDING 项的运行行为。

## 4. 独立验证结果登记格式

每个验证者返回：

```text
Item: V-xxx
Role: Code Agent | Codex Work
Tested commit: <full SHA>
Environment: <JDK/Node/MySQL/Spark/...>
Expected: ...
Actual: ...
Commands/Reproduction: ...
Evidence: ...
Result: PASS | FAIL
```

ChatGPT 复核后才更新本文件最终状态。
