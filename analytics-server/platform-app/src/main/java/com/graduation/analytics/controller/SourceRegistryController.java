package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.source.SourceAuditActions;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.dto.SourceCheckResult;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * 源登记管理（P1-03）：列表/详情/新建/修改/校验/暂停/激活。
 *
 * <p>控制器**不注入任何 mapper**，只依赖 {@link SourceRegistryService}（任务书 §4 P1-03 硬约束），
 * 这样"当前源绑定"这条并发敏感的写路径只有一个入口。</p>
 *
 * <p>权限：整类复用既有的 {@link PermissionCode#RUNTIME_MANAGE}（第 9 个权限码，admin/data_dev）。
 * 不新增第 10 个权限码——那会牵动冻结的 {@code PermissionCode.ALL}/{@code MATRIX_ORDER}
 * 与权限矩阵测试，而「谁可以改运行环境」与「谁可以改源登记」在 V2 里是同一类运维角色。
 * 与 {@code RuntimeProfileController} 同样让读接口也要求该权限（登记行会暴露画像路径与接入方式）。</p>
 *
 * <p>审计在**控制器层**写：{@link OperationAuditService} 属 {@code ai-decision} 模块，
 * 而 {@code connection-ingestion} 不依赖它。这与既有的 {@code DecisionController}/
 * {@code UserAdminController} 完全同构。规则：</p>
 * <ul>
 *   <li>真实变更 → 1 行 SUCCESS（带 before/after 摘要）；</li>
 *   <li>任何失败（含被拒绝的尝试）→ 1 行 FAILED，reason 以稳定错误码开头；</li>
 *   <li>幂等空操作（{@code changed=false}）与只读 {@code /test} → 不写行。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources")
@RequiresPermission(PermissionCode.RUNTIME_MANAGE)
public class SourceRegistryController {

    /** 与 {@code operation_audit_log.reason VARCHAR(512)} 对齐：超长截断，避免因为一条超长原因让审计写失败 */
    private static final int REASON_MAX = 512;

    private final SourceRegistryService sourceService;
    private final OperationAuditService audit;

    public SourceRegistryController(SourceRegistryService sourceService, OperationAuditService audit) {
        this.sourceService = sourceService;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<List<SourceRegistryView>> list() {
        return ApiResponse.ok(sourceService.list(), TraceContext.create().traceId());
    }

    @GetMapping("/{id}")
    public ApiResponse<SourceRegistryView> get(@PathVariable Long id) {
        return ApiResponse.ok(sourceService.get(id), TraceContext.create().traceId());
    }

    @PostMapping
    public ApiResponse<SourceRegistryView> create(@RequestBody SourceRegistryCreateReq req,
                                                  HttpServletRequest request) {
        return mutate(SourceAuditActions.ACTION_SOURCE_CREATE, null, "登记新源",
                actor(request), () -> sourceService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<SourceRegistryView> update(@PathVariable Long id,
                                                  @RequestBody SourceRegistryUpdateReq req,
                                                  HttpServletRequest request) {
        return mutate(SourceAuditActions.ACTION_SOURCE_UPDATE, id, "修改源登记",
                actor(request), () -> sourceService.update(id, req));
    }

    /** 只读校验：不改状态、不留审计（D-035 裁决 8） */
    @PostMapping("/{id}/test")
    public ApiResponse<SourceCheckResult> test(@PathVariable Long id) {
        return ApiResponse.ok(sourceService.test(id), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<SourceRegistryView> activate(@PathVariable Long id, HttpServletRequest request) {
        return mutate(SourceAuditActions.ACTION_SOURCE_ACTIVATE, id, "设为当前源",
                actor(request), () -> sourceService.activate(id));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<SourceRegistryView> pause(@PathVariable Long id, HttpServletRequest request) {
        return mutate(SourceAuditActions.ACTION_SOURCE_PAUSE, id, "暂停源",
                actor(request), () -> sourceService.pause(id));
    }

    // ---------------------------------------------------------------- 内部

    private AuditActor actor(HttpServletRequest request) {
        return CallerContext.actor(request, TraceContext.create().traceId());
    }

    /**
     * 变更端点统一收口：成功按 {@code changed} 决定是否留痕，失败一律留 FAILED 行后原样抛出。
     *
     * <p>失败路径吞掉**审计自身**的异常（只记日志），因为原始业务异常才是调用方要看的；
     * 成功路径不吞——审计是安全证据，写不进去就必须暴露（与 {@code OperationAuditService} 的策略一致）。</p>
     */
    private ApiResponse<SourceRegistryView> mutate(String action, Long requestedId, String reasonPrefix,
                                                   AuditActor actor, Supplier<SourceChangeOutcome> body) {
        SourceChangeOutcome outcome;
        try {
            outcome = body.get();
        } catch (RuntimeException e) {
            auditFailure(actor, action, requestedId, e);
            throw e;
        }

        SourceRegistryView view = outcome.source();
        if (outcome.changed()) {
            Long resourceId = view != null && view.id() != null ? view.id() : requestedId;
            audit.success(actor, action, SourceAuditActions.RESOURCE_SOURCE_REGISTRY,
                    resourceId == null ? null : String.valueOf(resourceId),
                    digestOf(outcome.before()), digestOf(view),
                    reasonPrefix + (view == null ? "" : " " + view.sourceCode()));
        }
        return ApiResponse.ok(view, actor.traceId());
    }

    private void auditFailure(AuditActor actor, String action, Long requestedId, RuntimeException e) {
        String code = e instanceof PlatformBizException biz ? biz.getCode() : e.getClass().getSimpleName();
        try {
            audit.failure(actor, action, SourceAuditActions.RESOURCE_SOURCE_REGISTRY,
                    requestedId == null ? null : String.valueOf(requestedId),
                    null, null, truncate(code + ": " + e.getMessage()));
        } catch (RuntimeException auditError) {
            log.error("源登记审计写入失败（不掩盖原始异常） action={} id={} trace={}",
                    action, requestedId, actor.traceId(), auditError);
        }
    }

    /**
     * 变更摘要：把**全部可变更业务字段**都写进去，不放凭据（源登记里本来也没有）。
     * 复用 {@link OperationAuditService#digest} 而不是自拼字符串——摘要格式只能有一个所有者。
     *
     * <p>为什么必须列全：真机验收实测到一次只改 {@code displayName}/{@code timezone} 的 {@code PUT}，
     * 其审计行 before/after 逐字相同（摘要当时只含 id/sourceCode/status/current/profileVersion/profilePath）——
     * 行是留下了，但事后**看不出改了什么**，"每次变更有审计"就只剩形式。
     * 摘要在 512 字符处由 {@code OperationAuditService} 显式截断并标注，不会静默丢字段。</p>
     */
    private static String digestOf(SourceRegistryView view) {
        if (view == null) {
            return null;
        }
        return OperationAuditService.digest(
                "id", String.valueOf(view.id()),
                "sourceCode", view.sourceCode(),
                "displayName", view.displayName(),
                "ingestMode", view.ingestMode(),
                "status", view.status(),
                "current", String.valueOf(view.current()),
                "profileVersion", view.profileVersion(),
                "profilePath", view.profilePath(),
                "timezone", view.timezone(),
                "currency", view.currency());
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= REASON_MAX) {
            return reason;
        }
        return reason.substring(0, REASON_MAX - 5) + "…(截断)";
    }
}
