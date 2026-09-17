package com.graduation.analytics.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceTemplates;
import com.graduation.analytics.ai.llm.JsonExtractor;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.llm.LlmProvider.AiRequest;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlExecutor.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解释服务（§8.8/§22.2-22.3、§19.3、§25）：
 *
 * <ul>
 *   <li><b>快照级解释（R8-1 主路径）</b>：只消费 {@link EvidencePackage}，先跑
 *       {@link EvidenceTemplates} 的固定六段模板（不依赖模型即可出完整结论），
 *       模型可用时**只允许改写摘要措辞**，且改写字串里的数字必须全部出现在证据包叙述中，
 *       否则判为越界 → 回退模板结果（§19.3「LLM 失败 → 模板结果」）；</li>
 *   <li><b>问数解释（保留路径）</b>：针对一次真实执行结果做证据约束解释，供 /ai/queries 使用。</li>
 * </ul>
 *
 * <p>S3-57：{@link ExplanationResult#providerUsed()} 直接记录**最终被采用结果**的真实来源。
 * 模型调用失败、数值守卫拒绝或 provider 不可用时，即使期间发生过网络调用，最终结果仍标记
 * {@code template}；只有模型输出实际被采用时才记录 {@link LlmProvider#providerName()}。
 * 控制器不得再通过“文本是否变化”反推 provider。</p>
 *
 * <p>S3-60：模板回退的 {@code limitations} 必须忠实描述**真实回退原因**。provider 超时/限流/网络/
 * 鉴权/格式故障不得再伪装成“数值校验失败”；已经尝试过模型调用的问数解释也不得声称“未调用大模型”。
 * 对外只暴露稳定的原因类别，不回显 provider 原始异常消息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplanationService {

    private final LlmProvider llmProvider;
    private final AiCallLogMapper callLogMapper;
    private final SqlExecutor executor;

    private static final String PROMPT_VERSION = "explain_v1";
    /** 证据包解释的提示词版本（§19.3 版本化模板：模板与提示词各自版本化） */
    private static final String PROMPT_VERSION_EVIDENCE = "explain_evidence_v1";
    /** 摘要长度上限，防止模型长篇发挥 */
    private static final int SUMMARY_MAX_CHARS = 400;
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    public record Evidence(String snapshotId, String question, String sql, List<String> tables,
                           int returnedRows, long queryElapsedMs, String timeRange, String definitions,
                           String evidenceId, String templateVersion) {
    }

    /**
     * @param providerUsed 最终被采用解释的来源；模板/规则回退固定为 {@code template}，
     *                     模型结果被实际采用时为 {@link LlmProvider#providerName()}。
     */
    public record ExplanationResult(String summary, List<Map<String, Object>> facts,
                                    List<Map<String, Object>> possibleCauses,
                                    List<Map<String, Object>> suggestions,
                                    List<String> limitations, String providerUsed, Evidence evidence) {
    }

    /** 内部改写结果：成功时带摘要；失败时只带可安全展示的稳定回退说明。 */
    private record RewriteResult(String summary, String fallbackLimitation) {
        private static RewriteResult accepted(String summary) {
            return new RewriteResult(summary, null);
        }

        private static RewriteResult fallback(String limitation) {
            return new RewriteResult(null, limitation);
        }

        private boolean accepted() {
            return summary != null;
        }
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
            return ruleBased(query, question, snapshotId, timeRange,
                    "规则回退模式：模型服务不可用，未调用大模型");
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
                    llmProvider.providerName(),
                    new Evidence(snapshotId, question, query.sql(), query.tables(),
                            query.rowsReturned(), query.elapsedMs(), timeRange, PROMPT_VERSION, null, null));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            logCall("explanation", elapsed, "FAILED", e.getMessage());
            log.warn("explanation failed, fallback to rule-based: {}", e.getMessage());
            return ruleBased(query, question, snapshotId, timeRange,
                    "规则回退模式：" + providerFailureLabel(e) + "，已回退模板摘要");
        }
    }

    /** 规则化摘要（无 LLM 或 LLM 失败时的证据链兜底，§3.5.5） */
    private ExplanationResult ruleBased(TextToSqlService.QueryResult query, String question,
                                        String snapshotId, String timeRange, String limitation) {
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
                List.of(limitation),
                EvidenceTemplates.Narrative.PROVIDER_TEMPLATE,
                new Evidence(snapshotId, question, query.sql(), query.tables(),
                        query.rowsReturned(), query.elapsedMs(), timeRange, PROMPT_VERSION, null, null));
    }

    // ── R8-1：证据包解释（只消费 EvidencePackage；模板优先，模型只改措辞） ──────────

    /**
     * 基于证据包生成结构化解释。
     *
     * <p>顺序固定：模板六段先成立 → 模型可用则只改写摘要（数字必须来自证据包叙述）→
     * 任何失败（模型不可用/越界/解析失败）都回退模板结果，**永远有结论**（§19.3）。</p>
     */
    public ExplanationResult explain(EvidencePackage pkg, String question) {
        EvidenceTemplates.Narrative narrative = EvidenceTemplates.render(pkg);
        String summary = narrative.summary();
        String providerUsed = EvidenceTemplates.Narrative.PROVIDER_TEMPLATE;
        List<String> limitations = new ArrayList<>(narrative.limitations());

        if (llmProvider.healthCheck()) {
            RewriteResult rewrite = rewriteSummary(pkg, question, narrative);
            if (rewrite.accepted()) {
                summary = rewrite.summary();
                providerUsed = llmProvider.providerName();
            } else {
                limitations.add(rewrite.fallbackLimitation());
            }
        } else {
            limitations.add("未调用大模型：结论完全来自固定模板");
        }

        return new ExplanationResult(summary, evidenceFacts(pkg), anomalyCauses(pkg),
                actionSuggestions(narrative), limitations, providerUsed,
                new Evidence(pkg.snapshotId(), question, null,
                        pkg.lineage() == null ? List.of() : pkg.lineage().mysqlTables(),
                        pkg.facts().size(), 0L, periodText(pkg), pkg.definitionVersion(),
                        pkg.evidenceId(), pkg.templateVersion()));
    }

    /** 模板段落（供接口原样返回，前端不必重新拼段落） */
    public Map<String, List<String>> sections(EvidencePackage pkg) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        EvidenceTemplates.render(pkg).sections().forEach(s -> out.put(s.title(), s.lines()));
        return out;
    }

    /**
     * 模型只改写摘要：成功返回摘要；失败返回可安全展示的稳定原因，调用方统一回退模板。
     * 数值守卫：改写字串中出现的每个数字都必须能在证据包叙述里找到（日期、计数、指标值都算）。
     */
    private RewriteResult rewriteSummary(EvidencePackage pkg, String question, EvidenceTemplates.Narrative narrative) {
        String prompt = """
                你是电商经营分析助手。下面是**已经算好并带证据引用**的分析结论，请只做措辞改写：
                1) 不得新增、删除、修改任何数字；不得引入结论中没有的指标或事实；
                2) 不得下因果结论（相关性不等于因果），不得承诺结果；
                3) 只输出一句中文摘要，120 字以内，不要 JSON、不要换行。
                """;
        String user = "问题：" + (question == null ? "（未提供）" : question)
                + "\n快照：" + (pkg.snapshotId() == null ? "无可用快照" : pkg.snapshotId())
                + "\n结论原文：\n" + narrative.toText();
        long start = System.currentTimeMillis();
        try {
            String content = llmProvider.complete(new AiRequest(prompt, user, 0.0)).content();
            long elapsed = System.currentTimeMillis() - start;
            String candidate = content == null ? "" : content.trim().replaceAll("\\s+", " ");
            if (candidate.isEmpty() || candidate.length() > SUMMARY_MAX_CHARS) {
                logCall("explanation_evidence", elapsed, "REJECTED", "SUMMARY_LENGTH_GUARD", PROMPT_VERSION_EVIDENCE);
                return RewriteResult.fallback(
                        "模型改写不符合摘要格式/长度约束，摘要回退固定模板（数值仍来自证据包）");
            }
            List<String> violations = numberViolations(candidate, narrative.toText());
            if (!violations.isEmpty()) {
                logCall("explanation_evidence", elapsed, "REJECTED",
                        "SUMMARY_NUMBER_GUARD:" + violations, PROMPT_VERSION_EVIDENCE);
                return RewriteResult.fallback(
                        "模型改写未通过数值校验，摘要回退固定模板（数值仍来自证据包）");
            }
            logCall("explanation_evidence", elapsed, "OK", null, PROMPT_VERSION_EVIDENCE);
            return RewriteResult.accepted(candidate);
        } catch (Exception e) {
            logCall("explanation_evidence", System.currentTimeMillis() - start, "FAILED",
                    e.getMessage(), PROMPT_VERSION_EVIDENCE);
            log.warn("evidence explanation rewrite failed, fallback to template: {}", e.getMessage());
            return RewriteResult.fallback(
                    providerFailureLabel(e) + "，摘要回退固定模板（数值仍来自证据包）");
        }
    }

    /**
     * 把 provider 稳定故障类型映射成可安全展示的原因；不回显原始异常 message，避免把供应商细节/凭据带到响应。
     */
    private static String providerFailureLabel(Exception e) {
        if (e instanceof LlmProvider.LlmException llmException) {
            return switch (llmException.type()) {
                case "TIMEOUT" -> "模型调用超时";
                case "RATE_LIMITED" -> "模型服务限流";
                case "NETWORK" -> "模型网络调用失败";
                case "AUTH" -> "模型服务鉴权失败";
                case "FORMAT" -> "模型返回格式不可用";
                default -> "模型调用失败";
            };
        }
        return "模型调用失败";
    }

    /** 返回候选摘要里「证据包叙述中找不到」的数字（空 = 通过） */
    static List<String> numberViolations(String candidate, String evidenceText) {
        Set<String> allowed = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(evidenceText == null ? "" : evidenceText);
        while (m.find()) {
            allowed.add(m.group());
        }
        Set<String> violations = new LinkedHashSet<>();
        Matcher c = NUMBER.matcher(candidate == null ? "" : candidate);
        while (c.find()) {
            if (!allowed.contains(c.group())) {
                violations.add(c.group());
            }
        }
        return List.copyOf(violations);
    }

    /** 事实：每条都带 evidenceIds（列/证据引用），供前端逐条复核 */
    private List<Map<String, Object>> evidenceFacts(EvidencePackage pkg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvidencePackage.Fact f : pkg.facts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statement", (f.metricName() == null || f.metricName().isBlank() ? f.metricCode()
                    : f.metricName() + "(" + f.metricCode() + ")") + " = " + f.value()
                    + (f.unit() == null ? "" : f.unit()) + "，期间 " + f.period());
            m.put("metricCode", f.metricCode());
            m.put("value", f.value());
            m.put("evidenceIds", List.of(f.evidenceRef()));
            out.add(m);
        }
        for (EvidencePackage.Comparison c : pkg.comparisons()) {
            if (c.baseline() == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statement", c.metricCode() + " 环比变化 " + c.delta() + "（" + c.deltaRate() + "）");
            m.put("metricCode", c.metricCode());
            m.put("value", c.delta());
            m.put("evidenceIds", List.of(c.evidenceRef(), c.baselineRef()));
            out.add(m);
        }
        if (out.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statement", "无可用快照/指标，未取到任何事实");
            m.put("evidenceIds", List.of());
            out.add(m);
        }
        return out;
    }

    /** 候选异常 → possibleCauses（confidence 固定 LOW：阈值命中不等于原因） */
    private List<Map<String, Object>> anomalyCauses(EvidencePackage pkg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvidencePackage.AnomalyCandidate a : pkg.anomalies()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("statement", a.statement());
            m.put("confidence", "LOW");
            m.put("ruleCode", a.ruleCode());
            m.put("severity", a.severity());
            m.put("evidenceIds", List.of(a.evidenceRef()));
            out.add(m);
        }
        return out;
    }

    /** 核查行动段落 → suggestions（每条都对应一条规则命中） */
    private List<Map<String, Object>> actionSuggestions(EvidenceTemplates.Narrative narrative) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvidenceTemplates.Section s : narrative.sections()) {
            if (!EvidenceTemplates.T_ACTIONS.equals(s.title())) {
                continue;
            }
            for (String line : s.lines()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", line.length() > 60 ? line.substring(0, 60) + "…" : line);
                m.put("action", line);
                m.put("targetMetricCode", null);
                out.add(m);
            }
        }
        return out;
    }

    private static String periodText(EvidencePackage pkg) {
        EvidencePackage.Period p = pkg.currentPeriod();
        if (p == null) {
            return "-";
        }
        return p.from() == null || p.from().equals(p.to()) ? String.valueOf(p.to()) : p.from() + "~" + p.to();
    }

    /** 校验 + 脱敏后执行一个白名单 SQL（供 evidence 复核场景） */
    public ExecutionResult executeSafe(String sql) throws SQLException {
        return executor.execute(sql);
    }

    private void logCall(String useCase, long elapsedMs, String status, String error) {
        logCall(useCase, elapsedMs, status, error, PROMPT_VERSION);
    }

    private void logCall(String useCase, long elapsedMs, String status, String error, String promptVersion) {
        AiCallLog logRow = new AiCallLog();
        logRow.setUseCase(useCase);
        logRow.setProvider(llmProvider.providerName());
        logRow.setPromptVersion(promptVersion);
        logRow.setElapsedMs(elapsedMs);
        logRow.setStatus(status);
        logRow.setError(error == null ? null : truncate(error, 200));
        logRow.setCreatedAt(LocalDateTime.now());
        callLogMapper.insert(logRow);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
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