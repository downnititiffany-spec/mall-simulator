package com.graduation.analytics.ai.sql;

import com.graduation.analytics.common.QueryTimeoutPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-19 反熵守卫：只读查询超时只有**一个数值属主**（{@link QueryTimeoutPolicy}）。
 *
 * <p>阶段4 分析读路径在 {@code metricReadJdbcTemplate} 上取该值；阶段6 的 AI 只读 SQL
 * （{@link SqlExecutor}，裸 JDBC 直连 metric_read）也从这里取值。HiveServer2 RuntimeProfile
 * 探测的语句级映射由 {@code RuntimeProfileHiveProbeRedactionTest} 单独核验。本测试只钉住
 * AI SQL 对统一属主的引用，避免该执行点重新引入字面量。</p>
 *
 * <p><b>边界</b>：共享配置覆盖分析 JDBC、HiveServer2 探测语句与 AI SQL/EXPLAIN 的语句级超时；AI 执行点仍有
 * {@code JdbcTemplate} 与裸 JDBC 两类，各自将超时映射为 {@code QUERY_TIMEOUT}。
 * 真 MySQL 到点行为及真 HTTP 超时响应仍需真实环境验证。</p>
 */
class QueryTimeoutOwnerDriftTest {

    @Test
    @DisplayName("AI 只读 SQL 的默认超时值取自平台唯一属主")
    void aiReadPathUsesUnifiedTimeoutDefault() {
        assertThat(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS).isEqualTo(30);
        SqlExecutor executor = new SqlExecutor();
        assertThat(executor.queryTimeoutSeconds()).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("AI 只读 SQL 与分析只读模板共享可配置超时，非法值仍回退默认值")
    void configuredTimeoutAndInvalidFallbackComeFromSameOwner() {
        SqlExecutor executor = new SqlExecutor();
        ReflectionTestUtils.setField(executor, "queryTimeoutProperty", "7");
        assertThat(executor.queryTimeoutSeconds()).isEqualTo(QueryTimeoutPolicy.readTimeoutSeconds("7"));

        ReflectionTestUtils.setField(executor, "queryTimeoutProperty", "0");
        assertThat(executor.queryTimeoutSeconds()).isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }
}
