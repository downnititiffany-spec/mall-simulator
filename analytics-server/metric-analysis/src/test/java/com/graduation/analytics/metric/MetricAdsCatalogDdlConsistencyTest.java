package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADS 镜像表**结构双所有者**反熵守卫：{@code db/metric/*.sql} 迁移建出的列 与
 * {@link MetricAdsCatalog}（Java 侧表/列白名单）必须逐表逐列一致。
 *
 * <p>为什么需要它：发布链上 {@code MetricAdsWriter} 按白名单列名拼 INSERT、
 * {@code AdsExportReader}/{@code MetricPublishValidator} 按白名单与导出清单做集合比对，
 * 三者都只看**白名单**；而"这一列在 MySQL 里到底存不存在"只有真库才知道。
 * 于是"白名单加了列、迁移没加"这类漂移在编译期与普通单测里完全不可见，
 * 只会在真实发布时以 {@code Unknown column} 失败（历史缺陷 DEF-10 同一类：结构所有者不同步）。
 * 本测试不需要真库，直接解析迁移 SQL 文本做比对，把该类漂移挡在提交前。</p>
 *
 * <p>解析口径：把 {@code db/metric} 下全部迁移按**版本号升序**拼接后，
 * 先取 {@code CREATE TABLE ... ENGINE} 的括号内列名，再按出现顺序应用
 * {@code ALTER TABLE ... ADD COLUMN ...}。列序参与比对（白名单与建表/加列顺序一致，
 * 便于人工对照差异），{@code snapshot_id}/{@code dt} 为所有表共有的技术列，不在白名单内，比对时排除。</p>
 */
class MetricAdsCatalogDdlConsistencyTest {

    private static final Path MIGRATION_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/metric");

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?\\s*\\((.*?)\\)\\s*ENGINE",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?\\s+(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD\\s+COLUMN\\s+`?([a-z_][a-z0-9_]*)`?", Pattern.CASE_INSENSITIVE);

    private static final Pattern COLUMN_NAME = Pattern.compile("^`?([a-z_][a-z0-9_]*)`?");

    /** 约束行不是列定义（与 {@code AiSqlDriftTest} 同一套判定） */
    private static final List<String> CONSTRAINT_PREFIXES =
            List.of("PRIMARY KEY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN KEY");

    /** 所有表共有的技术列：白名单里不登记，比对时两侧同时排除 */
    private static final Set<String> TECHNICAL_COLUMNS = Set.of("snapshot_id", "dt");

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("8 张 ADS 镜像：迁移建出的业务列与 MetricAdsCatalog 白名单逐表逐列一致")
    void everyCatalogTableHasExactlyTheMigratedColumns() throws IOException {
        Map<String, List<String>> ddl = parseTables(readMigrations());

        assertEquals(MetricAdsCatalog.ALL.size(), ddl.keySet().stream().filter(n -> n.endsWith("_m")).count(),
                "迁移里的 ADS 镜像表（*_m）数量与白名单不一致，实测迁移表: " + ddl.keySet());

        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            List<String> migrated = ddl.get(spec.name());
            assertNotNull(migrated, spec.name() + " 在 db/metric 的迁移里不存在（白名单有表、库结构无表）");
            assertEquals(spec.columns(), migrated,
                    spec.name() + " 白名单列与迁移列不一致（白名单加了列但迁移没加 ⇒ 真实发布会 Unknown column）");
        }
    }

    @Test
    @DisplayName("反向：迁移里建的 ADS 镜像表（*_m）都必须在白名单里（防新增迁移漏登记）")
    void everyMigratedMirrorTableIsInTheCatalog() throws IOException {
        Set<String> catalogNames = MetricAdsCatalog.ALL.stream()
                .map(MetricAdsCatalog::name).collect(Collectors.toCollection(LinkedHashSet::new));

        for (String table : parseTables(readMigrations()).keySet()) {
            if (table.endsWith("_m")) {
                assertTrue(catalogNames.contains(table),
                        "迁移建了 " + table + "，但 MetricAdsCatalog 未登记（写不进也读不出，属幽灵表）");
            }
        }
    }

    @Test
    @DisplayName("解析器正向对照：漏列/多列/新增迁移加列都能被抓出（证明比对非空洞）")
    void parserDetectsMissingAndExtraColumns() {
        String create = "CREATE TABLE ads_demo_m (\n"
                + "    snapshot_id VARCHAR(64) NOT NULL,\n"
                + "    dt          VARCHAR(16) NOT NULL,\n"
                + "    `r`         INT         NOT NULL DEFAULT 0 COMMENT 'R 分',\n"
                + "    period_end  VARCHAR(32) NOT NULL DEFAULT '',\n"
                + "    PRIMARY KEY (snapshot_id, dt)\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        assertEquals(List.of("r", "period_end"), parseTables(create).get("ads_demo_m"),
                "建表解析应排除 snapshot_id/dt 与约束行，并去掉反引号");

        String withAlter = create + "\nALTER TABLE ads_demo_m\n"
                + "    ADD COLUMN r_days INT NOT NULL DEFAULT 0 COMMENT 'R 原值',\n"
                + "    ADD COLUMN m_amount DECIMAL(18,2) NOT NULL DEFAULT 0;";
        assertEquals(List.of("r", "period_end", "r_days", "m_amount"), parseTables(withAlter).get("ads_demo_m"),
                "ALTER ... ADD COLUMN 应按出现顺序追加");

        // 反向对照：白名单多一列（迁移没加）必须被判不等 —— 否则上面的比对是空洞的
        List<String> whitelistWithExtra = List.of("r", "period_end", "r_days", "m_amount", "f_count");
        assertFalse(whitelistWithExtra.equals(parseTables(withAlter).get("ads_demo_m")),
                "白名单多出一列时比对必须失败");
    }

    // ------------------------------------------------------------------ 解析

    /** 迁移目录下的全部 SQL 文本（按版本号升序拼接；缺目录由 RepoRoot 直接失败，不退化为跳过） */
    private static String readMigrations() throws IOException {
        assertTrue(Files.isDirectory(MIGRATION_DIR), "迁移目录不存在: " + MIGRATION_DIR);

        List<Path> files = new ArrayList<>();
        try (var list = Files.list(MIGRATION_DIR)) {
            list.filter(p -> p.getFileName().toString().endsWith(".sql")).forEach(files::add);
        }
        files.sort(Comparator.comparingInt(MetricAdsCatalogDdlConsistencyTest::versionOf)
                .thenComparing(p -> p.getFileName().toString()));

        StringBuilder sql = new StringBuilder();
        for (Path f : files) {
            sql.append("-- ==== ").append(f.getFileName()).append(" ====\n")
                    .append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
        }
        return sql.toString();
    }

    /** 文件名前缀 V<n>__ 的版本号；不合规文件排在最后（其存在会被 {@link #everyCatalogTableHasExactlyTheMigratedColumns} 之类用例暴露） */
    private static int versionOf(Path p) {
        Matcher m = MIGRATION_FILE.matcher(p.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** 解析建表与加列语句，得到 表 → 业务列（有序，不含 snapshot_id/dt） */
    private static Map<String, List<String>> parseTables(String sql) {
        Map<String, List<String>> tables = new LinkedHashMap<>();

        Matcher create = CREATE_TABLE.matcher(sql);
        while (create.find()) {
            List<String> cols = new ArrayList<>();
            for (String rawLine : create.group(2).split("\n")) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("--") || isConstraint(line)) {
                    continue;
                }
                Matcher col = COLUMN_NAME.matcher(line);
                if (col.find() && !TECHNICAL_COLUMNS.contains(col.group(1).toLowerCase())) {
                    cols.add(col.group(1).toLowerCase());
                }
            }
            tables.put(create.group(1).toLowerCase(), cols);
        }

        Matcher alter = ALTER_TABLE.matcher(sql);
        while (alter.find()) {
            List<String> cols = tables.get(alter.group(1).toLowerCase());
            if (cols == null) {
                continue;
            }
            Matcher add = ADD_COLUMN.matcher(alter.group(2));
            while (add.find()) {
                String name = add.group(1).toLowerCase();
                if (!TECHNICAL_COLUMNS.contains(name)) {
                    cols.add(name);
                }
            }
        }
        return tables;
    }

    private static boolean isConstraint(String line) {
        String upper = line.toUpperCase();
        return CONSTRAINT_PREFIXES.stream().anyMatch(upper::startsWith);
    }
}
