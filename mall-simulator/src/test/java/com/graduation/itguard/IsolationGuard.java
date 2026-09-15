package com.graduation.itguard;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立模块（mall-simulator / synthetic-data-generator）测试侧「防误写门禁」。
 *
 * <p><b>为什么需要一份独立实现</b>：这两个模块是各自独立的 Spring Boot 工程，
 * <b>不在</b> {@code analytics-server} reactor 内，也没有 {@code platform-common} 的
 * test-jar 依赖，因此拿不到 V25-S01/V25-S02 建立的
 * {@code com.graduation.analytics.testsupport.TestIsolationGuard}。
 * 这里保留那套门禁的**判据**（默认关闭 / 实例端口白名单 / 库名禁止清单 / 账号禁止清单 /
 * 写前实连核对），但不复制平台侧的全部 API——只做这两个模块真正需要的那几件事。</p>
 *
 * <p><b>判据一律是连接级的，不以命名作安全证明</b>——库名里含 {@code _test} 不算隔离。</p>
 *
 * <h3>配置项（系统属性 → 环境变量 → 隔离档案）</h3>
 * <pre>
 *   -Dit.guard.enabled=true              必须显式开启（默认关闭）
 *   -Dit.guard.instancePorts=3307        允许的隔离实例端口清单
 *   -Dit.guard.runId=&lt;本次运行标识&gt;       目标库名必须以此前缀开头
 *   -Dit.guard.forbiddenDatabases=...    额外禁止库名（逗号分隔，追加到内置清单）
 *   -Dit.guard.forbiddenAccounts=...     额外禁止账号（逗号分隔，追加到内置清单）
 *   -Dit.guard.serverFingerprint=hostname:port
 *                                        登记实例指纹，**唯一权威形态**（如 {@code dahaishui:3307}）；
 *                                        不接受裸 hostname／裸端口／{@code 127.0.0.1:port}／
 *                                        {@code localhost:port}；{@code @@server_uuid} 是独立漂移事实，
 *                                        不是 fingerprint 替代值
 *   -Dit.guard.url / user / password     目标连接（口令可写 credref:&lt;id&gt;）
 * </pre>
 *
 * <p>档案文件：classpath 或工作目录下的 {@code it-guard.local.properties}。</p>
 *
 * <p>本类<b>只读</b>：只执行 {@code SELECT} 与 {@code @@} 变量查询，不写数据、不建对象。
 * 唯一的例外是 {@link #requireCredential}：当取值是 {@code credref:} 引用时按约定去
 * {@code credref-&lt;id&gt;.properties} 里取，那也只是读文件。</p>
 */
public final class IsolationGuard {

    public static final String PREFIX = "it.guard.";
    public static final String PROFILE = "it-guard.local.properties";

    /** 宿主正式 MySQL 实例端口：显式点名拒绝。 */
    public static final int HOST_FORMAL_PORT = 3306;

    private static final Pattern MYSQL_URL =
            Pattern.compile("(?i)^jdbc:mysql://([^/]+)(?:/([^?]*))?(?:\\?(.*))?$");

    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$");

    private IsolationGuard() {
    }

    // ── 配置解析 ───────────────────────────────────────────────────────────

    /** 取必填配置项；系统属性 → 环境变量 → 档案；都没有则拒绝（无默认值）。 */
    public static String require(String key) {
        String v = System.getProperty(PREFIX + key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = System.getenv(envName(key));
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = profile().getProperty(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        throw new GuardViolation("缺少测试隔离配置项 " + PREFIX + key + "（系统属性 / 环境变量 "
                + envName(key) + " / 档案 " + PROFILE + "）：拒绝运行（不用正式地址/正式账号兜底）");
    }

    /** 取可选配置项；不存在返回 {@code fallback}。 */
    public static String optional(String key, String fallback) {
        try {
            return require(key);
        } catch (GuardViolation e) {
            return fallback;
        }
    }

    /** 显式开启开关：**默认关闭**（不存在时返回 false）。 */
    public static boolean enabled() {
        return "true".equalsIgnoreCase(optional("enabled", "false"));
    }

    /** 开启门禁；未显式开启即拒绝。 */
    public static void requireEnabled(String who) {
        if (!enabled()) {
            throw new GuardViolation("[" + who + "] 测试默认关闭：未提供 -D" + PREFIX
                    + "enabled=true（或档案 " + PROFILE + "）。"
                    + "这些用例会建库/建表/清表，必须显式声明目标隔离环境后才允许运行。");
        }
    }

    private static String envName(String key) {
        return "IT_GUARD_" + key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static boolean profileResolved = false;
    private static Properties profileProps = new Properties();

    private static synchronized Properties profile() {
        if (profileResolved) {
            return profileProps;
        }
        profileResolved = true;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl == null ? null : cl.getResourceAsStream(PROFILE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                profileProps = p;
                return p;
            }
        } catch (IOException e) {
            throw new GuardViolation("读取 classpath:" + PROFILE + " 失败：" + e.getMessage());
        }
        Path file = Paths.get(PROFILE).toAbsolutePath();
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                throw new GuardViolation("读取 " + file + " 失败：" + e.getMessage());
            }
            profileProps = p;
        }
        return profileProps;
    }

    /**
     * 口令解析：支持 {@code credref:<id>} 引用（去 {@code credref-<id>.properties} 取），
     * 避免把口令写进命令行或源码。
     */
    public static String requireCredential(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new GuardViolation("[" + what + "] 口令为空：拒绝运行。");
        }
        if (!value.startsWith("credref:")) {
            return value;
        }
        String id = value.substring("credref:".length()).trim();
        if (id.isEmpty()) {
            throw new GuardViolation("[" + what + "] credref 引用为空：拒绝运行。");
        }
        for (Path candidate : new Path[] {
                Paths.get("credref-" + id + ".properties").toAbsolutePath(),
                Paths.get(System.getProperty("user.home", "."), ".graduation", "credref-" + id + ".properties")}) {
            if (Files.isRegularFile(candidate)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(candidate)) {
                    p.load(in);
                } catch (IOException e) {
                    throw new GuardViolation("[" + what + "] 读取凭据引用失败：" + e.getMessage());
                }
                String secret = p.getProperty("password");
                if (secret == null || secret.isBlank()) {
                    throw new GuardViolation("[" + what + "] 凭据引用 " + candidate
                            + " 内没有 password 键：拒绝运行。");
                }
                return secret;
            }
        }
        throw new GuardViolation("[" + what + "] 未找到凭据引用 " + value
                + "（期望 credref-" + id + ".properties）：拒绝运行。");
    }

    // ── URL / 连接判据 ─────────────────────────────────────────────────────

    /** 允许的隔离实例端口集合（来自配置，无默认值）。 */
    public static Set<Integer> allowedPorts() {
        String raw = require("instancePorts");
        Set<Integer> ports = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                ports.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                throw new GuardViolation("隔离实例端口清单含非法值：" + part);
            }
        }
        if (ports.isEmpty()) {
            throw new GuardViolation("隔离实例端口清单为空：拒绝运行（不猜测端口）。");
        }
        return ports;
    }

    /** JDBC URL 预检：**不开连接**，纯文本判据。返回解析出的目标库名。 */
    public static String assertUrlAllowed(String url, String reason) {
        if (url == null || url.isBlank()) {
            throw new GuardViolation("[" + reason + "] 数据源 URL 为空：拒绝运行。");
        }
        Matcher m = MYSQL_URL.matcher(url.trim());
        if (!m.matches()) {
            throw new GuardViolation("[" + reason + "] 无法识别的 JDBC URL 形态（只允许 jdbc:mysql://）：" + url);
        }
        String authority = m.group(1);
        String database = m.group(2);
        String query = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);

        if (query.contains("createdatabaseifnotexist=true")) {
            throw new GuardViolation("[" + reason + "] 禁止 createDatabaseIfNotExist=true："
                    + "测试不得创建数据库。");
        }
        for (String hostPort : authority.split(",")) {
            String host = hostPort;
            int port = HOST_FORMAL_PORT;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && colon < hostPort.length() - 1) {
                host = hostPort.substring(0, colon);
                try {
                    port = Integer.parseInt(hostPort.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new GuardViolation("[" + reason + "] 端口无法解析：" + hostPort);
                }
            }
            assertLocal(host, reason);
            assertPortAllowed(port, reason);
        }
        if (database == null || database.isBlank()) {
            throw new GuardViolation("[" + reason + "] JDBC URL 未指定目标库：拒绝运行（不猜测库名）。");
        }
        assertDatabaseAllowed(database.trim(), reason);
        return database.trim();
    }

    private static void assertLocal(String host, String reason) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (!(h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]"))) {
            throw new GuardViolation("[" + reason + "] 主机 " + host
                    + " 不是本机：测试只允许连本机隔离实例。");
        }
    }

    private static void assertPortAllowed(int port, String reason) {
        if (port <= 0) {
            throw new GuardViolation("[" + reason + "] 无法确定 MySQL 实例端口：拒绝运行。");
        }
        // 宿主正式端口**无条件**拒绝，且**优先于**白名单判定。
        // 早先这条写在 `else` 分支里（"不在白名单时若等于 3306 再报专门文案"），
        // 结果是：只要配置里把 3306 写进 instancePorts，正式端口就被静默放行。
        // 这个缺陷是 V25-S03 探针案例 2.2 跑出来的——配置不能覆盖掉禁止清单。
        if (port == HOST_FORMAL_PORT) {
            throw new GuardViolation("[" + reason + "] 实例端口 " + port
                    + " 是宿主正式 MySQL 实例端口：宿主上的任何库都不是隔离环境。"
                    + "隔离必须落在独立实例（端口 " + allowedPorts() + "）；库名含 test 不构成安全证明。");
        }
        if (allowedPorts().contains(port)) {
            return;
        }
        throw new GuardViolation("[" + reason + "] 实例端口 " + port
                + " 不在允许的隔离实例端口清单 " + allowedPorts() + " 内：拒绝运行。");
    }

    /** 库名判据：内置禁止清单 ∪ 配置补充清单，且必须以本次 runId 为前缀。 */
    public static void assertDatabaseAllowed(String database, String reason) {
        String db = database.trim();
        String lower = db.toLowerCase(Locale.ROOT);
        for (String forbidden : builtinForbiddenDatabases()) {
            if (lower.equals(forbidden)) {
                throw new GuardViolation("[" + reason + "] 目标库 " + db
                        + " 属内置禁止清单（真实业务库）：测试绝不写它。");
            }
        }
        String extra = optional("forbiddenDatabases", "");
        for (String part : extra.split(",")) {
            if (!part.isBlank() && lower.equals(part.trim().toLowerCase(Locale.ROOT))) {
                throw new GuardViolation("[" + reason + "] 目标库 " + db + " 属禁止清单：测试绝不写它。");
            }
        }
        String runId = require("runId");
        if (!RUN_ID.matcher(runId).matches()) {
            throw new GuardViolation("[" + reason + "] runId 形状非法：" + runId);
        }
        if (!db.startsWith(runId)) {
            throw new GuardViolation("[" + reason + "] 目标库 " + db + " 不以本次 runId（" + runId
                    + "）为前缀：拒绝运行。测试库必须是本次运行新建的，不能复用同名库。");
        }
    }

    /**
     * 内置禁止库名（真实业务/历史库，绝不可被配置覆盖掉）。
     * 子类模块可追加自己的库名——见 {@link #builtinForbiddenDatabases()} 的调用方覆写点。
     */
    private static Set<String> builtinForbiddenDatabases() {
        Set<String> set = new LinkedHashSet<>(Set.of(
                "mall_simulator", "mall", "mall_prod",
                "generator_meta", "generator_meta_prod",
                "analytics_metric", "analytics_meta"));
        for (String part : optional("builtinForbiddenDatabases", "").split(",")) {
            if (!part.isBlank()) {
                set.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    /** 账号判据：禁止清单（内置 + 配置补充）。 */
    public static void assertAccountAllowed(String account, String reason) {
        String bare = account == null ? "" : account.split("@")[0].replace("`", "").trim();
        Set<String> forbidden = new LinkedHashSet<>(Set.of("root", "mall_app", "mall_read", "meta_app"));
        for (String part : optional("forbiddenAccounts", "").split(",")) {
            if (!part.isBlank()) {
                forbidden.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (forbidden.contains(bare.toLowerCase(Locale.ROOT))) {
            throw new GuardViolation("[" + reason + "] 账号 " + bare
                    + " 属禁止清单（正式账号）：测试必须用本次运行自己的受限账号。");
        }
    }

    // ── 写前核对 ───────────────────────────────────────────────────────────

    /** 写前核对结果（供证据采集）。 */
    public record LiveFacts(String database, String account, String hostname, int port, String runId) {
    }

    /**
     * 写前核对：用**真实连接**确认本次运行确实落在登记的隔离范围内。
     * 必须在任何 DML/DDL <b>之前</b>调用；不符即抛 {@link GuardViolation}。
     */
    public static LiveFacts verifyBeforeWrite(DataSource dataSource, String expectedDatabase, String reason) {
        requireEnabled(reason);
        if (dataSource == null) {
            throw new GuardViolation("[" + reason + "] DataSource 为空：写前校验无法进行，拒绝继续。");
        }
        String url = readUrl(dataSource);
        if (url != null && !url.isBlank()) {
            assertUrlAllowed(url, reason);
        } else if (!"true".equalsIgnoreCase(optional("allowUnreadableUrl", "false"))) {
            // **失败关闭**：读不到 URL 就"跳过 URL 预检、直接开连接"是错的。
            // 实测（V25-S03 探针案例 3）：JDK 动态代理/包装 DataSource 上
            // getUrl/getURL/getJdbcUrl 三个访问器都不存在，readUrl 静默返回 null，
            // 于是 URL 判据整条失效，连接会真的发往宿主 3306 才靠"实连后判据"兜底——
            // 而那时与正式实例的网络交互/认证已经发生了。
            // 现在：读不到 URL ⇒ 拒绝运行；确需放行须显式声明
            // -Dit.guard.allowUnreadableUrl=true（并接受只有实连判据可用）。
            throw new GuardViolation("[" + reason + "] 无法从 DataSource（"
                    + dataSource.getClass().getName() + "）读取 JDBC URL：不满足"
                    + "「拒绝必须先于任何连接尝试」这一条，拒绝运行。"
                    + "请改用能暴露 URL 的 DataSource，或显式声明 -D" + PREFIX
                    + "allowUnreadableUrl=true 以仅依赖实连判据。");
        }
        try (Connection conn = dataSource.getConnection()) {
            String currentDb = scalar(conn, "SELECT DATABASE()");
            String account = scalar(conn, "SELECT CURRENT_USER()");
            int port = scalarInt(conn, "SELECT @@port");
            String hostname = scalar(conn, "SELECT @@hostname");
            if (currentDb == null || currentDb.isBlank()) {
                throw new GuardViolation("[" + reason
                        + "] 连接未选中任何库（SELECT DATABASE() 为 NULL）：拒绝运行。");
            }
            if (expectedDatabase != null && !expectedDatabase.equals(currentDb)) {
                throw new GuardViolation("[" + reason + "] 实际连接库（" + currentDb
                        + "）与声明目标库（" + expectedDatabase + "）不一致：配置漂移，拒绝写入。");
            }
            assertDatabaseAllowed(currentDb, reason);
            assertPortAllowed(port, reason);
            assertAccountAllowed(account, reason);
            String expected = require("serverFingerprint");
            if (!fingerprintMatches(expected, hostname, port)) {
                throw new GuardViolation("[" + reason + "] 实例指纹不匹配：登记为 " + expected
                        + "，实际 hostname=" + hostname + " port=" + port
                        + "（唯一权威身份形如 hostname:port，规范化后必须完全相等；"
                        + "裸 hostname／裸端口／127.0.0.1:port／localhost:port 一律不接受，"
                        + "同库名换实例也必须拒绝）。");
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@version_comment")) {
                rs.next();
            }
            LiveFacts facts = new LiveFacts(currentDb, account, hostname, port, require("runId"));
            System.out.println("[IsolationGuard] 写前核对通过（" + reason + "）：database=" + facts.database()
                    + " account=" + facts.account() + " hostname=" + facts.hostname()
                    + " port=" + facts.port() + " runId=" + facts.runId());
            return facts;
        } catch (SQLException e) {
            throw new GuardViolation("[" + reason + "] 写前校验无法完成（连接/查询失败）：" + e.getMessage()
                    + "：拒绝继续（不降级、不回退正式库）。");
        }
    }

    /**
     * 实例指纹判据：唯一权威实例身份＝{@code hostname:port}。
     *
     * <p>登记值必须与实连实例的 {@code @@hostname + ":" + @@port} <b>规范化后完全相等</b>：
     * 两侧去首尾空白、hostname 段大小写归一。**端口不做模糊匹配、hostname 不做前缀匹配**；
     * 裸 {@code hostname}、裸 {@code port}、{@code 127.0.0.1:port}、{@code localhost:port}
     * 一律拒绝（用连接地址代替真实 hostname 等于没核对）；{@code @@server_uuid} 同样
     * <b>不是</b> fingerprint 的替代值——它是门禁 6（runner 探针）的独立漂移事实。</p>
     *
     * <p>端口白名单（{@link #assertPortAllowed}）是**另一层独立约束**，不由本判据兜底，
     * 也不替代本判据。本方法与 analytics 侧
     * {@code com.graduation.analytics.testsupport.TestIsolationGuard.fingerprintMatches}
     * 语义一致。</p>
     *
     * <p>包内可见（原 {@code private}）以供同包单测
     * {@code IsolationGuardFingerprintTest} 直接验证反例集；判据本身只多不少。</p>
     */
    static boolean fingerprintMatches(String expected, String hostname, int port) {
        if (expected == null || expected.isBlank() || hostname == null || hostname.isBlank() || port <= 0) {
            return false;
        }
        return expected.trim().equalsIgnoreCase(hostname.trim() + ":" + port);
    }

    private static String readUrl(DataSource ds) {
        for (String accessor : new String[] {"getUrl", "getURL", "getJdbcUrl"}) {
            try {
                Object v = ds.getClass().getMethod(accessor).invoke(ds);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                // 包装类型读不到 URL：留给实连后的 @@port 判据兜底
            }
        }
        return null;
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int scalarInt(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** 门禁拒绝异常。 */
    public static class GuardViolation extends RuntimeException {
        public GuardViolation(String message) {
            super(message);
        }
    }
}
