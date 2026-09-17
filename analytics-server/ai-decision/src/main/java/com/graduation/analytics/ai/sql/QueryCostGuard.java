package com.graduation.analytics.ai.sql;

import com.graduation.analytics.common.PlatformBizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EXPLAIN 成本守卫（R8-2 契约 §2.3）：通过校验器后、真正执行前，用 {@code EXPLAIN <sql>}
 * 看 MySQL 预估要扫多少行，超过 {@code ai.sql.max-explain-rows}（默认 500000）直接拒绝
 * {@code SQL_COST_TOO_HIGH}。
 *
 * <p><b>行数口径（尽力说明清楚）</b>：<br>
 * ① 取 EXPLAIN 输出的 {@code rows} 列（MySQL 对每张表的预估扫描行数）；<br>
 * ② 若计划中出现的表**多于一张**（理论上被校验器挡住，这里是纵深防御）→ 取各行 {@code rows} 的**和**；<br>
 * ③ 只有一张表但计划有多行（例如 type=ALL + 附加计划行）→ 取各行 {@code rows} 的**乘积**
 * （最坏情况，偏保守）；<br>
 * ④ 任一行缺 {@code rows} 列/值为 null/解析失败 → **拒绝**（fail-closed），绝不当作 0 放行。</p>
 *
 * <p>与 {@link SqlExecutor} 同源：JdbcTemplate 由 {@code SqlExecutor#effectiveDataSource()}
 * （即 metricReadDataSource）构建，只读源缺失时构造即失败，不存在"换个源做 EXPLAIN"的路径。</p>
 *
 * <p>S3-58：EXPLAIN 本身超时是“查询超时”，不是“成本过高”。因此 Spring
 * {@link QueryTimeoutException} 单独映射已有平台稳定码 {@code QUERY_TIMEOUT}；其它 EXPLAIN 故障仍按
 * fail-closed 的 {@code SQL_COST_TOO_HIGH} 处理，避免把未知计划错误放行。</p>
 */
@Slf4j
@Component
public class QueryCostGuard {

    /** 契约 §2.3 默认阈值 */
    public static final long DEFAULT_MAX_EXPLAIN_ROWS = 500_000L;

    private final SqlExecutor sqlExecutor;
    private final JdbcTemplate metricReadJdbcTemplate;
    private final long maxExplainRows;

    @Autowired
    public QueryCostGuard(SqlExecutor sqlExecutor,
                          @Value("${ai.sql.max-explain-rows:500000}") long maxExplainRows) {
        this.sqlExecutor = sqlExecutor;
        this.maxExplainRows = maxExplainRows <= 0 ? DEFAULT_MAX_EXPLAIN_ROWS : maxExplainRows;
        this.metricReadJdbcTemplate = buildTemplate(sqlExecutor);
    }

    /** 测试/装配用构造：直接给定只读 JdbcTemplate（生产路径复用 {@code SqlExecutor} 的只读源） */
    public QueryCostGuard(SqlExecutor sqlExecutor, JdbcTemplate metricReadJdbcTemplate, long maxExplainRows) {
        this.sqlExecutor = sqlExecutor;
        this.metricReadJdbcTemplate = metricReadJdbcTemplate;
        this.maxExplainRows = maxExplainRows <= 0 ? DEFAULT_MAX_EXPLAIN_ROWS : maxExplainRows;
    }

    private static JdbcTemplate buildTemplate(SqlExecutor executor) {
        if (executor == null) {
            throw new IllegalStateException(
                    "QueryCostGuard 依赖 SqlExecutor：EXPLAIN 必须复用 metricReadDataSource（§17.1 禁止回退 meta）");
        }
        // effectiveDataSource() 在只读源缺失时抛异常 → 成本守卫根本装配不起来，属于期望的 fail-closed
        DataSource ds = executor.effectiveDataSource();
        return new JdbcTemplate(ds);
    }

    public record CostEstimate(long estimatedScanRows, int planRows) {
    }

    /**
     * EXPLAIN 并校验成本。
     *
     * @throws AiSqlException {@code SQL_COST_TOO_HIGH} 成本超阈值、普通 EXPLAIN 失败或计划无法解析；
     *                        {@code QUERY_TIMEOUT} 表示 EXPLAIN 到点中止。
     */
    public CostEstimate check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new AiSqlException(SqlPolicy.SQL_EMPTY, "EXPLAIN 成本校验收到空 SQL");
        }
        List<Map<String, Object>> plan;
        try {
            plan = metricReadJdbcTemplate.queryForList("EXPLAIN " + sql);
        } catch (QueryTimeoutException e) {
            throw new AiSqlException(PlatformBizException.QUERY_TIMEOUT,
                    "EXPLAIN 超过统一查询超时，按 fail-closed 中止", e);
        } catch (RuntimeException e) {
            // fail-closed：非超时 EXPLAIN 失败绝不放行（宁可不答复，也不冒全表扫描风险）
            throw new AiSqlException(SqlPolicy.SQL_COST_TOO_HIGH,
                    "EXPLAIN 执行失败，按 fail-closed 拒绝: " + e.getMessage(), e);
        }
        if (plan == null || plan.isEmpty()) {
            throw new AiSqlException(SqlPolicy.SQL_COST_TOO_HIGH, "EXPLAIN 未返回任何计划行，按 fail-closed 拒绝");
        }
        Set<String> tables = new LinkedHashSet<>();
        long product = 1L;
        long sum = 0L;
        for (Map<String, Object> row : plan) {
            Object table = row.get("table");
            if (table != null) {
                tables.add(String.valueOf(table));
            }
            Long rows = toLong(row.get("rows"));
            if (rows == null) {
                throw new AiSqlException(SqlPolicy.SQL_COST_TOO_HIGH,
                        "EXPLAIN 计划缺少 rows 列或值不可解析，按 fail-closed 拒绝: " + row);
            }
            if (rows < 0) {
                throw new AiSqlException(SqlPolicy.SQL_COST_TOO_HIGH, "EXPLAIN rows 出现负值，按 fail-closed 拒绝");
            }
            sum += rows;
            product = product > Long.MAX_VALUE / Math.max(rows, 1L)
                    ? Long.MAX_VALUE : product * Math.max(rows, 1L);
        }
        long estimated = tables.size() > 1 ? sum : product;
        if (estimated > maxExplainRows) {
            throw new AiSqlException(SqlPolicy.SQL_COST_TOO_HIGH,
                    "EXPLAIN 预估扫描 " + estimated + " 行，超过阈值 " + maxExplainRows
                            + "（表=" + tables + "，计划行数=" + plan.size() + "）");
        }
        log.debug("EXPLAIN 成本通过: estimated={} rows, planRows={}, tables={}", estimated, plan.size(), tables);
        return new CostEstimate(estimated, plan.size());
    }

    public long maxExplainRows() {
        return maxExplainRows;
    }

    static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 供日志/审计使用的计划摘要 */
    static List<String> summarize(List<Map<String, Object>> plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) {
            return out;
        }
        for (Map<String, Object> row : plan) {
            out.add("table=" + row.get("table") + ",type=" + row.get("type") + ",rows=" + row.get("rows"));
        }
        return out;
    }
}
