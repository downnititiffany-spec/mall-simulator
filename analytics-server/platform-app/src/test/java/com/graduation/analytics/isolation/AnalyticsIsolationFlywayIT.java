package com.graduation.analytics.isolation;

import com.graduation.analytics.testsupport.TestIsolationGuard;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.fs.MD5MD5CRC32CastagnoliFileChecksum;
import org.apache.hadoop.fs.MD5MD5CRC32FileChecksum;
import org.apache.hadoop.io.MD5Hash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 7 analytics 双库 schema 准备入口。
 *
 * <p>本类只在 {@code -Pisolated-analytics-schema} 下被 Surefire 选中。它刻意不使用
 * {@code IsolationProfileCondition}：既然调用方显式选择了 schema 准备，缺完整 V25_IT_* 时必须
 * <b>失败</b>，不能 disabled 后让 Maven 假绿。</p>
 *
 * <p>执行顺序严格是：加载完整隔离上下文 → 分别建立 meta/metric 最小权限连接 → 两边各自
 * {@link TestIsolationGuard#verifyBeforeWrite} → Flyway migrate。不会碰 3306、不会合库、不会用
 * 一个跨库写账号。无论本次是 fresh 初始化还是对已初始化隔离库的重复验证，第二次 migrate 都必须
 * 0 个脚本，并且 Flyway 必须能报告当前版本、关键表必须实际存在。</p>
 */
@Tag("analytics-schema-it")
class AnalyticsIsolationFlywayIT {

    @Test
    void migratesMetaAndMetricSchemasIndependentlyAndIdempotently() {
        TestIsolationGuard.TestRunContext context = TestIsolationGuard.loadContext();
        assertThat(context.metaDb()).isNotEqualTo(context.metricDb());

        DataSource metaDs = dataSource(context.metaDb(), "meta.username", "meta.password");
        DataSource metricDs = dataSource(context.metricDb(), "metric.publish.username", "metric.publish.password");

        TestIsolationGuard.LiveFacts metaFacts =
                TestIsolationGuard.verifyBeforeWrite(context, metaDs, context.metaDb());
        TestIsolationGuard.LiveFacts metricFacts =
                TestIsolationGuard.verifyBeforeWrite(context, metricDs, context.metricDb());
        System.out.println("[AnalyticsIsolationFlywayIT] meta facts: " + metaFacts.redactedSummary());
        System.out.println("[AnalyticsIsolationFlywayIT] metric facts: " + metricFacts.redactedSummary());

        Flyway metaFlyway = Flyway.configure().dataSource(metaDs).locations("classpath:db/meta").load();
        Flyway metricFlyway = Flyway.configure().dataSource(metricDs).locations("classpath:db/metric").load();

        var metaFirst = metaFlyway.migrate();
        var metricFirst = metricFlyway.migrate();
        var metaSecond = metaFlyway.migrate();
        var metricSecond = metricFlyway.migrate();

        // MigrateResult.targetSchemaVersion 在“schema 已是最新，本次执行 0 条迁移”时允许为 null，
        // 因此不能把它当作 schema 已初始化的判据。重复使用同一隔离 RunId 时，正确行为正是首轮 0 条。
        assertThat(metaFlyway.info().current()).as("meta Flyway 必须已有当前版本").isNotNull();
        assertThat(metricFlyway.info().current()).as("metric Flyway 必须已有当前版本").isNotNull();
        assertThat(metaFlyway.info().current().getVersion().getVersion())
                .as("隔离 meta schema 必须真实应用分类/城市等级规则迁移 V34")
                .isEqualTo("34");
        assertThat(metricFlyway.info().current().getVersion().getVersion())
                .as("隔离 metric schema 必须真实应用 ADS 分类/城市等级服务表迁移 V13")
                .isEqualTo("13");
        assertThat(metaSecond.migrationsExecuted).as("meta 第二次启动必须空跑").isZero();
        assertThat(metricSecond.migrationsExecuted).as("metric 第二次启动必须空跑").isZero();

        JdbcTemplate meta = new JdbcTemplate(metaDs);
        JdbcTemplate metric = new JdbcTemplate(metricDs);
        List<Map<String, Object>> categoryRegionRules = meta.queryForList(
                "SELECT rule_code, version, stage, severity FROM quality_rule_definition"
                        + " WHERE (rule_code = 'ADS_CATEGORY_SALE_RECONCILE' AND version = 1)"
                        + " OR (rule_code = 'ADS_REGION_SALE_RECONCILE' AND version = 1)"
                        + " OR (rule_code = 'ADS_STAGING_PRESENT' AND version = 3)"
                        + " OR (rule_code = 'MXP_EXPORT_COMPLETE' AND version = 2)");
        assertThat(categoryRegionRules)
                .as("V34 新增/升级的四条质量规则必须真实落库，不能只通过静态 SQL 检查")
                .extracting(row -> row.get("rule_code") + ":" + row.get("version")
                        + ":" + row.get("stage") + ":" + row.get("severity"))
                .containsExactlyInAnyOrder(
                        "ADS_CATEGORY_SALE_RECONCILE:1:ADS:BLOCKING",
                        "ADS_REGION_SALE_RECONCILE:1:ADS:BLOCKING",
                        "ADS_STAGING_PRESENT:3:ADS:BLOCKING",
                        "MXP_EXPORT_COMPLETE:2:PUBLISH:BLOCKING");
        assertThat(tableCount(meta, "runtime_profile"))
                .as("db/meta 迁移必须在 metaDb 创建 runtime_profile")
                .isEqualTo(1);
        assertThat(tableCount(metric, "metric_snapshot"))
                .as("db/metric 迁移必须在 metricDb 创建 metric_snapshot")
                .isEqualTo(1);

        System.out.println("[AnalyticsIsolationFlywayIT] migrated meta=" + context.metaDb()
                + " first=" + metaFirst.migrationsExecuted + " second=" + metaSecond.migrationsExecuted
                + "; metric=" + context.metricDb()
                + " first=" + metricFirst.migrationsExecuted + " second=" + metricSecond.migrationsExecuted);
    }

    /**
     * G31-09 / D-041 的存储层证据：file_identity 加宽到 255 后，94 字符 HDFS 身份
     * 必须原样落库/回读，唯一键与 upsert 语义不变。
     *
     * <p>行为级「同一文件重试不重读」的判定在 {@code LocalFileIngestor}（单测已钉）；
     * D-041 的断点恰恰在它前面一步——94 字符身份 INSERT 抛 MysqlDataTruncation，
     * 断点行根本落不下来。本用例证明这一步已修复。本用例只写**自己的**一行
     * file_checkpoint，finally 自清，不给其他用例留状态。</p>
     */
    @Test
    @DisplayName("G31-09/D-041：94 字符 HDFS 身份原样存取，uk_ckpt_source 语义不变")
    void fileCheckpointStoresHdfsIdentityAndKeepsUpsertSemantics() {
        TestIsolationGuard.TestRunContext context = TestIsolationGuard.loadContext();
        DataSource metaDs = dataSource(context.metaDb(), "meta.username", "meta.password");
        TestIsolationGuard.verifyBeforeWrite(context, metaDs, context.metaDb());
        Flyway.configure().dataSource(metaDs).locations("classpath:db/meta").load().migrate();
        JdbcTemplate meta = new JdbcTemplate(metaDs);

        // 列宽以 information_schema 实测为准：脚本文本归静态门禁所有，这里只认真库事实
        Map<String, Object> column = meta.queryForMap(
                "SELECT column_type, character_maximum_length, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = 'file_checkpoint'"
                        + " AND column_name = 'file_identity'");
        assertThat(String.valueOf(column.get("column_type"))).isEqualTo("varchar(255)");
        assertThat(((Number) column.get("character_maximum_length")).intValue())
                .as("D-041 判据宽度：255（uk_ckpt_source utf8mb4 预算 8+8+2000+1020=3036 ≤ 3072）")
                .isEqualTo(255);
        assertThat(column.get("is_nullable")).as("空值语义不得随加宽改变").isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).isEmpty();

        // 种子主键查询获取，不硬编码 id（种子由 V7/V16 写入）
        Long sourceId = meta.queryForObject(
                "SELECT id FROM source_registry WHERE source_code = 'mock-mall'", Long.class);
        Long profileId = meta.queryForObject(
                "SELECT id FROM runtime_profile WHERE profile_code = 'local-dev'", Long.class);

        // HdfsLandingStorage.fileIdentity 真实形态（HdfsLandingStorage.java:145）：
        // "hdfs:" + checksum.getAlgorithmName() + ":" + HexFormat hex(checksum.getBytes())。
        // 算法名与校验和字节由真实 Hadoop 类给出（512B CRC32C、单块小文件 crcPerBlock=0）：
        // 算法名 "MD5-of-0MD5-of-512CRC32C" 24 字符；getBytes() = WritableUtils.toByteArray，
        // 返回 DataOutputBuffer.getData() 的**原始缓冲**（不按写入量裁剪）——write() 实写
        // 28 字节（bytesPerCRC 4 + crcPerBlock 8 + md5 16），但 ByteArrayOutputStream 初容量
        // 32，28 ≤ 32 不扩容 → 实际返回 byte[32]（含 4 字节零余量）→ 64 hex。
        // 合计 5+24+1+64 = 94（hadoop-common 3.3.4 javap 字节码与平台侧实测双证一致，94）。
        MD5MD5CRC32FileChecksum checksum = new MD5MD5CRC32CastagnoliFileChecksum(
                512, 0L, new MD5Hash("0123456789abcdef0123456789abcdef"));
        String identity = "hdfs:" + checksum.getAlgorithmName() + ":"
                + java.util.HexFormat.of().formatHex(checksum.getBytes());
        assertThat(identity)
                .startsWith("hdfs:MD5-of-0MD5-of-512CRC32C:")
                .as("94 > 64：V8 宽度(64)下此 INSERT 必然 MysqlDataTruncation（D-041 的复现形态）")
                .hasSize(94);
        String filePath = "hdfs://127.0.0.1:19000/landing/g3109-it/flume-raw/mock-mall/events.jsonl";
        try {
            meta.update("INSERT INTO file_checkpoint"
                            + " (runtime_profile_id, source_id, file_path, file_identity, next_offset)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    profileId, sourceId, filePath, identity, 4096L);
            assertThat(meta.queryForObject(
                            "SELECT file_identity FROM file_checkpoint"
                                    + " WHERE runtime_profile_id = ? AND source_id = ? AND file_path = ?",
                            String.class, profileId, sourceId, filePath))
                    .as("94 字符 HDFS 身份必须原样回读（存储层保真 = 同文件重试不重读的前提）")
                    .isEqualTo(identity);

            assertThatThrownBy(() -> meta.update("INSERT INTO file_checkpoint"
                            + " (runtime_profile_id, source_id, file_path, file_identity, next_offset)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    profileId, sourceId, filePath, identity, 4096L))
                    .as("同 (profile, source, path, identity) 重复插入必须被 uk_ckpt_source 拒绝——"
                            + "LocalFileIngestor 的 insert-or-update upsert 依赖这一前提")
                    .isInstanceOf(DuplicateKeyException.class);

            // 换文件版本路径：UPDATE 到新身份必须被接受且无损（upsert 的另一分支）
            MD5MD5CRC32FileChecksum nextChecksum = new MD5MD5CRC32CastagnoliFileChecksum(
                    512, 0L, new MD5Hash("ffffffffffffffffffffffffffffffff"));
            String nextIdentity = "hdfs:" + nextChecksum.getAlgorithmName() + ":"
                    + java.util.HexFormat.of().formatHex(nextChecksum.getBytes());
            meta.update("UPDATE file_checkpoint SET file_identity = ?, next_offset = ?"
                            + " WHERE runtime_profile_id = ? AND source_id = ? AND file_path = ?",
                    nextIdentity, 8192L, profileId, sourceId, filePath);
            assertThat(meta.queryForObject(
                            "SELECT file_identity FROM file_checkpoint"
                                    + " WHERE runtime_profile_id = ? AND source_id = ? AND file_path = ?",
                            String.class, profileId, sourceId, filePath))
                    .as("UPDATE 换身份后必须原样可读（255 宽度下同样无损）")
                    .isEqualTo(nextIdentity);
        } finally {
            meta.update("DELETE FROM file_checkpoint"
                            + " WHERE runtime_profile_id = ? AND source_id = ? AND file_path = ?",
                    profileId, sourceId, filePath);
        }
        assertThat(meta.queryForObject(
                        "SELECT COUNT(*) FROM file_checkpoint"
                                + " WHERE runtime_profile_id = ? AND source_id = ? AND file_path = ?",
                        Integer.class, profileId, sourceId, filePath))
                .as("本用例只允许自清自证：不留任何 own 行")
                .isZero();
    }

    private static int tableCount(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return count == null ? 0 : count;
    }

    private static DataSource dataSource(String database, String userKey, String passwordKey) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + TestIsolationGuard.requiredProperty("mysql.host") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(TestIsolationGuard.requiredProperty(userKey));
        ds.setPassword(TestIsolationGuard.requiredProperty(passwordKey));
        return ds;
    }
}
