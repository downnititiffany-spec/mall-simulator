package com.graduation.analytics.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.graduation.analytics.testsupport.RepoRoot;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1-05 迁移脚本（{@code V17__source_dimension_for_checkpoint_and_batch.sql}）的静态门禁。
 *
 * <p>本迁移与 V16 的形态不同：它**必须**替换 {@code file_checkpoint} 的唯一键
 * （{@code uk_ckpt} → {@code uk_ckpt_source}），因为旧键比新键更严，留着它第二个源就插不进
 * 同一路径的断点行（决策记录 {@code D-037} 裁决 1）。所以"只许加不许改"这条通则不能照抄，
 * 必须换成**逐条允许清单**：本类把"唯一的删除动作就是删 {@code uk_ckpt} 这一个索引、
 * 且没有任何行删除"写成可执行断言——迁移脚本只能执行一次，事后回滚靠人记得，
 * 静态门禁是提交前唯一能拦住误删的机制。</p>
 */
class FileCheckpointSourceMigrationScriptTest {

    /** 迁移目录（analytics_meta 的一套；由 {@code MetaFlywayInitializer} 以 classpath:db/meta 加载） */
    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** 总控为本任务分配的迁移号（V16 之后的第一个空号，D-037「追号与边界」） */
    private static final int ASSIGNED_VERSION = 17;
    private static final String SCRIPT_NAME = "V17__source_dimension_for_checkpoint_and_batch.sql";

