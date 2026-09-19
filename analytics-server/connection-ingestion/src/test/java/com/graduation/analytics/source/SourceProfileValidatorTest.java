package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.mapping.MappingProfileLoader;
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
 * 文件内 sourceCode/profileVersion 与登记行一致 + **顶层必备键齐全**。
 *
 * <p>顶层必备键按画像语法分派（D-029）：v1 用设计 §4.2 的 9 个键（键名逐字），
 * v2（V2_STRICT）用 {@link MappingProfileLoader#V2_STRICT_TOP_LEVEL_KEYS} 的 8 个键——
 * 与摄取路径的 Loader 同一权威清单，不自创第二份。</p>
 *
 * <p>完整画像 Schema 校验器属 P3-01；本类只钉 P1-03 的最小口径，键名不自创。</p>
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

    private String validV2Profile(String code, String version) {
        return """
                {
                  "profileVersion": "%s",
                  "sourceCode": "%s",
                  "contractVersion": "1.0",
                  "eventTypeMappings": { "sourceField": "state", "values": { "ORDER_PLACED": "order_created" } },
                  "fieldMappings": { "event": { "event_id": "evt_no" } },
                  "enumSemantics": { "behavior_type": { "BROWSE": "view" } },
                  "timePolicy": { "field": "occur_at", "formats": ["ISO_OFFSET_DATE_TIME"], "zone": "Asia/Shanghai" },
                  "amountPolicy": { "bySourceField": { "grand_minor": "FEN" } }
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
    @DisplayName("v2 必备键清单 = Loader 的 V2_STRICT_TOP_LEVEL_KEYS（同一权威清单，禁止第二份）")
    void v2RequiredKeysAreExactlyTheLoaderSet() {
        assertThat(SourceProfileValidator.V2_STRICT_REQUIRED_KEYS)
                .containsExactlyInAnyOrderElementsOf(MappingProfileLoader.V2_STRICT_TOP_LEVEL_KEYS);
        assertThat(SourceProfileValidator.V2_STRICT_REQUIRED_KEYS)
                .doesNotContain("canonical", "eventTypeMapping", "fieldMapping", "identityPolicy", "quarantinePolicy");
    }

    @Test
    @DisplayName("V2_STRICT 画像（fieldMappings 为对象）：按 v2 口径放行，语法名与必备键如实回填")
    void validV2ProfilePassesUnderV2KeySet() throws IOException {
        write(REL, validV2Profile(CODE, "2.0"));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "2.0");

        assertThat(check.exists()).isTrue();
        assertThat(check.jsonObject()).isTrue();
        assertThat(check.sourceCodeMatches()).isTrue();
        assertThat(check.profileVersionMatches()).isTrue();
        assertThat(check.missingKeys()).isEmpty();
        assertThat(check.requiredTopLevelKeys()).isEqualTo(SourceProfileValidator.V2_STRICT_REQUIRED_KEYS);
        assertThat(check.syntaxName()).isEqualTo("V2_STRICT");
        assertThat(check.detail()).contains("V2_STRICT 的 " + SourceProfileValidator.V2_STRICT_REQUIRED_KEYS.size()
                + " 个顶层必备键齐全");
        assertThat(check.ok()).isTrue();
    }

    @Test
    @DisplayName("V2_STRICT 画像缺 amountPolicy：只缺 v2 键，不按 v1 口径误报 v1 专属键")
    void v2ProfileMissingV2KeyIsReportedUnderV2KeySet() throws IOException {
        write(REL, validV2Profile(CODE, "2.0").replace("\"amountPolicy\": { \"bySourceField\": { \"grand_minor\": \"FEN\" } }",
                "\"enumSemantics2\": {}"));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "2.0");

        assertThat(check.missingKeys()).containsExactly("amountPolicy");
        assertThat(check.missingKeys()).doesNotContain("canonical", "eventTypeMapping", "fieldMapping",
                "identityPolicy", "quarantinePolicy");
        assertThat(check.syntaxName()).isEqualTo("V2_STRICT");
        assertThat(check.detail()).contains("画像缺 V2_STRICT 顶层必备键：amountPolicy");
        assertThat(check.ok()).isFalse();
    }

    @Test
    @DisplayName("v1 扁平画像（无 fieldMappings）：仍按设计 §4.2 口径，语法名 V1_COMPATIBILITY")
    void v1ProfileKeepsDesignKeySetAndSyntaxName() throws IOException {
        write(REL, """
                {"profileVersion":"1.0","sourceCode":"%s","canonical":{"schemaVersion":"1.0"}}
                """.formatted(CODE));

        SourceProfileValidator.ProfileCheck check = validator().check(REL, CODE, "1.0");

        assertThat(check.syntaxName()).isEqualTo("V1_COMPATIBILITY");
        assertThat(check.requiredTopLevelKeys()).isEqualTo(SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS);
        assertThat(check.missingKeys()).containsExactlyInAnyOrder(
                "eventTypeMapping", "fieldMapping", "enumSemantics",
                "identityPolicy", "timePolicy", "quarantinePolicy");
        assertThat(check.detail()).contains("画像缺设计 §4.2 顶层必备键：");
        assertThat(check.ok()).isFalse();
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
