package com.graduation.analytics.runtime;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeProfileHiveActivationGateTest {

    @TempDir
    Path landingRoot;

    @Test
    void singleNodeWithoutHiveJdbcUrlCannotActivateOrReplaceCurrentProfile() {
        RuntimeProfileMapper profileMapper = mock(RuntimeProfileMapper.class);
        MetricStore metricStore = mock(MetricStore.class);
        CredentialService credentialService = mock(CredentialService.class);
        RuntimeProfile candidate = new RuntimeProfile();
        candidate.setId(41L);
        candidate.setType(RuntimeProfile.TYPE_SINGLE_NODE);
        candidate.setStatus(RuntimeProfile.STATUS_DRAFT);
        candidate.setLandingUri(landingRoot.toUri().toString());
        candidate.setSparkSubmitPath(null);

        when(profileMapper.selectById(41L)).thenReturn(candidate);
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenReturn(List.of());
        RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
                profileMapper, mock(LocalLandingStorage.class), metricStore, credentialService);

        assertThatThrownBy(() -> service.activate(41L))
                .isInstanceOfSatisfying(PlatformBizException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("ACTIVATE_CHECK_FAILED");
                    assertThat(error.getMessage()).contains("hive: UNKNOWN: 未配置 hive_jdbc_url");
                });

        assertThat(candidate.getStatus()).isEqualTo(RuntimeProfile.STATUS_DRAFT);
        verify(profileMapper, never()).updateById(any(RuntimeProfile.class));
        verify(profileMapper, never()).selectList(any());
        verifyNoInteractions(credentialService);
    }
}
