package com.graduation.analytics.metric;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlMetricStorePaginationTest {

    private final JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate readJdbc = mock(JdbcTemplate.class);
    private final ActiveRuntimeProfileIdProvider activeProfile = () -> Optional.of(41L);
    private final MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc, activeProfile);

    @Test
    void returnsStablePageMetadataAndWhitelistedSortSql() {
        MetricSnapshot row = new MetricSnapshot();
        row.setId(11L);
        when(readJdbc.queryForObject("SELECT COUNT(*) FROM metric_snapshot WHERE runtime_profile_id = ?",
                Long.class, 41L)).thenReturn(21L);
        when(readJdbc.query(anyString(), eq(MySqlMetricStore.SNAPSHOT_MAPPER), eq(41L), eq(10), eq(10L)))
                .thenReturn(List.of(row));

        MySqlMetricStore.SnapshotPage result = store.listSnapshotsPage(2, 10, "createdAt,DESC");

        assertThat(result.items()).containsExactly(row);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.sort()).isEqualTo("createdat,desc");
        verify(readJdbc).query("SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time, "
                        + "pipeline_run_id, status, version, definition_version, data_updated_at, published_at, "
                        + "source, failure_reason, created_at, source_id FROM metric_snapshot "
                        + "WHERE runtime_profile_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MySqlMetricStore.SNAPSHOT_MAPPER, 41L, 10, 10L);
    }

    @Test
    void clampsPastEndPageAndRejectsUnlistedSortWithoutQueryingRows() {
        when(readJdbc.queryForObject("SELECT COUNT(*) FROM metric_snapshot WHERE runtime_profile_id = ?",
                Long.class, 41L)).thenReturn(11L);
        when(readJdbc.query(anyString(), eq(MySqlMetricStore.SNAPSHOT_MAPPER), eq(41L), eq(10), eq(10L)))
                .thenReturn(List.of());

        MySqlMetricStore.SnapshotPage result = store.listSnapshotsPage(9, 10, "id,desc");

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThatThrownBy(() -> store.listSnapshotsPage(1, 10, "snapshot_id;drop table,desc"))
                .isInstanceOf(PlatformBizException.class);
        verify(readJdbc, never()).query(anyString(), eq(MySqlMetricStore.SNAPSHOT_MAPPER), eq(41L), eq(10), eq(0L));
    }

    @Test
    void rejectsInvalidBoundsBeforeReading() {
        assertThatThrownBy(() -> store.listSnapshotsPage(0, 10, "id,desc"))
                .isInstanceOf(PlatformBizException.class);
        assertThatThrownBy(() -> store.listSnapshotsPage(1, 101, "id,desc"))
                .isInstanceOf(PlatformBizException.class);
        verify(readJdbc, never()).queryForObject(
                "SELECT COUNT(*) FROM metric_snapshot WHERE runtime_profile_id = ?", Long.class, 41L);
    }
}
