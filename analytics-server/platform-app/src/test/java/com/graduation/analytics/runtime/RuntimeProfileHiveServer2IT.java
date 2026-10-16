package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.io.TempDir;

/** Explicit local HiveServer2 integration check; excluded from the default *Test suite. */
class RuntimeProfileHiveServer2IT {

    private static final String LOCAL_HIVE_JDBC_URL =
            "jdbc:hive2://127.0.0.1:10015/default";

    @TempDir
    Path landingRoot;

    @Test
    void profileTestWorkflowReportsRealHiveServer2Probe() {
        RuntimeProfileMapper profileMapper = mock(RuntimeProfileMapper.class);
        MetricStore metricStore = mock(MetricStore.class);
        CredentialService credentialService = mock(CredentialService.class);
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(79L);
        profile.setType(RuntimeProfile.TYPE_LOCAL);
        profile.setStatus(RuntimeProfile.STATUS_DRAFT);
        profile.setLandingUri(landingRoot.toUri().toString());
        profile.setHiveJdbcUrl(LOCAL_HIVE_JDBC_URL);
        when(profileMapper.selectById(79L)).thenReturn(profile);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenReturn(List.of());

        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                profileMapper, metricStore, credentialService);

        RuntimeProfileService.TestResult result = service.test(79L);

        assertThat(result.checks()).filteredOn(check -> check.name().equals("hive"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.applicable()).isTrue();
                    assertThat(check.passed()).isTrue();
                    assertThat(check.detail()).isEqualTo("SELECT 1 ok (HiveServer2)");
                });
        assertThat(result.summary()).contains("hive=PASS");
        assertThat(result.allPassed()).isFalse(); // LOCAL's Spark submit path is deliberately absent.
        assertThat(profile.getStatus()).isEqualTo(RuntimeProfile.STATUS_DRAFT);
        verify(profileMapper).updateById(profile);
    }

    @Test
    void runtimeProfileProbeExecutesSelectOneAgainstHiveServer2() throws Exception {
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(LocalLandingStorage.class),
                mock(MetricStore.class), mock(CredentialService.class));
        RuntimeProfile profile = new RuntimeProfile();
        profile.setType(RuntimeProfile.TYPE_REMOTE_CLUSTER);
        profile.setHiveJdbcUrl(LOCAL_HIVE_JDBC_URL);

        Method checkHive = RuntimeProfileServiceImpl.class
                .getDeclaredMethod("checkHive", RuntimeProfile.class);
        checkHive.setAccessible(true);
        RuntimeProfileService.CheckDetail result;
        try {
            result = (RuntimeProfileService.CheckDetail) checkHive.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("RuntimeProfile HiveServer2 probe should return a check result",
                    e.getCause());
        }

        assertThat(result.applicable()).isTrue();
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("SELECT 1 ok (HiveServer2)");
        assertThat(result.detail()).doesNotContain("127.0.0.1", "10015", "jdbc:hive2");
    }
}
