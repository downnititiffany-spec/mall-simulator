package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Hive JDBC connection-timeout probe against a local stalled Thrift handshake. */
class RuntimeProfileHiveConnectTimeoutIT {

    @Test
    void runtimeProfileProbeBoundsAStalledHiveJdbcHandshakeAndRestoresGlobalTimeout() throws Exception {
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        int originalLoginTimeout = java.sql.DriverManager.getLoginTimeout();
        AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        ScheduledExecutorService fixture = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "runtime-profile-hive-timeout-fixture");
            thread.setDaemon(true);
            return thread;
        });

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            fixture.submit(() -> {
                try {
                    Socket socket = server.accept();
                    acceptedSocket.set(socket);
                    accepted.countDown();
                    while (!socket.isClosed()) Thread.sleep(50);
                } catch (Exception ignored) {
                    // The fixture is closed by the test or watchdog.
                }
            });
            // Bound cleanup if a regression makes DriverManager/Hive JDBC ignore the configured timeout.
            fixture.schedule(() -> closeQuietly(acceptedSocket.get()), 8, TimeUnit.SECONDS);

            CredentialService credentials = mock(CredentialService.class);
            when(credentials.resolve("timeout-it-ref")).thenReturn("fixture-only-password");
            RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                    mock(RuntimeProfileMapper.class), mock(MetricStore.class), credentials);
            Field timeout = RuntimeProfileServiceImpl.class.getDeclaredField("hiveProbeQueryTimeoutSeconds");
            timeout.setAccessible(true);
            timeout.set(service, "2");

            RuntimeProfile profile = new RuntimeProfile();
            profile.setType(RuntimeProfile.TYPE_SINGLE_NODE);
            profile.setHiveJdbcUrl("jdbc:hive2://127.0.0.1:" + server.getLocalPort() + "/default");
            profile.setCredentialRef("timeout-it-ref");

            Method checkHive = RuntimeProfileServiceImpl.class
                    .getDeclaredMethod("checkHive", RuntimeProfile.class);
            checkHive.setAccessible(true);
            long startedAt = System.nanoTime();
            RuntimeProfileService.CheckDetail result =
                    (RuntimeProfileService.CheckDetail) checkHive.invoke(service, profile);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.applicable()).isTrue();
            assertThat(result.passed()).isFalse();
            assertThat(result.detail()).startsWith("SELECT 1 failed (");
            assertThat(result.detail()).doesNotContain("127.0.0.1", "jdbc:hive2", "fixture-only-password");
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            assertThat(elapsed).isLessThan(Duration.ofSeconds(6));
            assertThat(java.sql.DriverManager.getLoginTimeout()).isEqualTo(originalLoginTimeout);
        } finally {
            closeQuietly(acceptedSocket.get());
            fixture.shutdownNow();
            java.sql.DriverManager.setLoginTimeout(originalLoginTimeout);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {
            // Best-effort cleanup for the local test fixture only.
        }
    }
}
