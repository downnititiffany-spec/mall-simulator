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
 *
 * <p><b>P1-05（V17）的真库取证状态</b>：本类自 P1-05 起把 V17 纳入 {@code EXPECTED_META_SCRIPTS}。
 * 但真库 {@code analytics_meta} 在 P1-05 交付时仍是 V16（V17 由总控在其"同批次换 jar"窗口应用）,
 * 因此**本类无法在 P1-05 这一轮对真库通过**：一旦在本类里连真库，{@link #twoApplicationStartups()}
 * 就会把真库迁移到 V17，那属于越界写库。本轮的替代证据是
 * {@code docs/acceptance/p1-05-manifest-source-20260911/}：在同一 schema 的**临时副本库**上
 * 用最新代码启动，由 {@code MetaFlywayInitializer} 实际应用 V17，并留下迁移历史原样输出；
 * 并且本类本身也被 {@code -Dp1.it.metaDb=analytics_meta_p105it} 指向另一份"真库 dump 的副本库"
 * **实跑通过**（见该目录 {@code e3-20-migration-it-on-replica.log}）。真库上的本类运行留给总控换 jar 之后。</p>
 *
 * <p><b>P2-07（V18）的真库取证状态</b>：{@code EXPECTED_META_SCRIPTS} 自本轮起含 V18（源级
 * {@code warehouse_prefix}），且 {@link #warehousePrefixIsBackfilledNotNullWithoutDefault()} 断言
 * "NOT NULL、无默认值、存量行全部回填、旧列仍在"。与 P1-05 同理，**本轮不在真库跑**：真库
 * {@code analytics_meta} 仍是 V17，连上去就会把它迁到 V18（越界写库，D-080）。本轮的替代证据是
 * "真库只读转储 → 副本库 → 由 Flyway 实际应用 V18"的日志（见
 * {@code docs/acceptance/p2-07-source-prefix-20260912/}）。</p>
 */
@EnabledIfSystemProperty(named = "p1.it", matches = "true")
class SourceRegistryMigrationMySqlIT {

    /**
     * 目标库名。默认就是真库 {@code analytics_meta}（本类的语义不变：跑的仍是"真 MySQL 8.0 上的迁移链"）。
     *
     * <p>允许用 {@code -Dp1.it.metaDb=...} 指向一个**从真库 dump 出来的字节等价副本库**：P1-05 的 V17
     * 在真库上属越界写（真库仍是 V16，由总控在同批次换 jar 时才应用），但"本类在 V16→V17 的库上到底
     * 绿不绿"不能靠推理。副本库跑通的是同一份脚本链、同一份存量数据，因此是本轮能拿到的最强证据；
     * 真库上的本类运行仍留给总控换 jar 之后。凭证同理，默认值不写库外账号。</p>
     */
    private static final String META_DB = System.getProperty("p1.it.metaDb", "analytics_meta");

    /**
     * meta 库应当存在的迁移脚本清单（P1-05 起含 V17，D-037）。
     *
     * <p>写成**显式清单**而不是"最后一个是 V16"：清单能把"少了哪个脚本"和"多了没登记的脚本"
     * 都变成红，而"最后版本号"只能发现前者。历史脚本行的 checksum 由 Flyway 自己校验，
     * 本类不复制 checksum（复制了就变成两处所有者）。</p>
     */
    private static final List<String> EXPECTED_META_SCRIPTS = List.of(
            "V1__platform_ingestion.sql",
            "V2__platform_pipeline_quality.sql",
            "V3__platform_ai_audit.sql",
            "V4__platform_decisions.sql",
            "V5__platform_users.sql",
            "V7__platform_runtime_profile.sql",
            "V8__platform_ingestion_r3.sql",
            "V9__platform_stage_evidence_widen.sql",
            "V10__spark_job_run_output_partitions.sql",
            "V11__data_quality_layers.sql",
            "V12__quality_detail_width.sql",
            "V13__metric_definition_r7.sql",
            "V14__r8_identity_decision.sql",
            "V15__stage_evidence_mediumtext.sql",
            "V16__source_registry.sql",
            "V17__source_dimension_for_checkpoint_and_batch.sql",
            "V18__source_warehouse_prefix.sql");

    private static final String V17_SCRIPT = "V17__source_dimension_for_checkpoint_and_batch.sql";
    private static final String V18_SCRIPT = "V18__source_warehouse_prefix.sql";


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
        // P2-07（V18）：源级数仓命名空间前缀。宽度 24 = 契约 v2 rule.prefixPattern ^[a-z][a-z0-9_]{0,23}$ 的上界
        EXPECTED_SOURCE_REGISTRY_TYPES.put("warehouse_prefix", "varchar(24)");
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
    @DisplayName("P2-07：迁移历史含已登记的全部脚本（含 V16/V17/V18），全部成功、无失败行")
    void migrationHistoryContainsAllRegisteredScriptsAndNoFailedRuns() {
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT installed_rank, version, script, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).as("历史行数应等于脚本数（无重复、无中途失败重跑残留）")
                .hasSize(EXPECTED_META_SCRIPTS.size());
        assertThat(rows.stream().map(r -> String.valueOf(r.get("script"))).toList())
                .as("脚本清单必须逐条一致：少一个（没跑到）或多一个（没登记）都算漂移")
                .containsExactlyElementsOf(EXPECTED_META_SCRIPTS);
        assertThat(rows.stream().map(r -> r.get("success")).distinct().toList())
                .as("不允许存在 success=0 的迁移行")
                .containsExactly(Boolean.TRUE);

        // version 列必须与脚本名里的号一致：清单与历史两处对不上就是漂移
        assertThat(rows.stream().map(r -> String.valueOf(r.get("version"))).toList())
                .as("version 列必须由脚本名推导，不允许手写号")
                .containsExactlyElementsOf(EXPECTED_META_SCRIPTS.stream()
                        .map(name -> name.substring(1, name.indexOf("__")))
                        .toList());

        // 清单最后一项（本轮 = V18）必须是最后应用的脚本 —— "新迁移只能往后加"的可判据形式。
        // 原 P1-05 版本把"最后"硬写成 V17，加 V18 后必然失败，故改为从清单推导。
        String newest = EXPECTED_META_SCRIPTS.get(EXPECTED_META_SCRIPTS.size() - 1);
        Map<String, Object> last = rows.get(rows.size() - 1);
        assertThat(newest).as("本轮新增脚本即 V18").isEqualTo(V18_SCRIPT);
        assertThat(String.valueOf(last.get("script")))
                .as("清单最后一项必须就是最后应用的脚本")
                .isEqualTo(newest);
        assertThat(String.valueOf(last.get("version"))).as("V18 的 version 列").isEqualTo("18");

        Map<String, Object> v17 = rows.stream()
                .filter(r -> V17_SCRIPT.equals(String.valueOf(r.get("script"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V17 未落在迁移历史里：" + EXPECTED_META_SCRIPTS));
        assertThat(String.valueOf(v17.get("version"))).as("V17 的 version 列").isEqualTo("17");
        assertThat(((Number) v17.get("installed_rank")).intValue())
                .as("V17 必须排在 V18 之前")
                .isLessThan(((Number) last.get("installed_rank")).intValue());

        System.out.println("[P2-07] 迁移历史脚本清单=" + rows.stream().map(r -> r.get("script")).toList());
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

        // P1-05（V17，D-037）之后 V16 不再是最后应用的脚本，故这里不再断言 maxRank == V16 的 rank，
        // 改为断言 V16 在历史中的**相对次序**（V16 之前的所有号都排在它前面）——这正是 P1-02 当时要的
        // 性质，且不会在后续迁移加号时变红（原断言 "V16 应是最后应用的脚本" 会在 V17 落地后必然失败）。
        Integer laterRank = meta.queryForObject(
                "SELECT MIN(installed_rank) FROM flyway_schema_history WHERE version <> '16' "
                        + "AND CAST(version AS UNSIGNED) > 16", Integer.class);
        if (laterRank != null) {
            assertThat(((Number) row.get("installed_rank")).intValue())
                    .as("比 V16 号大的脚本必须排在 V16 之后")
                    .isLessThan(laterRank);
        }
        Integer earlierRank = meta.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history WHERE version <> '16' "
                        + "AND CAST(version AS UNSIGNED) < 16", Integer.class);
        if (earlierRank != null) {
            assertThat(((Number) row.get("installed_rank")).intValue())
                    .as("比 V16 号小的脚本必须排在 V16 之前")
                    .isGreaterThan(earlierRank);
        }
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

    @Test
    @DisplayName("P2-07（V18）：warehouse_prefix 为 NOT NULL 无默认值、存量行已回填 'dw'，旧列仍在")
    void warehousePrefixIsBackfilledNotNullWithoutDefault() {
        String type = meta.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' AND column_name = 'warehouse_prefix'",
                String.class, META_DB);
        assertThat(type).as("列宽必须等于契约 v2 上界 24").isEqualTo("varchar(24)");

        String nullable = meta.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' AND column_name = 'warehouse_prefix'",
                String.class, META_DB);
        assertThat(nullable).as("V18 第三步必须收紧为 NOT NULL").isEqualTo("NO");

        String defaultValue = meta.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'source_registry' AND column_name = 'warehouse_prefix'",
                String.class, META_DB);
        assertThat(defaultValue)
                .as("D-071：不得有默认值 —— 有默认值就会让忘填前缀的源静默落进 dw_*")
                .isNull();

        Integer blank = meta.queryForObject(
                "SELECT COUNT(*) FROM source_registry WHERE warehouse_prefix IS NULL OR warehouse_prefix = ''",
                Integer.class);
        assertThat(blank).as("回填必须覆盖全部存量行").isZero();

        String seedPrefix = meta.queryForObject(
                "SELECT warehouse_prefix FROM source_registry WHERE source_code = 'mock-mall'", String.class);
        assertThat(seedPrefix)
                .as("零迁移等价：种子源回填值与今日解析结果逐字相同（dw）")
                .isEqualTo("dw");

        // D-073：本轮只断读不删列 —— 旧列必须还在（删列是另一个任务的破坏性 DDL）
        Integer legacy = meta.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'runtime_profile' "
                        + "AND column_name = 'hive_database_prefix'", Integer.class, META_DB);
        assertThat(legacy).as("V18 不得删除 runtime_profile.hive_database_prefix").isEqualTo(1);

        System.out.println("[P2-07] warehouse_prefix: type=" + type + ", nullable=" + nullable
                + ", default=" + defaultValue + "；种子源前缀=" + seedPrefix);
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/" + META_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        ds.setUsername(System.getProperty("p1.it.metaUser", "meta_app"));
        ds.setPassword(System.getProperty("p1.it.metaPassword", "meta_app_pw_2026"));
        return ds;
    }
}
