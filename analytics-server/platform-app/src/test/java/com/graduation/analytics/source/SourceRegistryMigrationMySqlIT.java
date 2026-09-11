package com.graduation.analytics.source;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-02 真库验证（默认跳过：需 {@code -Dp1.it=true} 且 surefire 显式指定本类）。
 *
 * <p>验证对象是**真 MySQL 8.0 的 analytics_meta**：连两次 Flyway 迁移（模拟两次应用启动），
 * 然后用 information_schema 断言新表结构、种子源、外键、加性列与存量回填。</p>
 *
 * <p>本类只读业务数据、不写任何业务行（唯一写入是 Flyway 自己的迁移历史）。
 * 回填「不改版本/激活时间」的逐值证据是同一时刻抓取的迁移前/后行快照对比，
 * 落在 {@code docs/acceptance/p1-02-source-registry-20260911/}（见该目录 README）。</p>
 */
@EnabledIfSystemProperty(named = "p1.it", matches = "true")
class SourceRegistryMigrationMySqlIT {

    private static final String META_DB = "analytics_meta";

    /** 迁移前 frozen 的 runtime_profile 列集合（实测于 2026-09-11，见 pre-state/05-runtime-profile-columns.txt） */
    private static final List<String> FROZEN_RUNTIME_PROFILE_COLUMNS = List.of(
            "id", "profile_code", "profile_name", "type", "status",
            "landing_uri", "hdfs_uri", "hive_jdbc_url", "hive_database_prefix",
            "spark_master", "deploy_mode", "yarn_queue",
            "ssh_host", "ssh_port", "ssh_user",
            "spark_submit_path", "spark_job_jar_uri",
            "metric_store_type", "metric_store_config_ref", "credential_ref",
            "timezone", "version", "created_at", "updated_at");

    /** source_registry 冻结列 → 期望 column_type（实施书 §3.1） */
    private static final Map<String, String> EXPECTED_SOURCE_REGISTRY_TYPES = new LinkedHashMap<>();

