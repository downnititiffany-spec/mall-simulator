package com.graduation.analytics.source;

/**
 * 源登记域的审计动作码 / 资源类型（P1-03）。
 *
 * <p><b>为什么常量放在这里而不是 {@code OperationAuditService}</b>：那个类属
 * {@code ai-decision} 模块，而 P1-03 的任务边界明确禁止改动 {@code analytics-server/ai-decision/**}
 * （只允许读它的 {@code OperationAuditService}/{@code OperationAuditLog}）。
 * 该服务的 {@code action} 形参本来就是自由字符串，因此新动作码放在本包即可，
 * 既满足 D-035「复用同一张 {@code operation_audit_log}、不建第二张审计表/第二个服务」，
 * 又不动被冻结的模块。这一点作为**偏差**已上报父会话裁决。</p>
 *
 * <p>命名沿用既有风格（{@code DECISION_*} / {@code USER_*} / {@code AI_QUERY}）：域前缀 + 动词。</p>
 */
public final class SourceAuditActions {

    /** 新建源登记 */
    public static final String ACTION_SOURCE_CREATE = "SOURCE_CREATE";

    /** 修改源登记（source_code 不可改；状态不经此变更） */
    public static final String ACTION_SOURCE_UPDATE = "SOURCE_UPDATE";

    /** 设为当前激活源（绑定唯一 ACTIVE runtime_profile 的 source_id） */
    public static final String ACTION_SOURCE_ACTIVATE = "SOURCE_ACTIVATE";

    /** 暂停源（当前源不可暂停） */
    public static final String ACTION_SOURCE_PAUSE = "SOURCE_PAUSE";

    /** 资源类型：与 {@code operation_audit_log.resource_type} 对应 */
    public static final String RESOURCE_SOURCE_REGISTRY = "SOURCE_REGISTRY";

    private SourceAuditActions() {
    }
}
