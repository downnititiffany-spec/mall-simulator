package com.graduation.analytics.runtime;

import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProfileSnapshotSourceFreezeTest {

    @Test
    void explicitFrozenSourceIdIsUsedInsteadOfMutableProfileValue() {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setSourceId(22L);
        profile.setVersion(9);

        RuntimeProfileSnapshot snapshot = RuntimeProfileSnapshot.from(profile, 11L);

        assertThat(snapshot.sourceId()).isEqualTo(11L);
        assertThat(snapshot.version()).isEqualTo(9);
    }

    @Test
    void legacyFactoryStillSnapshotsProfileSourceId() {
        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setSourceId(22L);

        assertThat(RuntimeProfileSnapshot.from(profile).sourceId()).isEqualTo(22L);
    }
}
