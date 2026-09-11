package com.graduation.analytics.ai.sql;

import com.graduation.analytics.ai.SemanticCatalog;

import java.util.Locale;
import java.util.Set;

/**
 * AI 问数安全策略（规则表唯一事实来源）：契约 §2.3 表格的**机器可读**形式。
 *
 * <p>JSqlParser AST 负责结构判定，本类负责「什么算允许」的取值集合；两者配合后，
 * {@link SqlSafetyValidator} 只做遍历与判定，不再散落魔法字符串，
 * {@link com.graduation.analytics.ai.TextToSqlService} 的提示词也从这里取函数名单，
 * 保证「提示词说的」和「校验器认的」永远一致（§19.5 末段：few-shot 必须与校验规则一致）。</p>
 */
public final class SqlPolicy {

    private SqlPolicy() {
    }

    // ── 结构规则错误码（写入 ai_query_history.errors） ──────────────────────
    public static final String SQL_EMPTY = "SQL_EMPTY";
    public static final String SQL_PARSE_ERROR = "SQL_PARSE_ERROR";
    public static final String SQL_NOT_SELECT = "SQL_NOT_SELECT";
    public static final String SQL_MULTI_STATEMENT = "SQL_MULTI_STATEMENT";
    public static final String SQL_COMMENT = "SQL_COMMENT";
    public static final String SQL_DDL_DML = "SQL_DDL_DML";
    public static final String SQL_UNION = "SQL_UNION";
    public static final String SQL_JOIN = "SQL_JOIN";
    public static final String SQL_SUBQUERY = "SQL_SUBQUERY";
    public static final String SQL_CTE = "SQL_CTE";
    public static final String SQL_WINDOW = "SQL_WINDOW";
    public static final String SQL_SELECT_STAR = "SQL_SELECT_STAR";
    public static final String SQL_TABLE_NOT_ALLOWED = "SQL_TABLE_NOT_ALLOWED";
    public static final String SQL_COLUMN_NOT_ALLOWED = "SQL_COLUMN_NOT_ALLOWED";
    public static final String SQL_FUNCTION_NOT_ALLOWED = "SQL_FUNCTION_NOT_ALLOWED";
    public static final String SQL_DATE_MISSING = "SQL_DATE_MISSING";
    public static final String SQL_DATE_RANGE_TOO_WIDE = "SQL_DATE_RANGE_TOO_WIDE";
    public static final String SQL_DATE_OUT_OF_SCOPE = "SQL_DATE_OUT_OF_SCOPE";
    public static final String SQL_SNAPSHOT_MISSING = "SQL_SNAPSHOT_MISSING";
    public static final String SQL_SNAPSHOT_MISMATCH = "SQL_SNAPSHOT_MISMATCH";

    /**
     * 问句层注入筛错误码（§19.5 纵深防御）：问句本身含写操作/结构变更/SQL 片段时，
     * 在生成之前就拒绝，避免「规则回退把攻击问句静默答成无害查询」。
     * 判定实现见 {@link com.graduation.analytics.ai.QuestionSafetyScreen}。
     */
    public static final String SQL_QUESTION_UNSAFE = "SQL_QUESTION_UNSAFE";

    // ── 成本 / 作用域错误码（契约 §2.2、§2.3） ──────────────────────────────
    public static final String NO_ACTIVE_SNAPSHOT = "NO_ACTIVE_SNAPSHOT";
    public static final String METRIC_READ_SOURCE_MISSING = "METRIC_READ_SOURCE_MISSING";
    public static final String SQL_COST_TOO_HIGH = "SQL_COST_TOO_HIGH";

    /** LIMIT 上限（契约 §2.3：rowLimit=200）；缺省补 200，>200 改写为 200 */
    public static final int LIMIT_CAP = AiScope.DEFAULT_ROW_LIMIT;

    /** dt 范围含端点时的最大天数（契约 §2.3：扫描 ≤90 天） */
    public static final int MAX_SCAN_DAYS = AiScope.DEFAULT_MAX_SCAN_DAYS;

    /** §19.2「AI 不重新计算数字」：只读单表 ADS */
    public static final String READ_ONLY_ACCOUNT = "metric_read";

    /**
     * 函数白名单（契约 §2.3，大小写无关）。
     * 注意这是**封闭白名单**：不在表内一律拒绝，包括 {@code DATE_SUB}/{@code CURDATE}/{@code FIELD}/
     * {@code SLEEP}/{@code BENCHMARK}/{@code LOAD_FILE} —— 日期一律用字面量，不用「当前时间」函数。
     */
    public static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "SUM", "AVG", "COUNT", "MIN", "MAX", "ROUND", "ABS",
            "COALESCE", "IFNULL", "FLOOR", "CEIL", "DATE", "CAST");

    /**
     * 即使未来有人误把危险函数加进白名单也必须拒绝的清单（防御性，不参与正常判定）。
     */
    public static final Set<String> DENIED_FUNCTIONS = Set.of(
            "SLEEP", "BENCHMARK", "LOAD_FILE", "SYS_EXEC", "XP_CMDSHELL", "GET_LOCK", "UUID_SHORT");

    /** 禁止出现子查询/CTE 的写法特征（字符串层兜底；AST 层另有判定） */
    public static final Set<String> DENIED_SCHEMAS = Set.of("INFORMATION_SCHEMA", "MYSQL", "PERFORMANCE_SCHEMA", "SYS");

    public static boolean functionAllowed(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        String normalized = normalizeFunction(functionName);
        return ALLOWED_FUNCTIONS.contains(normalized) && !DENIED_FUNCTIONS.contains(normalized);
    }

    /** 函数名归一化：去下划线/空格后大写（{@code DATE_SUB} → {@code DATESUB}，与白名单不同名即拒绝） */
    public static String normalizeFunction(String functionName) {
        return functionName == null ? "" : functionName.replace("_", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /** 列是否为日期列（字面量格式与范围校验据此选择；日期列集合登记在 {@link SemanticCatalog}） */
    public static boolean isDateColumn(String columnName) {
        return columnName != null && SemanticCatalog.DATE_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT));
    }

    public static boolean containsDeniedSchema(String sql) {
        String lower = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        return lower.contains("information_schema") || lower.contains("performance_schema")
                || lower.contains("mysql.") || lower.contains("sys.");
    }
}
