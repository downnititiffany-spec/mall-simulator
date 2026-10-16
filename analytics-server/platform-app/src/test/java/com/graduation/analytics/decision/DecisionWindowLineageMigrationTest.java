package com.graduation.analytics.decision;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionWindowLineageMigrationTest {

    private static final Path META_DIR = RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");
    private static final Path SCRIPT = META_DIR.resolve("V31__decision_window_snapshot_lineage.sql");

    @Test
    @DisplayName("决策窗口迁移号唯一且仅增加完整窗口血缘列")
    void migrationIsUniqueAdditiveAndComplete() throws IOException {
        var versions = listVersions();
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        String code = Pattern.compile("(?m)^--[^\\r\\n]*$|/\\*.*?\\*/", Pattern.DOTALL)
                .matcher(sql).replaceAll(" ");

        assertThat(versions).doesNotHaveDuplicates().contains(31);
        assertThat(code.toLowerCase()).doesNotContain("drop ", "truncate ", "delete ", "rename ");
        assertThat(code).contains("source_id", "baseline_snapshot_refs", "baseline_window_start",
                "baseline_window_end", "actual_snapshot_refs", "metric_definition_version",
                "baseline_sample_count", "actual_sample_count", "runtime_profile_id");
    }

    private java.util.List<Integer> listVersions() throws IOException {
        try (Stream<Path> files = Files.list(META_DIR)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.*\\.sql"))
                    .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                    .collect(Collectors.toList());
        }
    }
}