    static {
        EXPECTED_SOURCE_REGISTRY_TYPES.put("id", "bigint");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("source_code", "varchar(64)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("display_name", "varchar(128)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("ingest_mode", "varchar(16)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("profile_path", "varchar(255)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("timezone", "varchar(64)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("currency", "char(3)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("status", "varchar(16)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("profile_version", "varchar(32)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("created_at", "datetime(3)");
        EXPECTED_SOURCE_REGISTRY_TYPES.put("updated_at", "datetime(3)");
    }

    private static JdbcTemplate meta;
    private static int firstStartupExecuted;
    private static int secondStartupExecuted;
    private static String firstStartupSchemaVersion;

    @BeforeAll
    static void twoApplicationStartups() {
        meta = new JdbcTemplate(dataSource());
        // 第一次「启动」：MetaFlywayInitializer 等价调用（同 classpath:db/meta）
        var first = Flyway.configure().dataSource(meta.getDataSource()).locations("classpath:db/meta").load().migrate();
        firstStartupExecuted = first.migrationsExecuted;
        firstStartupSchemaVersion = String.valueOf(first.targetSchemaVersion);
        // 第二次「启动」：必须是空跑
        var second = Flyway.configure().dataSource(meta.getDataSource()).locations("classpath:db/meta").load().migrate();
        secondStartupExecuted = second.migrationsExecuted;

        System.out.println("[P1-02] analytics_meta 首次启动执行脚本数=" + firstStartupExecuted
                + "，schemaVersion=" + firstStartupSchemaVersion
                + "；第二次启动执行脚本数=" + secondStartupExecuted);
    }

    @Test
    @DisplayName("重复启动是空跑，且 V16 在迁移历史中只有一行且成功")
    void repeatStartupIsANoOp() {
        assertThat(secondStartupExecuted)
                .as("同一脚本不允许被应用两次")
                .isZero();

        Map<String, Object> row = meta.queryForMap(
                "SELECT installed_rank, success, script FROM flyway_schema_history WHERE version = '16'");
        assertThat(row.get("script")).isEqualTo("V16__source_registry.sql");
        // 注意：MySQL Connector/J 把 TINYINT(1) 映射为 Boolean（不是 Number）
        assertThat(row.get("success")).as("迁移必须成功落历史").isEqualTo(Boolean.TRUE);

        Integer count = meta.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16'", Integer.class);
        assertThat(count).as("V16 只允许一条历史行").isEqualTo(1);

        Integer maxRank = meta.queryForObject("SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class);
        assertThat(maxRank).as("V16 应是最后应用的脚本").isEqualTo(((Number) row.get("installed_rank")).intValue());
    }

    @Test
    @DisplayName("真库 source_registry 列/类型/非空与种子源登记一致")
    void sourceRegistrySchemaMatchesFrozenContract() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> nullability = new LinkedHashMap<>();
        meta.query("SELECT column_name, column_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' ORDER BY ordinal_position",
                rs -> {
                    actual.put(rs.getString("column_name"), rs.getString("column_type"));
                    nullability.put(rs.getString("column_name"), rs.getString("is_nullable"));
                }, META_DB);

        assertThat(actual)
                .as("列集合必须与实施书 §3.1 逐字段一致（多列少列都算漂移）")
                .containsExactlyEntriesOf(EXPECTED_SOURCE_REGISTRY_TYPES);
        assertThat(nullability.values())
                .as("登记字段全部 NOT NULL（审计列由默认值填充）")
                .containsOnly("NO");

        String sourceCodeKey = meta.queryForObject(
                "SELECT column_key FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' AND column_name = 'source_code'",
                String.class, META_DB);
        assertThat(sourceCodeKey).as("source_code 是业务键：必须唯一").isEqualTo("UNI");

        String extra = meta.queryForObject(
                "SELECT extra FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' AND column_name = 'id'",
                String.class, META_DB);
        assertThat(extra).as("id 是自增代理键").contains("auto_increment");
    }

    @Test
    @DisplayName("种子源 mock-mall：ACTIVE / FILE / 仓库相对画像路径")
    void seedSourceIsRegisteredAsActiveReferenceMall() {
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT source_code, display_name, ingest_mode, profile_path, timezone, currency, status, profile_version "
                        + "FROM source_registry WHERE source_code = 'mock-mall'");

        assertThat(rows).as("种子源只允许一行").hasSize(1);
        Map<String, Object> seed = rows.get(0);
        assertThat(seed.get("ingest_mode")).isEqualTo("FILE");
        assertThat(seed.get("status")).isEqualTo("ACTIVE");
        assertThat(seed.get("profile_path")).isEqualTo("analytics-server/source-profiles/mock-mall.v1.json");
        assertThat(seed.get("timezone")).isEqualTo("Asia/Shanghai");
        assertThat(seed.get("currency")).isEqualTo("CNY");
        assertThat(String.valueOf(seed.get("display_name"))).isNotBlank();
        assertThat(String.valueOf(seed.get("profile_version"))).isNotBlank();
        System.out.println("[P1-02] 种子源=" + seed);
    }

    @Test
    @DisplayName("runtime_profile 只多出 source_id 一列（加性变更）")
    void runtimeProfileGainsOnlySourceIdColumn() {
        List<String> actual = meta.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'runtime_profile' ORDER BY ordinal_position",
                String.class, META_DB);

        List<String> expected = new java.util.ArrayList<>(FROZEN_RUNTIME_PROFILE_COLUMNS);
        expected.add("source_id");
        assertThat(actual)
                .as("迁移前 24 列必须原样保留，新增列只能是 source_id")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("存量 profile 已回填，source_id 可空但带外键指向 source_registry")
    void legacyProfilesAreBackfilledWithForeignKey() {
        Long seedId = meta.queryForObject(
                "SELECT id FROM source_registry WHERE source_code = 'mock-mall'", Long.class);

        List<Map<String, Object>> profiles = meta.queryForList(
                "SELECT id, profile_code, status, source_id FROM runtime_profile ORDER BY id");
        assertThat(profiles).as("存量 profile 不应被删除").isNotEmpty();
        assertThat(profiles)
                .as("回填后不应再有 source_id 为空的 profile：%s", profiles)
                .allSatisfy(row -> assertThat(row.get("source_id")).isEqualTo(seedId));

        String nullable = meta.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'runtime_profile' AND column_name = 'source_id'",
                String.class, META_DB);
        assertThat(nullable).as("兼容期允许为空（新登记源尚未绑定运行环境）").isEqualTo("YES");

        List<Map<String, Object>> fk = meta.queryForList(
                "SELECT k.constraint_name, k.referenced_table_name, k.referenced_column_name "
                        + "FROM information_schema.key_column_usage k "
                        + "WHERE k.table_schema = ? AND k.table_name = 'runtime_profile' "
                        + "AND k.column_name = 'source_id' AND k.referenced_table_name IS NOT NULL", META_DB);
        assertThat(fk).as("source_id 必须有外键指向 source_registry").hasSize(1);
        assertThat(fk.get(0).get("referenced_table_name")).isEqualTo("source_registry");
        assertThat(fk.get(0).get("referenced_column_name")).isEqualTo("id");

        System.out.println("[P1-02] 回填后 profile 行=" + profiles + "，seedId=" + seedId);
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/" + META_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        ds.setUsername("meta_app");
        ds.setPassword("meta_app_pw_2026");
        return ds;
    }
}
