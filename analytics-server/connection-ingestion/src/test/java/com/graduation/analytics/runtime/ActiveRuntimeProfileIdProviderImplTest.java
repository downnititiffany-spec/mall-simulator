package com.graduation.analytics.runtime;

import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.mapper.RuntimeProfileMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveRuntimeProfileIdProviderImplTest {

    private final RuntimeProfileMapper profileMapper = mock(RuntimeProfileMapper.class);
    private final ActiveRuntimeProfileIdProviderImpl provider =
            new ActiveRuntimeProfileIdProviderImpl(profileMapper);

    @Test
    void returnsIdWhenExactlyOneActiveProfileExists() {
        when(profileMapper.selectList(any())).thenReturn(List.of(profile(41L)));

        assertThat(provider.activeRuntimeProfileId()).isEqualTo(Optional.of(41L));
    }

    @Test
    void returnsEmptyWhenNoActiveProfileExists() {
        when(profileMapper.selectList(any())).thenReturn(List.of());

        assertThat(provider.activeRuntimeProfileId()).isEmpty();
    }

    @Test
    void failsClosedWhenMultipleActiveProfilesExist() {
        when(profileMapper.selectList(any()))
                .thenReturn(List.of(profile(41L), profile(42L)));

        assertThat(provider.activeRuntimeProfileId()).isEmpty();
    }

    @Test
    void failsClosedWhenTheOnlyActiveProfileHasNoValidId() {
        when(profileMapper.selectList(any())).thenReturn(List.of(profile(null)));

        assertThat(provider.activeRuntimeProfileId()).isEmpty();
    }

    private static RuntimeProfile profile(Long id) {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(id);
        profile.setStatus(RuntimeProfile.STATUS_ACTIVE);
        return profile;
    }
}
