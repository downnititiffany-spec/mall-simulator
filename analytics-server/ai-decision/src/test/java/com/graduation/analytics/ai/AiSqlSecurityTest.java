package com.graduation.analytics.ai;

import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.ai.sql.AiScopeResolver;
import com.graduation.analytics.ai.sql.AiSqlException;
import com.graduation.analytics.ai.sql.QueryCostGuard;
import com.graduation.analytics.ai.sql.SqlExecutor;
import com.graduation.analytics.ai.sql.SqlPolicy;
import com.graduation.analytics.ai.sql.SqlSafetyValidator;
import com.graduation.analytics.ai.sql.SqlSafetyValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R8-2 安全问数攻击集/越权集单测（契约 §2.4）。
 *
 * <p>不依赖 Spring 容器与数据库：{@link SqlSafetyValidator} 用真实 JSqlParser 走全树校验，
 * 只把语义目录替换为「5 张已发布 ADS 的白名单桩」；{@link AiScopeResolver} / {@link QueryCostGuard}
 * 用 mock 的 {@link JdbcTemplate} 覆盖「无 ACTIVE 快照」与「EXPLAIN 超阈值」两条路径。</p>
 */
class AiSqlSecurityTest {

    // ── 作用域：businessDate=2026-09-04 → minAllowedDate=2026-06-07（含端点 90 天） ──
    private static final AiScope SCOPE =
            AiScope.of("S20260901_24", "v2", LocalDate.of(2026, 9, 4));

    private static final String SNAP = "snapshot_id = 'S20260901_24'";

    private static SqlSafetyValidator validator() {
        SemanticCatalog catalog = mock(SemanticCatalog.class);
        Map<String, java.util.Set<String>> tables = new LinkedHashMap<>();
        tables.put("ads_operation_overview_m", java.util.Set.of("snapshot_id", "dt", "pv", "uv", "dau",
                "order_count", "sale_amount", "net_sale_amount", "avg_order_value", "refund_rate", "full_refund_rate"));
        tables.put("ads_sale_trend_m", java.util.Set.of("snapshot_id", "dt", "order_count", "buyer_count",
                "sale_amount", "avg_order_value"));
        tables.put("ads_behavior_funnel_m", java.util.Set.of("snapshot_id", "dt", "stage", "user_count",
                "conversion_rate", "overall_buy_rate"));
        tables.put("ads_hot_product_m", java.util.Set.of("snapshot_id", "dt", "product_id", "product_name",
                "heat_score", "pv", "fav", "cart", "buy", "rank_no"));
        tables.put("ads_product_conversion_m", java.util.Set.of("snapshot_id", "dt", "product_id", "pv_users",
                "buy_users", "conversion_rate"));
        tables.forEach((table, cols) -> {
            when(catalog.tableWhitelisted(table)).thenReturn(true);
            when(catalog.fieldExists(org.mockito.ArgumentMatchers.eq(table), anyString()))
                    .thenAnswer(inv -> cols.contains(((String) inv.getArgument(1)).toLowerCase(java.util.Locale.ROOT)));
        });
        return new SqlSafetyValidator(catalog);
    }

    private static ValidationResult validate(String sql) {
        return validator().validate(sql, SCOPE);
    }

    private static void assertRejected(String sql, String expectedCode) {
        ValidationResult r = validate(sql);
        assertFalse(r.ok(), "该 SQL 必须被拒绝，实际放行: " + sql);
        assertEquals(expectedCode, r.code(), "规则码不符，SQL=" + sql + "，错误=" + r.error());
    }

    // ── 合法 SQL（必须通过且 LIMIT 被规范到 200 以内） ────────────────────────

    @Test
    @DisplayName("合法 SQL：单表 + 快照/日期字面量 → 通过，缺 LIMIT 补 200")
    void 合法SQL通过且补LIMIT() {
        String sql = "SELECT dt, sale_amount, order_count FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260829' AND dt <= '20260904' ORDER BY dt";
        ValidationResult r = validate(sql);
        assertTrue(r.ok(), "合法查询被误拒: " + r.error());
        assertTrue(r.sql().toUpperCase(java.util.Locale.ROOT).endsWith("LIMIT 200"), "未补 LIMIT 200: " + r.sql());
    }

