package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.IsolationProfileCondition;
import com.graduation.analytics.testsupport.TestIsolationGuard;
import com.graduation.analytics.testsupport.TestIsolationGuard.DeletionTarget;
import com.graduation.analytics.testsupport.TestIsolationGuard.LiveFacts;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext;
import com.graduation.analytics.testsupport.TestIsolationGuard.WorkScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7-2 真库集成测试（**默认关闭**：没有登记测试隔离档案时整个类不执行，见
 * {@link IsolationProfileCondition} 与指导书 V2.5 §9.4）。
 *
 * <p>用途：证明 ADS DAO 与 Flyway 建出的表结构一致（列名/类型/主键），
 * 并验证「同一 dt 在不同 snapshot_id 下共存」——即 R7 修复的 dt 单列主键互相覆盖问题。</p>
 *
 * <p><b>V25-S01 整改（原缺陷 → 现行约束）</b>：本类过去写死
 * {@code jdbc:mysql://127.0.0.1:3306/analytics_metric} ＋ 正式写账号 {@code metric_pub}，
 * cleanup 里还有 {@code DELETE FROM metric_snapshot WHERE runtime_profile_id = 999001}
 * ——任一时刻加 {@code -Dmetric.it=true} 就能删掉正式指标库上该档案的全部快照。
 * 现在：</p>
 * <ol>
 *   <li>目标库/账号全部来自 {@link TestRunContext}（本次运行新建的隔离库与受限账号），
 *       正式库地址在**建立连接之前**就被 {@link TestIsolationGuard#verifyBeforeWrite} 拒绝；</li>
 *   <li>写前用实际连接核对 {@code SELECT DATABASE()}＋服务实例指纹＋账号实际权限；</li>
 *   <li>快照号带本次 {@code testRunId} 前缀，档案 id 取本 run 自己登记的 {@code runtime_profile} 行，
 *       <b>不再按可复用的固定 id 批量 DELETE</b>；</li>
 *   <li>cleanup 删除前先查出目标清单并逐条验范围（{@link TestIsolationGuard#assertDeletionTargets}）。</li>
 * </ol>
 *
 * <p><b>为什么隔离上下文放在 {@code @BeforeAll} 而不是 static 字段</b>：static 初始化会在
 * <b>类加载</b>时就求值，那时 JUnit 还没评估 {@link IsolationProfileCondition} ——
 * 结果是「配置缺失」直接抛 {@code ExceptionInInitializerError}，把整个测试套件打红，
 * 而不是按 §9.4 要求「默认关闭」。放在 {@code @BeforeAll} 后，顺序变成
 * 「先判定是否启用 → 启用才加载配置 → 配置非法仍硬失败」，既不静默跳过写测试，也不误伤回归。</p>
 *
 * <p>运行方式（缺配置时类被禁用，不会静默写成 PASS）：</p>
 * <pre>
 *   mvn -o -f analytics-server/pom.xml -pl metric-analysis -am test ^
 *       "-Dtest=MetricAdsMySqlIT" "-Dv25.it.testRunId=v25it-...-..." ...
 * </pre>
 */
@ExtendWith(IsolationProfileCondition.class)
class MetricAdsMySqlIT {

    private static final String DT = "2026-09-01";

    private static TestRunContext context;
    private static String snapshotActive;
    private static String snapshotB;
    private static String snapshotC;

    /** 本次拥有的 ADS 表（与 MetricAdsCatalog 白名单一致，删除时逐表限定本次快照）。 */
    private static final List<String> OWNED_ADS_TABLES = new ArrayList<>();

    static {
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            OWNED_ADS_TABLES.add(spec.name());
        }
    }

    private static JdbcTemplate publish;
    private static JdbcTemplate read;
    private static MetricAdsWriter writer;
    private static MetricAdsReader reader;
    private static WorkScope scope;
    private static long profileId;

    @BeforeAll
    static void setUp() {
        // 到这里说明类已被判定为「启用」，因此配置缺失/非法必须硬失败，不能静默跳过
        context = TestIsolationGuard.loadContext();
        String run = context.testRunId();
        snapshotActive = run + "-ads-active";
        snapshotB = run + "-ads-b";
        snapshotC = run + "-ads-c";
        System.out.println("[MetricAdsMySqlIT] 隔离上下文：" + context.redactedSummary());

        // 键名不带 v25.it. 前缀：TestIsolationGuard.requiredProperty 内部会先查系统属性
        // -Dv25.it.<key>，再查隔离档案文件，两处都没有才拒绝（无正式目标兜底）。
        DataSource publishDs = dataSource("metric.publish.username", "metric.publish.password");
        DataSource readDs = dataSource("metric.read.username", "metric.read.password");

        // 写前校验：任何 DDL/DML 之前，用实际连接核对库名/服务实例指纹/账号实际权限
        LiveFacts publishFacts = TestIsolationGuard.verifyBeforeWrite(context, publishDs, context.metricDb());
        LiveFacts readFacts = TestIsolationGuard.verifyBeforeWrite(context, readDs, context.metricDb());
        System.out.println("[MetricAdsMySqlIT] publish 连接事实：" + publishFacts.redactedSummary());
        System.out.println("[MetricAdsMySqlIT] read 连接事实：" + readFacts.redactedSummary());

        publish = new JdbcTemplate(publishDs);
        read = new JdbcTemplate(readDs);
        writer = new MetricAdsWriter(publish);
        reader = new MetricAdsReader(read);

        profileId = registerOwnProfile();
        scope = new WorkScope(run, profileId,
                new LinkedHashSet<>(List.of(snapshotActive, snapshotB, snapshotC)),
                new LinkedHashSet<>(OWNED_ADS_TABLES));
        System.out.println("[MetricAdsMySqlIT] 本次拥有范围：profileId=" + profileId
                + " snapshots=" + List.of(snapshotActive, snapshotB, snapshotC)
                + " tables=" + OWNED_ADS_TABLES.size());

        cleanup();
        insertSnapshot(snapshotActive, "ACTIVE", 1);
        insertSnapshot(snapshotB, "ARCHIVED", null);
        insertSnapshot(snapshotC, "ARCHIVED", null);
    }

    @AfterAll
    static void tearDown() {
        if (scope != null) {
            cleanup();
        }
    }

    @Test
    @DisplayName("真库往返：写入 ADS 行 → 按快照读回 → 计数 → deleteSnapshot 清理")
    void roundTripAgainstRealSchema() {
        assertThat(writer.insertRows("ads_operation_overview_m", snapshotActive, DT,
                List.of(overviewRow(100L, new BigDecimal("1234.5600"))))).isEqualTo(1);
        assertThat(writer.insertRows("ads_hot_product_m", snapshotActive, DT,
                List.of(hotRow(1, 11L), hotRow(2, 22L), hotRow(3, 33L)))).isEqualTo(3);

        assertThat(reader.activeSnapshotId()).isEqualTo(snapshotActive);
        assertThat(reader.activeSnapshotId(profileId)).isEqualTo(snapshotActive);
        assertThat(reader.activeSnapshotId(profileId + 500_000L)).isNull();
        assertThat(reader.countRows("ads_hot_product_m", snapshotActive)).isEqualTo(3);

        Map<String, Object> overview = reader.selectOneBySnapshot("ads_operation_overview_m", snapshotActive, DT);
        assertThat(overview).isNotNull();
        assertThat(((Number) overview.get("pv")).longValue()).isEqualTo(100L);
        assertThat(new BigDecimal(String.valueOf(overview.get("sale_amount"))))
                .isEqualByComparingTo(new BigDecimal("1234.56"));

        Map<String, Object> top = reader.selectOneActive("ads_hot_product_m", DT);
        assertThat(top).isNotNull();
        assertThat(((Number) top.get("rank_no")).intValue()).isEqualTo(1); // ORDER BY rank_no + LIMIT 1
        assertThat(reader.selectActive("ads_hot_product_m", DT)).hasSize(3);

        assertThat(writer.deleteSnapshot(snapshotActive)).isEqualTo(4);
        assertThat(reader.countRows("ads_hot_product_m", snapshotActive)).isZero();
        assertThat(reader.selectOneBySnapshot("ads_operation_overview_m", snapshotActive, DT)).isNull();
    }

    @Test
    @DisplayName("同一个 dt 在两个快照下共存：主键不是 dt 单列（R7 核心修复）")
    void sameDtSurvivesTwoSnapshots() {
        writer.insertRows("ads_operation_overview_m", snapshotB, DT, List.of(overviewRow(1L, BigDecimal.ONE)));
        writer.insertRows("ads_operation_overview_m", snapshotC, DT, List.of(overviewRow(2L, BigDecimal.TEN)));

        assertThat(reader.countRows("ads_operation_overview_m", snapshotB)).isEqualTo(1);
        assertThat(reader.countRows("ads_operation_overview_m", snapshotC)).isEqualTo(1);
        assertThat(((Number) reader.selectOneBySnapshot("ads_operation_overview_m", snapshotC, DT).get("pv"))
                .longValue()).isEqualTo(2L);
    }

    // ── 自带档案登记与 cleanup（只清本次 testRunId 拥有的记录）──────────────────

    /**
     * 登记本 run 自己的 {@code runtime_profile} 行，返回其 id。
     *
     * <p>不用固定 id：固定 id 是可复用标识，旧 cleanup 正是按它批量 DELETE，
     * 会连带删掉同档案下的正式快照。</p>
     */
    private static long registerOwnProfile() {
        String profileCode = "v25it-" + context.testRunId();
        String profileName = "V25-S01 隔离测试档案 " + context.testRunId();
        // 只写 runtime_profile 的既有列（以 Flyway 脚本为准，不凭记忆加列）
        publish.update("INSERT INTO runtime_profile (profile_code, profile_name, type, status, landing_uri, "
                        + "spark_master, metric_store_type, timezone, version, credential_ref) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE profile_name = VALUES(profile_name), updated_at = CURRENT_TIMESTAMP(3)",
                profileCode, profileName, "LOCAL", "ACTIVE", context.hdfsRoot() + "/landing",
                "local[1]", "MYSQL", "Asia/Shanghai", 1, context.credentialsRef());
        Long id = publish.queryForObject("SELECT id FROM runtime_profile WHERE profile_code = ?", Long.class, profileCode);
        assertThat(id).as("本次运行必须有自己的 runtime_profile 行").isNotNull();
        System.out.println("[MetricAdsMySqlIT] 本次登记档案 id=" + id + " profile_code=" + profileCode);
        return id;
    }

    private static Map<String, Object> overviewRow(long pv, BigDecimal saleAmount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pv", pv);
        row.put("uv", pv);
        row.put("dau", pv);
        row.put("order_count", 5L);
        row.put("sale_amount", saleAmount);
        row.put("net_sale_amount", saleAmount);
        row.put("avg_order_value", new BigDecimal("246.9100"));
        row.put("refund_rate", new BigDecimal("0.012500"));
        row.put("full_refund_rate", new BigDecimal("0.005000"));
        return row;
    }

    private static Map<String, Object> hotRow(int rankNo, long productId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank_no", rankNo);
        row.put("product_id", productId);
        row.put("product_name", "IT 商品 " + productId);
        row.put("heat_score", new BigDecimal("9.5"));
        row.put("pv", 100L);
        row.put("buy", 3L);
        return row;
    }

    private static void insertSnapshot(String snapshotId, String status, Integer activeFlag) {
        scope.requireOwnedSnapshot(snapshotId);
        LocalDateTime now = LocalDateTime.now();
        publish.update("INSERT INTO metric_snapshot (snapshot_id, runtime_profile_id, business_time, status, "
                        + "version, definition_version, data_updated_at, published_at, source, active_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                snapshotId, profileId, Timestamp.valueOf(now), status, 1, "v1",
                Timestamp.valueOf(now), Timestamp.valueOf(now), "spark-ads", activeFlag);
    }

    /**
     * cleanup：先按本次拥有的快照号查出实际行，打印清单并逐条验范围，再按**快照号 + 本次档案 id** 删除。
     * 不使用 {@code WHERE runtime_profile_id = <常量>} 这类可复用批量条件。
     */
    private static void cleanup() {
        for (String snapshotId : List.of(snapshotActive, snapshotB, snapshotC)) {
            safeDeleteSnapshot(snapshotId);
        }
        int profiles = publish.update("DELETE FROM runtime_profile WHERE id = ? AND profile_code = ?",
                profileId, "v25it-" + context.testRunId());
        System.out.println("[MetricAdsMySqlIT] cleanup runtime_profile 行数=" + profiles + " id=" + profileId);
    }

    private static void safeDeleteSnapshot(String snapshotId) {
        List<DeletionTarget> targets = read.query(
                "SELECT snapshot_id, runtime_profile_id, status, active_flag FROM metric_snapshot WHERE snapshot_id = ?",
                (rs, rowNum) -> new DeletionTarget(rs.getString("snapshot_id"), rs.getLong("runtime_profile_id"),
                        rs.getString("status"), (Integer) rs.getObject("active_flag")),
                snapshotId);
        TestIsolationGuard.assertDeletionTargets(scope, targets);
        if (targets.isEmpty()) {
            return;
        }
        for (String table : OWNED_ADS_TABLES) {
            scope.requireOwnedTable(table);
            int adsRows = publish.update("DELETE FROM " + table + " WHERE snapshot_id = ?", snapshotId);
            if (adsRows > 0) {
                System.out.println("[MetricAdsMySqlIT] cleanup " + table + " rows=" + adsRows
                        + " snapshot_id=" + snapshotId);
            }
        }
        int values = publish.update("DELETE FROM metric_value WHERE snapshot_id = ?", snapshotId);
        int snapshots = publish.update("DELETE FROM metric_snapshot WHERE snapshot_id = ? AND runtime_profile_id = ?",
                snapshotId, profileId);
        System.out.println("[MetricAdsMySqlIT] cleanup snapshot_id=" + snapshotId
                + " metric_value_rows=" + values + " metric_snapshot_rows=" + snapshots);
    }

    /**
     * 数据源：库名**只能**来自 {@link TestRunContext}（没有 {@code analytics_metric} 之类的默认值）。
     * 账号同样只能来自系统属性；缺失时 {@link TestIsolationGuard#verifyBeforeWrite} 会因配置缺失拒绝，
     * 不存在「缺省用正式写账号」这条路径。
     */
    private static DataSource dataSource(String userProperty, String passwordProperty) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + requiredProperty("mysql.host") + "/" + context.metricDb()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        ds.setUsername(requiredProperty(userProperty));
        ds.setPassword(requiredProperty(passwordProperty));
        return ds;
    }

    private static String requiredProperty(String key) {
        return TestIsolationGuard.requiredProperty(key);
    }
}
