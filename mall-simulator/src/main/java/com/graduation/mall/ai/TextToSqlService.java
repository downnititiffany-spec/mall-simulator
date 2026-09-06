package com.graduation.mall.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.mall.ai.entity.AiCallLog;
import com.graduation.mall.ai.entity.AiQueryHistory;
import com.graduation.mall.ai.llm.JsonExtractor;
import com.graduation.mall.ai.llm.LlmProvider;
import com.graduation.mall.ai.llm.LlmProvider.AiRequest;
import com.graduation.mall.ai.mapper.AiCallLogMapper;
import com.graduation.mall.ai.mapper.AiQueryHistoryMapper;
import com.graduation.mall.ai.sql.SqlExecutor;
import com.graduation.mall.ai.sql.SqlSafetyValidator;
import com.graduation.mall.ai.sql.SqlSafetyValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 受控 Text-to-SQL 服务（§8.4/§8.7）：
 * 意图+主题 → Schema 裁剪 → LLM 生成 JSON → 四层安全校验 → 失败反馈一次修复 →
 * 只读执行 → 证据结果。LLM 不可用 → 规则回退（推荐问题模板）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextToSqlService {

    private final LlmProvider llmProvider;
    private final SemanticCatalog catalog;
    private final SqlSafetyValidator validator;
    private final SqlExecutor executor;
    private final AiQueryHistoryMapper historyMapper;
    private final AiCallLogMapper callLogMapper;

    public record QueryResult(String status, String sql, List<String> tables, int rowsReturned,
                              long elapsedMs, List<Map<String, Object>> rows, List<String> assumptions,
                              String error, String providerUsed) {
    }

    private static final String PROMPT_VERSION = "sql_v1";

    public QueryResult query(String question, String userId) {
        long start = System.currentTimeMillis();
        String status = "GENERATED";
        String sql = null;
        List<String> tables = catalog.selectTables(question);
        List<Map<String, Object>> rows = List.of();
        int rowsReturned = 0;
        List<String> assumptions = List.of();
        String error = null;
        String providerUsed = "rule-based";

        try {
            if (llmProvider.healthCheck()) {
                providerUsed = llmProvider.providerName();
                // ── 1. LLM 生成（temperature=0，§8.10） ──────────────────
                AiRequest req = new AiRequest(buildSystemPrompt(), buildUserQuestion(question, tables), 0);
                long callStart = System.currentTimeMillis();
                String content = llmProvider.complete(req).content();
                logCall("sql_generation", providerUsed, System.currentTimeMillis() - callStart, "OK", null);

                JsonNode parsed = JsonExtractor.extractJson(content);
                sql = parsed.path("sql").asText(null);
                if (sql == null || sql.isBlank()) {
                    throw new IllegalStateException("模型未返回 sql 字段");
                }
                assumptions = jsonArray(parsed.path("assumptions"));

                // ── 2. 安全校验 ──────────────────────────────────────────
                ValidationResult v = validator.validate(sql);
                if (!v.ok()) {
                    // ── 3. 一次受控修复（§8.7） ──────────────────────────
                    String repairPrompt = "上一次生成的 SQL 未通过安全校验：\n" + v.error()
                            + "\n原问题：" + question
                            + "\nSchema：" + catalog.schemaJson(tables)
                            + "\n请只输出修正后的 JSON（含 sql 字段），SQL 必须通过检查。";
                    long repairStart = System.currentTimeMillis();
                    String repairContent = llmProvider.complete(new AiRequest(
                            buildSystemPrompt(), repairPrompt, 0)).content();
                    logCall("sql_repair", providerUsed, System.currentTimeMillis() - repairStart, "OK", null);
                    JsonNode repaired = JsonExtractor.extractJson(repairContent);
                    sql = repaired.path("sql").asText(null);
                    v = validator.validate(sql);
                    if (!v.ok()) {
                        throw new IllegalStateException("修复后仍未通过安全校验: " + v.error());
                    }
                    status = "REPAIRED";
                } else {
                    status = "SAFE";
                }
                sql = v.sql(); // 可能被重写（LIMIT）
            } else {
                // ── 规则回退（§3.5.5 AI 不可用） ─────────────────────────
                RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve(question, tables);
                sql = fb.sql();
                assumptions = fb.assumptions();
                status = "GENERATED";
            }

            // ── 4. 只读执行 ──────────────────────────────────────────────
            SqlExecutor.ExecutionResult exec = executor.execute(sql);
            rows = exec.rows();
            rowsReturned = rows.size();
            // 状态语义：经历过修复 → REPAIRED；直接通过 → EXECUTED（§8.7 修复闭环可追踪）
            if (!status.equals("REPAIRED")) {
                status = "EXECUTED";
            }
        } catch (Exception e) {
            error = truncate(e.getMessage(), 500);
            status = "FAILED";
            log.warn("text-to-sql failed: {}", error);
        } finally {
            saveHistory(userId, question, sql, tables, status, rowsReturned,
                    System.currentTimeMillis() - start, error);
        }
        return new QueryResult(status, sql, tables, rowsReturned,
                System.currentTimeMillis() - start, rows, assumptions, error, providerUsed);
    }

    // ── Prompt 构建（§8.5 安全规则内嵌） ──────────────────────────────────

    private String buildSystemPrompt() {
        return """
                你是指标查询助手。只能生成只读 SELECT 查询，遵守：
                1. 只使用我给出的表和字段，禁止编造；
                2. 必须包含时间条件；问题没给时间范围时用最近 30 天并在 assumptions 注明；
                3. 只输出 JSON：{"intent","metrics","sql","assumptions"}；
                4. sql 只允许单表 SELECT（可含 WHERE/ORDER BY/GROUP BY/LIMIT）；
                5. 禁止 JOIN 子查询、禁止 DDL/DML、禁止注释；
                6. 金额字段来自表定义，不可直接对客单价做 AVG；
                7. 不确定时返回 clarification_needed。
                """;
    }

    private String buildUserQuestion(String question, List<String> tables) {
        return "问题：" + question
                + "\n可用 Schema：" + catalog.schemaJson(tables)
                + "\n少样本：\n" + catalog.fewShots(tables);
    }

    // ── 审计（§12.2/§24.1） ───────────────────────────────────────────────

    private void logCall(String useCase, String provider, long elapsedMs, String status, String error) {
        AiCallLog logRow = new AiCallLog();
        logRow.setUseCase(useCase);
        logRow.setProvider(provider);
        logRow.setModel(null);
        logRow.setPromptVersion(PROMPT_VERSION);
        logRow.setElapsedMs(elapsedMs);
        logRow.setStatus(status);
        logRow.setError(error);
        logRow.setCreatedAt(LocalDateTime.now());
        callLogMapper.insert(logRow);
    }

    private void saveHistory(String userId, String question, String sql, List<String> tables,
                             String status, int rowsReturned, long elapsedMs, String error) {
        AiQueryHistory h = new AiQueryHistory();
        h.setUserId(userId);
        h.setQuestion(question);
        h.setSqlText(sql);
        h.setTables(String.join(",", tables));
        h.setStatus(status);
        h.setRowsReturned(rowsReturned);
        h.setElapsedMs(elapsedMs);
        h.setErrors(error);
        h.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(h);
    }

    private static List<String> jsonArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}