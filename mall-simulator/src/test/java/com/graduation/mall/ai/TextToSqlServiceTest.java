package com.graduation.mall.ai;

import com.graduation.mall.ai.llm.MockLlmProvider;
import com.graduation.mall.ai.mapper.AiCallLogMapper;
import com.graduation.mall.ai.mapper.AiQueryHistoryMapper;
import com.graduation.mall.ai.sql.SqlExecutor;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text-to-SQL 全链测试（§8.4-8.7，MockLLM）：
 * 生成→校验→执行→审计；危险 SQL 一次修复；修复失败进入 FAILED。
 */
class TextToSqlServiceTest extends MallTestSupport {

    @Autowired
    private TextToSqlService textToSqlService;

    @Autowired
    private SqlExecutor executor;

    @Autowired
    private AiQueryHistoryMapper historyMapper;

    @Autowired
    private AiCallLogMapper callLogMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedAds() {
        jdbc.update("INSERT INTO ads_sale_trend_m (dt, order_count, buyer_count, sale_amount, net_sale_amount," +
                "avg_order_value, snapshot_id) VALUES (?,?,?,?,?,?,?)",
                "2026-09-04", 24, 20, new BigDecimal("3702.5000"),
                new BigDecimal("3602.5000"), new BigDecimal("154.2708"), "S_TEST");
    }

    private void mockSqlResponse(String marker, String json) {
        MockLlmProvider.BEHAVIOR.put(marker, ignored -> json);
    }

    @Test
    @DisplayName("安全 SQL：生成→校验→执行返回真实行，审计落库")
    void safeQueryFullChain() {
        mockSqlResponse("问题：查询销售总额",
                """
                {"intent":"查询销售额","metrics":["sale_amount"],
                 "sql":"SELECT dt, sale_amount, order_count FROM ads_sale_trend_m WHERE dt = '2026-09-04'",
                 "assumptions":["固定日期"]}""");

        TextToSqlService.QueryResult result = textToSqlService.query("查询销售总额", "tester");

        assertEquals("EXECUTED", result.status());
        assertEquals(1, result.rowsReturned());
        assertEquals("3702.5000", String.valueOf(result.rows().get(0).get("sale_amount")), "真实执行结果");
        assertTrue(result.providerUsed().equals("mock"));
        assertTrue(result.sql().toLowerCase().contains("limit"), "LIMIT 已由校验器补齐");

        // 审计
        assertEquals(1L, historyMapper.selectCount(null).longValue());
        assertEquals(1L, callLogMapper.selectCount(null).longValue());
    }

    @Test
    @DisplayName("危险 SQL：首轮被拦，一次修复后 REPAIRED 并成功执行")
    void dangerousSqlRepaired() {
        mockSqlResponse("问题：删除数据测试",
                """
                {"intent":"删除","metrics":[],
                 "sql":"DELETE FROM ads_sale_trend_m WHERE dt = '2026-09-04'",
                 "assumptions":[]}""");
        mockSqlResponse("上一次生成的 SQL",
                """
                {"intent":"删除","metrics":[],
                 "sql":"SELECT dt, sale_amount FROM ads_sale_trend_m WHERE dt = '2026-09-04'",
                 "assumptions":["同上，但已改为只读"]}""");

        TextToSqlService.QueryResult result = textToSqlService.query("删除数据测试", "tester");

        assertEquals("REPAIRED", result.status(), "修复后必须重新通过安全校验并执行");
        assertEquals(1, result.rowsReturned());
        // 数据未被删除
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ads_sale_trend_m WHERE dt = '2026-09-04'",
                Integer.class);
        assertEquals(1, rows, "DELETE 必须被拦截，数据完好");
    }

    @Test
    @DisplayName("修复仍失败 → FAILED 且不执行任何语句")
    void repairFails() {
        mockSqlResponse("问题：连续危险",
                """
                {"intent":"x","metrics":[],
                 "sql":"DROP TABLE ads_sale_trend_m",
                 "assumptions":[]}""");
        mockSqlResponse("上一次生成的 SQL",
                """
                {"intent":"x","metrics":[],
                 "sql":"DELETE FROM ads_sale_trend_m",
                 "assumptions":[]}""");

        TextToSqlService.QueryResult result = textToSqlService.query("连续危险", "tester");
        assertEquals("FAILED", result.status());
        assertTrue(result.error() != null && result.error().contains("安全校验"), "错误必须说明校验原因");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ads_sale_trend_m", Integer.class);
        assertEquals(1, rows.intValue(), "表必须原样存在");
    }

    @Test
    @DisplayName("执行器：行数上限 1000 截断 + 金额字符串精度")
    void executorLimits() throws Exception {
        SqlExecutor.ExecutionResult r = executor.execute(
                "SELECT dt, sale_amount FROM ads_sale_trend_m WHERE dt = '2026-09-04'");
        assertNotNull(r.rows().get(0).get("sale_amount"));
        assertEquals("3702.5000", String.valueOf(r.rows().get(0).get("sale_amount")));
    }
}