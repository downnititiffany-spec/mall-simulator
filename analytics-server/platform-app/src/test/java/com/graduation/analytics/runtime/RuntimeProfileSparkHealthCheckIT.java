package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** Explicit local Spark CLI integration check; excluded from the default *Test suite. */
class RuntimeProfileSparkHealthCheckIT {

    @Test
    void runtimeProfileProbeExecutesConfiguredSparkSubmitVersionCommand() throws Exception {
        String sparkSubmitPath = System.getProperty("runtime.profile.sparkSubmitPath");
        assertNotNull(sparkSubmitPath, "Set -Druntime.profile.sparkSubmitPath to the installed Spark CLI path");

        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                mock(RuntimeProfileMapper.class), mock(MetricStore.class),
                mock(CredentialService.class));
        RuntimeProfile profile = new RuntimeProfile();
        profile.setType(RuntimeProfile.TYPE_SINGLE_NODE);
        profile.setSparkSubmitPath(sparkSubmitPath);

        Method checkSpark = RuntimeProfileServiceImpl.class
                .getDeclaredMethod("checkSpark", RuntimeProfile.class);
        checkSpark.setAccessible(true);
        RuntimeProfileService.CheckDetail result;
        try {
            result = (RuntimeProfileService.CheckDetail) checkSpark.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("RuntimeProfile Spark probe should return a check result", e.getCause());
        }

        assertThat(result.applicable()).isTrue();
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("探测通过");
        assertThat(result.detail()).doesNotContain(sparkSubmitPath, "spark-submit");
    }

}
