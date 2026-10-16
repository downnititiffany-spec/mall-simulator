package com.graduation.analytics.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 映射 Dry-Run 样本解析器（WP-2A-2 / Design V3.0 §7.3 规则 10 / §7.4 / WP-2A-2-FIX 裁决）。
 *
 * <p>职责边界（纯传输安全组件，TRANSPORT-SAFETY ONLY）：
 * <ul>
 *   <li>白名单根目录限制：仅允许引用平台批准的白名单位置 {@code analytics-server/source-profiles/} 与 {@code tests/golden-dataset/events/}；</li>
 *   <li>路径安全防护：严禁任意 URL（http/https/file/ftp 等，报 400）、严禁 {@code ..} 路径遍历（报 400）、严禁白名单之外的路径逃逸（报 400）；</li>
 *   <li>文件存在性与可读性校验：样本文件缺失或不可读时报 400（PARAM_INVALID）；</li>
 *   <li>样本规模限制：样本非空记录数上限 100 行（超限报 400）；</li>
 *   <li>空白行跳过（blank-line skipping）；</li>
 *   <li>【严禁检查行内容】：本解析器不校验、不预解析任何行内容。所有非空行原样（verbatim）透传至 DryRunEngine；
 *       不可解析或非对象行由引擎内部负责隔离并计入 JSON_PARSE_ERROR 与 reasonCounts（WP-2A-1 规则 8 与规则 6 隔离分流）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class MappingSampleResolver {

    public static final int MAX_RECORDS = 100;

    private static final Pattern URL_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[a-zA-Z]:[\\\\/].*");

    private final Path repoRoot;
    private final ObjectMapper objectMapper;

    @Autowired
    public MappingSampleResolver(@Value("${platform.source.profile-root:.}") String profileRoot,
                                 @Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.repoRoot = locateRepoRoot(profileRoot);
    }

    public MappingSampleResolver(String profileRoot) {
        this(profileRoot, null);
    }

    public Path getRepoRoot() {
        return repoRoot;
    }

    /**
     * 校验样本安全边界并加载原始非空行，返回最多 100 条记录（内容解析与隔离交由 DryRunEngine 负责）。
     */
    public List<String> resolveAndLoad(String sampleRef) {
        if (sampleRef == null || sampleRef.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sampleRef 必填");
        }

        String ref = sampleRef.trim();

        // 1. URL 校验拒绝
        if (isUrl(ref)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sampleRef 不支持 URL 形式: " + ref);
        }

        // 2. .. 路径遍历校验拒绝
        if (containsTraversal(ref)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sampleRef 禁止使用 '..' 路径遍历");
        }

        // 3. 白名单根目录
        Path rootProfiles = repoRoot.resolve("analytics-server/source-profiles").normalize().toAbsolutePath();
        Path rootGolden = repoRoot.resolve("tests/golden-dataset/events").normalize().toAbsolutePath();

        Path targetPath = resolvePathInAllowlist(ref, rootProfiles, rootGolden);

        // 4. 文件存在且可读校验
        if (!Files.isRegularFile(targetPath) || !Files.isReadable(targetPath)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "样本文件不存在或不可读: " + ref);
        }

        // 5. 读取非空记录（纯传输安全，不检查行内容）
        List<String> records = parseRecords(targetPath);

        // 6. 条数上限校验（最多 100 条）
        if (records.size() > MAX_RECORDS) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "样本记录数超过 " + MAX_RECORDS + " 条限制（实际: " + records.size() + " 条）");
        }

        return records;
    }

    private Path resolvePathInAllowlist(String ref, Path rootProfiles, Path rootGolden) {
        Path path = Paths.get(ref);
        if (path.isAbsolute()) {
            Path normalized = path.normalize().toAbsolutePath();
            if (normalized.startsWith(rootProfiles) || normalized.startsWith(rootGolden)) {
                return normalized;
            }
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "sampleRef 绝对路径不在平台批准的白名单目录内: " + ref);
        }

        // 相对路径：先尝试相对仓根
        Path candidateFromRepo = repoRoot.resolve(ref).normalize().toAbsolutePath();
        if (candidateFromRepo.startsWith(rootProfiles) || candidateFromRepo.startsWith(rootGolden)) {
            return candidateFromRepo;
        }

        // 尝试相对 rootProfiles
        Path candidateProfiles = rootProfiles.resolve(ref).normalize().toAbsolutePath();
        if (candidateProfiles.startsWith(rootProfiles) && Files.exists(candidateProfiles)) {
            return candidateProfiles;
        }

        // 尝试相对 rootGolden
        Path candidateGolden = rootGolden.resolve(ref).normalize().toAbsolutePath();
        if (candidateGolden.startsWith(rootGolden) && Files.exists(candidateGolden)) {
            return candidateGolden;
        }

        throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                "sampleRef 不在平台批准的白名单目录内: " + ref);
    }

    private List<String> parseRecords(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "样本文件读取失败: " + e.getMessage());
        }

        List<String> records = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            records.add(line);
        }

        return records;
    }

    private static boolean isUrl(String path) {
        if (path.startsWith("file:") || path.startsWith("http:") || path.startsWith("https:") || path.startsWith("ftp:")) {
            return true;
        }
        if (URL_PATTERN.matcher(path).matches()) {
            return true;
        }
        return false;
    }

    private static boolean containsTraversal(String path) {
        if (path.contains("..")) {
            for (String segment : path.replace('\\', '/').split("/")) {
                if ("..".equals(segment)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Path locateRepoRoot(String configuredRoot) {
        return RepoPathLocator.locateRepoRoot(configuredRoot);
    }
}
