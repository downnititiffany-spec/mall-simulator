package com.graduation.analytics.migration;

import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.QualityRuleDefinition;
import com.graduation.analytics.metric.RuleSeverity;
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
 * F-88 / V25-Q01 的迁移脚本门禁：**只读静态检查，不连库、不执行任何 DDL**。
 *
 * <p>钉住 V19（规则定义表 + 种子）与 V20（结果行版本列）两件事：</p>
 * <ol>
 *   <li><b>号位不冲突</b>：{@code db/meta} 内迁移号不重复（重复会让 Flyway 拒绝启动）；</li>
 *   <li><b>种子与 Java 目录同源</b>：V19 的 35 行种子逐字段等于
 *       {@link QualityRuleCatalog#definitions()} —— 这条是本门禁的**核心**。
 *       若允许 SQL 里再写一份手抄档位，就会产生「第二份严重度所有者」，
 *       与 {@code RuleSeverity} 的单一所有者直接冲突；本用例使二者不可能漂移。</li>
 *   <li><b>V20 只加可空列、不回填</b>：四列必须 NULL 可空、无 DEFAULT，且执行语句里
 *       不得出现 UPDATE/INSERT → 历史行留 NULL。总控裁决要求「严禁回填任何猜测值」，
 *       回填会让下游把猜测读成事实。本用例是那条裁决的机器化守卫。</li>
 * </ol>
 *
 * <p><b>本类能证明什么、不能证明什么</b>：只证明「脚本不可能写成别样」。
 * 「在真库上确实这样生效」**本类不证明** —— 按 DB 冻结约束，本泳道未对 3306 执行任何
 * DDL/DML，V19/V20 均**未在真库执行**（两脚本头部均已写明）。</p>
 */
class QualityRuleVersionMigrationScriptTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");

    private static final String V19 = "V19__quality_rule_definition.sql";
    private static final String V20 = "V20__data_quality_result_rule_version.sql";

    /** V20 的四个新列（顺序即脚本内的声明顺序）。 */
    private static final List<String> V20_COLUMNS = List.of(
            "rule_version", "effective_severity", "compat_policy_version", "rule_fingerprint");

    /** 「历史行无版本信息」的判读禁令，必须逐列出现。 */
    private static final String NO_VERSION_WARNING = "本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS";

    /** V19 种子行：11 个字段，字符串字面量与 NULL 混合。 */
    private static final Pattern SEED_ROW = Pattern.compile(
            "(?m)^\\s*\\(\\s*'([A-Z0-9_]+)'\\s*,\\s*(\\d+)\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,"
                    + "\\s*'([A-Z]+)'\\s*,\\s*'([A-Z_]+)'\\s*,\\s*(NULL|'([^']*)')\\s*,"
                    + "\\s*(\\d+)\\s*,\\s*(NULL|'([^']*)')\\s*,\\s*(NULL|'([^']*)')\\s*,"
                    + "\\s*'([0-9a-f]{64})'\\s*\\)");

    @Test
    @DisplayName("迁移号由总控分配且不冲突：V19/V20 存在，且 db/meta 内号位不重复")
    void migrationVersionsAreAssignedAndUnique() {
        List<Integer> versions = scriptVersions();
        assertThat(versions).as("db/meta 下应有迁移脚本").isNotEmpty();
        assertThat(versions)
                .as("同一迁移号不允许出现两次（Flyway 会拒绝启动）：%s", versions)
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("F-88/V25-Q01 的两个迁移号必须存在；改号/删号必在此处变红")
                .contains(19, 20);
        assertThat(Files.isRegularFile(META_DIR.resolve(V19))).as("%s 必须存在", V19).isTrue();
        assertThat(Files.isRegularFile(META_DIR.resolve(V20))).as("%s 必须存在", V20).isTrue();
    }

    @Test
    @DisplayName("V20 只加 4 个可空列：无 NOT NULL、无 DEFAULT、无 UPDATE/INSERT（历史行留 NULL）")
    void v20AddsOnlyNullableColumnsAndNeverBackfills() {
        String sql = code(V20);

        for (String column : V20_COLUMNS) {
            assertThat(sql)
                    .as("V20 必须声明 %s（ADD COLUMN 且显式 NULL）", column)
                    .containsPattern("(?is)add\\s+column\\s+" + column + "\\s+\\w+(\\(\\d+\\))?\\s+null\\b");
        }
        assertThat(countMatches(sql, "(?i)add\\s+column"))
                .as("V20 只应新增这 4 列，多一列即越界")
                .isEqualTo(V20_COLUMNS.size());
        assertThat(sql)
                .as("四列必须全部可空：出现 NOT NULL 会让既有 467 行无法满足约束")
                .doesNotContainPattern("(?i)not\\s+null");
        // 注意：不能用「全文不得出现 DEFAULT」来钉这一条 —— 脚本的 COMMENT 里刻意写了
        // 「不设 DEFAULT」作为说明，全文匹配会把说明本身判成违规（首轮实测踩到）。
        // 因此改为钉**列定义的结构**：每个 ADD COLUMN 的类型之后必须直接是 NULL + COMMENT，
        // in-between 容不下 `DEFAULT x` 或 `NOT NULL`，二者都会被这条挡住。
        for (String column : V20_COLUMNS) {
            assertThat(sql)
                    .as("%s 的类型之后必须是 NULL 紧跟 COMMENT；中间若插入 DEFAULT/NOT NULL 本断言即红", column)
                    .containsPattern("(?is)add\\s+column\\s+" + column
                            + "\\s+\\w+(\\(\\d+\\))?\\s+null\\s+comment\\s");
        }
        assertThat(sql)
                .as("严禁回填任何猜测值（总控裁决）：执行语句里不得出现 UPDATE/INSERT/DELETE")
                .doesNotContainPattern("(?i)\\b(update|insert|delete|replace)\\b");
        assertThat(sql)
                .as("V20 只加列，不得改动既有列定义")
                .doesNotContainPattern("(?i)\\b(modify|change)\\s+column\\b")
                .doesNotContainPattern("(?i)\\bdrop\\b");
        assertThat(countMatches(sql, ";"))
                .as("V20 应恰好是一条语句（一次迁移一件事）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V20 四列逐列写明「无版本信息，不得解读为 WARN/PASS」，且声明本轮未在真库执行")
    void v20DocumentsNullMeaningAndUnappliedState() {
        String script = read(V20);

        for (String column : V20_COLUMNS) {
            assertThat(columnOccurrences(script, column))
                    .as("%s 必须逐列带上无版本信息的判读禁令（避免 NULL 被读成 WARN/PASS）", column)
                    .isPositive();
        }
        assertThat(countMatches(script, Pattern.quote(NO_VERSION_WARNING)))
                .as("判读禁令应至少覆盖 4 列（实测出现次数）")
                .isGreaterThanOrEqualTo(V20_COLUMNS.size());
        assertThat(script)
                .as("必须写明本轮未在真库执行（DB 冻结），否则会被误读为已验证")
                .contains("未在 3306 上执行");
    }

    @Test
    @DisplayName("V19 建表对齐 §7.3.1 line 520，且唯一键是 (source_scope, rule_code, version)")
    void v19DeclaresTheContractTableAndUniqueKey() {
        String sql = code(V19);

        assertThat(countMatches(sql, "(?i)create\\s+table"))
                .as("V19 只应新建一张表")
                .isEqualTo(1);
        for (String required : List.of("rule_code", "version", "source_scope", "stage", "severity",
                "severity_mode", "threshold_json", "enabled", "effective_from", "effective_to", "checksum")) {
            assertThat(sql)
                    .as("§7.3.1 line 520 要求的契约字段 %s 必须在建表语句里", required)
                    .containsPattern("(?m)^\\s*" + required + "\\s+");
        }
        assertThat(sql)
                .as("唯一键必须是 (source_scope, rule_code, version)（D-10：不新增 source_id 列）")
                .containsPattern("(?is)unique\\s+key\\s+\\w*\\s*\\(\\s*source_scope\\s*,\\s*rule_code\\s*,\\s*version\\s*\\)");
        assertThat(sql)
                .as("V19 只建表 + 种子，不得改动任何既有表")
                .doesNotContainPattern("(?i)alter\\s+table\\s+(?!quality_rule_definition)")
                .doesNotContainPattern("(?i)\\bdrop\\b");
    }

    @Test
    @DisplayName("V19 种子逐字段等于 Java 目录：35 行、version 全 1、档位/模式/阈值/指纹零漂移")
    void v19SeedMatchesTheJavaCatalogExactly() {
        List<QualityRuleDefinition> definitions = QualityRuleCatalog.DEFAULT.definitions();
        assertThat(definitions)
                .as("目录契约全集应为 35 条（本用例是 SQL 与 Java 的对账点，改数必在此处变红）")
                .hasSize(35);

        List<String> seed = seedRows();
        assertThat(seed)
                .as("V19 种子行数必须等于目录定义数 —— 少登记一个码，该码一上线就会被读侧判为「未登记」而停止发布")
                .hasSameSizeAs(definitions);

        List<String> expected = new ArrayList<>();
        for (QualityRuleDefinition d : definitions) {
            expected.add(String.join("|",
                    d.ruleCode(), String.valueOf(d.version()), d.sourceScope(), d.stage(),
                    d.severity(), d.severityMode().name(),
                    d.thresholdJson() == null ? "" : d.thresholdJson(),
                    d.enabled() ? "1" : "0",
                    d.effectiveFrom() == null ? "" : d.effectiveFrom(),
                    d.effectiveTo() == null ? "" : d.effectiveTo(),
                    d.checksum()));
        }
        List<String> sortedExpected = new ArrayList<>(expected);
        List<String> sortedActual = new ArrayList<>(seed);
        sortedExpected.sort(String::compareTo);
        sortedActual.sort(String::compareTo);

        assertThat(sortedActual)
                .as("V19 种子与 QualityRuleCatalog.DEFAULT 必须零漂移（顺序无关）；"
                        + "差异行即「第二份严重度口径」的入口")
                .containsExactlyElementsOf(sortedExpected);

        // version 必须全为 1（本版只做既有码的登记，不借登记之名改档）
        assertThat(seed.stream().map(row -> row.split("\\|", -1)[1]).distinct().toList())
                .as("V19 是「既有规则的版本化登记」，version 必须全为 1；改档位必须发新 version 并显式裁决")
                .containsExactly("1");
    }

    @Test
    @DisplayName("种子档位取值域合法：THRESHOLD_OBSERVATION 必为 WARN 且带阈值，FIXED 无此约束")
    void seedSeverityModesSatisfyTheirInvariants() {
        List<String> seed = seedRows();
        assertThat(seed).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (String row : seed) {
            String[] f = row.split("\\|", -1);
            String code = f[0];
            String severity = f[4];
            String mode = f[5];
            String threshold = f[6];

            if (!RuleSeverity.isKnownSeverity(severity)) {
                violations.add(code + " 档位越界: " + severity);
            }
            if (!"FIXED".equals(mode) && !"THRESHOLD_OBSERVATION".equals(mode)) {
                violations.add(code + " severity_mode 越界: " + mode);
            }
            if ("THRESHOLD_OBSERVATION".equals(mode)) {
                if (!RuleSeverity.WARN.equals(severity)) {
                    violations.add(code + " THRESHOLD_OBSERVATION 的基准档必须是 WARN（观察项档），实为 " + severity);
                }
                if (threshold.isBlank()) {
                    violations.add(code + " THRESHOLD_OBSERVATION 必须给 thresholdJson");
                }
            }
        }
        assertThat(violations)
                .as("种子行违反 QualityRuleDefinition 紧凑构造器的不变式；这些行会让目录加载时直接抛错")
                .isEmpty();
    }

    // ── 读取与解析 ───────────────────────────────────────────────────────────────

    private static String read(String scriptName) {
        try {
            return Files.readString(META_DIR.resolve(scriptName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + scriptName + " 失败（F-88 迁移尚未落盘？）", e);
        }
    }

    /** 去掉注释后的 SQL：门禁只看真正会执行的语句，避免注释里的字眼造成假红。 */
    private static String code(String scriptName) {
        return read(scriptName).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    /** 解析 V19 的种子行，输出 {@code 11 个字段以 | 连接} 的规范化文本（NULL 与缺省统一为空串）。 */
    private static List<String> seedRows() {
        String sql = code(V19);
        // 只在 VALUES 之后找行，避免把其它小括号结构误当种子
        int valuesAt = sql.toUpperCase(java.util.Locale.ROOT).indexOf("VALUES");
        assertThat(valuesAt).as("V19 应有 VALUES 子句").isNotNegative();
        String tail = sql.substring(valuesAt);

        Matcher m = SEED_ROW.matcher(tail);
        List<String> rows = new ArrayList<>();
        while (m.find()) {
            String threshold = m.group(8) == null ? "" : m.group(8);
            String from = m.group(11) == null ? "" : m.group(11);
            String to = m.group(13) == null ? "" : m.group(13);
            rows.add(String.join("|",
                    m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6),
                    threshold, m.group(9), from, to, m.group(14)));
        }
        assertThat(rows)
                .as("V19 的 VALUES 段应解析出 35 行种子；解析到 %d 行说明格式已被改动", rows.size())
                .isNotEmpty();
        return rows;
    }

    /** 统计某列在脚本全文（含注释）中作为列名出现的次数。 */
    private static int columnOccurrences(String script, String column) {
        return countMatches(script, "(?i)\\b" + Pattern.quote(column) + "\\b");
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
