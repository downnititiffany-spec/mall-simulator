package com.graduation.analytics.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.ai.entity.AiCallLog;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.llm.JsonExtractor;
import com.graduation.analytics.ai.llm.LlmProvider;
import com.graduation.analytics.ai.llm.LlmProvider.AiRequest;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.ai.sql.AiSqlException;
import com.graduation.analytics.ai.sql.QueryCostGuard;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlPolicy;
import com.graduation.analytics.ai.sql.SqlSafetyValidator;
import com.graduation.analytics.ai.sql.SqlSafetyValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 受控 Text-to-SQL 服务（§19.4 流程 / R8-2 契约 §2.2-§2.4）。
 *
 * <p>流程（顺序即安全边界）：<br>
 * ① {@link AiScopeResolver#resolve()} 取 ACTIVE 快照 → {@link AiScope}（无 ACTIVE 直接失败）；<br>
 * ② 把**真实日期/快照作为字面量**注入上下文与提示词（含参数化 few-shot）；<br>
 * ③ LLM 生成 SQL（不可用则规则回退，回退模板同样带字面量）；<br>
 * ④ {@link SqlSafetyValidator#validate(String, AiScope)} 全树校验（失败允许一次受控修复）；<br>
 * ⑤ {@link QueryCostGuard#check(String)} EXPLAIN 成本校验；<br>
 * ⑥ {@link SqlExecutor} 只读执行（metric_read + 30s + LIMIT 200）。</p>
 *
 * <p>反熵（R8-2）：<br>
 * ① 提示词版本 {@code sql_v1 → sql_v2}：v1 教模型写「最近 30 天」并允许自锁快照，
 * 与「禁子查询 + 必须带 snapshot_id/dt 字面量」直接冲突；v2 把 scope 作为事实下发。<br>
 * ② 被拒绝的查询**也写审计行**（{@code status=REJECTED}，{@code errors=<规则码>}），
 * 否则「谁在试探校验器」在库里完全看不见（§19.6 全程审计）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextToSqlService {

    /** 契约 §2.1/§2.2：提示词与 few-shot 版本化标识 */
    public static final String PROMPT_VERSION = "sql_v2";

    /** 审计状态：校验/成本被拒（不执行） */
    public static final String STATUS_REJECTED = "REJECTED";

    private final LlmProvider llmProvider;
    private final SemanticCatalog catalog;
    private final SqlSafetyValidator validator;
    private final QueryCostGuard costGuard;
    private final AiScopeResolver scopeResolver;
    private final SqlExecutor executor;
    private final AiQueryHistoryMapper historyMapper;
    private final AiCallLogMapper callLogMapper;

    /**
     * 查询结果。
     *
     * @param errorCode 稳定错误码/规则码（如 SQL_JOIN、SQL_COST_TOO_HIGH、NO_ACTIVE_SNAPSHOT），成功为 null
     */
    public record QueryResult(String status, String sql, List<String> tables, int rowsReturned,
                              long elapsedMs, List<Map<String, Object>> rows, List<String> assumptions,
                              String error, String errorCode, String providerUsed) {
    }

    public QueryResult query(String question, String userId) {
        long start = System.currentTimeMillis();
        String status = "GENERATED";
        String sql = null;
        List<String> tables = catalog.selectTables(question);
        List<Map<String, Object>> rows = List.of();
        int rowsReturned = 0;
        List<String> assumptions = List.of();
        String error = null;
        String errorCode = null;
        String providerUsed = "rule-based";
        AiScope scope = null;
        Long explainRows = null;
        String stage = "SCOPE_RESOLVE";

        try {
            // ── 1. ACTIVE 快照作用域（日期/快照参数化的唯一来源，§19.5） ──────────
            scope = scopeResolver.resolve();

            // ── 1.5 问句层注入筛（§19.5 纵深防御；拒绝也留审计，2026-09-11 补强） ──
            // 放在 scope 之后：被拒问句同样带快照/日期作用域，便于事后取证。
            stage = "SCREEN";
            QuestionSafetyScreen.check(question);

            if (llmProvider.healthCheck()) {
                providerUsed = llmProvider.providerName();
                // ── 2. LLM 生成（temperature=0；提示词内嵌 scope 字面量） ──────────
                stage = "GENERATE";
                AiRequest req = new AiRequest(buildSystemPrompt(scope), buildUserQuestion(question, tables, scope), 0);
                long callStart = System.currentTimeMillis();
                String content = llmProvider.complete(req).content();
                logCall("sql_generation", providerUsed, System.currentTimeMillis() - callStart, "OK", null);

                JsonNode parsed = JsonExtractor.extractJson(content);
                sql = parsed.path("sql").asText(null);
                if (sql == null || sql.isBlank()) {
                    throw new IllegalStateException("模型未返回 sql 字段");
                }
                assumptions = jsonArray(parsed.path("assumptions"));

                // ── 3. 全树安全校验 ────────────────────────────────────────────
                stage = "VALIDATE";
                ValidationResult v = validator.validate(sql, scope);
                if (!v.ok()) {
                    // ── 一次受控修复（§8.7）：把规则码与 scope 一起回灌，避免修出同类问题 ──
                    stage = "REPAIR";
                    String repairPrompt = "上一次生成的 SQL 未通过安全校验：[" + v.code() + "] " + v.error()
                            + "\n原问题：" + question
                            + "\n必须满足：单表 SELECT；WHERE 含 snapshot_id = '" + scope.snapshotId()
                            + "' AND dt >= '" + scope.dtFrom() + "' AND dt <= '" + scope.dtTo()
                            + "'；禁止 JOIN/UNION/子查询/CTE/注释；函数仅限 "
                            + SqlPolicy.ALLOWED_FUNCTIONS
                            + "\nSchema：" + catalog.schemaJson(tables)
                            + "\n请只输出修正后的 JSON（含 sql 字段）。";
                    long repairStart = System.currentTimeMillis();
                    String repairContent = llmProvider.complete(new AiRequest(
                            buildSystemPrompt(scope), repairPrompt, 0)).content();
                    logCall("sql_repair", providerUsed, System.currentTimeMillis() - repairStart, "OK", null);
                    JsonNode repaired = JsonExtractor.extractJson(repairContent);
                    sql = repaired.path("sql").asText(null);
                    v = validator.validate(sql, scope);
                    if (!v.ok()) {
                        throw new AiSqlException(v.code(), "修复后仍未通过安全校验: " + v.error());
                    }
                    status = "REPAIRED";
                } else {
                    status = "SAFE";
                }
                sql = v.sql(); // 已重写 LIMIT
            } else {
                // ── 规则回退（§3.5.5）：模板同样带快照/日期字面量 ───────────────
                stage = "FALLBACK";
                RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve(question, tables, scope);
                if (!fb.ok()) {
                    throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT, fb.error());
                }
                sql = fb.sql();
                assumptions = fb.assumptions();
                // 回退模板也必须过同一套校验（模板漂移不能变成后门）
                ValidationResult v = validator.validate(sql, scope);
                if (!v.ok()) {
                    throw new AiSqlException(v.code(), "规则回退模板未通过安全校验: " + v.error());
                }
                sql = v.sql();
                status = "GENERATED";
            }

            // ── 4. EXPLAIN 成本校验（通过校验器之后、真正执行之前） ─────────────
            stage = "EXPLAIN";
            QueryCostGuard.CostEstimate cost = costGuard.check(sql);
            explainRows = cost.estimatedScanRows();

            // ── 5. 只读执行（metric_read + 30s + 200 行） ─────────────────────
            stage = "EXECUTE";
            SqlExecutor.ExecutionResult exec = executor.execute(sql);
            rows = exec.rows();
            rowsReturned = rows.size();
            if (!status.equals("REPAIRED")) {
                status = "EXECUTED";
            }
        } catch (AiSqlException e) {
            errorCode = e.code();
            error = truncate(e.getMessage(), 500);
            // 作用域缺失 = 系统不具备问数条件（FAILED）；SQL 被规则拒绝 = REJECTED（契约 §2.3 审计）
            boolean scopeFailure = SqlPolicy.NO_ACTIVE_SNAPSHOT.equals(e.code())
                    || SqlPolicy.METRIC_READ_SOURCE_MISSING.equals(e.code());
            if (scopeFailure) {
                status = "FAILED";
            } else {
                status = STATUS_REJECTED;
            }
            log.warn("text-to-sql rejected at stage {}: [{}] {}", stage, errorCode, error);
        } catch (Exception e) {
            error = truncate(e.getMessage(), 500);
            status = "FAILED";
            log.warn("text-to-sql failed: {}", error);
        } finally {
            saveHistory(userId, question, sql, tables, scope, status, rowsReturned,
                    System.currentTimeMillis() - start, error, errorCode, explainRows);
        }
        return new QueryResult(status, sql, tables, rowsReturned,
                System.currentTimeMillis() - start, rows, assumptions, error, errorCode, providerUsed);
    }

    // ── Prompt 构建（§19.5 安全规则内嵌；真实日期/快照作为字面量下发） ─────────

    String buildSystemPrompt(AiScope scope) {
        return """
                你是指标查询助手。只能生成**单表只读 SELECT**，必须遵守：
                1. 只使用我给出的表和字段，禁止编造；禁止 SELECT *，所有列必须显式写出；
                2. WHERE 必须同时含两个字面量条件：
                   snapshot_id = '%s'（当前 ACTIVE 快照）与
                   dt >= '%s' AND dt <= '%s'（紧凑 yyyyMMdd，如 20260901；允许区间最长 90 天）；
                   禁止用 (SELECT MAX(dt) ...) / (SELECT MAX(snapshot_id) ...) 等子查询取日期或快照；
                   禁止 CURDATE()/DATE_SUB()/NOW() 等"当前时间"函数，日期一律写字面量；
                3. 禁止 JOIN / UNION / 子查询 / CTE / 窗口函数 / 多语句 / 注释 / DDL / DML；
                4. 函数只允许：%s；
                5. 问题没给时间范围时，用区间右端作为业务日并取最近 7 天，并在 assumptions 注明；
                6. 结果行数上限 %d（LIMIT 可小于该值，超过会被改写）；
                7. 金额字段来自表定义，不可对客单价直接 AVG；
                8. 只输出 JSON：{"intent","metrics","sql","assumptions"}；不确定时返回 clarification_needed。
                """.formatted(scope.snapshotId(), scope.dtFrom(), scope.dtTo(),
                String.join(",", SqlPolicy.ALLOWED_FUNCTIONS), scope.rowLimit());
    }

    private String buildUserQuestion(String question, List<String> tables, AiScope scope) {
        return "问题：" + question
                + "\n当前 ACTIVE 快照：" + scope.snapshotId()
                + "（口径版本 " + scope.definitionVersion() + "，业务日（dt 字面量格式） " + scope.dtTo() + "）"
                + "\n允许查询日期区间（含端点，共 " + scope.maxScanDays() + " 天）："
                + scope.dtFrom() + " ~ " + scope.dtTo()
                + "\n可用 Schema：" + catalog.schemaJson(tables)
                + "\n少样本（已按当前快照/业务日参数化，可直接照抄结构）：\n" + catalog.fewShots(tables, scope);
    }

    // ── 审计（§19.6/§24.8；拒绝也写一行） ─────────────────────────────────────

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

    /**
     * 写 {@code ai_query_history}。
     *
     * <p>R8-2 契约 §2.3：快照锚点与作用域、EXPLAIN 估算行数、行数上限写入**真实列**
     * （{@code snapshot_id / scope_min_date / scope_max_date / explain_rows / scope_row_limit}，
     * 由 R8-3 的 {@code V14__r8_identity_decision.sql} 第 4 段纯增量 ALTER 提供）；
     * {@code errors} 只保留真正的错误文本（脱敏 + 截断），不再拼接结构化字段。<br>
     * 被拒绝的查询同样落一行（{@code status=REJECTED}），否则「谁在试探校验器」在库里看不见。</p>
     */
    private void saveHistory(String userId, String question, String sql, List<String> tables, AiScope scope,
                             String status, int rowsReturned, long elapsedMs, String error,
                             String errorCode, Long explainRows) {
        AiQueryHistory h = new AiQueryHistory();
        h.setUserId(userId);
        h.setQuestion(question);
        h.setSqlText(sql);
        h.setTables(String.join(",", tables));
        // scope 为 null 说明连 ACTIVE 快照都没解析出来（NO_ACTIVE_SNAPSHOT/只读源缺失）
        h.setSnapshotId(scope == null ? null : scope.snapshotId());
        h.setScopeMinDate(scope == null ? null : scope.minAllowedDate());
        h.setScopeMaxDate(scope == null ? null : scope.businessDate());
        h.setScopeRowLimit(scope == null ? null : scope.rowLimit());
        h.setExplainRows(explainRows);
        h.setStatus(status);
        h.setRowsReturned(rowsReturned);
        h.setElapsedMs(elapsedMs);
        h.setErrors(errorText(error, errorCode));
        h.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(h);
    }

    /** errors 列：规则码 + 说明（脱敏后 ≤200 字符） */
    private static String errorText(String error, String errorCode) {
        StringBuilder sb = new StringBuilder();
        if (errorCode != null) {
            sb.append('[').append(errorCode).append(']');
        }
        if (error != null && !error.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(error);
        }
        return sb.length() == 0 ? null : truncate(desensitize(sb.toString()), 200);
    }

    /** 审计脱敏：SQL 字面量/疑似口令类内容不进错误列（§21.4 审计禁止落敏感值） */
    private static String desensitize(String s) {
        return s.replaceAll("'[^']*'", "'***'");
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
