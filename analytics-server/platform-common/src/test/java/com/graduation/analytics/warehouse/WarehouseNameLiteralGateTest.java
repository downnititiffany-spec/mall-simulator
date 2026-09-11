package com.graduation.analytics.warehouse;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库名字面量门禁（P1-04 的 DoD 自动化）。
 *
 * <p>数仓库名只能由唯一所有者派生：Java {@code WarehouseNamespace} 与 Scala {@code WarehouseNamespace}
 * （同名镜像，规格 {@code contract-specs/specs/warehouse-namespace.v1.json}）。本测试扫**生产源码**
 * （两个程序的 {@code src/main}、{@code warehouse/ddl}、{@code scripts}），
 * 出现 `dw_ods` 这类裸库名、或 `dw_$layer` / `"dw_" + x` 这类在代码里拼前缀的写法即失败。</p>
 *
 * <p>**不扫测试源码**：测试里出现 `"dw_ods"` 是断言期望值（例如规格向量的 names 字段），
 * 那是「验证」而不是「第二处所有者」。**不扫文档/验收证据**：历史证据必须保持原样。</p>
 *
 * <p>本测试自身是「规格之外的第二道门」：即使有人绕过测试改实现，只要留下字面量就会红。</p>
 */
class WarehouseNameLiteralGateTest {

    /** 允许出现库名字面量的文件（唯一所有者本体，只此两处） */
    private static final Set<String> OWNER_FILES = Set.of(
            "analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java",
            "spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala");

    /** 裸库名：dw_ods / dw_dwd / dw_dim / dw_dws / dw_ads */
    private static final Pattern BARE_LITERAL = Pattern.compile("dw_(?:ods|dwd|dim|dws|ads)\\b");
    /** 在代码里拼前缀：dw_$layer（Scala/PS 插值）或 "dw_" + x（Java/Scala 拼接） */
    private static final Pattern DYNAMIC_PREFIX = Pattern.compile("dw_\\$|\"dw_\"\\s*\\+");

    /** 扫描范围（相对仓根，正斜杠结尾表示目录前缀） */
    private static final List<String> SCOPES = List.of("spark-jobs/src/main/", "warehouse/ddl/", "scripts/");
    private static final Pattern SERVER_MAIN =
            Pattern.compile("analytics-server/[^/]+/src/main/.*");

    /** 需要逐行检查的文本类文件 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "scala", "sql", "yml", "yaml", "properties", "json", "xml", "conf", "ps1", "sh", "txt", "md");

    /** 构建产物与已忽略目录：不参与扫描（static 是前端构建产物，landing 是数据） */
    private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", ".git", "landing", "static");

    @Test
    @DisplayName("生产源码里除唯一所有者外，不得出现库名字面量或前缀拼接")
    void noBareWarehouseNameLiterals() {
        List<Path> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (Path file : collect()) {
            String rel = RepoRoot.path().relativize(file).toString().replace('\\', '/');
            scanned.add(file);
            if (OWNER_FILES.contains(rel)) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取失败: " + rel, e);
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher bare = BARE_LITERAL.matcher(line);
                if (bare.find()) {
                    offenders.add(rel + ":" + (i + 1) + " → " + line.trim());
                    continue;
                }
                if (DYNAMIC_PREFIX.matcher(line).find()) {
                    offenders.add(rel + ":" + (i + 1) + " → " + line.trim());
                }
            }
        }

        // 门禁必须先证明「真的扫到了东西」，否则仓根定位/过滤出错会伪装成通过
        assertThat(scanned).as("扫描到的生产文件数（过少说明范围失效）").hasSizeGreaterThan(60);
        for (String owner : OWNER_FILES) {
            assertThat(scanned).as("唯一所有者必须落在扫描范围内: %s", owner)
                    .contains(RepoRoot.path(owner));
        }
        assertThat(scanned).as("必须扫到两个程序各自的文件")
                .anyMatch(p -> p.toString().replace('\\', '/').contains("/spark-jobs/src/main/"))
                .anyMatch(p -> p.toString().replace('\\', '/').contains("/analytics-server/"));

        assertThat(offenders)
                .as("库名必须只由 WarehouseNamespace 派生（唯一所有者）；发现裸字面量或前缀拼接：%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("扫描范围声明本身可解析（范围/所有者清单不为空）")
    void scopeIsWellFormed() {
        assertThat(SCOPES).isNotEmpty();
        assertThat(OWNER_FILES).hasSize(2);
        assertThat(collect()).as("collect() 至少能找到所有者的文件")
                .contains(RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala"));
    }

    @Test
    @DisplayName("warehouse/ddl 的库名走 ${WAREHOUSE_PREFIX} 变量（不写死、不另造第二套前缀约定）")
    void ddlDerivesDatabaseNamesFromSharedPrefix() {
        Path ddlDir = RepoRoot.path("warehouse/ddl");
        List<Path> files;
        try (var stream = Files.list(ddlDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + ddlDir, e);
        }
        assertThat(files).as("warehouse/ddl 下的 SQL 文件").hasSize(5);

        Set<String> layers = new LinkedHashSet<>();
        Set<String> variables = new LinkedHashSet<>();
        Matcher layerRef = Pattern.compile("\\$\\{WAREHOUSE_PREFIX\\}_(ods|dwd|dim|dws|ads)\\b").matcher("");
        Matcher anyVar = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}").matcher("");

        for (Path file : files) {
            String rel = RepoRoot.path().relativize(file).toString().replace('\\', '/');
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取失败: " + rel, e);
            }
            assertThat(text)
                    .as("%s 必须用 ${WAREHOUSE_PREFIX}_<层> 派生库名（漏传变量时 Hive 原样保留 ${...} → 建库处语法报错，fail-closed）", rel)
                    .contains("${WAREHOUSE_PREFIX}_");
            layerRef.reset(text);
            while (layerRef.find()) {
                layers.add(layerRef.group(1));
            }
            anyVar.reset(text);
            while (anyVar.find()) {
                variables.add(anyVar.group(1));
            }
        }

        assertThat(layers).as("DDL 覆盖的层后缀（五层都要有）")
                .containsExactlyInAnyOrder("ods", "dwd", "dim", "dws", "ads");
        assertThat(variables).as("DDL 里只允许 WAREHOUSE_PREFIX 一个变量：另造第二个前缀名就是第二处所有者")
                .containsExactly("WAREHOUSE_PREFIX");
    }

    // ── 文件收集 ───────────────────────────────────────────────────────────

    private static Set<Path> collect() {
        Set<Path> out = new LinkedHashSet<>();
        Path root = RepoRoot.path();
        walk(root.resolve("spark-jobs"), root, out);
        walk(root.resolve("analytics-server"), root, out);
        walk(root.resolve("warehouse"), root, out);
        walk(root.resolve("scripts"), root, out);
        return out;
    }

    private static void walk(Path start, Path root, Set<Path> out) {
        if (!Files.isDirectory(start)) {
            return;
        }
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(start) && SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (inScope(root.relativize(file)) && isText(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历失败: " + start, e);
        }
    }

    private static boolean inScope(Path relative) {
        String rel = relative.toString().replace('\\', '/');
        for (String scope : SCOPES) {
            if (rel.startsWith(scope)) {
                return true;
            }
        }
        return SERVER_MAIN.matcher(rel).matches();
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
