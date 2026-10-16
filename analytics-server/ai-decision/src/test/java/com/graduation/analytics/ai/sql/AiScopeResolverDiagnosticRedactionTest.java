package com.graduation.analytics.ai.sql;

import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiScopeResolverDiagnosticRedactionTest {

    @Test
    void snapshotReadFailureDoesNotExposeDriverMessageOrCause() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActiveRuntimeProfileIdProvider profiles = mock(ActiveRuntimeProfileIdProvider.class);
        when(profiles.activeRuntimeProfileId()).thenReturn(Optional.of(31L));
        when(jdbc.queryForList(eq(AiScopeResolver.ACTIVE_SNAPSHOT_SQL), eq(31L)))
                .thenThrow(new DataAccessResourceFailureException(
                        "jdbc failure containing UNTRUSTED_SECRET_MARKER"));

        assertThatThrownBy(() -> new AiScopeResolver(jdbc, profiles).resolve())
                .isInstanceOfSatisfying(AiSqlException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(SqlPolicy.METRIC_READ_SOURCE_MISSING);
                    assertThat(failure.getMessage()).isEqualTo("读取指标快照作用域失败（fail-closed）");
                    assertThat(failure.getMessage()).doesNotContain("UNTRUSTED_SECRET_MARKER");
                    assertThat(failure.getCause()).isNull();
                });
    }
}
