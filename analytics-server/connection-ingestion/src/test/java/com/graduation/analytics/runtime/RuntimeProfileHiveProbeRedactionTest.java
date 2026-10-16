package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.Statement;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeProfileHiveProbeRedactionTest {

    @Test
    void successfulHiveProbeExecutesSelectOneAndReturnsOnlySafeStatus() throws Exception {
        AtomicReference<String> executedSql = new AtomicReference<>();
        AtomicReference<String> connectedUser = new AtomicReference<>();
        AtomicReference<String> connectedPassword = new AtomicReference<>();
        AtomicInteger queryTimeoutSeconds = new AtomicInteger(-1);
        AtomicInteger loginTimeoutSeconds = new AtomicInteger(-1);
        Driver fakeDriver = new Driver() {
            @Override
            public Connection connect(String url, Properties info) {
                if (!acceptsURL(url)) return null;
                loginTimeoutSeconds.set(DriverManager.getLoginTimeout());
                connectedUser.set(info.getProperty("user"));
                connectedPassword.set(info.getProperty("password"));
                Statement statement = (Statement) Proxy.newProxyInstance(
                        Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                        (proxy, method, args) -> {
                            if ("setQueryTimeout".equals(method.getName())) {
                                queryTimeoutSeconds.set((Integer) args[0]);
                                return null;
                            }
                            if ("execute".equals(method.getName())) {
                                executedSql.set((String) args[0]);
                                return true;
                            }
                            if ("close".equals(method.getName())) return null;
                            if ("isClosed".equals(method.getName())) return false;
                            return defaultValue(method.getReturnType());
                        });
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if ("createStatement".equals(method.getName())) return statement;
                            if ("close".equals(method.getName())) return null;
                            if ("isClosed".equals(method.getName())) return false;
                            return defaultValue(method.getReturnType());
                        });
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:probe-hive-success:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() { return 1; }

            @Override
            public int getMinorVersion() { return 0; }

            @Override
            public boolean jdbcCompliant() { return false; }

            @Override
            public Logger getParentLogger() { return Logger.getGlobal(); }
        };

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolve("env:TEST_HIVE_PASSWORD")).thenReturn("private-password");
        int originalLoginTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(11);
        DriverManager.registerDriver(fakeDriver);
        try {
            RuntimeProfileService.CheckDetail result = invokeCheckHive(
                    "jdbc:probe-hive-success:private-host;token=private-token", credentials);

            assertThat(result.passed()).isTrue();
            assertThat(result.detail()).isEqualTo("SELECT 1 ok (HiveServer2)");
            assertThat(executedSql.get()).isEqualTo("SELECT 1");
            assertThat(connectedUser.get()).isEqualTo("hive");
            assertThat(connectedPassword.get()).isEqualTo("private-password");
            assertThat(loginTimeoutSeconds.get()).isEqualTo(30);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(11);
            assertThat(queryTimeoutSeconds.get()).isEqualTo(30);
            assertThat(result.detail()).doesNotContain("private-host", "private-token");

            invokeCheckHive(
                    "jdbc:probe-hive-success:private-host;token=private-token", credentials, "7");
            assertThat(loginTimeoutSeconds.get()).isEqualTo(7);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(11);
            assertThat(queryTimeoutSeconds.get()).isEqualTo(7);
        } finally {
            DriverManager.deregisterDriver(fakeDriver);
            DriverManager.setLoginTimeout(originalLoginTimeout);
        }
    }

    @Test
    void failedHiveProbeDoesNotExposeJdbcUrlOrDriverMessage() throws Exception {
        String jdbcUrl = "jdbc:hive2://probe-user:probe-pass@127.0.0.1:10015/default;token=probe-token";
        Driver fakeDriver = new Driver() {
            @Override
            public Connection connect(String url, Properties info) throws SQLException {
                throw new SQLException("driver echoed " + url + " private-detail");
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:hive2://probe-user:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() { return 1; }

            @Override
            public int getMinorVersion() { return 0; }

            @Override
            public boolean jdbcCompliant() { return false; }

            @Override
            public Logger getParentLogger() { return Logger.getGlobal(); }
        };

        int originalLoginTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(13);
        DriverManager.registerDriver(fakeDriver);
        try {
            RuntimeProfileService.CheckDetail result = invokeCheckHive(jdbcUrl, mock(CredentialService.class));

            assertThat(result.passed()).isFalse();
            assertThat(result.detail()).contains("SQLException");
            assertThat(result.detail()).doesNotContain(jdbcUrl, "probe-user", "probe-pass", "probe-token", "private-detail");
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(13);
        } finally {
            DriverManager.deregisterDriver(fakeDriver);
            DriverManager.setLoginTimeout(originalLoginTimeout);
        }
    }

    @Test
    void concurrentHiveProbesKeepTheirOwnDriverManagerTimeouts() throws Exception {
        AtomicInteger firstTimeout = new AtomicInteger(-1);
        AtomicInteger secondTimeout = new AtomicInteger(-1);
        CountDownLatch firstConnectEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstConnect = new CountDownLatch(1);
        CountDownLatch secondConnectEntered = new CountDownLatch(1);
        Driver fakeDriver = new Driver() {
            @Override
            public Connection connect(String url, Properties info) throws SQLException {
                if (!acceptsURL(url)) return null;
                if (url.endsWith("first")) {
                    firstTimeout.set(DriverManager.getLoginTimeout());
                    firstConnectEntered.countDown();
                    try {
                        if (!releaseFirstConnect.await(5, TimeUnit.SECONDS)) {
                            throw new SQLException("Timed out waiting to release first fake connection");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted fake connection", e);
                    }
                } else {
                    secondTimeout.set(DriverManager.getLoginTimeout());
                    secondConnectEntered.countDown();
                }
                return fakeConnection();
            }

            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:probe-hive-concurrent:");
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() { return 1; }

            @Override
            public int getMinorVersion() { return 0; }

            @Override
            public boolean jdbcCompliant() { return false; }

            @Override
            public Logger getParentLogger() { return Logger.getGlobal(); }
        };

        int originalLoginTimeout = DriverManager.getLoginTimeout();
        DriverManager.registerDriver(fakeDriver);
        AtomicReference<RuntimeProfileService.CheckDetail> firstResult = new AtomicReference<>();
        AtomicReference<RuntimeProfileService.CheckDetail> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread first = new Thread(() -> runProbe("jdbc:probe-hive-concurrent:first", "3", firstResult, threadFailure));
        Thread second = new Thread(() -> runProbe("jdbc:probe-hive-concurrent:second", "7", secondResult, threadFailure));
        try {
            first.start();
            assertThat(firstConnectEntered.await(5, TimeUnit.SECONDS)).isTrue();

            second.start();
            awaitThreadBlockedOrSecondConnect(second, secondConnectEntered);
            assertThat(secondConnectEntered.getCount())
                    .as("the second probe must wait while the first holds the shared timeout lock")
                    .isEqualTo(1);

            releaseFirstConnect.countDown();
            first.join(5000);
            second.join(5000);

            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(threadFailure.get()).isNull();
            assertThat(firstResult.get().passed()).isTrue();
            assertThat(secondResult.get().passed()).isTrue();
            assertThat(firstTimeout.get()).isEqualTo(3);
            assertThat(secondTimeout.get()).isEqualTo(7);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(originalLoginTimeout);
        } finally {
            releaseFirstConnect.countDown();
            first.join(5000);
            second.join(5000);
            DriverManager.deregisterDriver(fakeDriver);
            DriverManager.setLoginTimeout(originalLoginTimeout);
        }
    }

    @Test
    void nonLocalProfileWithoutHiveUrlFailsClosedAsAnApplicableCheck() throws Exception {
        CredentialService credentials = mock(CredentialService.class);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setType(RuntimeProfile.TYPE_SINGLE_NODE);

        RuntimeProfileService.CheckDetail result = invokeCheckHive(profile, credentials);

        assertThat(result.applicable()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("UNKNOWN: 未配置 hive_jdbc_url");
        verifyNoInteractions(credentials);
    }

    @Test
    void localProfileWithoutHiveUrlIsExplicitlyNotApplicable() throws Exception {
        CredentialService credentials = mock(CredentialService.class);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setType(RuntimeProfile.TYPE_LOCAL);

        RuntimeProfileService.CheckDetail result = invokeCheckHive(profile, credentials);

        assertThat(result.applicable()).isFalse();
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("不适用").doesNotContain("UNKNOWN");
        verifyNoInteractions(credentials);
    }

    private static RuntimeProfileService.CheckDetail invokeCheckHive(
            String jdbcUrl, CredentialService credentialService) throws Exception {
        return invokeCheckHive(jdbcUrl, credentialService, null);
    }

    private static void runProbe(String url, String timeoutSeconds,
                                 AtomicReference<RuntimeProfileService.CheckDetail> result,
                                 AtomicReference<Throwable> failure) {
        try {
            result.set(invokeCheckHive(url, mock(CredentialService.class), timeoutSeconds));
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    private static void awaitThreadBlockedOrSecondConnect(Thread thread, CountDownLatch secondConnectEntered)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && secondConnectEntered.getCount() != 0
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState() == Thread.State.BLOCKED || secondConnectEntered.getCount() == 0).isTrue();
    }

    private static Connection fakeConnection() {
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("isClosed".equals(method.getName())) return false;
                    return defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) return statement;
                    if ("close".equals(method.getName())) return null;
                    if ("isClosed".equals(method.getName())) return false;
                    return defaultValue(method.getReturnType());
                });
    }

    private static RuntimeProfileService.CheckDetail invokeCheckHive(
            String jdbcUrl, CredentialService credentialService, String queryTimeoutSeconds) throws Exception {
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class),
                mock(MetricStore.class), credentialService);
        if (queryTimeoutSeconds != null) {
            var timeoutField = RuntimeProfileServiceImpl.class.getDeclaredField("hiveProbeQueryTimeoutSeconds");
            timeoutField.setAccessible(true);
            timeoutField.set(service, queryTimeoutSeconds);
        }
        RuntimeProfile profile = new RuntimeProfile();
        profile.setType(RuntimeProfile.TYPE_REMOTE_CLUSTER);
        profile.setHiveJdbcUrl(jdbcUrl);
        profile.setCredentialRef("env:TEST_HIVE_PASSWORD");
        Method checkHive = RuntimeProfileServiceImpl.class.getDeclaredMethod("checkHive", RuntimeProfile.class);
        checkHive.setAccessible(true);
        try {
            return (RuntimeProfileService.CheckDetail) checkHive.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Hive probe should return a check result", e.getCause());
        }
    }

    private static RuntimeProfileService.CheckDetail invokeCheckHive(
            RuntimeProfile profile, CredentialService credentialService) throws Exception {
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class),
                mock(MetricStore.class), credentialService);
        Method checkHive = RuntimeProfileServiceImpl.class.getDeclaredMethod("checkHive", RuntimeProfile.class);
        checkHive.setAccessible(true);
        try {
            return (RuntimeProfileService.CheckDetail) checkHive.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Hive probe should return a check result", e.getCause());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
