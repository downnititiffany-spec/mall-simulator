package com.graduation.analytics.ai.sql;

import com.graduation.analytics.ai.SemanticCatalog;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器（R8-2 契约 §2.3，全树白名单校验）。
 *
 * <p>四层防护：① 字符串预检（多语句/注释/DDL-DML/危险关键字）② JSqlParser **AST 全树**判定
 * （单 SELECT、禁 JOIN/UNION/子查询/CTE/窗口、{@code SELECT *}）③ 列/函数 + 表**递归白名单**
 * ④ 快照钉住 + 日期字面量范围 + LIMIT 重写。</p>
 *
 * <p>与旧实现的差别（反熵）：<br>
 * ① 旧版只对 {@code SELECT} 列表里的**裸列**做白名单，{@code WHERE}/{@code ORDER BY}/函数实参里的列
 * 全部漏检（{@code WHERE 1=1 OR secret_col LIKE ...} 可过）；现在递归遍历
 * SELECT/WHERE/GROUP BY/ORDER BY/HAVING/函数实参中的每个 {@link Column}。<br>
 * ② 旧版允许「最多 3 表」并放行 {@code LIMIT 1000}；现在第一阶段强制**单表**，LIMIT 上限 200。<br>
 * ③ 旧版用字符串 {@code contains("dt")} 判日期条件（{@code 'dt' 出现在注释/别名里即通过}）；
 * 现在解析出真正的 {@code dt} 字面量区间并核对 {@link AiScope} 的
 * {@code [minAllowedDate, businessDate]} 与 90 天扫描上限，同时核对 {@code snapshot_id = '<ACTIVE>'}。</p>
 */
@Component
@RequiredArgsConstructor
public class SqlSafetyValidator {

    private final SemanticCatalog catalog;

    /** 缺省/上限行数（契约 §2.3：LIMIT 缺省补 200、>200 改写为 200） */
    public static final int DEFAULT_LIMIT = SqlPolicy.LIMIT_CAP;
    public static final int HARD_LIMIT = SqlPolicy.LIMIT_CAP;

