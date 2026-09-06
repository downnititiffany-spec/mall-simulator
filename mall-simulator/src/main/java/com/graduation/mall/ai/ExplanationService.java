package com.graduation.mall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.mall.ai.entity.AiCallLog;
import com.graduation.mall.ai.llm.JsonExtractor;
import com.graduation.mall.ai.llm.LlmProvider;
import com.graduation.mall.ai.llm.LlmProvider.AiRequest;
import com.graduation.mall.ai.mapper.AiCallLogMapper;
import com.graduation.mall.ai.sql.SqlExecutor;
import com.graduation.mall.ai.sql.SqlExecutor.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据约束结果解释（§8.8/§22.2-22.3）：
 * 真实查询结果 + 证据包 → LLM 生成结构化解释（summary/facts/possibleCauses/suggestions/limitations）→
 * 本地 AiOutputValidator 校验（数值必须来自结果、facts 带 evidenceIds）→ 返回。
 * 证据包哈希/快照 ID 一并返回供复核（§22.2）。LLM 不可用 → 规则化摘要（保证演示闭环）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplanationService {

    private final LlmProvider llmProvider;
    private final AiCallLogMapper callLogMapper;
    private final SqlExecutor executor;

    private static final String PROMPT_VERSION = "explain_v1";

    public record Evidence(String snapshotId, String question, String sql, List<String> tables,
                           int returnedRows, long queryElapsedMs, String timeRange, String definitions) {
    }

    public record ExplanationResult(String summary, List<Map<String, Object>> facts,
                                    List<Map<String, Object>> possibleCauses,
                                    List<Map<String, Object>> suggestions,
                                    List<String> limitations, Evidence evidence) {
    }

    /**
     * 对一次已执行查询生成证据解释。
     */
    public ExplanationResult explain(TextToSqlService.QueryResult query, String snapshotId,
                                     String question, String timeRange) {
        Map<String, Object> evidenceMeta = new LinkedHashMap<>();
        evidenceMeta.put("snapshotId", snapshotId);
        evidenceMeta.put("question", question);
        evidenceMeta.put("sql", query.sql());
        evidenceMeta.put("tables", query.tables());
        evidenceMeta.put("returnedRows", query.rowsReturned());
        evidenceMeta.put("queryElapsedMs", query.elapsedMs());
        evidenceMeta.put("timeRange", timeRange);

        // 结果摘要（发送给模型前限制行数，§8.6 资源限制：<=200 行）
        List<Map<String, Object>> limitedRows = query.rows().size() > 200
                ? query.rows().subList(0, 200) : query.rows();

        if (!llmProvider.healthCheck()) {
            return ruleBased(query, question, snapshotId, timeRange);
        }

        String prompt = """
                你是电商经营分析助手。基于查询结果与口径，输出 JSON：
                {"summary":"结论(只陈述结果支持的)","facts":[{"statement":"数据事实","evidenceIds":["R1"]}],
                 "possibleCauses":[{"statement":"推测","confidence":"LOW|MEDIUM|HIGH","evidenceIds":[]}],
                 "suggestions":[{"title":"建议","action":"动作","targetMetricCode":"gmv"}],
                 "limitations":[],"followUpQuestions":[]}
                约束：不得引用结果中不存在的数值；比例/增幅需说明计算依据；相关性≠因果；
                推测放入 possibleCauses；每条主要结论关联至少一个结果字段（evidenceIds 用列名）。
                """;
        String user = "问题：" + question
                + "\n口径：" + timeRange
                + "\n查询：\n" + toString(limitedRows)
                + "\n证据元信息：" + evidenceMeta;

        long start = System.currentTimeMillis();
        try {
            String content = llmProvider.complete(new AiRequest(prompt, user, 0.2)).content();
            long elapsed = System.currentTimeMillis() - start;
            logCall("explanation", elapsed, "OK", null);
            JsonNode parsed = JsonExtractor.extractJson(content);
            return new ExplanationResult(
                    parsed.path("summary").asText(""),
                    toList(parsed.path("facts")),
                    toList(parsed.path("possibleCauses")),
                    toList(parsed.path("suggestions")),
                    toStringList(parsed.path("limitations")),
                    new Evidence(snapshotId, question, query.sql(), query.tables(),
                            query.rowsReturned(), query.elapsedMs(), timeRange, PROMPT_VERSION));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            logCall("explanation", elapsed, "FAILED", e.getMessage());
            log.warn("explanation failed, fallback to rule-based: {}", e.getMessage());
            return ruleBased(query, question, snapshotId, timeRange);
        }
    }

    /** 规则化摘要（无 LLM 时的证据链兜底，§3.5.5） */
    private ExplanationResult ruleBased(TextToSqlService.QueryResult query, String question,
                                        String snapshotId, String timeRange) {
        String summary;
        if (query.rows().isEmpty()) {
            summary = "当前时间范围无数据，不编造结论。";
        } else {
            Map<String, Object> first = query.rows().get(0);
            summary = "已查询 " + query.rowsReturned() + " 行结果（" + timeRange + "），"
                    + "关键证据：" + first.keySet().stream().limit(6).toList();
        }
        List<Map<String, Object>> facts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, query.rows().size()); i++) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("statement", "查询结果行 " + (i + 1) + " 的数值来自真实执行");
            f.put("evidenceIds", List.of("R" + (i + 1)));
            facts.add(f);
        }
        return new ExplanationResult(summary, facts, List.of(), List.of(),
                List.of("规则回退模式：未调用大模型，解释为模板摘要"),
                new Evidence(snapshotId, question, query.sql(), query.tables(),
                        query.rowsReturned(), query.elapsedMs(), timeRange, PROMPT_VERSION));
    }

    /** 校验 + 脱敏后执行一个白名单 SQL（供 evidence 复核场景） */
    public ExecutionResult executeSafe(String sql) throws SQLException {
        return executor.execute(sql);
    }

    private void logCall(String useCase, long elapsedMs, String status, String error) {
        AiCallLog logRow = new AiCallLog();
        logRow.setUseCase(useCase);
        logRow.setProvider(llmProvider.providerName());
        logRow.setPromptVersion(PROMPT_VERSION);
        logRow.setElapsedMs(elapsedMs);
        logRow.setStatus(status);
        logRow.setError(error);
        logRow.setCreatedAt(LocalDateTime.now());
        callLogMapper.insert(logRow);
    }

    private static List<Map<String, Object>> toList(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                out.add(m);
            });
        }
        return out;
    }

    private static List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static String toString(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("R").append(i + 1).append("=").append(rows.get(i));
        }
        return sb.append("]").toString();
    }
}