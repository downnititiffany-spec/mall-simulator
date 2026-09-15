package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * S2-01A 映射执行器测试支撑（仅测试作用域）。
 *
 * <p>契约真相来自中立目录 {@code contract-specs/schemas/canonical-event.v1.schema.json}（只读），
 * 测试**不复制**契约内容；v2 测试画像放在 {@code src/test/resources/s2-01a/}。</p>
 *
 * <p>注意：{@code com.graduation.analytics.testsupport.RepoRoot} 位于 platform-common 的 test
 * 作用域，本模块看不到，因此此处保留一个最小「向上找仓根」实现（只在测试代码里，共 10 行）。
 * 若将来把 RepoRoot 提到主构件或 test-jar，应改用它并删除本实现（已在回报中登记）。</p>
 */
final class MappingTestSupport {

    private static final CanonicalContract CONTRACT = CanonicalContractLoader.load(repoSchemaFile());

    private MappingTestSupport() {
    }

    static CanonicalContract contract() {
        return CONTRACT;
    }

    static ObjectMapper mapper() {
        return MappingJson.mapper();
    }

    // ---------- 画像装载 ----------

    static MappingProfileLoad load(String profileJson, String sourceRef) {
        return new MappingProfileLoader(CONTRACT).load(profileJson, sourceRef);
    }

    /** 从测试资源装载画像，要求装载成功。 */
    static MappingProfile profile(String resourceName) {
        MappingProfileLoad load = load(resourceText(resourceName), resourceName);
        assertThat(load.ok()).as("画像应装载成功：%s issues=%s", resourceName, codes(load.issues())).isTrue();
        return load.profile();
    }

    /** 从内联 JSON 装载画像，要求装载成功。 */
    static MappingProfile profileFromText(String profileJson, String sourceRef) {
        MappingProfileLoad load = load(profileJson, sourceRef);
        assertThat(load.ok()).as("画像应装载成功：issues=%s", codes(load.issues())).isTrue();
        return load.profile();
    }

    // ---------- 执行 ----------

    static MappingOutcome execute(String resourceName, String rawJson) {
        return new MappingExecutor(CONTRACT, mapper()).execute(profile(resourceName), rawJson);
    }

    static MappingOutcome execute(String resourceName, JsonNode raw) {
        return new MappingExecutor(CONTRACT, mapper()).execute(profile(resourceName), raw);
    }

    static MappingOutcome executeText(String profileJson, String sourceRef, String rawJson) {
        MappingProfileLoad load = load(profileJson, sourceRef);
        assertThat(load.ok()).as("内联画像应装载成功：issues=%s", codes(load.issues())).isTrue();
        return new MappingExecutor(CONTRACT, mapper()).execute(load.profile(), rawJson);
    }

    // ---------- 断言辅助 ----------

    static List<String> codes(List<MappingIssue> issues) {
        return issues.stream().map(i -> i.reason().name()).collect(Collectors.toList());
    }

    static boolean has(List<MappingIssue> issues, MappingReason reason) {
        return issues.stream().anyMatch(i -> i.reason() == reason);
    }

    static MappingIssue issue(List<MappingIssue> issues, MappingReason reason) {
        return issues.stream().filter(i -> i.reason() == reason).findFirst()
                .orElseGet(() -> {
                    fail("期望存在 %s，实际=%s", reason, codes(issues));
                    return null;
                });
    }

    static String payloadText(MappingOutcome outcome, String field) {
        assertThat(outcome.canonical()).as("非隔离事件应有 canonical").isNotNull();
        JsonNode value = outcome.canonical().path("payload").path(field);
        assertThat(value.isMissingNode()).as("canonical.payload.%s 应存在", field).isFalse();
        return value.isNull() ? null : value.asText();
    }

    static JsonNode json(String text) {
        try {
            return mapper().readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 资源与仓根 ----------

    static String resourceText(String resourceName) {
        String path = "s2-01a/" + resourceName;
        try (InputStream in = MappingTestSupport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试资源不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path repoFile(String repoRelative) {
        return repoRoot().resolve(repoRelative);
    }

    static String repoText(String repoRelative) {
        try {
            return Files.readString(repoFile(repoRelative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoSchemaFile() {
        return repoFile("contract-specs/schemas/canonical-event.v1.schema.json");
    }

    private static Path repoRoot() {
        Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("contract-specs/schemas/canonical-event.v1.schema.json"))) {
                return dir;
            }
        }
        throw new IllegalStateException("找不到仓库根：从 " + start + " 向上未发现 contract-specs/schemas/canonical-event.v1.schema.json");
    }
}
