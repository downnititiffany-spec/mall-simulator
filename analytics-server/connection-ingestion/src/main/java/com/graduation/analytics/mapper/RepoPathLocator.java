package com.graduation.analytics.mapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 仓库根目录定位与发现工具（单一所有权，WP-2A-2-FIX2 裁决）。
 *
 * <p>供 {@link MappingSampleResolver} 与 {@link com.graduation.analytics.source.SourceMappingDryRunService}
 * 共享同一份向上回溯定位逻辑，避免重复编写 walk-up 逻辑导致测试与生产环境路径解析分歧。</p>
 */
public final class RepoPathLocator {

    private RepoPathLocator() {
    }

    /**
     * 基于配置的根路径（默认为 "."）向上回溯定位仓库根目录。
     *
     * @param configuredRoot 配置的起始目录
     * @return 定位到的仓库根绝对路径
     */
    public static Path locateRepoRoot(String configuredRoot) {
        Path start = Paths.get(configuredRoot != null && !configuredRoot.isBlank() ? configuredRoot : ".")
                .toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("tests/golden-dataset"))
                    && Files.isDirectory(dir.resolve("analytics-server"))) {
                return dir;
            }
            if (Files.isDirectory(dir.resolve("contract-specs"))
                    && Files.isDirectory(dir.resolve("spark-jobs"))) {
                return dir;
            }
        }
        return start;
    }
}
