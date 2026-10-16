package com.graduation.analytics.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiFeedbackMigrationScriptTest {
    @Test
    void v37AddsFeedbackTableAndNullableEvidenceAnchorsWithoutRewritingHistory() throws Exception {
        Path migration = Path.of("src/main/resources/db/meta/V37__ai_feedback_and_history_evidence.sql");
        String sql = Files.readString(migration);

        assertThat(sql).contains("ALTER TABLE ai_query_history", "ADD COLUMN source_id BIGINT NULL",
                "ADD COLUMN definition_version VARCHAR(32) NULL", "ADD COLUMN evidence_id VARCHAR(64) NULL",
                "CREATE TABLE ai_feedback", "UNIQUE KEY uk_ai_feedback_history (history_id)",
                "FOREIGN KEY (history_id) REFERENCES ai_query_history (id)");
        assertThat(sql.toUpperCase()).doesNotContain("DROP TABLE", "DROP COLUMN", "UPDATE AI_QUERY_HISTORY");
    }
}
