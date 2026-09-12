package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-07（V18）迁移脚本门禁：只读静态检查，不连库、不执行任何 DDL。
 *
 * <p>钉住四件事，任何一条被改动都会红：</p>
 * <ol>
 *   <li><b>三步形状</b>：加可空列 → 只回填空值行 → 收紧 NOT NULL（收紧即断言，仍有空值就迁移失败）；</li>
 *   <li><b>无 DEFAULT</b>（D-071）：执行语句里一个字面量默认值都没有 —— 有默认值就会让"忘填前缀"的源
 *       静默落进 {@code dw_*}；</li>
 *   <li><b>列宽来自冻结契约</b>：{@code VARCHAR(n)} 的 n 由 {@code warehouse-namespace.v2.json} 的
 *       {@code rule.prefixPattern} 上界推出，不是手抄的常数；</li>
 *   <li><b>只动 source_registry</b>（D-073）：不碰 {@code runtime_profile}（该列本轮只断读、不删列）。</li>
 * </ol>
 *
 * <p>真库上"确实这样生效"由 {@link SourceRegistryMigrationMySqlIT} 在真 MySQL 副本库上验证；
 * 本类只保证"脚本不可能写成别样"。</p>
 */
class SourceWarehousePrefixMigrationScriptTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** 总控为本任务分配的迁移号（V17 之后的第一个空号，见 RULINGS-20260912） */
    private static final int ASSIGNED_VERSION = 18;
    private static final String SCRIPT_NAME = "V18__source_warehouse_prefix.sql";
    private static final String TABLE = "source_registry";
    private static final String COLUMN = "warehouse_prefix";

    private static final Path SPEC =
            RepoRoot.path("contract-specs/specs/warehouse-namespace.v2.json");

    @Test
    @DisplayName("迁移号由总控分配且不冲突：V18 存在，且 db/meta 内号位不重复")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions).as("db/meta 下应有迁移脚本").isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("P2-07 的迁移号 %d 必须存在；改号/删号必在此处变红", ASSIGNED_VERSION)
                .contains(ASSIGNED_VERSION);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("P2-07 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("三步形状：加可空列 → 回填空值行 → 收紧 NOT NULL")
    void scriptIsAddBackfillTightenInThatOrder() {
        String sql = executableSql();

        int add = indexOf(sql, "(?is)alter\\s+table\\s+" + TABLE
                + "\\s+add\\s+column\\s+" + COLUMN + "\\s+varchar\\(\\d+\\)\\s+null");
        int backfill = indexOf(sql, "(?is)update\\s+" + TABLE + "\\b");
        int tighten = indexOf(sql, "(?is)alter\\s+table\\s+" + TABLE
                + "\\s+modify\\s+column\\s+" + COLUMN + "\\s+varchar\\(\\d+\\)\\s+not\\s+null");

        assertThat(add).as("必须先加**可空**列（存量行此刻还没有源级前缀）：%s", sql).isNotNegative();
        assertThat(backfill).as("必须有回填语句").isNotNegative();
        assertThat(tighten).as("必须收紧为 NOT NULL（该语句本身就是回填完整性的断言）").isNotNegative();
        assertThat(add).as("顺序：先加列").isLessThan(backfill);
        assertThat(backfill).as("顺序：再回填").isLessThan(tighten);
    }

    @Test
    @DisplayName("D-071：执行语句里不得出现 DEFAULT（有默认值 = 忘填前缀会静默落进 dw_*）")
    void scriptNeverDeclaresADefaultValue() {
        assertThat(executableSql())
                .as("列定义不得带 DEFAULT：缺省只能由服务层显式拒绝，不能在 DDL 层静默补上")
                .doesNotContainPattern("(?i)\\bdefault\\b");
    }

    @Test
    @DisplayName("回填只写新列 + updated_at 自赋值，且只命中 warehouse_prefix 为空的行")
    void backfillOnlyTouchesBlankPrefixRows() {
        String update = statement("UPDATE");

        assertThat(update)
                .as("回填目标列是新增列")
                .contains(COLUMN)
                .as("只回填空值行（已有前缀的行不得被改写）")
                .containsPattern("(?is)" + COLUMN + "\\s+is\\s+null");

        Matcher matcher = Pattern.compile("(?is)\\bset\\b(.*?)\\bwhere\\b").matcher(update);
        assertThat(matcher.find())
                .as("回填语句必须是 UPDATE ... SET ... WHERE ... 形状：%s", update)
                .isTrue();
        String setClause = matcher.group(1);
        assertThat(setClause)
                .as("SET 子句只允许写 warehouse_prefix 与 updated_at 自赋值（不冒充 operator 的版本/时间语义）")
                .contains(COLUMN)
                .containsPattern("(?is)updated_at\\s*=\\s*updated_at")
                .doesNotContain("source_code")
                .doesNotContain("status")
                .doesNotContain("profile_version")
                .doesNotContain("created_at")
                .doesNotContain("display_name");
    }

    @Test
    @DisplayName("D-073：只动 source_registry —— 不碰 runtime_profile，也不删旧列")
    void scriptOnlyTouchesSourceRegistry() {
        String sql = executableSql();

        assertThat(sql)
                .as("本轮只断读不删列：V18 不得出现 runtime_profile")
                .doesNotContain("runtime_profile")
                .as("不得删除任何列（删列是破坏性 DDL，另立任务）")
                .doesNotContainPattern("(?i)\\bdrop\\b")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .doesNotContainPattern("(?i)\\bdelete\\b")
                .doesNotContainPattern("(?i)\\brename\\b")
                .as("只允许改列宽/可空性之外的既有列定义：不得出现 CHANGE")
                .doesNotContainPattern("(?i)\\bchange\\s+column\\b");

        Matcher alter = Pattern.compile("(?is)alter\\s+table\\s+(\\S+)").matcher(sql);
        List<String> targets = new ArrayList<>();
        while (alter.find()) {
            targets.add(alter.group(1));
        }
        assertThat(targets).as("全部 ALTER TABLE 只能指向 %s", TABLE).isNotEmpty().containsOnly(TABLE);

        Matcher modify = Pattern.compile("(?is)modify\\s+column\\s+(\\S+)").matcher(sql);
        List<String> modified = new ArrayList<>();
        while (modify.find()) {
            modified.add(modify.group(1));
        }
        assertThat(modified).as("唯一允许改定义的列就是本轮新增列").containsOnly(COLUMN);
    }

    @Test
    @DisplayName("列宽由冻结契约推出：VARCHAR(n) 的 n = 1 + prefixPattern 上界（v2）")
    void columnWidthIsDerivedFromFrozenContract() throws IOException {
        JsonNode spec = new ObjectMapper().readTree(Files.readString(SPEC, StandardCharsets.UTF_8));

        assertThat(spec.path("status").asText())
                .as("本门禁只对已冻结的 v2 契约成立：%s", SPEC)
                .isEqualTo("FROZEN-2026-09-12");
        String pattern = spec.path("rule").path("prefixPattern").asText();
        Matcher bound = Pattern.compile("\\{0,(\\d+)\\}").matcher(pattern);
        assertThat(bound.find()).as("契约必须给出长度上界：%s", pattern).isTrue();
        int maxLength = 1 + Integer.parseInt(bound.group(1));

        assertThat(executableSql())
                .as("列宽必须等于契约上界 %d（契约：%s）", maxLength, pattern)
                .containsIgnoringCase("varchar(" + maxLength + ")")
                .doesNotContainIgnoringCase("varchar(" + (maxLength - 1) + ")")
                .doesNotContainIgnoringCase("varchar(" + (maxLength + 1) + ")");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败（P2-07 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句 */
    private static String code(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    /**
     * 去掉注释**与单引号字面量**后的 SQL。
     *
     * <p>为什么还要去字面量：列 COMMENT 里写了"不设 DEFAULT"这句话，若不去掉，{@code DEFAULT}
     * 门禁会把注释文字当成真语句而假红。去掉之后的断言才是"执行语句里没有 DEFAULT"。</p>
     */
    private static String executableSql() {
        return code(script()).replaceAll("'(?:[^']|'')*'", "''");
    }

    private static int indexOf(String sql, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(sql);
        return matcher.find() ? matcher.start() : -1;
    }

    private static String statement(String keyword) {
        String sql = executableSql();
        Matcher matcher = Pattern.compile("(?ims)(?:^|;)\\s*" + keyword + "\\b.*?;").matcher(sql);
        assertThat(matcher.find())
                .as("迁移脚本里应有以 %s 开头的语句", keyword)
                .isTrue();
        return matcher.group();
    }

    private static List<Integer> scriptVersions() {
        try (Stream<Path> files = Files.list(META_DIR)) {
            List<Integer> versions = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(name -> {
                        Matcher matcher = Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(name);
                        if (matcher.matches()) {
                            versions.add(Integer.valueOf(matcher.group(1)));
                        }
                    });
            return versions;
        } catch (IOException e) {
            throw new UncheckedIOException("列举 " + META_DIR + " 失败", e);
        }
    }
}
