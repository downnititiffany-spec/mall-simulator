package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricPublishRepositorySourceIdentityTest {

    private static final String SNAPSHOT_ID = "S-IDENTITY-1";

    @Test
    @DisplayName("legacy retry with null sourceId preserves the snapshot's existing identity")
    void nullIdentityRetryDoesNotErasePersistedSourceId() {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        when(publish.query(contains("active_flag"), anyStringRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of());
        when(publish.query(contains("SELECT source_id"), anyRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of(27L));
        when(publish.queryForObject(contains("COALESCE(MAX(version)"), eq(Integer.class), eq(7L)))
                .thenReturn(4);

        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        repository.createBuilding(7L, 3, SNAPSHOT_ID, "2026-09-23", "2026-09-23T00:00:00", 99L, "v2");

        verify(publish).update(contains("source_id = ?"), eq(MetricSnapshot.STATUS_BUILDING), eq(27L), eq(3),
                eq(99L), any(Timestamp.class), any(Timestamp.class), eq(SNAPSHOT_ID));
    }

    @Test
    @DisplayName("same persisted source identity is accepted on retry")
    void sameIdentityRetryIsIdempotentlyAccepted() {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        when(publish.query(contains("active_flag"), anyStringRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of());
        when(publish.query(contains("SELECT source_id"), anyRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of(27L));
        when(publish.queryForObject(contains("COALESCE(MAX(version)"), eq(Integer.class), eq(7L)))
                .thenReturn(4);

        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        repository.createBuilding(7L, 3, SNAPSHOT_ID, "2026-09-23", "2026-09-23T00:00:00", 99L, "v2", 27L);

        verify(publish).update(contains("source_id = ?"), eq(MetricSnapshot.STATUS_BUILDING), eq(27L), eq(3),
                eq(99L), any(Timestamp.class), any(Timestamp.class), eq(SNAPSHOT_ID));
    }

    @Test
    @DisplayName("different non-null identity for the same snapshotId fails before any mutation")
    void conflictingIdentityFailsClosed() {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        when(publish.query(contains("active_flag"), anyStringRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of());
        when(publish.query(contains("SELECT source_id"), anyRowMapper(), eq(SNAPSHOT_ID))).thenReturn(List.of(27L));

        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        assertThatThrownBy(() -> repository.createBuilding(7L, 3, SNAPSHOT_ID, "2026-09-23",
                "2026-09-23T00:00:00", 99L, "v2", 28L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing conflicting source_id 28");

        org.mockito.Mockito.verify(publish, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("重试不得把当前 ACTIVE 快照重置成 BUILDING")
    void activeSnapshotCannotBeReusedForBuilding() {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        when(publish.query(contains("active_flag"), anyStringRowMapper(), eq(SNAPSHOT_ID)))
                .thenReturn(List.of(SNAPSHOT_ID));

        MetricPublishRepository repository = new MetricPublishRepository(publish, read);
        assertThatThrownBy(() -> repository.createBuilding(7L, 3, SNAPSHOT_ID, "2026-09-23",
                "2026-09-23T00:00:00", 99L, "v2", 27L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is already ACTIVE")
                .hasMessageContaining("allocate a new snapshotId");

        org.mockito.Mockito.verify(publish, org.mockito.Mockito.never())
                .query(contains("SELECT source_id"), anyRowMapper(), eq(SNAPSHOT_ID));
        org.mockito.Mockito.verify(publish, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private static RowMapper<Long> anyRowMapper() {
        return (RowMapper<Long>) any(RowMapper.class);
    }

    @SuppressWarnings("unchecked")
    private static RowMapper<String> anyStringRowMapper() {
        return (RowMapper<String>) any(RowMapper.class);
    }
}
