package com.graduation.analytics.ai.sql;

import com.graduation.analytics.common.QueryTimeoutPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-19 反熵守卫：只读查询超时只有**一个数值属主**（{@link QueryTimeoutPolicy}）。
 *
 * <p>阶段4 分析读路径在 {@code metricReadJdbcTemplate} 上取该值；阶段6 的 AI 只读 SQL
 * （{@link SqlExecutor}，裸 JDBC 直连 metric_read）此前自带一个字面量 {@code 30}。
 * 两处若各自演化，就会出现"同一平台两套超时"，正是指导书 L158「超时统一」要消掉的东西。
 * 本测试把两者的相等关系钉住：改属主即两条路径同时改，改单边立刻红。</p>
 *
 * <p><b>边界</b>：相等只覆盖**数值与属主**；执行点仍有两个（JdbcTemplate / 裸 JDBC），
 * 且 AI 路径的 {@code SQLTimeoutException} 走阶段6 自己的错误上报，**未**汇入
 * {@code QUERY_TIMEOUT} 响应码 —— 已登记为遗留。</p>
 */
class QueryTimeoutOwnerDriftTest {

    @Test
    @DisplayName("AI 只读 SQL 的超时值取自平台唯一属主，不各自写字面量")
    void aiReadPathUsesUnifiedTimeoutOwner() {
        assertThat(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS).isEqualTo(30);
        assertThat(SqlExecutor.QUERY_TIMEOUT_SECONDS)
                .isEqualTo(QueryTimeoutPolicy.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }
}
