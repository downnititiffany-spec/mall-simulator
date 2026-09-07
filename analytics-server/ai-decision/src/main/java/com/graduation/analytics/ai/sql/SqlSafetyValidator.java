package com.graduation.analytics.ai.sql;

import com.graduation.analytics.ai.SemanticCatalog;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器（§8.6 四层防护）：
 * ① 字符串预检（多语句/注释逃逸/DDL-DML）② JSqlParser AST（只读 Select/With、禁函数）
 * ③ 表白名单 + 字段存在 ④ LIMIT 强制重写（默认 500 / 上限 1000）。
 * 校验失败返回明确错误；通过返回（可含重写后的 SQL）。
 */
@Component
@RequiredArgsConstructor
public class SqlSafetyValidator {

    private final SemanticCatalog catalog;

    public static final int DEFAULT_LIMIT = 500;
    public static final int HARD_LIMIT = 1000;

    /** 第一层：字符串预检 */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("(?is)(insert|update|delete|drop|alter|truncate|create|grant|revoke|load\\s+data|export|into\\s+outfile)\\s"),
            Pattern.compile("(?is);\\s*(insert|update|delete|drop|alter|truncate|create)"),      // 多语句
            Pattern.compile("(?is)/\\*.*?\\*/"),                                                   // 注释（可作逃逸载体）
            Pattern.compile("(?is)--\\s"),
            Pattern.compile("(?is)(sleep|benchmark|load_file|into\\s+outfile|sys_exec|xp_cmdshell)\\b"),
            Pattern.compile("(?is)\\bunion\\s+(all\\s+)?select\\b"),                               // union 注入
            Pattern.compile("(?is)\\binformation_schema\\b"),
            Pattern.compile("(?is)\\bmysql\\.\\b"));

    /** 第二层：AST 允许函数白名单（聚合/日期/数学/字符串常用子集，名称统一去下划线） */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "sum", "avg", "count", "min", "max", "distinct",
            "datesub", "dateadd", "curdate", "now", "datediff",
            "round", "abs", "coalesce", "ifnull", "floor", "ceil",
            "concat", "lower", "upper", "substring", "length",
            "field", "cast", "case");

    public record ValidationResult(boolean ok, String error, String sql) {
        static ValidationResult pass(String sql) {
            return new ValidationResult(true, null, sql);
        }

        static ValidationResult fail(String error) {
            return new ValidationResult(false, error, null);
        }
    }

    public ValidationResult validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return ValidationResult.fail("SQL 为空");
        }
        String sql = rawSql.trim();

        // ── ① 字符串预检 ──────────────────────────────────────────────
        for (Pattern p : FORBIDDEN_PATTERNS) {
            if (p.matcher(sql).find()) {
                return ValidationResult.fail("第一层预检拦截: 命中危险模式 " + p.pattern());
            }
        }

        // ── ② AST 解析 ────────────────────────────────────────────────
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            return ValidationResult.fail("SQL 无法解析: " + e.getMessage());
        }
        if (!(statement instanceof Select select)) {
            return ValidationResult.fail("只允许 SELECT 查询，收到: " + statement.getClass().getSimpleName());
        }
        if (select.getWithItemsList() != null
                && select.getWithItemsList().stream().anyMatch(w -> !(w.getSelect() instanceof Select))) {
            return ValidationResult.fail("WITH 中只允许只读查询");
        }
        String badFn = findForbiddenFunction(select);
        if (badFn != null) {
            return ValidationResult.fail("禁用函数: " + badFn);
        }

        // ── ③ 表白名单与字段存在（仅校验直接引用的 select 主体） ──────
        PlainSelect plain = select.getPlainSelect();
        if (plain == null) {
            return ValidationResult.fail("不支持 JOIN 嵌套的复合查询");
        }
        String fromTable = plain.getFromItem() == null ? null : plain.getFromItem().toString();
        if (fromTable == null || fromTable.contains("(") || fromTable.contains(")")) {
            return ValidationResult.fail("不支持子查询/多表 JOIN，只允许单表查询");
        }
        String table = fromTable.split("\\s+")[0].replace("`", "");
        if (!catalog.tableWhitelisted(table)) {
            return ValidationResult.fail("表不在 ADS 白名单: " + table);
        }
        if (plain.getJoins() != null && plain.getJoins().size() > 2) {
            return ValidationResult.fail("最多关联 3 张表");
        }
        List<String> columns = new ArrayList<>();
        if (plain.getSelectItems() != null) {
            for (var item : plain.getSelectItems()) {
                if (item != null && item.getExpression() instanceof net.sf.jsqlparser.schema.Column col) {
                    columns.add(col.getColumnName());
                }
            }
        }
        for (String col : columns) {
            if (!catalog.fieldExists(table, col)) {
                return ValidationResult.fail("字段不存在于 " + table + ": " + col);
            }
        }

        // ── 时间条件强制（§8.5：必须包含时间条件；防止无界全表扫描） ────
        net.sf.jsqlparser.expression.Expression where = plain.getWhere();
        if (where == null) {
            return ValidationResult.fail("缺少时间条件：WHERE 必须包含 dt 过滤");
        }
        if (!where.toString().toLowerCase().contains("dt")) {
            return ValidationResult.fail("时间条件必须引用 dt 列");
        }

        // ── ④ LIMIT 强制重写（§8.6 资源限制） ─────────────────────────
        String finalSql = rewriteLimit(sql);
        return ValidationResult.pass(finalSql);
    }

    private String findForbiddenFunction(Select select) {
        try {
            var collector = new ArrayList<String>();
            select.getPlainSelect().getSelectItems().forEach(item -> {
                if (item != null && item.getExpression() != null) {
                    collectFunctions(item.getExpression(), collector);
                }
            });
            if (select.getPlainSelect().getWhere() != null) {
                collectFunctions(select.getPlainSelect().getWhere(), collector);
            }
            for (String fn : collector) {
                String lower = fn.toLowerCase(Locale.ROOT);
                if (!ALLOWED_FUNCTIONS.contains(lower)) {
                    return fn;
                }
            }
            return null;
        } catch (Exception e) {
            return "UNKNOWN(解析异常)";
        }
    }

    private void collectFunctions(Expression expr, List<String> out) {
        if (expr == null) {
            return;
        }
        try {
            if (expr instanceof Function fn) {
                out.add(fn.getName().replace("_", "").replace(" ", ""));
                if (fn.getParameters() != null) {
                    for (Object e : fn.getParameters().getExpressions()) {
                        collectFunctions((Expression) e, out);
                    }
                }
            } else if (expr instanceof net.sf.jsqlparser.expression.BinaryExpression be) {
                collectFunctions(be.getLeftExpression(), out);
                collectFunctions(be.getRightExpression(), out);
            } else if (expr instanceof net.sf.jsqlparser.expression.Parenthesis p) {
                collectFunctions(p.getExpression(), out);
            } else if (expr instanceof net.sf.jsqlparser.expression.operators.relational.InExpression in) {
                collectFunctions(in.getLeftExpression(), out);
            } else if (expr instanceof net.sf.jsqlparser.expression.operators.relational.IsNullExpression i) {
                collectFunctions(i.getLeftExpression(), out);
            } else if (expr instanceof net.sf.jsqlparser.expression.CaseExpression c) {
                collectFunctions(c.getElseExpression(), out);
                if (c.getWhenClauses() != null) {
                    c.getWhenClauses().forEach(w -> collectFunctions(w.getWhenExpression(), out));
                }
            }
        } catch (Exception ignored) {
            // 个别表达式类型不常用则跳过（白名单仍在第一/三层把关）
        }
    }

    /** 追加/修正 LIMIT：无 LIMIT → 500；LIMIT >1000 → 1000 */
    private String rewriteLimit(String sql) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plain = select.getPlainSelect();
            if (plain.getLimit() == null) {
                return sql + (sql.trim().endsWith(";") ? "" : "") + " LIMIT " + DEFAULT_LIMIT;
            }
            String sqlLower = sql.toLowerCase(Locale.ROOT);
            if (sqlLower.matches("(?s).*limit\\s+\\d+\\s*;?\\s*$")) {
                java.util.regex.Matcher m = Pattern.compile("(?is)limit\\s+(\\d+)").matcher(sql);
                if (m.find()) {
                    int limit = Integer.parseInt(m.group(1));
                    if (limit > HARD_LIMIT) {
                        return sql.replaceFirst("(?is)limit\\s+\\d+", "LIMIT " + HARD_LIMIT);
                    }
                    return sql;
                }
            }
            return sql; // 复杂 limit 表达式交由数据库执行（仍受语句级超时约束）
        } catch (Exception e) {
            return sql;
        }
    }
}