    @Test
    @DisplayName("合法 SQL：聚合/函数白名单 + 小于 200 的显式 LIMIT 原样保留")
    void 合法聚合与显式小LIMIT保留() {
        String sql = "SELECT dt, ROUND(AVG(avg_order_value), 2) AS aov, SUM(sale_amount) AS gmv "
                + "FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260901' AND dt <= '20260904' GROUP BY dt ORDER BY dt DESC LIMIT 10";
        ValidationResult r = validate(sql);
        assertTrue(r.ok(), "合法聚合查询被误拒: " + r.error());
        assertTrue(r.sql().toUpperCase(java.util.Locale.ROOT).contains("LIMIT 10"), "小 LIMIT 被篡改: " + r.sql());
    }

    @Test
    @DisplayName("合法 SQL：BETWEEN 区间 + COALESCE/COUNT 通过（含端点 90 天内）")
    void 合法BETWEEN与白名单函数() {
        String sql = "SELECT dt, COALESCE(refund_rate, 0) AS rr, COUNT(order_count) AS c "
                + "FROM ads_operation_overview_m WHERE " + SNAP
                + " AND dt BETWEEN '20260607' AND '20260904' GROUP BY dt ORDER BY dt LIMIT 200";
        ValidationResult r = validate(sql);
        assertTrue(r.ok(), "合法 BETWEEN 查询被误拒: " + r.error());
        assertTrue(r.sql().toUpperCase(java.util.Locale.ROOT).contains("LIMIT 200"));
    }

    @Test
    @DisplayName("合法 SQL：漏斗单日查询 + 函数实参列命中白名单")
    void 合法漏斗单日查询() {
        String sql = "SELECT dt, stage, SUM(user_count) AS users, CAST(conversion_rate AS DECIMAL(10,4)) AS cr "
                + "FROM ads_behavior_funnel_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' AND stage = 'pay' GROUP BY dt, stage";
        ValidationResult r = validate(sql);
        assertTrue(r.ok(), "合法漏斗查询被误拒: " + r.error());
        assertTrue(r.sql().toUpperCase(java.util.Locale.ROOT).endsWith("LIMIT 200"));
    }

    @Test
    @DisplayName("LIMIT 100000 被改写为 200")
    void 超限LIMIT被改写为200() {
        String sql = "SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260901' AND dt <= '20260904' LIMIT 100000";
        ValidationResult r = validate(sql);
        assertTrue(r.ok(), "该查询本应通过（只改 LIMIT）: " + r.error());
        assertTrue(r.sql().toUpperCase(java.util.Locale.ROOT).contains("LIMIT 200"), "未改写为 200: " + r.sql());
        assertFalse(r.sql().contains("100000"), "仍残留超限 LIMIT: " + r.sql());
    }

    // ── 攻击集（契约 §2.4 逐条） ─────────────────────────────────────────────

    @Test
    @DisplayName("空 SQL 被拒")
    void 空SQL被拒() {
        assertRejected("", SqlPolicy.SQL_EMPTY);
        assertRejected("   ", SqlPolicy.SQL_EMPTY);
        assertRejected(null, SqlPolicy.SQL_EMPTY);
    }