    @Test
    @DisplayName("迁移号由总控分配：V17 存在、版本号唯一，且前置 V16 仍在")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("V17 是 P1-05 的迁移号，必须存在")
                .contains(ASSIGNED_VERSION);
        assertThat(versions)
                .as("V17 依赖 V16 建好的 source_registry 与 runtime_profile.source_id 回填")
                .contains(16);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("P1-05 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("唯一的删除动作是删索引 uk_ckpt：无 DROP TABLE/COLUMN、无 TRUNCATE、无 DELETE")
    void onlyAllowedDeletionIsTheLegacyUniqueIndex() {
        String sql = code(script());

        assertThat(sql)
                .as("不得删表或删列（列只在 P1-05 新增）")
                .doesNotContainPattern("(?i)\\bdrop\\s+table\\b")
                .doesNotContainPattern("(?i)\\bdrop\\s+column\\b")
                .as("不得清空表")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .as("不得删行：回填只允许 UPDATE ... WHERE source_id IS NULL")
                .doesNotContainPattern("(?i)\\bdelete\\b");

        Matcher drops = Pattern.compile("(?i)\\bdrop\\b\\s+\\w+\\s+[A-Za-z0-9_]+").matcher(sql);
        List<String> found = new ArrayList<>();
        while (drops.find()) {
            found.add(drops.group().replaceAll("\\s+", " "));
        }
        assertThat(found)
                .as("删除动作必须**恰好**是删这一个旧唯一键；多一个都是越界，需总控改裁决")
                .containsExactly("DROP INDEX uk_ckpt");
    }

    @Test
    @DisplayName("file_checkpoint.source_id：先可空加列 → 按 profile 回填 → 收紧为 NOT NULL")
    void sourceIdIsBackfilledThenTightened() {
        String sql = code(script());

        assertThat(sql)
                .as("第一步必须是**可空**加列：存量行此刻还没有源归属")
                .containsPattern("(?is)alter\\s+table\\s+file_checkpoint\\b[^;]*"
                        + "add\\s+column\\s+source_id\\s+bigint\\s+null");
        assertThat(sql)
                .as("回填以 runtime_profile 上登记的源为准（JOIN runtime_profile）")
                .containsPattern("(?is)update\\s+file_checkpoint\\s+\\w+\\s+join\\s+runtime_profile\\b");
        assertThat(sql)
                .as("回填只碰空值行")
                .containsPattern("(?is)set\\s+c\\.source_id\\s*=\\s*p\\.source_id\\s+where\\s+c\\.source_id\\s+is\\s+null");
        assertThat(sql)
                .as("最后收紧为非空：该列在唯一键里，可空会让唯一性失效；"
                        + "收紧语句本身就是断言（仍有空值则迁移失败中止）")
                .containsPattern("(?is)alter\\s+table\\s+file_checkpoint\\b[^;]*"
                        + "modify\\s+column\\s+source_id\\s+bigint\\s+not\\s+null");
    }

    @Test
    @DisplayName("禁止静默兜底：回填里不得出现 DEFAULT 1 / source_id = 1 之类把来源不明伪装成 mock-mall 的写法")
    void noSilentFallbackToSeedSource() {
        String sql = code(script());

        assertThat(sql)
                .as("file_checkpoint.source_id 不得带默认值（漏写必须报错而不是落成源 1）")
                .doesNotContainPattern("(?is)add\\s+column\\s+source_id\\s+bigint\\s+null\\s+default")
                .doesNotContainPattern("(?is)add\\s+column\\s+source_id\\s+bigint\\s+not\\s+null\\s+default");
        assertThat(Pattern.compile("(?is)set\\s+\\w*\\.?source_id\\s*=\\s*1\\b").matcher(sql).find())
                .as("不得把 source_id 直接写成字面量 1（D-037 裁决 2：那会把「来源不明」伪装成「来自 mock-mall」）")
                .isFalse();
    }

    @Test
    @DisplayName("唯一键替换为 uk_ckpt_source（含 source_id），并补外键与索引")
    void uniqueKeyIsReplacedWithSourceDimension() {
        String sql = code(script());

        assertThat(sql)
                .as("新唯一键必须同时含运行环境、源、路径、文件身份（四列一个都不能少）")
                .containsPattern("(?is)add\\s+unique\\s+key\\s+uk_ckpt_source\\s*\\(\\s*runtime_profile_id\\s*,"
                        + "\\s*source_id\\s*,\\s*file_path\\s*,\\s*file_identity\\s*\\)");
        assertThat(sql)
                .as("必须有外键指向 source_registry(id)：断点不得挂在不存在的源上")
                .containsPattern("(?is)foreign\\s+key\\s*\\(\\s*source_id\\s*\\)\\s*references\\s+source_registry\\s*\\(\\s*id\\s*\\)");
        assertThat(sql)
                .as("外键列需要索引（MySQL 不会为外键自动建索引）")
                .containsPattern("(?is)add\\s+key\\s+idx_file_checkpoint_source\\s*\\(\\s*source_id\\s*\\)");
        assertThat(sql)
                .as("旧键与新键不得并存（并存则第二个源插不进同一路径的断点行，P1-05 目的落空）")
                .containsPattern("(?is)drop\\s+index\\s+uk_ckpt\\b");
    }

    @Test
    @DisplayName("ingestion_batch.source_id 是归因列：允许可空（判据 = 不在唯一键里），且同样回填")
    void batchSourceIdStaysNullable() {
        String sql = code(script());

        assertThat(sql)
                .as("归因列允许可空：NULL 诚实地表示「历史行未标注」，不制造第二套默认值")
                .containsPattern("(?is)alter\\s+table\\s+ingestion_batch\\b[^;]*"
                        + "add\\s+column\\s+source_id\\s+bigint\\s+null");
        assertThat(sql)
                .as("批次表不得把该列收紧为非空（它与 file_checkpoint 的判据不同：不在唯一键里）")
                .doesNotContainPattern("(?is)alter\\s+table\\s+ingestion_batch\\b[^;]*"
                        + "modify\\s+column\\s+source_id\\s+bigint\\s+not\\s+null");
        assertThat(sql)
                .as("批次也要回填（按 profile 上登记的源）")
                .containsPattern("(?is)update\\s+ingestion_batch\\s+\\w+\\s+join\\s+runtime_profile\\b");
    }

    @Test
    @DisplayName("本迁移不碰 manifest 契约字段与既有业务列")
    void migrationDoesNotTouchUnrelatedColumns() {
        String sql = code(script());

        assertThat(sql)
                .as("既有列一个都不许改：source（连接器类型）、file_path、file_identity、next_offset 等")
                .doesNotContainPattern("(?is)modify\\s+column\\s+(?!source_id)\\w+")
                .doesNotContainPattern("(?is)drop\\s+column\\b")
                .as("迁移只做表结构 + 回填，不写 manifest 契约里的字段名")
                .doesNotContain("mappingVersion")
                .doesNotContain("profileVersion")
                .doesNotContain("hive_database_prefix");
    }

    private static String script() {
        try {
            return Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败（P1-05 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免注释里的 "DEFAULT 1"/"DROP" 造成假红 */
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
