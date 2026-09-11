package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源画像校验（P1-03 口径 = D-035 / 任务书 §3.12）：文件存在 + 可解析为 JSON 对象 +
 * 文件内 sourceCode/profileVersion 与登记行一致 + **设计 §4.2 第 112–143 行列出的 9 个顶层键齐全**。
 *
 * <p>完整画像 Schema 校验器属 P3-01；本类只钉 P1-03 的最小口径，键名逐字取自设计 §4.2，不自创。</p>
 */
class SourceProfileValidatorTest {

    private static final String REL = "analytics-server/source-profiles/p1-03-probe-1.v1.json";
    private static final String CODE = "p1-03-probe-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path root;

    private SourceProfileValidator validator() {
        return new SourceProfileValidator(root.toString(), objectMapper);
    }

    private void write(String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private String validProfile(String code, String version) {
        return """
                {
                  "profileVersion": "%s",
                  "sourceCode": "%s",
                  "canonical": { "schemaVersion": "1.0" },
                  "eventTypeMapping": { "product_viewed": "view" },
                  "fieldMapping": { "buyer_id": "user_id" },
                  "enumSemantics": { "behavior": { "browse": "view" } },
                  "identityPolicy": { "user": { "rawField": "buyer_id", "shape": "UUID", "surrogate": "HASH64" } },
                  "timePolicy": { "field": "created_at", "formats": ["ISO_OFFSET_DATE_TIME"] },
                  "quarantinePolicy": { "unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD" }
                }
                """.formatted(version, code);
    }

    @Test
    @DisplayName("必备顶层键清单逐字等于设计 §4.2 的 9 个键（含顺序），禁止自创")
    void requiredKeysAreVerbatimFromDesign() {
        assertThat(SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS).containsExactly(
                "profileVersion", "sourceCode", "canonical", "eventTypeMapping", "fieldMapping",
                "enumSemantics", "identityPolicy", "timePolicy", "quarantinePolicy");
    }

    @Test
    @DisplayName("文件不存在：exists=false，detail 只回显仓库相对路径（不含绝对本机路径）")
    void missingFileIsReportedWithoutAbsolutePath() {
        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.exists()).isFalse();
        assertThat(check.ok()).isFalse();
        assertThat(check.detail()).contains(REL);
        assertThat(check.detail()).doesNotContain(root.toString());
    }

    @Test
    @DisplayName("文件不是合法 JSON / 不是 JSON 对象：jsonObject=false")
    void nonJsonOrNonObjectIsRejected() throws IOException {
        write(REL, "not json at all");
        SourceProfileValidator.ProfileCheck broken = validator().check(REL, CODE, "1.0");
        assertThat(broken.exists()).isTrue();
        assertThat(broken.jsonObject()).isFalse();
        assertThat(broken.ok()).isFalse();

        write(REL, "[1,2,3]");
        SourceProfileValidator.ProfileCheck array = validator().check(REL, CODE, "1.0");
        assertThat(array.jsonObject()).isFalse();
        assertThat(array.ok()).isFalse();
    }

    @Test
    @DisplayName("sourceCode / profileVersion 与登记不一致：分别标记，且 ok=false")
    void identityMismatchIsReported() throws IOException {
        write(REL, validProfile("another-source", "2.0"));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.jsonObject()).isTrue();
        assertThat(check.sourceCodeMatches()).isFalse();
        assertThat(check.profileVersionMatches()).isFalse();
        assertThat(check.ok()).isFalse();
    }

    @Test
    @DisplayName("缺顶层必备键：missingKeys 逐个列出，ok=false")
    void missingKeysAreListed() throws IOException {
        write(REL, """
                {"profileVersion":"1.0","sourceCode":"%s","canonical":{"schemaVersion":"1.0"}}
                """.formatted(CODE));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.missingKeys()).containsExactlyInAnyOrder(
                "eventTypeMapping", "fieldMapping", "enumSemantics",
                "identityPolicy", "timePolicy", "quarantinePolicy");
        assertThat(check.ok()).isFalse();
    }

    @Test
    @DisplayName("合法画像：全部通过，missingKeys 为空")
    void validProfilePasses() throws IOException {
        write(REL, validProfile(CODE, "1.0"));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.exists()).isTrue();
        assertThat(check.jsonObject()).isTrue();
        assertThat(check.sourceCodeMatches()).isTrue();
        assertThat(check.profileVersionMatches()).isTrue();
        assertThat(check.missingKeys()).isEmpty();
        assertThat(check.ok()).isTrue();
    }

    @Test
    @DisplayName("顶层键齐全但值为 null 不算缺失键（键存在性判据是键名，不是值）")
    void nullValuedKeysStillCountAsPresent() throws IOException {
        write(REL, validProfile(CODE, "1.0").replace("\"canonical\": { \"schemaVersion\": \"1.0\" }",
                "\"canonical\": null"));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.missingKeys()).isEmpty();
        assertThat(check.ok()).isTrue();
    }

    @Test
    @DisplayName("路径策略违规（绝对路径 / .. 逃逸 / 空）由路径策略先拦，不给校验器")
    void pathPolicyIsSeparatelyOwned() {
        assertThat(SourcePathPolicy.isRepoRelative("C:/x/y.json")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative("/etc/passwd")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative("\\\\server\\share\\x.json")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative("a/../../b.json")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative("a\\b.json")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative("")).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative(null)).isFalse();
        assertThat(SourcePathPolicy.isRepoRelative(REL)).isTrue();
        assertThat(SourcePathPolicy.isRepoRelative("./analytics-server/source-profiles/x.json")).isTrue();
        assertThat(List.of(REL).get(0)).isEqualTo(REL);
    }
}
