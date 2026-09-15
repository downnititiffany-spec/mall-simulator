package com.graduation.analytics.runtime;

import com.graduation.analytics.landing.LandingLayout;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
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
 * S2-04B 的 V22 迁移门禁（{@code runtime_profile.landing_layout}）。
 *
 * <p>门禁要回答的问题只有一句：这份脚本**不可能**写成别样。它是加性迁移——
 * 只加一列、可空、无默认值、不 INSERT、不动任何已有列——而这四件事都必须由测试钉住，
 * 否则"加个默认值"或"顺手改个列"这类编辑在评审里只是一行 diff。</p>
 *
 * <p>另有两条判据来自代码侧的唯一所有者：列宽由 {@link LandingLayout} 的登记名上界推出
 * （不得手抄常数），值域**不进 DDL**（加 CHECK 会让"新增第三种布局"变成必须修改已发布迁移
 * ——那是真决策门，不能让日常开发顺手触发）。</p>
 */
class RuntimeProfileLandingLayoutMigrationScriptTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** 总控为本任务分配的迁移号（V21 之后的第一个空号） */
    private static final int ASSIGNED_VERSION = 22;
    private static final String SCRIPT_NAME = "V22__runtime_profile_landing_layout.sql";
    private static final String TABLE = "runtime_profile";
    private static final String COLUMN = "landing_layout";

    @Test
    @DisplayName("迁移号已分配且不冲突：V22 存在，脚本名与列名一致，db/meta 内号位不重复")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions).as("db/meta 下应有迁移脚本").isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("S2-04B 的迁移号 %d 必须存在；改号/删号必在此处变红", ASSIGNED_VERSION)
                .contains(ASSIGNED_VERSION);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("S2-04B 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("加性形状：只一条 ALTER TABLE runtime_profile ADD COLUMN landing_layout，可空、无 DEFAULT")
    void scriptIsAdditiveSingleColumn() {
        String sql = executableSql();

        assertThat(indexOf(sql, "(?is)alter\\s+table\\s+" + TABLE + "\\s+add\\s+column\\s+"
                + COLUMN + "\\s+varchar\\(\\d+\\)\\s+null"))
                .as("必须是「加一列 + 显式 NULL」这一条语句：%s", sql)
                .isNotNegative();
        assertThat(sql)
                .as("本迁移不得回填/删除/改定义：加性迁移的全部价值就在这里")
                .doesNotContainPattern("(?i)\\bdrop\\b")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .doesNotContainPattern("(?i)\\bdelete\\b")
                .doesNotContainPattern("(?i)\\bupdate\\b")
                .doesNotContainPattern("(?i)\\binsert\\b")
                .doesNotContainPattern("(?i)\\brename\\b")
                .doesNotContainPattern("(?i)\\bmodify\\s+column\\b")
                .doesNotContainPattern("(?i)\\bchange\\s+column\\b")
                .doesNotContainPattern("(?i)\\bnot\\s+null\\b")
                .doesNotContainPattern("(?i)\\bdefault\\b")
                .doesNotContainPattern("(?i)\\bunique\\b")
                .doesNotContainPattern("(?i)\\bforeign\\s+key\\b");
    }

    @Test
    @DisplayName("只碰 runtime_profile 的这一列：不得改别的表、不得顺手加别的列")
    void scriptTouchesOnlyThisTableAndColumn() {
        String sql = executableSql();

        Matcher alter = Pattern.compile("(?is)alter\\s+table\\s+(\\S+)").matcher(sql);
        List<String> targets = new ArrayList<>();
        while (alter.find()) {
            targets.add(alter.group(1).replace("`", ""));
        }
        assertThat(targets).as("全部 ALTER TABLE 只能指向 %s", TABLE)
                .isNotEmpty().containsOnly(TABLE);

        Matcher added = Pattern.compile("(?is)add\\s+column\\s+(\\S+)").matcher(sql);
        List<String> columns = new ArrayList<>();
        while (added.find()) {
            columns.add(added.group(1).replace("`", ""));
        }
        assertThat(columns).as("唯一允许新增的列就是本轮新增列").containsExactly(COLUMN);
    }

    @Test
    @DisplayName("列宽由代码侧唯一所有者给出：DDL 必须用 LandingLayout.COLUMN_WIDTH，且装得下最长登记名")
    void columnWidthIsDerivedFromRegisteredLayoutNames() {
        int width = LandingLayout.COLUMN_WIDTH;
        assertThat(width)
                .as("列宽必须装得下最长登记名（%d）：%s", LandingLayout.longestNameLength(),
                        LandingLayout.values())
                .isGreaterThanOrEqualTo(LandingLayout.longestNameLength());
        assertThat(width).as("上界 64：再宽说明有人想把这列当描述字段用").isLessThanOrEqualTo(64);
        assertThat(executableSql())
                .as("DDL 的宽度必须等于唯一所有者给出的值 %d：手抄别的数字都应在此变红", width)
                .containsIgnoringCase("varchar(" + width + ")")
                .doesNotContainIgnoringCase("varchar(" + (width - 1) + ")")
                .doesNotContainIgnoringCase("varchar(" + (width + 1) + ")");
    }

    @Test
    @DisplayName("值域不进 DDL：不得用 CHECK/ENUM 固化布局取值（否则新增布局＝改已发布迁移）")
    void valueDomainIsNotFrozenInDdl() {
        assertThat(executableSql())
                .as("值域的唯一所有者是 LandingLayout（未登记值 PARAM_INVALID 拒绝），DDL 只负责装字符串")
                .doesNotContainIgnoringCase("check")
                .doesNotContainIgnoringCase("enum(");
    }

    @Test
    @DisplayName("列名与实体字段同名：新列必须真的能被 MyBatis-Plus 读写（否则列加了也读不到）")
    void entityDeclaresTheNewColumn() throws NoSuchFieldException {
        Field field = RuntimeProfile.class.getDeclaredField("landingLayout");
        assertThat(field.getType()).as("布局列是字符串枚举名，不是数值/布尔").isEqualTo(String.class);
    }

    @Test
    @DisplayName("脚本不是空壳：加列语句之后不得跟着任何第二条语句（一条语句的迁移最好审计）")
    void scriptHasExactlyOneStatement() {
        String sql = executableSql();
        long semicolons = sql.chars().filter(ch -> ch == ';').count();
        assertThat(semicolons).as("本迁移应恰好一条语句，实际 %d 条：%s", semicolons, sql).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败（S2-04B 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句 */
    private static String code(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    /**
     * 去掉注释**与单引号字面量**后的 SQL。
     *
     * <p>为什么还要去字面量：列 COMMENT 里写着"值域由 LandingLayout 校验"，
     * 若不去掉，{@code check}/枚举词门禁会把注释文字当成真语句而假红。</p>
     */
    private static String executableSql() {
        return code(script()).replaceAll("'(?:[^']|'')*'", "''");
    }

    private static int indexOf(String sql, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(sql);
        return matcher.find() ? matcher.start() : -1;
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
