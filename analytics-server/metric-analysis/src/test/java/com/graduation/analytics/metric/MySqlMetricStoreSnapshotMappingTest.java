package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlMetricStoreSnapshotMappingTest {

    @Test
    @DisplayName("snapshot 行映射读取 source_id，并保留它与发布方 source 的分离")
    void mapsBusinessSourceIdSeparatelyFromPublisher() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(9L);
        when(row.getString("snapshot_id")).thenReturn("S9");
        when(row.getString("source")).thenReturn("spark-ads");
        when(row.getObject("source_id", Long.class)).thenReturn(42L);

        MetricSnapshot snapshot = MySqlMetricStore.SNAPSHOT_MAPPER.mapRow(row, 0);

        assertThat(snapshot.getSourceId()).isEqualTo(42L);
        assertThat(snapshot.getSource()).isEqualTo("spark-ads");
    }

    @Test
    @DisplayName("历史快照 source_id SQL NULL 映射为 Java null")
    void mapsHistoricalNullSourceIdAsNull() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(8L);
        when(row.getString("snapshot_id")).thenReturn("S8");
        when(row.getObject("source_id", Long.class)).thenReturn(null);

        MetricSnapshot snapshot = MySqlMetricStore.SNAPSHOT_MAPPER.mapRow(row, 0);

        assertThat(snapshot.getSourceId()).isNull();
    }
}
