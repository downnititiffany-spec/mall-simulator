-- =====================================================================
-- V14: R8-3 身份与决策（契约 docs/contracts/r8-evidence-security-decision.md §3.5）
-- 1) decision_task 补列：基线快照 / 口径版本 / 证据包 / 审批与执行备注 / 批准时间
-- 2) decision_evaluation 补列：前后快照、等长窗口、窗口聚合值、样本数、口径版本
-- 3) operation_audit_log（§21.4 操作审计：决策全流程 + 用户管理 + AI 查询）
-- 4) ai_query_history 补列（R8-2 §2.3：审计必须能回答「查了哪个快照、允许的时间范围、EXPLAIN 行数」）
-- 纯增量：只 ADD COLUMN / CREATE TABLE IF NOT EXISTS，不改列、不删列、不动历史迁移。
-- 列位置约定：每个 ALTER 里只有**第一个**新列带 AFTER，锚点取自 2026-09-11 在真实 analytics_meta 上
-- 核对过的既有列；同一语句内的其余新列不再串联 AFTER（不依赖 MySQL 未承诺的「同语句列序」语义，
-- 列顺序对按名访问无影响），避免迁移在真实链路里因锚点解析失败而中断。
-- =====================================================================

-- 1) 决策任务：基线快照与口径（§20.4 前快照）、证据包（§20.3 提交前齐备）、审批/执行备注
ALTER TABLE decision_task
    ADD COLUMN baseline_snapshot_id VARCHAR(64)  NULL COMMENT 'R8-3 批准时钉住的基线快照（§20.4 前快照，评价时不再漂移）' AFTER baseline_value,
    ADD COLUMN definition_version   VARCHAR(32)  NULL COMMENT 'R8-3 批准时目标指标口径版本（口径变更后可追溯）',
    ADD COLUMN evidence_package_id  VARCHAR(64)  NULL COMMENT 'R8-3 AI 建议证据包 id（§20.3 提交审批前必须齐备）',
    ADD COLUMN approval_note        VARCHAR(512) NULL COMMENT 'R8-3 审批备注（含基线快照/窗口溯源信息）',
    ADD COLUMN execution_note       VARCHAR(512) NULL COMMENT 'R8-3 执行备注（执行过程记录）',
    ADD COLUMN approved_at          DATETIME(3)  NULL COMMENT 'R8-3 批准时间：§20.4 基线窗口 [approvedDate-N+1, approvedDate] 的唯一依据';

-- 2) 效果评价：等长窗口前后对比的可复核字段（§20.4 / §3.4）
ALTER TABLE decision_evaluation
    ADD COLUMN baseline_snapshot_id    VARCHAR(64)   NULL COMMENT 'R8-3 前快照：批准时钉住的基线快照号' AFTER improvement_rate,
    ADD COLUMN actual_snapshot_id      VARCHAR(64)   NULL COMMENT 'R8-3 后快照：评价时读取的最新已发布快照号',
    ADD COLUMN window_start            DATE          NULL COMMENT 'R8-3 评价窗口起（= completedDate+1）',
    ADD COLUMN window_end              DATE          NULL COMMENT 'R8-3 评价窗口止（= completedDate+N，与 baseline 窗口等长）',
    ADD COLUMN baseline_period_value   DECIMAL(18,4) NULL COMMENT 'R8-3 基线窗口观测值（快照粒度观测，见 DecisionService 口径说明）',
    ADD COLUMN actual_period_value     DECIMAL(18,4) NULL COMMENT 'R8-3 评价窗口观测值',
    ADD COLUMN sample_count            INT           NULL COMMENT 'R8-3 参与对比的观测样本数（前后快照目标指标行数之和）',
    ADD COLUMN definition_version      VARCHAR(32)   NULL COMMENT 'R8-3 评价口径版本（含可配置阈值版本，§20.4）',
    ADD COLUMN eval_window_days        INT           NULL COMMENT 'R8-3 评价窗口天数（前端 tables.js 展示「窗口 N 天」）';

-- 3) 操作审计（§21.4）：trace/user/role/action/resource/前后摘要/原因/ip/结果/时间
CREATE TABLE IF NOT EXISTS operation_audit_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id      VARCHAR(64)  NULL COMMENT '链路 trace id（与响应信封一致）',
    user_id       VARCHAR(64)  NOT NULL COMMENT '操作者（取自登录会话，禁止请求头伪造）',
    role          VARCHAR(32)  NULL COMMENT '操作者角色',
    action        VARCHAR(64)  NOT NULL COMMENT '动作码：DECISION_CREATE/SUBMIT/APPROVE/REJECT/START/COMPLETE/CANCEL/EVALUATE、USER_CREATE/USER_TOGGLE/USER_RESET_PASSWORD、AI_QUERY',
    resource_type VARCHAR(32)  NOT NULL COMMENT '资源类型：DECISION_TASK/SYS_USER/AI_QUERY',
    resource_id   VARCHAR(64)  NULL COMMENT '资源 id',
    before_digest VARCHAR(512) NULL COMMENT '变更前摘要（仅状态与关键字段，禁止密码/token）',
    after_digest  VARCHAR(512) NULL COMMENT '变更后摘要',
    reason        VARCHAR(512) NULL COMMENT '原因/说明（驳回、取消、评价结论等）',
    ip            VARCHAR(64)  NULL COMMENT '客户端 ip',
    result        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_action_time (action, created_at),
    KEY idx_audit_user_time (user_id, created_at),
    KEY idx_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志（R8-3 §21.4：决策全流程/用户管理/AI 查询）';

-- 4) AI 问数审计补列（R8-2 契约 §2.3：快照锚点 / 允许时间范围 / EXPLAIN 估算行数 / 行数上限）
--    锚点列以 V3__platform_ai_audit.sql 现有结构为准：ai_query_history 的 SQL 元数据列名是 tables（非 tables_json），
--    故按实际列名锚定到 AFTER tables（已在真实 analytics_meta 核对：id/user_id/question/sql_text/tables/status/...）；
--    其余列依次追加（不加 AFTER），同样不依赖同语句列序。
ALTER TABLE ai_query_history
    ADD COLUMN snapshot_id     VARCHAR(64) NULL COMMENT 'R8-2 本次问数钉住的快照（§19.5 禁止 SQL 内 MAX(snapshot_id)）' AFTER tables,
    ADD COLUMN scope_min_date  DATE        NULL COMMENT 'R8-2 允许的最早日期（业务日-89，扫描上限 90 天）',
    ADD COLUMN scope_max_date  DATE        NULL COMMENT 'R8-2 允许的最晚日期（快照业务日）',
    ADD COLUMN explain_rows    BIGINT      NULL COMMENT 'R8-2 EXPLAIN 估算扫描行数（超阈值拒绝执行）',
    ADD COLUMN scope_row_limit INT         NULL COMMENT 'R8-2 单次查询最大返回行数（200）';
