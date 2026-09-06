package com.graduation.mall.controller;

import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.decision.DecisionService;
import com.graduation.mall.decision.DecisionService.ApproveReq;
import com.graduation.mall.decision.DecisionService.CreateDraftReq;
import com.graduation.mall.decision.DecisionStateMachine;
import com.graduation.mall.decision.entity.DecisionEvaluation;
import com.graduation.mall.decision.entity.DecisionTask;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 决策中心接口（§24.3 子集）：AI 草稿→人工审核→执行→效果评价。
 * 从 PENDING_REVIEW 到 APPROVED 必须人工操作（§22.6）。
 */
@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionService decisionService;

    public record TransitionReq(String reason) {
    }

    public record AiDraftReq(String title, String action, String targetMetricCode,
                             String targetDirection, String suggestionSnapshotId, String risk) {
    }

    /** AI 建议 → 决策草稿（AI 只能创建 DRAFT） */
    @PostMapping
    public ApiResponse<DecisionTask> createDraft(@Valid @RequestBody CreateDraftReq req,
                                                 @RequestHeader(value = "X-User-Id", defaultValue = "demo") String user) {
        DecisionTask task = decisionService.createDraft(req, user, "ai");
        return ApiResponse.ok(task, TraceContext.create().traceId());
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<DecisionTask> submit(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.transition(id, DecisionStateMachine.PENDING_REVIEW, "demo"),
                TraceContext.create().traceId());
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<DecisionTask> approve(@PathVariable Long id, @RequestBody ApproveReq req,
                                             @RequestHeader(value = "X-User-Id", defaultValue = "admin") String user) {
        return ApiResponse.ok(decisionService.approve(id, req, user), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<DecisionTask> reject(@PathVariable Long id, @RequestBody(required = false) TransitionReq req) {
        return ApiResponse.ok(decisionService.transition(id, DecisionStateMachine.REJECTED, "demo"),
                TraceContext.create().traceId());
    }

    @PostMapping("/{id}/start")
    public ApiResponse<DecisionTask> start(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.transition(id, DecisionStateMachine.IN_PROGRESS, "demo"),
                TraceContext.create().traceId());
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<DecisionTask> complete(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.transition(id, DecisionStateMachine.COMPLETED, "demo"),
                TraceContext.create().traceId());
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DecisionTask> cancel(@PathVariable Long id, @RequestBody(required = false) TransitionReq req) {
        String reason = req == null || req.reason() == null ? null : req.reason();
        return ApiResponse.ok(decisionService.cancel(id, reason, "demo"), TraceContext.create().traceId());
    }

    @PostMapping("/{id}/evaluate")
    public ApiResponse<DecisionEvaluation> evaluate(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.evaluate(id, "demo"), TraceContext.create().traceId());
    }

    @GetMapping
    public ApiResponse<List<DecisionTask>> list(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(decisionService.list(limit), TraceContext.create().traceId());
    }

    @GetMapping("/{id}/evaluations")
    public ApiResponse<List<DecisionEvaluation>> evaluations(@PathVariable Long id) {
        return ApiResponse.ok(decisionService.evaluations(id), TraceContext.create().traceId());
    }
}