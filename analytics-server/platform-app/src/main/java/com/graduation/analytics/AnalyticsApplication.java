package com.graduation.analytics;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 分析平台启动入口（R1 骨架，整改书 §6）：与 mall-simulator 独立的进程（端口 8091）。
 * 后续轮次逐步接入：connection-ingestion / warehouse-pipeline / metric-analysis /
 * ai-decision 及三库（analytics_meta / analytics_metric）迁移与代码。
 *
 * R7-1（V2.0 §17.1）：**移除** {@code com.graduation.analytics.metric.mapper} 扫描 ——
 * analytics_metric 不使用 MyBatis-Plus mapper，指标库读写一律走
 * metricPublishJdbcTemplate / metricReadJdbcTemplate（见 PlatformDataSources）。
 * R7-3：metric_definition 仍在 analytics_meta（§17.2 表所有权），其 mapper/实体迁到
 * {@code com.graduation.analytics.metric.dict} 并纳入扫描（发布前口径版本对账需要）。
 *
 * P1-03（2026-09-11）：新增 {@code com.graduation.analytics.source.mapper}
 * （{@code source_registry} 与 {@code runtime_profile.source_id} 绑定的唯一读写口）。
 * 仍是**同一份** {@code @MapperScan} 列表——不新建第二个扫描配置，避免"哪些包被扫描"
 * 出现两个说法。
 */
@SpringBootApplication
@MapperScan({"com.graduation.analytics.ingestion.mapper",
        "com.graduation.analytics.pipeline.mapper",
        "com.graduation.analytics.ai.mapper",
        "com.graduation.analytics.decision.mapper",
        "com.graduation.analytics.auth.mapper",
        "com.graduation.analytics.runtime.mapper",
        "com.graduation.analytics.metric.dict",
        "com.graduation.analytics.source.mapper"})
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}