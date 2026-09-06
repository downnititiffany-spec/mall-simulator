# Slice08: 决策闭环——AI 草稿→人工审核→执行→效果评价 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成阶段 8 决策中心（§22.6-22.7）：AI 建议（来自证据解释的 suggestions，带 targetMetricCode）→ `POST /decisions` 仅能创建 DRAFT → 人工提交审核/批准（锁定负责人、期限、目标指标、**基线值**）→ 开始/完成 → 评价（前后对比，§21.10，"越低越好"指标方向取反）→ EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA。全程记录审核人与操作日志语义。

**Architecture:** `decision` 包：`DecisionStateMachine`（纯状态机，12 态）；`DecisionService`（基线在 APPROVED 时从当前 ACTIVE 快照 MetricStore 锁定；evaluate 时读当前 ACTIVE 快照同指标值对比，缺失→INSUFFICIENT_DATA）；审计字段齐全（owner/due/baseline/target/risk/approver）。前端 `views/Decisions.vue`（列表+状态推进+效果展示），AiAssistant 建议项旁加"创建决策"入口。

**Tech Stack:** Java 17、Spring Boot 3.2.5、MyBatis-Plus、Flyway V5、JUnit 5。

**外部约束（V2.2 文稿）:** §22.6 状态机（DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED→EVALUATING→EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA；审核前可 REJECTED；执行中可 CANCELLED 记原因）；§22.7 决策示例（基线窗口、目标方向、负责人/期限）；§21.10 效果计算 improve=(actual−baseline)/|baseline|，DOWN 方向取反；AI 只能创建 DRAFT，从 PENDING_REVIEW 到 APPROVED 必须人工（§22.6）。

---

### Task 1: Flyway V5

`V5__decisions.sql`：`decision_task`（decision_no 唯一、source=ai|human、suggestion_snapshot_id、title、action、target_metric_code、target_direction UP|DOWN、baseline_value、target_value、eval_window_days、owner、due_date、status、risk、reject_reason、cancel_reason、created_by、approved_by、created_at/updated_at/started_at/completed_at/evaluated_at）、`decision_evaluation`（decision_id、baseline_value、actual_value、improvement_rate DECIMAL(10,4)、result、note、evaluated_by、created_at）

### Task 2: 状态机与实体

- `DecisionStateMachine`：合法边表 + `validate(from,to)`；非法流转抛业务错
- `DecisionTask` / `DecisionEvaluation` 实体 + Mapper
- Test: `DecisionStateMachineTest`（全合法流转 + 非法流转拒绝）

### Task 3: DecisionService（§22.6/§21.10）

- `createDraft`：source=ai 强制 DRAFT；AI 建议若无 targetMetricCode → 只能作为一般提示（§22.3）
- `submit/approve/reject/start/complete/evaluate/cancel`：状态机校验；approve 必须带 owner+due+target_metric+方向，并**从当前 ACTIVE 快照 MetricStore 锁定 baseline**
- `evaluate`：actual=当前 ACTIVE 快照目标指标值；缺失→INSUFFICIENT_DATA；improve=(actual−baseline)/|baseline|，DOWN 取反；>=0.05 EFFECTIVE、>0 PARTIAL、<=0 INEFFECTIVE；落 decision_evaluation
- Test: `DecisionServiceTest`（全流程：AI 草稿→审核→批准锁基线→执行→评价 EFFECTIVE；DOWN 指标退款率下降 → 改善率为正；无数据 → INSUFFICIENT_DATA；非法流转拒绝）

### Task 4: Controller + 前端

- `DecisionController`：POST /api/v1/decisions（草稿）、POST /{id}/submit|approve|reject|start|complete|evaluate、GET /api/v1/decisions?limit=
- `web/src/views/Decisions.vue`：状态列表 + 推进按钮 + 评价结果卡；AiAssistant 建议项"创建决策"
- 验证：mvn test 全绿；冒烟（mock AI 建议 → 决策全流程 → 评价）；npm build；提交

**验收（本轮完成定义）：** AI 无法创建非 DRAFT；批准必须锁定基线；评价对 UP/DOWN 指标方向正确且数据不足可识别；决策全生命周期可追踪（审核人/时间/结果）；页面可操作。这将关闭"发现→解释→行动→复盘"闭环（§9.6 创新点六）。