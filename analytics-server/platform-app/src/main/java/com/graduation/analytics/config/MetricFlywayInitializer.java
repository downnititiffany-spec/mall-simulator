package com.graduation.analytics.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * R7-1 指标库迁移执行器（V2.0 §17.2）：用 **metricPublishDataSource**（metric_pub）执行
 * {@code classpath:db/metric}（V1 快照/指标值 → V2 ADS 宽表 → V3 R7 ADS 补齐 → V4 画像 R/F/M 原值与窗口
 * → V5 销售趋势净销售额 → V6 运营大盘复购率与观察期声明 → V7 漏斗加购率）。
 *
 * 与 {@link MetaFlywayInitializer} 完全分离：
 * <ul>
 *   <li>不同数据源、不同库（analytics_metric vs analytics_meta）；</li>
 *   <li>迁移历史表各自落在自己的库里（analytics_metric.flyway_schema_history），互不共用；</li>
 *   <li>日志前缀独立，任一库迁移失败只影响自己的启动信息，便于定位。</li>
 * </ul>
 *
 * 注意：构造器显式写 @Qualifier —— Lombok 默认不把字段上的 @Qualifier 复制到构造参数，
 * 多数据源下会退化成按类型注入 @Primary 的 metaDataSource，把 metric 迁移跑进元数据库。
 */
@Slf4j
@Configuration
public class MetricFlywayInitializer {

    private final DataSource metricPublishDataSource;

    public MetricFlywayInitializer(
            @Qualifier("metricPublishDataSource") DataSource metricPublishDataSource) {
        this.metricPublishDataSource = metricPublishDataSource;
    }

    @PostConstruct
    public void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(metricPublishDataSource)
                .locations("classpath:db/metric")
                .load();
        var result = flyway.migrate();
        var current = flyway.info().current();
        String currentVersion = current == null || current.getVersion() == null
                ? "(无已应用迁移)" : current.getVersion().getVersion();
        log.info("analytics_metric 迁移完成: 执行 {} 个脚本，当前版本 {} (迁移历史 analytics_metric.flyway_schema_history)",
                result.migrationsExecuted, currentVersion);
    }
}
