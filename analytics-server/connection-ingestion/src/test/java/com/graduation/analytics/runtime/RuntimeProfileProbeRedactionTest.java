package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LandingStorage;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeProfileProbeRedactionTest {

    @TempDir
    Path tempDir;

    private static final String PRIVATE_DETAIL =
            "jdbc:mysql://db-user:db-pass@internal-db:3306/analytics?token=probe-secret /opt/private/spark";

    @Test
    void landingAndMetricFailuresDoNotExposeConnectionOrPathDetails() throws Exception {
        LocalLandingStorage landingStorage = mock(LocalLandingStorage.class);
        MetricStore metricStore = mock(MetricStore.class);
        when(landingStorage.healthCheck()).thenReturn(new LandingStorage.HealthResult(false, PRIVATE_DETAIL));
        when(metricStore.query(any(MetricStore.MetricQuery.class)))
                .thenThrow(new IllegalStateException(PRIVATE_DETAIL));
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), landingStorage, metricStore, mock(CredentialService.class));
        RuntimeProfile local = new RuntimeProfile();
        local.setType(RuntimeProfile.TYPE_LOCAL);
        local.setLandingUri("file:///missing-authority?token=probe-secret");
        local.setId(41L);

        RuntimeProfileService.CheckDetail landing = invoke(service, "checkLanding", local);
        RuntimeProfileService.CheckDetail metric = invoke(service, "checkMetric", local);

        assertThat(landing.passed()).isFalse();
        assertThat(landing.detail()).startsWith("探测失败 (").endsWith(")");
        assertThat(metric.passed()).isFalse();
        assertThat(metric.detail()).isEqualTo("探测失败 (IllegalStateException)");
        assertThat(landing.detail()).doesNotContain("db-user", "db-pass", "internal-db", "3306", "probe-secret", "/opt/private");
        assertThat(metric.detail()).doesNotContain("db-user", "db-pass", "internal-db", "3306", "probe-secret", "/opt/private");
        verify(metricStore).query(new MetricStore.MetricQuery(null, true, 41L));
    }

    @Test
    void metricProbeQueriesTheCandidateProfileAndNotTheGloballyActiveProfile() throws Exception {
        MetricStore metricStore = mock(MetricStore.class);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenReturn(List.of());
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class), metricStore,
                mock(CredentialService.class));
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setType(RuntimeProfile.TYPE_LOCAL);
        candidate.setId(73L);

        RuntimeProfileService.CheckDetail result = invoke(service, "checkMetric", candidate);

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("metricStore query ok");
        verify(metricStore).query(new MetricStore.MetricQuery(null, true, 73L));
    }

    @Test
    void metricProbeFailsClosedWhenCandidateProfileHasNoPersistentId() throws Exception {
        MetricStore metricStore = mock(MetricStore.class);
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class), metricStore,
                mock(CredentialService.class));
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setType(RuntimeProfile.TYPE_LOCAL);

        RuntimeProfileService.CheckDetail result = invoke(service, "checkMetric", candidate);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("探测失败");
        verify(metricStore, org.mockito.Mockito.never()).query(any(MetricStore.MetricQuery.class));
    }

    @Test
    void sparkProbeFailsClosedWhenSubmitPathIsNotConfigured() throws Exception {
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class), mock(MetricStore.class),
                mock(CredentialService.class));
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setType(RuntimeProfile.TYPE_LOCAL);

        RuntimeProfileService.CheckDetail result = invoke(service, "checkSpark", candidate);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("缺少 spark_submit_path").doesNotContain("D:\\Develop\\spark");
    }

    @Test
    void successfulLandingProbeDoesNotExposeConfiguredPath() throws Exception {
        LocalLandingStorage landingStorage = mock(LocalLandingStorage.class);
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), landingStorage, mock(MetricStore.class), mock(CredentialService.class));
        RuntimeProfile local = new RuntimeProfile();
        local.setType(RuntimeProfile.TYPE_LOCAL);
        local.setLandingUri(tempDir.toUri().toString());

        RuntimeProfileService.CheckDetail result = invoke(service, "checkLanding", local);

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("探测通过");
        assertThat(result.detail()).doesNotContain("db-user", "db-pass", "internal-db", "3306", "probe-secret", "/opt/private");
        verifyNoInteractions(landingStorage);
    }

    @Test
    void landingProbeUsesTheCandidateProfilesNamespaceInsteadOfTheApplicationDefault() throws Exception {
        LocalLandingStorage applicationDefault = mock(LocalLandingStorage.class);
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), applicationDefault, mock(MetricStore.class), mock(CredentialService.class));
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setType(RuntimeProfile.TYPE_LOCAL);
        candidate.setLandingUri(tempDir.toUri().toString());

        RuntimeProfileService.CheckDetail result = invoke(service, "checkLanding", candidate);

        assertThat(result.passed()).isTrue();
        assertThat(tempDir.resolve(".local-landing-probe")).exists();
        verifyNoInteractions(applicationDefault);
    }

    @Test
    void landingProbeFailsClosedWhenCandidateHasNoNamespace() throws Exception {
        LocalLandingStorage applicationDefault = mock(LocalLandingStorage.class);
        when(applicationDefault.healthCheck()).thenReturn(new LandingStorage.HealthResult(true, "ok"));
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), applicationDefault, mock(MetricStore.class), mock(CredentialService.class));
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setType(RuntimeProfile.TYPE_LOCAL);

        RuntimeProfileService.CheckDetail result = invoke(service, "checkLanding", candidate);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("探测失败 (IllegalArgumentException)");
        verify(applicationDefault, never()).healthCheck();
    }

    private static RuntimeProfileService.CheckDetail invoke(
            RuntimeProfileServiceImpl service, String methodName, RuntimeProfile profile) throws Exception {
        Method method = RuntimeProfileServiceImpl.class.getDeclaredMethod(methodName, RuntimeProfile.class);
        method.setAccessible(true);
        try {
            return (RuntimeProfileService.CheckDetail) method.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Probe should return a failed check result", e.getCause());
        }
    }
}
