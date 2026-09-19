package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.mapping.MappingProfile;
import com.graduation.analytics.mapping.MappingProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 源画像校验（P1-03）。口径 = D-035 + 任务书 §3.12：**文件存在 + 可解析为 JSON 对象 +
 * 文件内 {@code sourceCode}/{@code profileVersion} 与登记行一致 + 顶层必备键齐全**。
 *
 * <p>顶层必备键按画像语法分派（D-029）：{@code fieldMappings} 是对象 ⇒ V2_STRICT
 * （必备键 = {@link MappingProfileLoader#V2_STRICT_TOP_LEVEL_KEYS}，与摄取路径的 Loader 同一权威清单）；
 * 否则 v1 扁平口径（设计 §4.2 的 9 个键，顺序逐字）。分派判据与 {@code MappingProfileLoader.load}
 * 的形状判定逐字相同，保证生命周期门与摄取路径对同一份画像永远判定同一种语法。</p>
 *
 * <p>不做什么（如实登记的边界）：本类**不是**完整画像 Schema 校验器——
 * 每个键内部的子结构（{@code eventTypeMapping} 的枚举闭集、{@code identityPolicy} 的字段形状等）
 * 归 P3-01。本类只钉 P1-03 能钉的最小口径，键名不自创、不缩写。</p>
 *
 * <p>根目录由配置键 {@code platform.source.profile-root} 决定（默认 {@code .}，即进程工作目录＝仓库根）。
 * 平台启动约定要求工作目录为仓库根，因此默认值下 {@code analytics-server/source-profiles/x.v1.json}
 * 能直接解析。解析结果只在本类内部使用，**从不回显绝对路径**。</p>
 */
@Slf4j
@Component
public class SourceProfileValidator {

    /**
     * 设计 §4.2 列出的顶层必备键（9 个，逐字，含顺序）。改动此清单等于改契约，必须同步设计文档。
     */
    public static final List<String> REQUIRED_TOP_LEVEL_KEYS = List.of(
            "profileVersion",
            "sourceCode",
            "canonical",
            "eventTypeMapping",
            "fieldMapping",
            "enumSemantics",
            "identityPolicy",
            "timePolicy",
            "quarantinePolicy");

    /**
     * v2（V2_STRICT）画像的顶层必备键 = {@link MappingProfileLoader#V2_STRICT_TOP_LEVEL_KEYS}（唯一权威清单，
     * D-029：不另立第二份 v2 键清单）。Set 无序，这里排序成稳定列表，保证 missingKeys / 明细输出可复现。
     */
    public static final List<String> V2_STRICT_REQUIRED_KEYS = MappingProfileLoader.V2_STRICT_TOP_LEVEL_KEYS
            .stream().sorted(Comparator.naturalOrder()).toList();

    private final Path profileRoot;
    private final ObjectMapper objectMapper;

    public SourceProfileValidator(@Value("${platform.source.profile-root:.}") String profileRoot,
                                  ObjectMapper objectMapper) {
        this.profileRoot = Path.of(profileRoot).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /**
     * 校验一个登记行指向的画像文件。
     *
     * @param repoRelativePath 登记表里的 {@code profile_path}（仓库相对）
     * @param expectedSourceCode 登记表里的 {@code source_code}
     * @param expectedProfileVersion 登记表里的 {@code profile_version}（为 null 视为不通过：没有声明就无法比对）
     */
    public ProfileCheck check(String repoRelativePath, String expectedSourceCode, String expectedProfileVersion) {
        if (!SourcePathPolicy.isRepoRelative(repoRelativePath)) {
            // 不回显该值：它可能来自库里的历史行，回显等于把部署机路径写进响应（任务书 §6）
            return new ProfileCheck(false, false, false, false, List.of(), null, null, List.of(), null,
                    "profile_path 违反仓库相对路径策略（值已脱敏，未回显）");
        }

        Path file = SourcePathPolicy.resolveUnderRoot(profileRoot, repoRelativePath);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return new ProfileCheck(false, false, false, false, List.of(), null, null, List.of(), null,
                    "画像文件不存在或不可读（仓库相对路径）：" + repoRelativePath);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("源画像解析失败 path={}: {}", repoRelativePath, e.getMessage());
            return new ProfileCheck(true, false, false, false, List.of(), null, null, List.of(), null,
                    "画像文件不是合法 JSON（解析失败）：" + repoRelativePath);
        }
        if (root == null || !root.isObject()) {
            return new ProfileCheck(true, false, false, false, List.of(), null, null, List.of(), null,
                    "画像文件顶层必须是 JSON 对象：" + repoRelativePath);
        }

        // 画像语法分派与 Loader 同一结构判据（D-029）：fieldMappings 是对象 ⇒ V2_STRICT，否则 v1 §4.2 扁平口径。
        // 依据 MappingProfileLoader.load 的形状判定，保证生命周期门与摄取路径对同一份画像永远判定同一种语法。
        boolean v2Shape = root.path("fieldMappings").isObject();
        String syntaxName = v2Shape
                ? MappingProfile.ProfileSyntax.V2_STRICT.name()
                : MappingProfile.ProfileSyntax.V1_COMPATIBILITY.name();
        List<String> requiredKeys = v2Shape ? V2_STRICT_REQUIRED_KEYS : REQUIRED_TOP_LEVEL_KEYS;

        List<String> missing = new ArrayList<>();
        for (String key : requiredKeys) {
            if (!root.has(key)) {
                missing.add(key);
            }
        }

        String actualCode = text(root, "sourceCode");
        String actualVersion = text(root, "profileVersion");
        boolean codeMatches = expectedSourceCode != null && expectedSourceCode.equals(actualCode);
        boolean versionMatches = expectedProfileVersion != null && expectedProfileVersion.equals(actualVersion);

        String syntaxLabel = v2Shape ? "V2_STRICT" : "设计 §4.2";
        // v1 缺键文案逐字保持历史口径（"画像缺设计 §4.2 …"，无空格），避免下游逐字断言漂移
        String missingPrefix = v2Shape ? "画像缺 V2_STRICT 顶层必备键：" : "画像缺设计 §4.2 顶层必备键：";
        String detail;
        if (!missing.isEmpty()) {
            detail = missingPrefix + String.join(",", missing);
        } else if (!codeMatches) {
            detail = "画像 sourceCode 与登记不一致：登记=" + expectedSourceCode + " 文件=" + actualCode;
        } else if (!versionMatches) {
            detail = "画像 profileVersion 与登记不一致：登记=" + expectedProfileVersion + " 文件=" + actualVersion;
        } else {
            detail = "通过：" + syntaxLabel + " 的 " + requiredKeys.size() + " 个顶层必备键齐全，"
                    + "sourceCode/profileVersion 与登记一致";
        }
        return new ProfileCheck(true, true, codeMatches, versionMatches, List.copyOf(missing),
                actualCode, actualVersion, List.copyOf(requiredKeys), syntaxName, detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 校验结果。
     *
     * @param exists                文件存在且可读
     * @param jsonObject            可解析且顶层是 JSON 对象
     * @param sourceCodeMatches     文件内 {@code sourceCode} 与登记一致（前置不成立时为 false）
     * @param profileVersionMatches 文件内 {@code profileVersion} 与登记一致（前置不成立时为 false）
     * @param missingKeys           缺失的顶层必备键（键存在性判据是键名，值为 null 也算存在）
     * @param actualSourceCode      文件内实际的 {@code sourceCode}（前置不成立时为 null）
     * @param actualProfileVersion  文件内实际的 {@code profileVersion}（前置不成立时为 null）
     * @param requiredTopLevelKeys  本份画像语法对应的顶层必备键清单（前置不成立、语法未知时为空表）
     * @param syntaxName            画像语法名（{@code V2_STRICT} / {@code V1_COMPATIBILITY}；前置不成立时为 null）
     * @param detail                本条校验的总结说明，只含仓库相对路径
     */
    public record ProfileCheck(boolean exists, boolean jsonObject, boolean sourceCodeMatches,
                               boolean profileVersionMatches, List<String> missingKeys,
                               String actualSourceCode, String actualProfileVersion,
                               List<String> requiredTopLevelKeys, String syntaxName, String detail) {

        /** 是否可激活：全部条件成立 */
        public boolean ok() {
            return exists && jsonObject && sourceCodeMatches && profileVersionMatches && missingKeys.isEmpty();
        }
    }
}
