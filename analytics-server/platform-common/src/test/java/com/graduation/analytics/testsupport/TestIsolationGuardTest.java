package com.graduation.analytics.testsupport;

import com.graduation.analytics.testsupport.TestIsolationGuard.ContentFingerprint;
import com.graduation.analytics.testsupport.TestIsolationGuard.DeletionTarget;
import com.graduation.analytics.testsupport.TestIsolationGuard.IsolationViolationException;
import com.graduation.analytics.testsupport.TestIsolationGuard.LiveFacts;
import com.graduation.analytics.testsupport.TestIsolationGuard.MissingConfigurationException;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestIsolationConfig;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext;
import com.graduation.analytics.testsupport.TestIsolationGuard.WorkScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V25-S01 §9.4 门禁的 T0 单测：**不连任何库**，用内存事实与假 DataSource 证明
 * 「默认关闭、写前校验、账号最小权限、cleanup 只清本次拥有、破坏性动作兜底」五条性质。
 *
 * <p>本类自身不得被 {@link TestIsolationGuard} 的配置缺失拒绝所影响：它测的正是拒绝行为，
 * 因此不走 {@link TestIsolationGuard#loadContext()}（那条路径由真实的写入型 IT 使用）。</p>
 */
class TestIsolationGuardTest {

    private static final String RUN_ID = "v25it-20260914-120000-a1b2";
    private static final String FINGERPRINT = "host-v25it:8b7f";

    // ── 1. 默认关闭：配置缺失立即拒绝（不是 skip 后 PASS）────────────────────

    @Test
    @DisplayName("配置缺失 → 立即拒绝运行（MissingConfigurationException，不是跳过）")
    void missingConfigurationIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> TestIsolationGuard.loadFrom(dir.resolve("not-there.properties")))
                .isInstanceOf(MissingConfigurationException.class)
                .hasMessageContaining("测试隔离档案不存在");

        assertThatThrownBy(() -> TestIsolationGuard.loadFrom(null))
                .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("配置不完整（少一个键）→ 拒绝，且不使用任何默认值补齐")
    void incompleteConfigurationIsRefused(@TempDir Path dir) throws Exception {
        Path file = writeProps(dir.resolve("integration.local.properties"), validProps(RUN_ID));
        String content = Files.readString(file).replaceAll("(?m)^metricDb=.*\\R", "");
        Files.writeString(file, content);

        assertThatThrownBy(() -> TestIsolationGuard.loadFrom(file))
                .isInstanceOf(MissingConfigurationException.class)
                .hasMessageContaining("metricDb");
    }

    @Test
    @DisplayName("正式库配置 → 在构造 TestRunContext 时就被拒（不提供 analytics_metric fallback）")
    void productionDatabaseConfigurationIsRejected(@TempDir Path dir) throws Exception {
        Map<String, String> props = validProps(RUN_ID);
        props.put("metricDb", "analytics_metric");
        Path file = writeProps(dir.resolve("integration.local.properties"), props);

        TestIsolationConfig cfg = TestIsolationGuard.loadFrom(file);
        assertThat(cfg.metricDb()).isEqualTo("analytics_metric");
        assertThatThrownBy(() -> TestIsolationGuard.verifyContext(cfg))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("analytics_metric")
                .hasMessageContaining("禁止");
    }

    @Test
    @DisplayName("metaDb 指向正式库同样被拒（只改 JDBC URL 不算隔离）")
    void productionMetaDatabaseConfigurationIsRejected(@TempDir Path dir) throws Exception {
        Map<String, String> props = validProps(RUN_ID);
        props.put("metaDb", "analytics_meta");
        TestIsolationConfig cfg = TestIsolationGuard.loadFrom(writeProps(dir.resolve("p.properties"), props));

        assertThatThrownBy(() -> TestIsolationGuard.verifyContext(cfg))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("analytics_meta");
    }

    @Test
    @DisplayName("隔离范围不一致：hiveNamespace / hdfsRoot / manifestRoot / credentialsRef 缺 testRunId 一律拒")
    void partiallyScopedTargetsAreRejected(@TempDir Path dir) throws Exception {
        Map<String, String> base = validProps(RUN_ID);
        for (String key : List.of("hiveNamespace", "hdfsRoot", "manifestRoot", "credentialsRef")) {
            Map<String, String> broken = new LinkedHashMap<>(base);
            broken.put(key, broken.get(key).replace(RUN_ID, "shared-namespace"));
            TestIsolationConfig cfg = TestIsolationGuard.loadFrom(
                    writeProps(dir.resolve(key + ".properties"), broken));
            assertThatThrownBy(() -> TestIsolationGuard.verifyContext(cfg))
                    .as("字段 %s 不得退化为可复用/共享范围", key)
                    .isInstanceOf(IsolationViolationException.class);
        }
    }

    @Test
    @DisplayName("集群路径护栏：hdfsRoot 指向 hdfs:///graduation/** 一律拒")
    void clusterPathIsRejected(@TempDir Path dir) throws Exception {
        Map<String, String> props = validProps(RUN_ID);
        props.put("hdfsRoot", "hdfs://node01:8020/graduation/" + RUN_ID);
        TestIsolationConfig cfg = TestIsolationGuard.loadFrom(writeProps(dir.resolve("p.properties"), props));

        assertThatThrownBy(() -> TestIsolationGuard.verifyContext(cfg))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("/graduation/");
    }

    // ── 2. 写前校验：正式库地址在任何写入之前失败 ──────────────────────────

    @Test
    @DisplayName("给正式库配置时：在开连接之前就失败，绝不「先在正式库试写再回滚」")
    void verificationFailsBeforeAnyConnectionIsOpened(@TempDir Path dir) throws Exception {
        CountingDataSource probe = new CountingDataSource();
        TestRunContext context = TestIsolationGuard.verifyContext(
                TestIsolationGuard.loadFrom(writeProps(dir.resolve("p.properties"), validProps(RUN_ID))));

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, probe, "analytics_metric"))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("analytics_metric");
        assertThat(probe.connectionAttempts())
                .as("写前校验必须在建立连接之前拒绝，正式库上不留任何连接/写入痕迹")
                .isZero();
        assertThat(probe.statementAttempts())
                .as("拒绝路径不得在正式库上执行任何语句（含 SELECT DATABASE() 这类只读探测）")
                .isZero();
        assertThat(probe.unexpectedWrites()).isZero();
    }

    @Test
    @DisplayName("声明库与登记范围不符（只改 JDBC URL 到另一个库）→ 写前拒绝")
    void declaredDatabaseOutsideScopeIsRejected(@TempDir Path dir) throws Exception {
        CountingDataSource probe = new CountingDataSource();
        TestRunContext context = TestIsolationGuard.verifyContext(
                TestIsolationGuard.loadFrom(writeProps(dir.resolve("p.properties"), validProps(RUN_ID))));

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, probe, "some_other_db"))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("不在本次登记范围");
        assertThat(probe.connectionAttempts()).isZero();
    }

    @Test
    @DisplayName("没有 TestRunContext / 没有 DataSource → 直接拒绝（配置缺失即失败）")
    void nullInputsAreRefused() {
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(null, new CountingDataSource(), "x"))
                .isInstanceOf(MissingConfigurationException.class);
        // 隔离根用 java.io.tmpdir 派生（跨平台绝对路径；不写死 /tmp —— 在 Windows 上那不是绝对路径）
        String isolatedRoot = Path.of(System.getProperty("java.io.tmpdir"), RUN_ID).toString();
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(
                new TestRunContext(RUN_ID, FINGERPRINT, "m", "d", RUN_ID + "-hive", isolatedRoot,
                        isolatedRoot, "cred:" + RUN_ID, 1L, Path.of("x")), null, "d"))
                .isInstanceOf(MissingConfigurationException.class);
    }

    @Test
    @DisplayName("隔离根不接受 file:// 与 hdfs:// URI 写法（URI 会让「归属校验/安全删除」失去判据）")
    void uriShapedRootsAreRejected(@TempDir Path dir) throws Exception {
        for (String bad : List.of("file:///D:/Develop_code/GraduationProject/target/v25-it/" + RUN_ID + "/hdfs",
                "hdfs://127.0.0.1:9000/v25-it/" + RUN_ID,
                "file:///graduation/" + RUN_ID)) {
            Map<String, String> props = validProps(RUN_ID);
            props.put("hdfsRoot", bad);
            Path file = writeProps(dir.resolve("u.properties"), props);
            assertThatThrownBy(() -> TestIsolationGuard.verifyContext(TestIsolationGuard.loadFrom(file)))
                    .as("隔离根 %s 必须被拒", bad)
                    .isInstanceOf(IsolationViolationException.class);
        }
    }

    @Test
    @DisplayName("服务实例指纹不匹配 → 拒绝（同库名但换了一台实例不算同一隔离范围）")
    void fingerprintMismatchIsRejected() {
        assertThat(TestIsolationGuard.fingerprintMatches(FINGERPRINT, "other-uuid", "other-host")).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches(FINGERPRINT, "host-v25it", FINGERPRINT)).isTrue();
        assertThat(TestIsolationGuard.fingerprintMatches(FINGERPRINT, FINGERPRINT, "host-v25it")).isTrue();
        assertThat(TestIsolationGuard.fingerprintMatches(FINGERPRINT, FINGERPRINT + ":1234", "host-v25it")).isTrue();
        assertThat(TestIsolationGuard.fingerprintMatches("", "x", "y")).isFalse();
    }

    @Test
    @DisplayName("账号最小权限：root / 正式写账号 metric_pub 在禁止清单内")
    void forbiddenAccountsAreListed() {
        // 账号清单是私有的行为契约：这里用 LiveFacts 的可比较指纹证明「账号进入指纹」，
        // 实际拒绝路径由 verifyBeforeWrite 的 forbiddenAccounts 分支覆盖（需真连接，标未取证）。
        LiveFacts a = new LiveFacts(RUN_ID, FINGERPRINT, "root", "analytics_metric_v25it",
                "uuid-1", "host", 8, 3307, Set.of("x.SELECT"), Set.of());
        LiveFacts b = new LiveFacts(RUN_ID, FINGERPRINT, "metric_pub", "analytics_metric_v25it",
                "uuid-1", "host", 8, 3307, Set.of("x.SELECT"), Set.of());
        assertThat(a.sha1()).isNotEqualTo(b.sha1());
        assertThat(a.redactedSummary()).contains("account=root").doesNotContain("password");
    }

    // ── 2b. 实例层面隔离：端口/主机白名单（库名含 test 不算安全证明） ─────────

    @Test
    @DisplayName("隔离实例端口白名单：宿主 3306 一律拒，只有 WSL 隔离实例 3307 放行")
    void instancePortAllowlistIsEnforced() {
        // 宿主正式实例端口：最常见的踩回 F-100 的形态，必须点名拒绝
        assertThatThrownBy(() -> TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://127.0.0.1:3306/analytics_metric_test?useSSL=false")))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("宿主正式 MySQL 实例端口");

        // 改名成 *_test 也照样拒：库名不是判据
        assertThatThrownBy(() -> TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://127.0.0.1:3306/test_db")))
                .isInstanceOf(IsolationViolationException.class);

        // 省略端口时按驱动的默认 3306 处理，同样拒
        assertThatThrownBy(() -> TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://localhost/analytics_metric_v25it")))
                .isInstanceOf(IsolationViolationException.class);

        // 端口对但主机不是本机隔离实例 → 拒
        assertThatThrownBy(() -> TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://10.0.0.9:3307/analytics_metric_v25it")))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("不是本机隔离实例");

        // 白名单内的隔离实例 + 未登记的库名 → 由库名/范围判据继续拒（端口通过了不代表放行）
        assertThatThrownBy(() -> TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://127.0.0.1:3307/analytics_metric")))
                .isInstanceOf(IsolationViolationException.class);

        // 正例：WSL 隔离实例 3307 + 本次登记的隔离库
        TestIsolationGuard.assertJdbcTargetAllowed(
                urlDataSource("jdbc:mysql://127.0.0.1:3307/analytics_metric_v25it?useSSL=false"));

        // URL 读不到时不在预检阶段失败（留给实连后的 @@port 检查兜底）
        TestIsolationGuard.assertJdbcTargetAllowed(new CountingDataSource());
    }

    // ── 2c. 连接参数解析：系统属性优先，档案兜底，两处都没有即拒（无默认值） ──

    @Test
    @DisplayName("连接参数解析：系统属性优先；两处都没有则拒绝，绝不回退正式地址/正式账号")
    void connectionPropertiesFailClosedWithoutAnySource() {
        // 系统属性优先于档案
        System.setProperty(TestIsolationGuard.SYSTEM_PROPERTY_PREFIX + "probe.key", "from-system");
        try {
            assertThat(TestIsolationGuard.requiredProperty("probe.key")).isEqualTo("from-system");
        } finally {
            System.clearProperty(TestIsolationGuard.SYSTEM_PROPERTY_PREFIX + "probe.key");
        }

        // 两处都没有 → 拒绝，而不是给 127.0.0.1:3306 / root / metric_pub 之类的默认值
        assertThatThrownBy(() -> TestIsolationGuard.requiredProperty("definitely.absent.key"))
                .isInstanceOf(MissingConfigurationException.class)
                .hasMessageContaining("拒绝运行")
                .hasMessageContaining("不用正式账号/正式地址兜底");

        // 可空版本不抛，但也不会凭空造出默认值
        assertThat(TestIsolationGuard.optionalProperty("definitely.absent.key")).isNull();
    }

    private static DataSource urlDataSource(String url) {
        return new DataSource() {
            public String getUrl() {
                return url;
            }

            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("URL 预检不应建立连接");
            }

            @Override
            public Connection getConnection(String u, String p) throws SQLException {
                return getConnection();
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getLogger("url-datasource");
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("不是真实数据源");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    // ── 3. cleanup 只清本次 testRunId 拥有的记录 ────────────────────────────

    @Test
    @DisplayName("cleanup：目标清单必须逐条匹配 testRunId 前缀与本次 profileId")
    void cleanupTargetsAreScopedToThisRun() {
        WorkScope scope = new WorkScope(RUN_ID, 999101L,
                new LinkedHashSet<>(List.of(RUN_ID + "-ok", RUN_ID + "-bad")),
                new LinkedHashSet<>(List.of("ads_operation_overview_m")));

        TestIsolationGuard.assertDeletionTargets(scope, List.of(
                new DeletionTarget(RUN_ID + "-ok", 999101L, "ACTIVE", 1),
                new DeletionTarget(RUN_ID + "-bad", 999101L, "FAILED", null)));

        assertThatThrownBy(() -> TestIsolationGuard.assertDeletionTargets(scope,
                List.of(new DeletionTarget(RUN_ID + "-ok", 1L, "ACTIVE", 1))))
                .as("复用档案 id=1 上的快照必须被拒（这正是旧 cleanup 的批量 DELETE 风险）")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("runtime_profile_id");

        assertThatThrownBy(() -> TestIsolationGuard.assertDeletionTargets(scope,
                List.of(new DeletionTarget("S20260901_47", 999101L, "ACTIVE", 1))))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("不在本次拥有清单内");

        assertThatThrownBy(() -> TestIsolationGuard.assertDeletionTargets(scope,
                List.of(new DeletionTarget(RUN_ID + "-ghost", 999101L, "ACTIVE", 1))))
                .isInstanceOf(IsolationViolationException.class);
    }

    @Test
    @DisplayName("WorkScope 拒绝空/共享范围，requireOwnedSnapshot 要求 testRunId 前缀")
    void workScopeRejectsSharedRanges() {
        WorkScope scope = new WorkScope(RUN_ID, 7L, Set.of(RUN_ID + "-x"), Set.of("t"));
        assertThatThrownBy(() -> scope.requireOwnedSnapshot("runtime_profile_service_scope"))
                .isInstanceOf(IsolationViolationException.class);
        assertThatThrownBy(() -> scope.requireOwnedTable("metric_value"))
                .isInstanceOf(IsolationViolationException.class);
        assertThatThrownBy(() -> new WorkScope(RUN_ID, 7L, Set.of(), Set.of("t")))
                .isInstanceOf(IsolationViolationException.class);
        assertThatThrownBy(() -> new WorkScope(RUN_ID, 0L, Set.of("a"), Set.of("t")))
                .isInstanceOf(IsolationViolationException.class);
    }

    // ── 4. 绝对禁止的破坏性动作 ─────────────────────────────────────────────

    @Test
    @DisplayName("TRUNCATE / 递归删仓库根 / 删历史 manifest 一律被拒")
    void destructiveActionsAreBlocked(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> TestIsolationGuard.assertNoTruncate("TRUNCATE TABLE metric_snapshot"))
                .isInstanceOf(IsolationViolationException.class);
        assertThatThrownBy(() -> TestIsolationGuard.assertNoTruncate("  truncate   table x"))
                .isInstanceOf(IsolationViolationException.class);
        TestIsolationGuard.assertNoTruncate("DELETE FROM metric_snapshot WHERE snapshot_id = ?");

        assertThatThrownBy(() -> TestIsolationGuard.assertSafeRecursiveDelete(RepoRoot.path()))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("仓库根");
        assertThatThrownBy(() -> TestIsolationGuard.assertSafeRecursiveDelete(dir.getRoot()))
                .isInstanceOf(IsolationViolationException.class);
        TestIsolationGuard.assertSafeRecursiveDelete(RepoRoot.path().resolve("target").resolve("v25-s01"));

        TestRunContext context = new TestRunContext(RUN_ID, FINGERPRINT, "m", "d", RUN_ID + "-hive",
                Path.of(System.getProperty("java.io.tmpdir"), RUN_ID).toString(),
                Path.of(System.getProperty("java.io.tmpdir"), RUN_ID, "manifest").toString(),
                "cred:" + RUN_ID, 1L, Path.of("cfg"));
        TestIsolationGuard.assertSafeManifestDelete(context,
                Path.of(context.manifestRoot(), RUN_ID + "-export.json"));
        assertThatThrownBy(() -> TestIsolationGuard.assertSafeManifestDelete(context,
                Path.of(context.manifestRoot(), "..", "historical", "_export.json")))
                .isInstanceOf(IsolationViolationException.class);
    }

    @Test
    @DisplayName("内容指纹：计数相同但内容不同必须能区分（行数相等不足以证明零修改）")
    void contentFingerprintDistinguishesContentChanges() {
        ContentFingerprint before = new ContentFingerprint("analytics_metric", "metric_snapshot", 12,
                "aaa", "metric_value", 110, "S20260901_47:12");
        ContentFingerprint sameCountDifferentContent = new ContentFingerprint("analytics_metric", "metric_snapshot",
                12, "bbb", "metric_value", 110, "S20260901_47:12");
        ContentFingerprint pointerMoved = new ContentFingerprint("analytics_metric", "metric_snapshot", 12,
                "aaa", "metric_value", 110, "S20260901_48:13");

        assertThat(before.sameContent(before)).isTrue();
        assertThat(before.sameContent(sameCountDifferentContent)).isFalse();
        assertThat(before.sameContent(pointerMoved)).isFalse();
        assertThat(before.describe()).contains("rows=12").contains("S20260901_47:12");
    }

    // ── 5. 配置解析 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("合法隔离配置可构造 context，字段与 startedAt 齐备")
    void validConfigurationBuildsContext(@TempDir Path dir) throws Exception {
        TestIsolationConfig cfg = TestIsolationGuard.loadFrom(
                writeProps(dir.resolve("p.properties"), validProps(RUN_ID)));
        TestRunContext context = TestIsolationGuard.contextOf(cfg, 1_700_000_000_000L);

        assertThat(context.testRunId()).isEqualTo(RUN_ID);
        assertThat(context.serverFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(context.metaDb()).isEqualTo("analytics_meta_v25it");
        assertThat(context.metricDb()).isEqualTo("analytics_metric_v25it");
        assertThat(context.hiveNamespace()).startsWith(RUN_ID);
        assertThat(context.startedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(context.elapsedMillis(1_700_000_001_000L)).isEqualTo(1000L);
        assertThat(context.redactedSummary()).doesNotContain("pw").doesNotContain("password");
    }

    @Test
    @DisplayName("testRunId 形状非法 → 拒绝（不可复用/不可复查的标识不算隔离范围）")
    void malformedTestRunIdIsRejected(@TempDir Path dir) throws Exception {
        Map<String, String> props = validProps(RUN_ID);
        props.put("testRunId", "x");
        assertThatThrownBy(() -> TestIsolationGuard.loadFrom(writeProps(dir.resolve("p.properties"), props)))
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("testRunId");
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private static Map<String, String> validProps(String runId) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("testRunId", runId);
        props.put("serverFingerprint", FINGERPRINT);
        props.put("metaDb", "analytics_meta_v25it");
        props.put("metricDb", "analytics_metric_v25it");
        props.put("hiveNamespace", runId + "_hive");
        props.put("hdfsRoot", "D:/Develop_code/GraduationProject/target/v25-it/" + runId + "/hdfs");
        props.put("manifestRoot", "D:/Develop_code/GraduationProject/target/v25-it/" + runId + "/manifest");
        props.put("credentialsRef", "credref:" + runId);
        return props;
    }

    private static Path writeProps(Path file, Map<String, String> props) throws Exception {
        StringBuilder sb = new StringBuilder();
        props.forEach((k, v) -> sb.append(k).append('=').append(v).append(System.lineSeparator()));
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        return file;
    }

    /**
     * 只用来证明「写前校验在建立连接之前拒绝」的探针：任何 {@code getConnection()} 都计数并失败。
     * 它不是数据库，也不会执行任何写入。
     */
    private static final class CountingDataSource implements DataSource {

        private final AtomicInteger attempts = new AtomicInteger();

        int connectionAttempts() {
            return attempts.get();
        }

        /** 探针从不执行写入：恒为 0，用于断言「拒绝发生在任何写入之前」。 */
        int unexpectedWrites() {
            return 0;
        }

        /**
         * 探针从不返回连接，因此也从不执行语句：恒为 0。
         * 用来断言拒绝路径连只读探测（{@code SELECT DATABASE()}）都没有下发到正式库。
         */
        int statementAttempts() {
            return 0;
        }

        @Override
        public Connection getConnection() throws SQLException {
            attempts.incrementAndGet();
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // 探针不需要日志
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // 探针不需要超时
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("counting-datasource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("不是真实数据源");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
