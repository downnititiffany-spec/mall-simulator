package com.graduation.analytics.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * 数据库/外部写入型测试的隔离门禁（指导书 V2.5 §9.4 的唯一所有者）。
 *
 * <p>解决的问题：{@code MetricAdsMySqlIT} / {@code MetricPublisherMySqlIT} 过去用真实连接
 * （{@code jdbc:mysql://127.0.0.1:3306/analytics_metric}）＋正式写账号 {@code metric_pub}
 * 直接对正式指标库做 INSERT/DELETE，只要有人加 {@code -Dmetric.it=true} 就有清空正式快照的能力。
 * 本类把「默认关闭、写前校验、账号最小权限、只清本次拥有的记录」变成**可执行**的代码，
 * 而不是文档里的约定。</p>
 *
 * <p><b>六条硬性质（对应 §9.4）</b>：</p>
 * <ol>
 *   <li><b>默认关闭、缺失即拒</b>：{@link #loadContext()} 找不到测试隔离配置就抛
 *       {@link MissingConfigurationException}；没有任何 {@code analytics_metric} 之类的正式库 fallback。</li>
 *   <li><b>写前校验</b>：{@link #verifyBeforeWrite} 用**实际连接**取 {@code SELECT DATABASE()} /
 *       {@code @@port} / {@code @@hostname}，与登记范围比对后放行；正式库地址**一定被拒**。
 *       实例指纹的唯一权威身份是 {@code hostname:port}（{@code @@server_uuid} 只作独立漂移事实）。</li>
 *   <li><b>账号最小权限</b>：{@link #verifyBeforeWrite} 从 {@code information_schema} 读**本账号实际权限**；
 *       root 直接拒，对库外 schema 有任何非 SELECT 权限也拒。库名含 test **不作为**安全证明。</li>
 *   <li><b>同一隔离范围</b>：{@code TestRunContext} 的 metaDb/metricDb/hiveNamespace/hdfsRoot/manifestRoot
 *       必须整体落在登记的测试范围内；「只改 JDBC URL」不能构造出合法的 context。</li>
 *   <li><b>cleanup 只清本次拥有的记录</b>：{@link WorkScope} 由不可复用的 {@code testRunId} 与本次
 *       显式拥有的记录 ID 构成；{@link #assertDeletionTargets} 在删除**之前**打印目标清单并逐条验范围。</li>
 *   <li><b>破坏性动作兜底</b>：{@link #assertNoTruncate} / {@link #assertSafeRecursiveDelete} /
 *       {@link #assertSafeManifestDelete} 把「禁止 TRUNCATE 正式库、删历史 manifest、递归删仓库根」变成断言。</li>
 * </ol>
 *
 * <p><b>本类不主动连库、不写任何数据</b>：所有对外动作都经调用方传入的 {@link DataSource}，
 * 且只执行 SELECT 与 {@code information_schema} 查询。</p>
 */
public final class TestIsolationGuard {

    /** 测试隔离档案的 classpath 资源名（V25-S02 产出）。 */
    public static final String CLASSPATH_RESOURCE = "integration.local.properties";

    /** 系统属性前缀：{@code -Dv25.it.testRunId=...}。系统属性优先于 classpath 档案。 */
    public static final String SYSTEM_PROPERTY_PREFIX = "v25.it.";

    /** testRunId 的合法形状：{@code v25it-<yyyyMMdd-HHmmss>-<4+ 位随机/序号>}。 */
    private static final Pattern TEST_RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$");

    /**
     * 生产/演示库禁止清单（与配置来源无关，硬编码，{@code 不可被档案覆盖}）。
     *
     * <p>这些是 demo 数据真正被页面读取的库；{@code analytics_metric_p103}/{@code _p105} 是
     * 历史副本（{@code metric_pub} 对它也有写权限），同样不允许作为测试目标——测试要用**本轮新建**的库。</p>
     */
    private static final Set<String> FORBIDDEN_DATABASES = Set.of(
            "analytics_metric",
            "analytics_meta",
            "analytics_metric_p103",
            "analytics_metric_p105",
            "analytics_meta_p103",
            "analytics_meta_p105",
            "analytics_meta_p105it",
            "analytics_meta_v17probe",
            "analytics_verify_m3_parity",
            "mall_simulator");

    /** 正式写账号禁止清单：测试必须用本次测试库自己的受限账号。 */
    private static final Set<String> FORBIDDEN_ACCOUNTS = Set.of(
            "root", "metric_pub", "p103_metric_pub", "p105_metric_pub", "metric_read", "mall_app");

    /**
     * 允许的隔离实例端口白名单（用户 R2 裁决 / Q9 裁定）。
     *
     * <p>隔离库必须与正式指标库在**实例/端口**层面分开：WSL 内独立 MySQL 实例监听 3307。
     * 宿主 3306 上的任何库（含 9 个 {@code analytics_*} 以及 {@code test_db} /
     * {@code mall_simulator_test}）**一律不在白名单内**——</p>
     *
     * <p><b>为什么不能靠库名判断</b>：把宿主正式实例上的库改名为 {@code xxx_test}，或者新建一个
     * {@code analytics_metric_test}，在实例与账号层面仍然和正式库共享同一套进程、同一份
     * {@code datadir}、同一批全局账号权限。库名是**声明**，实例指纹与端口才是**事实**。
     * 所以判据是「连接级」的：{@code JDBC URL} 预检端口 + 实连后 {@code @@port} 命中白名单
     * + 实例指纹 {@code @@hostname:@@port} 与登记值规范化后**完全一致**（{@code @@server_uuid}
     * 只作独立漂移事实，不参与指纹放行）。</p>
     */
    private static final Set<Integer> ALLOWED_INSTANCE_PORTS = Set.of(3307);

    /** 宿主正式实例端口：显式点名，避免「默认 3306」被当成无事发生。 */
    private static final int HOST_FORMAL_PORT = 3306;

    /** 只读权限集合：出现在**非当前库**上可以接受。 */
    private static final Set<String> READ_ONLY_PRIVILEGES = Set.of("SELECT");

    /**
     * MySQL 的「不授予任何权限」占位标记。
     *
     * <p>{@code GRANT USAGE ON *.* TO `u`@`%`} 是 SHOW GRANTS 对**每个**账号（含刚建的最小权限
     * 账号）输出的第一行，它本身不授予任何东西。不把它排除，「持有全局非只读权限」判据会被它
     * 触发，最小权限账号在真实 MySQL 上必被拒——这是 DEV-001 版本识别修好之后暴露出的第二层阻断。</p>
     *
     * <p>只忽略「什么都不授予」的 USAGE；{@code ALL PRIVILEGES} 与具体写权限的判定一字未动。</p>
     */
    private static final Set<String> NO_EFFECT_PRIVILEGES = Set.of("USAGE");

    private TestIsolationGuard() {
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. 默认关闭：配置缺失立即拒绝（不是 skip 后 PASS）
    // ────────────────────────────────────────────────────────────────────────

    /** 缺少/不完整的测试隔离配置。**是失败，不是跳过。** */
    public static final class MissingConfigurationException extends IllegalStateException {
        public MissingConfigurationException(String message) {
            super(message);
        }
    }

    /** 配置存在但试图指向正式库/正式账号/越范围目标。 */
    public static final class IsolationViolationException extends IllegalStateException {
        public IsolationViolationException(String message) {
            super(message);
        }
    }

    /** 测试隔离档案的全部键；与 {@link TestRunContext} 字段一一对应。 */
    public record TestIsolationConfig(Path source, String testRunId, String serverFingerprint,
                                      String metaDb, String metricDb, String hiveNamespace,
                                      String hdfsRoot, String manifestRoot, String credentialsRef) {

        public TestIsolationConfig {
            requireText(source == null ? null : source.toString(), "config.source");
            requireText(testRunId, "testRunId");
            requireText(serverFingerprint, "serverFingerprint");
            requireText(metaDb, "metaDb");
            requireText(metricDb, "metricDb");
            requireText(hiveNamespace, "hiveNamespace");
            requireText(hdfsRoot, "hdfsRoot");
            requireText(manifestRoot, "manifestRoot");
            requireText(credentialsRef, "credentialsRef");
            if (!TEST_RUN_ID.matcher(testRunId).matches()) {
                throw new IsolationViolationException("testRunId 形状非法（必须是单次运行唯一、可复查的标识）：" + testRunId);
            }
        }
    }

    /**
     * 加载测试隔离档案并构造 {@link TestRunContext}。
     *
     * <p>顺序：系统属性 {@code -Dv25.it.*} → classpath {@value #CLASSPATH_RESOURCE} →
     * 仓库根 {@code analytics-server/integration.local.properties}。三者都没有就
     * **抛 {@link MissingConfigurationException}**，绝不回退到任何正式库默认值。</p>
     */
    public static TestRunContext loadContext() {
        return verifyContext(load());
    }

    /** 只加载配置，不校验目标范围（负向测试需要在这一步观察拒绝行为）。 */
    public static TestIsolationConfig load() {
        if (hasAnySystemProperty()) {
            return fromSystemProperties();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return fromProperties(Paths.get("classpath:" + CLASSPATH_RESOURCE), props);
            }
        } catch (IOException e) {
            throw new MissingConfigurationException("读取 classpath:" + CLASSPATH_RESOURCE + " 失败：" + e.getMessage());
        }
        Path repoFile = RepoRoot.path("analytics-server").resolve(CLASSPATH_RESOURCE);
        if (Files.isRegularFile(repoFile)) {
            return loadFrom(repoFile);
        }
        throw new MissingConfigurationException("未提供测试隔离配置，拒绝运行外部写入型测试。"
                + "需要以下之一：(1) 系统属性 " + SYSTEM_PROPERTY_PREFIX + "*；"
                + "(2) classpath:" + CLASSPATH_RESOURCE + "；(3) " + repoFile
                + "。本门禁**不**提供 analytics_metric 等正式库 fallback。");
    }

    /** 从显式文件加载（负向测试用）。文件不存在也抛 {@link MissingConfigurationException}。 */
    public static TestIsolationConfig loadFrom(Path propertiesFile) {
        if (propertiesFile == null || !Files.isRegularFile(propertiesFile)) {
            throw new MissingConfigurationException("测试隔离档案不存在：" + propertiesFile);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new MissingConfigurationException("读取 " + propertiesFile + " 失败：" + e.getMessage());
        }
        return fromProperties(propertiesFile, props);
    }

    /** 用系统属性构造配置（每个键缺失都抛 {@link MissingConfigurationException}）。 */
    public static TestIsolationConfig fromSystemProperties() {
        Properties props = new Properties();
        for (String key : List.of("testRunId", "serverFingerprint", "metaDb", "metricDb", "hiveNamespace",
                "hdfsRoot", "manifestRoot", "credentialsRef")) {
            String value = System.getProperty(SYSTEM_PROPERTY_PREFIX + key);
            if (value != null && !value.isBlank()) {
                props.setProperty(key, value.trim());
            }
        }
        return fromProperties(systemPropertiesMarker(), props);
    }

    /**
     * 系统属性来源的标记路径。
     *
     * <p><b>不能</b>写成 {@code Paths.get("system-properties:v25.it.")}：带冒号的假 URI 在 Windows 上
     * 直接抛 {@code InvalidPathException}（{@code Illegal char <:>}），会让「用系统属性提供配置」
     * 这条入口在 Windows 上整个失效。这里用一个合法的本地路径作为来源标记。</p>
     */
    private static Path systemPropertiesMarker() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "v25-it-system-properties.marker");
    }

    private static boolean hasAnySystemProperty() {
        for (String key : List.of("testRunId", "metaDb", "metricDb", "serverFingerprint")) {
            String value = System.getProperty(SYSTEM_PROPERTY_PREFIX + key);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1b. 连接参数解析：系统属性优先，其次隔离档案（档案缺失即拒，不给默认值）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 解析连接类配置项（{@code mysql.host} / 账号 / 口令引用）。
     *
     * <p>解析顺序与隔离档案一致：系统属性 {@code v25.it.<key>} 优先，其次当前隔离档案文件。
     * <b>没有默认值</b>：两处都没有就抛 {@link MissingConfigurationException}——尤其不会
     * 退回 {@code 127.0.0.1:3306}、{@code root} 或 {@code metric_pub} 之类的正式目标。</p>
     *
     * <p>存在的意义：{@code TestRunContext} 的 8 个字段里<b>不含</b> host/账号，写入型 IT 若自行
     * 直接读系统属性，就会出现「档案里写了 host，测试却仍报缺少 -Dv25.it.mysql.host」的断层，
     * 逼使用户为了跑测试而在命令行重复粘正式地址。这里把档案作为同一来源统一解析。</p>
     */
    public static String requiredProperty(String key) {
        requireText(key, "key");
        String fromSystem = System.getProperty(SYSTEM_PROPERTY_PREFIX + key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        Properties fromProfile = isolationProfileFileProperties();
        String value = fromProfile.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        throw new MissingConfigurationException("缺少测试隔离配置项 " + SYSTEM_PROPERTY_PREFIX + key
                + "（系统属性或隔离档案 " + CLASSPATH_RESOURCE + "）：拒绝运行（不用正式账号/正式地址兜底）");
    }

    /**
     * 校验本次运行 testRunId 的形状（{@code ^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$}）并原样返回。
     *
     * <p>为什么必须单独暴露：{@link #requiredProperty(String)} 只保证「配置项存在」，
     * <b>不保证形状</b>。任何用 testRunId 拼路径/拼库名/拼目录名的地方，如果直接取
     * {@code requiredProperty("testRunId")}，就会把带空格、带 {@code /}、带 {@code ..}
     * 的值当成合法标识使用。V25-S03 的 SparkItGuard.runRoot() 就是这样拼工作目录的——
     * 探针发现 {@code -Dv25.it.testRunId="bad id with spaces"} 被放行。
     * 形状校验集中在这里，调用方一律走本方法。</p>
     *
     * @throws MissingConfigurationException 配置项缺失
     * @throws IsolationViolationException   形状非法
     */
    public static String requireTestRunId() {
        String runId = requiredProperty("testRunId");
        if (!TEST_RUN_ID.matcher(runId).matches()) {
            throw new IsolationViolationException("testRunId 形状非法：" + runId
                    + "（要求 ^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$）：拒绝运行——"
                    + "它会被拼进路径/库名/目录名，非法形状不得进入。");
        }
        return runId;
    }

    /** {@link #requiredProperty} 的可空版本，供「档案里没写也不该报错」的场景使用。 */
    public static String optionalProperty(String key) {        try {
            return requiredProperty(key);
        } catch (MissingConfigurationException e) {
            return null;
        }
    }

    /** 一次运行只解析一次档案文件；解析失败时**不在异常里回显任何属性值**（避免口令进日志）。 */
    private static Properties isolationProfileFileProperties() {
        if (profileFileResolved) {
            if (profileFileLoadFailure != null) {
                throw new MissingConfigurationException(profileFileLoadFailure);
            }
            return profileFileProps;
        }
        profileFileResolved = true;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                profileFileProps = props;
                return props;
            }
        } catch (IOException e) {
            profileFileLoadFailure = "读取 classpath:" + CLASSPATH_RESOURCE + " 失败：" + e.getMessage();
            throw new MissingConfigurationException(profileFileLoadFailure);
        }
        Path repoFile = RepoRoot.path("analytics-server").resolve(CLASSPATH_RESOURCE);
        if (Files.isRegularFile(repoFile)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(repoFile)) {
                props.load(in);
            } catch (IOException e) {
                profileFileLoadFailure = "读取 " + repoFile + " 失败：" + e.getMessage();
                throw new MissingConfigurationException(profileFileLoadFailure);
            }
            profileFileProps = props;
            return props;
        }
        profileFileProps = new Properties();
        profileFileLoadFailure = "未找到测试隔离档案（classpath:" + CLASSPATH_RESOURCE + " 或 " + repoFile
                + "）：拒绝运行（不用正式账号/正式地址兜底）";
        throw new MissingConfigurationException(profileFileLoadFailure);
    }

    private static boolean profileFileResolved = false;
    private static Properties profileFileProps = new Properties();
    private static String profileFileLoadFailure = null;

    private static TestIsolationConfig fromProperties(Path source, Properties props) {
        List<String> missing = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("testRunId", "serverFingerprint", "metaDb", "metricDb", "hiveNamespace",
                "hdfsRoot", "manifestRoot", "credentialsRef")) {
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            } else {
                values.put(key, value.trim());
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingConfigurationException("测试隔离配置不完整（" + source + "），缺少键：" + missing
                    + "。缺任何一项都直接拒绝运行，不用默认值补齐。");
        }
        return new TestIsolationConfig(source, values.get("testRunId"), values.get("serverFingerprint"),
                values.get("metaDb"), values.get("metricDb"), values.get("hiveNamespace"),
                values.get("hdfsRoot"), values.get("manifestRoot"), values.get("credentialsRef"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. TestRunContext：全部目标必须落在同一登记的隔离范围
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 一次测试运行的隔离上下文（§9.4 要求字段全在）。
     *
     * <p>构造即校验「同一登记隔离范围」：库名不得命中正式库禁止清单，Hive namespace 必须带
     * {@code testRunId} 前缀，HDFS/manifest 根路径必须位于登记的测试根之下且**不得**是
     * {@code /graduation/...} 集群路径。因此「单改 JDBC URL」无法构造出合法 context。</p>
     */
    public record TestRunContext(String testRunId,
                                 String serverFingerprint,
                                 String metaDb,
                                 String metricDb,
                                 String hiveNamespace,
                                 String hdfsRoot,
                                 String manifestRoot,
                                 String credentialsRef,
                                 long startedAt,
                                 Path configSource) {

        public TestRunContext {
            requireText(testRunId, "testRunId");
            requireText(serverFingerprint, "serverFingerprint");
            requireText(hiveNamespace, "hiveNamespace");
            requireText(hdfsRoot, "hdfsRoot");
            requireText(manifestRoot, "manifestRoot");
            requireText(credentialsRef, "credentialsRef");
            if (!TEST_RUN_ID.matcher(testRunId).matches()) {
                throw new IsolationViolationException("testRunId 形状非法：" + testRunId);
            }
            if (startedAt <= 0L) {
                throw new IsolationViolationException("startedAt 必须为正（epoch millis）");
            }
            if (metricDb != null && metricDb.equals(metaDb)) {
                throw new IsolationViolationException("metaDb 与 metricDb 不得是同一个库：" + metricDb);
            }
            rejectForbiddenDatabase("metaDb", metaDb);
            rejectForbiddenDatabase("metricDb", metricDb);
            requireScopedPath("hdfsRoot", hdfsRoot, testRunId);
            requireScopedPath("manifestRoot", manifestRoot, testRunId);
            if (!hiveNamespace.startsWith(testRunId)) {
                throw new IsolationViolationException("hiveNamespace 必须以 testRunId 开头（实际 " + hiveNamespace
                        + "，testRunId=" + testRunId + "）——仅新 snapshotId/新库名不构成上游隔离");
            }
            if (!credentialsRef.contains(testRunId)) {
                throw new IsolationViolationException("credentialsRef 必须引用本次运行自己的受限账号（实际 " + credentialsRef
                        + "，testRunId=" + testRunId + "）");
            }
        }

        public long elapsedMillis(long nowMillis) {
            return nowMillis - startedAt;
        }

        /** 供证据日志使用的脱敏摘要（不含口令，只引用 credentialsRef）。 */
        public String redactedSummary() {
            return "testRunId=" + testRunId
                    + " serverFingerprint=" + serverFingerprint
                    + " metaDb=" + metaDb
                    + " metricDb=" + metricDb
                    + " hiveNamespace=" + hiveNamespace
                    + " hdfsRoot=" + hdfsRoot
                    + " manifestRoot=" + manifestRoot
                    + " credentialsRef=" + credentialsRef
                    + " startedAt=" + startedAt;
        }
    }

    /** 构造并校验 context（供 {@link #loadContext()} 与显式建档路径共用）。 */
    public static TestRunContext verifyContext(TestIsolationConfig cfg) {
        return new TestRunContext(cfg.testRunId(), cfg.serverFingerprint(), cfg.metaDb(), cfg.metricDb(),
                cfg.hiveNamespace(), cfg.hdfsRoot(), cfg.manifestRoot(), cfg.credentialsRef(),
                System.currentTimeMillis(), cfg.source());
    }

    /** 从 config 构造 context 但使用给定的 startedAt/now（负向测试要固定时间）。 */
    public static TestRunContext contextOf(TestIsolationConfig cfg, long startedAt) {
        return new TestRunContext(cfg.testRunId(), cfg.serverFingerprint(), cfg.metaDb(), cfg.metricDb(),
                cfg.hiveNamespace(), cfg.hdfsRoot(), cfg.manifestRoot(), cfg.credentialsRef(),
                startedAt, cfg.source());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. 写前校验：实际连接 + 实际权限
    // ────────────────────────────────────────────────────────────────────────

    /** 写前校验取到的实际库事实（全部来自 SELECT / information_schema）。 */
    public record LiveFacts(String testRunId, String serverFingerprint, String account, String currentDb,
                            String serverUuid, String hostname, int serverVersionMajor, int port,
                            Set<String> privileges, Set<String> schemasWithWritePrivilege) {

        public String sha1() {
            String material = String.join("\n", List.of(
                    nullSafe(testRunId), nullSafe(serverFingerprint), nullSafe(account), nullSafe(currentDb),
                    nullSafe(serverUuid), nullSafe(hostname), String.valueOf(serverVersionMajor),
                    String.valueOf(port),
                    new TreeSet<>(privileges).toString(), new TreeSet<>(schemasWithWritePrivilege).toString()));
            return TestRunDigest.sha1Hex(material);
        }

        public String redactedSummary() {
            return "testRunId=" + testRunId
                    + " account=" + account
                    + " currentDb=" + currentDb
                    + " serverUuid=" + serverUuid
                    + " hostname=" + hostname
                    + " port=" + port
                    + " serverVersionMajor=" + serverVersionMajor
                    + " privileges=" + new TreeSet<>(privileges)
                    + " schemasWithWritePrivilege=" + new TreeSet<>(schemasWithWritePrivilege)
                    + " fingerprintSha1=" + sha1();
        }

        private static String nullSafe(String value) {
            return value == null ? "" : value;
        }
    }

    /**
     * 执行任何 DDL/DML **之前**调用：用实际连接核对隔离白名单与账号最小权限。
     *
     * <p>校验项：① 实际 {@code SELECT DATABASE()} 必须等于 {@code context.metricDb()}/{@code metaDb()}；
     * ② 服务实例指纹必须与登记的 {@code serverFingerprint} 一致——**唯一权威身份是
     * {@code hostname:port}**（{@code @@hostname + ":" + @@port}），规范化后必须完全相等；
     * {@code @@server_uuid} 仍是必须取到的独立漂移事实（取不到即 fail-closed），但**不得**作为
     * 指纹的替代合法值（总控 2026-09-14 复核裁定）；③ 账号不是 root/正式写账号；④ 该账号除当前库外不得对其他 schema
     * 有任何非 SELECT 权限，对禁止清单库不得有任何权限；⑤ **实例端口必须命中隔离实例白名单**
     * （{@code @@port} ∈ {@link #ALLOWED_INSTANCE_PORTS}）——这是「实例层面隔离」的判据，
     * 库名里有没有 test 字样不作为安全证明。</p>
     *
     * <p>⑤ 与 ② 是**两层独立约束**：端口白名单管「不许落在正式实例上」，指纹管「必须是登记的
     * 那一台」，不靠其中一层兜底另一层。</p>
     *
     * @param dataSource 本次测试自己的数据源（只执行 SELECT）
     * @param database   调用方声明「这个连接应该落在哪个库」
     */
    public static LiveFacts verifyBeforeWrite(TestRunContext context, DataSource dataSource, String database) {
        if (context == null) {
            throw new MissingConfigurationException("TestRunContext 为空：写前校验无法进行，拒绝继续");
        }
        if (dataSource == null) {
            throw new MissingConfigurationException("DataSource 为空：写前校验无法进行，拒绝继续");
        }
        rejectForbiddenDatabase("target", database);
        if (!database.equals(context.metricDb()) && !database.equals(context.metaDb())) {
            throw new IsolationViolationException("目标库 " + database + " 不在本次登记范围 {"
                    + context.metaDb() + ", " + context.metricDb() + "} 内");
        }
        // 连接级预检：能在**不开连接**的情况下判定目标实例时，就先判定（拒绝不留连接痕迹）。
        assertJdbcTargetAllowed(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            String currentDb = scalarString(connection, "SELECT DATABASE()");
            String account = scalarString(connection, "SELECT CURRENT_USER()");
            String serverUuid = requireServerUuid(connection);
            String hostname = scalarString(connection, "SELECT @@hostname");
            // 主版本号必须用 VERSION() 解析：@@version_major 在本项目的两个真实例（宿主 3306 与
            // WSL 3307，均 8.0.41）上实测直接 ERROR 1193 (HY000): Unknown system variable 'version_major'，
            // 旧实现在这一条就抛异常退出，让下面的端口/账号/权限/指纹四项判据**一次都不执行**（DEV-001）。
            int major = majorVersion(connection);
            int port = scalarInt(connection, "SELECT @@port");
            if (currentDb == null || currentDb.isBlank()) {
                throw new IsolationViolationException("实际连接没有当前库（SELECT DATABASE() 为空）：JDBC URL 必须显式带库名");
            }
            if (!database.equals(currentDb)) {
                throw new IsolationViolationException("实际连接库（" + currentDb + "）与声明目标库（" + database
                        + "）不一致：判定为配置漂移，拒绝写入");
            }
            rejectForbiddenDatabase("实际连接", currentDb);
            rejectForbiddenInstancePort(port);

            String bareAccount = account == null ? "" : account.split("@")[0].replace("`", "").trim();
            if (FORBIDDEN_ACCOUNTS.contains(bareAccount.toLowerCase())) {
                throw new IsolationViolationException("账号 " + bareAccount
                        + " 属禁止清单（root 或正式库账号）：测试必须用本次测试库自己的受限账号");
            }

            Set<String> privileges = new LinkedHashSet<>();
            Set<String> schemasWithWrite = new TreeSet<>();
            collectPrivileges(connection, privileges, schemasWithWrite, currentDb);

            String expected = context.serverFingerprint();
            if (!fingerprintMatches(expected, hostname, port)) {
                throw new IsolationViolationException("服务实例指纹不匹配：登记=" + expected
                        + "，实际 hostname=" + hostname + " port=" + port
                        + "（唯一权威身份形如 hostname:port，规范化后必须完全相等；"
                        + "独立漂移事实 server_uuid=" + serverUuid + " 不得替代指纹判定）");
            }
            LiveFacts facts = new LiveFacts(context.testRunId(), expected, bareAccount, currentDb,
                    serverUuid, hostname, major, port, privileges, schemasWithWrite);
            printFacts(facts);
            return facts;
        } catch (SQLException e) {
            throw new IllegalStateException("写前校验无法完成（连接/查询失败）：" + e.getMessage(), e);
        }
    }

    /**
     * 读该账号的**实际**权限：{@code SHOW GRANTS}（驱动侧返回的 GRANT 原文），不靠配置声明。
     *
     * <p>{@code SHOW GRANTS} 是 MySQL 的白名单语句，任何账号都能看自己的授权；
     * 用原文解析可以避开 {@code information_schema.GRANTEE} 引号形态差异带来的漏判。</p>
     */
    private static void collectPrivileges(Connection connection, Set<String> privileges,
                                          Set<String> schemasWithWrite, String currentDb) throws SQLException {
        List<String> grants = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SHOW GRANTS");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                grants.add(rs.getString(1));
            }
        }
        for (String grant : grants) {
            System.out.println("[isolation-guard] 账号授权原文：" + grant);
            int onIdx = grant.toUpperCase().indexOf(" ON ");
            if (onIdx < 0) {
                continue;
            }
            String afterOn = grant.substring(onIdx + 4).trim();
            int toIdx = afterOn.toUpperCase().indexOf(" TO ");
            if (toIdx < 0) {
                continue;
            }
            // MySQL 的 SHOW GRANTS 把库名写成 `db`（实测 8.0.41）。归一化放在「剥掉 .* 之后」：
            // 原文是 `db`.* ，反引号只包住库名，先剥 .* 再剥反引号才对得上判断。
            // 不归一化会让「本次测试库」被判成「本次测试库之外」（合法受限账号被误拒），
            // 同时让禁止清单库的写权限漏检。
            String scope = afterOn.substring(0, toIdx).trim();
            String privText = grant.substring(0, onIdx).replaceFirst("(?i)^GRANT\\s+", "").trim();
            for (String raw : privText.split(",")) {
                String privilege = raw.trim().toUpperCase();
                if (privilege.isEmpty() || NO_EFFECT_PRIVILEGES.contains(privilege)) {
                    // USAGE = 「不授予任何权限」的占位标记（每个 MySQL 账号都有 GRANT USAGE ON *.*）：
                    // 它不属于任何权限，跳过它不是放宽判据，而是不让占位标记冒充全局写权限。
                    continue;
                }
                String schema = unquoteIdentifier(scope.endsWith(".*")
                        ? scope.substring(0, scope.length() - 2) : scope);
                privileges.add(schema + "." + privilege);
                boolean readOnly = READ_ONLY_PRIVILEGES.contains(privilege);
                if (!readOnly && FORBIDDEN_DATABASES.contains(schema)) {
                    throw new IsolationViolationException("账号对禁止清单库 " + schema + " 持有 " + privilege
                            + " 权限：测试账号必须最小权限，且不得触达正式库");
                }
                if (!readOnly && !"*".equals(schema) && !currentDb.equals(schema)) {
                    schemasWithWrite.add(schema);
                }
                if ("*".equals(schema) && !readOnly) {
                    throw new IsolationViolationException("账号持有全局非只读权限 " + privilege
                            + "：测试不得使用全局写权限账号");
                }
            }
        }
        if (!schemasWithWrite.isEmpty()) {
            throw new IsolationViolationException("账号在本次测试库之外还有写权限："
                    + new TreeSet<>(schemasWithWrite) + "（必须只授权本次测试库）");
        }
    }

    /**
     * 实例端口白名单判据：{@code port} 必须命中 {@link #ALLOWED_INSTANCE_PORTS}。
     *
     * <p>拒绝时显式区分「宿主正式实例端口」这一最常见错误，让报错本身就能说明
     * 「你为什么踩回了 F-100」。</p>
     */
    private static void rejectForbiddenInstancePort(int port) {
        if (port <= 0) {
            throw new MissingConfigurationException("无法从连接读取 @@port（实际 " + port
                    + "）：实例层面隔离无法证实，拒绝继续");
        }
        if (!ALLOWED_INSTANCE_PORTS.contains(port)) {
            String why = port == HOST_FORMAL_PORT
                    ? "这是宿主正式 MySQL 实例端口：宿主上的任何库（含 test_db / mall_simulator_test）都不是隔离环境"
                    : "不在隔离实例端口白名单 " + new TreeSet<>(ALLOWED_INSTANCE_PORTS) + " 内";
            throw new IsolationViolationException("排除：实例端口 " + port + " " + why
                    + "。隔离必须落在 WSL 独立实例（端口 3307），库名含 test 不构成安全证明");
        }
    }

    /**
     * 连接级预检：在**不建立连接**的前提下，从 {@link DataSource} 能看到的 JDBC URL 里判定目标实例。
     *
     * <p>这是「拒绝发生在任何写入之前」的最强形式——连 {@code SELECT DATABASE()} 这类只读探测
     * 都没有下发到目标实例。URL 不可读时不在这里失败：实连后的 {@code @@port} 检查仍会兜住。</p>
     */
    public static void assertJdbcTargetAllowed(DataSource dataSource) {
        String url = readableJdbcUrl(dataSource);
        if (url == null) {
            return;
        }
        Matcher matcher = Pattern.compile("(?i)^jdbc:mysql://([^/]+)(?:/([^?]*))?").matcher(url.trim());
        if (!matcher.find()) {
            throw new IsolationViolationException("无法识别的 JDBC URL 形态（只允许 jdbc:mysql:// 直连形态）：" + url);
        }
        String authority = matcher.group(1);
        String database = matcher.group(2);
        List<String> hosts = new ArrayList<>();
        for (String part : authority.split(",")) {
            String host = part.trim();
            int colon = host.lastIndexOf(':');
            int port = -1;
            if (colon >= 0) {
                String portText = host.substring(colon + 1).trim();
                host = host.substring(0, colon).trim();
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    throw new IsolationViolationException("JDBC URL 端口非法：" + portText + "（" + url + "）");
                }
            } else {
                port = HOST_FORMAL_PORT; // MySQL 驱动省略端口时按 3306 处理
            }
            hosts.add(host);
            rejectForbiddenInstancePort(port);
        }
        for (String host : hosts) {
            String normalized = host.toLowerCase();
            boolean local = normalized.equals("localhost") || normalized.equals("127.0.0.1")
                    || normalized.equals("::1") || normalized.equals("[::1]");
            if (!local) {
                throw new IsolationViolationException("JDBC 目标主机 " + host
                        + " 不是本机隔离实例：禁止把测试指向任何远端/宿主实例（" + url + "）");
            }
        }
        if (database != null && !database.isBlank()) {
            rejectForbiddenDatabase("JDBC URL 库名", database);
        }
    }

    /** 尽力读出 DataSource 的 JDBC URL（拿不到就返回 null，由实连后的端口检查兜底）。 */
    private static String readableJdbcUrl(DataSource dataSource) {
        for (String accessor : List.of("getUrl", "getURL", "getJdbcUrl")) {
            try {
                Object value = dataSource.getClass().getMethod(accessor).invoke(dataSource);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 换下一个取值方式
            }
        }
        return null;
    }

    /**
     * 实例指纹匹配：**唯一权威身份是 {@code hostname:port}**（DEV-002 N-3 收口，2026-09-14 总控复核裁定）。
     *
     * <p>登记值必须形如 {@code <hostname>:<port>}；实连读到的是 {@code @@hostname} 与 {@code @@port}。
     * 两侧各做 trim，hostname 段忽略大小写，**只有规范化后完全相等才通过**：</p>
     *
     * <ul>
     *   <li>{@code expected=dahaishui:3307} ＋ 实际 {@code dahaishui:3307} ⇒ <b>通过</b></li>
     *   <li>{@code expected=dahaishui:3307} ＋ 实际 {@code dahaishui:3306} ⇒ <b>拒绝</b>（同机不同实例）</li>
     *   <li>{@code expected=dahaishui:3307} ＋ 实际 {@code otherhost:3307} ⇒ <b>拒绝</b>（同端口不同机）</li>
     * </ul>
     *
     * <p><b>禁止 server_uuid 替代</b>：{@code expected} 若填成 {@code @@server_uuid} 的值，即便那台实例的
     * uuid 真的等于登记值，也**一律拒绝**——不允许出现「hostname:port 不匹配，但因为 uuid 匹配所以指纹
     * 仍然通过」的替代关系。{@code @@server_uuid} 仍是独立的 fail-closed 漂移事实（见
     * {@link #requireServerUuid}、{@link LiveFacts#sha1()}），只是不参与指纹放行。</p>
     *
     * <p>旧实现额外接受裸 {@code hostname}、任意 {@code hostname:<端口>} 后缀、以及 uuid，属于「同一登记值
     * 在不同判据下含义不同」的词汇表不对称，本次一并收紧；收紧只会让门禁更严，不会放宽任何条件。</p>
     *
     * @param expected 登记实例身份，形如 {@code hostname:port}
     * @param hostname 实连读到的 {@code @@hostname}
     * @param port     实连读到的 {@code @@port}
     */
    public static boolean fingerprintMatches(String expected, String hostname, int port) {
        if (expected == null || expected.isBlank() || hostname == null || hostname.isBlank() || port <= 0) {
            return false;
        }
        return expected.trim().equalsIgnoreCase(hostname.trim() + ":" + port);
    }

    /**
     * 读 {@code @@server_uuid}：独立于指纹的**漂移事实**，缺失即 fail-closed。
     *
     * <p>它不再参与指纹放行（见 {@link #fingerprintMatches}），但仍然是「同一台实例」的第二重事实：
     * 取值失败由上层包成「写前校验无法完成」，取到空值则在此直接拒绝，两者都不降级、不跳过。</p>
     */
    static String requireServerUuid(Connection connection) throws SQLException {
        String uuid = scalarString(connection, "SELECT @@server_uuid");
        if (uuid == null || uuid.isBlank()) {
            throw new IsolationViolationException("无法读取 @@server_uuid（实际 " + uuid
                    + "）：实例身份事实缺失，拒绝继续（写前校验不降级、不跳过）");
        }
        return uuid;
    }

    private static void printFacts(LiveFacts facts) {
        System.out.println("[isolation-guard] 写前校验通过 " + facts.redactedSummary());
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * 读服务端主版本号：{@code SELECT VERSION()} 原文的**首段数字**。
     *
     * <p><b>为什么不用 {@code @@version_major}</b>：那是 MariaDB 的系统变量。MySQL 8.0 上执行
     * {@code SELECT @@version_major} 会直接 {@code ERROR 1193 (HY000): Unknown system variable
     * 'version_major'}，被下面的 {@code catch (SQLException)} 包成
     * {@code IllegalStateException("写前校验无法完成")} 抛出——校验在这一条就中断，
     * 端口/账号/权限/指纹四项判据一次都没跑（DEV-001：30 个 mall IT 假红）。</p>
     *
     * <p>{@code VERSION()} 在 MySQL 5.7 / 8.0 / 8.4 与 MariaDB 上都存在且稳定：
     * {@code 8.0.41}、{@code 8.0.41-0ubuntu0.22.04.1}、{@code 5.7.44-log}、
     * {@code 10.11.6-MariaDB} 都能取到首段数字。</p>
     *
     * <p><b>解析不出来即拒（fail-closed）</b>：不认识版本号的实例不允许继续写入——
     * 宁可报「实例不可信」，也不把未知版本当成通过。这里不降级、不吞异常、不返回默认值。</p>
     */
    private static int majorVersion(Connection connection) throws SQLException {
        String version = scalarString(connection, "SELECT VERSION()");
        int major = parseMajorVersion(version);
        if (major <= 0) {
            throw new IsolationViolationException("无法从 SELECT VERSION() 识别服务端主版本号（实际 \""
                    + version + "\"）：实例不可信，拒绝继续（写前校验不降级、不跳过）");
        }
        return major;
    }

    /**
     * 从 {@code VERSION()} 原文解析主版本号；无法解析返回 {@code -1}。
     *
     * <p>包级可见是为了让 {@code TestIsolationGuardTest} 在不连库的情况下覆盖各种版本串形态
     * （MySQL 8 是 DEV-001 的现场版本）。判据本身仍是 {@link #majorVersion(Connection)} 的 fail-closed。</p>
     */
    static int parseMajorVersion(String version) {
        if (version == null) {
            return -1;
        }
        Matcher matcher = VERSION_MAJOR.matcher(version.trim());
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code VERSION()} 首段数字：要求「数字串位于开头，且其后是 '.' 或字符串结束」。 */
    private static final Pattern VERSION_MAJOR = Pattern.compile("^(\\d{1,3})(?:\\.|$)");

    /**
     * 去掉标识符的一层反引号并还原转义：{@code `db`} → {@code db}，{@code `a``b`} → {@code a`b}。
     *
     * <p>判据来源是 {@code SHOW GRANTS} 原文，而 MySQL 把 {@code ON `db`.*} 作为标准输出形态
     * （实测 8.0.41 隔离实例：{@code GRANT ALL PRIVILEGES ON `analytics_meta_...`.* TO ...}）。
     * 不做这一步：
     * ① 本次测试库会因为「多了两个反引号」被判成范围外，合法受限账号被误拒；
     * ② 禁止清单库的写权限会**漏检**（{@code `analytics_metric`} 与 {@code analytics_metric} 不等）。</p>
     *
     * <p>只剥一层反引号，不改变任何权限语义；判据仍然要求「除当前库外没有任何非只读权限」。</p>
     */
    static String unquoteIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String value = identifier.trim();
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            value = value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. cleanup 范围：只清本次 testRunId 拥有的记录
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 一次运行拥有的写入范围。
     *
     * <p>{@code runtime_profile_id} 是**可复用**的登记 ID，不能单独作为删除依据：
     * 历史版本的 cleanup 用 {@code DELETE FROM metric_snapshot WHERE runtime_profile_id = ?}
     * 一次删掉该档案下所有快照。本 record 要求删除目标必须同时命中 {@code testRunId} 与
     * 本次显式拥有的记录 ID。</p>
     */
    public record WorkScope(String testRunId, long profileId, Set<String> ownedSnapshotIds,
                            Set<String> ownedTables) {

        public WorkScope {
            requireText(testRunId, "workScope.testRunId");
            if (profileId <= 0L) {
                throw new IsolationViolationException("workScope.profileId 必须为正");
            }
            if (ownedSnapshotIds == null || ownedSnapshotIds.isEmpty()) {
                throw new IsolationViolationException("workScope.ownedSnapshotIds 不得为空（必须显式列出本次拥有的快照）");
            }
            if (ownedTables == null || ownedTables.isEmpty()) {
                throw new IsolationViolationException("workScope.ownedTables 不得为空");
            }
            ownedSnapshotIds = Set.copyOf(ownedSnapshotIds);
            ownedTables = Set.copyOf(ownedTables);
        }

        /** 快照号必须带 testRunId 前缀：证明它是本轮新建、不可复用的 ID。 */
        public void requireOwnedSnapshot(String snapshotId) {
            requireText(snapshotId, "snapshotId");
            if (!ownedSnapshotIds.contains(snapshotId)) {
                throw new IsolationViolationException("快照 " + snapshotId + " 不在本次拥有清单内");
            }
            if (!snapshotId.startsWith(testRunId)) {
                throw new IsolationViolationException("快照 " + snapshotId + " 不带 testRunId 前缀 "
                        + testRunId + "：不可复用 ID 不得作为删除依据");
            }
        }

        public void requireOwnedTable(String table) {
            if (!ownedTables.contains(table)) {
                throw new IsolationViolationException("表 " + table + " 不在本次拥有清单内");
            }
        }
    }

    /** 一条待删除记录的实际库内事实：清理前必须先查出来核对。 */
    public record DeletionTarget(String snapshotId, long actualProfileId, String status, Integer activeFlag) {

        public String describe() {
            return "snapshot_id=" + snapshotId + " runtime_profile_id=" + actualProfileId
                    + " status=" + status + " active_flag=" + activeFlag;
        }
    }

    /**
     * 删除**之前**：打印目标清单并逐条验证范围（§9.4「删除前输出目标清单并验证范围」）。
     *
     * @throws IsolationViolationException 任一目标不属于本次 {@code testRunId} 拥有的记录
     */
    public static void assertDeletionTargets(WorkScope scope, List<DeletionTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            System.out.println("[isolation-guard] cleanup 目标清单为空（无本次拥有的记录，跳过删除）testRunId="
                    + scope.testRunId());
            return;
        }
        System.out.println("[isolation-guard] cleanup 目标清单（删除前核对）count=" + targets.size());
        for (DeletionTarget target : targets) {
            System.out.println("[isolation-guard]   - " + target.describe());
            scope.requireOwnedSnapshot(target.snapshotId());
            if (target.actualProfileId() != scope.profileId()) {
                throw new IsolationViolationException("目标 " + target.snapshotId()
                        + " 的 runtime_profile_id=" + target.actualProfileId()
                        + " 与本次运行的 profileId=" + scope.profileId() + " 不一致：拒绝删除");
            }
        }
        System.out.println("[isolation-guard] cleanup 目标清单核对通过 testRunId=" + scope.testRunId());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. 绝对禁止的破坏性动作
    // ────────────────────────────────────────────────────────────────────────

    /** 禁止 TRUNCATE（正式库绝对禁止；测试库也一律用按 ID 的 DELETE，便于核对与回放）。 */
    public static void assertNoTruncate(String sql) {
        if (sql == null) {
            throw new IsolationViolationException("SQL 为空");
        }
        String normalized = sql.replaceAll("\\s+", " ").trim().toUpperCase();
        if (normalized.startsWith("TRUNCATE") || normalized.contains(" TRUNCATE ")) {
            throw new IsolationViolationException("测试代码不得出现 TRUNCATE：" + oneLine(sql));
        }
    }

    /**
     * 递归删除前的兜底：目标必须是仓库内的普通测试目录，不能是仓库根/盘符根/home 等。
     */
    public static void assertSafeRecursiveDelete(Path target) {
        if (target == null) {
            throw new IsolationViolationException("递归删除目标为空");
        }
        Path normalized = target.toAbsolutePath().normalize();
        Path repo = RepoRoot.path().toAbsolutePath().normalize();
        if (normalized.equals(repo)) {
            throw new IsolationViolationException("拒绝递归删除仓库根：" + normalized);
        }
        if (normalized.getParent() == null) {
            throw new IsolationViolationException("拒绝递归删除文件系统根：" + normalized);
        }
        if (!normalized.startsWith(repo)) {
            throw new IsolationViolationException("拒绝递归删除仓库外路径：" + normalized);
        }
        if (normalized.getNameCount() <= repo.getNameCount()) {
            throw new IsolationViolationException("拒绝递归删除仓库根层级：" + normalized);
        }
    }

    /** 历史 manifest 是不可变证据：只允许清理本次 testRunId 目录下的 manifest。 */
    public static void assertSafeManifestDelete(TestRunContext context, Path manifestPath) {
        if (manifestPath == null) {
            throw new IsolationViolationException("manifest 路径为空");
        }
        Path normalized = manifestPath.toAbsolutePath().normalize();
        Path root = Paths.get(context.manifestRoot()).toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IsolationViolationException("拒绝删除 manifestRoot 之外的路径：" + normalized + "（root=" + root + "）");
        }
        if (!normalized.toString().contains(context.testRunId())) {
            throw new IsolationViolationException("拒绝删除非本次运行的 manifest（路径不含 testRunId "
                    + context.testRunId() + "）：" + normalized);
        }
    }

    /** 集群路径护栏：任何测试都不得把目标指向 /graduation/**。 */
    public static void assertNotClusterPath(String path) {
        if (path == null) {
            return;
        }
        String normalized = path.replace('\\', '/').toLowerCase();
        if (normalized.contains("/graduation/") || normalized.startsWith("hdfs://") && normalized.contains("graduation")) {
            throw new IsolationViolationException("测试不得触碰集群路径 /graduation/**：" + path);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. 跑前/跑后证据：计数 + 关键行 checksum/快照指针
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 只读证据：正式库（或任何被观测库）的计数 + 关键行 checksum + ACTIVE 指针。
     *
     * <p>行数相等不足以证明零修改，所以这里同时给出由**排序后的整行拼接**算出的 MD5：
     * 行内容变化、顺序变化（在排序键内）都会改变它。调用方必须同时保存账号权限结论。</p>
     */
    public static ContentFingerprint captureContent(DataSource dataSource, String database, String snapshotTable,
                                                    String valueTable) {
        rejectForbiddenDatabase("观测目标", database);
        try (Connection connection = dataSource.getConnection()) {
            String table = database + "." + snapshotTable;
            long snapshots = scalarLong(connection, "SELECT COUNT(*) FROM " + table);
            String snapDigest = scalarString(connection, "SELECT COALESCE(MD5(GROUP_CONCAT(row_hash ORDER BY row_hash SEPARATOR '|')), 'EMPTY') "
                    + "FROM (SELECT MD5(CONCAT_WS('~', snapshot_id, runtime_profile_id, status, version, "
                    + "COALESCE(active_flag, -1), COALESCE(business_time, ''), COALESCE(source, ''))) AS row_hash FROM "
                    + table + ") t");
            long values = scalarLong(connection, "SELECT COUNT(*) FROM " + database + "." + valueTable);
            String pointer = scalarString(connection, "SELECT COALESCE(GROUP_CONCAT(CONCAT(snapshot_id, ':', version) "
                    + "ORDER BY snapshot_id SEPARATOR ','), 'NONE') FROM " + table + " WHERE active_flag = 1");
            return new ContentFingerprint(database, snapshotTable, snapshots, snapDigest,
                    valueTable, values, pointer);
        } catch (SQLException e) {
            throw new IllegalStateException("抓取只读证据失败（" + database + "）：" + e.getMessage(), e);
        }
    }

    /** 只读内容指纹：计数 + checksum + ACTIVE 指针。 */
    public record ContentFingerprint(String database, String snapshotTable, long snapshotRows, String snapshotDigest,
                                     String valueTable, long valueRows, String activePointer) {

        public String describe() {
            return database + "." + snapshotTable + " rows=" + snapshotRows + " md5=" + snapshotDigest
                    + " | " + database + "." + valueTable + " rows=" + valueRows
                    + " | activePointer=" + activePointer;
        }

        public boolean sameContent(ContentFingerprint other) {
            return other != null
                    && snapshotRows == other.snapshotRows
                    && valueRows == other.valueRows
                    && nullToEmpty(snapshotDigest).equals(other.snapshotDigest)
                    && nullToEmpty(activePointer).equals(other.activePointer);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ────────────────────────────────────────────────────────────────────────

    private static void rejectForbiddenDatabase(String role, String database) {
        if (database == null || database.isBlank()) {
            throw new MissingConfigurationException(role + " 库名为空：拒绝运行");
        }
        if (FORBIDDEN_DATABASES.contains(database.trim())) {
            throw new IsolationViolationException(role + " 指向正式/演示库 " + database
                    + "：测试禁止连正式库（也不允许先写再回滚）");
        }
    }

    /**
     * 隔离根必须同时满足三条：不含集群路径、是绝对路径、位于本次 testRunId 目录下。
     *
     * <p>这里刻意**不接受** {@code hdfs://} 与 {@code file://} URI 这两种写法：
     * 隔离配置里的 {@code hdfsRoot} 是「本次运行可写可删的落地根」，必须是本机文件系统上的绝对路径。
     * 写成 URI 会让后续的「按 runId 校验目录归属」「安全递归删除」等护栏失去判据
     * （{@link Paths#get} 遇到 {@code file:///D:/...} 会抛 {@code InvalidPathException}，
     * 而不是被当成路径处理）。URI 形式的位置类配置一律直接拒绝，避免半解析状态。</p>
     */
    private static void requireScopedPath(String role, String value, String testRunId) {
        assertNotClusterPath(value);
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("hdfs://")) {
            throw new IsolationViolationException(role + " 不接受 hdfs:// URI：隔离根必须是本机绝对路径（实际 " + value + "）");
        }
        if (normalized.startsWith("file://")) {
            throw new IsolationViolationException(role + " 不接受 file:// URI：请直接写本机绝对路径（实际 " + value + "）");
        }
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new IsolationViolationException(role + " 不是合法路径：" + value);
        }
        if (!path.isAbsolute()) {
            throw new IsolationViolationException(role + " 必须是绝对路径的隔离根：" + value);
        }
        if (!normalized.contains(testRunId)) {
            throw new IsolationViolationException(role + " 必须位于本次 testRunId 目录下（实际 " + value
                    + "，testRunId=" + testRunId + "）");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MissingConfigurationException("测试隔离配置缺少 " + field);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String oneLine(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 120 ? single : single.substring(0, 117) + "...";
    }
}
