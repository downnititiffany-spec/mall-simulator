package com.graduation.analytics.auth;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限码常量（R8-3 契约 §3.2 / V2.0 §21.1）。
 *
 * <p>为什么不用 URL 前缀猜角色：路径前缀与业务权限不是一一对应（例如 {@code /api/v1/metrics/quality}
 * 是运维日志权限、{@code /api/v1/ai/audit/*} 是审计权限，两者同属 {@code /metrics} 与 {@code /ai} 前缀），
 * 前缀规则只能表达「是不是 admin」，既管不住 data_dev/operator 的差异，也随新增路径静默放宽。
 * 改为：控制器方法/类上声明 {@link RequiresPermission}，由 {@link AuthInterceptor} 查
 * {@link RolePermissions} 矩阵判定。</p>
 *
 * <p>码值一经冻结不得改名：它们是审计日志（operation_audit_log.action 相邻字段）与越权测试的断言依据。</p>
 */
public final class PermissionCode {

    /** 用户管理（/api/v1/admin/users 全部动作） */
    public static final String USER_MANAGE = "user:manage";
    /** 运行环境管理（RuntimeProfile 创建/测试/激活/禁用） */
    public static final String RUNTIME_MANAGE = "runtime:manage";
    /** 流水线/采集启动与重试 */
    public static final String PIPELINE_RUN = "pipeline:run";
    /** 运维日志与质量结果只读查看 */
    public static final String OPS_LOG_VIEW = "ops:log:view";
    /** 分析看板（大盘/专题/决策与评价只读） */
    public static final String DASHBOARD_VIEW = "dashboard:view";
    /** AI 问数（/api/v1/ai/queries、/api/v1/ai/analyses、/api/v1/ai/history/my） */
    public static final String AI_QUERY = "ai:query";
    /** 创建决策草稿与推进执行（提交/开始/完成/取消/评价） */
    public static final String DECISION_CREATE = "decision:create";
    /** 审批决策（批准/驳回） */
    public static final String DECISION_APPROVE = "decision:approve";
    /** AI 审计查看（/api/v1/ai/audit/*，仅 admin） */
    public static final String AI_AUDIT_VIEW = "ai:audit:view";

    /** 全部权限码（越权测试与矩阵自检用；顺序稳定便于断言失败时阅读） */
    public static final Set<String> ALL = Set.of(
            USER_MANAGE, RUNTIME_MANAGE, PIPELINE_RUN, OPS_LOG_VIEW, DASHBOARD_VIEW,
            AI_QUERY, DECISION_CREATE, DECISION_APPROVE, AI_AUDIT_VIEW);

    /** 契约 §3.2 表格中的 9 个权限码（与 {@link #ALL} 同集合，供矩阵覆盖自检按表顺序遍历） */
    public static final Set<String> MATRIX_ORDER = new LinkedHashSet<>(java.util.List.of(
            USER_MANAGE, RUNTIME_MANAGE, PIPELINE_RUN, OPS_LOG_VIEW, DASHBOARD_VIEW,
            AI_QUERY, DECISION_CREATE, DECISION_APPROVE, AI_AUDIT_VIEW));

    private PermissionCode() {
    }
}
