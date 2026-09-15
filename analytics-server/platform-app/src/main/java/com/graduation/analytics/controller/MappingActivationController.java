package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.mapping.activation.MappingActivationOutcome;
import com.graduation.analytics.mapping.activation.MappingActivationService;
import com.graduation.analytics.source.SourceAuditActions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 映射激活（S2-03，设计 §7.4）：把一张**已通过 dry-run 的报告**激活为该源的正式映射画像。
 *
 * <pre>
 *   POST /api/v1/sources/{sourceId}/mappings/activate
 *   body: { "reportId": "dr-20260921102030-xxxxxxxx", "expectedProfileChecksum": "&lt;sha256&gt;" }
 * </pre>
 *
 * <p><b>为什么入参是「报告 id + 校验和」而不是画像原文/路径</b>：激活必须"预览的内容就是激活的内容"。
 * 报告里已经钉住了画像字节的 sha256 与契约字节的 sha256，调用方交回 {@code expectedProfileChecksum}
 * 就构成一次**确认**：服务端会把「报告钉住的哈希 / 调用方手上的哈希 / 磁盘现状的哈希」三者对齐，
 * 任一不符即 409。若允许再次上传画像原文，就会出现"预览的是 A、激活的是 B"的窗口——
 * 那正是设计 §7.3 规则 14 要排除的情形，也是本接口不再接收 {@code profileText} 的原因。</p>
 *
 * <p><b>权限</b>：复用既有的 {@link PermissionCode#RUNTIME_MANAGE}（第 9 个权限码，admin/data_dev），
 * 不新增第 10 个权限码——那会牵动冻结的 {@code PermissionCode.ALL}/{@code MATRIX_ORDER} 与权限矩阵测试；
 * 「谁能试算映射」与「谁能把映射真正切上线」在 V2 里是同一类运维角色。
 * 未登录 401、analyst 403 均由拦截器/权限切面在进入本方法之前给出。</p>
 *
 * <p><b>错误语义（不得用 500 表达业务冲突）</b>：报告不存在/不属于该源 → 404；报告不可激活、
 * 校验和不一致、契约漂移、画像内容变化 → 409；入参形状非法 → 400；
 * 落库能力尚未就绪（见 {@code UnavailableActiveMappingPointerStore}）→ 501，与"服务端故障"500 可区分。</p>
 *
 * <p><b>审计在控制器层写</b>：{@link OperationAuditService} 属 {@code ai-decision}，
 * 而 {@code connection-ingestion} 不依赖它（与 {@code SourceRegistryController}/{@code DecisionController} 同构）。
 * 规则与 P1-03 完全一致，避免出现第二套审计口径：</p>
 * <ul>
 *   <li>真实激活（{@code changed=true}）→ 1 行 SUCCESS，after 摘要含新指针的全部事实，
 *       替换既有指针时 before 摘要含被换下的 checksum；</li>
 *   <li>幂等重复（{@code changed=false}，同源同画像同契约）→ **不写行**
 *       （D-035 裁决 4：不重复写同一次绑定；否则刷新两次页面就多两行"激活"）；</li>
 *   <li>任何失败（含 501 能力缺口、409 冲突）→ 1 行 FAILED，reason 以稳定错误码开头；</li>
 *   <li>401/403 在进入本方法之前就被拒绝，因此无审计行——此时还没有可信身份可记（与 P1-03 同）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources/{sourceId}/mappings")
@RequiresPermission(PermissionCode.RUNTIME_MANAGE)
public class MappingActivationController {

    /** 与 {@code operation_audit_log.reason VARCHAR(512)} 对齐：超长截断，避免因为一条超长原因让审计写失败 */
    private static final int REASON_MAX = 512;

    private final MappingActivationService activationService;
    private final OperationAuditService audit;

    public MappingActivationController(MappingActivationService activationService, OperationAuditService audit) {
        this.activationService = activationService;
        this.audit = audit;
    }

    /**
     * 激活入参。
     *
     * @param reportId                授权本次激活的 dry-run 报告 id（本进程内有效）
     * @param expectedProfileChecksum 调用方手上的画像哈希：必须等于报告钉住的 {@code profileChecksum}
     *                                （服务端还会比对磁盘现存字节的哈希，三者一致才激活）
     */
    public record MappingActivateReq(
            @NotBlank String reportId,
            @NotBlank String expectedProfileChecksum) {
    }

    @PostMapping("/activate")
    public ApiResponse<MappingActivationOutcome> activate(@PathVariable String sourceId,
                                                          @Valid @RequestBody MappingActivateReq req,
                                                          HttpServletRequest request) {
        AuditActor actor = CallerContext.actor(request, TraceContext.create().traceId());
        MappingActivationOutcome outcome;
        try {
            outcome = activationService.activate(sourceId, req.reportId(), req.expectedProfileChecksum(),
                    actor.userId());
        } catch (RuntimeException e) {
            auditFailure(actor, sourceId, e);
            throw e;
        }
        // 成功路径的审计**不在 try 内**：审计是安全证据，写不进去就必须暴露成失败，
        // 而不是被下面的 catch 变成"业务失败 + FAILED 行"（那会让一次成功激活同时留下两条互相矛盾的行）。
        // 规则与 SourceRegistryController.mutate 完全一致。
        if (outcome.changed()) {
            audit.success(actor, SourceAuditActions.ACTION_MAPPING_ACTIVATE,
                    SourceAuditActions.RESOURCE_SOURCE_MAPPING, String.valueOf(outcome.sourceId()),
                    beforeDigest(outcome), afterDigest(outcome), replaceReason(outcome));
        }
        return ApiResponse.ok(outcome, actor.traceId());
    }

    // ---------------------------------------------------------------- 审计（失败不掩盖真问题）

    private void auditFailure(AuditActor actor, String sourceIdToken, RuntimeException e) {
        String reason = truncate(e instanceof PlatformBizException biz
                ? biz.getCode() + ": " + biz.getMessage()
                : e.getClass().getSimpleName() + ": " + e.getMessage());
        try {
            audit.failure(actor, SourceAuditActions.ACTION_MAPPING_ACTIVATE,
                    SourceAuditActions.RESOURCE_SOURCE_MAPPING, resourceIdOrNull(sourceIdToken),
                    null, null, reason);
        } catch (RuntimeException auditError) {
            // 审计写失败不能让原始业务异常消失（否则 409 会变成 500，真问题被掩盖）
            log.error("映射激活审计写入失败 sourceId={} actor={} trace={}: {}",
                    sourceIdToken, actor.userId(), actor.traceId(), auditError);
        }
    }

    /**
     * 审计的资源 id：只写**数字形状**的源 id，其它形状写 null（不把任意路径段塞进审计列）。
     * 与激活服务"非数字按源不存在处理、值不回显"的口径一致。
     */
    private static String resourceIdOrNull(String sourceIdToken) {
        String token = sourceIdToken == null ? "" : sourceIdToken.trim();
        return token.matches("(?:src-)?\\d{1,18}") ? token.replaceFirst("^src-", "") : null;
    }

    private static String beforeDigest(MappingActivationOutcome outcome) {
        return outcome.previousProfileChecksum() == null
                ? null
                : OperationAuditService.digest("previousProfileChecksum", outcome.previousProfileChecksum(),
                "profilePath", outcome.profilePath());
    }

    /** 激活后的全部事实（没有画像/契约哈希就说不清"切到了哪一版"，因此全部落摘要） */
    private static String afterDigest(MappingActivationOutcome outcome) {
        return OperationAuditService.digest(
                "sourceId", String.valueOf(outcome.sourceId()),
                "sourceCode", outcome.sourceCode(),
                "profilePath", outcome.profilePath(),
                "profileVersion", outcome.profileVersion(),
                "profileChecksum", outcome.profileChecksum(),
                "contractVersion", outcome.contractVersion(),
                "contractChecksum", outcome.contractChecksum(),
                "reportId", outcome.reportId(),
                "activatedAt", String.valueOf(outcome.activatedAt()),
                "activatedBy", outcome.activatedBy());
    }

    private static String replaceReason(MappingActivationOutcome outcome) {
        return outcome.previousProfileChecksum() == null
                ? "首次激活该源的映射画像（来自 dry-run 报告 " + outcome.reportId() + "）"
                : "替换该源已激活的映射画像（原 " + outcome.previousProfileChecksum()
                + " → 新 " + outcome.profileChecksum() + "，来自 dry-run 报告 " + outcome.reportId() + "）";
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= REASON_MAX) {
            return reason;
        }
        return reason.substring(0, REASON_MAX - 5) + "…(截断)";
    }
}
