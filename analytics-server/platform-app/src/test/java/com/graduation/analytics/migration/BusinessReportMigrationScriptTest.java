package com.graduation.analytics.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessReportMigrationScriptTest {
    @Test
    void v38AddsReportMetadataAndStructuredJsonArtifactTable() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/meta/V38__business_report_instances.sql"));
        assertThat(sql).contains("CREATE TABLE report_instance", "template_code", "source_id",
                "snapshot_id", "definition_version", "artifact_checksum", "artifact_json LONGTEXT",
                "KEY idx_report_creator_time");
        assertThat(sql.toUpperCase()).doesNotContain("DROP TABLE", "DROP COLUMN");
        assertThat(sql).doesNotMatch("(?im)^\\s*UPDATE\\s+.*");
    }
}
