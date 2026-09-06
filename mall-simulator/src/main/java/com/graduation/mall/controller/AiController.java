package com.graduation.mall.controller;

import com.graduation.mall.ai.ExplanationService;
import com.graduation.mall.ai.ExplanationService.Evidence;
import com.graduation.mall.ai.ExplanationService.ExplanationResult;
import com.graduation.mall.ai.TextToSqlService;
import com.graduation.mall.ai.TextToSqlService.QueryResult;
import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 智能分析接口（§24.3 子集）：自然语言查询 → 证据链结果；证据解释。
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final TextToSqlService textToSqlService;
    private final ExplanationService explanationService;

    public record AiQueryReq(@NotBlank String question, String timeRange) {
    }

    public record AiQueryResp(QueryResult query, ExplanationResult explanation) {
    }

    /** 一次完整问答：受控 Text-to-SQL + 证据解释（§3.5.4 AI 辅助运行模式） */
    @PostMapping("/queries")
    public ApiResponse<AiQueryResp> query(@RequestBody AiQueryReq req,
                                          @RequestHeader(value = "X-User-Id", defaultValue = "demo") String userId) {
        TraceContext trace = TraceContext.create();
        QueryResult query = textToSqlService.query(req.question(), userId);
        String snapshotId = latestSnapshotId(query);
        String timeRange = req.timeRange() == null || req.timeRange().isBlank() ? "近30天(默认)" : req.timeRange();
        ExplanationResult explanation = explanationService.explain(query, snapshotId,
                req.question(), timeRange);
        return ApiResponse.ok(new AiQueryResp(query, explanation), trace.traceId());
    }

    /** 仅生成解释（基于已有查询） */
    @PostMapping("/analyses")
    public ApiResponse<ExplanationResult> analyze(@RequestBody AiQueryReq req) {
        TraceContext trace = TraceContext.create();
        QueryResult query = textToSqlService.query(req.question(), "demo");
        ExplanationResult explanation = explanationService.explain(query,
                latestSnapshotId(query), req.question(),
                req.timeRange() == null ? "近30天(默认)" : req.timeRange());
        return ApiResponse.ok(explanation, trace.traceId());
    }

    private String latestSnapshotId(QueryResult query) {
        // 从查询结果行中尽力提取 snapshot_id；无则标注 unknown
        if (query.rows() != null && !query.rows().isEmpty()) {
            Object s = query.rows().get(0).get("snapshot_id");
            if (s != null) {
                return String.valueOf(s);
            }
        }
        return "unknown";
    }
}