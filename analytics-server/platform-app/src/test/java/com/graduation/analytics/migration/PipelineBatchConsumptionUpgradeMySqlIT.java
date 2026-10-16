package com.graduation.analytics.migration;

import com.graduation.analytics.testsupport.TestIsolationGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G31-12（总控 G31-11 复核第 1 项，D-050①）：V33「从已有 V32 数据正常升级」的真库夹具验证。
 * 默认跳过：需 {@code -Dp1.it=true} 且 surefire 显式指定本类（与 {@code *MySqlIT} 同一门禁模式）。
 *
 * <p>补的证据缺口：G31-11 现场是「正式库上 V33 首版 ODKU 回填触发 1567 → 人工置 success=1 +
 * 手工逐行回填（sql-repair2-backfill.sql，已归档）」——现场恢复可用，但<b>升级路径本身未闭合</b>。
 * 本类在 3307 隔离实例的本次运行专用空库上完整重放该路径：</p>
 * <ol>
 *   <li>Flyway 迁到 {@code target("32")}（= 升级前的既有库形态）；</li>
 *   <li>插入小型历史夹具——核心是 <b>「同批多次成功发布」</b>：批 24 有 r1/r2 两条 SUCCESS run
 *       （正是 1567 的触发条件），另带批 25 单条 SUCCESS、批 26 FAILED、批 NULL SUCCESS 各一
 *       （隔离回填 WHERE 三个条件的各自边界）；</li>
 *   <li>放开 target 完整迁移（V33 实际执行：建台账 + recalc_reason + INSERT IGNORE 回填）；</li>
 *   <li>断言台账：批 24 聚合为一行（publish_count=2、consumed_by=最新 run、
 *       first_consumed/consumed_at=最早 run、快照取最新 run 的 S20260901_23——与 G31-11 人工
 *       补偿口径逐字段一致），FAILED/无批次不入账；</li>
 *   <li>幂等：把回填语句从 classpath 的 V33 脚本原样提取出来重复执行两次，台账不变；</li>
 *   <li>不覆盖：预置 publish_count=99 的「既有行」（模拟人工补偿行/PIPELINE 行），再执行回填，
 *       既有行逐字段保持——INSERT IGNORE 撞键跳过（D-049f：保护锚不漂移）。</li>
 * </ol>
 *
 * <p><b>本类能证明什么、不能证明什么</b>：证明「已有 V32 数据 → V33 正常迁移」不再需要任何人工
 * 补偿，且与既有人工补偿行共存不覆盖。<b>不</b>触碰宿主 3306（永久冻结红线）；3307 正式
 * analytics_meta 的 checksum 重锚是独立运维动作，用本类打出的
 * {@code [G31-12][REANCHOR]} 行里的权威 checksum 值（本夹具库保留不删，供总控复查）。</p>
 */
