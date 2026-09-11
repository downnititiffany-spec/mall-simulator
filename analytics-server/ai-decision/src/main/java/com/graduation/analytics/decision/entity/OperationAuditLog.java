package com.graduation.analytics.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志（R8-3 契约 §3.3/§3.5、V2.0 §21.4）：表 {@code operation_audit_log}（analytics_meta）。
 *
 * <p>字段与 §21.4 一一对应：traceId / userId / role / action / resourceType / resourceId /
 * beforeDigest / afterDigest / reason / ip / result / createdAt。</p>
 *
 * <p>digest 只放状态与关键业务字段摘要（如 {@code status=DRAFT;owner=null;target=cart_rate}），
 * **禁止**写入密码、token、Authorization、LLM key（§21.3 日志脱敏）。</p>
 */
@Data
@TableName("operation_audit_log")
public class OperationAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String userId;

    private String role;

    /** 动作码，如 DECISION_CREATE / DECISION_APPROVE / USER_CREATE / AI_QUERY */
    private String action;

    /** 资源类型，如 DECISION_TASK / SYS_USER / AI_QUERY */
    private String resourceType;

    private String resourceId;

    private String beforeDigest;

    private String afterDigest;

    /** 原因/说明（驳回、取消、失败等必须非空） */
    private String reason;

    private String ip;

    /** SUCCESS / FAILED */
    private String result;

    private LocalDateTime createdAt;
}
