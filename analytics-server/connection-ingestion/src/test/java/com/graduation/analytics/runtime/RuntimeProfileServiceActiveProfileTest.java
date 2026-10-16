package com.graduation.analytics.runtime;

import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeProfileServiceActiveProfileTest {

    private final RuntimeProfileMapper profileMapper = mock(RuntimeProfileMapper.class);
    private final RuntimeProfileServiceImpl service = new RuntimeProfileServiceImpl(
            profileMapper,
            mock(LocalLandingStorage.class),
            mock(MetricStore.class),
            mock(CredentialService.class));

    @Test
    void returnsProfileWhenExactlyOneActiveProfileExists() {
        when(profileMapper.selectList(any())).thenReturn(List.of(profile(41L)));

        assertThat(service.findActive()).contains(profile(41L));
    }

    @Test
    void returnsEmptyWhenNoActiveProfileExists() {
        when(profileMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findActive()).isEmpty();
    }

    @Test
    void failsClosedWhenMultipleActiveProfilesExist() {
        when(profileMapper.selectList(any()))
                .thenReturn(List.of(profile(41L), profile(42L)));

        assertThat(service.findActive()).isEmpty();
    }

    @Test
    void failsClosedWhenTheOnlyActiveProfileHasNoValidId() {
        when(profileMapper.selectList(any())).thenReturn(List.of(profile(null)));

        assertThat(service.findActive()).isEmpty();
    }

    private static RuntimeProfile profile(Long id) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(id);
        profile.setStatus(RuntimeProfile.STATUS_ACTIVE);
        return profile;
    }
}
