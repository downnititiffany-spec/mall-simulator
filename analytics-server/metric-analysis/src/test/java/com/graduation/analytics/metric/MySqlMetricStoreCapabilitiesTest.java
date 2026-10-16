package com.graduation.analytics.metric;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlMetricStoreCapabilitiesTest {

    private final JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate readJdbc = mock(JdbcTemplate.class);
    private final MySqlMetricStore store = new MySqlMetricStore(publishJdbc, readJdbc);

    @Test
    void reportsImplementedCapabilitiesAndLiveAvailability() {
        when(readJdbc.getQueryTimeout()).thenReturn(30);
        when(readJdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        MetricStore.Capabilities capabilities = store.capabilities();

        assertThat(capabilities.type()).isEqualTo("mysql");
        assertThat(capabilities.supportsSnapshot()).isTrue();
        assertThat(capabilities.supportsDimensions()).isFalse();
        assertThat(capabilities.maxRows()).isNull();
        assertThat(capabilities.queryTimeoutSeconds()).isEqualTo(30);
        assertThat(capabilities.available()).isTrue();
        verify(readJdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void reportsUnavailableWithoutLeakingHealthDiagnostic() {
        when(readJdbc.getQueryTimeout()).thenReturn(30);
        when(readJdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new IllegalStateException("private connection diagnostic"));

        MetricStore.HealthResult health = store.healthCheck();
        MetricStore.Capabilities capabilities = store.capabilities();

        assertThat(health.ok()).isFalse();
        assertThat(health.detail()).isEqualTo("mysql metric store unavailable (metric_read)");
        assertThat(health.detail()).doesNotContain("private connection diagnostic");
        assertThat(capabilities.type()).isEqualTo("mysql");
        assertThat(capabilities.available()).isFalse();
        assertThat(capabilities.toString()).doesNotContain("private connection diagnostic");
    }
}
