package com.graduation.mall.support;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import javax.sql.DataSource;

/**
 * mall-simulator 测试侧「防误写门禁」的**模块适配层**（V25-S03 R-1）。
 *
 * <p><b>判据只有一份</b>：全部实现在 {@link com.graduation.itguard.IsolationGuard}。
 * 本类只做三件模块特有的事，不复制任何判据——避免同一个门禁在两处长出两套规则，
 * 那是比没有门禁更坏的状态（改了一处忘另一处）。</p>
 *
 * <ol>
 *   <li>把异常类型映到本模块沿用的 {@link MallIsolationException}；</li>
 *   <li>把 mall 的 {@code mall.it.*} 属性前缀映到共享门禁的 {@code it.guard.*}
 *       （纯字符串转发，见 {@link #bridgeSystemProperties()}）；</li>
 *   <li>提供 Flyway {@code BEFORE_MIGRATE} 回调（见 {@link BeforeMigrateGuard}）。</li>
 * </ol>
 *
 * <h3>整改前的实际风险（保留原状描述，便于对照）</h3>
 * <p>{@code src/test/resources/application-test.yml} 整改前写的是</p>
 * <pre>
 * url: jdbc:mysql://127.0.0.1:3306/mall_simulator_test?createDatabaseIfNotExist=true
 * username: ${MALL_DB_USER:root}
 * flyway.enabled: true
 * </pre>
 * <p>① 默认落到**宿主正式端口 3306**；② 默认账号 {@code root}；
 * ③ {@code createDatabaseIfNotExist=true} 会建库、Flyway 会建表，
 * 随后 {@code MallTestSupport.freshState()} 对业务表做 {@code delete(null)} 全表清空。
 * 只要有人把库名指向 {@code mall_simulator}（真实业务库，834,550 行），
 * 跑一次 {@code mvn test} 就会清空生产数据。**库名含 {@code _test} 不构成安全证明。**</p>
 *
 * <p>本类与 {@code com.graduation.itguard.IsolationGuard} 都<b>只读</b>：只执行 {@code SELECT}
 * 与 {@code @@} 变量查询，不写任何数据、不建任何对象。</p>
 */
public final class MallIsolationGuard {

    /** 系统属性前缀：{@code -Dmall.it.testRunId=...}。 */
    public static final String PREFIX = "mall.it.";

    /** 隔离档案：与共享门禁同名，便于统一心智模型。 */
    public static final String PROFILE_RESOURCE = com.graduation.itguard.IsolationGuard.PROFILE;

    /**
     * mall 侧键名 → 共享门禁键名的转发表。
     *
     * <p><b>{@code mysql.host} 故意不在这里</b>：它是 {@code host:port} 形态
     * （对齐 V25-S02 的 {@code integration.local.properties}），而共享门禁要的是**纯端口清单**。
     * 早先把两者同名直连，结果 {@code instancePorts} 被写成 {@code "127.0.0.1:3306"}`，
     * 端口白名单判据整条失效——这个缺陷是 V25-S03 探针案例 2.2 跑出来的。
     * 正确的做法是下面单独拆出端口（见 {@link #bridgeSystemProperties()} 第 3 步）。</p>
     */
    private static final String[][] KEY_BRIDGE = {
            {"enabled", "enabled"},
            {"testRunId", "runId"},
            {"serverFingerprint", "serverFingerprint"},
            {"mysql.url", "url"},
            {"mysql.username", "user"},
            {"mysql.password", "password"},
    };

    private MallIsolationGuard() {
    }

    /** 上一次桥接写入了哪些键——用于**先清理再重建**，避免桥接结果黏住上一组配置。 */
    private static final java.util.Set<String> BRIDGED_KEYS = new java.util.LinkedHashSet<>();

    /**
     * 把 {@code mall.it.*} 系统属性转发到共享门禁的 {@code it.guard.*} 命名空间。
     *
     * <p><b>必须先清理上一次桥接写入的键，再按当前 {@code mall.it.*} 重建</b>：
     * 否则同一个 JVM 里第二次调用（换一组配置，例如负向→正向连跑）会读到上一轮的
     * {@code it.guard.instancePorts}，出现"改了配置判据却没变"的静默错判。
     * 这个缺陷是 V25-S03 探针跑出来的（2.2 案例：端口判据仍报 3306）。</p>
     *
     * <p>幂等：显式给出的 {@code -Dit.guard.*} 不会被本方法覆盖——
     * 只清理由本方法自己写入的键。</p>
     */
    public static synchronized void bridgeSystemProperties() {
        // 1. 清理上一轮由本方法写入的键（不动调用方显式给出的 it.guard.*）
        for (String key : BRIDGED_KEYS) {
            System.clearProperty(key);
        }
        BRIDGED_KEYS.clear();
        // 2. 按键映射表转发
        for (String[] pair : KEY_BRIDGE) {
            String from = System.getProperty(PREFIX + pair[0]);
            if (from != null && !from.isBlank()) {
                String target = "it.guard." + pair[1];
                System.setProperty(target, from.trim());
                BRIDGED_KEYS.add(target);
            }
        }
        // 3. mysql.host=127.0.0.1:3307 → instancePorts=3307
        String host = System.getProperty(PREFIX + "mysql.host");
        if (host != null && host.contains(":")) {
            String port = host.substring(host.lastIndexOf(':') + 1).trim();
            if (!port.isEmpty()) {
                System.setProperty("it.guard.instancePorts", port);
                BRIDGED_KEYS.add("it.guard.instancePorts");
            }
        }
    }

