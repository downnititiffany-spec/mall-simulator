package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
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

class MySqlMetricStoreActiveProfileTest {

    private static final String ACTIVE_SNAPSHOT_SQL = "SELECT snapshot_id FROM metric_snapshot WHERE status = ? "
            + "AND runtime_profile_id = ? ORDER BY id DESC LIMIT 1";
    private final JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate readJdbc = mock(JdbcTemplate.class);
    private final ActiveRuntimeProfileIdProvider activeProfile = mock(ActiveRuntimeProfileIdProvider.class);

    @Test
    void latestActiveMetricQueryUsesTheGloballyActiveRuntimeProfile() {
        when(activeProfile.activeRuntimeProfileId()).thenReturn(Optional.of(41L));
        when(readJdbc.query(eq(ACTIVE_SNAPSHOT_SQL), any(RowMapper.class), eq("ACTIVE"), eq(41L)))
                .thenReturn(List.of("S_CURRENT"));
        MetricValue metric = new MetricValue();
        when(readJdbc.query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL),
                any(RowMapper.class), eq("S_CURRENT"), eq("ACTIVE"), eq("ARCHIVED")))
                .thenReturn(List.of(metric));

        MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc, activeProfile);
        List<MetricValue> result = store.query(new MetricStore.MetricQuery(null, true));

        assertThat(result).containsExactly(metric);
        verify(readJdbc).query(eq(ACTIVE_SNAPSHOT_SQL), any(RowMapper.class), eq("ACTIVE"), eq(41L));
    }

    @Test
    void explicitHistoricalSnapshotReadsOnlyPublishedStatusesByExactId() {
        MetricValue metric = new MetricValue();
        when(readJdbc.query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL),
                any(RowMapper.class), eq("S_ARCHIVED_OTHER_PROFILE"), eq("ACTIVE"), eq("ARCHIVED")))
                .thenReturn(List.of(metric));

        MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc, activeProfile);
        List<MetricValue> result = store.query(
                new MetricStore.MetricQuery("S_ARCHIVED_OTHER_PROFILE", false));

        assertThat(result).containsExactly(metric);
        verify(readJdbc).query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL), any(RowMapper.class),
                eq("S_ARCHIVED_OTHER_PROFILE"), eq("ACTIVE"), eq("ARCHIVED"));
        verify(activeProfile, never()).activeRuntimeProfileId();
    }

    @Test
    void explicitSnapshotWithNoPublishedRowReturnsNoMetricValues() {
        when(readJdbc.query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL),
                any(RowMapper.class), eq("S_FAILED"), eq("ACTIVE"), eq("ARCHIVED")))
                .thenReturn(List.of());

        MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc, activeProfile);

        assertThat(store.query(new MetricStore.MetricQuery("S_FAILED", false))).isEmpty();
        verify(readJdbc).query(eq(MySqlMetricStore.PUBLISHED_METRIC_VALUES_SQL), any(RowMapper.class),
                eq("S_FAILED"), eq("ACTIVE"), eq("ARCHIVED"));
        verify(activeProfile, never()).activeRuntimeProfileId();
    }

    @Test
    void missingProfileProviderFailsClosedForDefaultsAndSnapshotLists() {
        MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc);

        assertThat(store.activeSnapshotId()).isNull();
        assertThat(store.query(new MetricStore.MetricQuery(null, true))).isEmpty();
        assertThat(store.listSnapshots(10)).isEmpty();
        MetricStore.SnapshotPage page = store.listSnapshotsPage(1, 10, "id,desc");
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        verifyNoInteractions(readJdbc);
    }

    @Test
    void snapshotPagesAreScopedAndNoActiveProfileFailsClosed() {
        when(activeProfile.activeRuntimeProfileId()).thenReturn(Optional.of(41L));
        when(readJdbc.queryForObject("SELECT COUNT(*) FROM metric_snapshot WHERE runtime_profile_id = ?",
                Long.class, 41L)).thenReturn(1L);
        String pageSql = "SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time, "
                + "pipeline_run_id, status, version, definition_version, data_updated_at, published_at, source, "
                + "failure_reason, created_at, source_id FROM metric_snapshot WHERE runtime_profile_id = ? "
                + "ORDER BY id DESC LIMIT ? OFFSET ?";
        MetricSnapshot snapshot = new MetricSnapshot();
        when(readJdbc.query(pageSql, MySqlMetricStore.SNAPSHOT_MAPPER, 41L, 10, 0L))
                .thenReturn(List.of(snapshot));

        MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc, activeProfile);
        MetricStore.SnapshotPage page = store.listSnapshotsPage(1, 10, "id,desc");

        assertThat(page.items()).containsExactly(snapshot);
        assertThat(page.total()).isEqualTo(1);
        verify(readJdbc).query(pageSql, MySqlMetricStore.SNAPSHOT_MAPPER, 41L, 10, 0L);

        when(activeProfile.activeRuntimeProfileId()).thenReturn(Optional.empty());
        MetricStore.SnapshotPage empty = store.listSnapshotsPage(1, 10, "id,desc");
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isZero();
        verify(readJdbc, never()).queryForObject("SELECT COUNT(*) FROM metric_snapshot", Long.class);
    }
}
