package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlMetricStoreWindowQueryTest {

    @Test
    @DisplayName("窗口查询绑定来源/日期/口径，只保留每个业务日最新已发布快照")
    void filtersBySourceVersionAndWindowThenDeduplicatesPerDay() throws Exception {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        MySqlMetricStore store = new MySqlMetricStore(publish, read);
        ResultSet older = row("S1-old", 8L, 7L, "gmv", "10", "day:2026-09-01", "v1",
                LocalDateTime.of(2026, 9, 1, 23, 0));
        ResultSet latest = row("S1-new", 8L, 7L, "gmv", "12", "day:2026-09-01", "v1",
                LocalDateTime.of(2026, 9, 2, 1, 0));
        ResultSet next = row("S2", 8L, 7L, "gmv", "20", "day:2026-09-02", "v1",
                LocalDateTime.of(2026, 9, 3, 1, 0));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<MetricStore.WindowMetricValue> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(latest, 0), mapper.mapRow(older, 1), mapper.mapRow(next, 2));
        }).when(read).query(anyString(), any(RowMapper.class), any(Object[].class));

        List<MetricStore.WindowMetricValue> result = store.queryWindow(new MetricStore.WindowMetricQuery(
                8L, 7L, "gmv", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), "v1"));

        assertThat(result).extracting(MetricStore.WindowMetricValue::snapshotId).containsExactly("S1-new", "S2");
        assertThat(result).extracting(MetricStore.WindowMetricValue::value)
                .containsExactly(new BigDecimal("12"), new BigDecimal("20"));
        assertThat(result).extracting(MetricStore.WindowMetricValue::runtimeProfileId).containsOnly(8L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> bindings = ArgumentCaptor.forClass(Object[].class);
        verify(read, times(1)).query(sql.capture(), any(RowMapper.class), bindings.capture());
        assertThat(sql.getValue()).contains("ms.runtime_profile_id = ?", "ms.source_id = ?",
                "mv.metric_code = ?", "mv.definition_version = ?", "mv.period >= ?", "mv.period <= ?",
                "ms.status IN (?, ?)", "ORDER BY mv.period ASC, ms.published_at DESC, ms.id DESC");
        assertThat(bindings.getValue()).containsExactly(8L, 7L, "gmv", "v1", "day:2026-09-01", "day:2026-09-02",
                MetricSnapshot.STATUS_ACTIVE, MetricSnapshot.STATUS_ARCHIVED);
    }

    @Test
    @DisplayName("无效来源/版本/日期范围 fail-closed 且不触达数据库")
    void invalidWindowDoesNotQueryDatabase() {
        JdbcTemplate publish = mock(JdbcTemplate.class);
        JdbcTemplate read = mock(JdbcTemplate.class);
        MySqlMetricStore store = new MySqlMetricStore(publish, read);

        assertThat(store.queryWindow(new MetricStore.WindowMetricQuery(null, 7L, "gmv",
                LocalDate.now(), LocalDate.now(), "v1"))).isEmpty();
        assertThat(store.queryWindow(new MetricStore.WindowMetricQuery(8L, null, "gmv",
                LocalDate.now(), LocalDate.now(), "v1"))).isEmpty();
        assertThat(store.queryWindow(new MetricStore.WindowMetricQuery(8L, 7L, "gmv",
                LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), "v1"))).isEmpty();
        verify(read, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet row(String snapshotId, Long runtimeProfileId, Long sourceId, String metricCode, String value, String period,
                          String version, LocalDateTime publishedAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("period")).thenReturn(period);
        when(rs.getString("snapshot_id")).thenReturn(snapshotId);
        when(rs.getObject("runtime_profile_id", Long.class)).thenReturn(runtimeProfileId);
        when(rs.getObject("source_id", Long.class)).thenReturn(sourceId);
        when(rs.getString("metric_code")).thenReturn(metricCode);
        when(rs.getBigDecimal("metric_value")).thenReturn(new BigDecimal(value));
        when(rs.getString("definition_version")).thenReturn(version);
        when(rs.getTimestamp("published_at")).thenReturn(Timestamp.valueOf(publishedAt));
        return rs;
    }
}
