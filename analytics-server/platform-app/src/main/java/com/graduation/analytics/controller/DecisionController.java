package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.DecisionService;
import com.graduation.analytics.decision.DecisionService.ApproveReq;
import com.graduation.analytics.decision.DecisionService.CreateDraftReq;
import com.graduation.analytics.decision.DecisionService.ExecuteReq;
import com.graduation.analytics.decision.DecisionService.ReasonReq;
import com.graduation.analytics.decision.DecisionService.SubmitReq;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * 决策中心接口（§24.3 子集）：AI 草稿→人工审核→执行→效果评价。
 *
 * <p>R8-3 改动：删除 {@code X-User-Id} 请求头与 {@code demo}/{@code admin} 兜底，
 * 创建者/审批人/评价人一律来自 {@link CallerContext}（登录会话）；每个动作按契约 §3.2
 * 声明 {@link RequiresPermission}。路径与响应结构保持不变（前端 api.js 冻结）。</p>
 *
 * <p>审计（§21.4）：成功路径由 {@code DecisionService} 写 operation_audit_log（含前后摘要）；
 * 本层再包一层，把**被拒绝的动作**（非法状态流转、参数不齐）也记成 FAILED 行 —— 拒绝尝试同样是
 * 需要留痕的运维事实；失败路径审计写不进去时只记日志，不掩盖原始异常。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionService decisionService;
    private final OperationAuditService audit;

    /** AI 建议 → 决策草稿（来源固定 ai，服务层强制 DRAFT，§20.3） */
    @PostMapping
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionTask> createDraft(@RequestBody CreateDraftReq req, HttpServletRequest request) {
        AuditActor actor = actor(request);
        return action(OperationAuditService.ACTION_DECISION_CREATE, null, actor,
                () -> decisionService.createDraft(req, actor, "ai"));
    }

    /** 提交审批：齐备校验（action/owner/目标/窗口/证据）不通过即 400 PARAM_INVALID */
    @PostMapping("/{id}/submit")
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionTask> submit(@PathVariable Long id,
                                            @RequestBody(required = false) SubmitReq req,
                                            HttpServletRequest request) {
        AuditActor actor = actor(request);
        return action(OperationAuditService.ACTION_DECISION_SUBMIT, id, actor,
                () -> decisionService.submit(id, req, actor));
    }

    @PostMapping("/{id}/approve")
    @RequiresPermission(PermissionCode.DECISION_APPROVE)
    public ApiResponse<DecisionTask> approve(@PathVariable Long id, @RequestBody ApproveReq req,
                                             HttpServletRequest request) {
        AuditActor actor = actor(request);
        return action(OperationAuditService.ACTION_DECISION_APPROVE, id, actor,
                () -> decisionService.approve(id, req, actor));
    }

    /** 驳回：reason 必填（§20.3），缺则 400 PARAM_INVALID */
    @PostMapping("/{id}/reject")
    @RequiresPermission(PermissionCode.DECISION_APPROVE)
    public ApiResponse<DecisionTask> reject(@PathVariable Long id,
                                            @RequestBody(required = false) ReasonReq req,
                                            HttpServletRequest request) {
        AuditActor actor = actor(request);
        String reason = req == null ? null : req.reason();
        return action(OperationAuditService.ACTION_DECISION_REJECT, id, actor,
                () -> decisionService.reject(id, reason, actor));
    }

    @PostMapping("/{id}/start")
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionTask> start(@PathVariable Long id, HttpServletRequest request) {
        AuditActor actor = actor(request);
        return action(OperationAuditService.ACTION_DECISION_START, id, actor,
                () -> decisionService.start(id, actor));
    }

    @PostMapping("/{id}/complete")
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionTask> complete(@PathVariable Long id,
                                              @RequestBody(required = false) ExecuteReq req,
                                              HttpServletRequest request) {
        AuditActor actor = actor(request);
        String note = req == null ? null : req.note();
        return action(OperationAuditService.ACTION_DECISION_COMPLETE, id, actor,
                () -> decisionService.complete(id, note, actor));
    }

    /** 取消：reason 必填（§20.3），缺则 400 PARAM_INVALID */
    @PostMapping("/{id}/cancel")
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionTask> cancel(@PathVariable Long id,
                                            @RequestBody(required = false) ReasonReq req,
                                            HttpServletRequest request) {
        AuditActor actor = actor(request);
        String reason = req == null ? null : req.reason();
        return action(OperationAuditService.ACTION_DECISION_CANCEL, id, actor,
                () -> decisionService.cancel(id, reason, actor));
    }

    /** 效果评价：等长窗口前后对比（§20.4），数据不足判 INSUFFICIENT_DATA */
    @PostMapping("/{id}/evaluate")
    @RequiresPermission(PermissionCode.DECISION_CREATE)
    public ApiResponse<DecisionEvaluation> evaluate(@PathVariable Long id, HttpServletRequest request) {
        AuditActor actor = actor(request);
        return action(OperationAuditService.ACTION_DECISION_EVALUATE, id, actor,
                () -> decisionService.evaluate(id, actor));
    }

    /** 决策与评价属只读看板内容 → dashboard:view（四种角色都有） */
    @GetMapping
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<List<DecisionTask>> list(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(decisionService.list(limit), TraceContext.create().traceId());
    }

    @GetMapping("/{id}/evaluations")
    @RequiresPermission(PermissionCode.DASHBOARD_VIEW)
    public ApiResponse<List<DecisionEvaluation>> evaluations(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.evaluations(id), TraceContext.create().traceId());
    }

    /** 统一执行 + 失败留痕：审计 traceId 与响应信封同一个 */
    private <T> ApiResponse<T> action(String action, Long id, AuditActor actor, Supplier<T> body) {
        try {
            return ApiResponse.ok(body.get(), actor.traceId());
        } catch (RuntimeException e) {
            try {
                audit.failure(actor, action, OperationAuditService.RESOURCE_DECISION_TASK,
                        id == null ? null : String.valueOf(id), null, null, e.getMessage());
            } catch (RuntimeException auditError) {
                log.error("决策失败审计写入失败: action={}, id={}", action, id, auditError);
            }
            throw e;
        }
    }

    private AuditActor actor(HttpServletRequest request) {
        return CallerContext.actor(request, TraceContext.create().traceId());
    }
}
