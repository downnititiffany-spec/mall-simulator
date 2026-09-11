package com.graduation.analytics.source;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-02 迁移脚本门禁（DoD「回滚设计不删除业务数据 / 回填只覆盖 source_id 为空的存量行」的自动化）。
 *
 * <p>只读静态检查：不连库、不执行任何 DDL。真实库结构与数据由
 * {@link SourceRegistryMigrationMySqlIT} 在真 MySQL 上验证（本类保证「脚本不可能删数据」，
 * IT 保证「脚本在真库上确实这样生效」）。</p>
 *
 * <p>之所以把「不删数据」写成门禁而不是人工承诺：迁移脚本是**只能执行一次**的载体，
 * 事后回滚要靠人记得，静态门禁是唯一能在提交前拦住 {@code DROP}/{@code DELETE} 的机制。</p>
 */
class SourceRegistryMigrationScriptTest {

    /** 迁移目录（analytics_meta 的一套；由 {@code MetaFlywayInitializer} 以 classpath:db/meta 加载） */
    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** 总控为本任务分配的迁移号（V15 之后的第一个空号） */
    private static final int ASSIGNED_VERSION = 16;
    private static final String SCRIPT_NAME = "V16__source_registry.sql";

    /** 设计 §4.2 约定的源画像路径形状：仓库相对路径 */
    private static final String SEED_PROFILE_PATH = "analytics-server/source-profiles/mock-mall.v1.json";

    @Test
    @DisplayName("迁移号由总控分配且不冲突：V16 是该目录最高版本，且版本号唯一")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions)
                .as("db/meta 下应有迁移脚本")
                .isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions.stream().max(Comparator.naturalOrder()).orElseThrow())
                .as("P1-02 的迁移号必须是当前最高版本 %d；更高号说明总控已把该号分配给别的任务", ASSIGNED_VERSION)
                .isEqualTo(ASSIGNED_VERSION);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("P1-02 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("迁移是加性变更：不含 DROP / TRUNCATE / DELETE / RENAME / MODIFY / CHANGE")
    void migrationIsAdditiveOnly() {
        String sql = script();

        assertThat(code(sql))
                .as("P1-02 只允许新增：不得出现销毁性语句（回滚若要 DROP 必须另行书面确认后手动执行）")
                .doesNotContainPattern("(?i)\\bdrop\\b")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .doesNotContainPattern("(?i)\\bdelete\\b")
                .doesNotContainPattern("(?i)\\brename\\b")
                .doesNotContainPattern("(?i)\\balter\\s+table\\s+\\S+\\s+(modify|change)\\b");
    }

    @Test
    @DisplayName("迁移不动 hive_database_prefix：库名前缀的启用属于 P1-04 边界")
    void migrationDoesNotTouchWarehousePrefix() {
        assertThat(code(script()))
                .as("P1-02 只登记源身份；激活 hive_database_prefix 会改变现有仓库解析，属 P1-04/P1-06 范围")
                .doesNotContain("hive_database_prefix");
    }

    @Test
    @DisplayName("种子源 mock-mall 幂等插入，且画像路径是受控仓库相对路径")
    void seedSourceIsIdempotentAndUsesRelativeProfilePath() {
        String seed = statement("INSERT");

        assertThat(seed)
                .as("种子源编码必须等于冻结契约里的 source_system 取值 mock-mall")
                .contains("'mock-mall'")
                .as("种子源为当前在建的参考商城：本期唯一实现 FILE，状态 ACTIVE")
                .contains("'FILE'")
                .contains("'ACTIVE'")
                .as("重复执行同一脚本不得插出第二行（幂等守卫）")
                .containsPattern("(?is)not\\s+exists");
        assertThat(seed)
                .as("画像路径指向设计 §4.2 的版本化文件")
                .contains("'" + SEED_PROFILE_PATH + "'");
        assertThat(SEED_PROFILE_PATH)
                .as("画像路径必须是仓库相对路径：不以 / 或盘符开头、不含 ..")
                .doesNotStartWith("/")
                .doesNotContain("..")
                .doesNotContain(":");
    }

    @Test
    @DisplayName("存量回填只覆盖 source_id 为空的 profile，SET 子句不碰版本/状态/时间")
    void backfillOnlyTouchesRowsWithoutSourceId() {
        String update = statement("UPDATE");

        assertThat(update)
                .as("回填目标列是 source_id")
                .contains("source_id")
                .as("只回填空值行（已有 source_id 的行不得被改写）")
                .containsPattern("(?is)source_id\\s+is\\s+null");

        Matcher matcher = Pattern.compile("(?is)\\bset\\b(.*?)\\bwhere\\b").matcher(update);
        assertThat(matcher.find())
                .as("回填语句必须是 UPDATE ... SET ... WHERE ... 形状：%s", update)
                .isTrue();
        String setClause = matcher.group(1);
        assertThat(setClause)
                .as("SET 子句只允许写 source_id（及保持 updated_at 不变的显式自赋值）")
                .contains("source_id")
                .doesNotContain("version")
                .doesNotContain("status")
                .doesNotContain("created_at")
                .doesNotContain("profile_code")
                .doesNotContain("hive_database_prefix");
    }

    @Test
    @DisplayName("runtime_profile.source_id 可空、带索引与外键，兼容存量行")
    void sourceIdColumnIsNullableWithForeignKey() {
        String sql = code(script());

        assertThat(sql)
                .as("存量行先于源登记存在：兼容期必须可空（启动流水线时由服务层要求非空）")
                .containsPattern("(?is)alter\\s+table\\s+runtime_profile\\b[^;]*add\\s+column\\s+source_id\\s+bigint\\s+null");
        assertThat(sql)
                .as("必须有外键指向 source_registry(id)")
                .containsPattern("(?is)foreign\\s+key\\s*\\(\\s*source_id\\s*\\)\\s*references\\s+source_registry\\s*\\(\\s*id\\s*\\)");
        assertThat(sql)
                .as("外键列需要索引（MySQL 不会为 FK 自动建索引）")
                .containsPattern("(?is)(add\\s+(key|index)\\s+\\S*source|key\\s+idx_\\S*source)");
    }

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败（P1-02 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免注释里的"DROP"造成假红 */
    private static String code(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    /**
     * 取第一条以给定关键字**开头**的语句（到分号为止）。
     *
     * <p>必须锚在语句边界（行首或分号之后）：否则 {@code ON UPDATE CURRENT_TIMESTAMP(3)}
     * 里的 UPDATE 会被当成回填语句的开头（首轮 GREEN 实测踩到过这个坑）。</p>
     */
    private static String statement(String keyword) {
        String sql = code(script());
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
