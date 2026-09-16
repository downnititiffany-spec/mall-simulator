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

### V-002 — Web 统一验证入口

- **Status**：PENDING
- **Implementation baseline**：`351fee90b790b87992b7479c4a9f18774f7459ec`
- **Implementation commits**：`e36a350`（`npm run verify`）+ `db810d6`（结构守卫）+ `351fee9`（Decision Log D-011）
- **Area**：`web/package.json`、`web/tests/packageScripts.test.js`
- **Risk**：低；只增加前端验证入口与守卫，不改生产运行时代码。
- **Blocks further development**：NO。

#### Invariants

1. 统一入口唯一命令为 `npm run verify`；
2. 先执行 `npm test`，成功后再执行 `npm run build`；
3. 不把 build 成功替代单测通过；
4. 该入口不宣称覆盖真实浏览器/E2E/后端联调。

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm run verify
```

返回 Node/npm 版本、测试总数、失败数、build exit code 与日志位置。

#### Codex Work later

重点攻击：

- 在单测失败时 build 是否仍被错误执行；
- `verify` 是否出现递归调用自身；
- Windows / PowerShell / cmd 下 npm script 链是否符合预期；
- package script 与文档口径是否漂移；
- 是否存在另一个实际前端入口绕过该统一命令。

### V-003 — S3-54 AI 结论 summary 展示链

- **Status**：PENDING
- **Implementation baseline**：`80623a335e9df1b9fc758cb22172d72032b312db`
- **Implementation commit**：`80623a3`
- **Area**：`web/src/utils/context.js`、`web/src/views/AiAssistant.vue`、`web/tests/aiSummaryContext.test.js`
- **Risk**：低；只修前端只读展示链，不改后端契约、LLM 生成、SQL、安全或数据库。
- **Blocks further development**：NO。

#### Invariants

1. 页面结论只来自 `explanation.summary`；
2. summary 缺失/空白时返回 `null`，页面显示“后端未给出结论文本”；
3. `query.summary`、查询行或 evidence 字段不得替代 ExplanationResult.summary；
4. 不在前端生成、推导或改写业务结论。

#### Code Agent later

在精确 SHA 上执行：

```bash
cd web
npm run verify
```

返回测试总数、失败数、build 结果和日志位置。

#### Codex Work later

重点攻击：

- 后端 summary 为 `''`、空白、null、非字符串；
- query/evidence 同时出现伪造 summary 时是否被错误采用；
- 页面是否还存在其它结论渲染路径绕过 `evidenceContext.summary`；
- summary 包含 HTML/特殊字符时 Vue 是否按文本安全渲染；
- developer tests 是否只覆盖工具函数而未钉住页面接线。

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
