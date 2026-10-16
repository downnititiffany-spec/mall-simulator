package com.graduation.analytics.ai;

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

class AiCallRequestIdentityMigrationScriptTest {

    private static final Path META_DIR = RepoRoot.path(
            "analytics-server/platform-app/src/main/resources/db/meta");
    private static final String SCRIPT_NAME = "V36__ai_call_request_identity.sql";

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
        assertThat(versions).doesNotHaveDuplicates().contains(36);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME))).isTrue();
    }

    @Test
    void migrationAddsNullableAuditIdentityWithoutUniquenessOrBackfill() {
        String sql = script().replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ");
        List<String> statements = Stream.of(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).matches("(?is)^alter\\s+table\\s+ai_call_log\\b.*");
        assertThat(sql)
                .containsPattern("(?is)add\\s+column\\s+source_id\\s+bigint\\s+null")
                .containsPattern("(?is)add\\s+column\\s+snapshot_id\\s+varchar\\(64\\)\\s+null")
                .containsPattern("(?is)add\\s+column\\s+request_fingerprint\\s+char\\(64\\).*?\\s+null")
                .containsPattern("(?is)add\\s+key\\s+idx_ai_call_fingerprint\\s*\\(\\s*request_fingerprint\\s*\\)")
                .containsPattern("(?is)add\\s+key\\s+idx_ai_call_source_snapshot\\s*\\(\\s*source_id\\s*,\\s*snapshot_id\\s*\\)")
                .doesNotContainPattern("(?i)\\b(unique|insert|update|delete|drop|truncate|rename)\\b");
    }

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败", e);
        }
    }
}