    // ── ① 字符串预检 ────────────────────────────────────────────────────────
    /** 注释：{@code --} 与 {@code #} 行注释、块注释（可作逃逸载体，一律禁） */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)/\\*|\\*/|--|#");
    /** DDL/DML/存储过程/会话设置关键字（语句开头独立成词，避免误伤列名） */
    private static final Pattern DDL_DML_PATTERN = Pattern.compile(
            "(?is)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|REPLACE|MERGE|GRANT|REVOKE|CALL|EXEC|EXECUTE"
                    + "|SET|USE|LOAD|OUTFILE|DUMPFILE|INTO|RENAME|HANDLER|LOCK|UNLOCK|PREPARE|DEALLOCATE)\\b");
    /** 显式危险函数（不在白名单之外还额外点名，便于审计快速定位） */
    private static final Pattern DANGEROUS_FUNCTION_PATTERN = Pattern.compile(
            "(?is)\\b(SLEEP|BENCHMARK|LOAD_FILE|SYS_EXEC|XP_CMDSHELL|GET_LOCK|UUID_SHORT)\\s*\\(");
    /** LIMIT 重写：命中开头，保留 {@code ,offset} 与结尾分号 */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?is)^\\s*LIMIT\\s+(\\d+)(\\s*,\\s*\\d+)?(\\s*;)?\\s*$");

    /**
     * 校验结果。{@code code} 为稳定规则码（写入 {@code ai_query_history.errors}）；
     * {@code ok=false} 时 {@code sql} 为 null。
     */
    public record ValidationResult(boolean ok, String code, String error, String sql) {

        public static ValidationResult pass(String sql) {
            return new ValidationResult(true, null, null, sql);
        }

        public static ValidationResult fail(String code, String error) {
            return new ValidationResult(false, code, error, null);
        }

        public String message() {
            return ok ? "OK" : "[" + code + "] " + error;
        }
    }

    /** 契约 §2.3 入口：校验并返回可执行 SQL（LIMIT 已重写） */
    public ValidationResult validate(String rawSql, AiScope scope) {
        if (scope == null) {
            return ValidationResult.fail(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "缺少 AiScope：必须先用 AiScopeResolver 取 ACTIVE 快照，禁止无快照校验/执行");
        }
        if (rawSql == null || rawSql.isBlank()) {
            return ValidationResult.fail(SqlPolicy.SQL_EMPTY, "SQL 为空");
        }
        String sql = rawSql.trim();

        try {
            // ── ① 字符串预检 ────────────────────────────────────────────────
            checkPreScan(sql);
            // ── ② AST 解析与结构判定 ────────────────────────────────────────
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                return ValidationResult.fail(SqlPolicy.SQL_NOT_SELECT,
                        "只允许单条 SELECT，收到: " + statement.getClass().getSimpleName());
            }
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
                return ValidationResult.fail(SqlPolicy.SQL_CTE, "禁止 CTE（WITH 子句）");
            }
            if (select instanceof SetOperationList) {
                return ValidationResult.fail(SqlPolicy.SQL_UNION, "禁止 UNION / INTERSECT / EXCEPT 复合查询");
            }
            // 注意：JSqlParser 4.9 的 Select#getPlainSelect()/getSetOperationList() 在类型不符时
            // **直接抛 ClassCastException**（实测），因此这里必须用 instanceof 判定，不能调这两个 getter
            if (!(select instanceof PlainSelect)) {
                return ValidationResult.fail(SqlPolicy.SQL_SUBQUERY,
                        "只允许简单单表 SELECT，收到: " + select.getClass().getSimpleName());
            }
            PlainSelect plain = (PlainSelect) select;
            if (plain.getJoins() != null && !plain.getJoins().isEmpty()) {
                return ValidationResult.fail(SqlPolicy.SQL_JOIN, "禁止 JOIN（第一阶段强制单表查询）");
            }
            if (plain.getWindowDefinitions() != null && !plain.getWindowDefinitions().isEmpty()) {
                return ValidationResult.fail(SqlPolicy.SQL_WINDOW, "禁止窗口定义（WINDOW 子句）");
            }

            // ── ③ 单表 + FROM 判定 ─────────────────────────────────────────
            String table = checkFromTable(plain);

            // ── ③ 列 / 函数递归白名单（全树） ───────────────────────────────
            List<Target> targets = new ArrayList<>();
            checkSelectItems(plain, targets);
            collectFromWhere(plain.getWhere(), targets);
            collectFromGroupBy(plain, targets);
            checkOrderBy(plain, targets);
            collectFromHaving(plain.getHaving(), targets);
            checkTargets(table, targets);

            // ── ④ 快照钉住 + 日期字面量范围 ─────────────────────────────────
            Expression where = plain.getWhere();
            if (where == null) {
                return ValidationResult.fail(SqlPolicy.SQL_DATE_MISSING,
                        "缺少时间条件：WHERE 必须含 dt >= '<起始>' AND dt <= '<结束>' 字面量范围");
            }
            DateRange range = new DateRange();
            String snapshotId = evalWhere(where, range);
            if (snapshotId == null) {
                return ValidationResult.fail(SqlPolicy.SQL_SNAPSHOT_MISSING,
                        "WHERE 必须含 snapshot_id = '" + scope.snapshotId() + "' 字面量（禁止子查询取快照）");
            }
            if (!snapshotId.equals(scope.snapshotId())) {
                return ValidationResult.fail(SqlPolicy.SQL_SNAPSHOT_MISMATCH,
                        "快照必须钉住 ACTIVE 快照 " + scope.snapshotId() + "，实际为 " + snapshotId);
            }
            if (range.min == null || range.max == null) {
                return ValidationResult.fail(SqlPolicy.SQL_DATE_MISSING,
                        "WHERE 必须含 dt >= '<起始>' AND dt <= '<结束>' 字面量范围（不接受 dt = 单点或缺失）");
            }
            long span = range.max.toEpochDay() - range.min.toEpochDay() + 1;
            if (span > scope.maxScanDays()) {
                return ValidationResult.fail(SqlPolicy.SQL_DATE_RANGE_TOO_WIDE,
                        "扫描天数 " + span + " 天超过上限 " + scope.maxScanDays() + " 天");
            }
            if (!scope.businessDateInRange(range.min) || !scope.businessDateInRange(range.max)) {
                return ValidationResult.fail(SqlPolicy.SQL_DATE_OUT_OF_SCOPE,
                        "日期必须落在 [" + scope.minAllowedDate() + ", " + scope.businessDate() + "]，实际 ["
                                + range.min + ", " + range.max + "]");
            }

            // ── ④ LIMIT 强制重写 ───────────────────────────────────────────
            String rewritten = rewriteLimit(plain, sql, scope.rowLimit());
            return ValidationResult.pass(rewritten);
        } catch (AiSqlException e) {
            return ValidationResult.fail(e.code(), e.getMessage());
        } catch (JSQLParserException e) {
            return ValidationResult.fail(SqlPolicy.SQL_PARSE_ERROR, "SQL 无法解析: " + e.getMessage());
        } catch (Exception e) {
            // fail-closed：任何未预期异常都不放行
            return ValidationResult.fail(SqlPolicy.SQL_PARSE_ERROR,
                    "SQL 结构解析失败（fail-closed）: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ── ① 字符串预检 ────────────────────────────────────────────────────────

    private void checkPreScan(String sql) {
        if (COMMENT_PATTERN.matcher(sql).find()) {
            throw new AiSqlException(SqlPolicy.SQL_COMMENT, "禁止 SQL 注释（可作校验逃逸载体）");
        }
        // 多语句：第一个分号之后仍有内容（结尾单个分号允许，后续会被重写时去掉）
        int semi = sql.indexOf(';');
        if (semi >= 0 && !sql.substring(semi + 1).isBlank()) {
            throw new AiSqlException(SqlPolicy.SQL_MULTI_STATEMENT, "禁止多语句：分号后仍有语句");
        }
        String head = sql.toLowerCase(Locale.ROOT);
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new AiSqlException(SqlPolicy.SQL_NOT_SELECT, "只允许 SELECT 查询，语句必须以 SELECT 开头");
        }
        if (DDL_DML_PATTERN.matcher(sql).find()) {
            Matcher m = DDL_DML_PATTERN.matcher(sql);
            m.find();
            throw new AiSqlException(SqlPolicy.SQL_DDL_DML, "禁止 DDL/DML/会话语句关键字: " + m.group(1).toUpperCase(Locale.ROOT));
        }
        if (DANGEROUS_FUNCTION_PATTERN.matcher(sql).find()) {
            throw new AiSqlException(SqlPolicy.SQL_FUNCTION_NOT_ALLOWED, "禁止危险函数（SLEEP/BENCHMARK/LOAD_FILE 等）");
        }
        if (SqlPolicy.containsDeniedSchema(sql)) {
            throw new AiSqlException(SqlPolicy.SQL_TABLE_NOT_ALLOWED,
                    "禁止访问 information_schema / mysql / performance_schema / sys");
        }
        // 字符串里出现「)(SELECT」这类明显拼接痕迹一律拒绝（正常查询用不到）
        if (sql.toLowerCase(Locale.ROOT).matches("(?s).*\\)\\s*\\(\\s*select.*")) {
            throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "禁止子查询拼接写法");
        }
    }

    // ── ③ 单表 + FROM ───────────────────────────────────────────────────────

    private String checkFromTable(PlainSelect plain) {
        FromItem from = plain.getFromItem();
        if (from == null) {
            throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "缺少 FROM 子句");
        }
        if (from instanceof ParenthesedSelect
                || (!(from instanceof Table) && from.toString().contains("("))) {
            throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "禁止子查询作为 FROM 数据源");
        }
        if (!(from instanceof Table table)) {
            throw new AiSqlException(SqlPolicy.SQL_TABLE_NOT_ALLOWED,
                    "FROM 只允许白名单 ADS 表，收到: " + from.getClass().getSimpleName());
        }
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
            throw new AiSqlException(SqlPolicy.SQL_TABLE_NOT_ALLOWED,
                    "禁止限定库名（" + table.getSchemaName() + "）：只允许当前只读库内的已发布 ADS");
        }
        String name = table.getName() == null ? "" : table.getName().replace("`", "").toLowerCase(Locale.ROOT);
        if (!catalog.tableWhitelisted(name)) {
            throw new AiSqlException(SqlPolicy.SQL_TABLE_NOT_ALLOWED, "表不在已发布 ADS 白名单: " + name);
        }
        return name;
    }

    // ── ③ SELECT 列表 / 列 / 函数 ───────────────────────────────────────────

    private void checkSelectItems(PlainSelect plain, List<Target> targets) {
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.isEmpty()) {
            throw new AiSqlException(SqlPolicy.SQL_SELECT_STAR, "SELECT 列表为空");
        }
        for (SelectItem<?> item : items) {
            if (item == null) {
                continue;
            }
            String text = item.toString().toLowerCase(Locale.ROOT);
            if (text.contains("*")) {
                throw new AiSqlException(SqlPolicy.SQL_SELECT_STAR, "禁止 SELECT *：必须显式列出白名单列");
            }
            Expression expr = item.getExpression();
            if (expr == null) {
                continue;
            }
            if (expr instanceof Select || expr instanceof ParenthesedSelect) {
                throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "SELECT 列表禁止子查询");
            }
            collect(expr, targets);
        }
    }

    private void collectFromWhere(Expression where, List<Target> targets) {
        if (where != null) {
            collect(where, targets);
        }
    }

    private void collectFromGroupBy(PlainSelect plain, List<Target> targets) {
        if (plain.getGroupBy() == null) {
            return;
        }
        var list = plain.getGroupBy().getGroupByExpressionList();
        if (list == null || list.getExpressions() == null) {
            return;
        }
        for (Object e : list.getExpressions()) {
            if (e instanceof Expression expr) {
                collect(expr, targets);
            }
        }
    }

    private void checkOrderBy(PlainSelect plain, List<Target> targets) {
        if (plain.getOrderByElements() == null) {
            return;
        }
        for (OrderByElement o : plain.getOrderByElements()) {
            if (o == null || o.getExpression() == null) {
                continue;
            }
            Expression expr = o.getExpression();
            if (expr instanceof Select || expr instanceof ParenthesedSelect) {
                throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "ORDER BY 禁止子查询");
            }
            collect(expr, targets);
        }
    }

    private void collectFromHaving(Expression having, List<Target> targets) {
        if (having != null) {
            collect(having, targets);
        }
    }

    /** 递归收集表达式树里的列与函数（SELECT/WHERE/GROUP/ORDER/HAVING/函数实参全覆盖） */
    private void collect(Expression expr, List<Target> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Column c) {
            out.add(Target.column(c));
            return;
        }
        if (expr instanceof Function f) {
            out.add(Target.function(f.getName()));
            if (f.getParameters() != null && f.getParameters().getExpressions() != null) {
                for (Object e : f.getParameters().getExpressions()) {
                    if (e instanceof Expression inner) {
                        collect(inner, out);
                    }
                }
            }
            return;
        }
        if (expr instanceof Select || expr instanceof ParenthesedSelect) {
            throw new AiSqlException(SqlPolicy.SQL_SUBQUERY, "表达式内禁止子查询");
        }
        if (expr instanceof WindowDefinition) {
            throw new AiSqlException(SqlPolicy.SQL_WINDOW, "禁止窗口函数定义");
        }
        if (expr instanceof OrExpression or) {
            collect(or.getLeftExpression(), out);
            collect(or.getRightExpression(), out);
            return;
        }
        if (expr instanceof AndExpression and) {
            collect(and.getLeftExpression(), out);
            collect(and.getRightExpression(), out);
            return;
        }
        if (expr instanceof ComparisonOperator cmp) {
            collect(cmp.getLeftExpression(), out);
            collect(cmp.getRightExpression(), out);
            return;
        }
        if (expr instanceof Between between) {
            collect(between.getLeftExpression(), out);
            collect(between.getBetweenExpressionStart(), out);
            collect(between.getBetweenExpressionEnd(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
            collect(p.getExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.InExpression in) {
            collect(in.getLeftExpression(), out);
            if (in.getRightExpression() != null) {
                collect(in.getRightExpression(), out);
            }
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.IsNullExpression isNull) {
            collect(isNull.getLeftExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.LikeExpression like) {
            collect(like.getLeftExpression(), out);
            collect(like.getRightExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.NotExpression not) {
            collect(not.getExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.SignedExpression signed) {
            collect(signed.getExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.CaseExpression caseExpr) {
            collect(caseExpr.getSwitchExpression(), out);
            if (caseExpr.getWhenClauses() != null) {
                for (var w : caseExpr.getWhenClauses()) {
                    collect(w.getWhenExpression(), out);
                    collect(w.getThenExpression(), out);
                }
            }
            collect(caseExpr.getElseExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.CastExpression cast) {
            collect(cast.getLeftExpression(), out);
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.operators.relational.ExpressionList list) {
            if (list.getExpressions() != null) {
                for (Object e : list.getExpressions()) {
                    if (e instanceof Expression inner) {
                        collect(inner, out);
                    }
                }
            }
            return;
        }
        if (expr instanceof net.sf.jsqlparser.expression.AnalyticExpression analytic) {
            throw new AiSqlException(SqlPolicy.SQL_WINDOW, "禁止窗口函数（AnalyticExpression）");
        }
        // 其余字面量/参数节点不含列引用
    }

    private void checkTargets(String table, List<Target> targets) {
        for (Target t : targets) {
            if (t.functionName != null) {
                if (!SqlPolicy.functionAllowed(t.functionName)) {
                    throw new AiSqlException(SqlPolicy.SQL_FUNCTION_NOT_ALLOWED,
                            "函数不在白名单: " + t.functionName + "（允许: " + SqlPolicy.ALLOWED_FUNCTIONS + "）");
                }
                continue;
            }
            String column = t.column.toLowerCase(Locale.ROOT);
            if (!catalog.fieldExists(table, column)) {
                throw new AiSqlException(SqlPolicy.SQL_COLUMN_NOT_ALLOWED,
                        "字段不存在于 " + table + ": " + t.column);
            }
        }
    }

    private record Target(String column, String functionName) {
        static Target column(Column c) {
            return new Target(c.getColumnName(), null);
        }

        static Target function(String name) {
            return new Target(null, name);
        }
    }

    // ── ④ 快照钉住 + 日期字面量范围 ─────────────────────────────────────────

    /** 日期字面量区间（含端点），null 表示未出现 */
    private static final class DateRange {
        LocalDate min;
        LocalDate max;
    }

    /**
     * 遍历 WHERE 的 AND 合取项：核对 {@code snapshot_id} 字面量并抽取 dt 区间；
     * 同时保证 {@code dt} 只与**合法日期字面量**比较（不接受列/函数/非法格式）。
     *
     * @return WHERE 中出现的 snapshot_id 字面量（未出现返回 null）
     */
    private String evalWhere(Expression expr, DateRange range) {
        if (expr instanceof AndExpression and) {
            String left = evalWhere(and.getLeftExpression(), range);
            String right = evalWhere(and.getRightExpression(), range);
            return left != null ? left : right;
        }
        if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
            return evalWhere(p.getExpression(), range);
        }
        if (expr instanceof Between between) {
            if (between.isNot()) {
                return null;
            }
            return evalBetween(between, range);
        }
        if (expr instanceof ComparisonOperator cmp) {
            return evalComparison(cmp, range);
        }
        return null;
    }

    private String evalComparison(ComparisonOperator cmp, DateRange range) {
        boolean dtLeft = isDateColumn(cmp.getLeftExpression());
        boolean dtRight = isDateColumn(cmp.getRightExpression());
        if (dtLeft && dtRight) {
            throw new AiSqlException(SqlPolicy.SQL_DATE_MISSING, "dt 必须与日期字面量比较，不能与列比较");
        }
        Expression columnSide = dtLeft ? cmp.getLeftExpression() : dtRight ? cmp.getRightExpression() : null;
        Expression literalSide = dtLeft ? cmp.getRightExpression() : cmp.getLeftExpression();
        if (columnSide != null) {
            LocalDate date = requireDateLiteral(literalSide);
            String op = cmp.getStringExpression();
            boolean reversed = dtRight; // 字面量在左（'2026-09-01' <= dt）
            boolean min = reversed ? op.equals("<=") || op.equals("<") : op.equals(">=") || op.equals(">");
            boolean max = reversed ? op.equals(">=") || op.equals(">") : op.equals("<=") || op.equals("<");
            if (min && (range.min == null || date.isAfter(range.min))) {
                range.min = date;
            } else if (max && (range.max == null || date.isBefore(range.max))) {
                range.max = date;
            }
            return null;
        }
        // snapshot_id = '<ACTIVE>'
        if (isSnapshotColumn(cmp.getLeftExpression()) && cmp instanceof EqualsTo) {
            return literalText(cmp.getRightExpression());
        }
        if (isSnapshotColumn(cmp.getRightExpression()) && cmp instanceof EqualsTo) {
            return literalText(cmp.getLeftExpression());
        }
        return null;
    }

    private String evalBetween(Between between, DateRange range) {
        if (isSnapshotColumn(between.getLeftExpression())) {
            return null;
        }
        if (!isDateColumn(between.getLeftExpression())) {
            return null;
        }
        LocalDate start = requireDateLiteral(between.getBetweenExpressionStart());
        LocalDate end = requireDateLiteral(between.getBetweenExpressionEnd());
        if (range.min == null || start.isAfter(range.min)) {
            range.min = start;
        }
        if (range.max == null || end.isBefore(range.max)) {
            range.max = end;
        }
        return null;
    }

    private LocalDate requireDateLiteral(Expression expr) {
        String text = literalText(expr);
        if (text == null) {
            throw new AiSqlException(SqlPolicy.SQL_DATE_MISSING,
                    "dt 只允许与 yyyyMMdd 日期字面量比较，禁止列/函数/子查询: " + expr);
        }
        LocalDate date = parseStrictDate(text);
        if (date == null) {
            throw new AiSqlException(SqlPolicy.SQL_DATE_OUT_OF_SCOPE,
                    "日期字面量格式必须为 yyyyMMdd（ADS 落库格式，如 20260901），收到: " + text);
        }
        return date;
    }

    /**
     * 严格日期解析：只接受 ADS 落库的紧凑 {@code yyyyMMdd}（列是 VARCHAR 存紧凑值）。
     *
     * <p>2026-09-11 真机事故后收紧：原先接受 ISO {@code yyyy-MM-dd}，与真实列格式不符，
     * 生成出的 SQL 字符串比较恒 false → 静默 0 行。**宁可拒绝（loud）也不放行（silent empty）**。</p>
     */
    static LocalDate parseStrictDate(String text) {
        if (text == null || !text.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(text, AiScope.DT_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean isDateColumn(Expression expr) {
        return expr instanceof Column c && SqlPolicy.isDateColumn(c.getColumnName());
    }

    private boolean isSnapshotColumn(Expression expr) {
        return expr instanceof Column c && "snapshot_id".equalsIgnoreCase(c.getColumnName());
    }

    /** 取字面量文本：字符串/日期字面量，以及 {@code DATE('...')}/{@code CAST('...' AS DATE)} 包装 */
    private String literalText(Expression expr) {
        if (expr instanceof DateValue date) {
            return date.getValue() == null ? null : date.getValue().toString();
        }
        if (expr instanceof StringValue sv) {
            return sv.getValue();
        }
        if (expr instanceof Function fn && fn.getParameters() != null
                && fn.getParameters().getExpressions() != null
                && fn.getParameters().getExpressions().size() == 1
                && fn.getParameters().getExpressions().get(0) instanceof StringValue sv) {
            String name = SqlPolicy.normalizeFunction(fn.getName());
            if ("DATE".equals(name) || "CAST".equals(name)) {
                return sv.getValue();
            }
        }
        return null;
    }

    // ── ④ LIMIT 强制重写 ───────────────────────────────────────────────────

    /**
     * 契约 §2.3：无 LIMIT → 补 {@code LIMIT rowLimit}；LIMIT &gt; rowLimit → 改写为 rowLimit。
     * 小于等于上限的显式 LIMIT **保留原样**（契约只要求「>上限改写」，把 10 改成 200 会改变语义）。
     */
    private String rewriteLimit(PlainSelect plain, String sql, int rowLimit) {
        int cap = rowLimit > 0 ? rowLimit : DEFAULT_LIMIT;
        Long existing = null;
        if (plain.getLimit() != null && plain.getLimit().getRowCount() instanceof LongValue lv) {
            existing = lv.getValue();
        }
        String body = sql.trim();
        while (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        int index = body.toUpperCase(Locale.ROOT).lastIndexOf("LIMIT");
        if (existing != null && index > 0 && body.substring(index).matches("(?is)^LIMIT\\s+\\d+\\s*,\\s*\\d+\\s*$")) {
            // MySQL 双参写法 LIMIT offset,rowcount：rowcount 是行数，offset 不影响扫描成本
            return body;
        }
        if (existing != null && index > 0) {
            String tail = body.substring(index);
            Matcher m = LIMIT_PATTERN.matcher(tail);
            if (m.matches()) {
                long effective = existing;
                String number = String.valueOf(Math.min(Math.max(effective, 0), cap));
                return body.substring(0, index) + "LIMIT " + number + (m.group(2) == null ? "" : m.group(2));
            }
        }
        if (existing == null) {
            return body + " LIMIT " + cap;
        }
        return body;
    }
}