    @Test
    @DisplayName("多语句被拒")
    void 多语句被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'; SELECT 1", SqlPolicy.SQL_MULTI_STATEMENT);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'; DROP TABLE ads_sale_trend_m", SqlPolicy.SQL_MULTI_STATEMENT);
    }

    @Test
    @DisplayName("注释绕过被拒（-- 、# 、/* */）")
    void 注释绕过被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' -- ' AND dt <= '20260904", SqlPolicy.SQL_COMMENT);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP + " # 注释\n"
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_COMMENT);
        assertRejected("SELECT /* keep */ dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_COMMENT);
    }

    @Test
    @DisplayName("DDL/DML 被拒")
    void DDL与DML被拒() {
        assertRejected("DROP TABLE ads_sale_trend_m", SqlPolicy.SQL_NOT_SELECT);
        assertRejected("DELETE FROM ads_sale_trend_m WHERE dt = '20260904'", SqlPolicy.SQL_NOT_SELECT);
        assertRejected("INSERT INTO ads_sale_trend_m (dt) VALUES ('20260904')", SqlPolicy.SQL_NOT_SELECT);
        assertRejected("UPDATE ads_sale_trend_m SET sale_amount = 0", SqlPolicy.SQL_NOT_SELECT);
        assertRejected("TRUNCATE TABLE ads_sale_trend_m", SqlPolicy.SQL_NOT_SELECT);
        // SELECT 开头的写操作（INTO OUTFILE / 会话设置）同样被关键字预检拦下
        assertRejected("SELECT dt FROM ads_sale_trend_m INTO OUTFILE '/tmp/x'", SqlPolicy.SQL_DDL_DML);
    }

    @Test
    @DisplayName("JOIN 被拒")
    void JOIN被拒() {
        assertRejected("SELECT a.dt, a.sale_amount FROM ads_sale_trend_m a JOIN ads_operation_overview_m b "
                + "ON a.dt = b.dt WHERE a." + SNAP + " AND a.dt >= '20260904' AND a.dt <= '20260904'",
                SqlPolicy.SQL_JOIN);
    }

    @Test
    @DisplayName("子查询被拒")
    void 子查询被拒() {
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt = (SELECT MAX(dt) FROM ads_sale_trend_m)", SqlPolicy.SQL_SUBQUERY);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE "
                + "snapshot_id = (SELECT MAX(snapshot_id) FROM ads_sale_trend_m) "
                + "AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_SUBQUERY);
        assertRejected("SELECT dt, (SELECT 1) AS x FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_SUBQUERY);
    }

    @Test
    @DisplayName("CTE 被拒")
    void CTE被拒() {
        assertRejected("WITH t AS (SELECT dt, sale_amount FROM ads_sale_trend_m) "
                + "SELECT dt FROM t WHERE dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_CTE);
    }

    @Test
    @DisplayName("UNION 被拒")
    void UNION被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' UNION SELECT dt, sale_amount "
                + "FROM ads_sale_trend_m WHERE " + SNAP + " AND dt >= '20260904' AND dt <= '20260904'",
                SqlPolicy.SQL_UNION);
    }

    @Test
    @DisplayName("SELECT * 被拒")
    void SELECT星号被拒() {
        assertRejected("SELECT * FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_SELECT_STAR);
        assertRejected("SELECT t.* FROM ads_sale_trend_m t WHERE t." + SNAP
                + " AND t.dt >= '20260904' AND t.dt <= '20260904'", SqlPolicy.SQL_SELECT_STAR);
    }

    @Test
    @DisplayName("未白名单列被拒（SELECT / WHERE / ORDER BY 三处都查）")
    void 未白名单列被拒() {
        assertRejected("SELECT dt, secret_col FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_COLUMN_NOT_ALLOWED);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' AND password = 'x'",
                SqlPolicy.SQL_COLUMN_NOT_ALLOWED);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' ORDER BY engine_cost",
                SqlPolicy.SQL_COLUMN_NOT_ALLOWED);
        // 表也不在白名单
        assertRejected("SELECT dt FROM ads_user_profile_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_TABLE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("未白名单函数被拒（含 DATE_SUB/IF/FIELD 这类看着无害的）")
    void 未白名单函数被拒() {
        assertRejected("SELECT dt, DATE_SUB(dt, INTERVAL 1 DAY) AS d FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_FUNCTION_NOT_ALLOWED);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' AND IF(sale_amount > 0, 1, 0) = 1",
                SqlPolicy.SQL_FUNCTION_NOT_ALLOWED);
        assertRejected("SELECT dt FROM ads_behavior_funnel_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' ORDER BY FIELD(stage, 'pay', 'view')",
                SqlPolicy.SQL_FUNCTION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("information_schema / mysql 库访问被拒")
    void 系统库访问被拒() {
        assertRejected("SELECT table_name FROM information_schema.tables", SqlPolicy.SQL_TABLE_NOT_ALLOWED);
        assertRejected("SELECT dt FROM mysql.user WHERE dt >= '20260904' AND dt <= '20260904'",
                SqlPolicy.SQL_TABLE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("缺日期条件被拒（无 WHERE / 只有快照 / dt 单点相等）")
    void 缺日期条件被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m", SqlPolicy.SQL_DATE_MISSING);
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP, SqlPolicy.SQL_DATE_MISSING);
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt = '20260904'", SqlPolicy.SQL_DATE_MISSING);
    }

    @Test
    @DisplayName("超过 90 天扫描窗口被拒")
    void 超扫描窗口被拒() {
        // 2026-06-06 ~ 2026-09-04 = 91 天（上限 90）
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260606' AND dt <= '20260904'", SqlPolicy.SQL_DATE_RANGE_TOO_WIDE);
    }

    @Test
    @DisplayName("日期超出允许范围被拒（早于 minAllowedDate / 晚于 businessDate / 顺延写法）")
    void 日期越界被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260101' AND dt <= '20260131'", SqlPolicy.SQL_DATE_OUT_OF_SCOPE);
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260905' AND dt <= '20260906'", SqlPolicy.SQL_DATE_OUT_OF_SCOPE);
        // '2026-9-4' 宽松写法不在定长 yyyyMMdd 列上生效 → 视为非法日期字面量
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '2026-9-4' AND dt <= '2026-9-4'", SqlPolicy.SQL_DATE_OUT_OF_SCOPE);
        // 2026-09-11 真机事故回归：ISO yyyy-MM-dd 与 ADS 落库紧凑格式不符，
        // 字符串比较 '20260901' <= '2026-09-01' 恒 false → **静默 0 行**。
        // 现在必须 loud 拒绝，而不是放行后返回空结果。
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '2026-09-01' AND dt <= '2026-09-04'", SqlPolicy.SQL_DATE_OUT_OF_SCOPE);
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '2026-09-04' AND dt <= '2026-09-04'", SqlPolicy.SQL_DATE_OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("回归：dt 字面量只有紧凑 yyyyMMdd 一种格式（生成/校验/提示词同源）")
    void dt字面量格式唯一() {
        assertEquals("20260607", SCOPE.dtFrom());
        assertEquals("20260904", SCOPE.dtTo());
        assertEquals("20260904", AiScope.dt(LocalDate.of(2026, 9, 4)));
        assertEquals("2026-09-04", SCOPE.businessDate().toString(), "审计列仍是 ISO（DB DATE 列）");

        // ① 规则回退四种模板：生成的就是紧凑格式，且能被校验器放行
        List<String> questions = List.of("最近 7 天销售额趋势", "销量最高的商品排行",
                "用户行为漏斗转化", "整体经营概览");
        for (String q : questions) {
            RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve(
                    q, List.of("ads_sale_trend_m", "ads_operation_overview_m",
                            "ads_behavior_funnel_m", "ads_hot_product_m"), SCOPE);
            assertTrue(fb.ok(), q + " 应能生成回退模板");
            assertFalse(fb.sql().contains("2026-"),
                    "回退模板不得出现 ISO 日期（" + q + "）: " + fb.sql());
            ValidationResult v = validator().validate(fb.sql(), SCOPE);
            assertTrue(v.ok(), "回退模板必须与校验器同源（" + q + "）: " + v.code() + " " + v.error());
        }

        // ② 语义目录的日期范围 pin 与少样本
        assertEquals("dt >= '20260607' AND dt <= '20260904'", SemanticCatalog.dateRangePin(SCOPE));
        String few = new SemanticCatalog().fewShots(List.of("ads_sale_trend_m"), SCOPE);
        assertTrue(few.contains("20260904"), "少样本业务日应已参数化为紧凑格式: " + few);
        assertFalse(few.contains("2026-09-04"), "少样本不得残留 ISO 占位日期: " + few);
    }

    @Test
    @DisplayName("非 ACTIVE 快照 / 缺快照条件被拒")
    void 快照不匹配被拒() {
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE "
                + "snapshot_id = 'S20260901_23' AND dt >= '20260904' AND dt <= '20260904'",
                SqlPolicy.SQL_SNAPSHOT_MISMATCH);
        assertRejected("SELECT dt, sale_amount FROM ads_sale_trend_m WHERE "
                + "dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_SNAPSHOT_MISSING);
    }

    @Test
    @DisplayName("窗口函数 / 表库限定 / 危险函数被拒")
    void 其它攻击面被拒() {
        assertRejected("SELECT dt, ROW_NUMBER() OVER (ORDER BY sale_amount) AS rn FROM ads_sale_trend_m WHERE "
                + SNAP + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_WINDOW);
        assertRejected("SELECT dt FROM otherdb.ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904'", SqlPolicy.SQL_TABLE_NOT_ALLOWED);
        assertRejected("SELECT dt FROM ads_sale_trend_m WHERE " + SNAP
                + " AND dt >= '20260904' AND dt <= '20260904' AND SLEEP(5) = 0",
                SqlPolicy.SQL_FUNCTION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("scope 缺失时拒绝校验（fail-closed）")
    void scope缺失时拒绝校验() {
        ValidationResult r = validator().validate(
                "SELECT dt FROM ads_sale_trend_m WHERE dt >= '20260904' AND dt <= '20260904'", null);
        assertFalse(r.ok());
        assertEquals(SqlPolicy.NO_ACTIVE_SNAPSHOT, r.code());
    }

    // ── AiScopeResolver：ACTIVE 不存在 → NO_ACTIVE_SNAPSHOT ──────────────────

    @Test
    @DisplayName("AiScopeResolver：无 ACTIVE 快照 → NO_ACTIVE_SNAPSHOT（不回退归档/最新）")
    void 无ACTIVE快照被拒() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        AiScopeResolver resolver = new AiScopeResolver(jdbc);
        AiSqlException e = assertThrows(AiSqlException.class, resolver::resolve);
        assertEquals(SqlPolicy.NO_ACTIVE_SNAPSHOT, e.code());
        assertTrue(e.getMessage().contains("ACTIVE"));
    }

    @Test
    @DisplayName("AiScopeResolver：只读源缺失 → fail-closed")
    void 只读源缺失failClosed() {
        AiScopeResolver resolver = new AiScopeResolver((javax.sql.DataSource) null);
        AiSqlException e = assertThrows(AiSqlException.class, resolver::resolve);
        assertEquals(SqlPolicy.METRIC_READ_SOURCE_MISSING, e.code());
    }

    @Test
    @DisplayName("AiScopeResolver：读到 ACTIVE 快照 → scope 字段派生正确（businessDate-89）")
    void ACTIVE快照解析正确() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("snapshot_id", "S20260901_24");
        row.put("business_time", LocalDateTime.of(2026, 9, 4, 3, 0, 0));
        row.put("definition_version", "v2");
        when(jdbc.queryForList(anyString())).thenReturn(new ArrayList<>(List.of(row)));
        AiScope scope = new AiScopeResolver(jdbc).resolve();
        assertEquals("S20260901_24", scope.snapshotId());
        assertEquals("v2", scope.definitionVersion());
        assertEquals(LocalDate.of(2026, 9, 4), scope.businessDate());
        assertEquals(LocalDate.of(2026, 6, 7), scope.minAllowedDate());
        assertEquals(90, scope.maxScanDays());
        assertEquals(200, scope.rowLimit());
    }

    // ── QueryCostGuard：EXPLAIN 超阈值 / EXPLAIN 失败都拒绝 ──────────────────

    @Test
    @DisplayName("QueryCostGuard：EXPLAIN 预估超阈值 → SQL_COST_TOO_HIGH")
    void EXPLAIN超阈值被拒() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("EXPLAIN"))).thenReturn(List.of(planRow("ads_sale_trend_m", 900_000L)));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);
        AiSqlException e = assertThrows(AiSqlException.class, () -> guard.check("SELECT dt FROM ads_sale_trend_m"));
        assertEquals(SqlPolicy.SQL_COST_TOO_HIGH, e.code());
        assertTrue(e.getMessage().contains("900000"));
    }

    @Test
    @DisplayName("QueryCostGuard：EXPLAIN 低于阈值 → 通过并回传预估行数")
    void EXPLAIN低阈值通过() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("EXPLAIN"))).thenReturn(List.of(planRow("ads_sale_trend_m", 120L)));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);
        QueryCostGuard.CostEstimate est = guard.check("SELECT dt FROM ads_sale_trend_m");
        assertEquals(120L, est.estimatedScanRows());
        assertEquals(1, est.planRows());
        assertEquals(500_000L, guard.maxExplainRows());
    }

    @Test
    @DisplayName("QueryCostGuard：EXPLAIN 本身失败 / 无 rows 列 → fail-closed 拒绝")
    void EXPLAIN失败failClosed() {
        JdbcTemplate failing = mock(JdbcTemplate.class);
        when(failing.queryForList(contains("EXPLAIN")))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("EXPLAIN 失败", "EXPLAIN x", null));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), failing, 500_000L);
        AiSqlException e = assertThrows(AiSqlException.class, () -> guard.check("SELECT dt FROM ads_sale_trend_m"));
        assertEquals(SqlPolicy.SQL_COST_TOO_HIGH, e.code());

        JdbcTemplate noRows = mock(JdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", "ads_sale_trend_m");
        when(noRows.queryForList(contains("EXPLAIN"))).thenReturn(List.of(row));
        QueryCostGuard guard2 = new QueryCostGuard(mock(SqlExecutor.class), noRows, 500_000L);
        AiSqlException e2 = assertThrows(AiSqlException.class, () -> guard2.check("SELECT dt FROM ads_sale_trend_m"));
        assertEquals(SqlPolicy.SQL_COST_TOO_HIGH, e2.code());

        // 空计划（EXPLAIN 返回 0 行）同样拒绝
        JdbcTemplate emptyPlan = mock(JdbcTemplate.class);
        when(emptyPlan.queryForList(contains("EXPLAIN"))).thenReturn(List.of());
        QueryCostGuard guard3 = new QueryCostGuard(mock(SqlExecutor.class), emptyPlan, 500_000L);
        assertThrows(AiSqlException.class, () -> guard3.check("SELECT dt FROM ads_sale_trend_m"));
    }

    @Test
    @DisplayName("QueryCostGuard：多行计划按乘积口径（最坏情况，偏保守）")
    void EXPLAIN多行乘积口径() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("EXPLAIN")))
                .thenReturn(List.of(planRow("ads_sale_trend_m", 1_000L), planRow("ads_sale_trend_m", 600L)));
        QueryCostGuard guard = new QueryCostGuard(mock(SqlExecutor.class), jdbc, 500_000L);
        AiSqlException e = assertThrows(AiSqlException.class, () -> guard.check("SELECT dt FROM ads_sale_trend_m"));
        assertEquals(SqlPolicy.SQL_COST_TOO_HIGH, e.code());
        assertTrue(e.getMessage().contains("600000"), "乘积口径应为 1000*600=600000: " + e.getMessage());
    }

    // ── 语义目录与规则回退的一致性（少样本必须能过校验器） ────────────────────

    @Test
    @DisplayName("少样本/规则回退模板在真实目录下全部通过校验器（提示词与校验规则一致）")
    void 少样本与规则回退通过真实校验器() {
        SqlSafetyValidator real = new SqlSafetyValidator(new SemanticCatalog());
        SemanticCatalog catalog = new SemanticCatalog();
        for (Map.Entry<String, List<String>> e : SemanticCatalog.FEW_SHOTS.entrySet()) {
            for (String shot : e.getValue()) {
                String sql = SemanticCatalog.parameterize(shot.substring(shot.indexOf("答：") + 2), SCOPE);
                ValidationResult r = real.validate(sql, SCOPE);
                assertTrue(r.ok(), "少样本未通过校验（" + r.code() + "：" + r.error() + "）: " + sql);
                assertNotNull(r.sql());
            }
        }
        for (String q : List.of("最近一天的销售额和订单数是多少", "转化漏斗各阶段人数", "最近的热销商品排行",
                "今天的大盘 GMV 和退款率", "各商品转化率多少")) {
            RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve(q, catalog.selectTables(q), SCOPE);
            assertTrue(fb.ok(), "规则回退未生成 SQL: " + q);
            ValidationResult r = real.validate(fb.sql(), SCOPE);
            assertTrue(r.ok(), "规则回退模板未通过校验（" + r.code() + "：" + r.error() + "）: " + fb.sql());
        }
    }

    @Test
    @DisplayName("无 scope 时规则回退拒绝生成 SQL（fail-closed）")
    void 无scope时规则回退拒绝() {
        RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve("销售额", List.of("ads_sale_trend_m"));
        assertFalse(fb.ok());
        assertTrue(fb.error().contains(SqlPolicy.NO_ACTIVE_SNAPSHOT));
    }

    private static Map<String, Object> planRow(String table, long rows) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("select_type", "SIMPLE");
        row.put("table", table);
        row.put("type", "ALL");
        row.put("rows", rows);
        return row;
    }
}
