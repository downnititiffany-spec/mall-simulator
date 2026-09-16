# 开发与验证职责规则

> 状态：CURRENT
> 生效日期：2026-09-16
> 适用分支：`feature/v3-development` 及其后继开发分支
> 性质：V3.0 的执行治理补充；不得改变 `项目完整实施指导书 V3.0` 与 `项目设计文档 V3.0` 的正式范围、架构或契约。若发生冲突，必须进入正式决策并在需要时发布 V3.1。

## 1. 角色与唯一写入权

### 1.1 ChatGPT（Architect + Implementer）

负责：

- 读取当前 GitHub 事实、V3.0 指导书、V3.0 设计文档、Decision Log / ADR 与 `PROJECT_STATUS.md`；
- 架构与详细设计；
- 生产代码、开发者测试、迁移脚本、必要设计文档的实现；
- GitHub 远端写入、commit、push；
- 接收测试与对抗验证结果，判断属于测试错误、实现错误还是设计错误，并负责修复。

**GitHub 远端写权限在本项目执行流程中只由 ChatGPT 使用。**

### 1.2 Code Agent（Execution Tester）

只读 GitHub。负责 checkout/fetch 指定 commit 后运行 ChatGPT 已提供的测试和环境验证，包括但不限于：

- Maven / JDK；
- Spark / ScalaTest / spark-submit；
- MySQL 3307 隔离环境；
- Hive / Hadoop / Flume；
- Spring Boot / HTTP；
- Vue build 与必要的端到端运行。

不得 commit、push、merge、修改远端分支，不得自行修生产代码。返回 `Expected / Actual / Reproduction / Evidence / Commit SHA`。

### 1.3 Codex Work（Adversarial Verifier）

只读 GitHub。目标不是“证明测试通过”，而是主动寻找 ChatGPT 的设计与实现错误。

允许在本地/临时工作区新增探针、变异、临时测试和故障注入；禁止写 GitHub。发现问题只提交 finding 与复现证据，由 ChatGPT 决定并修改。

### 1.4 Independent Reviewer Chat

用于重大模块的第三方审查。Reviewer 不修改 Git，仅根据指定 commit、Design、ADR、契约独立寻找问题，不接受实现作者解释作为事实。

### 1.5 用户（Product Owner / Final Arbiter）

主要裁决：项目范围变化、重大架构分叉、风险操作、正式契约语义变化、实现方与验证方无法自行消解的冲突。

## 2. 权威事实与落盘原则

执行时按以下顺序恢复项目事实：

1. `docs/guidance/项目完整实施指导书 V3.0.md`：WHAT / 阶段目标与正式范围；
2. `docs/design/项目设计文档 V3.0.md`：DESIGN / 架构、数据、接口、不变量；
3. `docs/decisions/DECISION_LOG.md` 与 ADR：已批准的执行决策；
4. GitHub 当前代码与 commit：HOW / 实际实现；
5. `docs/PROJECT_STATUS.md`：FACT / 当前进度、测试与未测边界。

原则：**仓库事实 > 对话记忆。**

任何只存在聊天中、没有进入 GitHub 的项目决策，不视为正式项目决策。

- 普通实现决策：追加 `docs/decisions/DECISION_LOG.md`；
- 重大、长期、跨模块或难以回滚的决策：新增 ADR；
- 若新决策改变已冻结 V3.0 的正式范围/架构/契约语义：不得仅靠 Decision Log 覆盖，必须走正式版本升级。

## 3. 开发工作集与实现纪律

每次实现只维持一个有限工作集：

1. 读取相关 Design 章节；
2. 读取相关生产代码；
3. 读取相关测试与近期 commit；
4. 明确不变量与失败语义；
5. 如产生新决策，先落 Decision Log / ADR；
6. 实现生产代码；
7. 编写开发者测试与静态守卫；
8. 做静态复核并形成逻辑完整 commit；
9. push 到开发分支；
10. 模块代码面完整后形成 Test Handoff。