    /** 取必填配置项；无值即拒绝。 */
    public static String requiredProperty(String key) {
        bridgeSystemProperties();
        try {
            return com.graduation.itguard.IsolationGuard.require(key);
        } catch (com.graduation.itguard.IsolationGuard.GuardViolation e) {
            throw new MallIsolationException(e.getMessage());
        }
    }

    /** 取可选配置项；无值返回 {@code null}。 */
    public static String optionalProperty(String key) {
        try {
            return requiredProperty(key);
        } catch (MallIsolationException e) {
            return null;
        }
    }

    /** JDBC URL 预检：**不建立连接**，只解析 URL 文本。 */
    public static String assertUrlAllowed(String url, String reason) {
        bridgeSystemProperties();
        try {
            return com.graduation.itguard.IsolationGuard.assertUrlAllowed(url, reason);
        } catch (com.graduation.itguard.IsolationGuard.GuardViolation e) {
            throw new MallIsolationException(e.getMessage());
        }
    }

    /**
     * 写前核对：用**真实连接**确认本次运行确实落在登记的隔离范围内。
     * 必须在任何 DML/DDL <b>之前</b>调用。
     */
    public static void verifyBeforeWrite(DataSource dataSource, String reason) {
        bridgeSystemProperties();
        String expected = optionalProperty("mysql.url");
        String expectedDb = null;
        if (expected != null) {
            int slash = expected.indexOf('/', expected.indexOf("//") + 2);
            if (slash > 0) {
                expectedDb = expected.substring(slash + 1);
                int q = expectedDb.indexOf('?');
                if (q >= 0) {
                    expectedDb = expectedDb.substring(0, q);
                }
            }
        }
        try {
            com.graduation.itguard.IsolationGuard.verifyBeforeWrite(dataSource, expectedDb, reason);
        } catch (com.graduation.itguard.IsolationGuard.GuardViolation e) {
            throw new MallIsolationException(e.getMessage());
        }
    }

    /** 供证据采集：把当前解析到的隔离范围打成一行（不含任何口令）。 */
    public static String describeScope() {
        StringBuilder sb = new StringBuilder();
        for (String key : new String[] {"enabled", "testRunId", "serverFingerprint", "mysql.host"}) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String value = optionalProperty(key);
            sb.append(key).append('=').append(value == null ? "<未提供>" : value);
        }
        return sb.toString();
    }

    /**
     * Flyway {@code BEFORE_MIGRATE} 回调：在 Flyway 执行任何迁移**之前**做写前核对。
     *
     * <p>这是「默认跑 Flyway」的直接对冲——即使有人把 {@code flyway.enabled} 打开，
     * 只要目标不是登记的隔离实例/库，迁移就会在第一步被拒绝，而不是先在正式库建表。</p>
     */
    public static final class BeforeMigrateGuard implements Callback {

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            // 未显式开启时直接拒：整改前 application-test.yml 默认 enabled: true 且指向宿主 3306
            if (!"true".equalsIgnoreCase(String.valueOf(optionalProperty("enabled")))) {
                throw new MallIsolationException("Flyway 迁移被拒绝：mall 测试默认关闭，"
                        + "未提供 -D" + PREFIX + "enabled=true 与隔离档案 "
                        + PROFILE_RESOURCE + "。（整改前 application-test.yml 默认 enabled: true "
                        + "且指向宿主 3306，会在正式实例上建表。）");
            }
            DataSource ds = context.getConnection() == null
                    ? null : context.getConfiguration().getDataSource();
            verifyBeforeWrite(ds, "flyway-before-migrate");
        }

        @Override
        public String getCallbackName() {
            return "mall-isolation-guard";
        }
    }

    /** 门禁拒绝异常（区别于普通配置缺失，便于测试断言）。 */
    public static class MallIsolationException extends RuntimeException {
        public MallIsolationException(String message) {
            super(message);
        }
    }
}
