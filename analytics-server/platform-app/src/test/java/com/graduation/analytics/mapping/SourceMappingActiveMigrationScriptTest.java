package com.graduation.analytics.mapping;

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
 * S2-03.1 迁移脚本门禁（V21 激活指针落库）。
 *
 * <p>与 {@link com.graduation.analytics.source.SourceRegistryMigrationScriptTest} 同口径：只读静态检查，
 * 不连库、不执行 DDL。真库生效由 MySqlIT（需显式 {@code -Dp1.it=true} + 副本库）验证。</p>
 *
 * <p>为什么这张表也要门禁：它是**采集侧读"当前生效画像"的唯一来源**，一旦有人把"最新画像兜底"
 * 或 {@code is_current} 列塞进来，就会出现第二个 owner（设计 §7.2 / D-035）。
 * 门禁用一句"只允许 CREATE TABLE source_mapping_active"把这条边界钉在提交前。</p>
 */
class SourceMappingActiveMigrationScriptTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** S2-03.1 为本任务分配的迁移号（V20 之后的第一个空号） */
    private static final int ASSIGNED_VERSION = 21;
    private static final String SCRIPT_NAME = "V21__source_mapping_active.sql";
    private static final String TABLE = "source_mapping_active";

    @Test
    @DisplayName("迁移号已分配且不冲突：V21 存在、目录内不重复")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions).as("db/meta 下应有迁移脚本").isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("S2-03.1 的迁移号 %d 必须存在；改号/删号必在此处变红", ASSIGNED_VERSION)
                .contains(ASSIGNED_VERSION);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("S2-03.1 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("只建一张新表：不含 ALTER / DROP / TRUNCATE / DELETE / RENAME / MODIFY / CHANGE，也不写任何行")
    void migrationIsPurelyAdditiveOnANewTable() {
        String sql = code(script());

        assertThat(sql)
                .as("不得出现销毁性语句（回滚若要 DROP 必须另行书面确认后手动执行）")
                .doesNotContainPattern("(?i)\\bdrop\\b")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .doesNotContainPattern("(?i)\\bdelete\\b")
                .doesNotContainPattern("(?i)\\brename\\b")
                .doesNotContainPattern("(?i)\\balter\\s+table\\s+\\S+\\s+(modify|change)\\b");
        assertThat(sql)
                .as("不得改动任何已有表结构（加性变更只允许 CREATE TABLE）")
                .doesNotContainPattern("(?i)\\balter\\s+table\\b");
        // 快照式门禁：整个脚本**只有一条**语句，且必须是建这张新表。
        // 这样"不得 insert/update 任何行"这类断言不会踩 ON UPDATE CURRENT_TIMESTAMP(3) 里的 UPDATE 字样。
        List<String> statements = Stream.of(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
        assertThat(statements)
                .as("迁移脚本只允许一条语句；激活事实只能由 activate 写入，迁移无权伪造「某个源已激活」")
                .hasSize(1);
        assertThat(statements.get(0))
                .as("唯一的语句必须是 CREATE TABLE %s", TABLE)
                .matches("(?is)^create\\s+table\\s+" + TABLE + "\\b.*");
    }

    @Test
    @DisplayName("一源一行：source_id 是主键（同源替换即覆盖，不产生第二行）")
    void sourceIdIsThePrimaryKey() {
        assertThat(code(script()))
                .as("一个源最多一个激活指针：靠主键而不是靠应用层自觉")
                .containsPattern("(?is)primary\\s+key\\s*\\(\\s*source_id\\s*\\)");
    }

    @Test
    @DisplayName("字段集固定：引用 + 双校验和 + 版本 + 报告 id + 激活时刻/操作者 + 时间戳")
    void columnsAreExactlyTheActivationFacts() {
        String sql = code(script());

        assertThat(sql)
                .as("画像引用必须落库（采集侧要拿它比对「登记画像 == 激活画像」）")
                .containsPattern("(?is)profile_ref\\s+varchar\\s*\\(\\s*255\\s*\\)\\s+not\\s+null");
        assertThat(sql)
                .as("两个校验和都是小写 64 位 hex：不能用 TEXT/VARCHAR，长度固定才有可比性")
                .containsPattern("(?is)profile_checksum\\s+char\\s*\\(\\s*64\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)contract_checksum\\s+char\\s*\\(\\s*64\\s*\\)\\s+not\\s+null");
        assertThat(sql)
                .as("版本、报告 id、激活时刻、操作者都必须非空：激活事实不允许半份")
                .containsPattern("(?is)profile_version\\s+varchar\\s*\\(\\s*32\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)contract_version\\s+varchar\\s*\\(\\s*32\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)report_id\\s+varchar\\s*\\(\\s*64\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)activated_at\\s+datetime\\s*\\(\\s*3\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)activated_by\\s+varchar\\s*\\(\\s*64\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)created_at\\s+datetime\\s*\\(\\s*3\\s*\\)\\s+not\\s+null")
                .containsPattern("(?is)updated_at\\s+datetime\\s*\\(\\s*3\\s*\\)\\s+not\\s+null");
        assertThat(sql)
                .as("外键指向 source_registry(id)：不允许出现没有登记源的激活指针")
                .containsPattern("(?is)foreign\\s+key\\s*\\(\\s*source_id\\s*\\)\\s*references\\s+source_registry\\s*\\(\\s*id\\s*\\)");
    }

    @Test
    @DisplayName("不引入第二个'当前'：没有 is_current / active 标志列，也不改 source_registry")
    void noSecondCurrentFlagIsIntroduced() {
        String sql = code(script());

        assertThat(sql)
                .as("'当前生效'由'这一行存在'表达；再加 is_current 就会出现第二种真相（D-035）")
                .doesNotContainPattern("(?i)\\bis_current\\b")
                .doesNotContainPattern("(?i)\\bcurrent_since\\b")
                .doesNotContainPattern("(?i)\\bis_active\\b");
        assertThat(sql)
                .as("不得在 source_registry 上做任何 DDL（源登记语义属 P1-02，本次不碰）")
                .doesNotContainPattern("(?is)source_registry\\s+add\\b")
                .doesNotContainPattern("(?is)alter\\s+table\\s+source_registry\\b");
    }

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败（S2-03.1 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免注释里的"DROP"造成假红 */
    private static String code(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
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
