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
 *
 * <p><b>S2-03.1（V19/V20/V21）</b>：{@code EXPECTED_META_SCRIPTS} 此前停在 V18，而 {@code db/meta}
 * 已有 V19/V20/V21 —— 即"清单最后一项必须就是最后应用的脚本"这一断言早已与代码事实不符
 * （任何一次真库运行都会红）。本轮把三个脚本补进清单，末端断言从 V18 改为 V21。
 * 与 P1-05/P2-07 同理，**本轮仍不在真库 analytics_meta 上跑**（连上去就会把正式库迁到 V21，越界写库）：
 * 本类的真库取证仍须指向副本/临时库，命令见类注释与《开发过程事实与决策记录》F-29。</p>
 */
@EnabledIfSystemProperty(named = "p1.it", matches = "true")
class SourceRegistryMigrationMySqlIT {

    /**
     * 目标库名。**没有默认值**——V25-S03 R-3 整改点。
     *
     * <p>整改前这里是 {@code System.getProperty("p1.it.metaDb", "analytics_meta")}：默认值就是
     * **正式库** {@code analytics_meta}。而本类在 {@link #twoApplicationStartups()} 里跑
     * {@code Flyway.migrate()} 两次（属 DDL 写操作）。也就是说，只要有人敲
     * {@code -Dp1.it=true} 而**忘了**带 {@code -Dp1.it.metaDb=...}，就会把正式
     * {@code analytics_meta} 迁移到当前代码版本——这正是 D-080 记录的越界写库形态。</p>
     *
     * <p>整改后：缺配置 → **拒跑**（抛异常），不存在回退到正式库的路径。
     * 取值仍沿用 {@code p1.it.metaDb} 键，以保持既有验收命令与历史证据可复现。</p>
     */
    private static final String META_DB = requiredProperty("p1.it.metaDb",
            "目标 meta 库名。整改后无默认值：不得回退到正式库 analytics_meta。"
                    + "请显式指定一份从真库 dump 出来的副本库，例如 "
                    + "-Dp1.it.metaDb=analytics_meta_<runId>it");

    /** 目标实例主机:端口。**没有默认值**：默认 3306 意味着宿主正式实例。 */
    private static final String META_HOST = requiredProperty("p1.it.metaHost",
            "目标 MySQL 实例 host:port。整改后无默认值：不得回退到宿主正式实例 3306。"
                    + "隔离实例应形如 127.0.0.1:3307");

    /** 执行账号。**没有默认值**：整改前默认 {@code meta_app}（正式账号）。 */
    private static final String META_USER = requiredProperty("p1.it.metaUser",
            "执行账号。整改后无默认值：不得回退到正式账号 meta_app。");

    /**
     * 执行账号口令。**没有默认值、且仓库内不落明文**。
     *
     * <p>整改前源码里直接写着 {@code "meta_app_pw_2026"}。整改后口令必须由外部提供
     * （系统属性或隔离档案），取值支持 {@code credref:<id>} 引用形式。</p>
     */
    private static final String META_PASSWORD = requiredProperty("p1.it.metaPassword",
            "执行账号口令。整改后无默认值、仓库不落明文：请用系统属性或隔离档案提供"
                    + "（可写 credref:<id> 引用）");

    /** 本次运行标识：用于「库名必须属于本次运行」与证据可复查。 */
    private static final String TEST_RUN_ID = requiredProperty("p1.it.testRunId",
            "本次运行标识（testRunId）：整改后无默认值，用于证明目标是本次新建的副本库。");

    /** 登记的目标实例指纹（@@hostname 或 host:port）：同库名换实例也必须拒绝。 */
    private static final String SERVER_FINGERPRINT = requiredProperty("p1.it.serverFingerprint",
            "登记的目标实例指纹（@@hostname 或 host:port）：整改后无默认值。");

