package com.graduation.analytics.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 测试用的仓库根定位（唯一一处「向上找仓根」逻辑）。
 *
 * <p>契约类测试（如 {@code WarehouseNamespaceContractTest}、库名字面量门禁）要读仓库根下的
 * {@code contract-specs/}、{@code spark-jobs/} 等目录，而 Maven 的 {@code user.dir}
 * 既可能是 {@code analytics-server}（整反应堆）也可能是模块目录，因此统一向上回溯。</p>
 *
 * <p>找不到就**直接失败**（不允许退化成本地相对路径而让门禁变成空跑）。</p>
 */
public final class RepoRoot {

    private static final Path ROOT = locate();

    private RepoRoot() {
    }

    public static Path path() {
        return ROOT;
    }

    public static Path path(String relative) {
        return ROOT.resolve(relative);
    }

    private static Path locate() {
        Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("contract-specs")) && Files.isDirectory(dir.resolve("spark-jobs"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "找不到仓库根：从 " + start + " 向上未发现同时含 contract-specs/ 与 spark-jobs/ 的目录");
    }
}
