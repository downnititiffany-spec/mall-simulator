package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.TestIsolationGuard;
import com.graduation.analytics.testsupport.TestIsolationGuard.LiveFacts;
import com.graduation.analytics.testsupport.TestIsolationGuard.TestRunContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DEV-001 真链：在**真 3307 隔离实例**上把「写前校验」跑到底。
 *
 * <p>为什么要有这个类：{@code TestIsolationGuardTest} 用的是 Proxy 假驱动，只能证明"判据逻辑正确"，
 * 不能证明"在真 MySQL 8 上真的能跑"。DEV-001 的原始现场恰恰是"真库上第一条版本查询就抛 1193"，
 * 所以必须有一个**连真实例**的用例把四类判据真跑一遍。</p>
 *
 * <p><b>本类只读</b>：全部语句都是 {@code SELECT} / {@code SHOW GRANTS} / {@code SHOW DATABASES} 级别的读取，
 * 不下发任何 DDL/DML。因此它可以安全地对着隔离实例跑，也可以作为"未触碰 3306"的旁证。</p>
 *
 * <p><b>入口（DEV-003b）</b>：本类打 {@code @Tag("it")}，由 {@code metric-analysis/pom.xml} 的
 * {@code isolated-tests} profile 收集——默认档（{@code mvn test}）按
 * {@code <excludedGroups>it</excludedGroups>} 排除它（普通单元测试不连真实 MySQL），
 * 隔离档（{@code -Pisolated-tests}）按 {@code <groups>it</groups>} + {@code <includes>**&#47;*IT.java</includes>}
 * 选中它。项目统一入口是
 * {@code pwsh -NoProfile -File scripts/run-isolated-tests.ps1 -RunId <runId> -Module all -Confirm}：
 * 该脚本注入本类需要的 {@code DEV001_IT_*} 环境变量，并在跑完后**强制核对**本类确实被执行到
 * （零用例／未被选中一律按失败处理）；不再需要人工记类名走 {@code -Dtest=IsolationGuardMySqlIT}。</p>
 *
 * <p><b>凭据</b>：只从环境变量读（{@code DEV001_IT_*}），不进命令行、不落盘。
 * 缺任一项直接**失败而不是跳过**：隔离档里 skip 不算证据。</p>
 */
@Tag("it")
public class IsolationGuardMySqlIT {

    private static final String ENV_URL = "DEV001_IT_URL";
    private static final String ENV_USER = "DEV001_IT_USER";
    private static final String ENV_PASSWORD = "DEV001_IT_PASSWORD";
    private static final String ENV_RUNID = "DEV001_IT_RUNID";
    private static final String ENV_FINGERPRINT = "DEV001_IT_FINGERPRINT";

    // ────────────────────────────────────────────────────────────────────────
    // 1. DEV-001 根因复现：旧实现在真 MySQL 8 上确实取不到主版本号
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void legacyVersionQueryIsUnknownOnRealMysql8() throws SQLException {
        try (Connection connection = dataSource(env(ENV_URL)).getConnection();
             Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT @@version_major"),
                    "真 MySQL 8.0.41 上 SELECT @@version_major 必须报未知系统变量；"
                            + "它不报错就说明本用例的前提变了，需要重新评估 DEV-001");
            // 真实现场：ERROR 1193 (HY000): Unknown system variable 'version_major'
            assertEquals(1193, failure.getErrorCode(),
                    "错误码应为 1193（Unknown system variable），实际 " + failure.getErrorCode()
                            + " / " + failure.getMessage());
            // 对照：修复后走的 SELECT VERSION() 在同一个连接上必须可用
            try (ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).startsWith("8.");
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. 正向真链：四类判据全部真执行且通过（DEV-001 完成的判据）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void realMysql8ChainRunsAllFourJudgementsAndPasses() {
        String runId = env(ENV_RUNID);
        String mallDb = runId + "_mall";
        String generatorDb = runId + "_generator";
        String fingerprint = env(ENV_FINGERPRINT);
        UrlDataSource dataSource = dataSource(env(ENV_URL));

        LiveFacts facts = TestIsolationGuard.verifyBeforeWrite(
                context(mallDb, generatorDb, fingerprint), dataSource, mallDb);

        // ① 版本判据：MySQL 8 上真的取到了主版本号（旧实现在这里就抛 1193 退出）
        assertEquals(8, facts.serverVersionMajor(), "真 MySQL 8.0.41 应识别出主版本号 8");
        // ② 端口判据：真实例端口来自 SELECT @@port
        assertEquals(3307, facts.port(), "真隔离实例端口");
        // ③ 指纹判据：真 hostname:port 与 runner 注入的唯一实例身份**逐字相等**（DEV-002 语义）
        assertEquals(fingerprint, facts.hostname() + ":" + facts.port(),
                "实例身份必须是 @@hostname:@@port，且与注入值逐字一致");
        assertThat(facts.serverUuid()).isNotBlank();
        assertEquals(runId + "_mallapp", facts.account());
        assertEquals(mallDb, facts.currentDb());
        // ④ 权限判据：SHOW GRANTS 真解析（去反引号；USAGE 占位标记不算权限）
        // 真实现场：SHOW GRANTS 返回两行 —— "GRANT USAGE ON *.* TO ..." 与
        // "GRANT ALL PRIVILEGES ON `<db>`.* TO ..."。USAGE 必须被跳过（否则合法账号被误判成"全局非只读权限"），
        // 反引号库名必须去掉（否则本库自己的写权限会被算成"本次测试库之外还有写权限"）。
        assertThat(facts.privileges())
                .as("受限账号在本库上的真实授权应被解析出来：库名已去反引号、USAGE 占位标记已跳过")
                .isNotEmpty()
                .allMatch(p -> p.startsWith(mallDb + "."), "只应出现本库授权，且库名不带反引号")
                .noneMatch(p -> p.contains("`"))
                .noneMatch(p -> p.endsWith(".USAGE"))
                .anyMatch(p -> p.contains("ALL PRIVILEGES") || p.endsWith(".INSERT") || p.endsWith(".UPDATE"));
        assertThat(facts.schemasWithWritePrivilege())
                .as("只允许本次测试库的写权限：出现范围外写权限即判据失效")
                .isEmpty();
        assertThat(facts.sha1()).hasSize(40).matches("[0-9a-f]{40}");
        assertEquals(1, dataSource.connects(), "正向用例应恰好建立一次连接");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. 负向真链：四类判据在真实例上分别拒绝（不得为了"跑绿"而放宽）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void wrongFingerprintIsRejectedOnRealInstance() {
        String runId = env(ENV_RUNID);
        UrlDataSource dataSource = dataSource(env(ENV_URL));

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(
                context(runId + "_mall", runId + "_generator", "127.0.0.1:3307"),
                dataSource, runId + "_mall"))
                .hasMessageContaining("服务实例指纹不匹配")
                .hasMessageContaining("127.0.0.1:3307")
                .hasMessageContaining("dahaishui");
        // 指纹判据在权限判据之后：连接确实建立了，说明前四步都真跑过才轮到指纹拒绝
        assertEquals(1, dataSource.connects());
    }

    @Test
    void formalPortUrlIsRejectedWithoutConnecting() {
        String runId = env(ENV_RUNID);
        String formalPortUrl = env(ENV_URL).replace(":3307", ":3306");
        UrlDataSource dataSource = dataSource(formalPortUrl);

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(
                context(runId + "_mall", runId + "_generator", env(ENV_FINGERPRINT)),
                dataSource, runId + "_mall"))
                .hasMessageContaining("宿主正式 MySQL 实例端口");
        assertEquals(0, dataSource.connects(), "端口判据必须在建立连接之前就拒绝");
    }

    @Test
    void forbiddenDatabaseUrlIsRejectedWithoutConnecting() {
        String runId = env(ENV_RUNID);
        UrlDataSource dataSource = dataSource(urlForDatabase(env(ENV_URL), "analytics_metric"));

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(
                context(runId + "_mall", runId + "_generator", env(ENV_FINGERPRINT)),
                dataSource, runId + "_mall"))
                .hasMessageContaining("analytics_metric")
                .hasMessageContaining("正式");
        assertEquals(0, dataSource.connects(), "禁止库判据必须在建立连接之前就拒绝");
    }

    /**
     * 现场化误配：拿着 mall 库的账号去连 generator 库（两个库都在同一台隔离实例上）。
     *
     * <p>真实现场：MySQL 在**连接阶段**就拒绝（{@code Access denied for user '..._mallapp'@'%' to database
     * '..._generator'}），门禁必须 fail-closed —— 报"写前校验无法完成"并抛出，绝不能"连不上就当通过"。</p>
     *
     * <p><b>未覆盖（显式标注）</b>：「账号在本次测试库之外还有写权限」这条判据在真 3307 上**没有实测**：
     * 该实例上没有任何非 root 账号持有跨库写授权（见 {@code raw/3307-grant-matrix.txt}），
     * 要造出这个现场必须新增一条跨库 GRANT，属新增对象、超出本轮边界。
     * 这条判据目前只有假驱动单测的反例覆盖（{@code TestIsolationGuardTest.everyJudgementReallyRejects}）。</p>
     */
    @Test
    void wrongDatabaseCredentialIsRejectedFailClosed() {
        String runId = env(ENV_RUNID);
        String otherDb = runId + "_generator";
        UrlDataSource dataSource = dataSource(urlForDatabase(env(ENV_URL), otherDb));

        assertThatThrownBy(() -> TestIsolationGuard.verifyBeforeWrite(
                context(otherDb, runId + "_mall", env(ENV_FINGERPRINT)),
                dataSource, otherDb))
                .as("连不上目标库时必须 fail-closed，而不是给出任何形式的通过")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写前校验无法完成")
                .hasMessageNotContaining("写前校验通过");
        assertEquals(1, dataSource.connects(), "确实尝试了连接（不是靠 URL 预检蒙过去）");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 夹具：真凭据只从环境变量来
    // ────────────────────────────────────────────────────────────────────────

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + key
                    + "：本 IT 只在真隔离实例上跑，凭据不经命令行/不落盘；缺凭据必须显式失败（skip 不算证据）");
        }
        return value.trim();
    }

    private static TestRunContext context(String metaDb, String metricDb, String fingerprint) {
        String runId = env(ENV_RUNID);
        String root = Paths.get("target", "v25it-local", runId).toAbsolutePath().toString().replace('\\', '/');
        return new TestRunContext(runId, fingerprint, metaDb, metricDb,
                runId + "_ns", root + "/hdfs", root + "/manifest",
                "credref:" + runId + "-mallapp", System.currentTimeMillis(),
                Paths.get(System.getProperty("java.io.tmpdir"), "dev001-it-env.marker"));
    }

    private static UrlDataSource dataSource(String url) {
        return new UrlDataSource(url, env(ENV_USER), env(ENV_PASSWORD));
    }

    /** 把真实 URL 里的库名换掉，保留真主机名/真端口（负向用例要用同一台实例的真地址）。 */
    private static String urlForDatabase(String url, String database) {
        int query = url.indexOf('?');
        String head = query < 0 ? url : url.substring(0, query);
        String tail = query < 0 ? "" : url.substring(query);
        int slash = head.lastIndexOf('/');
        assertThat(slash).as("JDBC URL 必须显式带库名：" + url).isGreaterThan(0);
        return head.substring(0, slash + 1) + database + tail;
    }

    /**
     * 最小 {@link DataSource}：既能真连（{@link DriverManager}），又对门禁暴露 {@code getUrl()}
     * ——{@code assertJdbcTargetAllowed} 靠反射读 URL，读不到就退化成"实连后判端口"，
     * 那会让"连接级预检先拒绝"的用例失去判据。同时记录连接次数，用来证明"没建连就拒了"。
     */
    public static final class UrlDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        private final AtomicInteger connects = new AtomicInteger();

        UrlDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        public String getUrl() {
            return url;
        }

        int connects() {
            return connects.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            connects.incrementAndGet();
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            connects.incrementAndGet();
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("本用例不需要");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("本用例不需要");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("本用例不需要");
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            throw new UnsupportedOperationException("本用例不需要");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("不支持 unwrap");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
