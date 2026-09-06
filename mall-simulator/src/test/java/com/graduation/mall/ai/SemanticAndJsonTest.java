package com.graduation.mall.ai;

import com.graduation.mall.ai.llm.JsonExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 提取与语义目录测试。
 */
class SemanticAndJsonTest {

    @Test
    @DisplayName("JsonExtractor：裸 JSON / 代码块 / 前缀文本")
    void jsonExtractorVariants() {
        String bare = "{\"sql\":\"SELECT 1\"}";
        assertEquals("SELECT 1", JsonExtractor.extractJson(bare).path("sql").asText());

        String fenced = "```json\n{\"sql\":\"SELECT 2\"}\n```";
        assertEquals("SELECT 2", JsonExtractor.extractJson(fenced).path("sql").asText());

        String prefixed = "好的，这是结果：\n{\"sql\":\"SELECT 3\"}\n希望对你有帮助";
        assertEquals("SELECT 3", JsonExtractor.extractJson(prefixed).path("sql").asText());

        assertThrows(IllegalArgumentException.class, () -> JsonExtractor.extractJson("没有 JSON"));
    }

    @Test
    @DisplayName("SemanticCatalog：中文别名与主题→表选择")
    void catalogAliasesAndTopics() {
        SemanticCatalog catalog = new SemanticCatalog();
        assertEquals("sale_amount", catalog.FIELD_ALIASES.get("销售额"));
        assertEquals("order_count", catalog.FIELD_ALIASES.get("订单数"));

        assertTrue(catalog.selectTables("最近7天销售额趋势").contains("ads_sale_trend_m"));
        assertTrue(catalog.selectTables("9月4日转化漏斗").contains("ads_behavior_funnel_m"));
        assertTrue(catalog.selectTables("大盘活跃情况").contains("ads_operation_overview_m"));

        assertTrue(catalog.tableWhitelisted("ads_sale_trend_m"));
        assertTrue(catalog.fieldExists("ads_sale_trend_m", "sale_amount"));
        assertTrue(!catalog.fieldExists("ads_sale_trend_m", "password"));
        assertTrue(!catalog.tableWhitelisted("mysql.user"));

        // schema JSON 与少样本可生成
        String schema = catalog.schemaJson(java.util.List.of("ads_sale_trend_m"));
        assertTrue(schema.contains("sale_amount"));
        assertTrue(catalog.fewShots(java.util.List.of("ads_sale_trend_m")).contains("SELECT"));
    }

    @Test
    @DisplayName("规则回退：推荐问题→白名单模板 SQL")
    void ruleBasedFallback() {
        var funnel = RuleBasedSqlFallback.resolve("9月4日转化漏斗怎么样？", java.util.List.of("ads_behavior_funnel_m"));
        assertTrue(funnel.sql().contains("ads_behavior_funnel_m"));

        var sales = RuleBasedSqlFallback.resolve("最近7天销售额趋势如何？", java.util.List.of("ads_sale_trend_m"));
        assertTrue(sales.sql().contains("ads_sale_trend_m"));
        assertTrue(!sales.assumptions().isEmpty());
    }
}