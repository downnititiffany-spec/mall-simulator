package com.graduation.analytics.metric;

import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MetricAdsReaderActiveProfileTest {

    private static final String ACTIVE_SQL = "SELECT snapshot_id FROM metric_snapshot WHERE status = ? "
            + "AND runtime_profile_id = ? ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate readJdbc = mock(JdbcTemplate.class);
    private final ActiveRuntimeProfileIdProvider activeProfile = mock(ActiveRuntimeProfileIdProvider.class);

    @Test
    void defaultActiveSnapshotIsScopedToGloballyActiveRuntimeProfile() {
        when(activeProfile.activeRuntimeProfileId()).thenReturn(Optional.of(41L));
        when(readJdbc.query(eq(ACTIVE_SQL), any(RowMapper.class), eq("ACTIVE"), eq(41L)))
                .thenReturn(List.of("S_PROFILE_41"));

        String snapshotId = new MetricAdsReader(readJdbc, activeProfile).activeSnapshotId();

        assertThat(snapshotId).isEqualTo("S_PROFILE_41");
        verify(readJdbc).query(eq(ACTIVE_SQL), any(RowMapper.class), eq("ACTIVE"), eq(41L));
    }

    @Test
    void noActiveRuntimeProfileReturnsNullWithoutGlobalSnapshotLookup() {
        when(activeProfile.activeRuntimeProfileId()).thenReturn(Optional.empty());

        assertThat(new MetricAdsReader(readJdbc, activeProfile).activeSnapshotId()).isNull();

        verify(readJdbc, never()).query(eq("SELECT snapshot_id FROM metric_snapshot WHERE status = ? "
                + "ORDER BY id DESC LIMIT 1"), any(RowMapper.class), eq("ACTIVE"));
    }

    @Test
    void missingProfileProviderFailsClosedWithoutGlobalSnapshotLookup() {
        assertThat(new MetricAdsReader(readJdbc).activeSnapshotId()).isNull();

        verifyNoInteractions(readJdbc);
    }
}
