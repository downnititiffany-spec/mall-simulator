package com.graduation.analytics.migration;

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
 * G31-11（总控 D-048 §(5) M3 发布语义 / D-049a+e+f）的迁移脚本门禁：
 * **只读静态检查，不连库、不执行任何 DDL**。
 *
 * <p>钉住 V33（批次消费台账 + {@code pipeline_run.recalc_reason}）三件事：</p>
 * <ol>
 *   <li><b>号位不冲突</b>：33 号在 {@code db/meta} 内唯一（重复会让 Flyway 拒绝启动）；</li>
 *   <li><b>台账唯一性与回填守卫</b>：{@code pipeline_batch_consumption} 以
 *       UK {@code (source_id, batch_id)} 一批次一源一行；回填只认 SUCCESS 且
 *       input_batch_id/source_id 双非空的 run（既成事实，不猜），且
 *       {@code ON DUPLICATE KEY UPDATE id=id} 幂等——重复执行或与 PIPELINE 写入撞行
 *       不得覆盖既有行（D-049f：保护正式锚 S20260901_23 的 ACTIVE 快照不漂移）；</li>
 *   <li><b>重算理由留痕列</b>：{@code pipeline_run.recalc_reason} 为单条可空列——
 *       理由必填（空理由 400 PARAM_INVALID）是应用层契约，DB 侧 NULL 只表示
 *       「非重算 run」，因此列必须可空、且全脚本不得再动 pipeline_run 其它列。</li>
 * </ol>
 *
 * <p><b>本类能证明什么、不能证明什么</b>：只证明「脚本不可能写成别样」。
 * 「在真库上确实这样生效」**本类不证明** —— 按 DB 冻结约束（3306 永久冻结红线），
 * 本泳道未对 3306 执行任何 DDL/DML，V33 **未在 3306 上执行**（脚本头部已写明）；
 * 落地路径是隔离库演练 + V32 正式库 analytics_meta 的 Flyway 迁移。</p>
 */
class PipelineBatchConsumptionMigrationScriptTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    private static final String V33 = "V33__pipeline_batch_consumption.sql";

    /** 台账核心列：缺任一列，M3 语义②「消费状态独立记录」就没有落点。 */
    private static final List<String> LEDGER_COLUMNS = List.of(
            "source_id", "batch_id", "status", "consumed_by_run_id", "first_consumed_by_run_id",
            "target_snapshot_id", "publish_count", "recalc_count", "last_recalc_reason",
            "last_recalc_by", "last_recalc_at", "consumed_at", "created_via");

    @Test
    @DisplayName("迁移号不冲突：V33 存在，且 db/meta 内号位不重复")
    void migrationVersion33IsAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions).as("db/meta 下应有迁移脚本").isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("G31-11 的消费台账迁移号必须存在；改号/删号必在此处变红")
                .contains(33);
        assertThat(Files.isRegularFile(META_DIR.resolve(V33))).as("%s 必须存在", V33).isTrue();
    }

    @Test
    @DisplayName("V33 建台账表：UK (source_id, batch_id) 一批次一源一行，核心列齐备")
    void v33CreatesConsumptionLedgerWithSourceBatchUniqueness() {
        String sql = code(V33);

        assertThat(countMatches(sql, "(?i)create\\s+table\\s+pipeline_batch_consumption\\b"))
                .as("V33 应恰好建一次 pipeline_batch_consumption（一次迁移一件事）")
                .isEqualTo(1);
        assertThat(sql)
                .as("UK (source_id, batch_id)：同一批次同一源只允许一行消费事实——"
                        + "这是「无新输入不发布」与「失败批次保持待处理」两条判定的去重边界")
                .containsPattern("(?i)unique\\s+key\\s+uk_batch_consumption"
                        + "\\s*\\(\\s*source_id\\s*,\\s*batch_id\\s*\\)");
        for (String column : LEDGER_COLUMNS) {
            assertThat(sql)
                    .as("台账必须声明 %s（M3 语义②的落点列）", column)
                    .containsPattern("(?i)\\b" + Pattern.quote(column) + "\\b");
        }
        assertThat(sql)
                .as("status 唯一态 CONSUMED：不存在的事实在本表没有行，而不是另立「待处理」状态")
                .containsPattern("(?i)status\\s+VARCHAR\\(\\d+\\)\\s+NOT\\s+NULL\\s+DEFAULT\\s+'CONSUMED'");
        assertThat(sql)
                .as("created_via 默认 PIPELINE；回填行用 BACKFILL_V33 区分既成事实与流水线写入")
                .containsPattern("(?i)created_via\\s+VARCHAR\\(\\d+\\)\\s+NOT\\s+NULL\\s+DEFAULT\\s+'PIPELINE'");
    }

    @Test
    @DisplayName("V33 给 pipeline_run 加且仅加一条可空 recalc_reason（D-049e 理由留痕）")
    void v33AddsSingleNullableRecalcReasonColumn() {
        String sql = code(V33);

        assertThat(countMatches(sql, "(?i)alter\\s+table\\s+pipeline_run\\b"))
                .as("V33 对 pipeline_run 只应有一次 ALTER")
                .isEqualTo(1);
        assertThat(sql)
                .as("recalc_reason 必须是 VARCHAR(500) NULL（理由必填是应用层 400 契约；"
                        + "DB 侧 NULL 只表示「非重算 run」）")
                .containsPattern("(?is)add\\s+column\\s+recalc_reason\\s+VARCHAR\\(500\\)\\s+null\\b");
        assertThat(sql)
                .as("recalc_reason 必须紧跟 source_data_version，位置稳定便于回滚对照")
                .containsPattern("(?i)after\\s+source_data_version");
        assertThat(countMatches(sql, "(?i)add\\s+column"))
                .as("V33 全脚本只允许这一处 ADD COLUMN")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V33 回填双守卫 + 幂等：只回填 SUCCESS 且输入批次/来源明确的 run，撞行不覆盖")
    void v33BackfillIsGuardedAndIdempotent() {
        String sql = code(V33);

        assertThat(sql)
                .as("必须有历史回填（D-049f）：否则 V33 上线后已发布批次会被 FIFO 重新消费造成重发布")
                .containsPattern("(?is)insert\\s+into\\s+pipeline_batch_consumption\\b.*\\bselect\\b");
        assertThat(sql)
                .as("回填守卫①：只认 SUCCESS run——FAILED/中途失败的 run 从未发布，不是消费事实")
                .containsPattern("(?i)r\\.status\\s*=\\s*'SUCCESS'");
        assertThat(sql)
                .as("回填守卫②③：input_batch_id 与 source_id 双非空才回填，NULL 不猜")
                .containsPattern("(?i)r\\.input_batch_id\\s+is\\s+not\\s+null")
                .containsPattern("(?i)r\\.source_id\\s+is\\s+not\\s+null");
        assertThat(sql)
                .as("幂等（D-049f）：ON DUPLICATE KEY UPDATE id=id 撞行不覆盖——"
                        + "重复执行/与 PIPELINE 写入撞行都不得改写既有消费事实")
                .containsPattern("(?is)on\\s+duplicate\\s+key\\s+update\\s+id\\s*="
                        + "\\s*pipeline_batch_consumption\\.id");
        assertThat(sql)
                .as("回填行必须带 BACKFILL_V33 溯源标记，与 PIPELINE 写入可区分")
                .contains("'BACKFILL_V33'");
    }

    @Test
    @DisplayName("V33 恰好三条语句，且头部写明 3306 冻结披露、消费语义与回滚路径")
    void v33StatementCountAndDbFreezeDisclosure() {
        assertThat(countMatches(code(V33), ";"))
                .as("V33 应恰好三条语句：建台账、加 recalc_reason、幂等回填")
                .isEqualTo(3);

        String script = read(V33);
        assertThat(script)
                .as("必须写明未在 3306 上执行（3306 永久冻结红线），否则会被误读为已在真库验证")
                .contains("未在 3306 上执行");
        assertThat(script)
                .as("必须写明消费状态独立记录的语义（既非 READY 判定、亦非值相等去重）")
                .contains("独立记录")
                .contains("值相等去重");
        assertThat(script)
                .as("必须写明回滚路径（DROP TABLE / DROP COLUMN），且回滚前先回放导出事实")
                .contains("DROP TABLE pipeline_batch_consumption")
                .contains("DROP COLUMN recalc_reason");
    }

    // ── 读取与解析（与 QualityRuleVersionMigrationScriptTest 同款） ──────────────

    private static String read(String scriptName) {
        try {
            return Files.readString(META_DIR.resolve(scriptName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + scriptName + " 失败（G31-11 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免注释里的字眼造成假红。 */
    private static String code(String scriptName) {
        return read(scriptName).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    private static int countMatches(String text, String regexOrLiteral) {
        return (int) Pattern.compile(regexOrLiteral).matcher(text).results().count();
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
