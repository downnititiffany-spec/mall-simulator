package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-03 自备画像夹具（E3 真机验证"激活合法源"必需，因为 V16 种子的
 * {@code analytics-server/source-profiles/mock-mall.v1.json} 是 **P3-01 的交付物，本任务不创建**）。
 *
 * <p>夹具不得占用 {@code mock-mall.v1.json}（否则等于代 P3-01 交付），
 * 且必须满足设计 §4.2 的顶层必备键与路径形状 {@code analytics-server/source-profiles/<source>.v1.json}。</p>
 */
class SourceProfileFixtureTest {

    /** P1-03 自备探针画像（仓库相对路径，不占用种子源的 profile_path） */
    private static final List<String> PROBE_FILES = List.of(
            "p1-03-probe-1.v1.json",
            "p1-03-probe-2.v1.json");

    private static final String DIR = "analytics-server/source-profiles";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("探针画像存在且路径形状合规（仓库相对、无 ..、位于 analytics-server/source-profiles/）")
    void probeFixturesExistAtCompliantPaths() {
        List<String> problems = new ArrayList<>();
        for (String fileName : PROBE_FILES) {
            String relative = DIR + "/" + fileName;
            assertThat(SourcePathPolicy.isRepoRelative(relative))
                    .as("夹具路径必须是仓库相对路径：%s", relative).isTrue();
            Path file = RepoRoot.path(relative);
            if (!Files.isRegularFile(file)) {
                problems.add("缺失: " + relative);
                continue;
            }
            assertThat(file.normalize().startsWith(RepoRoot.path(DIR).normalize()))
                    .as("夹具不得逃逸出 %s：%s", DIR, relative).isTrue();
        }
        assertThat(problems).as("P1-03 探针画像必须落盘（E3 场景 10/12 依赖它）").isEmpty();
    }

    @Test
    @DisplayName("探针画像顶层必备键齐全（设计 §4.2 的 9 个键），且 sourceCode/profileVersion 自洽")
    void probeFixturesCarryRequiredKeys() throws IOException {
        for (String fileName : PROBE_FILES) {
            Path file = RepoRoot.path(DIR + "/" + fileName);
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(node.isObject()).as("%s 必须是 JSON 对象", fileName).isTrue();

            List<String> missing = SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS.stream()
                    .filter(key -> !node.has(key))
                    .toList();
            assertThat(missing).as("%s 缺顶层必备键", fileName).isEmpty();

            String sourceCode = node.get("sourceCode").asText();
            String version = node.get("profileVersion").asText();
            assertThat(fileName).as("文件名形状必须是 <sourceCode>.v<major>.json 且 profileVersion 主版本一致")
                    .isEqualTo("%s.v%s.json".formatted(sourceCode, version.split("\\.")[0]));
            assertThat(sourceCode).startsWith("p1-03-probe-");
        }
    }

    @Test
    @DisplayName("探针画像不得占用 P3-01 的交付物 mock-mall.v1.json")
    void probeFixturesDoNotClaimSeedProfilePath() {
        assertThat(PROBE_FILES).doesNotContain("mock-mall.v1.json");
        for (String fileName : PROBE_FILES) {
            assertThat(fileName).doesNotContain("mock-mall");
        }
    }

    @Test
    @DisplayName("探针画像内容不夹带本机绝对路径或凭据（源画像会被回显在 API 明细里）")
    void probeFixturesCarryNoSecretsOrAbsolutePaths() throws IOException {
        for (String fileName : PROBE_FILES) {
            String text = Files.readString(RepoRoot.path(DIR + "/" + fileName), StandardCharsets.UTF_8);
            assertThat(text).as("%s 不得含 Windows 盘符路径", fileName)
                    .doesNotContainPattern("[A-Za-z]:[\\\\/]");
            assertThat(text).as("%s 不得含凭据字段", fileName)
                    .doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret")
                    .doesNotContainIgnoringCase("token");
        }
    }
}
