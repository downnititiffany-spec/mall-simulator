package com.graduation.analytics.mapping;

import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import com.graduation.analytics.testsupport.TestIsolationGuard;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-03.1 真库验证（默认跳过：需 {@code -Dp1.it=true} 且 surefire 显式指定本类）。
 *
 * <p><b>验证对象是真 MySQL 8 上的一份**副本/临时库**（绝不含正式 {@code analytics_meta}）</b>：
 * 由 Flyway 把 {@code classpath:db/meta} 迁到 V21（含新建 {@code source_mapping_active}），
 * 然后用真实 JDBC 执行 **mapper 注解里的 SQL 原文**，断言表结构、一源一行的覆盖语义、
 * {@code created_at} 不被替换抹掉、以及 {@code JOIN source_registry} 真能取回 {@code source_code}。</p>
 *
 * <p><b>本 IT 不证明什么</b>（如实登记）：① MyBatis 的参数绑定与结果集映射——这里把
 * {@code #{sourceId}} 换成 JDBC {@code ?} 后直接执行，证明的是"SQL 在真库可执行且语义正确"，
 * 框架那一层由 {@code JdbcActiveMappingPointerStoreTest} 的接口替身覆盖；② 并发下 {@code FOR UPDATE}
 * 的真实互斥时长——只断言锁定读语句在本行的真实事务里可执行且读到已提交状态，
 * "两个并发 activate 只产生一次写入"仍属**未测**，登记在案。</p>
 *
 * <p><b>写前门禁</b>：与 {@code SourceRegistryMigrationMySqlIT} 同一道
 * {@link TestIsolationGuard#verifyBeforeWrite}，必须早于任何 DDL/DML：端口必须命中隔离白名单、
 * 账号不得是 root/正式账号、库名必须带本次 runId、实例指纹必须匹配。**没有默认值**，
 * 缺配置一律拒跑（不存在回退到 3306 正式实例的路径）。</p>
 *
 * <p><b>本轮取证状态</b>：本类在本轮**未运行**（本机没有可用的隔离实例与凭据）⇒ 结论为
 * **未测/IT-gated**，不得据此宣称"真库已验证"。运行方法与所需隔离配置见
 * {@code docs/status-history/开发过程事实与决策记录.md} F-29。</p>
 */
@EnabledIfSystemProperty(named = "p1.it", matches = "true")
class SourceMappingActiveMySqlIT {

    private static final String TABLE = "source_mapping_active";

    private static final String META_DB = requiredProperty("metaDb",
            "目标 meta 库名（不得回退到正式库 analytics_meta）");
    private static final String META_HOST = requiredProperty("metaHost",
            "目标 MySQL 实例 host:port（隔离实例，形如 127.0.0.1:3307）");
    private static final String META_USER = requiredProperty("metaUser", "执行账号（不得是 root/正式账号）");
    private static final String META_PASSWORD = requiredProperty("metaPassword", "执行账号口令（不落盘）");
    private static final String TEST_RUN_ID = requiredProperty("testRunId", "本次运行标识（库名必须带此前缀）");
    private static final String SERVER_FINGERPRINT = requiredProperty("serverFingerprint",
            "登记的目标实例指纹（hostname:port）");

    private static JdbcTemplate meta;
    private static long sourceId;
    private static String sourceCode;

    @BeforeAll
    static void migrateScratchReplicaToV21() {
        DataSource ds = dataSource();
        meta = new JdbcTemplate(ds);
        // 写前门禁必须在 Flyway.migrate()（DDL）之前
        TestIsolationGuard.verifyBeforeWrite(context(), ds, META_DB);

        var result = Flyway.configure().dataSource(ds).locations("classpath:db/meta").load().migrate();
        System.out.println("[S2-03.1] 副本库 " + META_DB + " 迁移执行脚本数=" + result.migrationsExecuted
                + "，schemaVersion=" + result.targetSchemaVersion);

        // 借一个已登记的源来满足外键，并断言 JOIN 目标存在
        List<Map<String, Object>> sources = meta.queryForList(
                "SELECT id, source_code FROM source_registry ORDER BY id LIMIT 1");
        assertThat(sources).as("副本库必须已有至少一个登记源（外键目标）").isNotEmpty();
        sourceId = ((Number) sources.get(0).get("id")).longValue();
        sourceCode = String.valueOf(sources.get(0).get("source_code"));
    }

    @BeforeEach
    void cleanPointerRow() {
        meta.update("DELETE FROM " + TABLE + " WHERE source_id = ?", sourceId);
    }

    @Test
    @DisplayName("V21 落在迁移历史里，且 source_mapping_active 的列/主键/外键与冻结契约一致")
    void tableShapeMatchesFrozenContract() {
        List<Map<String, Object>> history = meta.queryForList(
                "SELECT script, success FROM flyway_schema_history WHERE version = '21'");
        assertThat(history).as("V21 必须恰好一行历史").hasSize(1);
        assertThat(history.get(0).get("script")).isEqualTo("V21__source_mapping_active.sql");
        assertThat(history.get(0).get("success")).isEqualTo(Boolean.TRUE);

        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> nullability = new LinkedHashMap<>();
        meta.query("SELECT column_name, column_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                rs -> {
                    types.put(rs.getString("column_name"), rs.getString("column_type"));
                    nullability.put(rs.getString("column_name"), rs.getString("is_nullable"));
                }, META_DB, TABLE);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("source_id", "bigint");
        expected.put("profile_ref", "varchar(255)");
        expected.put("profile_version", "varchar(32)");
        expected.put("profile_checksum", "char(64)");
        expected.put("contract_version", "varchar(32)");
        expected.put("contract_checksum", "char(64)");
        expected.put("report_id", "varchar(64)");
        expected.put("activated_at", "datetime(3)");
        expected.put("activated_by", "varchar(64)");
        expected.put("created_at", "datetime(3)");
        expected.put("updated_at", "datetime(3)");
        assertThat(types).as("列集合与类型必须逐字段一致（多列少列都算漂移）")
                .containsExactlyEntriesOf(expected);
        assertThat(nullability.values()).as("除数据库自维护的两列外全部 NOT NULL")
                .containsOnly("NO");
        assertThat(types).as("本表不得出现 is_current / is_active 这类第二 owner 标记")
                .doesNotContainKeys("is_current", "is_active", "current_since");

        String pk = meta.queryForObject(
                "SELECT column_name FROM information_schema.key_column_usage k "
                        + "WHERE k.table_schema = ? AND k.table_name = ? AND k.constraint_name = 'PRIMARY'",
                String.class, META_DB, TABLE);
        assertThat(pk).as("一源一行：主键必须是 source_id 本身（唯一键是覆盖语义的最终防线）")
                .isEqualTo("source_id");

        List<Map<String, Object>> fk = meta.queryForList(
                "SELECT k.constraint_name, k.referenced_table_name, k.referenced_column_name "
                        + "FROM information_schema.key_column_usage k "
                        + "WHERE k.table_schema = ? AND k.table_name = ? "
                        + "AND k.column_name = 'source_id' AND k.referenced_table_name IS NOT NULL",
                META_DB, TABLE);
        assertThat(fk).as("source_id 必须有外键指向 source_registry(id)").hasSize(1);
        assertThat(fk.get(0).get("referenced_table_name")).isEqualTo("source_registry");
        assertThat(fk.get(0).get("referenced_column_name")).isEqualTo("id");
    }

    @Test
    @DisplayName("真库往返：upsert 覆盖同一行、created_at 保住、JOIN 取回 source_code")
    void upsertReplacesTheSameRowAndKeepsCreatedAt() {
        meta.update(sql("upsert"), sourceId, "profiles/raw-a.v2.json", "2.0", "a".repeat(64),
                "1.0", "b".repeat(64), "dr-s2031-0001", LocalDateTime.parse("2026-09-21T10:20:30"), "dev1");

        List<Map<String, Object>> first = meta.queryForList(
                "SELECT profile_checksum, created_at FROM " + TABLE + " WHERE source_id = ?", sourceId);
        assertThat(first).as("首次写入必须落一行").hasSize(1);

        meta.update(sql("upsert"), sourceId, "profiles/raw-b.v2.json", "2.0", "c".repeat(64),
                "1.0", "d".repeat(64), "dr-s2031-0002", LocalDateTime.parse("2026-09-21T11:00:00"), "dev2");

        List<Map<String, Object>> rows = meta.queryForList(
                "SELECT profile_ref, profile_checksum, contract_checksum, report_id, activated_by, created_at "
                        + "FROM " + TABLE + " WHERE source_id = ?", sourceId);
        assertThat(rows).as("同源二次激活必须覆盖同一行（不是新增第二行）").hasSize(1);
        assertThat(rows.get(0).get("profile_ref")).isEqualTo("profiles/raw-b.v2.json");
        assertThat(rows.get(0).get("profile_checksum")).isEqualTo("c".repeat(64));
        assertThat(rows.get(0).get("contract_checksum")).isEqualTo("d".repeat(64));
        assertThat(rows.get(0).get("report_id")).isEqualTo("dr-s2031-0002");
        assertThat(rows.get(0).get("activated_by")).isEqualTo("dev2");
        assertThat(String.valueOf(rows.get(0).get("created_at")))
                .as("created_at 只在首次插入取值：替换激活不得抹掉'首次激活时刻'")
                .isEqualTo(String.valueOf(first.get(0).get("created_at")));

        // findById 的 SQL 原文（把 #{sourceId} 换成 JDBC ?）—— 验证 JOIN 能取回 source_code
        Map<String, Object> joined = meta.queryForMap(sql("findById"), sourceId);
        assertThat(joined.get("sourceCode")).as("source_code 由 JOIN source_registry 取回").isEqualTo(sourceCode);
        assertThat(joined.get("profileRef")).isEqualTo("profiles/raw-b.v2.json");
        assertThat(joined.get("profileChecksum")).isEqualTo("c".repeat(64));
    }

    @Test
    @DisplayName("锁定读：无行返回空、有行在真事务里可执行并读到已提交状态")
    void lockingReadSeesCommittedRowInsideTransaction() {
        assertThat(meta.queryForList(sql("lockById"), sourceId))
                .as("该源尚未激活时锁定读必须返回空（不是异常、也不是造一行）").isEmpty();

        meta.update(sql("upsert"), sourceId, "profiles/raw-a.v2.json", "2.0", "a".repeat(64),
                "1.0", "b".repeat(64), "dr-s2031-0003", LocalDateTime.parse("2026-09-21T10:20:30"), "dev1");

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(meta.getDataSource()));
        List<Map<String, Object>> read = tx.execute(status ->
                meta.queryForList(sql("lockById"), sourceId));
        assertThat(read).as("锁定读在真实事务里必须能取到该行").isNotNull().hasSize(1);
        assertThat(read.get(0).get("activatedBy")).isEqualTo("dev1");
        System.out.println("[S2-03.1] 锁定读真库取证：sourceId=" + sourceId + " → " + read.get(0));
    }

    // ---------------------------------------------------------------- mapper 注解取 SQL 原文

    /**
     * 取 mapper 注解里的 SQL 原文（真库执行的就是要上线的那段文本），
     * 只把 MyBatis 具名占位符 {@code #{sourceId}} 换成 JDBC {@code ?}。
     */
    private static String sql(String methodName) {
        Method method = java.util.Arrays.stream(SourceMappingActiveMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapper 上找不到方法 " + methodName));
        String text;
        if ("upsert".equals(methodName)) {
            Insert insert = method.getAnnotation(Insert.class);
            assertThat(insert).isNotNull();
            text = String.join(" ", insert.value());
        } else {
            Select select = method.getAnnotation(Select.class);
            assertThat(select).isNotNull();
            text = String.join(" ", select.value());
        }
        return text.replace("#{sourceId}", "?");
    }

    private static String requiredProperty(String bareKey, String purpose) {
        String value = System.getProperty("p1.it." + bareKey);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        try {
            return TestIsolationGuard.requiredProperty(bareKey);
        } catch (RuntimeException e) {
            throw new TestIsolationGuard.MissingConfigurationException(
                    "缺少测试隔离配置项 -Dp1.it." + bareKey + "（" + purpose + "）：拒绝运行（不用正式账号/正式地址兜底）");
        }
    }

    private static TestIsolationGuard.TestRunContext context() {
        return new TestIsolationGuard.TestRunContext(
                TEST_RUN_ID, SERVER_FINGERPRINT, META_DB, META_DB,
                System.getProperty("p1.it.hiveNamespace", "unused-no-hive"),
                System.getProperty("p1.it.hdfsRoot", "unused-no-hdfs"),
                System.getProperty("p1.it.manifestRoot", "unused-no-manifest"),
                "credref:" + META_USER, System.currentTimeMillis(), java.nio.file.Path.of("p1.it.properties"));
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + META_HOST + "/" + META_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        ds.setUsername(META_USER);
        ds.setPassword(META_PASSWORD);
        return ds;
    }
}
