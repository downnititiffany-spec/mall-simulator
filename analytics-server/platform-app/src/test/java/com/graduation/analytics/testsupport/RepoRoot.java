package com.graduation.analytics.testsupport;

import com.graduation.analytics.mapper.RepoPathLocator;

import java.nio.file.Path;

/**
 * 测试环境仓库根目录定位辅助类，委托给统一的 {@link RepoPathLocator}。
 */
public final class RepoRoot {

    private RepoRoot() {
    }

    public static Path path() {
        return RepoPathLocator.locateRepoRoot(".");
    }

    public static Path path(String relativePath) {
        return path().resolve(relativePath);
    }
}