@EnabledIfSystemProperty(named = "p1.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelineBatchConsumptionUpgradeMySqlIT {

    private static final String V33_SCRIPT = "V33__pipeline_batch_consumption.sql";

    /** 夹具用的合成 source_id（pipeline_run.source_id 无外键，见 V30；取值避开种子源 id 段）。 */
    private static final long SOURCE_ID = 900001L;

    private static final String META_DB = requiredProperty("p1.it.metaDb",
            "目标 meta 库名。无默认值：不得回退到正式库 analytics_meta（D-080 形态）。");

    private static final String METRIC_DB = requiredProperty("p1.it.metricDb",
            "context 登记用 metric 库名（本类不写该库）：隔离护栏要求双库登记且不得与 meta 库同名。");

    private static final String META_HOST = requiredProperty("p1.it.metaHost",
            "目标 MySQL 实例 host:port。无默认值：不得回退到宿主正式实例 3306。隔离实例应形如 127.0.0.1:3307");

    private static final String META_USER = requiredProperty("p1.it.metaUser",
            "执行账号。无默认值：不得回退到正式账号 meta_app；须为本次运行自己的受限账号。");

    private static final String META_PASSWORD = requiredProperty("p1.it.metaPassword",
            "执行账号口令。无默认值、仓库不落明文：请用系统属性或隔离档案提供。");

    private static final String TEST_RUN_ID = requiredProperty("p1.it.testRunId",
            "本次运行标识（testRunId）：证明目标是本次新建的隔离库。");

    private static final String SERVER_FINGERPRINT = requiredProperty("p1.it.serverFingerprint",
            "登记的目标实例指纹（hostname:port）：同库名换实例也必须拒绝。");

    private static JdbcTemplate meta;
    private static long profileId;
    private static long r1Id;
    private static long r2Id;
    private static long r3Id;

    private static String requiredProperty(String key, String purpose) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        final String bare = key.substring(key.lastIndexOf('.') + 1);
        try {
            return TestIsolationGuard.requiredProperty(bare);
        } catch (RuntimeException e) {
            throw new TestIsolationGuard.MissingConfigurationException("缺少测试隔离配置项 -D" + key
                    + "（" + purpose + "）：拒绝运行（不用正式账号/正式地址兜底）。档案兜底亦未命中："
                    + e.getMessage());
        }
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:mysql://" + META_HOST + "/" + META_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        ds.setUsername(META_USER);
        ds.setPassword(META_PASSWORD);
        return ds;
    }

    /** 平台侧共享 TestRunContext：登记值必须是本次运行自己的真实 scope（构造即校验，占位符会被拒）。 */
    private static TestIsolationGuard.TestRunContext context() {
        return new TestIsolationGuard.TestRunContext(
                TEST_RUN_ID, SERVER_FINGERPRINT, META_DB, METRIC_DB,
                requiredProperty("p1.it.hiveNamespace", "隔离 Hive namespace（登记用，本类不访问）"),
                requiredProperty("p1.it.hdfsRoot", "隔离 HDFS 根（登记用，本类不访问）"),
                requiredProperty("p1.it.manifestRoot", "隔离 manifest 根（登记用，本类不访问）"),
                "credref:" + META_USER,
                System.currentTimeMillis(),
                Path.of("p1.it.properties"));
    }

    @BeforeAll
    static void upgradeFromV32WithFixture() {
        DataSource ds = dataSource();
        meta = new JdbcTemplate(ds);
        // 写前门禁在任何 DDL/DML 之前（G31-09 收紧后的同款调用点）
        TestIsolationGuard.verifyBeforeWrite(context(), ds, META_DB);

        // 干净起点（幂等重跑）：只清本次运行自己的隔离库，DROP 范围以 information_schema 实查为界。
        // FOREIGN_KEY_CHECKS 是会话级变量，而 JdbcTemplate 每次执行都可能拿新连接，
        // 因此关闭 FK 的 SET 与全部 DROP 必须走同一条连接（否则 FK 约束会拦 DROP）。
        List<String> tables = meta.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, META_DB);
        try (java.sql.Connection con = ds.getConnection();
             java.sql.Statement st = con.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            st.execute("SET FOREIGN_KEY_CHECKS=1");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("隔离库清空失败（仅限本次 testRunId 自己的库）：" + META_DB, e);
        }

        // 第 1 步：把库升到 V32（= 正式库升级前的形态）
        Flyway.configure().dataSource(ds).locations("classpath:db/meta").target("32").load().migrate();
        assertHistoryApplied("32", "升级前");
        assertThat(historyVersions()).as("升级前不得已存在 V33 记录").doesNotContain("33");

        // 第 2 步：历史夹具（运行时种子 profile；同批多次成功发布 = 批 24 两条 SUCCESS run）
        List<Long> profiles = meta.queryForList(
                "SELECT id FROM runtime_profile WHERE profile_code = 'local-dev' ORDER BY id", Long.class);
        assertThat(profiles).as("V32 迁移后的 runtime_profile 应含种子 'local-dev'").isNotEmpty();
        profileId = profiles.get(0);
        // r1/r2：同批多次成功发布（批 24）——首版 ODKU 在此形态下语句内自撞（1567 根因）
        r1Id = insertRun("r1", "SUCCESS", null,
                Timestamp.valueOf("2026-09-01 20:00:00.000"), Timestamp.valueOf("2026-09-01 20:10:00.000"),
                24L, "S20260901_20");
        r2Id = insertRun("r2", "SUCCESS", null,
                Timestamp.valueOf("2026-09-01 23:00:00.000"), Timestamp.valueOf("2026-09-01 23:10:00.000"),
                24L, "S20260901_23");
        // r3：批 25 单条 SUCCESS（常规一回填一行）
        r3Id = insertRun("r3", "SUCCESS", null,
                Timestamp.valueOf("2026-09-02 20:00:00.000"), Timestamp.valueOf("2026-09-02 20:10:00.000"),
                25L, "S20260901_25");
        // r4：FAILED 且批次明确（status 条件的边界：不入账）
        insertRun("r4", "FAILED", "RUN_LANDING_MISMATCH",
                Timestamp.valueOf("2026-09-03 20:00:00.000"), null,
                26L, null);
        // r5：SUCCESS 且源明确但无输入批次（input_batch_id 条件的边界：不入账）
        insertRun("r5", "SUCCESS", null,
                Timestamp.valueOf("2026-09-04 20:00:00.000"), Timestamp.valueOf("2026-09-04 20:10:00.000"),
                null, null);

        // 第 3 步：完整迁移（V33 实际执行）
        Flyway.configure().dataSource(ds).locations("classpath:db/meta").load().migrate();
        assertHistoryApplied("33", "升级后");
    }

    @Test
    @Order(1)
    @DisplayName("V32→V33 升级：同批多次成功发布聚合一行，字段与人工补偿口径一致，FAILED/无批次不入账")
    void upgradeBackfillsLedgerWithSameBatchMultiPublishAggregated() {
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT * FROM pipeline_batch_consumption ORDER BY batch_id");
        assertThat(rows).as("夹具（批 24 两条 SUCCESS + 批 25 一条 + FAILED + 无批次）应恰好回填两行")
                .hasSize(2);

        Map<String, Object> row24 = rows.get(0);
        assertThat(((Number) row24.get("batch_id")).longValue()).isEqualTo(24L);
        assertThat(String.valueOf(row24.get("status"))).isEqualTo("CONSUMED");
        assertThat(((Number) row24.get("publish_count")).intValue())
                .as("同批多次成功发布：publish_count=COUNT(*)=2（与 G31-11 人工补偿批 24 口径一致）")
                .isEqualTo(2);
        assertThat(((Number) row24.get("consumed_by_run_id")).longValue())
                .as("consumed_by 取最新 run（r2）")
                .isEqualTo(r2Id);
        assertThat(((Number) row24.get("first_consumed_by_run_id")).longValue())
                .as("first_consumed 取最早 run（r1）")
                .isEqualTo(r1Id);
        assertThat(String.valueOf(row24.get("target_snapshot_id")))
                .as("快照取最新 run 的产出（S20260901_23 = 正式锚同款语义）")
                .isEqualTo("S20260901_23");
        assertThat(((LocalDateTime) row24.get("consumed_at")))
                .as("consumed_at 取最早 run 的 finished_at（缺省回退 updated_at，夹具走 finished_at）")
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 20, 10, 0, 0));
        assertThat(String.valueOf(row24.get("created_via"))).isEqualTo("BACKFILL_V33");
        assertThat(((Number) row24.get("recalc_count")).intValue()).isZero();
        assertThat(row24.get("last_recalc_reason")).isNull();
        assertThat(row24.get("last_recalc_by")).isNull();
        assertThat(row24.get("last_recalc_at")).isNull();

        Map<String, Object> row25 = rows.get(1);
        assertThat(((Number) row25.get("batch_id")).longValue()).isEqualTo(25L);
        assertThat(((Number) row25.get("publish_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row25.get("consumed_by_run_id")).longValue()).isEqualTo(r3Id);
        assertThat(((Number) row25.get("first_consumed_by_run_id")).longValue()).isEqualTo(r3Id);
        assertThat(String.valueOf(row25.get("target_snapshot_id"))).isEqualTo("S20260901_25");

        assertThat(ledgerRow(26L)).as("FAILED run（r4）不入账：发布成功才落行").isNull();

        Map<String, Object> recalcColumn = meta.queryForMap(
                "SELECT column_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'pipeline_run' AND column_name = 'recalc_reason'",
                META_DB);
        assertThat(String.valueOf(recalcColumn.get("COLUMN_TYPE")).toLowerCase())
                .as("recalc_reason 必须是 VARCHAR(500) NULL（D-049e：理由必填是应用层 400 契约）")
                .isEqualTo("varchar(500)");
        assertThat(String.valueOf(recalcColumn.get("IS_NULLABLE"))).isEqualTo("YES");
        Integer recalcReasonSet = meta.queryForObject(
                "SELECT COUNT(*) FROM pipeline_run WHERE recalc_reason IS NOT NULL", Integer.class);
        assertThat(recalcReasonSet).as("夹具 run 均非重算，recalc_reason 全 NULL").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("幂等：回填语句重复执行两次，台账行数与逐字段值不变")
    void backfillReexecutionIsIdempotent() {
        String backfill = extractedBackfillStatement();
        meta.execute(backfill);
        meta.execute(backfill);

        assertThat(countLedgerRows()).as("重复执行不得新增行").isEqualTo(2);
        Map<String, Object> row24 = ledgerRow(24L);
        assertThat(((Number) row24.get("publish_count")).intValue()).isEqualTo(2);
        assertThat(((Number) row24.get("consumed_by_run_id")).longValue()).isEqualTo(r2Id);
        assertThat(((LocalDateTime) row24.get("consumed_at")))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 20, 10, 0, 0));
    }

    @Test
    @Order(3)
    @DisplayName("不覆盖：预置「既有行」（publish_count=99 + 独有快照标记）后回填撞键跳过，既有行逐字段保持")
    void backfillDoesNotOverwritePreexistingRow() {
        meta.update("UPDATE pipeline_batch_consumption SET publish_count = 99, "
                + "target_snapshot_id = 'MUTATED_SHOULD_SURVIVE' WHERE source_id = ? AND batch_id = ?",
                SOURCE_ID, 24L);

        meta.execute(extractedBackfillStatement());

        Map<String, Object> row24 = ledgerRow(24L);
        assertThat(((Number) row24.get("publish_count")).intValue())
                .as("INSERT IGNORE 撞既有行（人工补偿行/PIPELINE 行）必须跳过，绝不改写（D-049f 保护锚）")
                .isEqualTo(99);
        assertThat(String.valueOf(row24.get("target_snapshot_id"))).isEqualTo("MUTATED_SHOULD_SURVIVE");
        assertThat(countLedgerRows()).isEqualTo(2);
    }

    @AfterAll
    static void printReAnchorFacts() {
        if (meta == null) {
            return;
        }
        List<Map<String, Object>> history = meta.queryForList(
                "SELECT checksum, success FROM flyway_schema_history WHERE version = '33'");
        Object checksum = history.isEmpty() ? null : history.get(0).get("checksum");
        System.out.println("[G31-12][REANCHOR] V33 flyway_schema_history checksum=" + checksum
                + " success=" + (history.isEmpty() ? "?" : history.get(0).get("success"))
                + " schema=" + META_DB + "@" + META_HOST
                + "（3307 正式 analytics_meta 重锚：UPDATE flyway_schema_history SET checksum=<此值> "
                + "WHERE version='33'；本夹具库保留不删供复查）");
        System.out.println("[G31-12][REANCHOR] " + context().redactedSummary());
    }

    private static long insertRun(String suffix, String status, String errorCode, Timestamp businessTime,
                                  Timestamp finishedAt, Long inputBatchId, String snapshotId) {
        String key = TEST_RUN_ID + "-" + suffix;
        meta.update("INSERT INTO pipeline_run (idempotency_key, runtime_profile_id, pipeline_code, "
                        + "business_time, source_data_version, status, error_code, trace_id, finished_at, "
                        + "input_batch_id, target_snapshot_id, source_id) "
                        + "VALUES (?, ?, 'DAILY_CORE', ?, NULL, ?, ?, ?, ?, ?, ?, ?)",
                key, profileId, businessTime, status, errorCode, key + "-trace", finishedAt,
                inputBatchId, snapshotId, SOURCE_ID);
        return meta.queryForObject("SELECT id FROM pipeline_run WHERE idempotency_key = ?",
                Long.class, key);
    }

    private static Map<String, Object> ledgerRow(long batchId) {
        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT * FROM pipeline_batch_consumption WHERE source_id = ? AND batch_id = ?",
                SOURCE_ID, batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int countLedgerRows() {
        return meta.queryForObject("SELECT COUNT(*) FROM pipeline_batch_consumption", Integer.class);
    }

    private static List<String> historyVersions() {
        return meta.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);
    }

    private static void assertHistoryApplied(String version, String phase) {
        assertThat(historyVersions()).as("%s迁移历史应含 V%s", phase, version).contains(version);
    }

    /**
     * 从 classpath 的 V33 脚本原样提取**活的**回填语句（剥掉 {@code --} 注释行后取唯一的
     * INSERT IGNORE INTO pipeline_batch_consumption 语句）。首版 ODKU 触发语句只存在于注释里，
     * 剥注释后不可能被匹配到——提取到的必然是 G31-12 重构后的活语句。
     */
    private static String extractedBackfillStatement() {
        try (InputStream in = PipelineBatchConsumptionUpgradeMySqlIT.class
                .getResourceAsStream("/db/meta/" + V33_SCRIPT)) {
            assertThat(in).as("classpath:db/meta/%s 必须存在", V33_SCRIPT).isNotNull();
            StringBuilder code = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (line.trim().startsWith("--")) {
                    continue;
                }
                code.append(line).append('\n');
            }
            Matcher m = Pattern.compile(
                    "(?is)insert\\s+ignore\\s+into\\s+pipeline_batch_consumption\\b.*?;")
                    .matcher(code.toString());
            assertThat(m.find()).as("V33 活体回填语句（INSERT IGNORE …）应存在").isTrue();
            return m.group();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
