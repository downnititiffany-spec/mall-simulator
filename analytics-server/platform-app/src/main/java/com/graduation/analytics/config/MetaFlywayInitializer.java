package com.graduation.analytics.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * R1 平台元数据库迁移执行器（整改书 §7.3）：analytics_meta 迁移集合
 * classpath:db/meta（V1 采集 → V2 流水线/质量 → V3 AI 审计 → V4 决策 → V5 用户 → V6 只读账号）。
 * 关闭 Spring Boot 默认 flyway，由本执行器显式控制（多库阶段，metric/business 后续接入）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MetaFlywayInitializer {

    private final DataSource metaDataSource;

    @PostConstruct
    public void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(metaDataSource)
                .locations("classpath:db/meta")
                .load();
        var result = flyway.migrate();
        log.info("analytics_meta 迁移完成: 执行 {} 个脚本，版本 {}", result.migrationsExecuted, result.targetSchemaVersion);
    }
}