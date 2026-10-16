package com.graduation.analytics.runtime;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProfileSingleActiveMigrationScriptTest {

    private static final Path META_DIR = RepoRoot.path(
            "analytics-server/platform-app/src/main/resources/db/meta");
    private static final String SCRIPT_NAME = "V35__runtime_profile_single_active.sql";

    @Test
    void migrationVersionIsUniqueAndScriptExists() {
        List<Integer> versions;
        try (Stream<Path> files = Files.list(META_DIR)) {
            versions = files.map(path -> path.getFileName().toString())
                    .map(name -> Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(name))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(versions).doesNotHaveDuplicates().contains(35);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME))).isTrue();
    }

    @Test
    void migrationAddsGeneratedUniqueGuardWithoutRewritingProfileRows() {
        String sql = script().replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ");
        List<String> statements = Stream.of(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0))
                .matches("(?is)^alter\\s+table\\s+runtime_profile\\b.*");
        assertThat(sql)
                .containsPattern("(?is)active_profile_guard\\s+tinyint\\s+generated\\s+always\\s+as\\s*\\(\\s*case\\s+when\\s+status\\s*=\\s*'ACTIVE'\\s+then\\s+1\\s+else\\s+null\\s+end\\s*\\)\\s+stored")
                .containsPattern("(?is)add\\s+unique\\s+key\\s+uk_runtime_profile_single_active\\s*\\(\\s*active_profile_guard\\s*\\)")
                .doesNotContainPattern("(?i)\\b(insert|update|delete|drop|truncate|rename)\\b");
    }

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败", e);
        }
    }
}
