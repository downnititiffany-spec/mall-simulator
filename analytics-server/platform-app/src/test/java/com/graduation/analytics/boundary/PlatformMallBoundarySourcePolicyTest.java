package com.graduation.analytics.boundary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.1 §3.4-4 边界守卫（L0，源码级负向证据）：分析平台不得引用商城/生成器 Java 包、商城业务表或 {@code mall.*} 配置键。
 *
 * <p>为什么用源码断言而不是行为断言：这类耦合被删掉后很容易"顺手加回来"（一个兜底分支、一行默认值、
 * 一个为了兼容旧脚本保留的配置键），行为测试仍会全绿。本测试直接扫描平台六个模块的 {@code src/main}，
 * 常驻钉住"平台不是为某个具体商城写死"（V2.1 §3.1 分析平台"明确禁止依赖商城数据库或 Java 包"）。</p>
 *
 * <p>如实登记的边界：本类扫 Java 与 {@code yml/properties}；{@code *.sql} 由第三个用例单独钉住，
 * 因为 {@code platform-app/src/main/resources/db/business/V1__init_mall.sql} 是一份**未被任何 Flyway
 * location 加载**的死文件（加载点只有 {@code classpath:db/meta} 与 {@code classpath:db/metric}），
 * 它的物理清理由看板任务 M1-3 在确认后执行——不在此处静默豁免。</p>
 */
class PlatformMallBoundarySourcePolicyTest {

    /** 平台六个模块（当前工作目录是 analytics-server/platform-app，故平台根为 ..） */
    private static final List<String> MODULES = List.of(
            "platform-common", "connection-ingestion", "warehouse-pipeline",
            "metric-analysis", "ai-decision", "platform-app");

    private static final Path PLATFORM_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** Java 源码禁止痕迹：引用商城/生成器程序、商城库表、mall.* 配置键 */
    private static final Map<String, Pattern> JAVA_FORBIDDEN = new LinkedHashMap<>();

    static {
        JAVA_FORBIDDEN.put("商城 Java 包", Pattern.compile("com\\.graduation\\.mall\\b"));
        JAVA_FORBIDDEN.put("商城业务表名", Pattern.compile("\\bmall_[a-z][a-z0-9_]*"));
        JAVA_FORBIDDEN.put("商城数据库名", Pattern.compile("mall_simulator"));
        JAVA_FORBIDDEN.put("商城/生成器程序类", Pattern.compile("MallSimulatorApplication|GeneratorController"));
        JAVA_FORBIDDEN.put("mall.* 配置键", Pattern.compile("\"mall\\.[a-z]"));
    }

    /** 配置类资源禁止痕迹 */
    private static final Map<String, Pattern> CONFIG_FORBIDDEN = new LinkedHashMap<>();

    static {
        CONFIG_FORBIDDEN.put("mall.* 配置键", Pattern.compile("^\\s*mall\\.[a-z]", Pattern.MULTILINE));
        CONFIG_FORBIDDEN.put("商城数据库名", Pattern.compile("mall_simulator"));
        CONFIG_FORBIDDEN.put("商城 Java 包", Pattern.compile("com\\.graduation\\.mall\\b"));
    }

    /** 被 Flyway 真正加载的迁移目录（平台唯一允许存在的迁移来源） */
    private static final List<String> LOADED_MIGRATION_DIRS = List.of("db/meta", "db/metric");

    @Test
    @DisplayName("平台 Java 源码不出现商城程序、商城库表或 mall.* 配置键")
    void platformJavaHasNoMallCoupling() {
        List<Path> sources = scan("src/main/java", ".java");
        assertThat(sources).as("平台 Java 生产源码必须存在（守卫不可空跑）").hasSizeGreaterThan(20);

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = read(source);
            for (Map.Entry<String, Pattern> rule : JAVA_FORBIDDEN.entrySet()) {
                Matcher matcher = rule.getValue().matcher(text);
                if (matcher.find()) {
                    offenders.add("%s 出现%s：\"%s\"（第 %d 行）".formatted(
                            relative(source), rule.getKey(), matcher.group(), lineOf(text, matcher.start())));
                }
            }
        }
        assertThat(offenders)
                .as("V2.1 §3.4-4：分析平台不得引用商城/生成器 Java 包、商城表或 mall.* 配置键")
                .isEmpty();
    }

    @Test
    @DisplayName("平台配置资源不出现 mall.* 配置键或商城数据库名")
    void platformConfigHasNoMallCoupling() {
        List<Path> configs = new ArrayList<>();
        configs.addAll(scan("src/main/resources", ".yml"));
        configs.addAll(scan("src/main/resources", ".yaml"));
        configs.addAll(scan("src/main/resources", ".properties"));
        assertThat(configs).as("平台配置资源必须存在（守卫不可空跑）").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path config : configs) {
            String text = read(config);
            for (Map.Entry<String, Pattern> rule : CONFIG_FORBIDDEN.entrySet()) {
                Matcher matcher = rule.getValue().matcher(text);
                if (matcher.find()) {
                    offenders.add("%s 出现%s：\"%s\"（第 %d 行）".formatted(
                            relative(config), rule.getKey(), matcher.group().trim(),
                            lineOf(text, matcher.start())));
                }
            }
        }
        assertThat(offenders)
                .as("V2.1 §3.4-4：平台配置不得出现 mall.* 键或商城库名")
                .isEmpty();
    }

    @Test
    @DisplayName("商城表 DDL 只可能存在于未被迁移加载的死文件里（M1-3 待清理，不静默豁免）")
    void mallTableLiteralsLiveOnlyInUnloadedBusinessDir() {
        List<String> offenders = new ArrayList<>();
        int sqlFiles = 0;
        for (Path sql : scan("src/main/resources", ".sql")) {
            sqlFiles++;
            Matcher matcher = JAVA_FORBIDDEN.get("商城业务表名").matcher(read(sql));
            boolean inBusinessDir = relative(sql).replace('\\', '/').contains("/db/business/");
            boolean loaded = LOADED_MIGRATION_DIRS.stream()
                    .anyMatch(dir -> relative(sql).replace('\\', '/').contains("/" + dir + "/"));
            while (matcher.find()) {
                if (loaded) {
                    offenders.add("%s 被 Flyway 加载却引用了商城表 %s".formatted(relative(sql), matcher.group()));
                } else if (!inBusinessDir) {
                    offenders.add("%s 不在 db/business/ 却引用了商城表 %s".formatted(relative(sql), matcher.group()));
                }
            }
        }
        assertThat(sqlFiles).as("平台迁移脚本必须存在（守卫不可空跑）").isGreaterThan(5);
        assertThat(offenders)
                .as("被加载的迁移不得引用商城表；未加载的残留只允许留在 db/business/ 并登记为 M1-3")
                .isEmpty();
    }

    // ---------- 工具 ----------

    private static List<Path> scan(String relativeDir, String suffix) {
        List<Path> found = new ArrayList<>();
        for (String module : MODULES) {
            Path dir = PLATFORM_ROOT.resolve(module).resolve(relativeDir);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .sorted()
                        .forEach(found::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return found;
    }

    private static String relative(Path path) {
        return PLATFORM_ROOT.relativize(path).toString();
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
