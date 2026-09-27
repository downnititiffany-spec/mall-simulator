package com.graduation.analytics.migration;

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
 * G31-09 迁移脚本（{@code V32__file_checkpoint_identity_width.sql}）的静态门禁：
 * **只读静态检查，不连库、不执行任何 DDL**。
 *
 * <p>D-041 的缺陷是纯存储宽度问题：{@code file_checkpoint.file_identity VARCHAR(64)}
 * 装不下 HDFS 身份（≈94 字符），checkpoint INSERT 抛 MysqlDataTruncation，断点行落不下来，
 * 同一 HDFS 文件重试就全量重读。修复 = 一条加宽语句，**不允许**夹带任何其他结构动作：
 * 幂等判定逻辑在 {@code LocalFileIngestor}（单测已钉），这里若出现 DROP/ADD KEY、加列、
 * 删行等，都属于越界改动，必须在提交前被拦住。</p>
 *
 * <p><b>本类能证明什么、不能证明什么</b>：只证明「脚本不可能写成别样」。
 * 「在真库上确实生效且 94 字符身份原样落库」由 isolated 档的
 * {@code AnalyticsIsolationFlywayIT}（3307 per-run 库）证明，本类不证明。</p>
 */
class FileCheckpointIdentityWidthMigrationScriptTest {

    /** 迁移目录（analytics_meta 的一套；由 {@code MetaFlywayInitializer} 以 classpath:db/meta 加载） */
    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    /** 本批次迁移号（V31 之后的第一个空号，D-044④ 短批次序列） */
    private static final int ASSIGNED_VERSION = 32;
    private static final String SCRIPT_NAME = "V32__file_checkpoint_identity_width.sql";

    /** 已发布迁移的零改动锚点：加宽只允许追加 V32，不允许回改历史脚本。 */
    private static final String V8_SCRIPT = "V8__platform_ingestion_r3.sql";
    private static final String V17_SCRIPT = "V17__source_dimension_for_checkpoint_and_batch.sql";

    @Test
    @DisplayName("迁移号分配：V32 存在、版本号唯一，且前置 V31 仍在")
    void migrationVersionIsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("V32 是 G31-09 的迁移号，必须存在")
                .contains(ASSIGNED_VERSION);
        assertThat(versions)
                .as("V32 是 V31（decision_window_snapshot_lineage）之后的下一个号")
                .contains(31);
        assertThat(Files.isRegularFile(META_DIR.resolve(SCRIPT_NAME)))
                .as("G31-09 迁移脚本 %s 必须存在", SCRIPT_NAME)
                .isTrue();
    }

    @Test
    @DisplayName("整脚本只有一条 ALTER TABLE：对 file_identity 单列加宽，不得夹带其他动作")
    void scriptIsExactlyOneColumnWidenStatement() {
        String sql = code(script());

        Matcher alters = Pattern.compile("(?i)\\balter\\s+table\\s+([A-Za-z0-9_]+)").matcher(sql);
        List<String> tables = new ArrayList<>();
        while (alters.find()) {
            tables.add(alters.group(1));
        }
        assertThat(tables)
                .as("本迁移只允许对 file_checkpoint 这一张表动作")
                .containsExactly("file_checkpoint");

        assertThat(sql)
                .as("只允许 MODIFY COLUMN file_identity 一处列改动，其他列一个都不许碰")
                .containsPattern("(?is)modify\\s+column\\s+file_identity\\s+varchar\\(255\\)")
                .doesNotContainPattern("(?is)modify\\s+column\\s+(?!file_identity)\\w+")
                .as("不得加列/删列/删表/清表/删行")
                .doesNotContainPattern("(?i)\\badd\\s+column\\b")
                .doesNotContainPattern("(?i)\\bdrop\\s+(table|column)\\b")
                .doesNotContainPattern("(?i)\\btruncate\\b")
                .doesNotContainPattern("(?i)\\bdelete\\b")
                .as("不得 INSERT/UPDATE 业务行：宽度修复不是数据修复")
                .doesNotContainPattern("(?i)\\binsert\\s+into\\b")
                .doesNotContainPattern("(?i)\\bupdate\\b");
    }

    @Test
    @DisplayName("键与外键原样保留：不 DROP/ADD 索引或约束（MODIFY 自动维护所属唯一键）")
    void indexesAndConstraintsAreUntouched() {
        String sql = code(script());

        assertThat(sql)
                .as("唯一键 uk_ckpt_source / 索引 idx_file_checkpoint_source / 外键 fk_file_checkpoint_source"
                        + " 必须由 MODIFY 自动维护，脚本不得重建（重建=越界动作清单膨胀）")
                .doesNotContainPattern("(?i)\\bdrop\\s+index\\b")
                .doesNotContainPattern("(?i)\\bdrop\\s+foreign\\s+key\\b")
                .doesNotContainPattern("(?i)\\badd\\s+(unique\\s+)?key\\b")
                .doesNotContainPattern("(?i)\\badd\\s+constraint\\b");
    }

    @Test
    @DisplayName("列定义语义不变：仍 NOT NULL DEFAULT ''，注释保留身份语义说明")
    void columnSemanticsArePreserved() {
        String sql = code(script());

        assertThat(sql)
                .as("加宽不得顺手改空值语义：LOCAL 存量行依赖 NOT NULL DEFAULT ''")
                .containsPattern("(?is)modify\\s+column\\s+file_identity\\s+varchar\\(255\\)\\s+not\\s+null\\s+default\\s*''")
                .as("列注释必须说明两种身份来源，防止后来者把宽度当成可再压缩的余量")
                .containsPattern("(?is)comment\\s+'文件身份（LOCAL=创建时间戳毫秒；HDFS=");
    }

    @Test
    @DisplayName("已发布迁移零改动：V8 仍为 VARCHAR(64)、V17 仍持有 uk_ckpt_source（修复只允许追加 V32）")
    void publishedMigrationsRemainUntouched() {
        String v8 = code(script(V8_SCRIPT));
        String v17 = code(script(V17_SCRIPT));

        assertThat(v8)
                .as("V8 是已发布脚本：file_identity 的原始宽度定义必须原样保留（回改历史 = checksum 漂移）")
                .containsPattern("(?is)add\\s+column\\s+file_identity\\s+varchar\\(64\\)")
                .doesNotContainPattern("(?is)varchar\\(255\\)");
        assertThat(v17)
                .as("V17 是已发布脚本：uk_ckpt_source 四列定义必须原样保留")
                .containsPattern("(?is)add\\s+unique\\s+key\\s+uk_ckpt_source\\s*\\(\\s*runtime_profile_id\\s*,"
                        + "\\s*source_id\\s*,\\s*file_path\\s*,\\s*file_identity\\s*\\)");
    }

    private static String script() {
        return script(SCRIPT_NAME);
    }

    private static String script(String name) {
        try {
            return Files.readString(META_DIR.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + name + " 失败（迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免头注释里的 "VARCHAR(64)"/"DROP" 造成假红 */
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
