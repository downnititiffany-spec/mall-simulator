package com.graduation.analytics.decision;

import com.graduation.analytics.decision.entity.OperationAuditLog;
import com.graduation.analytics.decision.mapper.OperationAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计写入（R8-3 契约 §3.3/§3.5、V2.0 §21.4）：
 * 决策创建/提交/批准/驳回/开始/完成/取消/评价、用户管理动作、AI 查询全部落 {@code operation_audit_log}。
 *
 * <p>写入策略：**直接插入、失败向上抛出**。审计是安全证据，不允许「写失败也当成功」
 * 的静默降级（本项目已多次因吞异常掩盖真问题）；失败时 ERROR 日志保留上下文再由调用方失败。</p>
 *
 * <p>摘要：{@link #digest} 只拼状态与关键业务字段，禁止放入密码/token/Authorization（§21.3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationAuditService {

    // 动作码（写入 operation_audit_log.action；改名会影响审计检索与测试断言）
    public static final String ACTION_DECISION_CREATE = "DECISION_CREATE";
    public static final String ACTION_DECISION_SUBMIT = "DECISION_SUBMIT";
    public static final String ACTION_DECISION_APPROVE = "DECISION_APPROVE";
    public static final String ACTION_DECISION_REJECT = "DECISION_REJECT";
    public static final String ACTION_DECISION_START = "DECISION_START";
    public static final String ACTION_DECISION_COMPLETE = "DECISION_COMPLETE";
    public static final String ACTION_DECISION_CANCEL = "DECISION_CANCEL";
    public static final String ACTION_DECISION_EVALUATE = "DECISION_EVALUATE";
    public static final String ACTION_USER_CREATE = "USER_CREATE";
    public static final String ACTION_USER_TOGGLE = "USER_TOGGLE";
    public static final String ACTION_USER_RESET_PASSWORD = "USER_RESET_PASSWORD";
    public static final String ACTION_AI_QUERY = "AI_QUERY";

    // 资源类型
    public static final String RESOURCE_DECISION_TASK = "DECISION_TASK";
    public static final String RESOURCE_SYS_USER = "SYS_USER";
    public static final String RESOURCE_AI_QUERY = "AI_QUERY";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";

    /** before_digest / after_digest 列宽 512：超长截断，避免 MysqlDataTruncation 让审计写入失败（同 V9 教训） */
    private static final int DIGEST_MAX = 512;

    private final OperationAuditLogMapper auditLogMapper;

    /** 记录一次成功操作 */
    public OperationAuditLog success(AuditActor actor, String action, String resourceType, String resourceId,
                                     String beforeDigest, String afterDigest, String reason) {
        return record(actor, action, resourceType, resourceId, beforeDigest, afterDigest, reason, RESULT_SUCCESS);
    }

    /** 记录一次失败操作（参数非法、状态非法等；原因必填） */
    public OperationAuditLog failure(AuditActor actor, String action, String resourceType, String resourceId,
                                     String beforeDigest, String afterDigest, String reason) {
        return record(actor, action, resourceType, resourceId, beforeDigest, afterDigest, reason, RESULT_FAILED);
    }

    /** 落库一行审计（字段与 §21.4 对齐） */
    public OperationAuditLog record(AuditActor actor, String action, String resourceType, String resourceId,
                                    String beforeDigest, String afterDigest, String reason, String result) {
        OperationAuditLog entry = new OperationAuditLog();
        entry.setTraceId(actor.traceId());
        entry.setUserId(actor.userId());
        entry.setRole(actor.role());
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setBeforeDigest(truncate(beforeDigest));
        entry.setAfterDigest(truncate(afterDigest));
        entry.setReason(reason);
        entry.setIp(actor.ip());
        entry.setResult(result);
        entry.setCreatedAt(LocalDateTime.now());
        try {
            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("操作审计写入失败 action={} resource={}:{} actor={} trace={}",
                    action, resourceType, resourceId, actor.userId(), actor.traceId(), e);
            throw e;
        }
        return entry;
    }

    /**
     * 摘要拼装：{@code key=value;key=value}。value 为 null 也写出（审计要能看出「当时是空的」）。
     * 仅用于状态/关键字段，调用方负责不放敏感值。
     */
    public static String digest(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(keyValues[i]).append('=').append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= DIGEST_MAX) {
            return value;
        }
        log.warn("审计摘要超长已截断: {} 字符", value.length());
        return value.substring(0, DIGEST_MAX - 5) + "…(截断)";
    }
}