“持续开发”不等于同时大范围修改 Spark、Spring Boot、Vue、AI、Flume；一个逻辑闭环完成后再切换工作集。

## 4. 开发者测试与独立验证

ChatGPT 编写的测试属于 developer tests，不是最终验收。

重要能力至少争取两种不同层级证据，例如：

- Spark：结构/单测 + 数据行为或真实 Spark 运行；
- Migration：文本/结构测试 + 3307 真 MySQL；
- HTTP：Controller/Service 测试 + Boot/curl；
- 前端：unit + build/真实页面；
- 数据链：局部算法测试 + 跨模块/端到端证据。

Code Agent 负责执行既有测试；Codex Work 必须额外主动找反例，不得只重复 developer tests。

## 5. Test Handoff

模块代码面完成后，ChatGPT 输出并落盘/提交必要的测试交接事实，至少包含：

- 被测 commit SHA；
- 模块目标与关键不变量；
- Code Agent 应执行的现有测试命令/档位；
- Codex Work 应重点攻击的边界；
- 已知环境红/未测边界；
- 明确禁止的越界结论。

测试方必须针对精确 commit 测试，禁止用“当前最新”代替固定 SHA。

## 6. 停止条件

普通实现阶段不得因为单个类、子任务、commit 或普通 backlog 完成而停止。

当前聊天中的实现任务应持续到以下任一条件：

1. 当前模块的设计、代码、developer tests 与 Git commit 已完整，需要进入 Code Agent / Codex Work 的独立测试；
2. 触发 HARD DECISION；
3. GitHub/工具/依赖使当前模块无法继续实现。

HARD DECISION 包括：

- 删除表/列或破坏性数据迁移；
- 修改既有字段类型或正式业务语义；
- 修改已发布 Flyway migration；
- 写入/迁移正式 3306 数据或切 ACTIVE；
- 修改 `contract-specs/**` 已有正式契约语义；
- 改变 V3.0 总体架构或正式项目范围；
- 删除已发布能力；
- 引入 V3.0 未规划的大型基础组件；
- 两个候选方案会形成明显长期架构分叉。

普通加法式实现、新表、新 nullable 列、新索引、新 migration、内部 DTO/API 扩展、普通 bug 修复，不因其本身构成 HARD DECISION。

## 7. Git 纪律

- 当前开发主线：`feature/v3-development`，除非 Decision Log 明确变更；
- 只有 ChatGPT 写远端 Git；Code Agent、Codex Work、Reviewer 只读；
- 每个逻辑完整工作集形成独立 commit；
- 禁止 force push 已共享开发历史；
- 禁止自动 merge `main`；合并 `main` 由用户明确批准；
- 验证方报告必须带精确 commit SHA。

## 8. Independent Reviewer 触发条件

出现以下任一类重大模块时，ChatGPT 应主动要求用户开启独立 Reviewer 新会话，并提供可直接复制的 Reviewer Prompt：

- Flyway / 数据模型重大变化；
- 权限、鉴权、安全边界；
- Spark SQL / DWD / ADS 核心指标语义；
- AI SQL、LLM 安全过滤、AI 输出落库/决策；
- 状态机、幂等、重试、恢复；
- 跨模块正式契约；
- 发布、回滚、生产切换；
- 一次修改横跨多个核心模块且失败代价高。

## 9. 指导书与状态文件后续演进

V3.0 暂不因流程变化原地重写。

下一正式版本 V3.1 的指导书应逐步从“实现步骤型”调整为“验收规范型”，重点包含：阶段目标、完成定义、不变量、测试矩阵、边界、故障注入、回归范围、证据要求、禁止越界声称、通过标准。

`PROJECT_STATUS.md` 应逐步瘦身，只保留当前阶段、HEAD、阶段状态、测试 baseline、重大 blocker/backlog、当前任务与关键 commit；详细 RED/GREEN、探针和长证据归 `docs/acceptance/**`，长期决策归 `docs/decisions/**` / `docs/status-history/**`。
