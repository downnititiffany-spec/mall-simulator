package com.graduation.analytics.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.ExplanationService.ExplanationResult;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.TextToSqlService.QueryResult;
import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceRequest;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.ai.evidence.EvidenceTemplates;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * AI 智能分析接口（§24.3 子集）：自然语言查询 → 证据链结果；证据包 + 固定模板解释。
 *
 * <p>R8-3 改动：删除 {@code X-User-Id} 请求头与 {@code demo} 兜底 —— 审计归属只能是登录用户
 * （{@link CallerContext}，缺失即 401）；问数接口按 §3.2 归 {@code ai:query}（四种角色都有），
 * 审计查看归 {@code ai:audit:view}（仅 admin）；每次问数在 operation_audit_log 记一行
 * （§21.4：AI 查询必须可审计；SQL 本身的归属仍是 ai_query_history，此处只记哈希摘要）。</p>
 *
 * <p>R8-2 §1 改动：新增 {@code POST /api/v1/ai/explanations}（证据包全量 + 模板六段叙述）；
 * {@code /ai/queries} 增补 {@code evidenceSummary}/{@code evidenceId}。快照锚点**只由证据包给出**
 * （{@link EvidenceService#build} 钉住 ACTIVE 或显式快照，§19.5 禁止在 SQL 里 {@code MAX(snapshot_id)}，
 * 也禁止写 {@code "unknown"} 占位值）；本控制器不计算任何数值、不拼业务文本，只做搬运与形状适配。</p>
 *
 * <p>S3-57：解释来源不再通过“摘要文本是否变化”推断；{@link ExplanationResult#providerUsed()}
 * 由实际解释调用链记录最终采用的来源，控制器只原样搬运。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final TextToSqlService textToSqlService;
    private final ExplanationService explanationService;
    private final EvidenceService evidenceService;
    private final AiQueryHistoryMapper queryHistoryMapper;
    private final AiCallLogMapper callLogMapper;
    private final OperationAuditService audit;

    public record AiQueryReq(@NotBlank String question, String timeRange) {
    }

    /** 证据解释请求（R8-2 §1）：三段皆可选——不传快照取 ACTIVE，不传时间范围取快照业务日 */
    public record ExplainReq(String snapshotId, String timeRange, String question) {
    }

    /** 证据解释响应：evidence 为证据包全量；narrative 为可展示的叙述（模板六段 + 摘要） */
    public record EvidenceExplanationResp(EvidencePackage evidence, ExplanationNarrative narrative) {
    }

    /** 叙述形状（§19.3）：providerUsed=template 表示最终结论来自固定模板 */
    public record ExplanationNarrative(String summary, List<ExplanationSection> sections,
                                       List<String> limitations, String providerUsed,
                                       String templateVersion) {
    }

    public record ExplanationSection(String title, List<String> lines) {
    }

    public record AiQueryResp(QueryResult query, ExplanationResult explanation,
                              String evidenceSummary, String evidenceId) {
    }

    /**
     * 证据包 + 解释（R8-2 §1）：一次调用给出「证据（数值/血缘/质量）+ 六段叙述」。
     *
     * <p>取数/口径/模板全部在 ai-decision 的证据链里，本方法只把 {@link EvidencePackage} 与
     * {@link EvidenceTemplates.Narrative} 搬出去；模型不可用时 {@code explain} 回退固定模板，
     * 因此**永远不会因为模型问题返回 5xx**（§19.3）。取数本身失败则如实报错，不伪造证据。</p>
     */
    @PostMapping("/explanations")
    @RequiresPermission(PermissionCode.AI_QUERY)
    public ApiResponse<EvidenceExplanationResp> explain(@RequestBody(required = false) ExplainReq req,
                                                       HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        ExplainReq body = req == null ? new ExplainReq(null, null, null) : req;
        EvidencePackage pkg = evidenceService.build(
                new EvidenceRequest(body.snapshotId(), null, body.timeRange(), actor.userId()));
        ExplanationResult explanation = evidenceService.explain(pkg, body.question());
        EvidenceTemplates.Narrative template = EvidenceTemplates.render(pkg);
        auditExplanation(actor, pkg, body.question());
        return ApiResponse.ok(new EvidenceExplanationResp(pkg,
                new ExplanationNarrative(explanation.summary(), sectionsOf(template),
                        explanation.limitations(), explanation.providerUsed(),
                        template.templateVersion())), trace.traceId());
    }

    /** 一次完整问答：受控 Text-to-SQL + 证据解释（§3.5.4 AI 辅助运行模式；审计归属登录用户） */
    @PostMapping("/queries")
    @RequiresPermission(PermissionCode.AI_QUERY)
    public ApiResponse<AiQueryResp> query(@RequestBody AiQueryReq req, HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        QueryResult query = textToSqlService.query(req.question(), actor.userId());
        String timeRange = req.timeRange() == null || req.timeRange().isBlank() ? "近30天(默认)" : req.timeRange();
        EvidencePackage pkg = buildEvidenceQuietly(null, req.timeRange(), actor.userId());
        String snapshotId = resolveSnapshotId(pkg, query);
        ExplanationResult explanation = explanationService.explain(query, snapshotId,
                req.question(), timeRange);
        auditQuery(actor, req.question(), snapshotId, query);
        return ApiResponse.ok(new AiQueryResp(query, explanation,
                summaryOf(pkg), evidenceIdOf(pkg)), trace.traceId());
    }

    /** 仅生成解释（基于已有查询） */
    @PostMapping("/analyses")
    @RequiresPermission(PermissionCode.AI_QUERY)
    public ApiResponse<ExplanationResult> analyze(@RequestBody AiQueryReq req, HttpServletRequest request) {
        TraceContext trace = TraceContext.create();
        AuditActor actor = CallerContext.actor(request, trace.traceId());
        QueryResult query = textToSqlService.query(req.question(), actor.userId());
        EvidencePackage pkg = buildEvidenceQuietly(null, req.timeRange(), actor.userId());
        String snapshotId = resolveSnapshotId(pkg, query);
        ExplanationResult explanation = explanationService.explain(query, snapshotId, req.question(),
                req.timeRange() == null ? "近30天(默认)" : req.timeRange());
        auditQuery(actor, req.question(), snapshotId, query);
        return ApiResponse.ok(explanation, trace.traceId());
    }

    /** AI 查询审计落库（operation_audit_log）：只记状态/哈希/行数，不复制 SQL 与问题原文 */
    private void auditQuery(AuditActor actor, String question, String snapshotId, QueryResult query) {
        boolean failed = queryAuditFailed(query);
        String digest = OperationAuditService.digest(
                "status", query == null ? null : query.status(),
                "questionHash", shortHash(question),
                "sqlHash", shortHash(query == null ? null : query.sql()),
                "snapshot", snapshotId,
                "tables", query == null || query.tables() == null ? null : String.join(",", query.tables()),
                "rows", query == null ? null : String.valueOf(query.rowsReturned()),
                "elapsedMs", query == null ? null : String.valueOf(query.elapsedMs()),
                "errorCode", query == null ? null : query.errorCode());
        String action = OperationAuditService.ACTION_AI_QUERY;
        String resourceType = OperationAuditService.RESOURCE_AI_QUERY;
        String resourceId = "q-" + shortHash(question);
        if (failed) {
            audit.failure(actor, action, resourceType, resourceId, null, digest,
                    "AI 问数被治理规则拒绝/失败：" + (query == null ? "无结果" : query.errorCode()));
        } else {
            audit.success(actor, action, resourceType, resourceId, null, digest, "AI 问数");
        }
    }

    /**
     * operation_audit_log 的成功/失败判据唯一放在这里。
     * S3-59 修正：此前只识别 REJECT/ERROR，TextToSqlService 的 FAILED（例如无 ACTIVE、只读源缺失、超时）
     * 会被错误记成 audit.success。null 结果也按失败处理，不能把“无结果”记成成功。
     */
    static boolean queryAuditFailed(QueryResult query) {
        if (query == null || query.status() == null) {
            return true;
        }
        String status = query.status().toUpperCase(Locale.ROOT);
        return status.contains("REJECT") || status.contains("ERROR") || status.contains("FAIL");
    }

    /**
     * 证据解释也算一次 AI 查询（§21.4）：落一行审计。
     *
     * <p>只读接口不该因为审计表不可用而变成 5xx（解释接口不改业务状态），故写入失败只告警；
     * 告警文本点名 operation_audit_log 建表（V14）——迁移未执行时这里是唯一的可见信号。</p>
     */
    private void auditExplanation(AuditActor actor, EvidencePackage pkg, String question) {
        String digest = OperationAuditService.digest(
                "snapshot", pkg.snapshotId(),
                "evidenceId", pkg.evidenceId(),
                "facts", String.valueOf(pkg.facts().size()),
                "qualityGate", pkg.dataQuality() == null ? null : pkg.dataQuality().gateStatus(),
                "questionHash", shortHash(question),
                "warnings", pkg.warnings().isEmpty() ? null : String.join(",", pkg.warnings()));
        try {
            audit.success(actor, OperationAuditService.ACTION_AI_QUERY,
                    OperationAuditService.RESOURCE_AI_QUERY, pkg.evidenceId(), null, digest,
                    "AI 证据解释（快照 " + pkg.snapshotId() + "）");
        } catch (RuntimeException e) {
            log.warn("证据解释审计写入失败（operation_audit_log 是否已随 V14 建表？）: {}", e.getMessage());
        }
    }

    private EvidencePackage buildEvidenceQuietly(String snapshotId, String timeRange, String requestedBy) {
        try {
            return evidenceService.build(new EvidenceRequest(snapshotId, null, timeRange, requestedBy));
        } catch (RuntimeException e) {
            log.warn("证据包构建失败，问答照常返回（evidenceSummary/evidenceId 置空）: {}", e.getMessage());
            return null;
        }
    }

    private String resolveSnapshotId(EvidencePackage pkg, QueryResult query) {
        if (pkg != null && pkg.snapshotId() != null && !pkg.snapshotId().isBlank()) {
            return pkg.snapshotId();
        }
        Object fromRow = query != null && query.rows() != null && !query.rows().isEmpty()
                ? query.rows().get(0).get("snapshot_id") : null;
        if (fromRow != null && !String.valueOf(fromRow).isBlank()) {
            return String.valueOf(fromRow);
        }
        log.warn("AI 问数未取到快照锚点（证据包={}，结果行={}）：按 null 记录，不使用占位值",
                pkg == null ? "构建失败" : pkg.warnings(), fromRow);
        return null;
    }

    private static String summaryOf(EvidencePackage pkg) {
        return pkg == null ? null : EvidenceTemplates.render(pkg).summary();
    }

    private static String evidenceIdOf(EvidencePackage pkg) {
        return pkg == null ? null : pkg.evidenceId();
    }

    private static List<ExplanationSection> sectionsOf(EvidenceTemplates.Narrative template) {
        return template.sections().stream()
                .map(section -> new ExplanationSection(section.title(), section.lines()))
                .toList();
    }

    private static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/audit/history")
    @RequiresPermission(PermissionCode.AI_AUDIT_VIEW)
    public ApiResponse<List<AiQueryHistory>> auditHistory(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(queryHistoryMapper.selectList(new LambdaQueryWrapper<AiQueryHistory>()
                .orderByDesc(AiQueryHistory::getId)
                .last("LIMIT " + Math.max(1, Math.min(200, limit)))), TraceContext.create().traceId());
    }

    @GetMapping("/audit/calls")
    @RequiresPermission(PermissionCode.AI_AUDIT_VIEW)
    public ApiResponse<List<AiCallLog>> auditCalls(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                .orderByDesc(AiCallLog::getId)
                .last("LIMIT " + Math.max(1, Math.min(200, limit)))), TraceContext.create().traceId());
    }

    @GetMapping("/history/my")
    @RequiresPermission(PermissionCode.AI_QUERY)
    public ApiResponse<List<AiQueryHistory>> myHistory(@RequestParam(defaultValue = "10") int limit) {
        String userId = CallerContext.requireUserId();
        return ApiResponse.ok(queryHistoryMapper.selectList(new LambdaQueryWrapper<AiQueryHistory>()
                .eq(AiQueryHistory::getUserId, userId)
                .orderByDesc(AiQueryHistory::getId)
                .last("LIMIT " + Math.max(1, Math.min(50, limit)))), TraceContext.create().traceId());
    }
}
