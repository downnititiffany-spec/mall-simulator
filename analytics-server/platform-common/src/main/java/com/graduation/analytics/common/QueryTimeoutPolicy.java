package com.graduation.analytics.common;

import org.springframework.core.env.Environment;

/**
 * 只读查询超时的**唯一数值属主**（指导书 V3.0 §7 阶段4 L158「…分页、限流、**超时统一**」）。
 *
 * <p>设计 V3.0 的锚点：L544 明确 MetricStore 的能力描述要包含 {@code queryTimeout}；
 * L569 给只读 SQL 定下「查询超时 30 秒」；L572 要求只读链路上 {@code setReadOnly} 与
 * 「timeout / maxRows」**同时生效**。本类只承担"一个数、一处定、谁都能引用"，
 * 不做执行（执行点分别是 {@code metricReadJdbcTemplate} 与 {@code SqlExecutor}）。</p>
 *
 * <p><b>为什么需要它</b>：S3-19 之前，阶段4 的分析只读链路（{@code MySqlMetricStore}、
 * {@code MetricAdsReader}）**没有任何语句超时**，而阶段6 的 {@code SqlExecutor} 自带字面量 30；
 * 同一平台两套超时正是 L158 要消掉的漂移。属主收拢后，改一处两条路径同时改。</p>
 *
 * <p><b>绝不解释成"无超时"</b>：JDBC 里 {@code setQueryTimeout(0)} 表示永不超时。
 * 因此配置值为 0 / 负数 / 非数字 / 空 / 越界时一律回退默认值，而不是把 L158 静默关掉。</p>
 *
 * <p><b>未做（已登记）</b>：per-Store 能力描述（L544 的 {@code supportsSnapshot /
 * supportsDimensions / maxRows / queryTimeout / availability}）本轮未实现；
 * meta 读写共用模板与发布写路径刻意未纳入读超时。</p>
 */
public final class QueryTimeoutPolicy {

    /** 统一默认读查询超时（秒）：取设计 L569 的 30 秒先例，AI 只读 SQL 原先也是这个值。 */
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    /** 配置键：阶段4 分析只读查询超时（秒）。缺省即用 {@link #DEFAULT_QUERY_TIMEOUT_SECONDS}。 */
    public static final String READ_TIMEOUT_PROPERTY = "platform.query.read-timeout-seconds";

    private QueryTimeoutPolicy() {
    }

    /**
     * 解析只读查询超时（秒）。**永不返回非正值**——0/负数/非数字/空/整数溢出全部回退默认值。
     *
     * @param raw 配置原文，可为 {@code null}
     * @return 严格大于 0 的超时秒数
     */
    public static int readTimeoutSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_QUERY_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }

    /**
     * 从运行环境取只读查询超时（秒）。
     *
     * @param env 运行环境；为 {@code null} 时取默认值（便于装配测试直接构造）
     * @return 严格大于 0 的超时秒数
     */
    public static int readTimeoutSeconds(Environment env) {
        return env == null ? DEFAULT_QUERY_TIMEOUT_SECONDS : readTimeoutSeconds(env.getProperty(READ_TIMEOUT_PROPERTY));
    }
}
