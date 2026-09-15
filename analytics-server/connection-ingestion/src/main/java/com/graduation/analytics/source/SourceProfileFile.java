package com.graduation.analytics.source;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceRegistryView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「登记源的画像文件怎么定位、怎么读」的唯一所有者（S2-03）。
 *
 * <p><b>为什么把它抽出来</b>：S2-02 的采集侧（{@code SourceMapper}）与 S2-03 的激活侧
 * （{@code MappingActivationService}）都要回答同一个问题——{@code source_registry.profile_path}
 * 是**仓库相对**路径，根目录由 {@code platform.source.profile-root} 决定，越界/缺失/不可读一律
 * fail-closed。两份实现会立刻产生"采集说读得到、激活说读不到"的双所有者问题。
 * 这里只搬运既有语义，**不新增判定**：路径策略仍是 {@link SourcePathPolicy}，
 * 错误码仍是 {@code MAPPING_PROFILE_INVALID}(409)。</p>
 *
 * <p><b>不回显路径值</b>：{@code profile_path} 可能来自库里的历史行，回显等于把部署机目录结构
 * 写进响应与审计（任务书 §6）。只有**已经过策略校验**的仓库相对值才会出现在消息里。</p>
 *
 * <p>{@code purpose} 只影响报文（"拒绝采集" / "拒绝激活"），不影响任何判定分支——
 * 让调用方说清是谁在拒绝，而不是让读日志的人去猜。</p>
 */
public final class SourceProfileFile {

    private SourceProfileFile() {
    }

    /**
     * 定位登记源的画像文件（要求存在且可读）。
     *
     * @param profileRoot 画像根目录（{@code platform.source.profile-root}，绝对化后使用）
     * @param source      登记源视图；{@code profile_path} 必须是仓库相对路径
     * @param purpose     报文后缀，例如 {@code "拒绝采集"} / {@code "拒绝激活"}
     */
    public static Path resolve(Path profileRoot, SourceRegistryView source, String purpose) {
        String sourceCode = source.sourceCode();
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "登记源缺 source_code，无法装载映射画像（source_id=" + source.id() + "）");
        }
        String repoRelative = source.profilePath();
        if (!SourcePathPolicy.isRepoRelative(repoRelative)) {
            // 不回显该值：它可能来自库里的历史行，回显等于把部署机路径写进异常（任务书 §6）
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "profile_path 违反仓库相对路径策略（值已脱敏，未回显），" + purpose + "：sourceCode=" + sourceCode);
        }
        Path file = SourcePathPolicy.resolveUnderRoot(profileRoot, repoRelative);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像文件不存在或不可读（仓库相对路径）：" + repoRelative + "，" + purpose + "：sourceCode=" + sourceCode);
        }
        return file;
    }

    /** 读画像**原始字节**（激活侧比对 sha256 用；哈希必须算在字节上，不做任何再序列化）。 */
    public static byte[] readBytes(Path file, String repoRelative, String sourceCode, String purpose) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像文件读取失败（仓库相对路径）：" + repoRelative + "，" + purpose + "：sourceCode=" + sourceCode);
        }
    }

    /** 读画像文本（UTF-8；装载器与 dry-run 都用同一份字节语义）。 */
    public static String readText(Path file, String repoRelative, String sourceCode, String purpose) {
        return new String(readBytes(file, repoRelative, sourceCode, purpose), StandardCharsets.UTF_8);
    }
}
