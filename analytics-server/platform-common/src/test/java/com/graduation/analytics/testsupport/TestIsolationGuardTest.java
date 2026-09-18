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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
    private static final String FINGERPRINT = "host-v25it:3307";

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
    @DisplayName("指纹语义收口（DEV-002 N-3）：唯一权威身份是 hostname:port，server_uuid 不得替代")
    void fingerprintOnlyAcceptsCanonicalHostnamePort() {
        // ① hostname ＋ 正确 3307 ⇒ 通过（规范化：两侧 trim、hostname 段忽略大小写）
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "dahaishui", 3307)).isTrue();
        assertThat(TestIsolationGuard.fingerprintMatches("  Dahaishui:3307 ", " dahaishui ", 3307)).isTrue();
        // ② hostname 相同但端口 3306 ⇒ 拒绝（同机不同实例）
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "dahaishui", 3306)).isFalse();
        // ③ hostname 不同但端口 3307 ⇒ 拒绝（同端口不同机）
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "otherhost", 3307)).isFalse();
        // ④ server_uuid 作为登记值 ⇒ 一律拒绝：禁止「hostname:port 不匹配但 uuid 相同就放行」
        assertThat(TestIsolationGuard.fingerprintMatches("de8ebbea-aff4-11f1-8037-00155d5dba47",
                "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("uuid-v25it", "host-v25it", 3307)).isFalse();
        // 旧实现的其它宽松放行形态一并收紧：裸 hostname / 裸端口 / 环回别名 / 任意端口后缀
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui", "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("3307", "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("127.0.0.1:3307", "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("localhost:3307", "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "dahaishui:3306", 3307)).isFalse();
        // 登记值/实际值缺失 ⇒ 拒绝（不返回 true 兜底）
        assertThat(TestIsolationGuard.fingerprintMatches("", "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches(null, "dahaishui", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", null, 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "  ", 3307)).isFalse();
        assertThat(TestIsolationGuard.fingerprintMatches("dahaishui:3307", "dahaishui", -1)).isFalse();
    }

    @Test
    @DisplayName("指纹门禁不接受 server_uuid 替代：登记值填 uuid、实际 uuid 真的相同 ⇒ 仍然拒绝")
    void serverUuidIsNotAcceptedAsFingerprintAtGate(@TempDir Path dir) throws Exception {
        TestRunContext uuidAsFingerprint = contextOf(dir, "uuid-v25it");
        FakeMySql8 instance = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        instance.grants.add("GRANT USAGE ON *.* TO `v25it_rw`@`%`");
        instance.grants.add("GRANT ALL PRIVILEGES ON `analytics_metric_v25it`.* TO `v25it_rw`@`%`");
        assertThat(instance.serverUuid)
                .as("夹具的 server_uuid 与登记值逐字相同——旧实现会因此放行")
                .isEqualTo("uuid-v25it");

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(uuidAsFingerprint,
                instance.dataSource(), "analytics_metric_v25it"))
                .as("DEV-002 N-3：uuid 不得成为 hostname:port 的替代合法值")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("服务实例指纹不匹配")
                .hasMessageContaining("不得替代指纹判定");
    }

    @Test
    @DisplayName("端口门禁与指纹门禁是两层：登记指纹写成 3306 时，实连 3307 也被指纹门禁拒（不靠端口兜底）")
    void wrongPortInRegisteredFingerprintIsRejectedByFingerprintGate(@TempDir Path dir) throws Exception {
        TestRunContext wrongPort = contextOf(dir, "host-v25it:3306");
        FakeMySql8 instance = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        instance.grants.add("GRANT USAGE ON *.* TO `v25it_rw`@`%`");
        instance.grants.add("GRANT ALL PRIVILEGES ON `analytics_metric_v25it`.* TO `v25it_rw`@`%`");

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(wrongPort, instance.dataSource(),
                "analytics_metric_v25it"))
                .as("actual 端口 3307 通过端口白名单，随后必须被指纹判据独立拦下")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("服务实例指纹不匹配")
                .hasMessageNotContaining("宿主正式 MySQL 实例端口");
    }

    @Test
    @DisplayName("server_uuid 独立漂移保护：取不到就 fail-closed 拒绝（不降级、不跳过）")
    void missingServerUuidIsRejectedFailClosed(@TempDir Path dir) throws Exception {
        TestRunContext context = contextOf(dir);
        FakeMySql8 noUuid = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        noUuid.serverUuid = null;
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, noUuid.dataSource(),
                "analytics_metric_v25it"))
                .as("指纹本身匹配（host-v25it:3307），但独立的实例事实缺失 ⇒ 仍然拒绝")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("@@server_uuid")
                .hasMessageContaining("实例身份事实缺失");
    }

    @Test
    @DisplayName("server_uuid 漂移可被独立观测：库名/账号/端口/hostname 全同、uuid 变 ⇒ fingerprintSha1 必变")
    void serverUuidDriftChangesFingerprintDigest() {
        LiveFacts before = new LiveFacts(RUN_ID, FINGERPRINT, "v25it_rw", "analytics_metric_v25it",
                "uuid-A", "host-v25it", 8, 3307, Set.of("analytics_metric_v25it.SELECT"), Set.of());
        LiveFacts after = new LiveFacts(RUN_ID, FINGERPRINT, "v25it_rw", "analytics_metric_v25it",
                "uuid-B", "host-v25it", 8, 3307, Set.of("analytics_metric_v25it.SELECT"), Set.of());
        assertThat(before.sha1()).hasSize(40);
        assertThat(after.sha1()).hasSize(40);
        assertThat(before.sha1())
                .as("换实例（uuid 变）必须改变指纹sha1，即使其它事实全同")
                .isNotEqualTo(after.sha1());
        assertThat(before.redactedSummary()).contains("serverUuid=uuid-A");
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

    // ── 2c. 连接参数解析：系统属性 → 环境变量 → 档案；都没有即拒（无默认值） ──

    @Test
    @DisplayName("连接参数解析：系统属性优先于环境变量；全部缺失则拒绝，绝不回退正式地址/正式账号")
    void connectionPropertiesFailClosedWithoutAnySource() {
        Map<String, String> environment = Map.of("V25_IT_PROBE_KEY", "from-environment");
        // 系统属性优先于环境变量/档案
        System.setProperty(TestIsolationGuard.SYSTEM_PROPERTY_PREFIX + "probe.key", "from-system");
        try {
            assertThat(TestIsolationGuard.requiredProperty("probe.key", environment)).isEqualTo("from-system");
        } finally {
            System.clearProperty(TestIsolationGuard.SYSTEM_PROPERTY_PREFIX + "probe.key");
        }

        // 没有 -D 时，runner 可安全通过环境变量注入（无需把口令放命令行/档案）
        assertThat(TestIsolationGuard.requiredProperty("probe.key", environment)).isEqualTo("from-environment");

        // 两处都没有 → 拒绝，而不是给 127.0.0.1:3306 / root / metric_pub 之类的默认值
        assertThatThrownBy(() -> TestIsolationGuard.requiredProperty("definitely.absent.key"))
                .isInstanceOf(MissingConfigurationException.class)
                .hasMessageContaining("拒绝运行")
                .hasMessageContaining("不用正式账号/正式地址兜底");

        // 可空版本不抛，但也不会凭空造出默认值
        assertThat(TestIsolationGuard.optionalProperty("definitely.absent.key")).isNull();
    }

    @Test
    @DisplayName("V25_IT_* 命名稳定且完整环境来源可构造双库上下文；缺字段 fail-closed")
    void environmentConfigurationUsesStableNamesAndRequiresCompleteSource() {
        assertThat(TestIsolationGuard.environmentName("testRunId")).isEqualTo("V25_IT_TEST_RUN_ID");
        assertThat(TestIsolationGuard.environmentName("serverFingerprint")).isEqualTo("V25_IT_SERVER_FINGERPRINT");
        assertThat(TestIsolationGuard.environmentName("metaDb")).isEqualTo("V25_IT_META_DB");
        assertThat(TestIsolationGuard.environmentName("metric.publish.password"))
                .isEqualTo("V25_IT_METRIC_PUBLISH_PASSWORD");

        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : validProps(RUN_ID).entrySet()) {
            env.put(TestIsolationGuard.environmentName(entry.getKey()), entry.getValue());
        }
        TestIsolationConfig cfg = TestIsolationGuard.fromEnvironment(env);
        assertThat(cfg.testRunId()).isEqualTo(RUN_ID);
        assertThat(cfg.metaDb()).isNotEqualTo(cfg.metricDb());
        assertThat(cfg.source().getFileName().toString()).isEqualTo("v25-it-environment.marker");

        env.remove("V25_IT_METRIC_DB");
        assertThatThrownBy(() -> TestIsolationGuard.fromEnvironment(env))
                .isInstanceOf(MissingConfigurationException.class)
                .hasMessageContaining("metricDb")
                .hasMessageNotContaining("analytics_metric");
    }

    // ── 2d. DEV-001：MySQL 8 语义下「版本识别 + 四项判据真的执行」 ───────────

    @Test
    @DisplayName("主版本识别取 VERSION() 首段数字：现场 MySQL 8.0.41 可解析，认不出的形态一律判为不可用")
    void serverMajorVersionIsParsedFromVersionString() {
        assertThat(TestIsolationGuard.parseMajorVersion("8.0.41")).isEqualTo(8);
        assertThat(TestIsolationGuard.parseMajorVersion("8.0.41-0ubuntu0.22.04.1")).isEqualTo(8);
        assertThat(TestIsolationGuard.parseMajorVersion("5.7.44-log")).isEqualTo(5);
        assertThat(TestIsolationGuard.parseMajorVersion("8.4.0")).isEqualTo(8);
        assertThat(TestIsolationGuard.parseMajorVersion("10.11.6-MariaDB")).isEqualTo(10);
        for (String unusable : List.of("", "   ", "unknown", "v8.0.41", "MariaDB-10.11")) {
            assertThat(TestIsolationGuard.parseMajorVersion(unusable))
                    .as("无法解析的 VERSION() 原文「%s」必须判为不可用（fail-closed，不返回默认版本）", unusable)
                    .isEqualTo(-1);
        }
        assertThat(TestIsolationGuard.parseMajorVersion(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("SHOW GRANTS 的库名反引号必须归一化：本次库不被误判为范围外，禁止清单库仍被拦住")
    void grantScopeIdentifiersAreUnquoted() {
        // 实测 3307 隔离实例（MySQL 8.0.41）的 SHOW GRANTS 原文就是带反引号的 `db` 形态
        assertThat(TestIsolationGuard.unquoteIdentifier("`analytics_metric_v25it`"))
                .isEqualTo("analytics_metric_v25it");
        assertThat(TestIsolationGuard.unquoteIdentifier("*")).isEqualTo("*");
        assertThat(TestIsolationGuard.unquoteIdentifier("`a``b`")).isEqualTo("a`b");
        assertThat(TestIsolationGuard.unquoteIdentifier(null)).isEmpty();
    }

    @Test
    @DisplayName("MySQL 8 语义（@@version_major 报 1193）下四项判据真的执行：端口/账号/权限/指纹全部落地")
    void mysql8SemanticsRunAllFourJudgements(@TempDir Path dir) throws Exception {
        TestRunContext context = contextOf(dir);
        FakeMySql8 instance = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        // 两行都照抄实测 3307 的 SHOW GRANTS 原文（USAGE 占位行 + 本次库上的写权限行）：
        // 假实例的职责只是「提供 MySQL 8 的真实服务端事实」，不替门禁做判断。
        instance.grants.add("GRANT USAGE ON *.* TO `v25it_rw`@`%`");
        instance.grants.add("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER "
                + "ON `analytics_metric_v25it`.* TO `v25it_rw`@`%`");

        LiveFacts facts = TestIsolationGuard.verifyBeforeWrite(context, instance.dataSource(),
                "analytics_metric_v25it");

        assertThat(facts.serverVersionMajor())
                .as("DEV-001：MySQL 8 上版本识别必须成功，而不是在校验中途抛 ERROR 1193")
                .isEqualTo(8);
        assertThat(facts.port()).as("② 端口判据").isEqualTo(3307);
        assertThat(facts.account()).as("③ 账号判据").isEqualTo("v25it_rw");
        assertThat(facts.serverUuid()).isEqualTo("uuid-v25it");
        assertThat(facts.hostname()).isEqualTo("host-v25it");
        assertThat(facts.privileges())
                .as("④ 权限判据：SHOW GRANTS 必须真的读过，库名不带反引号，USAGE 不算权限")
                .contains("analytics_metric_v25it.INSERT")
                .doesNotContain("analytics_metric_v25it.USAGE");
        assertThat(facts.schemasWithWritePrivilege())
                .as("本次测试库上的写权限不算「本次测试库之外还有写权限」")
                .isEmpty();
        assertThat(facts.sha1()).hasSize(40);
        assertThat(instance.readSqls())
                .as("版本识别必须用 VERSION()；且端口/账号/权限/指纹四项判据的查询必须真的下发过")
                .containsExactly("SELECT DATABASE()", "SELECT CURRENT_USER()", "SELECT @@server_uuid",
                        "SELECT @@hostname", "SELECT VERSION()", "SELECT @@port", "SHOW GRANTS");
    }

    @Test
    @DisplayName("四项判据逐条可拒：实连 3306 / root 账号 / 越权写别的库 / 写禁止清单库 / 全局写 / 指纹不符 / 版本不可识别")
    void everyJudgementReallyRejects(@TempDir Path dir) throws Exception {
        TestRunContext context = contextOf(dir);

        FakeMySql8 formalPort = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        formalPort.port = 3306;
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context,
                formalPort.dataSource("jdbc:mysql://127.0.0.1:3307/analytics_metric_v25it"),
                "analytics_metric_v25it"))
                .as("② 端口判据：URL 里写 3307 也盖不住「实连到 3306」这个事实")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("宿主正式 MySQL 实例端口");

        FakeMySql8 rootAccount = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        rootAccount.account = "root@localhost";
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, rootAccount.dataSource(),
                "analytics_metric_v25it"))
                .as("③ 账号判据")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("禁止清单");

        FakeMySql8 otherSchemaWrite = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        otherSchemaWrite.grants.add("GRANT ALL PRIVILEGES ON `mall_simulator_test`.* TO `v25it_rw`@`%`");
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, otherSchemaWrite.dataSource(),
                "analytics_metric_v25it"))
                .as("④ 权限判据：本次测试库之外的写权限")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("本次测试库之外");

        FakeMySql8 forbiddenSchemaWrite = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        forbiddenSchemaWrite.grants.add("GRANT INSERT ON `analytics_metric`.* TO `v25it_rw`@`%`");
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, forbiddenSchemaWrite.dataSource(),
                "analytics_metric_v25it"))
                .as("④ 权限判据：对禁止清单库持有写权限（反引号不得让它漏检）")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("禁止清单库");

        FakeMySql8 globalWrite = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        globalWrite.grants.add("GRANT ALL PRIVILEGES ON *.* TO `v25it_rw`@`%`");
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, globalWrite.dataSource(),
                "analytics_metric_v25it"))
                .as("④ 权限判据：全局非只读权限")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("全局非只读权限");

        FakeMySql8 wrongFingerprint = new FakeMySql8("8.0.41", "analytics_metric_v25it");
        wrongFingerprint.serverUuid = "other-uuid";
        wrongFingerprint.hostname = "other-host";
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, wrongFingerprint.dataSource(),
                "analytics_metric_v25it"))
                .as("⑤ 指纹判据")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("服务实例指纹不匹配");

        FakeMySql8 unknownVersion = new FakeMySql8("unknown", "analytics_metric_v25it");
        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(context, unknownVersion.dataSource(),
                "analytics_metric_v25it"))
                .as("版本判据 fail-closed：识别不出服务端版本就拒绝，不降级、不跳过后续判据")
                .isInstanceOf(IsolationViolationException.class)
                .hasMessageContaining("无法从 SELECT VERSION() 识别服务端主版本号");
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

    /** 用合法配置构造本次运行的 {@link TestRunContext}（走 verifyContext，范围校验一并生效）。 */
    private static TestRunContext contextOf(Path dir) throws Exception {
        return TestIsolationGuard.verifyContext(TestIsolationGuard.loadFrom(
                writeProps(dir.resolve("p.properties"), validProps(RUN_ID))));
    }

    /** 同上，但登记一个指定的实例身份（用于指纹判据的正/反例）。 */
    private static TestRunContext contextOf(Path dir, String fingerprint) throws Exception {
        Map<String, String> props = validProps(RUN_ID);
        props.put("serverFingerprint", fingerprint);
        return TestIsolationGuard.verifyContext(TestIsolationGuard.loadFrom(
                writeProps(dir.resolve("p-fingerprint.properties"), props)));
    }

    /**
     * 只回答「MySQL 8 服务端事实」的假实例：不连任何库、不执行任何写入、不做任何判断。
     *
     * <p>存在的理由：DEV-001 的现场行为是 MySQL 8.0 对 {@code SELECT @@version_major} 回
     * {@code ERROR 1193 (HY000): Unknown system variable 'version_major'}。这里**照抄这个行为**，
     * 于是「门禁还在查 @@version_major」会立即表现为校验中断（后续判据一次都不跑），
     * 而修好之后会稳定跑完 7 条查询。授权原文同样照抄实测 3307 的 SHOW GRANTS 形态。</p>
     */
    private static final class FakeMySql8 {

        private final String version;
        private final String currentDb;
        private String account = "v25it_rw@%";
        private String serverUuid = "uuid-v25it";
        private String hostname = "host-v25it";
        private int port = 3307;
        private final List<String> grants = new ArrayList<>();
        private final List<String> readSqls = new ArrayList<>();

        FakeMySql8(String version, String currentDb) {
            this.version = version;
            this.currentDb = currentDb;
        }

        /** 门禁实际下发过的查询（按顺序）：用来证明四项判据真的执行，而不是中途抛异常退出。 */
        List<String> readSqls() {
            return readSqls;
        }

        DataSource dataSource() {
            return dataSource("jdbc:mysql://127.0.0.1:" + port + "/" + currentDb);
        }

        DataSource dataSource(String url) {
            return (DataSource) proxy(DataSource.class, (p, m, args) -> switch (m.getName()) {
                case "getUrl", "getURL", "getJdbcUrl" -> url;
                case "getConnection" -> connection();
                default -> defaultValue(m.getReturnType());
            });
        }

        private Connection connection() {
            return (Connection) proxy(Connection.class, (p, m, args) -> {
                if (!"prepareStatement".equals(m.getName())) {
                    return defaultValue(m.getReturnType());
                }
                String sql = ((String) args[0]).trim();
                readSqls.add(sql);
                if (sql.contains("@@version_major")) {
                    // MySQL 8.0.41 原样行为：ERROR 1193 (HY000): Unknown system variable 'version_major'
                    throw new SQLException("Unknown system variable 'version_major'", "HY000", 1193);
                }
                return preparedStatement(rowsFor(sql));
            });
        }

        private PreparedStatement preparedStatement(List<Object> rows) {
            return (PreparedStatement) proxy(PreparedStatement.class, (p, m, args) -> switch (m.getName()) {
                case "executeQuery" -> resultSet(rows);
                case "close" -> null;
                default -> defaultValue(m.getReturnType());
            });
        }

        private List<Object> rowsFor(String sql) throws SQLException {
            return switch (sql) {
                case "SELECT DATABASE()" -> List.of(currentDb);
                case "SELECT CURRENT_USER()" -> List.of(account);
                case "SELECT @@server_uuid" -> Collections.singletonList(serverUuid);
                case "SELECT @@hostname" -> List.of(hostname);
                case "SELECT VERSION()" -> List.of(version);
                case "SELECT @@port" -> List.of(port);
                case "SHOW GRANTS" -> List.copyOf(grants);
                default -> throw new SQLException("假实例未提供该查询（门禁不该下发未登记的语句）：" + sql);
            };
        }
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(TestIsolationGuardTest.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }

    private static ResultSet resultSet(List<Object> column) {
        Iterator<Object> rows = column.iterator();
        Object[] current = new Object[1];
        return (ResultSet) proxy(ResultSet.class, (p, m, args) -> switch (m.getName()) {
            case "next" -> {
                boolean has = rows.hasNext();
                if (has) {
                    current[0] = rows.next();
                }
                yield has;
            }
            case "getString" -> current[0] == null ? null : String.valueOf(current[0]);
            case "getInt" -> current[0] == null ? 0
                    : current[0] instanceof Number number
                    ? number.intValue() : Integer.parseInt(String.valueOf(current[0]));
            case "close" -> null;
            default -> defaultValue(m.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
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