    /**
     * 读取必填配置项：系统属性 → 隔离档案 → **抛异常拒跑**。
     *
     * <p>与 V25-S01/V25-S02 的两个 {@code *MySqlIT} 同一门禁模式，唯一区别是键前缀沿用
     * {@code p1.it.*}（保持既有验收命令），并委托平台侧共享的
     * {@link com.graduation.analytics.testsupport.TestIsolationGuard#requiredProperty(String)}
     * 做「档案兜底 + 拒绝文案」——避免这里再长出第二套解析逻辑。</p>
     */
    private static String requiredProperty(String key, String purpose) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        // 档案兜底：V25-S02 的隔离档案（v25.it.* / integration.local.properties）
        final String bare = key.substring(key.lastIndexOf('.') + 1);
        try {
            return com.graduation.analytics.testsupport.TestIsolationGuard.requiredProperty(bare);
        } catch (RuntimeException e) {
            throw new com.graduation.analytics.testsupport.TestIsolationGuard
                    .MissingConfigurationException("缺少测试隔离配置项 -D" + key + "（" + purpose
                    + "）：拒绝运行（不用正式账号/正式地址兜底）。档案兜底亦未命中：" + e.getMessage());
        }
    }

    /**
     * 写前门禁：在任何 DDL/DML 之前核对**真实连接**。
     *
     * <p>与两个 {@code *MySqlIT} 同一模式：{@code SELECT DATABASE()} 必须等于声明目标库、
     * 实例端口必须命中隔离白名单（3307）、账号不得是 root/正式写账号、实例指纹必须匹配。</p>
     *
     * <p>注意调用点：必须在 {@code Flyway.migrate()} <b>之前</b>。整改前本类没有任何写前校验，
     * V17/V18 迁移一旦连上正式库就会先改表再断言。</p>
     */
    private static void verifyBeforeWrite(DataSource ds, String database) {
        com.graduation.analytics.testsupport.TestIsolationGuard.verifyBeforeWrite(
                context(), ds, database);
    }

    /** 由 {@code p1.it.*} 构造平台侧共享的 {@link com.graduation.analytics.testsupport.TestRunContext}。 */
    private static com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext context() {
        return new com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext(
                TEST_RUN_ID, SERVER_FINGERPRINT, META_DB, META_DB,
                System.getProperty("p1.it.hiveNamespace", "unused-no-hive"),
                System.getProperty("p1.it.hdfsRoot", "unused-no-hdfs"),
                System.getProperty("p1.it.manifestRoot", "unused-no-manifest"),
                "credref:" + META_USER,
                System.currentTimeMillis(),
                java.nio.file.Path.of("p1.it.properties"));
    }

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
            "V18__source_warehouse_prefix.sql",
            "V19__quality_rule_definition.sql",
            "V20__data_quality_result_rule_version.sql",
            "V21__source_mapping_active.sql");

    private static final String V17_SCRIPT = "V17__source_dimension_for_checkpoint_and_batch.sql";
    private static final String V18_SCRIPT = "V18__source_warehouse_prefix.sql";
    private static final String V21_SCRIPT = "V21__source_mapping_active.sql";


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
        // V25-S03 R-3：写前门禁必须在第一次 Flyway.migrate() 之前。
        // 整改前本类直接在正式库上跑 migrate（V17/V18 是 DDL），连错库就先改表再断言。
        verifyBeforeWrite(meta.getDataSource(), META_DB);
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
    @DisplayName("P2-07/S2-03.1：迁移历史含已登记的全部脚本（含 V16/V17/V18/V19/V20/V21），全部成功、无失败行")
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

        // 清单最后一项（本轮 = V21）必须是最后应用的脚本 —— "新迁移只能往后加"的可判据形式。
        // 原 P1-05 版本把"最后"硬写成 V17，加 V18 后必然失败，故改为从清单推导。
        String newest = EXPECTED_META_SCRIPTS.get(EXPECTED_META_SCRIPTS.size() - 1);
        Map<String, Object> last = rows.get(rows.size() - 1);
        assertThat(newest).as("S2-03.1 起清单末端即 V21").isEqualTo(V21_SCRIPT);
        assertThat(String.valueOf(last.get("script")))
                .as("清单最后一项必须就是最后应用的脚本")
                .isEqualTo(newest);
        assertThat(String.valueOf(last.get("version"))).as("V21 的 version 列").isEqualTo("21");

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
        // V25-S03 R-3：主机:端口与口令都不再有默认值（整改前分别是 3306 与明文 meta_app_pw_2026）
        ds.setUrl("jdbc:mysql://" + META_HOST + "/" + META_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        ds.setUsername(META_USER);
        ds.setPassword(META_PASSWORD);
        return ds;
    }
}
